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
    statusBarColor: Color = Color.Transparent,
    navigationBarColor: Color = Color.Transparent,
    isTerminalDark: Boolean = false,
    manageSystemBars: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode && manageSystemBars) {
        SideEffect {
            val window = (view.context as Activity).window
            // 只有显式传入非透明色才覆盖 Activity 主题。
            // 这样 Theme.Termux.Main 里的 statusBarColor/navigationBarColor = @color/hyper_surface
            // （会跟随亮/暗模式自动选 #F7F7F7 / #1C1B1F）才能正常生效。
            if (statusBarColor != Color.Transparent) {
                window.statusBarColor = statusBarColor.toArgb()
            }
            if (navigationBarColor != Color.Transparent) {
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
