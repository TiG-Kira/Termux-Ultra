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

    /** 开启/关闭总开关。开启时初始化沙箱并保留最小化初始快照；关闭时回收 Agent 授权。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        VorteXSandboxPrefs.setEnabled(context, enabled)
        if (enabled) {
            ensureInitialized(context)
        } else {
            // 总开关关闭后，Agent / 插件均不可再使用沙箱。
            VorteXSandboxPrefs.setAgentAuthorized(context, false)
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
     * 确保沙箱已就位：解压引导脚本、建立目录、并在首次（或快照为空）时抓取最小化初始快照。
     * 幂等，可反复调用。
     */
    fun ensureInitialized(context: Context) {
        val root = getRootDir(context)
        val bootstrap = getBootstrapExecutable(context)
        if (!bootstrap.exists()) {
            extractBootstrap(context, bootstrap)
        }
        val snapshotHome = getSnapshotHome(context)
        if (snapshotHome.list().isNullOrEmpty()) {
            takeSnapshot(context)
        }
        getRunHome(context).mkdirs()
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
     * 抓取当前用户环境为最小化初始快照。策略：
     * 把当前 Termux $HOME 整体复制到 snapshot（保留用户全部环境与配置），
     * 失败则退化为递归拷贝。
     */
    fun takeSnapshot(context: Context) {
        val src = TermuxConstants.TERMUX_HOME_DIR_PATH
        val dst = getSnapshotHome(context).absolutePath
        try {
            val cmd = "cp -a '$src/.' '$dst/' 2>/dev/null || cp -r '$src/.' '$dst/' 2>/dev/null"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
        } catch (_: Throwable) {
            try {
                File(src).copyRecursively(File(dst), overwrite = true)
            } catch (_: Throwable) {
                // 快照抓取失败不应阻断开关开启；run 层为空时会回退到原始 $HOME。
            }
        }
    }

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
