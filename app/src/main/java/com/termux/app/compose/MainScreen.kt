package com.termux.app.compose

import android.content.Context

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop as miuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop as rememberMiuixLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import com.termux.R
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession

private const val SWIPE_THRESHOLD = 100f

@Composable
fun MainScreen(
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    sessions: List<TermuxSession>,
    onSessionClick: (TermuxSession) -> Unit,
    onNewTerminal: () -> Unit,
    onNewTerminalAndOpenConsole: () -> Unit,
    onStopTerminal: (TermuxSession) -> Unit,
    onRenameTerminal: (TermuxSession, String) -> Unit,
    onExecuteScript: (String, String) -> Unit,
    onAboutClick: () -> Unit,
    showVnc: Boolean,
    isWakeLockEnabled: Boolean,
    onToggleWakeLock: () -> Unit,
    onRefreshSessions: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    // 单一、全局的 TopAppBar：TopAppBarState 按选中页重建（吸顶状态存在 state 里），保证切页后吸顶状态复位。
    // 注意：MiuixScrollBehavior 内部按 (state, canScroll, snapSpec, flingSpec) remember，
    // 默认参数 canScroll={true} 和 snapAnimationSpec=spring(...) 每次重组都会新建实例，
    // 导致 ScrollBehavior 每次重组都被重建 → nestedScroll 连接与吸顶 snap 动画被反复打断，
    // 表现为"吸顶后小标题间歇性消失"。这里显式 remember 稳定这三个参数。
    val topAppBarState = remember(selectedTab) { TopAppBarState(0f, 0f, 0f) }
    val canScrollAlways: () -> Boolean = remember { val f: () -> Boolean = { true }; f }
    val snapSpec = remember { spring<Float>(stiffness = 2500f) }
    val flingSpec: DecayAnimationSpec<Float> = rememberSplineBasedDecay()
    val scrollBehavior = MiuixScrollBehavior(
        state = topAppBarState,
        canScroll = canScrollAlways,
        snapAnimationSpec = snapSpec,
        flingAnimationSpec = flingSpec,
    )
    // 各页面把各自的 TopAppBar 内容写入此槽，由 MainScreen 的 Scaffold.topBar 统一渲染
    val topBarContent = remember {
        mutableStateOf<@Composable () -> Unit>({ MainTopBar(selectedTab, showVnc, scrollBehavior) })
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var remoteSubTab by remember { mutableStateOf(0) }
    var previousTab by remember { mutableStateOf(selectedTab) }
    var rawDragOffset by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val systemNavBarsHeight = with(density) {
        WindowInsets.navigationBars.getBottom(density).toDp()
    }
    val dragOffsetAnimatable = remember { Animatable(0f) }
    var isSwipingInProgress by remember { mutableStateOf(false) }
    var isOverviewEditMode by remember { mutableStateOf(false) }
    var swipeTargetTab by remember { mutableStateOf<Int?>(null) }
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    var currentPageAlphaState by remember { mutableFloatStateOf(1f) }
    var skipNextTransition by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val navPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    // 导航栏样式：glass=浮动玻璃（新版默认）、classic=经典（旧默认）、liquid_glass=玻璃、soft_light=柔光。
    // 历史值迁移：旧 "default"（原默认）与旧 "floating"（已删除）统一迁到新的浮动玻璃底栏。
    val navBarStyle = remember {
        when (val stored = navPrefs.getString("navigation_bar_style", null)) {
            "classic", "liquid_glass", "soft_light" -> stored
            else -> "glass"
        }
    }
    val useGlassNav = navBarStyle == "glass" && android.os.Build.VERSION.SDK_INT >= 33
    val useLiquidGlassNav = navBarStyle == "liquid_glass"
    val useSoftLightNav = navBarStyle == "soft_light"
    var glassNavFailed by remember { mutableStateOf(false) }

    // Crash recovery: if the previous glass nav rendering attempt crashed (SIGSEGV etc.),
    // the "crash pending" flag will still be set. Detect this and auto-fallback.
    LaunchedEffect(useLiquidGlassNav) {
        if (useLiquidGlassNav && navPrefs.getBoolean("glass_nav_crash_pending", false)) {
            glassNavFailed = true
            navPrefs.edit().remove("glass_nav_crash_pending").apply()
        }
    }

    val navStyle = when {
        useLiquidGlassNav && !glassNavFailed -> 2
        useSoftLightNav -> 3
        useGlassNav -> 0
        else -> 1
    }

    // 页面可用性过滤：根据设备 API 支持程度隐藏无可用功能的页面入口。
    // tab 索引 0-4 分别对应 总览/终端/文件/远程/设置 页面。
    fun pageForTab(tab: Int): ApiCompat.Page = when (tab) {
        0 -> ApiCompat.Page.OVERVIEW
        1 -> ApiCompat.Page.TERMINAL
        2 -> ApiCompat.Page.FILES
        3 -> ApiCompat.Page.REMOTE
        else -> ApiCompat.Page.SETTINGS
    }
    val availableTabs = remember {
        listOf(0, 1, 2, 3, 4).filter { ApiCompat.isPageAvailable(pageForTab(it)) }
    }

    // 经典底栏（miuix NavigationBar）自身高度
    val classicNavHeight = 56.dp
    // 浮动玻璃底栏：GlassNavigationBarDefaults.Height(54dp) + 底部留白(12dp)
    val glassNavTotalHeight = 54.dp + 12.dp
    val navContainerHeight = getNavContainerHeight(availableTabs.size, NavStyle.GLASS)
    val totalNavHeight = when (navStyle) {
        // 浮动玻璃：悬浮于系统导航栏之上
        0 -> glassNavTotalHeight + systemNavBarsHeight
        // 经典：贴合系统导航栏
        1 -> classicNavHeight + systemNavBarsHeight
        // 玻璃/柔光：底部留白已含在 navContainerHeight 内
        else -> navContainerHeight
    }
    val snackbarBottomPadding = when (navStyle) {
        0 -> glassNavTotalHeight + systemNavBarsHeight + 8.dp
        1 -> classicNavHeight + systemNavBarsHeight + 12.dp
        else -> systemNavBarsHeight + navContainerHeight + 12.dp
    }

    LaunchedEffect(navStyle) {
        if (navStyle == 2) {
            // Mark that glass nav rendering is in progress
            navPrefs.edit().putBoolean("glass_nav_crash_pending", true).apply()
        } else {
            navPrefs.edit().remove("glass_nav_crash_pending").apply()
        }
    }

    // After first successful glass nav frame, clear the crash-pending flag.
    // Delay 2s to ensure the first frame is fully rendered — if a native crash
    // (SIGSEGV etc.) occurs during rendering, this block won't execute and the
    // flag persists, enabling automatic crash recovery on next launch.
    if (navStyle == 2) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(2000)
            navPrefs.edit().remove("glass_nav_crash_pending").apply()
        }
    }

    LaunchedEffect(glassNavFailed) {
        if (glassNavFailed) {
            navPrefs.edit().remove("glass_nav_crash_pending").apply()
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.glass_nav_bar_fallback_toast),
                duration = SnackbarDuration.Long
            )
        }
    }

    val liquidGlassBackdrop = rememberLayerBackdrop()
    // 浮动玻璃底栏的取景层（miuix-blur）：底栏透过它折射页面内容
    val glassNavBackdrop = rememberMiuixLayerBackdrop()

    val direction = if (selectedTab > previousTab) 1 else -1
    val isRemoteWithVnc = selectedTab == 3 && showVnc
    val dragOffset = if (isSwipingInProgress) rawDragOffset else dragOffsetAnimatable.value
    val swipeProgress = if (isSwipingInProgress && swipeTargetTab != null) {
        (kotlin.math.abs(rawDragOffset) / screenWidthPx).coerceIn(0f, 1f)
    } else {
        0f
    }

    // 若当前选中页被屏蔽，回退到第一个可用页
    LaunchedEffect(availableTabs) {
        if (selectedTab !in availableTabs) {
            previousTab = selectedTab
            onTabChange(availableTabs.firstOrNull() ?: 0)
        }
    }

    fun handleSwipe(dragAmount: Float) {
        if (kotlin.math.abs(dragAmount) < SWIPE_THRESHOLD) return

        // 从 availableTabs 中查找下一个/上一个可用页（跳过被屏蔽的页面）
        fun nextAvailable(from: Int): Int? = availableTabs.filter { it > from }.minOrNull()
        fun prevAvailable(from: Int): Int? = availableTabs.filter { it < from }.maxOrNull()

        if (dragAmount < 0) {
            // Swipe left -> next
            if (selectedTab == 3 && showVnc) {
                if (remoteSubTab == 0) {
                    remoteSubTab = 1
                } else {
                    nextAvailable(3)?.let {
                        previousTab = selectedTab
                        onTabChange(it)
                    }
                }
            } else {
                nextAvailable(selectedTab)?.let {
                    previousTab = selectedTab
                    onTabChange(it)
                }
            }
        } else {
            // Swipe right -> previous
            if (selectedTab == 3 && showVnc && remoteSubTab == 1) {
                remoteSubTab = 0
            } else {
                prevAvailable(selectedTab)?.let {
                    previousTab = selectedTab
                    onTabChange(it)
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { topBarContent.value() },
        bottomBar = {
            when (navStyle) {
                2 -> {
                    val glassDims = computeNavDimensions(availableTabs.size, NavStyle.GLASS)
                    LiquidGlassNavigationBarWithIndicator(
                        selectedIndex = availableTabs.indexOf(selectedTab).coerceAtLeast(0),
                        itemCount = availableTabs.size,
                        backdrop = liquidGlassBackdrop,
                        onIndexChange = { index ->
                            val actualTab = availableTabs.getOrElse(index) { selectedTab }
                            if (actualTab != selectedTab) {
                                previousTab = selectedTab
                                onTabChange(actualTab)
                            }
                        }
                    ) { onPositioned ->
                        if (0 in availableTabs) {
                            LiquidGlassNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_overview),
                                label = stringResource(R.string.overview),
                                selected = selectedTab == 0,
                                onClick = { previousTab = selectedTab; onTabChange(0) },
                                dims = glassDims,
                                index = 0,
                                onPositioned = onPositioned
                            )
                        }
                        if (1 in availableTabs) {
                            LiquidGlassNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                                label = stringResource(R.string.terminal),
                                selected = selectedTab == 1,
                                onClick = { previousTab = selectedTab; onTabChange(1) },
                                dims = glassDims,
                                index = 1,
                                onPositioned = onPositioned
                            )
                        }
                        if (2 in availableTabs) {
                            LiquidGlassNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_files),
                                label = stringResource(R.string.files),
                                selected = selectedTab == 2,
                                onClick = { previousTab = selectedTab; onTabChange(2) },
                                dims = glassDims,
                                index = 2,
                                onPositioned = onPositioned
                            )
                        }
                        if (3 in availableTabs) {
                            LiquidGlassNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_vnc),
                                label = stringResource(R.string.remote),
                                selected = selectedTab == 3,
                                onClick = { previousTab = selectedTab; onTabChange(3) },
                                dims = glassDims,
                                index = 3,
                                onPositioned = onPositioned
                            )
                        }
                        if (4 in availableTabs) {
                            LiquidGlassNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_settings),
                                label = stringResource(R.string.settings),
                                selected = selectedTab == 4,
                                onClick = { previousTab = selectedTab; onTabChange(4) },
                                dims = glassDims,
                                index = 4,
                                onPositioned = onPositioned
                            )
                        }
                    }
                }
                3 -> {
                    val softLightDims = computeNavDimensions(availableTabs.size, NavStyle.SOFT_LIGHT)
                    SoftLightNavigationBarWithIndicator(
                        selectedIndex = availableTabs.indexOf(selectedTab).coerceAtLeast(0),
                        itemCount = availableTabs.size,
                        backdrop = liquidGlassBackdrop,
                        onIndexChange = { index ->
                            val actualTab = availableTabs.getOrElse(index) { selectedTab }
                            if (actualTab != selectedTab) {
                                previousTab = selectedTab
                                onTabChange(actualTab)
                            }
                        }
                    ) {
                        if (0 in availableTabs) {
                            SoftLightNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_overview),
                                label = stringResource(R.string.overview),
                                selected = selectedTab == 0,
                                onClick = { previousTab = selectedTab; onTabChange(0) },
                                dims = softLightDims
                            )
                        }
                        if (1 in availableTabs) {
                            SoftLightNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                                label = stringResource(R.string.terminal),
                                selected = selectedTab == 1,
                                onClick = { previousTab = selectedTab; onTabChange(1) },
                                dims = softLightDims
                            )
                        }
                        if (2 in availableTabs) {
                            SoftLightNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_files),
                                label = stringResource(R.string.files),
                                selected = selectedTab == 2,
                                onClick = { previousTab = selectedTab; onTabChange(2) },
                                dims = softLightDims
                            )
                        }
                        if (3 in availableTabs) {
                            SoftLightNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_vnc),
                                label = stringResource(R.string.remote),
                                selected = selectedTab == 3,
                                onClick = { previousTab = selectedTab; onTabChange(3) },
                                dims = softLightDims
                            )
                        }
                        if (4 in availableTabs) {
                            SoftLightNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_settings),
                                label = stringResource(R.string.settings),
                                selected = selectedTab == 4,
                                onClick = { previousTab = selectedTab; onTabChange(4) },
                                dims = softLightDims
                            )
                        }
                    }
                }
                0 -> {
                    val tabIcons = mapOf(
                        0 to R.drawable.ic_overview,
                        1 to R.drawable.ic_terminal,
                        2 to R.drawable.ic_files,
                        3 to R.drawable.ic_vnc,
                        4 to R.drawable.ic_settings
                    )
                    val tabLabels = mapOf(
                        0 to stringResource(R.string.overview),
                        1 to stringResource(R.string.terminal),
                        2 to stringResource(R.string.files),
                        3 to stringResource(R.string.remote),
                        4 to stringResource(R.string.settings)
                    )
                    GlassNavigationBar(
                        items = availableTabs.map { tab ->
                            GlassNavigationItem(
                                icon = ImageVector.vectorResource(tabIcons.getValue(tab)),
                                label = tabLabels.getValue(tab)
                            )
                        },
                        selectedIndex = availableTabs.indexOf(selectedTab).coerceAtLeast(0),
                        onSelect = { index ->
                            val actualTab = availableTabs.getOrElse(index) { selectedTab }
                            if (actualTab != selectedTab) {
                                previousTab = selectedTab
                                onTabChange(actualTab)
                            }
                        },
                        backdrop = glassNavBackdrop,
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = systemNavBarsHeight + 12.dp
                        )
                    )
                }
                // 经典底栏：miuix 原生 NavigationBar（原默认样式，现更名为经典）
                1 -> {
                    NavigationBar() {
                        if (0 in availableTabs) {
                            NavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_overview),
                                label = stringResource(R.string.overview),
                                selected = selectedTab == 0,
                                onClick = { previousTab = selectedTab; onTabChange(0) }
                            )
                        }
                        if (1 in availableTabs) {
                            NavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                                label = stringResource(R.string.terminal),
                                selected = selectedTab == 1,
                                onClick = { previousTab = selectedTab; onTabChange(1) }
                            )
                        }
                        if (2 in availableTabs) {
                            NavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_files),
                                label = stringResource(R.string.files),
                                selected = selectedTab == 2,
                                onClick = { previousTab = selectedTab; onTabChange(2) }
                            )
                        }
                        if (3 in availableTabs) {
                            NavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_vnc),
                                label = stringResource(R.string.remote),
                                selected = selectedTab == 3,
                                onClick = { previousTab = selectedTab; onTabChange(3) }
                            )
                        }
                        if (4 in availableTabs) {
                            NavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_settings),
                                label = stringResource(R.string.settings),
                                selected = selectedTab == 4,
                                onClick = { previousTab = selectedTab; onTabChange(4) }
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        val contentPadding = PaddingValues(
            top = padding.calculateTopPadding(),
            bottom = 0.dp
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    when {
                        useLiquidGlassNav || useSoftLightNav -> Modifier.layerBackdrop(liquidGlassBackdrop)
                        useGlassNav -> Modifier.miuixLayerBackdrop(glassNavBackdrop)
                        else -> Modifier
                    }
                )
                .padding(contentPadding)
                .pointerInput(selectedTab, showVnc, isOverviewEditMode, availableTabs) {
                    detectDragGestures(
                        onDragStart = {
                            if (isOverviewEditMode) return@detectDragGestures
                            isSwipingInProgress = true
                            rawDragOffset = 0f
                            swipeTargetTab = null
                            currentPageAlphaState = 1f
                        },
                        onDrag = { change, dragAmount ->
                            if (isOverviewEditMode) return@detectDragGestures
                            change.consume()
                            rawDragOffset += dragAmount.x
                            val progress = if (screenWidthPx > 0f) {
                                kotlin.math.abs(rawDragOffset) / screenWidthPx
                            } else {
                                0f
                            }
                            val isSubTabSwipe = selectedTab == 3 && showVnc && (
                                (remoteSubTab == 0 && rawDragOffset < 0) ||
                                (remoteSubTab == 1 && rawDragOffset > 0)
                            )
                            currentPageAlphaState = if (!isSubTabSwipe) {
                                (1f - progress).coerceIn(0f, 1f)
                            } else {
                                1f
                            }
                            if (kotlin.math.abs(rawDragOffset) > 10f) {
                                if (rawDragOffset < 0) {
                                    if (!(selectedTab == 3 && showVnc && remoteSubTab == 0)) {
                                        availableTabs.filter { it > selectedTab }.minOrNull()?.let {
                                            swipeTargetTab = it
                                        }
                                    }
                                } else {
                                    if (!(selectedTab == 3 && showVnc && remoteSubTab == 1)) {
                                        availableTabs.filter { it < selectedTab }.maxOrNull()?.let {
                                            swipeTargetTab = it
                                        }
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            if (isOverviewEditMode) {
                                rawDragOffset = 0f
                                swipeTargetTab = null
                                return@detectDragGestures
                            }
                            val exceeded = kotlin.math.abs(rawDragOffset) >= SWIPE_THRESHOLD
                            val finalOffset = rawDragOffset
                            val isMainTabSwipe = swipeTargetTab != null
                            val isSubTabSwipe = selectedTab == 3 && showVnc && (
                                (remoteSubTab == 0 && rawDragOffset < 0) ||
                                (remoteSubTab == 1 && rawDragOffset > 0)
                            )
                            val startOffset = rawDragOffset
                            val endOffset = if (exceeded && isMainTabSwipe) {
                                if (rawDragOffset > 0) screenWidthPx else -screenWidthPx
                            } else {
                                0f
                            }

                            scope.launch {
                                animate(
                                    initialValue = 0f,
                                    targetValue = 1f,
                                    animationSpec = tween(300)
                                ) { fraction, _ ->
                                    rawDragOffset = startOffset + (endOffset - startOffset) * fraction
                                    if (!isSubTabSwipe) {
                                        val progress = if (screenWidthPx > 0f) {
                                            kotlin.math.abs(rawDragOffset) / screenWidthPx
                                        } else {
                                            0f
                                        }
                                        currentPageAlphaState = (1f - progress).coerceIn(0f, 1f)
                                    }
                                }
                                rawDragOffset = endOffset

                                if (exceeded && isMainTabSwipe) {
                                    // Animation complete: overlay is fully faded out
                                    // Skip AnimatedContent transition to avoid double animation flash
                                    skipNextTransition = true
                                    handleSwipe(finalOffset)
                                    kotlinx.coroutines.delay(50)
                                    skipNextTransition = false
                                    isSwipingInProgress = false
                                    swipeTargetTab = null
                                    rawDragOffset = 0f
                                    currentPageAlphaState = 1f
                                } else if (exceeded && !isSubTabSwipe) {
                                    // Sub-tab swipe exceeded threshold
                                    skipNextTransition = true
                                    handleSwipe(finalOffset)
                                    kotlinx.coroutines.delay(50)
                                    skipNextTransition = false
                                    isSwipingInProgress = false
                                    swipeTargetTab = null
                                    rawDragOffset = 0f
                                    currentPageAlphaState = 1f
                                } else {
                                    // Not exceeded, spring back
                                    // Need to skip transition when returning to current page
                                    skipNextTransition = true
                                    isSwipingInProgress = false
                                    swipeTargetTab = null
                                    kotlinx.coroutines.delay(50)
                                    skipNextTransition = false
                                    rawDragOffset = 0f
                                    currentPageAlphaState = 1f
                                }
                            }
                        },
                        onDragCancel = {
                            if (isOverviewEditMode) {
                                rawDragOffset = 0f
                                swipeTargetTab = null
                                return@detectDragGestures
                            }
                            val startOffset = rawDragOffset
                            val isSubTabSwipe = selectedTab == 3 && showVnc && (
                                (remoteSubTab == 0 && rawDragOffset < 0) ||
                                (remoteSubTab == 1 && rawDragOffset > 0)
                            )
                            scope.launch {
                                animate(
                                    initialValue = 0f,
                                    targetValue = 1f,
                                    animationSpec = tween(300)
                                ) { fraction, _ ->
                                    rawDragOffset = startOffset * (1f - fraction)
                                    if (!isSubTabSwipe) {
                                        val progress = if (screenWidthPx > 0f) {
                                            kotlin.math.abs(rawDragOffset) / screenWidthPx
                                        } else {
                                            0f
                                        }
                                        currentPageAlphaState = (1f - progress).coerceIn(0f, 1f)
                                    }
                                }
                                // Skip transition when returning to current page
                                skipNextTransition = true
                                isSwipingInProgress = false
                                swipeTargetTab = null
                                rawDragOffset = 0f
                                currentPageAlphaState = 1f
                                kotlinx.coroutines.delay(50)
                                skipNextTransition = false
                            }
                        }
                    )
                }
        ) {
            // 底层：swipe 过程中的目标页面（独立渲染，不影响 AnimatedContent 中当前页面的状态）
            if (isSwipingInProgress && swipeTargetTab != null) {
                PageContentForTab(
                    context = context,
                    tab = swipeTargetTab!!,
                    sessions = sessions,
                    onSessionClick = onSessionClick,
                    onNewTerminal = onNewTerminal,
                    onNewTerminalAndOpenConsole = onNewTerminalAndOpenConsole,
                    onStopTerminal = onStopTerminal,
                    onRenameTerminal = onRenameTerminal,
                    onExecuteScript = onExecuteScript,
                    onAboutClick = onAboutClick,
                    showVnc = showVnc,
                    isWakeLockEnabled = isWakeLockEnabled,
                    onToggleWakeLock = onToggleWakeLock,
                    onRefreshSessions = onRefreshSessions,
                    onOverviewEditModeChanged = { isOverviewEditMode = it },
                    onRemoteSubTabChange = { remoteSubTab = it },
                    onGoToFiles = {
                        previousTab = swipeTargetTab!!
                        onTabChange(2)
                    },
                    onGoToSettings = {
                        previousTab = swipeTargetTab!!
                        onTabChange(4)
                    },
                    navBarBottomPadding = totalNavHeight,
                    onTopBarContent = { topBarContent.value = it },
                    active = swipeTargetTab == selectedTab
                )
            }

            // 顶层：当前页面的 AnimatedContent，targetState 始终为 selectedTab
            // swipe 时通过 graphicsLayer 跟随手指平移和淡出，不改变 targetState，页面状态不会被销毁重建
            AnimatedContent(
                targetState = selectedTab,
                label = "MainScreenTransition",
                modifier = if (isSwipingInProgress && swipeTargetTab != null) {
                    Modifier.graphicsLayer {
                        translationX = rawDragOffset
                        alpha = currentPageAlphaState
                    }
                } else {
                    Modifier
                },
                transitionSpec = {
                    if (skipNextTransition) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        fadeIn(
                            animationSpec = tween(durationMillis = 300)
                        ) togetherWith fadeOut(
                            animationSpec = tween(durationMillis = 150)
                        )
                    }
                }
            ) { tab ->
                PageContentForTab(
                    context = context,
                    tab = tab,
                    sessions = sessions,
                    onSessionClick = onSessionClick,
                    onNewTerminal = onNewTerminal,
                    onNewTerminalAndOpenConsole = onNewTerminalAndOpenConsole,
                    onStopTerminal = onStopTerminal,
                    onRenameTerminal = onRenameTerminal,
                    onExecuteScript = onExecuteScript,
                    onAboutClick = onAboutClick,
                    showVnc = showVnc,
                    isWakeLockEnabled = isWakeLockEnabled,
                    onToggleWakeLock = onToggleWakeLock,
                    onRefreshSessions = onRefreshSessions,
                    onOverviewEditModeChanged = { isOverviewEditMode = it },
                    onRemoteSubTabChange = { remoteSubTab = it },
                    onGoToFiles = {
                        previousTab = tab
                        onTabChange(2)
                    },
                    onGoToSettings = {
                        previousTab = tab
                        onTabChange(4)
                    },
                    navBarBottomPadding = totalNavHeight,
                    onTopBarContent = { topBarContent.value = it },
                    active = tab == selectedTab
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = snackbarBottomPadding),
            contentAlignment = Alignment.BottomCenter
        ) {
            SnackbarHost(state = snackbarHostState)
        }

        val riskDialogState by RiskConfirmManager.dialogState.collectAsState()
        val showAuthorizationMask = riskDialogState != null
        val disableWarningState by RiskConfirmManager.disableWarningState.collectAsState()
        val showDisableWarningMask = disableWarningState.show

        if (showAuthorizationMask) {
            AuthorizationMask()
        }

        if (showDisableWarningMask) {
            DisableWarningMask()
        }

        // 风险命令确认弹窗（主页不显示风险 Snackbar，由终端页独占）
        RiskConfirmDialogHost(snackbarHostState, collectSnackbar = false)

    }
}

@Composable
private fun PageContentForTab(
    context: android.content.Context,
    tab: Int,
    sessions: List<TermuxSession>,
    onSessionClick: (TermuxSession) -> Unit,
    onNewTerminal: () -> Unit,
    onNewTerminalAndOpenConsole: () -> Unit,
    onStopTerminal: (TermuxSession) -> Unit,
    onRenameTerminal: (TermuxSession, String) -> Unit,
    onExecuteScript: (String, String) -> Unit,
    onAboutClick: () -> Unit,
    showVnc: Boolean,
    isWakeLockEnabled: Boolean,
    onToggleWakeLock: () -> Unit,
    onRefreshSessions: () -> Unit,
    onOverviewEditModeChanged: (Boolean) -> Unit,
    onRemoteSubTabChange: (Int) -> Unit,
    onGoToFiles: () -> Unit,
    onGoToSettings: () -> Unit,
    navBarBottomPadding: Dp,
    onTopBarContent: (@Composable () -> Unit) -> Unit,
    active: Boolean = true
) {
    when (tab) {
        0 -> OverviewScreen(
            sessions = sessions,
            onSessionClick = onSessionClick,
            onNewTerminal = onNewTerminal,
            onNewTerminalAndOpenConsole = onNewTerminalAndOpenConsole,
            onStopAllSessions = {
                sessions.filter { it.getTerminalSession().isRunning }.forEach { session ->
                    onStopTerminal(session)
                }
            },
            isWakeLockEnabled = isWakeLockEnabled,
            onToggleWakeLock = onToggleWakeLock,
            onExecuteScript = onExecuteScript,
            onRefresh = onRefreshSessions,
            onEditModeChanged = onOverviewEditModeChanged,
            navBarBottomPadding = navBarBottomPadding,
            onTopBarContent = onTopBarContent,
            active = active
        )
        1 -> {
            val runtimeCore = TerminalRuntimeCore.getCurrent(context)
            if (runtimeCore == TerminalRuntimeCore.Core.KOTLIN_COMPOSE) {
                ComposeTerminalListScreen(
                    context = context,
                    onNewTerminal = onNewTerminal,
                    isWakeLockEnabled = isWakeLockEnabled,
                    onToggleWakeLock = onToggleWakeLock,
                    navBarBottomPadding = navBarBottomPadding,
                    onTopBarContent = onTopBarContent,
                    active = active
                )
            } else {
                TerminalListScreen(
                    sessions = sessions,
                    onSessionClick = onSessionClick,
                    onNewTerminal = onNewTerminal,
                    onStopTerminal = onStopTerminal,
                    onRenameTerminal = onRenameTerminal,
                    isWakeLockEnabled = isWakeLockEnabled,
                    onToggleWakeLock = onToggleWakeLock,
                    onRefresh = onRefreshSessions,
                    navBarBottomPadding = navBarBottomPadding,
                    onTopBarContent = onTopBarContent,
                    active = active
                )
            }
        }
        2 -> FileManagerScreen(
            onOpenFile = onExecuteScript,
            navBarBottomPadding = navBarBottomPadding,
            onTopBarContent = onTopBarContent,
            active = active
        )
        3 -> com.termux.app.remote.RemoteScreen(
            showVnc = showVnc,
            initialTab = 0,
            onTabChange = onRemoteSubTabChange,
            onGoToFiles = onGoToFiles,
            onGoToSettings = onGoToSettings,
            navBarBottomPadding = navBarBottomPadding,
            onTopBarContent = onTopBarContent,
            active = active
        )
        4 -> SettingsScreen(
            onAboutClick = onAboutClick,
            navBarBottomPadding = navBarBottomPadding,
            onTopBarContent = onTopBarContent,
            active = active
        )
    }
}

/**
 * 默认（首帧回退）的全局顶栏：仅展示当前页标题，保证切页动画期间顶栏不为空。
 * 各页面在组合阶段通过 [topBarContent] 槽覆盖为带导航图标与操作按钮的完整顶栏。
 */
@Composable
private fun MainTopBar(tab: Int, showVnc: Boolean, scrollBehavior: ScrollBehavior) {
    val title = when (tab) {
        0 -> stringResource(R.string.overview)
        1 -> stringResource(R.string.terminal)
        2 -> stringResource(R.string.files)
        3 -> if (showVnc) stringResource(R.string.remote) else stringResource(R.string.ssh)
        else -> stringResource(R.string.settings)
    }
    TopAppBar(title = title, scrollBehavior = scrollBehavior)
}
