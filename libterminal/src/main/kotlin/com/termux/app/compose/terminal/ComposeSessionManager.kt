package com.termux.app.compose.terminal

import android.content.Context
import com.termux.app.compose.terminal.engine.TerminalSession
import com.termux.app.compose.terminal.process.ITerminalProcess
import com.termux.app.compose.terminal.process.TermuxProcessBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Compose 模式下的终端会话管理器。
 *
 * 管理 libterminal 的 TerminalSession 实例生命周期：创建、列出、结束。
 * 与 TermuxService 的 TermuxSession 列表并行存在，根据 TerminalRuntimeCore
 * 的选择决定使用哪套管理体系。
 */
class ComposeSessionManager private constructor(private val context: Context) {

    data class SessionInfo(
        val session: TerminalSession,
        val name: String
    )

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private var nextId = 1

    /** 创建一个新的终端会话并立即启动 shell。 */
    fun createSession(
        shellPath: String,
        cwd: String,
        args: Array<String>,
        env: Array<String>,
        sessionName: String = "Session $nextId"
    ): TerminalSession {
        val processFactory: (Int, Int, Int, Int) -> ITerminalProcess = { rows, cols, cw, ch ->
            TermuxProcessBridge(shellPath, cwd, args, env, rows, cols, cw, ch)
        }

        val session = TerminalSession(
            id = nextId++,
            sessionName = kotlinx.coroutines.flow.MutableStateFlow(sessionName),
            processFactory = processFactory
        )

        _sessions.value = _sessions.value + SessionInfo(session, sessionName)
        session.execute()
        return session
    }

    /** 结束指定会话。 */
    fun killSession(sessionId: Int) {
        val info = _sessions.value.firstOrNull { it.session.id == sessionId } ?: return
        info.session.finishIfRunning()
        _sessions.value = _sessions.value.filter { it.session.id != sessionId }
    }

    /** 结束所有会话。 */
    fun killAllSessions() {
        _sessions.value.forEach { it.session.finishIfRunning() }
        _sessions.value = emptyList()
        nextId = 1
    }

    companion object {
        @Volatile
        private var instance: ComposeSessionManager? = null

        fun getInstance(context: Context): ComposeSessionManager {
            return instance ?: synchronized(this) {
                instance ?: ComposeSessionManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
