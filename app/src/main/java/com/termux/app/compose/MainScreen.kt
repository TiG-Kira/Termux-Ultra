package com.termux.app.compose

import android.content.Context

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import com.termux.R
import com.termux.shared.shell.TermuxSession

private const val SWIPE_THRESHOLD = 100f
private const val SWIPE_VELOCITY_THRESHOLD = 300f

@Composable
fun MainScreen(
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    sessions: List<TermuxSession>,
    onSessionClick: (TermuxSession) -> Unit,
    onNewTerminal: () -> Unit,
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
    val navBarStyle = remember {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("navigation_bar_style", "default") ?: "default"
    }
    val navPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    val useFloatingNav = navBarStyle == "floating"
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
        useFloatingNav -> 1
        useSoftLightNav -> 3
        else -> 0
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

    val navStyleForHeight = when (navStyle) {
        2 -> NavStyle.GLASS
        3 -> NavStyle.SOFT_LIGHT
        1 -> NavStyle.FLOATING
        else -> NavStyle.DEFAULT
    }
    val navContainerHeight = getNavContainerHeight(availableTabs.size, navStyleForHeight)
    val totalNavHeight = if (navStyle == 0) {
        navContainerHeight + systemNavBarsHeight
    } else {
        navContainerHeight
    }
    val snackbarBottomPadding = when (navStyle) {
        // 浮动导航栏使用 offset(y = bottomMargin - 4.dp) 定位
        // 实际底部距屏幕底部约 4.dp，所以总高度 = systemBars + containerHeight + 4.dp
        1 -> systemNavBarsHeight + navContainerHeight + 16.dp
        // 玻璃/柔光/默认导航栏：总高度已包含 bottomMargin
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
                1 -> {
                    val floatingDims = computeNavDimensions(availableTabs.size, NavStyle.FLOATING)
                    val configuration = LocalConfiguration.current
                    val density = LocalDensity.current
                    val screenWidthDp = with(density) { configuration.screenWidthDp.dp }
                    val sidePadding = 12.dp * 2
                    val availableWidthPx = with(density) { (screenWidthDp - sidePadding).toPx() }
                    val baseItemWidthPx = with(density) { 72.dp.toPx() }
                    val baseGapPx = with(density) { 16.dp.toPx() }
                    val baseWidthPx = availableTabs.size * baseItemWidthPx + (availableTabs.size - 1) * baseGapPx
                    val scaleFactor = if (baseWidthPx > availableWidthPx) {
                        (availableWidthPx / baseWidthPx).coerceAtLeast(0.85f)
                    } else {
                        1.0f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = floatingDims.bottomMargin - 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier.graphicsLayer {
                                scaleX = scaleFactor
                                scaleY = scaleFactor
                            }
                        ) {
                            FloatingNavigationBar() {
                            if (0 in availableTabs) {
                                FloatingNavigationBarItem(
                                    icon = ImageVector.vectorResource(R.drawable.ic_overview),
                                    label = stringResource(R.string.overview),
                                    selected = selectedTab == 0,
                                    onClick = { previousTab = selectedTab; onTabChange(0) }
                                )
                            }
                            if (1 in availableTabs) {
                                FloatingNavigationBarItem(
                                    icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                                    label = stringResource(R.string.terminal),
                                    selected = selectedTab == 1,
                                    onClick = { previousTab = selectedTab; onTabChange(1) }
                                )
                            }
                            if (2 in availableTabs) {
                                FloatingNavigationBarItem(
                                    icon = ImageVector.vectorResource(R.drawable.ic_files),
                                    label = stringResource(R.string.files),
                                    selected = selectedTab == 2,
                                    onClick = { previousTab = selectedTab; onTabChange(2) }
                                )
                            }
                            if (3 in availableTabs) {
                                FloatingNavigationBarItem(
                                    icon = ImageVector.vectorResource(R.drawable.ic_vnc),
                                    label = stringResource(R.string.remote),
                                    selected = selectedTab == 3,
                                    onClick = { previousTab = selectedTab; onTabChange(3) }
                                )
                            }
                            if (4 in availableTabs) {
                                FloatingNavigationBarItem(
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
                else -> {
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
                    if (useLiquidGlassNav || useSoftLightNav) {
                        Modifier.layerBackdrop(liquidGlassBackdrop)
                    } else {
                        Modifier
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
                                    // Commit tab change - AnimatedContent is already showing target page
                                    handleSwipe(finalOffset)
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
            // During swipe, AnimatedContent renders the target page underneath
            // The current page is shown as an overlay on top
            val animatedTargetState = if (isSwipingInProgress && swipeTargetTab != null) swipeTargetTab!! else selectedTab

            AnimatedContent(
                targetState = animatedTargetState,
                label = "MainScreenTransition",
                transitionSpec = {
                    // Skip transition during swipe or when skipNextTransition is set
                    if (targetState != initialState && (isSwipingInProgress || kotlin.math.abs(dragOffset) > 0f || skipNextTransition)) {
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
                    tab = tab,
                    sessions = sessions,
                    onSessionClick = onSessionClick,
                    onNewTerminal = onNewTerminal,
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
                    navBarBottomPadding = totalNavHeight
                )
            }

            // Current page overlay on top (fades out as user swipes)
            if (isSwipingInProgress && swipeTargetTab != null && swipeTargetTab != selectedTab) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = rawDragOffset
                            alpha = currentPageAlphaState
                        }
                ) {
                    PageContentForTab(
                        tab = selectedTab,
                        sessions = sessions,
                        onSessionClick = onSessionClick,
                        onNewTerminal = onNewTerminal,
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
                            previousTab = selectedTab
                            onTabChange(2)
                        },
                        onGoToSettings = {
                            previousTab = selectedTab
                            onTabChange(4)
                        },
                        navBarBottomPadding = totalNavHeight
                    )
                }
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
    tab: Int,
    sessions: List<TermuxSession>,
    onSessionClick: (TermuxSession) -> Unit,
    onNewTerminal: () -> Unit,
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
    navBarBottomPadding: Dp
) {
    when (tab) {
        0 -> OverviewScreen(
            sessions = sessions,
            onSessionClick = onSessionClick,
            onNewTerminal = onNewTerminal,
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
            navBarBottomPadding = navBarBottomPadding
        )
        1 -> TerminalListScreen(
            sessions = sessions,
            onSessionClick = onSessionClick,
            onNewTerminal = onNewTerminal,
            onStopTerminal = onStopTerminal,
            onRenameTerminal = onRenameTerminal,
            isWakeLockEnabled = isWakeLockEnabled,
            onToggleWakeLock = onToggleWakeLock,
            onRefresh = onRefreshSessions,
            navBarBottomPadding = navBarBottomPadding
        )
        2 -> FileManagerScreen(
            onOpenFile = onExecuteScript,
            navBarBottomPadding = navBarBottomPadding
        )
        3 -> com.termux.app.remote.RemoteScreen(
            showVnc = showVnc,
            initialTab = 0,
            onTabChange = onRemoteSubTabChange,
            onGoToFiles = onGoToFiles,
            onGoToSettings = onGoToSettings,
            navBarBottomPadding = navBarBottomPadding
        )
        4 -> SettingsScreen(
            onAboutClick = onAboutClick,
            navBarBottomPadding = navBarBottomPadding
        )
    }
}
