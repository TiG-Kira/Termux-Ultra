// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.termux.app.compose.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * AndroidLiquidGlass demo（`catalog/utils/DampedDragAnimation.kt`）的等价实现。
 *
 * 底栏胶囊的动画状态机：
 * - [value]：胶囊位置（单位 = tab 下标），阻尼临界弹簧驱动（dampingRatio = 1，无过冲——
 *   "液体"的过冲感来自拉伸与 [InteractiveHighlight]，而不是位置弹跳）；
 * - [velocity]：暴露给拉伸公式（`scaleX /= 1 - v*0.75`，速度越快拉得越长）；
 * - [pressProgress]：按压进度 0..1，玻璃折射强度、高光、内阴影、放大全部乘它——
 *   **静止时玻璃效果为 0，只有灰色提示**，动起来才"活"；
 * - [scaleX] / [scaleY]：`lerp(initialScale, pressedScale, pressProgress)`，
 *   demo 的 pressedScale = 78f/56f ≈ 1.39，按住时胶囊明显鼓起；
 * - [modifier]：拖动手势（`detectDragGestures`），拖动中 `updateValue` 跟手。
 *
 * 点击/程序化切换走 [animateToValue]：先把 pressProgress 拉到 1（玻璃浮现），
 * 位置动画到位后再落回 0（玻璃淡出、凝回灰色提示）——酷安点击切换有完整玻璃
 * 特效而 demo 只有拖动有，这一步是把两者行为对齐的关键。
 *
 * **拖动 → 松手吸附的竞态**（AVD 实证）：`updateValue` 的 snapTo 经 `scope.launch`
 * 排队，松手时最后一个 snapTo 可能还没执行；此时启动的 `animateToValue` 动画会被
 * 随后执行的 snapTo 打断，胶囊就卡在两 tab 中间。所以：
 * - [targetValue] 读同步字段 [pendingTarget]（不是 Animatable.targetValue，它会滞后），
 *   且该字段只记录「拖动意图」，不参与动画目标值；
 * - 每次 `updateValue` 先 cancel 排队中的 [snapJob]，snapTo 用 UNDISPATCHED 立即落地；
 *   `animateToValue` 开头再兜底取消一次；
 * - [dragStartSettled] 区分「拖动刚结束」（起点 = 松手点）与「普通点按 / 连续切换」
 *   （起点 = 真实视觉位置），否则快速连点时胶囊会先回跳到上一个整数目标；
 * - 拖动开始把 [pendingTarget] 重置为 `position.value`，下一轮拖动不会继承
 *   上一轮松手的半程点——那是同一个残留状态换个时机发作。
 */
class DampedDragAnimation(
    animationScope: CoroutineScope,
    initialValue: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
    private val visibilityThreshold: Float,
    private val initialScale: Float,
    private val pressedScale: Float,
    val onDragStarted: () -> Unit = {},
    val onDragStopped: () -> Unit = {},
    val onDrag: (position: Offset, dragAmount: Offset) -> Unit = { _, _ -> },
) {
    private val scope = animationScope

    private val position = Animatable(initialValue)
    private val press = Animatable(0f)

    private var pendingTarget: Float = initialValue
    private var snapJob: Job? = null
    private var dragStartSettled = true

    /** 胶囊当前位置（tab 下标，可为小数 = 正在两 tab 之间）。 */
    val value: Float get() = position.value

    /** 拖动的最新意图位置（松手吸附用它；与 Animatable.targetValue 不同，不滞后）。 */
    val targetValue: Float get() = pendingTarget

    /** 当前速度（tab/秒），拉伸公式用。 */
    val velocity: Float get() = position.velocity

    /** 按压进度 0..1。 */
    val pressProgress: Float get() = press.value

    /** 按压缩放 X（= Y，等比鼓起）。 */
    val scaleX: Float get() = lerp(initialScale, pressedScale, press.value)
    val scaleY: Float get() = lerp(initialScale, pressedScale, press.value)

    /** 拖动手势：按下鼓起、拖动跟手、松手触发 [onDragStopped]（吸附逻辑由调用方做）。 */
    val modifier: Modifier = Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { _ ->
                onDragStarted()
                // 新一轮拖动从胶囊的实际位置起步，不继承上一轮松手的半程点
                pendingTarget = position.value
                dragStartSettled = false
                snapJob?.cancel()
                // 立即落地：同时打断仍在跑的切换动画（动画中途开始拖动）
                snapJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    position.snapTo(pendingTarget)
                }
                scope.launch { press.animateTo(1f, spring(1f, Spring.StiffnessHigh)) }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                onDrag(change.position, dragAmount)
            },
            onDragEnd = {
                // press 的回落统一交给 animateToValue：
                // 这里若自己 launch animateTo(0f)，会和 animateToValue 开头的
                // press.animateTo(1f) 竞速，玻璃在飞行途中就被淡掉了。
                onDragStopped()
            },
            onDragCancel = {
                onDragStopped()
            },
        )
    }

    /** 拖动中直接跟手（不播动画）。 */
    fun updateValue(target: Float) {
        pendingTarget = target.coerceIn(valueRange.start, valueRange.endInclusive)
        dragStartSettled = false
        snapJob?.cancel()
        snapJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            position.snapTo(pendingTarget)
        }
    }

    /**
     * 按下（任意位置）立刻浮现玻璃：快速把 pressProgress 拉满。
     * 玻璃浮现在 position 的当前值处（= 当前选中的 tab），随后点击换页 /
     * 拖动都从这一点出发——「液滴从当前 tag 诞生」，不等抬起后的状态流。
     */
    fun pressDown() {
        scope.launch { press.animateTo(1f, spring(1f, Spring.StiffnessHigh)) }
    }

    /**
     * 抬手兜底回落：延迟 [delayMs] 再淡出。
     * 若这次抬手触发了换页，currentIndex 流的 animateToValue 会以
     * press.animateTo(1f) 抢占本回落（同一 Animatable 的并发 animateTo 自动互斥），
     * 玻璃保持到飞到位再淡出；点击当前 tab / 无后续时按延迟安静淡出。
     */
    fun pressUpDelayed(delayMs: Long = 280L) {
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            press.animateTo(0f, spring(1f, Spring.StiffnessMediumLow))
        }
    }

    /**
     * 程序化切换 / 松手吸附：玻璃浮现（press→1）→ 位置到位 → 玻璃淡出（press→0）。
     * visibilityThreshold 很小（demo 用 0.001f），跨一个 tab 就算"动了"。
     */
    suspend fun animateToValue(target: Float, holdPressUntilSettle: Boolean = false) {
        val coerced = target.coerceIn(valueRange.start, valueRange.endInclusive)
        // 先丢弃排队中的拖动 snapTo，避免它随后打断本动画
        snapJob?.cancel()
        snapJob = null
        // 拖动刚结束时 pendingTarget 仍是松手点，从它起动画才能把胶囊带离中间；
        // 其它路径一律用真实视觉位置，快速连点不会先回跳到上一个整数目标
        position.snapTo(if (dragStartSettled) position.value else pendingTarget)
        dragStartSettled = true
        // pendingTarget 与动画终点对齐：否则「按下其它 tab 触发的迁移」完成后
        // pendingTarget 仍是旧 tab，松手吸附会按过期值把胶囊拉回去（AVD 实证）
        pendingTarget = coerced
        if (abs(position.value - coerced) < visibilityThreshold) {
            press.animateTo(0f, spring(1f, Spring.StiffnessMediumLow))
            return
        }
        press.animateTo(1f, spring(1f, Spring.StiffnessHigh))
        position.animateTo(
            coerced,
            spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh),
        )
        // 按住模式（按下其它 tab 触发的迁移）：手指还没抬，玻璃保持；
        // 松手后状态流会以 holdPress=false 再触发一次（position 已到位，
        // 走 early-return），那时才淡出成正常选中态。
        if (!holdPressUntilSettle) {
            press.animateTo(0f, spring(1f, Spring.StiffnessMediumLow))
        }
    }
}
