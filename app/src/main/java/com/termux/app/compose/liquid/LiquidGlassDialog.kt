// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.termux.app.compose.liquid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.RoundedRectangle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 液态玻璃弹层的圆角（与 miuix 对话框的 32dp 对齐，比 glass 默认 28dp 更硬朗）。 */
val LiquidDialogCornerRadius: Dp = 32.dp

/**
 * 液态玻璃弹层（Kyant0/AndroidLiquidGlass 独立实现）。
 *
 * 结构与 miuix `GlassDialog` 一致（Scrim 点击关闭 / 底部滑入或居中缩放 / IME 与导航栏避让），
 * 但材质换成液态玻璃：`vibrancy + blur(12dp) + lens(24dp, 28dp, chromaticAberration)`，
 * 28/32dp 圆角用 `RoundedRectangle` 而不是 `Capsule`，透镜 shader 才拿得到角半径。
 *
 * 与柔光玻璃+ 的差别在于弹层边缘会出现明显的色散彩边（厚玻璃特征），
 * 且内阴影换成 32dp 大圆角的深投影，观感更「液态」。
 *
 * 注意：`backdrop` 必须是渲染在本弹层子树**之外**的取景层（主屏内容 Box 注册那条），
 * 否则会自采样成环。
 */
@Composable
fun LiquidGlassDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomCenter,
    scrimColor: Color = Color.Black,
    cornerRadius: Dp = LiquidDialogCornerRadius,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val scrimAlpha = if (isDark) 0.60f else 0.30f

    // 延迟一帧再置可见：否则首次组合时 visible 已是 true，AnimatedVisibility 判定为首帧可见而不播进入动画
    val animVisible = remember { mutableStateOf(false) }
    LaunchedEffect(visible) { animVisible.value = visible }

    val scrim by animateFloatAsState(
        targetValue = if (animVisible.value) scrimAlpha else 0f,
        animationSpec = tween(if (animVisible.value) 260 else 200),
        label = "liquidDialogScrim",
    )

    val interactionSource = remember { MutableInteractionSource() }

    val shape: () -> Shape = remember(cornerRadius) { { RoundedRectangle(cornerRadius) } }
    val effects: BackdropEffectScope.() -> Unit = remember {
        {
            vibrancy()
            blur(12.dp.toPx())
            // 弹层面积大，折射量比底栏更大一档，并始终开色散（厚玻璃的边缘彩边）
            lens(24.dp.toPx(), 28.dp.toPx(), chromaticAberration = true)
        }
    }
    val highlight = remember { { LiquidBarHighlight } }
    val shadow = remember { { LiquidBarShadow } }
    val surfaceColor: Color = if (isDark) {
        Color(0xFF1C1C1E).copy(alpha = 0.55f)
    } else {
        Color(0xFFF7F7FA).copy(alpha = 0.55f)
    }
    val onDrawSurface: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit =
        remember(surfaceColor) { { drawRect(surfaceColor) } }

    // 不在此处 early-return：AnimatedVisibility 必须先在树里经历「不可见」这一帧，
    // 随后 animVisible 翻成 true 才会播进入动画。外层 DialogLayout 负责窗口级显隐，
    // 隐藏时这个 Box 不会被用户点到（与 miuix GlassDialog 同款结构）。
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(scrimColor.copy(alpha = scrim)) }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismissRequest,
                ),
        )
        val isBottomAttached = alignment == Alignment.BottomCenter
        AnimatedVisibility(
            visible = animVisible.value,
            modifier = Modifier
                .align(alignment)
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = if (isBottomAttached) 12.dp else 0.dp,
                )
                .let { m ->
                    val withIme = m.imePadding()
                    if (isBottomAttached) withIme.navigationBarsPadding() else withIme
                },
            enter = if (isBottomAttached) {
                slideInVertically(animationSpec = tween(280), initialOffsetY = { it }) +
                    fadeIn(animationSpec = tween(200))
            } else {
                scaleIn(animationSpec = tween(280), initialScale = 0.86f) +
                    fadeIn(animationSpec = tween(200))
            },
            exit = if (isBottomAttached) {
                slideOutVertically(animationSpec = tween(220), targetOffsetY = { it }) +
                    fadeOut(animationSpec = tween(160))
            } else {
                scaleOut(animationSpec = tween(220), targetScale = 0.9f) +
                    fadeOut(animationSpec = tween(160))
            },
        ) {
            Column(
                modifier = Modifier
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = shape,
                        effects = effects,
                        highlight = highlight,
                        shadow = shadow,
                        onDrawSurface = onDrawSurface,
                    )
                    .pointerInput(Unit) {
                        detectTapGestures { /* 吞掉弹层内部点击，避免穿透到 Scrim */ }
                    }
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}
