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
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.sign

/**
 * 液态玻璃底栏高度：与柔光玻璃+（miuix `GlassNavigationBar`，54dp）对齐，
 * 两套材质切换时底栏长宽、列表底部垫高完全一致，页面不会跳。
 */
val LiquidNavHeight: Dp = 54.dp

/**
 * 切换意图：`serial` 每次写都自增，保证「松手吸附回原 tab」这种 index 不变的情况
 * 也会触发一次状态流。单个状态对象 = 单次写 = 单次 emit，避免两次写让
 * `collectLatest` 取消掉正在播的吸附动画并吞掉那次的 `onSelect`。
 */
private data class NavSettle(val index: Int, val serial: Int)

/**
 * 液态玻璃版底部导航栏 —— 1:1 移植 AndroidLiquidGlass demo 的 `LiquidBottomTabs`
 * （Kyant0 官方实现；酷安的 `LiquidGlassTabPanel` 即基于同库同结构的工程化封装）。
 *
 * 绘制分三层（z 序）：
 * 1. **Row A 可见底栏**：`vibrancy + blur(8) + lens(24,24)`，表面 = 容器色；
 *    按压时整条底栏 `scale = 1 + 16dp/宽`（随 pressProgress，酷安按压时整条微鼓）；
 * 2. **Row B 隐形导出层**：`alpha(0)` 不可见，只把「底栏 + 图标」染成强调色录进
 *    `tabsBackdrop`，胶囊折射吸进来的图标是染过色的；
 * 3. **胶囊**：采样 `combined(page, tabsBackdrop)`，折射/高光/内阴影/表面强度全部乘
 *    pressProgress——**静止时全为 0，只剩 Black 10% 的灰色提示**（酷安同款）；
 *    速度驱动的拉伸是 demo 的除法对称式：`scaleX /= 1 - v*0.75`。
 *
 * 交互：点按 tab、横向拖动胶囊、外部切 Tab 三条路径统一汇入 [NavSettle] 状态流，
 * 动画与 `onSelect` 上报只发生在一处；拖动时 `updateValue` 跟手 + 整条底栏
 * EaseOut 微移（panelOffset，最多 4dp），松手吸附最近 tab 并回弹。
 * 图标高亮跟随胶囊的实际位置（`round(damped.value)`），不跟随目标 tab。
 *
 * 只服务于 Termux Ultra 的「玻璃」档（`navStyle == 2` / `"liquid_glass"`）；
 * 浮动玻璃（默认）与经典档分别由 `GlassNavigationBar`、miuix `NavigationBar` 承担。
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
    // demo 的强调色口径（选中/染色都用它）；与 Kiba 主色（蓝）同族
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f) else Color(0xFF121212).copy(0.4f)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val scope = rememberCoroutineScope()

    val tabsBackdrop = rememberLayerBackdrop()

    // 桥接：动画回调（在 BoxWithConstraints 外构造）需要拿内部测量的量。
    // 回调每次重组都会被重新赋值（捕获最新状态），所以字段可变即可。
    class DragBridge {
        var tabWidth: Float = 0f
        var isLtr: Boolean = true
        var offsetAnimation: Animatable<Float, *>? = null
        var offsetSnapJob: Job? = null
        var setCurrent: (Int) -> Unit = {}
    }
    val bridge = remember { DragBridge() }
    bridge.isLtr = isLtr
    var dampedRef by remember { mutableStateOf<DampedDragAnimation?>(null) }

    var navSettle by remember { mutableStateOf(NavSettle(selectedIndex, 0)) }
    // 按住其它 tab 触发的迁移：玻璃保持到松手（抬起后的同 index 再触发走 early-return 淡出）
    var holdPressUntilUp by remember { mutableStateOf(false) }
    // 只在上报目标真的变化时回调 onSelect，避免吸附回原位也上报一次
    var reportedIndex by remember { mutableIntStateOf(selectedIndex) }

    // 与 Kiba 上游一致用 remember(scope)：宿主的 availableTabs 由 remember 无 key
    // 计算，组合存活期间 tab 数恒定；开关页面会重启 Activity，整棵状态树重建，
    // 不需要给 items.size 加 key（加了反而会让 highlightIndex / interactiveHighlight
    // 等派生状态捕获旧 damped 实例）。
    val damped = remember(scope) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(items.size - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            onDragStopped = {
                dampedRef?.let { d ->
                    // targetValue 读同步字段，不受排队 snapTo 滞后影响；roundToInt
                    // 让停在半程的胶囊吸附到最近 tab，并经由状态流触发换页
                    val target = d.targetValue.fastRoundToInt().fastCoerceIn(0, items.size - 1)
                    bridge.setCurrent(target)
                    // demo 的松手回弹：先丢弃排队中的让位 snapTo，否则它会覆盖回弹动画
                    bridge.offsetSnapJob?.cancel()
                    bridge.offsetSnapJob = null
                    bridge.offsetAnimation?.let { a ->
                        scope.launch { a.animateTo(0f, spring(1f, 300f, 0.5f)) }
                    }
                }
            },
            onDrag = { _, dragAmount ->
                dampedRef?.let { d ->
                    val w = bridge.tabWidth
                    if (w > 0f) {
                        d.updateValue(
                            d.targetValue + dragAmount.x / w * if (bridge.isLtr) 1f else -1f,
                        )
                        bridge.offsetAnimation?.let { a ->
                            bridge.offsetSnapJob?.cancel()
                            bridge.offsetSnapJob = scope.launch { a.snapTo(a.value + dragAmount.x) }
                        }
                    }
                }
            },
        ).also { dampedRef = it }
    }

    // 外部 selectedIndex（其它入口切 Tab）→ 汇入同一条流。
    // 形参不是 State，靠 LaunchedEffect 的 key 变化驱动即可，无需再包一层 snapshotFlow
    LaunchedEffect(selectedIndex) {
        if (selectedIndex != navSettle.index) {
            navSettle = NavSettle(selectedIndex, navSettle.serial + 1)
        }
    }
    // 点按 / 拖动吸附 / 外部切换的唯一出口。
    // onSelect 必须在动画**之前**即时上报：collectLatest 的新值会取消上一协程，
    // 若上报排在动画之后，「动画完成 + 立刻松手」的窗口会让 onSelect 随协程
    // 一起被取消——页面不切但胶囊已飞到目标（恶性竞态，AVD 实证）。
    // 页面切换与液滴飞行就此解耦：点按即时换页，液滴随后跟上。
    LaunchedEffect(damped) {
        snapshotFlow { navSettle }
            .drop(1)
            .collectLatest { (index, _) ->
                if (index != reportedIndex) {
                    reportedIndex = index
                    onSelect(index)
                }
                damped.animateToValue(index.toFloat(), holdPressUntilUp)
            }
    }

    // 图标高亮跟随胶囊的实际位置，只在跨过 tab 边界的那一帧重组
    val highlightIndex by remember {
        derivedStateOf { damped.value.fastRoundToInt().fastCoerceIn(0, items.size - 1) }
    }

    // DrawBackdropElement.equals 按 lambda 引用判等，update() 无条件
    // invalidateDrawCache()。这些 lambda 一旦每次重组新建，整套取景链就会重录
    // （滚动时表现为底栏细抖），所以身份一律固定在这里。
    val capsuleShape: () -> Shape = remember { { Capsule() } }
    val barEffects: BackdropEffectScope.() -> Unit = remember {
        {
            vibrancy()
            blur(8f.dp.toPx())
            lens(24f.dp.toPx(), 24f.dp.toPx())
        }
    }
    val barLayerBlock: GraphicsLayerScope.() -> Unit = remember(damped) {
        {
            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, damped.pressProgress)
            scaleX = scale
            scaleY = scale
        }
    }
    val barSurface: DrawScope.() -> Unit = remember(containerColor) {
        { drawRect(containerColor) }
    }
    val exportEffects: BackdropEffectScope.() -> Unit = remember(damped) {
        {
            val progress = damped.pressProgress
            vibrancy()
            blur(8f.dp.toPx())
            lens(24f.dp.toPx() * progress, 24f.dp.toPx() * progress)
        }
    }
    val exportHighlight: () -> Highlight? = remember(damped) {
        { Highlight.Default.copy(alpha = damped.pressProgress) }
    }
    val pillLens: BackdropEffectScope.() -> Unit = remember(damped) {
        {
            val progress = damped.pressProgress
            lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress, chromaticAberration = true)
        }
    }
    val pillHighlight: () -> Highlight? = remember(damped) {
        { Highlight.Default.copy(alpha = damped.pressProgress) }
    }
    val pillShadow: () -> Shadow? = remember(damped) {
        { Shadow(alpha = damped.pressProgress) }
    }
    val pillInnerShadow: () -> InnerShadow? = remember(damped) {
        { InnerShadow(radius = 8f.dp * damped.pressProgress, alpha = damped.pressProgress) }
    }
    val pillLayerBlock: GraphicsLayerScope.() -> Unit = remember(damped) {
        {
            scaleX = damped.scaleX
            scaleY = damped.scaleY
            val velocity = damped.velocity / 10f
            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
        }
    }
    val pillSurface: DrawScope.() -> Unit = remember(isLightTheme, damped) {
        {
            val progress = damped.pressProgress
            drawRect(
                if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                alpha = 1f - progress,
            )
            drawRect(Color.Black.copy(alpha = 0.03f * progress))
        }
    }
    // Row B 录制层的图标随按压鼓起（demo 的 LocalLiquidBottomTabScale 口径）
    val pressScale: () -> Float = remember(damped) {
        { lerp(1f, 1.2f, damped.pressProgress) }
    }
    val combinedBackdrop = if (backdrop != null) {
        rememberCombinedBackdrop(backdrop, tabsBackdrop)
    } else {
        null
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / items.size
        }
        bridge.tabWidth = tabWidth

        // 整条底栏的"被拖动"偏移：拖动时底栏向拖动方向微微让位（最多 4dp，EaseOut 收敛）
        val offsetAnimation = remember { Animatable(0f) }
        bridge.offsetAnimation = offsetAnimation
        // 单次状态写：serial 自增保证「吸附回原 tab」也会 emit 一次
        bridge.setCurrent = { index ->
            navSettle = NavSettle(index, navSettle.serial + 1)
        }
        val panelOffsetState = remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
        val panelOffset by panelOffsetState

        val interactiveHighlight = remember(scope) {
            InteractiveHighlight(
                animationScope = scope,
                position = { size, _ ->
                    val w = bridge.tabWidth
                    Offset(
                        if (bridge.isLtr) {
                            (damped.value + 0.5f) * w + panelOffsetState.value
                        } else {
                            size.width - (damped.value + 0.5f) * w + panelOffsetState.value
                        },
                        size.height / 2f,
                    )
                },
            )
        }

        // Row A：可见底栏
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = capsuleShape,
                            effects = barEffects,
                            layerBlock = barLayerBlock,
                            onDrawSurface = barSurface,
                        )
                    } else {
                        Modifier.background(containerColor, Capsule())
                    },
                )
                .then(interactiveHighlight.modifier)
                .height(height)
                .fillMaxWidth()
                .padding(4f.dp)
                // 按下立刻浮现玻璃（在当前选中的 tag 上）：不等抬起后的状态流。
                // 抬手后延迟兜底回落——若触发了换页，animateToValue 的
                // press.animateTo(1f) 会抢占回落，玻璃保持到飞到位再淡出。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                    }
                }
                .pointerInput(damped, items.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val downX = down.position.x
                        val downY = down.position.y
                        damped.pressDown()
                        holdPressUntilUp = true
                        val hit = (downX / tabWidth).toInt().fastCoerceIn(0, items.size - 1)
                        // Termux Ultra 适配：命中迁移延后到确认手势方向后再提交。
                        // 从底栏起手的纵向滚动要放行给页面（PR #367 修过默认底栏的同款
                        // 误判）；Kiba 上游「按下即切页」会让纵滑被误判成选 tab。
                        var hitCommitted = false
                        // 按住期间左右拖动跟手：可继续拖过任意 tag，松手吸附最近者。
                        // 首帧从胶囊的**实际视觉位置**起跳（迁移动画可能已飞出半程，
                        // 若从 pendingTarget 起跳会回跳）。
                        var lastX = downX
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val totalDx = abs(change.position.x - downX)
                            val dy = abs(change.position.y - downY)
                            if (!dragging && !hitCommitted &&
                                dy > viewConfiguration.touchSlop && dy > totalDx
                            ) {
                                // 纵向为主：不提交命中、不驱动胶囊，事件留给页面滚动
                                holdPressUntilUp = false
                                damped.pressUpDelayed()
                                return@awaitEachGesture
                            }
                            val dx = change.position.x - lastX
                            lastX = change.position.x
                            if (dragging || totalDx > viewConfiguration.touchSlop) {
                                dragging = true
                                change.consume()
                                if (!hitCommitted) {
                                    hitCommitted = true
                                    if (hit != navSettle.index) {
                                        navSettle = NavSettle(hit, navSettle.serial + 1)
                                    }
                                }
                                damped.updateValue(
                                    (if (damped.targetValue != damped.value) damped.value
                                     else damped.targetValue) + dx / tabWidth * if (isLtr) 1f else -1f,
                                )
                                // demo 的让位：整条底栏向拖动方向微移
                                bridge.offsetSnapJob?.cancel()
                                bridge.offsetSnapJob = scope.launch {
                                    bridge.offsetAnimation?.let { a ->
                                        a.snapTo(a.value + dx)
                                    }
                                }
                            }
                            if (!change.pressed) {
                                // 轻点：抬起时提交命中
                                if (!hitCommitted) {
                                    hitCommitted = true
                                    if (hit != navSettle.index) {
                                        navSettle = NavSettle(hit, navSettle.serial + 1)
                                    }
                                }
                                // 消费 up：下层 TabContent 还挂着 clickable，不消费会再写一次
                                // navSettle（同一帧三次写会让 collectLatest 吞掉本次切换）
                                change.consume()
                                break
                            }
                        }
                        // 松手：吸附最近 tab（含 down 迁移未完成的场景）；
                        // holdPress 复位后流会以 holdPress=false 再触发，玻璃淡成选中态
                        holdPressUntilUp = false
                        // 拖动用**当前视觉位置**吸附（value 是唯一事实，迁移动画可能未完）；
                        // 轻点没有拖动过，value 仍停在原 tab，必须沿用 up 时提交的 hit，
                        // 否则这里会把刚提交的切换覆盖回当前 tab（点击不切页）。
                        val target = if (dragging) {
                            damped.value.fastRoundToInt().fastCoerceIn(0, items.size - 1)
                        } else {
                            hit
                        }
                        bridge.offsetSnapJob?.cancel()
                        bridge.offsetSnapJob = null
                        bridge.offsetAnimation?.let { a ->
                            scope.launch { a.animateTo(0f, spring(1f, 300f, 0.5f)) }
                        }
                        navSettle = NavSettle(target, navSettle.serial + 1)
                        damped.pressUpDelayed()
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                TabContent(
                    item = item,
                    tint = if (i == highlightIndex) accentColor else unselectedTint(),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    // 不直接 onSelect：交给状态流在液滴到位后统一上报一次
                    onClick = {
                        navSettle = NavSettle(i, navSettle.serial + 1)
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
                .graphicsLayer { translationX = panelOffset }
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
            // 前两个会把 tabsBackdrop 染成实心蓝（胶囊折射出一整块色块），
            // 第三个会拦掉 Row A 的真实点按（均为 AVD 实证）。
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

        // 胶囊：一切玻璃效果 × pressProgress；静止 = Black 10% 灰色提示
        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX = if (bridge.isLtr) {
                        damped.value * tabWidth + panelOffset
                    } else {
                        size.width - (damped.value + 1f) * tabWidth + panelOffset
                    }
                }
                .then(interactiveHighlight.gestureModifier)
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
