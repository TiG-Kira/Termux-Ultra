package com.termux.app.compose

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
    val dragOffsetAnimatable = remember { Animatable(0f) }
    var isSwipingInProgress by remember { mutableStateOf(false) }

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
    val isRemoteWithVnc = selectedTab == 2 && showVnc
    val dragOffset = if (isSwipingInProgress) rawDragOffset else dragOffsetAnimatable.value

    // 页面可用性过滤：根据设备 API 支持程度隐藏无可用功能的页面入口。
    // tab 索引 0-4 分别对应 终端/文件/远程/资源/设置 页面。
    fun pageForTab(tab: Int): ApiCompat.Page = when (tab) {
        0 -> ApiCompat.Page.TERMINAL
        1 -> ApiCompat.Page.FILES
        2 -> ApiCompat.Page.REMOTE
        3 -> ApiCompat.Page.RESOURCES
        else -> ApiCompat.Page.SETTINGS
    }
    val availableTabs = remember {
        listOf(0, 1, 2, 3, 4).filter { ApiCompat.isPageAvailable(pageForTab(it)) }
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
            if (selectedTab == 2 && showVnc) {
                if (remoteSubTab == 0) {
                    remoteSubTab = 1
                } else {
                    nextAvailable(2)?.let {
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
            if (selectedTab == 2 && showVnc && remoteSubTab == 1) {
                remoteSubTab = 0
            } else {
                prevAvailable(selectedTab)?.let {
                    previousTab = selectedTab
                    if (selectedTab == 3 && showVnc && it == 2) {
                        remoteSubTab = 1
                    }
                    onTabChange(it)
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { 
            SnackbarHost(
                state = snackbarHostState,
                modifier = Modifier
                    .padding(WindowInsets.navigationBars.asPaddingValues())
                    .padding(bottom = 97.dp)
            ) 
        },
        bottomBar = {
            when (navStyle) {
                2 -> {
                    LiquidGlassNavigationBarWithIndicator(
                        selectedIndex = selectedTab,
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
                            LiquidGlassNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                                label = stringResource(R.string.terminal),
                                selected = selectedTab == 0,
                                onClick = { previousTab = selectedTab; onTabChange(0) }
                            )
                        }
                        if (1 in availableTabs) {
                            LiquidGlassNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_files),
                                label = stringResource(R.string.files),
                                selected = selectedTab == 1,
                                onClick = { previousTab = selectedTab; onTabChange(1) }
                            )
                        }
                        if (2 in availableTabs) {
                            LiquidGlassNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_vnc),
                                label = stringResource(R.string.remote),
                                selected = selectedTab == 2,
                                onClick = { previousTab = selectedTab; onTabChange(2) }
                            )
                        }
                        if (3 in availableTabs) {
                            LiquidGlassNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_resources),
                                label = stringResource(R.string.resources),
                                selected = selectedTab == 3,
                                onClick = { previousTab = selectedTab; onTabChange(3) }
                            )
                        }
                        if (4 in availableTabs) {
                            LiquidGlassNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_settings),
                                label = stringResource(R.string.settings),
                                selected = selectedTab == 4,
                                onClick = { previousTab = selectedTab; onTabChange(4) }
                            )
                        }
                    }
                }
                3 -> {
                    SoftLightNavigationBarWithIndicator(
                        selectedIndex = selectedTab,
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
                                icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                                label = stringResource(R.string.terminal),
                                selected = selectedTab == 0,
                                onClick = { previousTab = selectedTab; onTabChange(0) }
                            )
                        }
                        if (1 in availableTabs) {
                            SoftLightNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_files),
                                label = stringResource(R.string.files),
                                selected = selectedTab == 1,
                                onClick = { previousTab = selectedTab; onTabChange(1) }
                            )
                        }
                        if (2 in availableTabs) {
                            SoftLightNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_vnc),
                                label = stringResource(R.string.remote),
                                selected = selectedTab == 2,
                                onClick = { previousTab = selectedTab; onTabChange(2) }
                            )
                        }
                        if (3 in availableTabs) {
                            SoftLightNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_resources),
                                label = stringResource(R.string.resources),
                                selected = selectedTab == 3,
                                onClick = { previousTab = selectedTab; onTabChange(3) }
                            )
                        }
                        if (4 in availableTabs) {
                            SoftLightNavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_settings),
                                label = stringResource(R.string.settings),
                                selected = selectedTab == 4,
                                onClick = { previousTab = selectedTab; onTabChange(4) }
                            )
                        }
                    }
                }
                1 -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = 14.dp)
                    ) {
                        FloatingNavigationBar() {
                            if (0 in availableTabs) {
                                FloatingNavigationBarItem(
                                    icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                                    label = stringResource(R.string.terminal),
                                    selected = selectedTab == 0,
                                    onClick = { previousTab = selectedTab; onTabChange(0) }
                                )
                            }
                            if (1 in availableTabs) {
                                FloatingNavigationBarItem(
                                    icon = ImageVector.vectorResource(R.drawable.ic_files),
                                    label = stringResource(R.string.files),
                                    selected = selectedTab == 1,
                                    onClick = { previousTab = selectedTab; onTabChange(1) }
                                )
                            }
                            if (2 in availableTabs) {
                                FloatingNavigationBarItem(
                                    icon = ImageVector.vectorResource(R.drawable.ic_vnc),
                                    label = stringResource(R.string.remote),
                                    selected = selectedTab == 2,
                                    onClick = { previousTab = selectedTab; onTabChange(2) }
                                )
                            }
                            if (3 in availableTabs) {
                                FloatingNavigationBarItem(
                                    icon = ImageVector.vectorResource(R.drawable.ic_resources),
                                    label = stringResource(R.string.resources),
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
                else -> {
                    NavigationBar() {
                        if (0 in availableTabs) {
                            NavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                                label = stringResource(R.string.terminal),
                                selected = selectedTab == 0,
                                onClick = { previousTab = selectedTab; onTabChange(0) }
                            )
                        }
                        if (1 in availableTabs) {
                            NavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_files),
                                label = stringResource(R.string.files),
                                selected = selectedTab == 1,
                                onClick = { previousTab = selectedTab; onTabChange(1) }
                            )
                        }
                        if (2 in availableTabs) {
                            NavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_vnc),
                                label = stringResource(R.string.remote),
                                selected = selectedTab == 2,
                                onClick = { previousTab = selectedTab; onTabChange(2) }
                            )
                        }
                        if (3 in availableTabs) {
                            NavigationBarItem(
                                icon = ImageVector.vectorResource(R.drawable.ic_resources),
                                label = stringResource(R.string.resources),
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
                .pointerInput(selectedTab, showVnc) {
                    detectDragGestures(
                        onDragStart = {
                            isSwipingInProgress = true
                            rawDragOffset = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            rawDragOffset += dragAmount.x
                        },
                        onDragEnd = {
                            val exceeded = kotlin.math.abs(rawDragOffset) >= SWIPE_THRESHOLD
                            handleSwipe(rawDragOffset)
                            val finalOffset = rawDragOffset
                            isSwipingInProgress = false
                            if (!exceeded) {
                                scope.launch {
                                    dragOffsetAnimatable.snapTo(finalOffset)
                                    dragOffsetAnimatable.animateTo(0f, animationSpec = tween(200))
                                }
                            } else {
                                scope.launch {
                                    dragOffsetAnimatable.snapTo(0f)
                                }
                            }
                            rawDragOffset = 0f
                        },
                        onDragCancel = {
                            val finalOffset = rawDragOffset
                            isSwipingInProgress = false
                            scope.launch {
                                dragOffsetAnimatable.snapTo(finalOffset)
                                dragOffsetAnimatable.animateTo(0f, animationSpec = tween(200))
                            }
                            rawDragOffset = 0f
                        }
                    )
                }
        ) {
            AnimatedContent(
                targetState = selectedTab,
                label = "MainScreenTransition",
                transitionSpec = {
                    if (targetState != initialState && (isSwipingInProgress || kotlin.math.abs(dragOffset) > 0f)) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        if (selectedTab > previousTab) {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(200)
                            ) togetherWith slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(200)
                            )
                        } else {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(200)
                            ) togetherWith slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(200)
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationX = dragOffset
                }
            ) { tab ->
                when (tab) {
                    0 -> TerminalListScreen(
                        sessions = sessions,
                        onSessionClick = onSessionClick,
                        onNewTerminal = onNewTerminal,
                        onStopTerminal = onStopTerminal,
                        onRenameTerminal = onRenameTerminal,
                        isWakeLockEnabled = isWakeLockEnabled,
                        onToggleWakeLock = onToggleWakeLock,
                        onRefresh = onRefreshSessions
                    )
                    1 -> FileManagerScreen(onOpenFile = onExecuteScript)
                    2 -> com.termux.app.remote.RemoteScreen(
                        showVnc = showVnc,
                        initialTab = remoteSubTab,
                        onTabChange = { remoteSubTab = it },
                        onGoToFiles = {
                            previousTab = selectedTab
                            onTabChange(1)
                        },
                        onGoToResources = {
                            previousTab = selectedTab
                            onTabChange(3)
                        }
                    )
                    3 -> ResourcesScreen()
                    4 -> SettingsScreen(onAboutClick = onAboutClick)
                }
            }
        }

        val stopDialogState by StopConfirmDialog.dialogState.collectAsState()
        val riskDialogState by RiskConfirmManager.dialogState.collectAsState()
        val disableWarningState by RiskConfirmManager.disableWarningState.collectAsState()
        val showAuthorizationMask = stopDialogState != null || riskDialogState != null || disableWarningState.show

        if (showAuthorizationMask) {
            AuthorizationMask()
        }

        // 停止/退出确认弹窗（状态驱动，在主 Compose 树内渲染，避免 addView overlay 白屏）
        StopConfirmDialogHost(snackbarHostState)

        // 风险命令确认弹窗（主页不显示风险 Snackbar，由终端页独占）
        RiskConfirmDialogHost(snackbarHostState, collectSnackbar = false)
    }
}
