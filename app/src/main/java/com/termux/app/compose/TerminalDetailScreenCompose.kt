package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.termux.app.compose.terminal.ComposeTerminalScreen
import com.termux.app.compose.terminal.engine.TerminalSession as LibTerminalSession
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * Kotlin+Compose 模式下的终端详情屏幕。
 *
 * 使用 libterminal 的 TerminalView + TerminalSession 渲染终端，
 * 外壳保持与 Java+NDK 模式一致的 TopAppBar + Scaffold 结构。
 */
@Composable
fun TerminalDetailScreenCompose(
    session: LibTerminalSession,
    textSize: Int = 14,
    cursorBlink: Boolean = true,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("compose_terminal", android.content.Context.MODE_PRIVATE) }
    var currentFontSize by remember { mutableStateOf(prefs.getInt("font_size", textSize)) }
    var currentCursorBlink by remember { mutableStateOf(prefs.getBoolean("cursor_blink", cursorBlink)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "终端",
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                }
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
        ) {
            ComposeTerminalScreen(
                session = session,
                modifier = Modifier.fillMaxSize(),
                useLightTheme = false,
                textSize = currentFontSize,
                cursorBlink = currentCursorBlink,
                composePrefs = prefs
            )
        }
    }
}
