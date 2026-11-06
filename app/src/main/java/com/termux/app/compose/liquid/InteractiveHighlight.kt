// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.termux.app.compose.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * AndroidLiquidGlass demo（`catalog/utils/InteractiveHighlight.kt`）的等价实现。
 *
 * 底栏表面那道**跟随胶囊位置的镜面高光**：按压/拖动时亮起（径向白光斑），
 * 中心始终钉在胶囊中心（[position] 回调算），拖过哪里亮到哪里——
 * 这是"液滴在玻璃上滚动"观感的重要一环。
 *
 * - [modifier]：挂在**底栏本体**上（在玻璃渲染之上叠画光斑）；
 * - [gestureModifier]：挂在**胶囊**上（捕获按压，驱动亮起/熄灭）。
 */
class InteractiveHighlight(
    animationScope: CoroutineScope,
    /** 光斑中心：`position(底栏尺寸, 手势位置)`。demo 里直接用胶囊当前位置算。 */
    private val position: (size: Size, offset: Offset) -> Offset,
) {
    private val scope = animationScope

    private val progress = Animatable(0f)
    private var gestureOffset by mutableStateOf(Offset.Zero)

    /** 挂在底栏本体上：在内容之上叠画径向高光。 */
    val modifier: Modifier = Modifier.drawWithContent {
        drawContent()
        val p = progress.value
        if (p > 0f) {
            val center = position(size, gestureOffset)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.10f * p),
                        Color.White.copy(alpha = 0.0f),
                    ),
                    center = center,
                    // 光斑半径随进度扩散，到顶约半个屏宽
                    radius = size.minDimension * (0.3f + 0.7f * p),
                ),
                size = size,
            )
        }
    }

    /** 挂在胶囊上：按下亮起、抬手熄灭。 */
    val gestureModifier: Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            gestureOffset = down.position
            scope.launch { progress.animateTo(1f, spring(1f, Spring.StiffnessMedium)) }
            waitForUpOrCancellation()
            scope.launch { progress.animateTo(0f, spring(1f, Spring.StiffnessMediumLow)) }
        }
    }
}
