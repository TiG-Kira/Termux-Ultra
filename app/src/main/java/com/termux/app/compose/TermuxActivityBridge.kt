package com.termux.app.compose

import android.app.Activity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.TermuxActivity
import com.termux.app.terminal.shell.ComposeSessionManager
import com.termux.app.terminal.shell.ComposeTerminalSettings
import com.termux.app.terminal.shell.pid

/**
* Bridge helpers used by [TermuxActivity] (Java) to invoke the Compose-based
     * terminal UI (setContent, KiTerminalTheme, etc.) that are awkward or
     * impossible to call directly from Java.
 */
object TermuxActivityBridge {

    /**
     * Replace the current Activity window content with the Compose-based
     * TerminalDetailScreenCompose, wrapped by KiTerminalTheme (Miuix theme).
     *
     * 会话由 ComposeSessionManager 单例持久管理：
     * - 首次调用：创建新 shell 会话
     * - 后续调用（已有会话）：直接显示当前会话，不新建
     * - onBack 只退出 Activity，不 kill 会话（保持后台运行）
     */
    @JvmStatic
    fun setTerminalDetailContent(
        activity: TermuxActivity,
        onBack: Runnable,
    ) {
        startComposeModeTerminal(activity, onBack)
    }

    private fun startComposeModeTerminal(
        activity: TermuxActivity,
        onBack: Runnable
    ) {
        val sessionManager = ComposeSessionManager.getInstance(activity)

        // 每次进入终端都重新从 ~/.termux/colors.properties 与 font.ttf 读取 Styling，
        // 保证与 Java 模式的主题/字体始终保持同步（即使此前在设置页改过主题）
        ComposeTerminalSettings.init(activity)
        ComposeTerminalSettings.reloadFromStylingDisk()

        // 优先处理 Java 接口传入的会话句柄（第三方页面"新会话/tmux 执行"等），
        // 使终端直接展示对应的会话；无句柄时维持原有行为。
        val targetSession = resolveSessionFromIntent(activity, sessionManager)

        // 效仿 Java 版策略：未初始化的会话（新建后未进入过，pid=0）在用户手动点击进入
        // 终端控制台的那一刻才真正初始化（拉起进程）
        if (targetSession.pid == 0) {
            targetSession.execute()
        }

        sessionManager.switchTo(targetSession.id)

        activity.setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                KiTerminalTheme(
                    manageSystemBars = false,
                    content = {
                        TerminalDetailScreenCompose(
                            sessionManager = sessionManager,
                            session = targetSession,
                            onBack = {
                                // 修复：返回 Activity 不 kill 会话！
                                onBack.run()
                            }
                        )
                    }
                )
            }
        }
    }

    /**
     * 根据 Activity Intent 中携带的 "sessionHandle"（适配会话句柄）解析目标终端会话；
     * 无句柄/解析失败时退回：当前会话 → 第一个会话 → 新建默认 shell。
     */
    private fun resolveSessionFromIntent(
        activity: TermuxActivity,
        sessionManager: ComposeSessionManager
    ): com.awkoo.libterminal.engine.TerminalSession {
        val handle = try { activity.intent.getStringExtra("sessionHandle") } catch (_: Throwable) { null }
        if (handle != null) {
            val terminal = activity.termuxService?.termuxSessions?.firstOrNull { handle == it.getTerminalSession().mHandle }?.getTerminalSession()
            if (terminal is com.termux.app.terminal.shell.TerminalSessionAdapter) {
                sessionManager.sessions.value.firstOrNull { it.session.id == terminal.sessionId }?.session?.let {
                    return it
                }
            }
        }
        return sessionManager.currentSession
            ?: sessionManager.sessions.value.firstOrNull()?.session
            ?: sessionManager.createDefaultSession()
    }
}
