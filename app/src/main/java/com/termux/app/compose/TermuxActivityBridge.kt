package com.termux.app.compose

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.TermuxActivity
import com.termux.app.compose.terminal.ComposeSessionManager
import com.termux.app.compose.terminal.engine.TerminalSession as LibTerminalSession
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.shell.TermuxShellUtils
import com.termux.view.TerminalView
import java.io.File

/**
 * Bridge helpers used by [TermuxActivity] (Java) to invoke Compose-only
 * APIs (setContent, KiTerminalTheme, etc.) that are awkward or impossible
 * to call directly from Java.
 */
object TermuxActivityBridge {

    /**
     * Replace the current Activity window content with the Compose-based
     * TerminalDetailScreen, wrapped by KiTerminalTheme (Miuix theme).
     */
    @JvmStatic
    fun setTerminalDetailContent(
        activity: TermuxActivity,
        terminalView: TerminalView,
        onBack: Runnable,
    ) {
        val isComposeMode = TerminalRuntimeCore.isComposeMode(activity)

        if (isComposeMode) {
            // Compose 模式：创建 libterminal TerminalSession
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
        val envClient = TermuxShellEnvironmentClient()

        // 工作目录
        val prefs = activity.getSharedPreferences("termux_preferences", Activity.MODE_PRIVATE)
        val workingDir = prefs.getString("current_session_dir", null)
            ?: envClient.getDefaultWorkingDirectoryPath()

        // Shell 环境（完整 Termux 环境）
        val env = TermuxShellUtils.buildEnvironment(activity, false, workingDir)

        // 选择 shell
        val defaultBinPath = envClient.getDefaultBinPath().ifEmpty { "/system/bin" }
        var shellPath: String? = null
        var isLoginShell = false

        for (shellBinary in arrayOf("login", "bash", "zsh")) {
            val shellFile = File(defaultBinPath, shellBinary)
            if (shellFile.canExecute()) {
                shellPath = shellFile.absolutePath
                isLoginShell = true
                break
            }
        }

        if (shellPath == null) {
            shellPath = "/system/bin/sh"
        }

        // 处理 arguments：login shell 的 argv[0] 加 "-" 前缀
        val processArgs = envClient.setupProcessArgs(shellPath, emptyArray())
        val executable = processArgs[0]
        val shellBasename = executable.substringAfterLast('/')
        val argv0 = if (isLoginShell) "-$shellBasename" else shellBasename
        val args = arrayOf(argv0) + processArgs.drop(1)

        // 创建 Compose 模式会话
        val sessionManager = ComposeSessionManager.getInstance(activity)
        val composePrefs = activity.getSharedPreferences("compose_terminal", Activity.MODE_PRIVATE)
        val textSize = composePrefs.getInt("font_size", 14)
        val cursorBlink = composePrefs.getBoolean("cursor_blink", true)

        val session = sessionManager.createSession(
            shellPath = executable,
            cwd = workingDir,
            args = args,
            env = env,
            sessionName = "Compose $shellBasename"
        )

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
                            session = session,
                            textSize = textSize,
                            cursorBlink = cursorBlink,
                            onBack = {
                                session.finishIfRunning()
                                sessionManager.killSession(session.id)
                                onBack.run()
                            }
                        )
                    }
                )
            }
        }
    }
}
