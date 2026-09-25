package com.termux.app.terminal.shell

import android.content.Context
import com.awkoo.libterminal.engine.TerminalSession as LibTerminalSession
import com.termux.shared.compat.ShellEnvironmentCompat
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession

/**
 * Nova 会话的 Java 兼容外观（继承经典 [TerminalSession] 空壳并覆盖方法面，
 * 全部委托给 libterminal [LibTerminalSession]）。
 *
 * 取代旧 ComposeSessionBridge 的「经典空壳镜像 + WriteForwarder + 注册表」：
 *  - [isRunning] 直接反映 Nova 会话运行态；
 *  - [write] 直接写入 Nova 会话进程；
 *  - [finishIfRunning] 直接结束 Nova 会话进程；
 *  - [getShellPid] 走 TerminalSessionCompat 兼容层（与 ComposeSessionManager 同源）。
 *
 * 因此 TermuxService 的 mTermuxSessions 列表中一个元素就是一个 Nova 会话本体，
 * 不再存在经典空壳与 Nova 会话的双份映射，也不再有 handle→会话 的注册表间接层。
 *
 * 注意：libterminal 目前未提供 transcript 文本文本读取 API，因此
 * getEmulator()/getTranscript* 语义与旧镜像一致（为空），不属本适配层范围。
 */
class NovaTerminalSessionAdapter private constructor(
    private val nova: LibTerminalSession,
    private val shellPath: String,
    private val cwd: String
) : com.termux.terminal.TerminalSession(shellPath, cwd, emptyArray(), emptyArray(), 5000, null) {

    /** 对应 Nova 会话 id（供 ComposeSessionManager.switchTo/killSession 使用）。 */
    val novaId: Int
        get() = nova.id

    /** 直接访问底层 Nova 会话（获取 pid/exited 等兼容状态）。 */
    val novaSession: LibTerminalSession
        get() = nova

    override fun isRunning(): Boolean = nova.isRunning.value

    override fun write(data: ByteArray, offset: Int, count: Int) {
        val payload = if (offset == 0 && count == data.size) data else java.util.Arrays.copyOfRange(data, offset, offset + count)
        nova.write(payload)
    }

    override fun finishIfRunning() {
        nova.finishIfRunning()
    }

    override fun getShellPid(): Int = TerminalSessionCompat.getPid(nova.id)

    override fun getCwd(): String? = cwd

    companion object {

        /**
         * 在 Compose 模式下创建"新终端"：直接拉起 Nova 会话并把其 Java 句柄包装成
         * [TermuxSession] 返回（与旧 ComposeSessionBridge.createComposeMirrorSession
         * 等价，但不再创建经典空壳镜像、不维护注册表、不做写转发）。
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
            val args = executionCommand.arguments ?: emptyArray()
            val env = envClient.buildEnvironment(context, executionCommand.isFailsafe, workingDirectory)

            val nova = ComposeSessionManager.getInstance(context).createSession(
                shellPath = shellPath,
                cwd = workingDirectory,
                args = args,
                env = env,
                sessionName = sessionName ?: ""
            )

            val adapter = NovaTerminalSessionAdapter(nova, shellPath, workingDirectory)
            adapter.mSessionName = sessionName
            return TermuxSession.wrap(adapter, executionCommand, null, false)
        }
    }
}