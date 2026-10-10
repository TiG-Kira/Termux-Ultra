package com.termux.app.vortex

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * VorteX 沙箱核心管理器。
 *
 * VorteX 沙箱是一个与 Termux 真实用户环境**隔离**、但又**包含当前用户全部环境**的测试环境：
 *
 * - 沙箱内的所有修改（文件写入、配置变更、乃至虚拟 ROOT 下的操作）都局限于沙箱内部，
 *   不会泄漏到真实用户环境；
 * - 会话结束时沙箱自动重置到最小化初始快照，不留残余；
 * - 沙箱提供**虚拟 ROOT 权限**——即便当前并非 ROOT 用户，也能以 uid 0 的视角执行命令，
 *   用于预演危险脚本或正式应用上线前的演练。
 *
 * 目录结构（位于应用私有 files 目录，与 Termux shell 同 UID 可访问）：
 * ```
 *   <filesDir>/vortex/
 *     vortex_sandbox.sh   # 引导脚本（从 assets 解出的可执行脚本）
 *     snapshot/home/      # 最小化初始快照（开启总开关时从当前 $HOME 抓取）
 *     run/home/           # 当前会话可写层；会话结束被重置回 snapshot
 * ```
 *
 * 总开关、Agent 授权、插件开关三套状态由 [VorteXSandboxPrefs] 持久化。
 */
object VorteXSandbox {

    private const val ASSET_BOOTSTRAP = "vortex_sandbox.sh"

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /** 沙箱根目录：`<filesDir>/vortex`。 */
    fun getRootDir(context: Context): File =
        File(context.filesDir, "vortex").also { it.mkdirs() }

    /** 引导脚本可执行文件。 */
    fun getBootstrapExecutable(context: Context): File =
        File(getRootDir(context), ASSET_BOOTSTRAP)

    /** 最小化初始快照目录（会话重置目标）。 */
    fun getSnapshotHome(context: Context): File =
        File(getRootDir(context), "snapshot/home").also { it.mkdirs() }

    /** 当前会话可写层目录（沙箱内 $HOME 实际指向这里）。 */
    fun getRunHome(context: Context): File =
        File(getRootDir(context), "run/home").also { it.mkdirs() }

    /** 内存储影子层（对应真实 `/storage/emulated/0`）。 */
    fun getRunStorage(context: Context): File =
        File(getRootDir(context), "run/storage").also { it.mkdirs() }

    /** 内存储快照目录。 */
    fun getSnapshotStorage(context: Context): File =
        File(getRootDir(context), "snapshot/storage").also { it.mkdirs() }

    /**
     * Agent 运行时根目录：当 Termux Agent 被授权使用沙箱时，其 ShellTool 的工作目录
     * 指向此处，从而把 Agent 的产物（下载、生成文件）也收束在沙箱内。
     */
    fun getAgentSandboxRoot(context: Context): File {
        ensureInitialized(context)
        return getRunHome(context)
    }

    // ------------------------------------------------------------------
    // 开关状态（委托给 VorteXSandboxPrefs）
    // ------------------------------------------------------------------

    fun isEnabled(context: Context): Boolean = VorteXSandboxPrefs.isEnabled(context)

    /**
     * 开启/关闭总开关。开启时初始化沙箱并保留最小化初始快照；关闭时回收 Agent 授权。
     *
     * 注意：初始化可能涉及数十 MB 的磁盘 IO，**绝不能**在调用方（设置页开关回调，运行在
     * UI 线程）同步执行——那会直接 ANR 闪退。因此这里只落盘开关状态并立即返回，
     * 重活交给后台线程。开关瞬间生效与否无关紧要：进入沙箱时脚本还会再校验一次。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        VorteXSandboxPrefs.setEnabled(context, enabled)
        if (enabled) {
            ensureInitializedAsync(context)
        } else {
            // 总开关关闭后，Agent / 插件均不可再使用沙箱。
            VorteXSandboxPrefs.setAgentAuthorized(context, false)
            // 关闭时顺手丢弃残缺的快照。
            //
            // 为什么必须丢：首次开启若因 ANR 闪退而夭折，snapshot 会停在半成品状态
            // （拷贝到一半被杀）。它没有完成标记，但**目录非空**——若保留下来，
            // 下次开启会走「快照已就绪」的快速路径，用户拿到的仍是那个缺了一堆
            // 文件的环境，且没有任何途径恢复。这里显式删除，让下次开启从头重建。
            if (!isSnapshotComplete(context)) {
                try {
                    getSnapshotHome(context).deleteRecursively()
                } catch (_: Throwable) {
                }
            }
            // run 层是临时空间，关闭即清。
            try {
                File(getRootDir(context), "run").deleteRecursively()
            } catch (_: Throwable) {
            }
        }
    }

    fun isAgentAuthorized(context: Context): Boolean =
        isEnabled(context) && VorteXSandboxPrefs.isAgentAuthorized(context)

    fun setAgentAuthorized(context: Context, authorized: Boolean) {
        if (!isEnabled(context)) {
            VorteXSandboxPrefs.setAgentAuthorized(context, false)
            return
        }
        VorteXSandboxPrefs.setAgentAuthorized(context, authorized)
    }

    fun isPluginSandboxEnabled(context: Context, pluginId: String): Boolean =
        isEnabled(context) && VorteXSandboxPrefs.isPluginSandboxEnabled(context, pluginId)

    fun setPluginSandboxEnabled(context: Context, pluginId: String, enabled: Boolean) {
        VorteXSandboxPrefs.setPluginSandboxEnabled(context, pluginId, enabled && isEnabled(context))
    }

    /** 仅当总开关开启且插件开关开启时返回 true——给插件执行路径快速判断用。 */
    fun shouldPluginUseSandbox(context: Context, pluginId: String): Boolean =
        isPluginSandboxEnabled(context, pluginId)

    // ------------------------------------------------------------------
    // 初始化 / 快照 / 重置
    // ------------------------------------------------------------------

    /**
     * 快照完成标记文件。
     *
     * 为什么必须要标记：过去用「snapshot 目录非空」判断快照是否完成，
     * 但拷贝中途被杀死（ANR 闪退 / 应用被杀）会留下**半成品目录**——它非空，
     * 于是被误判为完成，之后永远不再重抓。用户表现为「开了沙箱后 .bashrc 引用的
     * 若干脚本凭空消失，且再开关一次也恢复不了」，因为坏快照已经固化。
     *
     * 只有拷贝全部成功才落这个标记；检测到目录非空但无标记 → 判定为残缺并重抓。
     */
    private const val SNAPSHOT_MARKER = ".vortex_snapshot_ok"

    private fun snapshotMarker(context: Context) = File(getSnapshotHome(context), SNAPSHOT_MARKER)

    /** 快照是否完整可用。 */
    fun isSnapshotComplete(context: Context): Boolean = snapshotMarker(context).exists()

    /**
     * 确保沙箱已就位：解压引导脚本、建立目录、并在快照缺失或残缺时重抓初始快照。
     *
     * 幂等，且**可自愈**：半成品快照会被识别并重新抓取（而不是像旧实现那样
     * 把残缺状态当成完成，从此再也修复不了）。
     *
     * ⚠️ 本方法含全量磁盘 IO（拷贝整个 $HOME），必须在后台线程调用。
     */
    fun ensureInitialized(context: Context) {
        getRootDir(context)
        val bootstrap = getBootstrapExecutable(context)
        if (!bootstrap.exists() || bootstrap.length() == 0L) {
            extractBootstrap(context, bootstrap)
        }
        val snapshotHome = getSnapshotHome(context)
        if (!isSnapshotComplete(context)) {
            // 目录非空却无完成标记 = 上次拷贝中途夭折的残缺快照，先清干净再重抓，
            // 否则 cp 会把新旧内容混在一起，更难收拾。
            if (snapshotHome.list().isNullOrEmpty().not()) {
                try {
                    snapshotHome.deleteRecursively()
                } catch (_: Throwable) {
                }
                snapshotHome.mkdirs()
            }
            takeSnapshot(context)
        }
        getRunHome(context).mkdirs()
    }

    /**
     * 后台执行 [ensureInitialized]，避免调用方（尤其是设置页开关回调）被磁盘 IO 阻塞。
     * 已在初始化中时直接返回，重复调用安全。
     */
    @Volatile
    private var initializing = false

    private val initLock = Any()

    fun ensureInitializedAsync(context: Context) {
        synchronized(initLock) {
            if (initializing) return
            initializing = true
        }
        val appContext = context.applicationContext
        Thread({
            try {
                ensureInitialized(appContext)
            } catch (_: Throwable) {
                // 失败不阻断开关本身；下次进入沙箱时 prepareAsync 会再试一次
            } finally {
                synchronized(initLock) { initializing = false }
            }
        }, "vortex-sandbox-init").apply { isDaemon = true }.start()
    }

    /** 从 assets 解压引导脚本并赋予可执行权限。 */
    private fun extractBootstrap(context: Context, target: File) {
        try {
            context.assets.open(ASSET_BOOTSTRAP).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true)
        } catch (_: Throwable) {
            // 解压失败则退化为普通文件；引导脚本内部以 sh 显式调用自身，不强制 +x。
        }
    }

    /**
     * 抓取当前用户环境为最小化初始快照。
     *
     * **只有拷贝全部成功才写完成标记**——中途夭折（ANR 闪退、应用被杀、磁盘满）时
     * 不落标记，让下一次 [ensureInitialized] 能识别出这是残缺快照并重新抓。
     * 这正是「开了沙箱闪退一次之后功能彻底崩掉」的根因修复点。
     *
     * 注意：这里复制的是**用户数据**（$HOME），不复制 `$PREFIX`（usr/bin、lib）——
     * 沙箱运行时由引导脚本 cp -a 出独立的 $PREFIX 影子层。
     */
    fun takeSnapshot(context: Context) {
        val src = TermuxConstants.TERMUX_HOME_DIR_PATH
        val dst = getSnapshotHome(context).absolutePath
        val marker = snapshotMarker(context)
        // 先清掉旧标记：本次拷贝成功前，快照一律视为不可用。
        try { marker.delete() } catch (_: Throwable) {}

        val ok = try {
            // 用 tar 管道而非 cp -a，目的有两个：
            //
            // 1) **显式排除 storage**。Termux 的 $HOME/storage 是指向
            //    /storage/emulated/0 的符号链接，一旦被穿透就会把用户的整个内存储
            //    拖进快照（可达数 GB）。`cp -a` 通常会原样保留软链，但一旦目标
            //    环境对软链的处理不一致（部分 Android 版本 / FUSE 层），就可能穿透。
            //    这里从源侧就把它排除掉，不把正确性押在软链语义上。
            // 2) 退出码可靠反映成功与否，供上面写完成标记用。
            val cmd = "tar -C '$src' --exclude=./storage -cf - . 2>/dev/null | tar -C '$dst' -xf - 2>/dev/null"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor() == 0
        } catch (_: Throwable) {
            // 兜底也必须排除 storage：copyRecursively 会**跟随符号链接**，
            // 直接把 /storage/emulated/0 的几十 GB 拖进快照，且这还是在主线程上。
            try {
                val srcFile = File(src)
                srcFile.listFiles()?.forEach { child ->
                    if (child.name == "storage") return@forEach
                    val target = File(dst, child.name)
                    try {
                        if (child.isDirectory) child.copyRecursively(target, overwrite = true)
                        else child.copyTo(target, overwrite = true)
                    } catch (_: Throwable) {
                    }
                }
                true
            } catch (_: Throwable) {
                false
            }
        }
        takeStorageSnapshot(context)
        if (ok) {
            try { marker.createNewFile() } catch (_: Throwable) {}
        }
    }

    /**
     * 抓取内存储（`/storage/emulated/0`）为快照。
     *
     * **只记录顶层目录名，一个文件都不复制。**
     *
     * 曾经这里对顶层**文件**也调`copyTo`，看起来只是「拷贝顶层」很轻，
     * 实际是灾难：用户内存储顶层散落着大量大文件（安装包、压缩包、影音、
     * 文档，动辄单个几百 MB，累计可达数 GB），而 `takeSnapshot()` 是在
     * `setEnabled()` 的 UI 线程里同步调用的——这意味着**点一下开关就要在主线程
     * 同步复制几个 GB**，必然 ANR 闪退，且拷到一半被杀留下半成品快照，
     * 进而引发「沙箱环境缺文件且永不恢复」的问题。
     *
     * 沙箱的定位是「用户 Termux 环境的隔离预演」，不需要复制用户的内存储内容。
     * 真正的播种交给引导脚本按需进行（它同样只建空目录占位）。
     */
    private fun takeStorageSnapshot(context: Context) {
        val real = File("/storage/emulated/0")
        val snap = getSnapshotStorage(context)
        try {
            if (!real.isDirectory) return
            // 仅占位：建空目录即可，不递归、不复制任何文件内容。
            real.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    val target = File(snap, child.name)
                    if (!target.exists()) target.mkdirs()
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 探测当前环境是否具备 proot（决定沙箱是「绝对路径级隔离」还是「仅 $HOME 隔离」）。
     * 结果做短时缓存，避免设置页每次重组都起子进程。
     */
    @Volatile
    private var prootAvailableCache: Boolean? = null

    fun isProotAvailable(context: Context): Boolean {
        prootAvailableCache?.let { return it }
        val result = try {
            val p = ProcessBuilder(
                "sh", "-c",
                "command -v proot >/dev/null 2>&1 && echo yes || echo no"
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor()
            out.contains("yes")
        } catch (_: Throwable) {
            false
        }
        prootAvailableCache = result
        return result
    }

    /** 沙箱当前隔离能力的可读描述，用于设置页副标题与自检。 */
    fun isolationSummary(context: Context): String =
        if (isProotAvailable(context)) "proot 隔离：绝对路径亦受保护"
        else "轻量隔离：请先 pkg install proot"

    /** 将当前会话可写层重置回最小化初始快照（供调试/手动重置调用）。 */
    fun resetSandbox(context: Context) {
        val runHome = getRunHome(context)
        try {
            runHome.deleteRecursively()
        } catch (_: Throwable) {
        }
        runHome.mkdirs()
        try {
            val snap = getSnapshotHome(context).absolutePath
            val cmd = "cp -a '$snap/.' '${runHome.absolutePath}/' 2>/dev/null || true"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
        } catch (_: Throwable) {
        }
        // 内存储影子层同样重置
        try {
            val runStorage = getRunStorage(context)
            runStorage.deleteRecursively()
            runStorage.mkdirs()
            val snapStorage = getSnapshotStorage(context).absolutePath
            Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "cp -a '$snapStorage/.' '${runStorage.absolutePath}/' 2>/dev/null || true")
            ).waitFor()
        } catch (_: Throwable) {
        }
        // $PREFIX 影子层：整目录删掉即可（引导脚本会 cp -a 重建一份真实拷贝）。
        // 必须是真实拷贝而非硬链接——硬链接共享 inode，沙箱内改写文件会写穿到真实环境，
        // 违背「所有改动在会话完全结束后消失」这一硬要求。
        // 必须删——否则沙箱里装的包会残留到下一个会话。
        try {
            File(getRootDir(context), "run/usr").deleteRecursively()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // 预热（准备中提示）与彻底清理
    // ------------------------------------------------------------------

    /** 影子 prefix 目录：约 95MB 的真实拷贝，是沙箱启动延迟的唯一来源。 */
    private fun runPrefixDir(context: Context) = File(getRootDir(context), "run/usr")

    /** 影子 prefix 拷贝完成标记——语义同 [SNAPSHOT_MARKER]。 */
    private const val PREFIX_MARKER = ".vortex_prefix_ok"

    /**
     * 沙箱是否已就绪（影子 prefix 已存在**且完整**）。
     *
     * 必须靠标记文件而非「目录非空」：拷贝中途夭折会留下半成品目录，
     * 同样会被误判为就绪，之后再也不会重试——这正是用户反馈的
     * 「首次开启闪退后功能彻底崩掉」。
     */
    fun isPrepared(context: Context): Boolean {
        val dir = runPrefixDir(context)
        return dir.isDirectory && File(dir, PREFIX_MARKER).exists()
    }

    /**
     * 预热沙箱：把「拷贝 $PREFIX 到影子层」这一步提前做掉，让后续进入沙箱时几乎无延迟。
     *
     * 必须放后台线程——这是 95MB 级的磁盘 IO，跑在主线程会 ANR。
     * 已在准备中时直接回调 [onReady]，重复调用安全。
     *
     * @param onReady 准备完成回调（可能在任意线程触发，调用方需自行切回主线程）
     */
    @Volatile
    private var preparing = false

    /** 预热进行中时的等待者，prepareAsync 重入时统一回调。 */
    private val prepareWaiters = mutableListOf<() -> Unit>()

    fun prepareAsync(context: Context, onReady: () -> Unit) {
        if (isPrepared(context)) {
            onReady()
            return
        }
        synchronized(this) {
            // 重入时不能直接 return——否则第二次调用的 onReady 永远不触发，
            // UI 会永久停在「准备中」。改为登记为等待者，由首个任务完成后统一回调。
            prepareWaiters.add(onReady)
            if (preparing) return
            preparing = true
        }
        val appContext = context.applicationContext
        Thread({
            try {
                ensureInitialized(appContext)
                prepareShadowPrefix(appContext)
            } catch (_: Throwable) {
                // 预热失败不阻断：真正进入沙箱时引导脚本还会再试一次
            } finally {
                val callbacks = synchronized(this) {
                    preparing = false
                    val c = prepareWaiters.toList()
                    prepareWaiters.clear()
                    c
                }
                callbacks.forEach { it() }
            }
        }, "vortex-sandbox-prepare").apply { isDaemon = true }.start()
    }

    /**
     * 同步准备影子 prefix（供引导脚本之外的路径调用）。
     * 幂等：已完整存在则直接返回；残缺则清掉重来。
     *
     * ⚠️ 含 95MB 级磁盘 IO，必须在后台线程调用（见 [prepareAsync]）。
     */
    fun prepareShadowPrefix(context: Context) {
        val target = runPrefixDir(context)
        if (isPrepared(context)) return
        // 无标记但目录非空 = 上次拷贝中途夭折的残缺层，清干净重来。
        if (target.list().isNullOrEmpty().not()) {
            try { target.deleteRecursively() } catch (_: Throwable) {}
        }
        target.mkdirs()
        val marker = File(target, PREFIX_MARKER)
        val src = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)
        val ok = try {
            val cmd = "cp -a '${src.absolutePath}/.' '${target.absolutePath}/' 2>/dev/null"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor() == 0
        } catch (_: Throwable) {
            false
        }
        if (ok) {
            try { marker.createNewFile() } catch (_: Throwable) {}
        }
    }

    /**
     * 彻底清理沙箱占用的空间。
     *
     * 沙箱只是**临时**占用空间：会话一结束就必须把影子 prefix（~95MB）与影子 HOME 全部删除，
     * 否则用户会平白看到近百 MB 的「不明占用」。调用方应在会话真正结束
     * （进程退出 / 被 kill / 应用退出）后调用本方法。
     */
    fun purgeAll(context: Context) {
        try {
            File(getRootDir(context), "run").deleteRecursively()
        } catch (_: Throwable) {
        }
        // 告警哨兵与「已通知」标记一并清掉：空间已回收，
        // 下次会话是全新状态，不该复用上一次的提示记录。
        try {
            File(getRootDir(context), NOTICE_FILE).delete()
            File(getRootDir(context), NOTIFIED_MARKER).delete()
        } catch (_: Throwable) {
        }
        activeManualSessionId = null
    }

    /** 引导脚本写入的「内存储已被重定向」告警哨兵文件名。 */
    const val NOTICE_FILE = ".vortex_storage_notice"

    /** 引导脚本侧的「已提示过」标记文件名。 */
    const val NOTIFIED_MARKER = ".vortex_storage_notified"

    /**
     * 会话结束钩子：若结束的是手动沙箱会话，则彻底回收空间。
     *
     * @param sessionName 会话标题；非沙箱会话会被忽略
     */
    fun onSessionEnded(context: Context, sessionName: String?) {
        if (sessionName == SANDBOX_SESSION_TITLE) {
            purgeAll(context)
        }
    }

    /**
     * 回收「一次 Agent 对话 / 一次插件调用」产生的影子空间。
     *
     * 这两条路径不走引导脚本的 `--interactive`，因此不共享 EXIT trap，
     * 必须在调用方明确告知「这次调用结束了」时手动回收，否则每次对话
     * 都会留下约 95MB 残留。
     */
    fun onEphemeralRunEnded(context: Context) {
        purgeAll(context)
    }

    /** Agent 是否正在使用 VorteX 沙箱（决定调用结束后要不要回收）。 */
    fun isAgentUsingSandbox(context: Context): Boolean =
        isEnabled(context) && isAgentAuthorized(context)

    /** 指定插件是否正在使用 VorteX 沙箱。 */
    fun isPluginUsingSandbox(context: Context, pluginId: String): Boolean =
        shouldPluginUseSandbox(context, pluginId)

    // ------------------------------------------------------------------
    // 内存储：重定向 + 告警 + 样本导入
    // ------------------------------------------------------------------

    /**
     * 检查引导脚本是否留下了「内存储已被重定向」的哨兵，若有则投递 Snackbar 告警。
     *
     * 由 UI 侧周期调用（见 `VorteXSandboxNoticeHost`）。引导脚本运行在 libterminal
     * 的独立进程，无法直接驱动 Compose 的 Snackbar，故以哨兵文件作为跨进程信使。
     * 读完即删，确保同一条告警只提示一次。
     */
    fun consumeStorageNotice(context: Context) {
        val f = File(getRootDir(context), NOTICE_FILE)
        if (!f.exists()) return
        try {
            if (f.delete()) {
                VorteXSandboxNotice.post(
                    "VorteX 沙箱内已屏蔽 Android 内部存储（/sdcard 等）：" +
                        "沙箱只预演 Termux 环境。若需处理内存储中的文件，" +
                        "请先用 Termux:API 的存储权限把样本复制到 \$HOME 下再执行。"
                )
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 把用户内存储中的文件复制进沙箱可写层，供 Agent / 插件在沙箱内处理。
     *
     * 沙箱内**一律禁止**访问内存储，因此 Agent 与插件遇到位于内存储的输入
     * （样本文件、素材、待处理文档等）时，必须先经此函数导入到 \$HOME，
     * 再在沙箱内继续处理——而不是让沙箱去挂载内存储。
     *
     * @param realPath 用户内存储中的**真实**绝对路径（如 `/sdcard/Download/a.bin`）
     * @return 沙箱内的可写副本路径；失败时返回 null
     */
    fun importFromRealStorage(context: Context, realPath: String): String? {
        val src = File(realPath)
        if (!src.exists()) return null
        val imports = File(getRunHome(context), "vortex_imports")
        if (!imports.exists() && !imports.mkdirs()) return null

        // 保留一份原始文件名，多个同名文件靠目录分层避免互相覆盖。
        val safeName = realPath.trim('/').replace('/', '_').ifEmpty { "import" }
        val target = File(imports, safeName)
        return try {
            if (src.isDirectory) {
                src.copyRecursively(target, overwrite = true)
            } else {
                target.parentFile?.mkdirs()
                src.copyTo(target, overwrite = true)
            }
            target.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 命令包裹（插件 / Agent 执行路径）
    // ------------------------------------------------------------------

    /**
     * 若插件被授权在沙箱中运行，则将 [command] 包裹为「在 VorteX 沙箱内执行」的形式；
     * 否则原样返回。
     */
    fun wrapPluginCommand(context: Context, pluginId: String, command: String): String {
        if (!shouldPluginUseSandbox(context, pluginId)) return command
        ensureInitialized(context)
        val bootstrap = getBootstrapExecutable(context).absolutePath
        val escaped = command.replace("'", "'\\''")
        // bash '<bootstrap>' --run '<escaped>'：引导脚本先 setup 再执行命令，全程处于沙箱。
        return "bash '$bootstrap' --run '$escaped'"
    }

    // ------------------------------------------------------------------
    // 单会话限制（手动沙箱会话）
    // ------------------------------------------------------------------

    /**
     * 当前手动沙箱会话的标题。所有从终端页入口进入的沙箱会话都使用此标题，
     * 便于在会话列表中识别并强制「同时仅一个」。
     */
    const val SANDBOX_SESSION_TITLE = "沙箱会话"

    @Volatile
    private var activeManualSessionId: Int? = null

    /** 记录一个手动沙箱会话 id（覆盖式：保证同时只有一个手动沙箱会话）。 */
    fun setActiveManualSessionId(id: Int?) {
        activeManualSessionId = id
    }

    /** 返回当前活跃的手动沙箱会话 id（可能为 null）。 */
    fun getActiveManualSessionId(): Int? = activeManualSessionId
}
