package com.termux.app.plugin

import com.termux.shared.shell.TermuxSession
import com.termux.terminal.TerminalSession

/**
 * TerminalSession → sessionId 的查找表。
 *
 * 当 TermuxSessionClient.onTermuxSessionExited 被触发时，
 * 我们拿到的只有 TermuxSession 引用，没有 sessionId。
 * 所以需要一个 TerminalSession.handle → sessionId 的反向查找。
 */
object PluginPersistentSessionRegistry {

    /** 把 terminalSession.handle 映射到 PluginPersistentSession */
    private val handleToSession = java.util.concurrent.ConcurrentHashMap<String, PluginPersistentSession>()

    /** 注册一个 session（由 PluginManager.openPersistentSession 调用） */
    fun register(session: PluginPersistentSession) {
        val handle = session.termuxSession.terminalSession.mHandle
        handleToSession[handle] = session
    }

    /** 移除（关闭或退出时） */
    fun unregister(session: PluginPersistentSession) {
        val handle = session.termuxSession.terminalSession.mHandle
        handleToSession.remove(handle)
    }

    /** TerminalSession 退出回调入口 —— 通知 PluginManager 清理注册表 */
    fun onSessionExited(terminal: TerminalSession) {
        val handle = terminal.mHandle ?: return
        val session = handleToSession.remove(handle) ?: return
        PluginManager.unregisterSession(session.sessionId)
    }
}
