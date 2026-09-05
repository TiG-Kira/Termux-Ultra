package com.termux.app.compose.terminal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.app.compose.terminal.color.TerminalColorScheme
import com.termux.app.compose.terminal.engine.TerminalSession
import com.termux.app.compose.terminal.view.TerminalView as LibTerminalView

/**
 * Compose 模式下的终端渲染屏幕。
 *
 * 使用 AndroidView 包装 libterminal 的 TerminalView，
 * 绑定 TerminalSession，实现终端显示与交互。
 */
@Composable
fun ComposeTerminalScreen(
    session: TerminalSession?,
    modifier: Modifier = Modifier,
    useLightTheme: Boolean = false,
    textSize: Int = 14,
    cursorBlink: Boolean = true,
    composePrefs: android.content.SharedPreferences? = null
) {
    val context = LocalContext.current
    var terminalView by remember { mutableStateOf<LibTerminalView?>(null) }
    var lastSessionId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(session) {
        terminalView?.let { tv ->
            if (session != null && lastSessionId != session.id) {
                tv.currentSession = session
                lastSessionId = session.id
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (useLightTheme) Color(0xFFFFFFFF) else Color(0xFF121212)
            )
    ) {
        AndroidView(
            factory = { ctx ->
                LibTerminalView(ctx, useLightTheme).apply {
                    this.textSize = textSize
                    this.typeface = android.graphics.Typeface.MONOSPACE
                }.also { tv ->
                    terminalView = tv
                }
            },
            update = { tv ->
                tv.useLightTheme = useLightTheme
                tv.textSize = textSize
                if (session != null && lastSessionId != session.id) {
                    tv.currentSession = session
                    lastSessionId = session.id
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            terminalView?.dispose()
            terminalView = null
        }
    }
}
