package com.termux.app.compose

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun KiTerminalTheme(
    content: @Composable () -> Unit
) {
    MiuixTheme(
        controller = ThemeController(colorSchemeMode = ColorSchemeMode.Light, isDark = false),
        content = content
    )
}