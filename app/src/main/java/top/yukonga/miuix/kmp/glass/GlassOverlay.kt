// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop

/** Default values for [GlassDialog]. */
object GlassOverlayDefaults {

    /** Corner radius of a dialog. 与 miuix 对话框规范一致（32dp）。 */
    val DialogCornerRadius: Dp = 32.dp

    /** 对话框内容宽度上限，与 miuix 对话框规范一致。 */
    val DialogMaxWidth: Dp = 420.dp

    /** 对话框距屏幕左右/底部的外边距，与 miuix 对话框规范一致。 */
    val DialogOutsideMargin: Dp = 12.dp

    /** Opacity of the scrim behind a dialog in a light theme. */
    val ScrimAlphaLight: Float = 0.3f

    /** Opacity of the scrim behind a dialog in a dark theme. */
    val ScrimAlphaDark: Float = 0.6f
}

/**
 * A dialog on glass, over a scrim.
 *
 * @param visible Whether the dialog is showing.
 * @param onDismissRequest Called when the scrim is tapped.
 * @param backdrop The [Backdrop] supplying the content behind the glass.
 * @param modifier The modifier applied to the box holding the scrim and the panel.
 * @param scrimColor Colour of the scrim behind the panel.
 * @param scrimAlpha Opacity of the scrim once the dialog has settled.
 * @param style The glass material.
 * @param shape The dialog's silhouette.
 * @param maxWidth 内容宽度上限，防止在平板/桌面窗口被拉成长条。
 * @param alpha Opacity multiplier for the material.
 * @param shadow The shadow under the dialog. `null` removes it.
 * @param stroke Optional bloom stroke along the rim.
 * @param onDismissFinished Called once the exit animation has fully played.
 * @param content The dialog's content.
 */
@Composable
fun GlassDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    scrimColor: Color = Color.Black,
    scrimAlpha: Float = GlassOverlayDefaults.ScrimAlphaLight,
    style: GlassStyle = GlassDefaults.Style,
    shape: GlassShape = GlassShape(GlassOverlayDefaults.DialogCornerRadius),
    maxWidth: Dp = GlassOverlayDefaults.DialogMaxWidth,
    alpha: Float = 1f,
    stroke: GlassStroke? = null,
    shadow: GlassShadow? = GlassShadows.ExtraHigh,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // visible 经真实的 false→true 过渡驱动动画：MutableTransitionState 以 false 起步，
    // 首次挂载时 visible 已为 true 也不会直接以终态出现而跳过入场。
    val transitionVisible = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { transitionVisible.targetState = visible }

    val scrim = animateFloatAsState(
        targetValue = if (transitionVisible.targetState) scrimAlpha else 0f,
        animationSpec = if (transitionVisible.targetState) GlassMotion.dialogEnter() else GlassMotion.dialogExit(),
        label = "glassDialogScrim",
    )
    val active by remember {
        derivedStateOf {
            transitionVisible.currentState || transitionVisible.targetState || scrim.value > 0.001f
        }
    }
    rememberGlassPopupBackProgress(
        show = visible,
        active = active,
        enabled = visible,
        retainWhenInactive = false,
        resetSpec = GlassMotion.dialogExit(),
        onDismissRequest = onDismissRequest,
    )
    if (!active) return

    if (onDismissFinished != null) {
        LaunchedEffect(transitionVisible.isIdle) {
            if (transitionVisible.isIdle && !transitionVisible.targetState) {
                onDismissFinished()
            }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(scrimColor.copy(alpha = scrim.value)) }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismissRequest,
                ),
        )
        AnimatedVisibility(
            visibleState = transitionVisible,
            // 与 miuix 对话框落位一致：底部居中 + 12dp 外边距（不是居中浮块）。
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    start = GlassOverlayDefaults.DialogOutsideMargin,
                    end = GlassOverlayDefaults.DialogOutsideMargin,
                    bottom = GlassOverlayDefaults.DialogOutsideMargin,
                ),
            // 底部弹出：自下而上滑入 + 淡入；退场反向。
            enter = slideInVertically(
                animationSpec = GlassMotion.dialogEnter(),
                initialOffsetY = { it / 4 },
            ) + fadeIn(animationSpec = GlassMotion.fadeIn()),
            exit = slideOutVertically(
                animationSpec = GlassMotion.dialogExit(),
                targetOffsetY = { it / 4 },
            ) + fadeOut(animationSpec = GlassMotion.fadeOut()),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .glassPanel(
                        backdrop = backdrop,
                        shape = shape,
                        style = style,
                        alpha = alpha,
                        stroke = stroke,
                        shadow = shadow,
                    )
                    .pointerInput(Unit) {
                        detectTapGestures { /* Consume taps inside the dialog. */ }
                    }
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}
