package com.termux.app.compose

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.termux.app.compose.liquid.LocalLiquidBackdrop
import com.termux.app.compose.liquid.LocalLiquidTopBarEnabled
import top.yukonga.miuix.kmp.blur.layerBackdrop as miuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop as rememberMiuixLayerBackdrop
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

    // 外观样式的统一入口：所有 Activity（含 40+ 个独立页面）都从这里拿当前样式。
    // Application.onCreate 只覆盖进程启动，切换样式仅重启 Activity，组合期需再同步一次。
    UiStylePrefs.init(context)
    val uiStyle by UiStylePrefs.uiStyle.collectAsState()
    applyUiStyleTokens(uiStyle)
    val styled = uiStyle != HyperGlassStyle.Default

    // 主题级取景层只在这里创建并下发；真正挂载由 rememberGlassPageBackdrop 追加到
    // 各页面内容 Box 的 contentModifier。挂在本函数的 Box 上会把顶栏一起录进去，
    // 顶栏再采样它就会自采样并触发 runtime shader 崩溃。
    val softLayer = rememberMiuixLayerBackdrop()
    val liquidLayer = rememberLayerBackdrop()
    // 只挂当前样式真正会采样的那一条：Soft 用 miuix、Liquid 用 kyant。
    // 两条一起挂会让另一条每帧空录一层（终端类高频重绘页面尤其明显）。
    val pageLayers = remember(softLayer, liquidLayer, uiStyle) {
        if (styled) {
            PageBackdropLayers(
                soft = if (uiStyle is HyperGlassStyle.Soft) Modifier.miuixLayerBackdrop(softLayer) else Modifier,
                liquid = if (uiStyle is HyperGlassStyle.Liquid) Modifier.layerBackdrop(liquidLayer) else Modifier,
            )
        } else {
            null
        }
    }

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

    CompositionLocalProvider(
        LocalUiStyle provides uiStyle,
        LocalPageBackdropLayers provides pageLayers,
        LocalHyperGlassBackdrop provides if (styled) softLayer else null,
        LocalLiquidBackdrop provides if (uiStyle is HyperGlassStyle.Liquid) liquidLayer else null,
        LocalLiquidTopBarEnabled provides (uiStyle is HyperGlassStyle.Liquid),
    ) {
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
}
