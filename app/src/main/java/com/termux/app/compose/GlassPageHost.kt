package com.termux.app.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
 * How much of the screen [padding]'s top bar is covering right now.
 *
 * Read it from the `Scaffold`'s own `padding` rather than computing a height: the bar collapses by
 * shrinking its own measured height, so this tracks the collapse exactly. A constant cannot — it
 * stays at the expanded height forever, and the difference is left showing as an opaque band under
 * the collapsed bar.
 */
@Composable
fun topBarClearance(padding: PaddingValues): Dp = padding.calculateTopPadding()

/**
 * The bar's height folded into a page's own scrollable `contentPadding`, on top of [padding]'s.
 *
 * The clearance has to live on the scrollable content, never as padding on the page root: root
 * padding is a dead band that never moves, so once the bar collapses into its small form the band
 * is taller than the bar and shows as an opaque slab under it. As content padding it is scrolled
 * away, the first screenful still starts below the bar's full height, and everything past it runs
 * under the bar's gradient band.
 */
@Composable
fun standaloneContentPadding(
    padding: PaddingValues,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    start: Dp = 0.dp,
    end: Dp = 0.dp,
): PaddingValues = PaddingValues(
    top = top + topBarClearance(padding),
    bottom = bottom,
    start = start,
    end = end,
)

/**
 * The backdrop a standalone page's glass top bar samples.
 *
 * Pages outside [MainScreen] — every standalone activity and every sub-screen — cannot see the tab
 * host's backdrop, so each captures its own. Call once per page and hand [backdrop] to the bar.
 *
 * ```
 * val page = rememberGlassPageBackdrop()
 * Scaffold(topBar = { GlassTopAppBar(title = …, backdrop = page.backdrop, …) }) { padding ->
 *     Box(Modifier.fillMaxSize().then(page.contentModifier).padding(padding)) { … }
 * }
 * ```
 *
 * The capture belongs on the content, never on the `Scaffold`: [contentModifier] takes the content's
 * children, so the bar above it stays outside the layer. A layer that enclosed the bar would have
 * the bar sampling a texture containing itself, and the runtime shaders it draws for that abort the
 * process the moment the bar collapses and re-captures.
 */
class GlassPageBackdrop internal constructor(
    val layer: LayerBackdrop,
) {
    /** What [GlassTopAppBar] samples. */
    val backdrop: Backdrop get() = layer

    /** Marks the page content as what [backdrop] captures. */
    val contentModifier: Modifier = Modifier.layerBackdrop(layer)
}

@Composable
fun rememberGlassPageBackdrop(): GlassPageBackdrop {
    val layer = rememberLayerBackdrop()
    return remember(layer) { GlassPageBackdrop(layer) }
}

/**
 * Provides [backdrop] to every [GlassTopAppBar] in [content] and captures the page as one layer.
 *
 * The one-call form, equivalent to [rememberGlassPageBackdrop] plus wiring it to a `Scaffold`.
 * Prefer the explicit form when the page's content takes a modifier of its own.
 */
@Composable
fun GlassPageBackdropHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val page = rememberGlassPageBackdrop()
    CompositionLocalProvider(LocalGlassTopAppBarBackdrop provides page.backdrop) {
        Box(
            modifier = modifier.fillMaxSize().then(page.contentModifier),
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
