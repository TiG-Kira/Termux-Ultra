package com.termux.app.compose.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    colorScheme: TerminalColorScheme? = null,
    typeface: android.graphics.Typeface? = null
) {
    var terminalView by remember { mutableStateOf<LibTerminalView?>(null) }
    var lastSessionId by remember { mutableStateOf<Int?>(null) }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        AndroidView(
            factory = { ctx ->
                LibTerminalView(ctx, useLightTheme).apply {
                    this.textSize = textSize
                    this.typeface = typeface ?: android.graphics.Typeface.MONOSPACE
                    this.customColorScheme = colorScheme
                }.also { tv ->
                    terminalView = tv
                }
            },
            update = { tv ->
                tv.useLightTheme = useLightTheme
                tv.textSize = textSize
                tv.typeface = typeface ?: android.graphics.Typeface.MONOSPACE
                tv.customColorScheme = colorScheme
                // cursorBlink 由 TerminalEmulator 内部管理，通过 TerminalView.setBlinkingEnabled setter 无法直接设置，
                // 但 session.emulator.isTextBlinkingEnabled 可动态控制
                tv.currentSession?.emulator?.isTextBlinkingEnabled = cursorBlink
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
