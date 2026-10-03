package com.termux.app.terminal.shell

import android.content.Context
import com.awkoo.libterminal.engine.TerminalSession as LibTerminalSession
import com.termux.shared.compat.ShellEnvironmentCompat
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession

/**
 * 终端会话的 Java 兼容外观（继承 [TerminalSession] 并覆盖方法面，
 * 全部委托给 libterminal [LibTerminalSession]）。
 *
 * 会话由 ComposeSessionManager 单例持久管理，此适配层仅承载 Java 侧
 * 兼容句柄：mTermuxSessions 列表中的一个元素就是一个 libterminal 会话，
 * 无镜像/注册表/写转发等间接层。
 *
 * 注意：libterminal 目前未提供 transcript 文本读取 API，因此
 * getEmulator()/getTranscript* 返回空值，不属本适配层范围。
 */
class TerminalSessionAdapter private constructor(
    private val libSession: LibTerminalSession,
    private val shellPath: String,
    private val cwd: String
) : com.termux.terminal.TerminalSession(shellPath, cwd, emptyArray(), emptyArray(), 5000, null) {

    /** 对应 libterminal 会话 id（供 ComposeSessionManager.switchTo/killSession 使用）。 */
    val sessionId: Int
        get() = libSession.id

    /** 直接访问底层 libterminal 会话（获取 pid/exited 等兼容状态）。 */
    val session: LibTerminalSession
        get() = libSession

    override fun isRunning(): Boolean = libSession.isRunning.value

    override fun write(data: ByteArray, offset: Int, count: Int) {
        val payload = if (offset == 0 && count == data.size) data else java.util.Arrays.copyOfRange(data, offset, offset + count)
        libSession.write(payload)
    }

    override fun finishIfRunning() {
        libSession.finishIfRunning()
    }

    override fun getShellPid(): Int = TerminalSessionCompat.getPid(libSession.id)

    override fun getCwd(): String? = cwd

    companion object {

        /**
         * 创建"新终端"：直接拉起 libterminal 会话并把其 Java 句柄包装成
         * [TermuxSession] 返回。
         *
         * @return 非 null。（参数构建失败时由调用方自行记录日志。）
         */
        @JvmStatic
        fun create(
            context: Context,
            executionCommand: ExecutionCommand,
            sessionName: String?
        ): TermuxSession {
            val envClient = ShellEnvironmentCompat(TermuxShellEnvironment())
            val workingDirectory = executionCommand.workingDirectory ?: "/"
            val shellPath = executionCommand.executable ?: "/system/bin/sh"
            val rawArgs = executionCommand.arguments ?: emptyArray()
            // execvp(cmd, argv) 不要求 argv[0] == cmd，内核会把 argv 原样传给新程序，
            // 因此 argv[0] 是谁直接影响被 exec 的程序如何解析自己的选项。
            // TermuxService.createTermuxSession(null, arrayOf("-c", command), ...) 传进来的
            // argv 是 ["-c", <整段脚本>]：argv[0] 是「选项」而非程序名。
            // /system/bin/sh (mksh) 收到后把 argv[0]="-c" 当作程序名（按惯例剥掉前导 '-'，
            // 于是 $0 变成 "c"），随后从 argv[1] 起找选项——而 argv[1] 直接是脚本正文，
            // 不以 '-' 开头，于是被当成「要执行的脚本文件路径」。结果：
            //   短脚本 → `c: <脚本>: No such file or directory`
            //   长脚本 → `c: <脚本>: File name too long` (ENAMETOOLONG)，退出码 127
            // 这正是 QEMU with VNC 生成的大脚本必挂的原因。
            // 修法：argv[0] 以 '-' 开头（即调用方漏传程序名）时补回程序名，
            // 使布局变为 ["sh", "-c", <脚本>]，与 createDefaultSession() 的做法一致。
            val args = if (rawArgs.isNotEmpty() && rawArgs[0].startsWith("-")) {
                arrayOf(shellPath.substringAfterLast('/')) + rawArgs
            } else {
                rawArgs
            }
            val env = envClient.buildEnvironment(context, executionCommand.isFailsafe, workingDirectory)

            val session = ComposeSessionManager.getInstance(context).createSession(
                shellPath = shellPath,
                cwd = workingDirectory,
                args = args,
                env = env,
                sessionName = sessionName ?: ""
            )

            val adapter = TerminalSessionAdapter(session, shellPath, workingDirectory)
            adapter.mSessionName = sessionName
            return TermuxSession.wrap(adapter, executionCommand, null, false)
        }
    }
}