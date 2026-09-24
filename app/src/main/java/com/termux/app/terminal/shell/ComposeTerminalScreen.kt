package com.termux.app.terminal.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.awkoo.libterminal.color.TerminalColorScheme
import com.awkoo.libterminal.engine.TerminalSession
import com.awkoo.libterminal.engine.TerminalCursorStyle
import com.awkoo.libterminal.view.TerminalView as LibTerminalView

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
    terminalViewRef: MutableState<LibTerminalView?>,
    useLightTheme: Boolean = false,
    textSize: Int = 14,
    cursorBlink: Boolean = true,
    cursorStyle: TerminalCursorStyle = TerminalCursorStyle.BAR,
    textBlinking: Boolean = true,
    colorScheme: TerminalColorScheme? = null,
    typeface: android.graphics.Typeface? = null
) {
    var terminalView by remember { mutableStateOf<LibTerminalView?>(null) }
    var lastSessionId by remember { mutableStateOf<Int?>(null) }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        val resolvedScheme = colorScheme
            ?: if (useLightTheme) TerminalColorScheme.light() else TerminalColorScheme.dark()

        AndroidView(
            factory = { ctx ->
                LibTerminalView(ctx).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    defaultFocusHighlightEnabled = false
                    this.textSize = textSize
                    this.typeface = typeface ?: android.graphics.Typeface.MONOSPACE
                    this.colorScheme = resolvedScheme
                    this.cursorBlinking = cursorBlink
                    this.cursorStyle = cursorStyle
                    this.textBlinking = textBlinking
                }.also { tv ->
                    terminalView = tv
                    terminalViewRef.value = tv
                }
            },
            update = { tv ->
                tv.textSize = textSize
                tv.typeface = typeface ?: android.graphics.Typeface.MONOSPACE
                tv.colorScheme = resolvedScheme
                tv.cursorBlinking = cursorBlink
                tv.cursorStyle = cursorStyle
                tv.textBlinking = textBlinking
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
            terminalViewRef.value = null
        }
    }
}
