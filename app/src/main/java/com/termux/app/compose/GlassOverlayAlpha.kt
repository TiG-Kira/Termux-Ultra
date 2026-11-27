package com.termux.app.compose

/**
 * 玻璃弹层蒙层透明度（亮/暗主题两档）。
 *
 * miuix-glass 0.9.5 官方只提供单一 [top.yukonga.miuix.kmp.glass.GlassDialogDefaults.ScrimColor]，
 * 没有按主题分档的常量；这里保留 vendored 时代的取值口径。
 */
internal object GlassOverlayAlpha {
    val ScrimAlphaLight: Float = 0.3f
    val ScrimAlphaDark: Float = 0.6f
}
