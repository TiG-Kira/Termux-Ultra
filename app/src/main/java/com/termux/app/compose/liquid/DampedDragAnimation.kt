// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT
//
// 状态机移植自 HyperIsland（1812z/HyperIsland, compose/component/LiquidGlassNavigationBar.kt），
// 该文件原始实现源自 compose-miuix-ui 的 IosLiquidGlassNavigationBar / SukiSU-Ultra
// FloatingBottomBar（Apache-2.0）。Kiba 与 Termux Ultra 两侧都只做包名、
// 取值范围类型与回调签名的适配。

package com.termux.app.compose.liquid

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 液态底栏胶囊的完整动画状态机（HyperIsland 口径）。
 *
 * 五条独立的 [Animatable] 是"手感"的来源，彼此**不合并**：
 * - [value]：胶囊位置（tab 下标），`spring(1f, 1000f)` 紧致临界阻尼；
 * - [velocity]：由 [VelocityTracker] 从真实手指轨迹算出（归一化到 valueRange），
 *   再用 `spring(0.5f, 300f)` 平滑——拖动停止后仍有余韵，不是瞬间归零；
 * - [pressProgress]：玻璃浮现 / 凝回，位置飞行与它并行启动；
 * - [scaleX] / [scaleY]：**两条不同阻尼的弹簧**（0.6 / 0.7，stiffness 250f），
 *   横向先鼓、纵向稍慢，松手时各自带回弹——这是"果冻感"的关键，
 *   旧实现的 `lerp(1f, 1.39f, press)` 是等比线性跟随，完全没有弹性。
 *
 * [modifier] 用双 pass 的 [inspectDragGestures] 接管胶囊本体：
 * **按下瞬间（Initial pass）就 [press]**，因此按住当前选中的 tab（手指正好落在胶囊上）
 * 一样会浮现玻璃、鼓起并可拖动——旧实现把 pressDown 挂在底层 Row 上，
 * 会被 tab 的 clickable settle 抢掉，等于"按住当前页毫无反应"。
 *
 * [MutatorMutex] 串行化切换动画：[animateToValue] 内部的 press→飞行→release 是一个
 * 整体，新的切换不会把上一次的玻璃收尾拦腰截断。
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedFloatingPointRange<Float>,
    private val pressedScale: Float = 78f / 56f,
    private val canDrag: (Offset) -> Boolean = { true },
    private val onDragStopped: DampedDragAnimation.() -> Unit,
    private val onDrag: DampedDragAnimation.(IntSize, Offset) -> Unit,
    /** 每次 release 收敛完成后上报最终 tab，用于把页面索引与胶囊落点强制对齐（兜底）。 */
    private val onGestureSettled: (Int) -> Unit = {},
) {
    private val valueAnimation = Animatable(initialValue, 0.001f)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(1f, 0.001f)
    private val scaleYAnimation = Animatable(1f, 0.001f)
    private val mutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    /**
     * 位移写入的唯一所有者。
     *
     * 旧实现里 `updateValue` 每来一帧 move 就 `launch` 一个独立协程去 `animateTo`，
     * 轻扫时最后几帧可能排在手势收尾**之后**才被主线程调度，把
     * [valueAnimation] 的 `targetValue` 改回一个半程小数；此时
     * `onDragStopped` 已经用瞬时 [value] 判定过"已就位"并跳过收敛，
     * 胶囊就永久卡在两个 tab 之间，页面也不会切。
     * 所有写入统一从这里先取消旧 job、再当帧启动新动画，杜绝残留写入。
     */
    private var valueJob: Job? = null

    /** 胶囊当前位置（tab 下标，可为小数 = 正在两 tab 之间）。 */
    val value: Float get() = valueAnimation.value

    /** 当前位置动画的目标（松手吸附、release 判定都用它）。 */
    val targetValue: Float get() = valueAnimation.targetValue

    /** 位置动画是否仍在跑：外部回流去重时判断「飞行中」用，避免重复播一次起飞。 */
    val isAnimatingValue: Boolean get() = valueJob?.isActive == true

    /** 平滑后的拖动速度（tab/秒），胶囊拉伸公式直接读它。 */
    val velocity: Float get() = velocityAnimation.value

    /** 按压进度 0..1：折射、高光、阴影、Row B 图标缩放全部乘它。 */
    val pressProgress: Float get() = pressAnimation.value

    /** 横 / 纵独立弹性缩放。 */
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value

    /** 拖动手势：按压缩放 + 跟手 + 松手吸附，全部挂在胶囊上。 */
    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = {
                velocityTracker.resetTracking()
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { change, amount ->
            // 手指移出底栏范围后停止跟随，避免胶囊被拖到栏外
            if (canDrag(change.position) && canDrag(change.previousPosition)) {
                onDrag(size, amount)
            }
        }
    }

    /** 按下：玻璃浮现 + 横纵各自弹到鼓起状态（阻尼不同 → 有层次）。 */
    private fun press() {
        animationScope.launch {
            launch { pressAnimation.animateTo(1f, spring(1f, 1000f, 0.001f)) }
            launch { scaleXAnimation.animateTo(pressedScale, spring(0.6f, 250f, 0.001f)) }
            launch { scaleYAnimation.animateTo(pressedScale, spring(0.7f, 250f, 0.001f)) }
        }
    }

    /**
     * 松手：等胶囊位置真正接近目标后才凝回灰色提示（而不是抬手瞬间就把玻璃抹掉）。
     * `withFrameNanos` 让第一帧的玻璃稳定可见；`snapshotFlow.first` 等飞行收敛。
     *
     * [holdPress] 为 true 表示手指仍按在目标 tab 上（旧版「按住期间玻璃保持」）：
     * 此时继续维持玻璃直到抬手；60 帧兜底防止 up 事件意外丢失导致玻璃永不散。
     */
    private fun release(holdPress: () -> Boolean = { false }) {
        animationScope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .first { abs(it - valueAnimation.targetValue) < threshold }
            }
            var frames = 0
            while (holdPress() && frames < 60) {
                withFrameNanos { }
                frames++
            }
            launch { pressAnimation.animateTo(0f, spring(1f, 1000f, 0.001f)) }
            launch { scaleXAnimation.animateTo(1f, spring(0.6f, 250f, 0.001f)) }
            launch { scaleYAnimation.animateTo(1f, spring(0.7f, 250f, 0.001f)) }
            // 最后一道兜底：万一某条手势链完全没走到 onDragStopped，胶囊也必须
            // 停在整数 tab，且页面索引要与它一致（onSelect 幂等）。
            val settledIndex = valueAnimation.value.roundToInt()
                .coerceIn(valueRange.start.toInt(), valueRange.endInclusive.toInt())
            if (abs(valueAnimation.value - settledIndex.toFloat()) > 0.01f) {
                animateValue(settledIndex.toFloat(), trackVelocity = false)
            }
            onGestureSettled(settledIndex)
        }
    }

    /** 所有对 [valueAnimation] 的写入必须经过这里：取消旧 job + 当帧启动新动画。 */
    private fun animateValue(target: Float, trackVelocity: Boolean) {
        val coerced = target.coerceIn(valueRange.start, valueRange.endInclusive)
        valueJob?.cancel()
        valueJob = animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimation.animateTo(coerced, spring(1f, 1000f, 0.001f)) {
                if (trackVelocity) updateVelocity()
            }
        }
    }

    /** 拖动跟手（带一点弹簧，不是生硬 snapTo），并把真实手指速度喂给 [velocityAnimation]。 */
    fun updateValue(target: Float) {
        animateValue(target, trackVelocity = true)
    }

    /**
     * 松手吸附：无条件把位置收敛到整数 tab、速度平滑归零，**不重启 press / release**。
     * 玻璃收尾仍归按下链那次 [release]；这里若再播一次 press，会与它抢同一条
     * Animatable，松手瞬间玻璃明暗闪一下。
     */
    fun settleTo(target: Float) {
        animateValue(target, trackVelocity = false)
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            velocityAnimation.animateTo(0f, spring(0.5f, 300f, 0.01f))
        }
    }

    /**
     * 点按 / 程序化切换 / 松手吸附：整段动画由 [mutex] 串行，不被下一次切换打断收尾。
     *
     * [CoroutineStart.UNDISPATCHED]：调用点就在按下事件的 Initial pass，
     * 立即进入 mutate 并把 press / 飞行两条 Animatable 排进队列，省掉一次协程调度帧
     * （否则「按下→玻璃浮现→起飞」会整体慢一帧，真机上就是切入慢半拍）。
     */
    fun animateToValue(target: Float, holdPress: () -> Boolean = { false }) {
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.mutate {
                press()
                // 与 updateValue / settleTo 共用同一个 job：新切换会取消上一次飞行，
                // 也能取消仍在排队的「拖动末帧」，保证 targetValue 最终一定是整数。
                animateValue(target, trackVelocity = false)
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, spring(0.5f, 300f, 0.01f)) }
                }
                release(holdPress)
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(SystemClock.uptimeMillis(), Offset(value, 0f))
        val range = valueRange.endInclusive - valueRange.start
        val target = if (range == 0f) 0f else velocityTracker.calculateVelocity().x / range
        animationScope.launch { velocityAnimation.animateTo(target, spring(0.5f, 300f, 0.01f)) }
    }
}

/**
 * 双 pass 手势观察器（HyperIsland 原样）：先在 Initial pass 拿一次 down，
 * 再在 Main pass 拿一次，保证同一节点上多个 pointerInput（胶囊的拖拽 + 高光）
 * 和下层 tab 的 clickable 都能完整收到事件，且不会被彼此的 consume 挡掉。
 */
internal suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (PointerInputChange) -> Unit = {},
    onDragEnd: (PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (PointerInputChange, Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val down = awaitFirstDown(requireUnconsumed = false)
        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        val up = drag(initialDown.id) { change -> onDrag(change, change.positionChange()) }
        if (up == null) onDragCancel() else onDragEnd(up)
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    if (currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true) return null
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (change.changedToUpIgnoreConsumed()) {
            val other = event.changes.fastFirstOrNull { it.pressed }
            if (other == null) return change
            pointer = other.id
        } else if (change.previousPosition != change.position) {
            return change
        }
    }
}
