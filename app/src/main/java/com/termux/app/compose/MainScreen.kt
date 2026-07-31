package com.termux.app.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
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
    var remoteSubTab by remember { mutableStateOf(0) }
    var previousTab by remember { mutableStateOf(selectedTab) }
    var rawDragOffset by remember { mutableFloatStateOf(0f) }
    val dragOffsetAnimatable = remember { Animatable(0f) }
    var isSwipingInProgress by remember { mutableStateOf(false) }

    val direction = if (selectedTab > previousTab) 1 else -1
    val isRemoteWithVnc = selectedTab == 2 && showVnc
    val dragOffset = if (isSwipingInProgress) rawDragOffset else dragOffsetAnimatable.value

    fun handleSwipe(dragAmount: Float) {
        if (kotlin.math.abs(dragAmount) < SWIPE_THRESHOLD) return

        if (dragAmount < 0) {
            // Swipe left -> next
            if (selectedTab == 2 && showVnc) {
                if (remoteSubTab == 0) {
                    remoteSubTab = 1
                } else {
                    onTabChange(3)
                }
            } else {
                val next = when (selectedTab) {
                    0 -> 1
                    1 -> 2
                    2 -> if (showVnc) 3 else 3
                    3 -> 4
                    else -> null
                }
                next?.let {
                    previousTab = selectedTab
                    onTabChange(it)
                }
            }
        } else {
            // Swipe right -> previous
            if (selectedTab == 2 && showVnc && remoteSubTab == 1) {
                remoteSubTab = 0
            } else {
                val prev = when (selectedTab) {
                    1 -> 0
                    2 -> 1
                    3 -> 2
                    4 -> 3
                    else -> null
                }
                prev?.let {
                    previousTab = selectedTab
                    if (selectedTab == 3 && showVnc) {
                        remoteSubTab = 1
                    }
                    onTabChange(it)
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(modifier = Modifier.padding(horizontal = 12.dp)) {
                NavigationBarItem(
                    icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                    label = stringResource(R.string.terminal),
                    selected = selectedTab == 0,
                    onClick = { previousTab = selectedTab; onTabChange(0) }
                )
                NavigationBarItem(
                    icon = ImageVector.vectorResource(R.drawable.ic_files),
                    label = stringResource(R.string.files),
                    selected = selectedTab == 1,
                    onClick = { previousTab = selectedTab; onTabChange(1) }
                )
                NavigationBarItem(
                    icon = ImageVector.vectorResource(R.drawable.ic_vnc),
                    label = stringResource(R.string.remote),
                    selected = selectedTab == 2,
                    onClick = { previousTab = selectedTab; onTabChange(2) }
                )
                NavigationBarItem(
                    icon = ImageVector.vectorResource(R.drawable.ic_resources),
                    label = stringResource(R.string.resources),
                    selected = selectedTab == 3,
                    onClick = { previousTab = selectedTab; onTabChange(3) }
                )
                NavigationBarItem(
                    icon = ImageVector.vectorResource(R.drawable.ic_settings),
                    label = stringResource(R.string.settings),
                    selected = selectedTab == 4,
                    onClick = { previousTab = selectedTab; onTabChange(4) }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                modifier = Modifier.graphicsLayer {
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
    }
}
