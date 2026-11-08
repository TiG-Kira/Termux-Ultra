// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT
//
// 移植自 HyperIsland（1812z/HyperIsland, compose/component/LiquidGlassNavigationBar.kt），
// 原始实现来自 compose-miuix-ui 的 IosLiquidGlassNavigationBar（Apache-2.0）。

package com.termux.app.compose.liquid

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 底栏表面跟随手指的镜面高光（HyperIsland 口径）。
 *
 * - 用 `BlendMode.Plus` 叠加两层（整片微亮 + AGSL `smoothstep` 径向光斑）；
 * - 光斑在内容**之下**绘制，不会压暗 tab 图标；
 * - 与 [DampedDragAnimation] 共用双 pass 的 [inspectDragGestures]，不消费手势。
 *
 * 宿主适配：`RuntimeShader` 是 API 33+，本仓 minSdk 28。AGSL 实现被隔离进
 * [AgslHighlight]，主类字段类型不引用 `RuntimeShader`，低版本不会触发类加载，
 * 并回落到等价的径向渐变光斑。
 */
class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    /** 光斑中心：`position(底栏尺寸, 手势位置)`。 */
    private val position: (size: Size, offset: Offset) -> Offset,
) {
    private val pressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(
        Offset.Zero,
        Offset.VectorConverter,
        Offset.VisibilityThreshold,
    )
    private var startPosition = Offset.Zero

    private val agsl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AgslHighlight()
    } else {
        null
    }

    /** 挂在底栏本体：加色混合的镜面光斑，画在内容之下。 */
    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressAnimation.value
        if (progress > 0f) {
            drawRect(Color.White.copy(alpha = 0.06f * progress), blendMode = BlendMode.Plus)
            val highlightPosition = position(size, positionAnimation.value)
            val center = Offset(
                highlightPosition.x.fastCoerceIn(0f, size.width),
                highlightPosition.y.fastCoerceIn(0f, size.height),
            )
            val radius = size.minDimension * 1.2f
            val highlighter = agsl
            if (highlighter != null) {
                highlighter.draw(this, center, radius, progress)
            } else {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f * progress),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
        }
        drawContent()
    }

    /** 挂在胶囊：按下亮起并跟手，抬手熄灭且光斑弹回起点。 */
    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressAnimation.animateTo(1f, spring(0.5f, 300f, 0.001f)) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = { reset() },
            onDragCancel = { reset() },
        ) { change, _ ->
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
    }

    private fun reset() {
        animationScope.launch {
            launch { pressAnimation.animateTo(0f, spring(0.5f, 300f, 0.001f)) }
            launch {
                positionAnimation.animateTo(
                    startPosition,
                    spring(0.5f, 300f, Offset.VisibilityThreshold),
                )
            }
        }
    }
}

/** AGSL 光斑实现（API 33+）：隔离 [RuntimeShader]，低版本不加载这个类。 */
private class AgslHighlight {
    private val shader = RuntimeShader(
        """
        uniform float2 size;
        layout(color) uniform half4 color;
        uniform float radius;
        uniform float2 position;
        half4 main(float2 coord) {
            float dist = distance(coord, position);
            float intensity = smoothstep(radius, radius * 0.5, dist);
            return color * intensity;
        }
        """.trimIndent(),
    )

    fun draw(scope: DrawScope, center: Offset, radius: Float, progress: Float) {
        shader.setFloatUniform("size", scope.size.width, scope.size.height)
        shader.setColorUniform("color", Color.White.copy(alpha = 0.12f * progress).toArgb())
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("position", center.x, center.y)
        scope.drawRect(ShaderBrush(shader), blendMode = BlendMode.Plus)
    }
}
