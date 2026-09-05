package com.termux.app.compose

import android.app.Activity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.TermuxActivity
import com.termux.app.compose.terminal.ComposeSessionManager
import com.termux.app.compose.terminal.ComposeTerminalSettings
import com.termux.app.compose.terminal.engine.TerminalSession as LibTerminalSession

/**
 * Bridge helpers used by [TermuxActivity] (Java) to invoke Compose-only
 * APIs (setContent, KiTerminalTheme, etc.) that are awkward or impossible
 * to call directly from Java.
 */
object TermuxActivityBridge {

    /**
     * Replace the current Activity window content with the Compose-based
     * TerminalDetailScreen, wrapped by KiTerminalTheme (Miuix theme).
     *
     * Compose 模式下，会话由 ComposeSessionManager 单例持久管理：
     * - 首次调用：创建新 shell 会话
     * - 后续调用（已有会话）：直接显示当前会话，不新建
     * - onBack 只退出 Activity，不 kill 会话（保持后台运行）
     */
    @JvmStatic
    fun setTerminalDetailContent(
        activity: TermuxActivity,
        terminalView: com.termux.view.TerminalView,
        onBack: Runnable,
    ) {
        val isComposeMode = TerminalRuntimeCore.isComposeMode(activity)

        if (isComposeMode) {
            startComposeModeTerminal(activity, onBack)
        } else {
            // Java+NDK 模式（默认）
            activity.setContent {
                val navDispatcher = NavigationHelper.createDispatcher()
                val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
                CompositionLocalProvider(
                    LocalNavigationEventDispatcherOwner provides navDispatcherOwner
                ) {
                    KiTerminalTheme(
                        manageSystemBars = false,
                        content = {
                            TerminalDetailScreen(
                                activity = activity,
                                terminalView = terminalView,
                                onBack = { onBack.run() },
                                overlayMode = false,
                            )
                        }
                    )
                }
            }
        }
    }

    private fun startComposeModeTerminal(
        activity: TermuxActivity,
        onBack: Runnable
    ) {
        val sessionManager = ComposeSessionManager.getInstance(activity)

        // 每次进入 Compose 终端都重新从 ~/.termux/colors.properties 与 font.ttf 读取 Styling，
        // 保证与 Java 模式的主题/字体始终保持同步（即使此前在设置页改过主题）
        ComposeTerminalSettings.init(activity)
        ComposeTerminalSettings.reloadFromStylingDisk()

        // 优先处理 Java 接口传入的镜像句柄（第三方页面"新会话/tmux 执行"等），
        // 使 Compose 终端直接展示对应的 Compose 会话；无句柄时维持原有行为。
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
     * 根据 Activity Intent 中携带的 "sessionHandle"（Compose 镜像句柄）解析目标 Compose 会话；
     * 无句柄/解析失败时退回：当前会话 → 第一个会话 → 新建默认 shell。
     */
    private fun resolveSessionFromIntent(
        activity: TermuxActivity,
        sessionManager: ComposeSessionManager
    ): com.termux.app.compose.terminal.engine.TerminalSession {
        val handle = try { activity.intent.getStringExtra("sessionHandle") } catch (_: Throwable) { null }
        val sessionId = ComposeSessionBridge.resolveComposeSessionId(handle)
        if (sessionId != null) {
            sessionManager.sessions.value.firstOrNull { it.session.id == sessionId }?.session?.let {
                return it
            }
        }
        return sessionManager.currentSession
            ?: sessionManager.sessions.value.firstOrNull()?.session
            ?: sessionManager.createDefaultSession()
    }
}
