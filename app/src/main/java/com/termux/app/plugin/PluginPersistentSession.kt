package com.termux.app.plugin

import android.content.Context
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.shell.ShellUtils
import com.termux.shared.shell.TermuxSession
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import java.util.UUID

/**
 * 插件持久化会话 —— 打开一个常驻 shell，插件可以持续向其输入命令、读取输出。
 *
 * 与 PluginManager.executeShellCommand() 每次新建进程不同，这个会话会保持存活，
 * 直到插件显式关闭或 TermuxService 被销毁。
 */
class PluginPersistentSession(
    val sessionId: String,
    val pluginId: String,
    val sessionName: String,
    val termuxSession: TermuxSession,
) {

    private val terminalSession: TerminalSession = termuxSession.terminalSession

    /** 上次读取 transcript 时的游标（用于增量读取）。 */
    @Volatile
    private var readOffset: Int = 0

    /** 当前运行状态。 */
    val isRunning: Boolean
        get() = terminalSession.isRunning

    /** 获取 shell 当前 working directory（可能为 null）。 */
    val cwd: String?
        get() = terminalSession.cwd

    /** 进程 PID（0 = 未启动, >0 = 运行中, -1 = 已结束）。 */
    val pid: Int
        get() = terminalSession.shellPid

    /** 进程退出代码（仅已结束时有效）。 */
    val exitCode: Int
        get() = terminalSession.exitStatus

    /**
     * 向 shell 写入原始字节数据（stdin）。
     * 可以是命令文本，也可以是 Ctrl+C、Ctrl+D 等控制字节。
     */
    fun write(data: ByteArray, offset: Int = 0, count: Int = data.size): Boolean {
        if (!isRunning) return false
        terminalSession.write(data, offset, count)
        return true
    }

    /**
     * 向 shell 写入一行文本（自动追加换行符）。
     */
    fun writeln(line: String): Boolean {
        return write((line + '\n').toByteArray())
    }

    /**
     * 向 shell 发送一条命令并回车（等价于 writeln）。
     */
    fun executeCommand(command: String): Boolean = writeln(command)

    /**
     * 读取自上次 read 以来新增的终端输出文本（增量读取）。
     *
     * @param mark 若为 true，游标推进到末尾，下次 readNew 不会再读到；
     *             若为 false，游标保持不动，适合做「偷看」。
     */
    fun readNew(mark: Boolean = true): String {
        val full = ShellUtils.getTerminalSessionTranscriptText(terminalSession, true, false) ?: return ""
        val result = if (readOffset >= full.length) "" else full.substring(readOffset)
        if (mark) readOffset = full.length
        return result
    }

    /**
     * 读取从会话开始至今的全部 transcript（不更新游标）。
     */
    fun readAll(): String {
        return ShellUtils.getTerminalSessionTranscriptText(terminalSession, true, false) ?: ""
    }

    /**
     * 重置读取游标为 transcript 末尾，忽略所有历史输出。
     */
    fun resetReadCursor() {
        val full = ShellUtils.getTerminalSessionTranscriptText(terminalSession, true, false) ?: ""
        readOffset = full.length
    }

    /**
     * 发送 Ctrl+C（0x03），中断前台程序。
     */
    fun interrupt() {
        if (!isRunning) return
        val b = byteArrayOf(0x03.toByte())
        terminalSession.write(b, 0, b.size)
    }

    /**
     * 发送 EOF（Ctrl+D，0x04）。
     */
    fun sendEof() {
        if (!isRunning) return
        val b = byteArrayOf(0x04.toByte())
        terminalSession.write(b, 0, b.size)
    }

    /**
     * 结束会话（SIGKILL）。幂等，可安全重复调用。
     */
    fun close() {
        if (!isRunning) return
        terminalSession.finishIfRunning()
    }

    companion object {
        private const val DEFAULT_COLS = 80
        private const val DEFAULT_ROWS = 24
        private const val DEFAULT_TRANSCRIPT_ROWS = 10000

        /**
         * 由 PluginManager 调用：创建并初始化一个持久化插件会话。
         *
         * @return PluginPersistentSession 或 null（创建失败）
         */
        fun create(
            context: Context,
            pluginId: String,
            sessionName: String,
        ): PluginPersistentSession? {
            val sessionId = "${pluginId}::${UUID.randomUUID().toString().take(8)}"

            val shellPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash"
            val envClient = TermuxShellEnvironmentClient()
            val wd = envClient.defaultWorkingDirectoryPath.ifEmpty { "/" }

            val command = ExecutionCommand(
                System.currentTimeMillis().toInt(),
                shellPath,
                null, // arguments —— null 让 shell 以交互模式启动（login shell）
                null, // stdin
                wd,
                false, // inBackground —— false，让 TerminalSession 正常走 PTY 流程
                false  // isFailsafe
            )
            command.commandLabel = "PluginSession: $sessionName ($pluginId)"
            command.terminalTranscriptRows = DEFAULT_TRANSCRIPT_ROWS

            val sessionClient = TermuxTerminalSessionClientBase()

            // 创建 TermuxSessionClient —— 会话退出时调用 PluginManager 清理注册表
            val cleanupClient = TermuxSession.TermuxSessionClient { exited ->
                // 用终端 handle 查找对应的 sessionId（我们稍后会设置 lookup map）
                PluginPersistentSessionRegistry.onSessionExited(exited.terminalSession)
            }

            val termuxSession = TermuxSession.execute(
                context,
                command,
                sessionClient,
                cleanupClient,
                envClient,
                sessionName,
                false // setStdoutOnExit
            ) ?: return null

            val terminal = termuxSession.terminalSession

            // 手动初始化 emulator（必须），PTY IO 线程才能启动
            terminal.updateSize(DEFAULT_COLS, DEFAULT_ROWS, 0, 0)

            // 等 PTY 完全就绪
            Thread.sleep(300)

            // 标记会话来源为 PLUGIN
            termuxSession.setSource(TermuxSession.SessionSource.PLUGIN)

            return PluginPersistentSession(sessionId, pluginId, sessionName, termuxSession)
        }
    }
}
