package com.termux.app.compose

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.shell.TermuxSession
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.terminal.TermuxTerminalSessionClientBase
import com.termux.app.compose.terminal.ComposeSessionManager
import com.termux.app.compose.terminal.engine.TerminalSession as LibEngineSession
import com.termux.terminal.TerminalSession as JavaTerminalSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Compose 内核下的 Java 接口兼容桥接层。
 *
 * 目标：在 Compose 模式下，第三方页面（第三方资源中心 / 工具中心 / 文件管理 / 总览等）
 * 无需改动任何代码，直接调用 Java 版接口（TermuxService.createTermuxSession 等）即可：
 *  - 创建"新终端" / tmux 会话时，实际拉起的是 Compose 会话；
 *  - 返回的 TermuxSession 作为"镜像句柄"，其 mHandle / mSessionName / write() / isRunning()
 *    全部与 Java 接口一致，write() 自动转发到真正的 Compose 会话进程；
 *  - TermuxActivity 通过 "sessionHandle" 定位并切换到对应的 Compose 会话。
 */
object ComposeSessionBridge {

    /** 单个镜像会话的登记信息。 */
    data class BridgeEntry(
        val handle: String,
        val mirror: JavaTerminalSession,
        val composeSession: LibEngineSession
    )

    /** handle（Java 镜像句柄）→ 桥接登记。 */
    private val registry = ConcurrentHashMap<String, BridgeEntry>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Compose 模式下创建会话（由 TermuxService.createTermuxSession 调用）。
     *
     * 复用了 Java 版 [TermuxSession.execute] 的完整参数构建逻辑（登录 shell 选择、
     * setupProcessArgs、argv0 处理、workingDirectory 默认值），保证与 Java 模式行为完全一致；
     * 同时拉起真正进程的 Compose 会话，并把返回的 Java [TermuxSession] 作为与其一一对应的镜像句柄。
     */
    fun createComposeMirrorSession(
        context: Context,
        executionCommand: ExecutionCommand,
        sessionName: String?
    ): TermuxSession? {
        val envClient = TermuxShellEnvironmentClient()

        // 1) Java 镜像：复用 TermuxSession.execute 构建（未附着到 TerminalView 时不会拉起进程）
        val mirror = TermuxSession.execute(
            context,
            executionCommand,
            TermuxTerminalSessionClientBase(),
            null,
            envClient,
            sessionName,
            false
        ) ?: return null

        // TermuxSession.execute 已填充 executable / arguments / workingDirectory（不会为空）
        val workingDirectory = executionCommand.workingDirectory ?: "/"
        val shellPath = executionCommand.executable ?: "/system/bin/sh"
        val env = envClient.buildEnvironment(context, executionCommand.isFailsafe, workingDirectory)

        // 2) 真正的 Compose 会话（立即拉起进程）
        val composeSession = ComposeSessionManager.getInstance(context).createSession(
            shellPath = shellPath,
            cwd = workingDirectory,
            args = executionCommand.arguments ?: emptyArray(),
            env = env,
            sessionName = sessionName ?: ""
        )

        // 3) 登记镜像句柄并安装写转发器，使 Java 侧 write() 能到达 Compose 会话
        val bridgeEntry = BridgeEntry(mirror.getTerminalSession().mHandle, mirror.getTerminalSession(), composeSession)
        registry[bridgeEntry.handle] = bridgeEntry
        mirror.getTerminalSession().setWriteForwarder(BridgeWriteForwarder(composeSession))

        return mirror
    }

    /** 是否为 Compose 镜像会话句柄。 */
    fun isMirrorSession(handle: String?): Boolean = handle != null && registry.containsKey(handle)

    /** 通过镜像句柄解析 Compose 会话 id（找不到返回 null）。 */
    fun resolveComposeSessionId(handle: String?): Int? = if (handle == null) null else registry[handle]?.composeSession?.id

    /** 通过镜像句柄向 Compose 会话写入数据（同步调用，线程安全）。 */
    fun writeToCompose(handle: String?, data: ByteArray, offset: Int, count: Int) {
        val entry = (if (handle != null) registry[handle] else null) ?: return
        entry.composeSession.write(if (offset == 0 && count == data.size) data else java.util.Arrays.copyOfRange(data, offset, offset + count))
    }

    /** 清除镜像登记（同时结束对应 Compose 会话进程）。 */
    fun removeByJavaMirror(mirror: JavaTerminalSession) {
        val entry = registry.remove(mirror.mHandle) ?: return
        entry.composeSession.finishIfRunning()
    }

    /** 清空整个镜像注册表（切换运行核心时调用；会话进程由 ComposeSessionManager.killAllSessions 结束）。 */
    fun clearAll() {
        registry.clear()
    }

    /** 通过镜像句柄切换到对应 Compose 会话（用于 TermuxActivity.onNewIntent 等场景）。 */
    fun switchToSessionByHandle(context: Context, handle: String?) {
        val id = resolveComposeSessionId(handle) ?: return
        mainHandler.post {
            val manager = ComposeSessionManager.getInstance(context)
            if (manager.sessions.value.any { it.session.id == id }) {
                manager.switchTo(id)
            }
        }
    }

    /** 以 optimistic 方式判断 Compose 会话是否仍存活。 */
    fun isComposeSessionAlive(handle: String?): Boolean {
        if (handle == null) return false
        return registry[handle]?.composeSession?.isRunning == true
    }

    /** Compose 写转发器实现：把对 Java 镜像的写入转发到真正的 Compose 会话。 */
    private class BridgeWriteForwarder(
        private val composeSession: LibEngineSession
    ) : JavaTerminalSession.WriteForwarder {
        override fun forwardWrite(data: ByteArray, offset: Int, count: Int) {
            if (offset == 0 && count == data.size) {
                composeSession.write(data)
            } else {
                composeSession.write(java.util.Arrays.copyOfRange(data, offset, offset + count))
            }
        }

        override fun isAlive(): Boolean = composeSession.isRunning

        override fun kill(): Unit = composeSession.finishIfRunning()
    }
}