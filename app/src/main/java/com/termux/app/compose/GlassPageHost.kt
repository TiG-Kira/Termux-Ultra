package com.termux.app.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * How far a scrolling page holds its first item below the top of the screen.
 *
 * The glass top bar occludes with a gradient band of its own, so a page must not push its content
 * below the bar — that would leave the band nothing to cover. Instead the page lets its content run
 * under the bar and spends this much as scrollable `contentPadding`, so the first item still starts
 * clear of the bar while everything after it passes beneath the gradient.
 */
val LocalTopBarClearance = compositionLocalOf { 0.dp }

/**
 * [padding] with its top stripped off, for a page whose content has to pass under the glass top bar.
 *
 * The bottom and the sides are kept, which is where a page's own insets live.
 */
@Composable
fun pagePaddingWithoutTop(padding: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = padding.calculateStartPadding(direction),
        end = padding.calculateEndPadding(direction),
        bottom = padding.calculateBottomPadding(),
    )
}

/**
 * The backdrop a standalone page's glass top bar samples.
 *
 * Pages outside [MainScreen] — every standalone activity and every sub-screen — cannot see the tab
 * host's backdrop, so each captures its own. Call once per page, hand [backdrop] to the bar and
 * [layerModifier] to the page's [top.yukonga.miuix.kmp.basic.Scaffold]; everything the page draws
 * then sits behind the bar's glass.
 *
 * ```
 * val page = rememberGlassPageBackdrop()
 * Scaffold(modifier = page.layerModifier, topBar = {
 *     GlassTopAppBar(title = …, backdrop = page.backdrop, …)
 * }) { … }
 * ```
 */
class GlassPageBackdrop internal constructor(
    val backdrop: Backdrop,
    val layerModifier: Modifier,
)

@Composable
fun rememberGlassPageBackdrop(): GlassPageBackdrop {
    val backdrop = rememberLayerBackdrop()
    val layerModifier = remember(backdrop) { Modifier.layerBackdrop(backdrop) }
    return remember(backdrop, layerModifier) { GlassPageBackdrop(backdrop, layerModifier) }
}

/**
 * Provides [backdrop] to every [GlassTopAppBar] in [content] and captures the page as one layer.
 *
 * The one-call form, equivalent to [rememberGlassPageBackdrop] plus wiring it to a `Scaffold`.
 * Prefer the explicit form when the page's `Scaffold` takes a modifier of its own.
 */
@Composable
fun GlassPageBackdropHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val page = rememberGlassPageBackdrop()
    CompositionLocalProvider(LocalGlassTopAppBarBackdrop provides page.backdrop) {
        Box(
            modifier = modifier.fillMaxSize().then(page.layerModifier),
            content = content,
        )
    }
}

/**
 * Wraps a page whose glass top bar falls back to its solid pill fill.
 *
 * The bar still renders as glass — pill, stroke, shadow — but it has no backdrop to sample, which
 * is what a bar over an opaque surface wants. Cheaper than [GlassPageBackdropHost]: no extra layer
 * is captured or blurred every frame.
 */
@Composable
fun GlassPageWithoutBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val noBackdrop = remember { null as Backdrop? }
    CompositionLocalProvider(LocalGlassTopAppBarBackdrop provides noBackdrop) {
        Box(modifier = modifier.fillMaxSize(), content = content)
    }
}
