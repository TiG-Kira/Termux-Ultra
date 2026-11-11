// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.termux.app.compose.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 液态玻璃底栏高度：与柔光玻璃+（miuix `GlassNavigationBar`，54dp）对齐，
 * 两套材质切换时底栏长宽、列表底部垫高完全一致，页面不会跳。
 */
val LiquidNavHeight: Dp = 54.dp

/**
 * 液态玻璃版底部导航栏。
 *
 * **交互状态机与手感整体移植自 HyperIsland**（1812z/HyperIsland 的
 * `LiquidGlassNavigationBar.kt` / `FloatingLiquidBar`）：
 * - 胶囊本体独占双 pass 手势，按下瞬间就浮现玻璃 + 横纵不同阻尼地鼓起；
 *   **按住当前选中的 tab 也有完整液态反馈**（旧实现把 press 挂在底层 Row 上，
 *   被 tab 的 clickable settle 抢掉，等于没有任何动作）；
 * - 五条独立弹簧（位置 / 速度 / press / scaleX / scaleY）+ `MutatorMutex` +
 *   真实 `VelocityTracker`，松手等飞行收敛才凝回提示；
 * - 镜面高光用 AGSL `smoothstep` + `BlendMode.Plus` 加色叠加，并按设备重力方向偏移。
 *
 * 渲染层仍走 Kiba 的 Kyant0 backdrop 2.0.1（与 miuix blur 是两套不兼容的取景 API），
 * 因此只做**等效适配**：透明度口径按对方（blur 4dp / 容器 30% / 常驻镜面高光与投影 /
 * 胶囊 `depthEffect`），三层取景结构（Row A / 隐形 Row B / combined 胶囊）保持不变。
 *
 * 只服务于 Termux Ultra 的「玻璃」档（`navStyle == 2` / `"liquid_glass"`）；
 * 默认浮动玻璃(0) 与经典(1) 两档底栏由 `GlassNavigationBar` / miuix `NavigationBar`
 * 承担，与本文件无关。镜面主光改为固定重力方向（见 `DeviceTilt.kt`）。
 */
@Composable
fun LiquidNavigationBar(
    items: List<GlassNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    height: Dp = LiquidNavHeight,
) {
    if (items.isEmpty()) return
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    // HyperIsland 口径：表面尽量淡，让折射/高光成为主角（旧值 40% 偏实、发闷）
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.3f) else Color(0xFF121212).copy(0.3f)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val tabsBackdrop = rememberLayerBackdrop()
    // 保留 State 对象本身：回调 lambda 在 remember 里构造，捕获解包值会读到旧 index / 旧回调
    val latestSelectedIndexState = rememberUpdatedState(selectedIndex)
    val latestOnSelectState = rememberUpdatedState(onSelect)
    // 镜面主光跟随设备倾斜（对方 rememberGravityHighlight 的等效实现：
    // Kyant0 Highlight 没有可配置光源，改为偏移 InteractiveHighlight 的光斑中心）
    val tiltState = rememberDeviceTilt()

    // 动画回调在组件最外层构造，但需要 BoxWithConstraints 里的测量值：
    // 用普通可变对象桥接（BoxWithConstraints 是 SubcomposeLayout，内部 remember 会重建）
    class Metrics {
        var tabWidth: Float = 0f
        var totalWidth: Float = 0f
        var isLtr: Boolean = true
        // 拖动时每帧都会 cancel/重建，绝不能用 State（会把含 glass 的子树拖进逐帧重组）
        var offsetSnapJob: Job? = null
        /** 手指仍按在「其它 tab」上：玻璃保持到抬手（对齐 2.3.18 的按住手感）。 */
        var holdingOtherTab: Boolean = false
        /** 起飞预览尚未提交切页：此时胶囊与页面刻意不一致，禁止兜底回调提前切页。 */
        var previewPending: Boolean = false
    }
    val metrics = remember { Metrics() }
    metrics.isLtr = isLtr

    val totalWidthState = remember { mutableFloatStateOf(0f) }
    val offsetAnimation = remember { Animatable(0f) }
    // 记录最后一次驱动过动画的 tab，避免「拖动吸附 + selectedIndex 回流」播两次玻璃。
    // 保留 State 对象本身：回调 lambda 在 remember 里构造，捕获解包后的 Int 会读到旧值。
    val lastAnimatedState = remember { mutableIntStateOf(selectedIndex) }

    val panelOffsetState = remember(density) {
        derivedStateOf {
            val total = totalWidthState.floatValue
            if (total == 0f) 0f else {
                val fraction = (offsetAnimation.value / total).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
    }

    // 自引用桥：canDrag / onDragStopped 需要访问尚未构造完的动画对象
    class Holder { var animation: DampedDragAnimation? = null }
    val holder = remember { Holder() }

    val dragAnimation = remember(scope, items.size, density) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(items.size - 1).toFloat(),
            canDrag = { offset ->
                val animation = holder.animation ?: return@DampedDragAnimation false
                val w = metrics.tabWidth
                if (w <= 0f || metrics.totalWidth <= 0f) return@DampedDragAnimation false
                val padding = with(density) { 4f.dp.toPx() }
                val indicatorX = animation.value * w
                val touchX = if (metrics.isLtr) {
                    padding + indicatorX + offset.x
                } else {
                    metrics.totalWidth - padding - w - indicatorX + offset.x
                }
                touchX in 0f..metrics.totalWidth
            },
            onDragStopped = {
                // 用「动画目标」而不是瞬时视觉值算落点：轻扫末帧 value 还来不及
                // 跟上 targetValue，拿 value 判断会误以为胶囊已就位。
                val target = dragTarget.fastRoundToInt().fastCoerceIn(0, items.size - 1)
                // 无条件上报（onSelect 幂等）：轻扫时 target 可能恰好等于当前页，
                // 但不能因此整段跳过，否则胶囊位置与 selectedIndex 会永久不一致。
                latestOnSelectState.value(target)
                lastAnimatedState.intValue = target
                // 无条件收敛位置：value 接近整数不代表 targetValue 不是半程残留值。
                // settleTo 只收位置、不重启 press/release，玻璃收尾仍归 onDragEnd 的 release
                // （它会等位置收敛后才凝回，飞行余韵不受影响）。
                settleTo(target.toFloat())
                metrics.offsetSnapJob?.cancel()
                metrics.offsetSnapJob = null
                scope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDrag = { _, amount ->
                val w = metrics.tabWidth
                if (w > 0f) {
                    updateValue(
                        (targetValue + amount.x / w * if (metrics.isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (items.size - 1).toFloat()),
                    )
                    metrics.offsetSnapJob?.cancel()
                    metrics.offsetSnapJob = scope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + amount.x)
                    }
                }
            },
            // release 收敛完成后统一对齐：万一某条手势链完全漏掉 onDragStopped，
            // 这里也能把页面索引拉回胶囊最终落点（极低概率竞态的最后一道兜底）。
            onGestureSettled = { index ->
                val target = index.fastCoerceIn(0, items.size - 1)
                lastAnimatedState.intValue = target
                // 按下预览期间 release 的 holdPress 最多等 60 帧就会凝回，若此时
                // 也上报切页，长按非当前 tab 会在没抬手时提前翻页。预览未提交时
                // 只记账，页面统一由 commit() 在确认手势方向后切换。
                if (!metrics.previewPending && target != latestSelectedIndexState.value) {
                    latestOnSelectState.value(target)
                }
            },
        ).also { holder.animation = it }
    }

    // 外部切 Tab（其它入口改 selectedIndex）→ 驱动液滴飞行。
    // 底栏自身的点按已在按下瞬间驱动并记账，拖动吸附也已自己播过动画，
    // 这里靠 lastAnimated 去重，避免玻璃起落两次。
    LaunchedEffect(dragAnimation) {
        snapshotFlow { latestSelectedIndexState.value }.collectLatest { index ->
            val target = index.fastCoerceIn(0, items.size - 1)
            // 除了 index 变化，静止状态下胶囊位置偏离整数 tab 时也强制对齐，
            // 让任何一次外部回流都能自愈「卡在两 tab 之间」的残留状态。
            // 飞行中必须跳过，否则每次点按的 selectedIndex 回流都会重播一次起飞。
            val drifted = !dragAnimation.isAnimatingValue &&
                abs(dragAnimation.value - target.toFloat()) > 0.02f
            if (target != lastAnimatedState.intValue || drifted) {
                lastAnimatedState.intValue = target
                dragAnimation.animateToValue(target.toFloat())
            }
        }
    }

    // 图标高亮跟随胶囊实际位置，只在跨过 tab 边界的那一帧重组
    // key 必须带 dragAnimation：它在 items.size / density 变化时会重建，
    // 这两处若无 key 会继续捕获旧实例（图标高亮冻住、光斑钉在旧位置）。
    val highlightIndex by remember(dragAnimation) {
        derivedStateOf { dragAnimation.value.fastRoundToInt().fastCoerceIn(0, items.size - 1) }
    }

    val interactiveHighlight = remember(scope, dragAnimation) {
        InteractiveHighlight(
            animationScope = scope,
            position = { size, gestureOffset ->
                val w = metrics.tabWidth
                val center = if (metrics.isLtr) {
                    (dragAnimation.value + 0.5f) * w + panelOffsetState.value
                } else {
                    size.width - (dragAnimation.value + 0.5f) * w + panelOffsetState.value
                }
                val gravity = tiltState.value
                val magnitudeSquared =
                    gravity.gravityX * gravity.gravityX + gravity.gravityY * gravity.gravityY
                val gx = if (magnitudeSquared > 0.01f) {
                    gravity.gravityX / sqrt(magnitudeSquared)
                } else {
                    0f
                }
                // 手指在胶囊内的偏移轻微牵引 + 重力决定镜面主光方向
                val pull = (gestureOffset.x - w / 2f) * 0.2f
                Offset(
                    (center + pull + gx * size.minDimension * 0.12f)
                        .fastCoerceIn(0f, size.width),
                    size.height / 2f,
                )
            },
        )
    }

    // DrawBackdropElement.equals 按 lambda 引用判等，update() 无条件
    // invalidateDrawCache()。lambda 换身份会重录整套取景链（滚动时底栏细抖），
    // 所以下面一律 remember 固定身份，动画值只在 draw / graphicsLayer 阶段读。
    val capsuleShape: () -> Shape = remember { { Capsule() } }
    val barEffects: BackdropEffectScope.() -> Unit = remember {
        {
            vibrancy()
            blur(4f.dp.toPx())
            lens(24f.dp.toPx(), 24f.dp.toPx())
        }
    }
    val barLayerBlock: GraphicsLayerScope.() -> Unit = remember(dragAnimation) {
        {
            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, dragAnimation.pressProgress)
            scaleX = scale
            scaleY = scale
        }
    }
    val barSurface: DrawScope.() -> Unit = remember(containerColor) {
        { drawRect(containerColor) }
    }
    // 常驻镜面高光 + 淡投影：HyperIsland 的 baseHighlight(0.75) / dropShadow(10dp)
    val barHighlight: () -> Highlight? = remember {
        { Highlight.Default.copy(alpha = 0.75f) }
    }
    val barShadow: () -> Shadow? = remember(isLightTheme) {
        {
            Shadow(
                radius = 10f.dp,
                color = Color.Black,
                alpha = if (isLightTheme) 0.1f else 0.2f,
            )
        }
    }
    val exportEffects: BackdropEffectScope.() -> Unit = remember(dragAnimation) {
        {
            val progress = dragAnimation.pressProgress
            vibrancy()
            blur(4f.dp.toPx())
            lens(24f.dp.toPx() * progress, 24f.dp.toPx() * progress)
        }
    }
    val exportHighlight: () -> Highlight? = remember(dragAnimation) {
        { Highlight.Default.copy(alpha = dragAnimation.pressProgress) }
    }
    val pillLens: BackdropEffectScope.() -> Unit = remember(dragAnimation) {
        {
            val progress = dragAnimation.pressProgress
            // depthEffect 对齐 HyperIsland：折射带纵深，不再是一层平面色散
            lens(
                10f.dp.toPx() * progress,
                14f.dp.toPx() * progress,
                depthEffect = true,
                chromaticAberration = true,
            )
        }
    }
    val pillHighlight: () -> Highlight? = remember(dragAnimation) {
        { Highlight.Default.copy(alpha = dragAnimation.pressProgress) }
    }
    val pillShadow: () -> Shadow? = remember(dragAnimation) {
        { Shadow(alpha = dragAnimation.pressProgress) }
    }
    val pillInnerShadow: () -> InnerShadow? = remember(dragAnimation) {
        {
            InnerShadow(
                radius = 8f.dp * dragAnimation.pressProgress,
                alpha = dragAnimation.pressProgress,
            )
        }
    }
    val pillLayerBlock: GraphicsLayerScope.() -> Unit = remember(dragAnimation) {
        {
            val press = dragAnimation.pressProgress
            // 按压鼓起 + 速度拉伸都只属于「浮动玻璃」阶段。松手后玻璃凝回灰色选中提示，
            // 形状必须严格回到 1:1 的标准胶囊：否则残余速度（spring 0.5/300 还在衰减）
            // 会在玻璃消失后继续横向拉伸静止形状，最右 tab 会顶出底栏圆角。
            val velocity = dragAnimation.velocity / 10f
            val stretchX = (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
            val stretchY = (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
            scaleX = lerp(1f, dragAnimation.scaleX / (1f - stretchX), press)
            scaleY = lerp(1f, dragAnimation.scaleY * (1f - stretchY), press)
        }
    }
    val pillSurface: DrawScope.() -> Unit = remember(isLightTheme, dragAnimation) {
        {
            val progress = dragAnimation.pressProgress
            // 静止 = 灰色提示；按住/飞行 = 白玻璃气泡（透镜正对图标时也有清晰液滴）
            drawRect(
                if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                alpha = 1f - progress,
            )
            drawRect(
                if (isLightTheme) Color.White.copy(0.38f) else Color.White.copy(0.14f),
                alpha = progress,
            )
        }
    }
    val pressScale: () -> Float = remember(dragAnimation) {
        { lerp(1f, 1.2f, dragAnimation.pressProgress) }
    }
    val combinedBackdrop = if (backdrop != null) {
        rememberCombinedBackdrop(backdrop, tabsBackdrop)
    } else {
        null
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / items.size
        }

        // Row A：可见底栏。胶囊手势只覆盖当前 tab，点其它 tab 的即时入口在链尾的 pointerInput。
        Row(
            Modifier
                .onGloballyPositioned { coordinates ->
                    val w = coordinates.size.width.toFloat()
                    totalWidthState.floatValue = w
                    val tabWidthMeasured = ((w - with(density) { 8f.dp.toPx() }) / items.size)
                        .coerceAtLeast(0f)
                    metrics.totalWidth = w
                    metrics.tabWidth = tabWidthMeasured
                }
                .graphicsLayer { translationX = panelOffsetState.value }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = capsuleShape,
                            effects = barEffects,
                            highlight = barHighlight,
                            shadow = barShadow,
                            layerBlock = barLayerBlock,
                            onDrawSurface = barSurface,
                        )
                    } else {
                        Modifier.background(containerColor, Capsule())
                    },
                )
                .then(interactiveHighlight.modifier)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = {},
                )
                .height(height)
                .fillMaxWidth()
                .padding(4f.dp)
                // 宿主适配（#372 口径）：Kiba 上游在 down 立即「起飞 + onSelect」，
                // 但从底栏起手的纵向滚动会被误判成选 tab（#367 修过默认底栏同款问题）。
                // 因此把「切页提交」延后到确认手势方向；**起飞则在 down 当帧就启动**，
                // 不再等抬手，修掉「按住没反应、松手才飞」的迟滞，同时保留滚动保护。
                // 提交时不等父级 selectedIndex 回流，所以不会退化成 Kiba v2 的「抬手慢半拍」。
                .pointerInput(dragAnimation, items.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val w = metrics.tabWidth
                        if (w <= 0f) return@awaitEachGesture
                        val startIndex = latestSelectedIndexState.value
                        // 内容区宽 = tabWidth * n（左右各 4dp padding 已排除）
                        val x = if (metrics.isLtr) {
                            down.position.x
                        } else {
                            w * items.size - down.position.x
                        }
                        val hit = (x / w).toInt().fastCoerceIn(0, items.size - 1)
                        // 按在当前 tab 上：press / 拖动 / 点按全归胶囊自身的手势链
                        // （对齐 Kiba 的 `hit != selectedIndex` 门控）。这里若也跟手，
                        // 胶囊会在没有 press() 的情况下滑动——灰色胶囊跟手、玻璃不浮现。
                        // 只有胶囊没覆盖到的上下 4dp 边缘条会走到这里，忽略即可。
                        if (hit == startIndex) return@awaitEachGesture

                        // 按下瞬间起飞：记账 + 玻璃浮现 + 胶囊飞向手指下的 tab 一次完成，
                        // 但此刻**不切页**。selectedIndex 回流时 snapshotFlow 靠
                        // lastAnimatedState 去重，不会播第二次玻璃。
                        lastAnimatedState.intValue = hit
                        metrics.holdingOtherTab = true
                        metrics.previewPending = true
                        dragAnimation.animateToValue(hit.toFloat()) {
                            metrics.holdingOtherTab
                        }

                        var committed = false
                        var previewCancelled = false
                        // 撤销预览：未提交（纵向滚动 / 手势中断）时把胶囊送回原 tab。
                        // 只收敛位置、不重启 press/release；down 那次 release 会等它
                        // 收敛后再凝回玻璃，不会出现二次起落。
                        fun cancelPreview() {
                            if (previewCancelled) return
                            previewCancelled = true
                            metrics.previewPending = false
                            lastAnimatedState.intValue = startIndex
                            metrics.holdingOtherTab = false
                            dragAnimation.settleTo(startIndex.toFloat())
                        }
                        // 提交切页：轻点抬手或确认横向拖动时才上报，纵向滚动不受影响。
                        fun commit() {
                            if (committed) return
                            committed = true
                            metrics.previewPending = false
                            latestOnSelectState.value(hit)
                        }
                        val dir = if (metrics.isLtr) 1f else -1f
                        var lastX = down.position.x
                        var dragging = false
                        var dragBase = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change =
                                event.changes.firstOrNull { it.id == down.id } ?: break
                            val totalDx = abs(change.position.x - down.position.x)
                            val totalDy = abs(change.position.y - down.position.y)
                            if (!committed && !dragging &&
                                totalDy > viewConfiguration.touchSlop && totalDy > totalDx
                            ) {
                                // 纵向为主：撤销按下时的起飞预览、不提交切页，
                                // 事件留给页面滚动
                                cancelPreview()
                                return@awaitEachGesture
                            }
                            val dx = change.position.x - lastX
                            lastX = change.position.x
                            if (!change.pressed) {
                                // 轻点抬手：只提交切页，飞行动画已在 down 启动。
                                // 不 consume up，胶囊上的 inspectDragGestures 与
                                // TabContent.clickable 都要收到事件。
                                commit()
                                break
                            }
                            if (!dragging) {
                                if (totalDx <= viewConfiguration.touchSlop) continue
                                dragging = true
                                commit()
                                // 首帧从胶囊真实视觉位置起跳：起飞动画可能已飞出半程，
                                // 用按下点或 targetValue 起跳都会让液滴当场回跳
                                dragBase = dragAnimation.value
                                continue
                            }
                            dragBase = (dragBase + dx / w * dir)
                                .fastCoerceIn(0f, (items.size - 1).toFloat())
                            dragAnimation.updateValue(dragBase)
                            // 整条底栏让位（与胶囊拖动手势同口径）
                            metrics.offsetSnapJob?.cancel()
                            metrics.offsetSnapJob = scope.launch {
                                offsetAnimation.snapTo(offsetAnimation.value + dx)
                            }
                        }
                        // 手势异常中断（没收到 up、也没确认拖动）时撤销起飞预览，
                        // 避免胶囊停在别的 tab 上而页面没切。
                        if (!committed) cancelPreview()
                        metrics.holdingOtherTab = false
                        metrics.offsetSnapJob?.cancel()
                        metrics.offsetSnapJob = null
                        scope.launch {
                            offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                        }
                        if (dragging) {
                            // 纯点按绝不重发吸附：此刻起飞动画可能还在飞，
                            // round 半程点会把目标改成中途 tab 并掐断在飞的动画。
                            // 拖动过则用 dragTarget 而非瞬时 value 算落点：
                            // 轻扫末帧的写入可能还没落到 value 上。
                            val target = dragAnimation.dragTarget.fastRoundToInt()
                                .fastCoerceIn(0, items.size - 1)
                            lastAnimatedState.intValue = target
                            // 只收敛位置，玻璃收尾仍交给按住链那次 release
                            dragAnimation.settleTo(target.toFloat())
                            // 无条件上报，保证页面与胶囊落点一致
                            latestOnSelectState.value(target)
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                TabContent(
                    item = item,
                    tint = if (i == highlightIndex) accentColor else unselectedTint(),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    // 键盘 / 无障碍点击没有 down 事件，这里兜底；普通点按已由上面的
                    // Initial pass 观察器驱动，lastAnimatedState 去重避免玻璃起落两次。
                    onClick = {
                        if (i != lastAnimatedState.intValue) {
                            lastAnimatedState.intValue = i
                            dragAnimation.animateToValue(i.toFloat())
                        }
                        latestOnSelectState.value(i)
                    },
                )
            }
        }

        // Row B：隐形导出层（图标染成强调色录进 tabsBackdrop）
        Row(
            Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .graphicsLayer { translationX = panelOffsetState.value }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = capsuleShape,
                            effects = exportEffects,
                            highlight = exportHighlight,
                        )
                    } else {
                        Modifier
                    },
                )
                .then(interactiveHighlight.modifier)
                .height(height - 8f.dp)
                .fillMaxWidth()
                .padding(horizontal = 4f.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 不画表面色、不整层 ColorFilter.tint、不挂 clickable：
            // 前两个会把 tabsBackdrop 染成实心蓝，第三个会拦掉 Row A 的真实点按。
            items.forEach { item ->
                TabContent(
                    item = item,
                    tint = accentColor,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = null,
                    scale = pressScale,
                )
            }
        }

        // 胶囊：手势 + 高光 + 一切玻璃效果。按住当前 tab 时手指正落在这块上，
        // inspectDragGestures 的 Initial pass 立刻 press() —— 这是本次修复的关键。
        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX = if (metrics.isLtr) {
                        dragAnimation.value * tabWidth + panelOffsetState.value
                    } else {
                        size.width - (dragAnimation.value + 1f) * tabWidth +
                            panelOffsetState.value
                    }
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dragAnimation.modifier)
                .then(
                    if (combinedBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = capsuleShape,
                            effects = pillLens,
                            highlight = pillHighlight,
                            shadow = pillShadow,
                            innerShadow = pillInnerShadow,
                            layerBlock = pillLayerBlock,
                            onDrawSurface = pillSurface,
                        )
                    } else {
                        Modifier.background(Color.Black.copy(alpha = 0.1f), Capsule())
                    },
                )
                .height(height - 8f.dp)
                .fillMaxWidth(1f / items.size),
        )
    }
}

@Composable
private fun unselectedTint(): Color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.62f)

@Composable
private fun TabContent(
    item: GlassNavigationItem,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    scale: (() -> Float)? = null,
) {
    val iconScale = scale
    Column(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (iconScale != null) {
                    Modifier.graphicsLayer {
                        val s = iconScale()
                        scaleX = s
                        scaleY = s
                    }
                } else {
                    Modifier
                },
            ),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 宿主适配：TU 的 GlassNavigationItem 只有 ImageVector，没有 iconPainter。
        Icon(
            imageVector = item.icon,
            contentDescription = item.contentDescription ?: item.label,
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
        if (item.label != null) {
            Text(
                text = item.label,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
