package com.termux.app.compose

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun KiTerminalTheme(
    statusBarColor: Color = Color.Unspecified,
    navigationBarColor: Color = Color.Unspecified,
    isTerminalDark: Boolean = false,
    manageSystemBars: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode && manageSystemBars) {
        SideEffect {
            val window = (view.context as Activity).window
            // 显式传入了具体颜色才覆盖 window 属性；
            // 否则让 Manifest theme / Activity onCreate 里的设置生效。
            if (statusBarColor != Color.Unspecified) {
                window.statusBarColor = statusBarColor.toArgb()
            }
            if (navigationBarColor != Color.Unspecified) {
                window.navigationBarColor = navigationBarColor.toArgb()
            }
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = if (isTerminalDark) false else !darkTheme
            controller.isAppearanceLightNavigationBars = if (isTerminalDark) false else !darkTheme
        }
    }
    MiuixTheme(
        controller = ThemeController(colorSchemeMode = ColorSchemeMode.System),
        content = {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize()
            ) {
                content()
            }
        }
    )
}
