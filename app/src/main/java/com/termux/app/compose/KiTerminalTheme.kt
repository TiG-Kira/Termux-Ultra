package com.termux.app.compose

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    // 必须在订阅前同步加载落盘值，否则开了动态取色会先闪一帧默认配色。
    AppThemePrefs.init(context)
    val materialYou by AppThemePrefs.materialYouEnabled.collectAsState()

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
    val colorSchemeMode = if (materialYou && ApiCompat.isFeatureUsable(context, ApiCompat.Feature.MIUIX_DYNAMIC_COLOR)) {
        ColorSchemeMode.MonetSystem
    } else {
        ColorSchemeMode.System
    }

    MiuixTheme(
        // ThemeController 的 colorSchemeMode 是构造参数且只读，切换必须重建实例。
        controller = remember(colorSchemeMode) { ThemeController(colorSchemeMode = colorSchemeMode) },
        content = {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize()
            ) {
                content()
            }
        }
    )
}
