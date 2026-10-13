package com.termux.app.vortex

import android.content.Context
import android.os.Build
import com.termux.R
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * VorteX 沙箱核心管理器。
 *
 * 沙箱是一个与 Termux 真实用户环境**隔离**的测试环境：所有修改都局限于沙箱内部，
 * 不会泄漏到真实环境；会话结束时一切修改自动消失，不留残余。沙箱同时提供
 * **虚拟 ROOT 权限**（uid 0 视角），用于预演危险脚本。
 *
 * ## 最小快照拆分（本版核心改动）
 *
 * 旧实现把整个 `$HOME` 拷贝成快照、再把 `$PREFIX` 拷 95MB 成影子层——既慢又占空间。
 * 本版按用户需求把沙箱目录拆成两块：
 *
 * ```
 *   <filesDir>/vortex/
 *     essential/   # 「必要更改区」：**持久化**。仅含 su/sudo 模拟器与伪装 ROOT 所需的
 *                  #   文件篡改组件（由引导脚本幂等播种）。重启沙箱后仍在，无需重生成。
 *     tmp/         # 「临时存储区」：**会话级、易失**。影子写（copy-up）的副本、
 *                  #   删除 whiteout 标记、运行时 /proc 影子、导入样本等都落在这里。
 *                  #   会话结束整目录清空，一切修改即消失。
 *     lib/         # 运行时从 APK 解出的 LD_PRELOAD 影子写拦截库（libvortex_shadow.so）
 *     vortex_sandbox.sh  # 引导脚本
 * ```
 *
 * 常规目录（真实 `$HOME`、`$PREFIX`）不再拷贝，而是以**链接视图**呈现：读取走真实文件，
 * 写入被 LD_PRELOAD 拦截、copy-up 到 `tmp/` 影子副本后虚拟写——写调用返回成功，
 * 真实底层数据不变（影子写入机制）。这与 overlayfs 的 copy-up / whiteout 语义同源，
 * 只是实现在用户态（无 root 的 Android 无法挂 overlayfs）。
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

    /**
     * 「必要更改区」根目录（**持久化**）。
     *
     * 只放伪装 ROOT 所必需的文件篡改组件（su/sudo 模拟器脚本等）。这些文件极小、
     * 且跨会话保留——重启沙箱后无需重新生成，正是「最小快照默认仅含 su/sudo
     * 及相关篡改组件」的落点。用户区（home / prefix）不在此列，它们以链接视图呈现。
     */
    fun getEssentialDir(context: Context): File =
        File(getRootDir(context), "essential").also { it.mkdirs() }

    /**
     * 「临时存储区」根目录（**会话级、易失**）。
     *
     * 影子写副本、删除 whiteout、运行时 /proc 影子、样本导入全部落在这里。
     * 会话结束（[purgeAll]）整体清空，一切修改即消失。
     */
    fun getTmpDir(context: Context): File =
        File(getRootDir(context), "tmp").also { it.mkdirs() }

    /** LD_PRELOAD 影子写拦截库（从 APK 解出的 .so）所在文件。 */
    fun getShadowLibFile(context: Context): File =
        File(File(getRootDir(context), "lib"), "libvortex_shadow.so")

    /**
     * Agent 运行时根目录：当 Termux Agent 被授权使用沙箱时，其工作目录指向**真实
     * `$HOME`**——沙箱内对该目录的写入会被 LD_PRELOAD 影子化，从而把 Agent 的产物
     * 收束在沙箱（tmp/）内，绝不污染真实环境。
     */
    fun getAgentSandboxRoot(context: Context): File {
        ensureInitialized(context)
        return File(TermuxConstants.TERMUX_HOME_DIR_PATH)
    }

    // ------------------------------------------------------------------
    // 开关状态（委托给 VorteXSandboxPrefs）
    // ------------------------------------------------------------------

    fun isEnabled(context: Context): Boolean = VorteXSandboxPrefs.isEnabled(context)

    /**
     * 开启/关闭总开关。开启时初始化沙箱；关闭时回收 Agent 授权并清空临时存储区。
     *
     * 注意：初始化可能涉及磁盘 IO（解压引导脚本与 .so），**绝不能**在调用方
     * （设置页开关回调，运行在 UI 线程）同步执行——那会直接 ANR 闪退。因此这里只
     * 落盘开关状态并立即返回，重活交给后台线程。开关瞬间生效与否无关紧要：
     * 进入沙箱时脚本还会再校验一次。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        setEnabledAsync(context, enabled)
    }

    /**
     * 异步开启/关闭总开关：开关状态立即落盘（UI 立刻翻转），真正耗时的初始化 /
     * 清理放到后台线程执行，完成后回调 [onDone]（**可能在任意线程**，调用方需自行切主线程）。
     */
    fun setEnabledAsync(context: Context, enabled: Boolean, onDone: (() -> Unit)? = null) {
        VorteXSandboxPrefs.setEnabled(context, enabled)
        val appContext = context.applicationContext
        Thread({
            try {
                applyEnabled(appContext, enabled)
            } catch (_: Throwable) {
            } finally {
                onDone?.invoke()
            }
        }, "vortex-sandbox-toggle").apply { isDaemon = true }.start()
    }

    /** 开关生效的实体动作：开启走后台初始化，关闭做状态回收与临时区清理。⚠️ 含磁盘 IO。 */
    private fun applyEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            ensureInitialized(context)
        } else {
            // 总开关关闭后，Agent / 插件均不可再使用沙箱。
            VorteXSandboxPrefs.setAgentAuthorized(context, false)
            // 仅清临时存储区（tmp/）。「必要更改区」(essential/) 持久保留，
            // 因为它只装 su/sudo 等伪装 ROOT 组件，跨会话保留无害且符合「最小快照持久化」。
            try {
                getTmpDir(context).deleteRecursively()
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
    // 初始化
    // ------------------------------------------------------------------

    /**
     * 确保沙箱就位：解压引导脚本、从 APK 解出 LD_PRELOAD 影子写库、建立目录、并保证
     * 「必要更改区」可写。幂等，且必须在后台线程调用（含磁盘 IO）。
     *
     * 与旧实现的关键区别：这里**不再拷贝整个 `$HOME` 快照，也不再拷贝 95MB 的
     * `$PREFIX` 影子层**。用户区以链接视图呈现，写入走 tmp/ 影子副本（见
     * `vortex_shadow.c`）。「必要更改区」(essential/) 由引导脚本幂等播种 su/sudo
     * 模拟器等少量文件，本方法只保证目录存在。
     */
    fun ensureInitialized(context: Context) {
        getRootDir(context)
        val bootstrap = getBootstrapExecutable(context)
        if (!bootstrapUpToDate(context, bootstrap)) {
            extractBootstrap(context, bootstrap)
        }
        // 解出 LD_PRELOAD 影子写库（extractNativeLibs=false，APK 不自动解压 .so）。
        extractShadowLib(context)
        // essential/、tmp/ 目录存在性由对应 getter 保证；这里补一遍避免竞态。
        getEssentialDir(context)
        getTmpDir(context)
    }

    /** 后台执行 [ensureInitialized]，避免调用方被磁盘 IO 阻塞。重复调用安全。 */
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
     * 磁盘上的引导脚本是否与 assets 中一致（即是否为当前 app 版本的脚本）。
     *
     * 脚本只有几十 KB，直接整份比对即可；内容一致时**绝不重写**，
     * 避免覆盖一个正在执行中的脚本。
     */
    private fun bootstrapUpToDate(context: Context, target: File): Boolean {
        if (!target.exists() || target.length() == 0L) return false
        return try {
            val onDisk = target.readBytes()
            context.assets.open(ASSET_BOOTSTRAP).use { it.readBytes() }.contentEquals(onDisk)
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * 从安装包（APK）解出 LD_PRELOAD 影子写拦截库 `libvortex_shadow.so` 到
     * `<root>/lib/`，供引导脚本以 `LD_PRELOAD` 注入沙箱子进程。
     *
     * 为什么必须手动解压：`AndroidManifest` 设了 `extractNativeLibs="false"`，系统不会
     * 在安装时把 .so 解到应用私有目录；而 LD_PRELOAD 需要文件的真实路径。故这里用
     * `ZipFile` 读取 `lib/<abi>/libvortex_shadow.so` 并写出。
     *
     * 已解出过则复用，但必须校验**版本戳**（APK 内该条目的 CRC+大小）：
     * 「解出一次就永不重抽」是致命疏漏——修过影子库的 bug（如 fortify 把
     * `__open_2` 拦截器改写成自调用导致栈溢出/自旋）后，老用户升级 APK
     * 仍会继续跑 files 目录里的旧 .so，崩溃与卡死照旧。无戳或戳不一致
     * 一律重解。
     *
     * ⚠️ 含磁盘 IO，必须在后台线程调用。
     */
    fun extractShadowLib(context: Context): Boolean {
        val target = getShadowLibFile(context)
        return try {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val apk = context.applicationInfo.sourceDir
            ZipFile(apk).use { zf ->
                // 主 ABI 优先，失败回退到 arm64-v8a（Termux 主战场）。
                val entry = zf.getEntry("lib/$abi/libvortex_shadow.so")
                    ?: zf.getEntry("lib/arm64-v8a/libvortex_shadow.so")
                    ?: return false
                val stamp = File(target.parentFile, ".libvortex_version")
                val stampText = "${entry.crc}:${entry.size}"
                if (target.exists() && target.length() == entry.size &&
                    stamp.isFile && stamp.readText().trim() == stampText
                ) {
                    return true
                }
                target.parentFile?.mkdirs()
                zf.getInputStream(entry).use { ins ->
                    target.outputStream().use { outs -> ins.copyTo(outs) }
                }
                target.setExecutable(true)
                stamp.writeText(stampText)
                target.exists() && target.length() > 0L
            }
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------
    // 就绪判定 / 预热
    // ------------------------------------------------------------------

    /**
     * 沙箱是否已就绪（影子写库已解出，且「必要更改区」已播种）。
     *
     * 旧实现靠「95MB 影子 prefix 拷贝完成」判定就绪，那次拷贝是进入沙箱延迟的唯一来源。
     * 新实现去掉了那次拷贝，就绪 = 库已解出 + essential/ 非空（su/sudo 模拟器已就位），
     * 因此预热几乎瞬时完成。
     */
    fun isPrepared(context: Context): Boolean {
        val lib = getShadowLibFile(context)
        if (!lib.exists() || lib.length() == 0L) return false
        val essential = getEssentialDir(context)
        val seeded = File(essential, "su").exists() || File(essential, "sudo").exists()
        return essential.list()?.isNotEmpty() == true && seeded
    }

    /**
     * 预热沙箱：确保初始化完成（解库 + essential 目录就绪）。
     *
     * 旧版这里要拷 95MB 的 `$PREFIX` 影子层（必须放后台线程，否则 ANR）。新版无此重活，
     * 预热极快，但仍保留异步入口以兼容调用方（设置页 / 终端列表页）。
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
            // 重入时不能直接 return——否则第二次调用的 onReady 永远不触发。
            prepareWaiters.add(onReady)
            if (preparing) return
            preparing = true
        }
        val appContext = context.applicationContext
        Thread({
            try {
                ensureInitialized(appContext)
            } catch (_: Throwable) {
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

    // ------------------------------------------------------------------
    // proot 探测 / 隔离能力描述
    // ------------------------------------------------------------------

    /** 探测当前环境是否具备 proot（决定沙箱是「绝对路径级隔离」还是「仅 LD_PRELOAD 影子」）。 */
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
        if (isProotAvailable(context)) {
            "proot 隔离 + LD_PRELOAD 影子写：绝对路径受保护，写入不落盘"
        } else {
            "轻量影子写：请先 pkg install proot 以获得绝对路径隔离"
        }

    /**
     * 手动重置沙箱：清空临时存储区（tmp/），让下一会话从「链接视图 + 必要更改区」重新起步。
     * 「必要更改区」(essential/) 保留。供调试 / 手动重置调用。
     */
    fun resetSandbox(context: Context) {
        try {
            getTmpDir(context).deleteRecursively()
        } catch (_: Throwable) {
        }
        getTmpDir(context).mkdirs()
    }

    // ------------------------------------------------------------------
    // 彻底清理（会话结束）
    // ------------------------------------------------------------------

    /**
     * 彻底清理**临时**占用空间：清空 tmp/（影子副本、whiteout、运行时 /proc、导入样本）。
     *
     * 一切修改都落在 tmp/，清空即「所有改动在会话完全结束后消失」。「必要更改区」
     * (essential/) 不在此清——它只装 su/sudo 等伪装 ROOT 组件，本就要求持久。
     *
     * 调用方应在会话真正结束（进程退出 / 被 kill / 应用退出）后调用本方法。
     *
     * ⚠️ 回收全程在专用后台线程执行：tmp/proc 下有为设备上每个进程建的符号链接
     * （可达上万条），主线程同步 `deleteRecursively` 会直接卡死 UI（ANR 闪退）。
     */
    fun purgeAll(context: Context) {
        purgeExecutor.execute { doPurge(context.applicationContext) }
    }

    /**
     * 空间回收专用单线程：purgeAll 与一次性运行回收都排入此队列，FIFO 串行，
     * 避免两条路径并发删同一棵树；[awaitPendingPurge] 借 FIFO 语义等待全部在途
     * 回收完成。
     */
    private val purgeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vortex-sandbox-purge").apply { isDaemon = true }
    }

    /** 回收动作本体（在 [purgeExecutor] 上执行）：先摘 procwatch pidfile，再清 tmp/ 与哨兵。 */
    private fun doPurge(appContext: Context) {
        // 先摘 procwatch pidfile：后台 watcher 每 0.2s 自检，发现 pidfile
        // 消失即自行退场；否则它会不断重建 tmp/proc，回收永远不干净。
        try {
            File(getRootDir(appContext), PROCWATCH_PIDFILE).delete()
        } catch (_: Throwable) {
        }
        try {
            getTmpDir(appContext).deleteRecursively()
        } catch (_: Throwable) {
        }
        // 告警哨兵与「已通知」标记一并清掉：空间已回收，下次会话是全新状态。
        try {
            File(getRootDir(appContext), NOTICE_FILE).delete()
            File(getRootDir(appContext), NOTIFIED_MARKER).delete()
            File(getTmpDir(appContext), SHADOWING_NOTICE).delete()
            File(getTmpDir(appContext), SHADOWING_NOTIFIED).delete()
        } catch (_: Throwable) {
        }
        activeManualSessionId = null
    }

    /**
     * 等待在途的空间回收全部完成（**新建沙箱会话前调用**，保证 tmp/ 是干净 slate）。
     *
     * 利用单线程队列的 FIFO 语义：提交一个空任务并等待其结束，即保证此前排队的
     * 所有回收均已完成。无在途任务时立即返回；超时后放弃等待，由引导脚本交互
     * 启动时的清零兜底。
     */
    fun awaitPendingPurge(timeoutMs: Long = 10_000) {
        try {
            purgeExecutor.submit(Runnable { }).get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
    }

    /** 引导脚本写入的「内存储已被重定向」告警哨兵文件名。 */
    const val NOTICE_FILE = ".vortex_storage_notice"

    /** 引导脚本侧的「已提示过」标记文件名。 */
    const val NOTIFIED_MARKER = ".vortex_storage_notified"

    /** 引导脚本 procwatch 后台守护的 pidfile（与 `vortex_sandbox.sh` 保持同名）。 */
    const val PROCWATCH_PIDFILE = ".vortex_procwatch_pid"

    /** LD_PRELOAD 首次影子化时写的哨兵文件名（宿主侧据此弹 Snackbar）。 */
    const val SHADOWING_NOTICE = ".vortex_shadowing"

    /** 影子化提示的「已提示过」标记文件名（每会话仅提示一次）。 */
    const val SHADOWING_NOTIFIED = ".vortex_shadow_notified"

    /**
     * 会话结束钩子：若结束的是手动沙箱会话，则彻底回收临时空间。
     *
     * @param sessionName 会话标题；非沙箱会话会被忽略
     */
    fun onSessionEnded(context: Context, sessionName: String?) {
        if (sessionName == SANDBOX_SESSION_TITLE) {
            purgeAll(context)
        }
    }

    /**
     * 回收「一次 Agent 对话 / 一次插件调用」产生的临时空间。
     *
     * 这两条路径走引导脚本 `--run`（非 `--interactive`），与手动会话**共用同一个
     * tmp 目录**（不存在独立的 run.* 子目录）；其 EXIT trap（reset_run）已在正常退出时
     * 自清 tmp/，本方法只覆盖脚本被 SIGKILL 等 trap 未执行的异常路径——此时残余
     * 同样直接落在 tmp/ 下，故回收目标就是 tmp/ 本身（复用 [doPurge]）。
     *
     * ⚠️ 手动沙箱会话存活期间不清：tmp/proc 是活会话的运行时 /proc 影子，连锅端会
     * 直接崩掉该会话。该会话结束时 [onSessionEnded] → purgeAll 会连残余一并回收，
     * 此处跳过不会漏。门禁确认放在回收队列内执行，避开与「新建会话」的竞速窗口。
     */
    fun onEphemeralRunEnded(context: Context) {
        val appContext = context.applicationContext
        purgeExecutor.execute {
            if (activeManualSessionId == null) doPurge(appContext)
        }
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
     * 检查 LD_PRELOAD 是否写来了「检测到修改，正在影子化」的哨兵，若有则投递 Snackbar。
     *
     * 触发时机：沙箱内首次出现对受控目录（真实 `$HOME` / `$PREFIX`）的写入，影子写库
     * copy-up 真实内容到 tmp/ 后写入该哨兵。宿主侧轮询到此即提示用户「修改已被影子化」，
     * 读完即删（节流由影子写库的 NOTIFIED 标记保证，每会话仅一次）。
     */
    fun consumeShadowingNotice(context: Context) {
        val f = File(getTmpDir(context), SHADOWING_NOTICE)
        if (!f.exists()) return
        try {
            if (f.delete()) {
                VorteXSandboxNotice.post(context.getString(R.string.vortex_shadowing_notice))
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 把用户内存储中的文件复制进沙箱临时存储区，供 Agent / 插件在沙箱内处理。
     *
     * 沙箱内**一律禁止**访问内存储，因此遇到位于内存储的输入（样本文件、素材、待处理
     * 文档等）时，必须先经此函数导入到临时区（tmp/imports），再在沙箱内继续处理。
     *
     * @param realPath 用户内存储中的**真实**绝对路径（如 `/sdcard/Download/a.bin`）
     * @return 沙箱内的可写副本路径；失败时返回 null
     */
    fun importFromRealStorage(context: Context, realPath: String): String? {
        val src = File(realPath)
        if (!src.exists()) return null
        val imports = File(getTmpDir(context), "imports")
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
     * 否则原样返回。引导脚本内部会注入 LD_PRELOAD 影子写库，并处理 su/sudo 模拟与
     * 虚拟 ROOT。
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
