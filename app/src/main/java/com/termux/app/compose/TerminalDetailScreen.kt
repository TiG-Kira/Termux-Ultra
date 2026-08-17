package com.termux.app.compose

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.viewpager.widget.ViewPager
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.activities.HelpActivity
import com.termux.app.activities.SettingsActivity
import com.termux.app.terminal.io.TerminalToolbarViewPager
import com.termux.shared.shell.TermuxSession
import com.termux.shared.view.KeyboardUtils
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Top-level composable function for easy integration with TermuxActivity.setContent {}.
 * Full Compose mode: embeds TerminalView in Compose hierarchy.
 */
@Composable
fun createTerminalDetailScreen(
    activity: TermuxActivity,
    terminalView: TerminalView,
    onBack: () -> Unit,
) {
    TerminalDetailScreen(activity, terminalView, onBack, false)
}

/**
 * Overlay mode: TerminalView remains in legacy XML layout; Compose renders
 * only the top bar, dialogs and bottom toolbar on top as a floating layer.
 */
@Composable
fun createTerminalDetailOverlay(
    activity: TermuxActivity,
    terminalView: TerminalView,
    onBack: () -> Unit,
) {
    TerminalDetailScreen(activity, terminalView, onBack, true)
}

@Composable
fun TerminalDetailScreen(
    activity: TermuxActivity,
    terminalView: TerminalView,
    onBack: () -> Unit,
    overlayMode: Boolean = false,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var localSessions by remember { mutableStateOf<List<TermuxSession>>(emptyList()) }
    var currentSessionName by remember { mutableStateOf("") }
    var showSessionList by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var sessionCount by remember { mutableIntStateOf(0) }
    var showKillConfirm by remember { mutableStateOf(false) }
    var showToolbar by remember { mutableStateOf(activity.preferences.shouldShowTerminalToolbar()) }
    var toolbarHeight by remember { mutableIntStateOf(activity.terminalToolbarDefaultHeightValue) }
    var sessionPid by remember { mutableIntStateOf(0) }
    var terminalBgColor by remember { mutableStateOf(Color(0xFF000000)) }
    var isTerminalDark by remember { mutableStateOf(true) }

    val scrollBehavior = MiuixScrollBehavior()

    val topBarContentColor = if (isTerminalDark) Color.White else Color.Black
    val topBarContentColorSecondary = if (isTerminalDark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f)

    val termuxService = activity.termuxService

    fun updateCurrentSessionName(act: TermuxActivity, svc: com.termux.app.TermuxService?) {
        val session = act.currentSession
        currentSessionName = if (session != null) {
            session.mSessionName?.ifEmpty {
                val index = svc?.getIndexOfSession(session) ?: -1
                if (index >= 0) context.getString(R.string.terminal) + " ${index + 1}" else context.getString(R.string.terminal)
            } ?: context.getString(R.string.terminal)
        } else {
            context.getString(R.string.terminal)
        }
    }

    fun refreshSessions() {
        val svc = activity.termuxService
        if (svc != null) {
            localSessions = svc.termuxSessions.toList()
            sessionCount = localSessions.size
        }
    }

    LaunchedEffect(termuxService) {
        while (true) {
            val svc = termuxService
            if (svc != null) {
                val fresh = svc.termuxSessions.toList()
                if (fresh != localSessions) {
                    localSessions = fresh
                    sessionCount = fresh.size
                }
                updateCurrentSessionName(activity, termuxService)
            }
            delay(100)
        }
    }

    LaunchedEffect(showSessionList) {
        if (showSessionList) {
            refreshSessions()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val emulator = terminalView.mEmulator
            if (emulator != null) {
                val bgColor = try {
                    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]
                } catch (_: Exception) {
                    0xFF000000.toInt()
                }
                if (bgColor != 0) {
                    terminalBgColor = Color(bgColor)
                    isTerminalDark = terminalBgColor.luminance() < 0.5f
                }
            }
            val activity = terminalView.context as? android.app.Activity
            if (activity != null) {
                val controller = androidx.core.view.WindowCompat.getInsetsController(
                    activity.window, terminalView
                )
                controller.isAppearanceLightStatusBars = !isTerminalDark
                controller.isAppearanceLightNavigationBars = !isTerminalDark
            }
            delay(500)
        }
    }

    fun switchToSession(session: TermuxSession) {
        val termSession = session.getTerminalSession()
        activity.termuxTerminalSessionClient.setCurrentSession(termSession)
        showSessionList = false
        coroutineScope.launch {
            delay(100)
            updateCurrentSessionName(activity, termuxService)
        }
    }

    fun closeCurrentSession() {
        val currentSession = activity.currentSession
        if (currentSession == null) return

        val sessions = termuxService?.termuxSessions ?: emptyList()
        val currentIndex = sessions.indexOfFirst { it.getTerminalSession() == currentSession }
        var targetSession: TermuxSession? = null
        if (sessions.size > 1 && currentIndex >= 0) {
            targetSession = if (currentIndex > 0) sessions[currentIndex - 1] else sessions[currentIndex + 1]
        }

        val sessionName = currentSession.mSessionName ?: context.getString(R.string.terminal)
        activity.showToast("$sessionName 已停止，返回代码: 137", true)
        termuxService?.removeTermuxSession(currentSession)

        if (targetSession != null) {
            activity.termuxTerminalSessionClient.setCurrentSession(targetSession.getTerminalSession())
        }

        localSessions = termuxService?.termuxSessions ?: emptyList()
        sessionCount = localSessions.size

        if (sessionCount == 0) {
            onBack()
        } else {
            coroutineScope.launch {
                delay(100)
                updateCurrentSessionName(activity, termuxService)
            }
        }
    }

    fun addNewSession(isFailSafe: Boolean, sessionName: String?) {
        activity.termuxTerminalSessionClient.addNewSession(isFailSafe, sessionName)
        coroutineScope.launch {
            delay(200)
            localSessions = termuxService?.termuxSessions ?: emptyList()
            sessionCount = localSessions.size
            updateCurrentSessionName(activity, termuxService)
        }
    }

    fun renameSession(newName: String) {
        val currentSession = activity.currentSession ?: return
        currentSession.mSessionName = newName
        showRenameDialog = false
        coroutineScope.launch {
            delay(100)
            updateCurrentSessionName(activity, termuxService)
        }
    }

    fun toggleKeyboard() {
        if (terminalView.hasFocus()) {
            KeyboardUtils.hideSoftKeyboard(context, terminalView)
        } else {
            terminalView.requestFocus()
            KeyboardUtils.showSoftKeyboard(context, terminalView)
        }
    }

    fun toggleToolbar() {
        showToolbar = !showToolbar
        activity.preferences.setShowTerminalToolbar(showToolbar)
        if (showToolbar) {
            if (activity.isTerminalToolbarTextInputViewSelected()) {
                val textInputView: android.view.View? = activity.findViewById(R.id.terminal_toolbar_text_input)
                textInputView?.requestFocus()
            }
        }
    }

    fun killCurrentProcess() {
        val currentSession = activity.currentSession ?: return
        currentSession.finishIfRunning()
        showKillConfirm = false
        closeCurrentSession()
    }

    fun resetTerminal() {
        val currentSession = activity.currentSession ?: return
        currentSession.reset()
        activity.showToast(context.getString(R.string.msg_terminal_reset), true)
        showContextMenu = false
    }

    fun showStylingDialog() {
        if (!IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_STYLING)) {
            IntegratedTools.showEnablePrompt(context, IntegratedTools.Tool.TERMUX_STYLING)
            return
        }
        val stylingIntent = Intent()
        stylingIntent.setClassName(context.packageName, "com.termux.styling.TermuxStyleActivity")
        try {
            context.startActivity(stylingIntent)
        } catch (_: Exception) {
            IntegratedTools.showEnablePrompt(context, IntegratedTools.Tool.TERMUX_STYLING)
        }
        showContextMenu = false
    }

    fun toggleKeepScreenOn() {
        if (terminalView.keepScreenOn) {
            terminalView.keepScreenOn = false
            activity.preferences.setKeepScreenOn(false)
        } else {
            terminalView.keepScreenOn = true
            activity.preferences.setKeepScreenOn(true)
        }
        showContextMenu = false
    }

    fun shareTranscript() {
        activity.termuxTerminalViewClient.shareSessionTranscript()
        showContextMenu = false
    }

    fun openHelp() {
        context.startActivity(Intent(context, HelpActivity::class.java))
        showContextMenu = false
    }

    fun openSettings() {
        context.startActivity(Intent(context, SettingsActivity::class.java))
        showContextMenu = false
    }

    fun reportIssue() {
        activity.termuxTerminalViewClient.reportIssueFromTranscript()
        showContextMenu = false
    }

    val scrollState = rememberScrollState()

    // 监听 TerminalView 的滚动状态，更新 scrollBehavior 实现吸顶效果
    LaunchedEffect(terminalView) {
        while (true) {
            val topRow = try {
                terminalView.getTopRow()
            } catch (_: Exception) {
                0
            }
            // terminalView.getTopRow() 返回负数表示向上滚动（查看历史），0 表示在底部
            scrollBehavior.state.contentOffset = (-topRow).toFloat()
            delay(50)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
                val newSessionEntry = DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = stringResource(R.string.terminal_new_session),
                            onClick = { addNewSession(false, null) }
                        ),
                        DropdownItem(
                            text = stringResource(R.string.terminal_new_safe_session),
                            onClick = { addNewSession(true, null) }
                        )
                    )
                )
                TopAppBar(
                    title = currentSessionName.ifEmpty { stringResource(R.string.terminal) },
                    scrollBehavior = scrollBehavior,
                    color = Color.Transparent,
                    titleColor = topBarContentColor,
                    largeTitleColor = topBarContentColor,
                navigationIcon = {
                    Row(modifier = Modifier.padding(start = 4.dp)) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = topBarContentColor
                            )
                        }
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val terminalInteractionSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .combinedClickable(
                                    interactionSource = terminalInteractionSource,
                                    indication = androidx.compose.foundation.LocalIndication.current,
                                    onClick = { showSessionList = true },
                                    onLongClick = {
                                        renameValue = currentSessionName
                                        showRenameDialog = true
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_terminal),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = topBarContentColor
                            )
                        }
                        IconButton(onClick = { toggleKeyboard() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_keyboard),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = topBarContentColor
                            )
                        }
                        OverlayIconDropdownMenu(
                            entry = newSessionEntry,
                            backgroundColor = Color.Transparent,
                            minWidth = 40.dp,
                            minHeight = 40.dp
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = topBarContentColor
                            )
                        }
                        IconButton(onClick = {
                            val currentSession = activity.currentSession
                            if (currentSession != null) {
                                closeCurrentSession()
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = topBarContentColor
                            )
                        }
                        IconButton(onClick = { showContextMenu = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = topBarContentColor
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!overlayMode && showToolbar) {
                Box(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    TerminalToolbar(activity = activity)
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!overlayMode) {
                // Full Compose mode: embed TerminalView in Compose hierarchy
                AndroidView(
                    factory = { ctx ->
                        // Ensure TerminalView is detached from any previous parent
                        // (e.g. the detached pre-inflated layout in preInflateLegacyViews)
                        (terminalView.parent as? android.view.ViewGroup)?.removeView(terminalView)

                        terminalView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        FrameLayout(ctx).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                            addView(terminalView)
                        }
                    },
                    update = { frameLayout ->
                        terminalView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        frameLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        if (terminalView.parent != frameLayout) {
                            (terminalView.parent as? android.view.ViewGroup)?.removeView(terminalView)
                            frameLayout.removeAllViews()
                            frameLayout.addView(terminalView)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (showSessionList) {
                OverlayDialog(
                    show = showSessionList,
                    onDismissRequest = { showSessionList = false },
                    title = stringResource(R.string.terminal_sessions),
                    content = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((localSessions.size.coerceAtLeast(3) * 72).coerceAtMost(360).dp)
                                .verticalScroll(scrollState)
                        ) {
                            localSessions.forEachIndexed { index, session ->
                                val termSession = session.getTerminalSession()
                                val isActive = activity.currentSession == termSession
                                val shellPid = termSession.shellPid
                                val isDead = shellPid == -1
                                val titleColor = when {
                                    isDead -> Color(0xFFFF5252)
                                    isActive -> MiuixTheme.colorScheme.primary
                                    else -> MiuixTheme.colorScheme.onSurface
                                }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable(enabled = !isDead) {
                                            switchToSession(session)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    if (isActive) MiuixTheme.colorScheme.primaryContainer
                                                    else MiuixTheme.colorScheme.surfaceVariant
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_terminal),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = if (isActive) MiuixTheme.colorScheme.primary else titleColor
                                            )
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = termSession.mSessionName
                                                    ?: "${stringResource(R.string.terminal)} ${index + 1}",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = titleColor,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isDead) {
                                                Text(
                                                    text = "退出代码:${termSession.exitStatus}",
                                                    fontSize = 12.sp,
                                                    color = Color(0xFFD32F2F)
                                                )
                                            } else if (isActive) {
                                                Text(
                                                    text = stringResource(R.string.current_session),
                                                    fontSize = 12.sp,
                                                    color = MiuixTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        if (!isDead) {
                                            IconButton(
                                                onClick = {
                                                    termuxService?.removeTermuxSession(termSession)
                                                    localSessions = termuxService?.termuxSessions ?: emptyList()
                                                    sessionCount = localSessions.size
                                                    if (isActive) {
                                                        if (sessionCount > 0) {
                                                            val targetSession = localSessions.firstOrNull()
                                                            if (targetSession != null) {
                                                                activity.termuxTerminalSessionClient.setCurrentSession(targetSession.getTerminalSession())
                                                            }
                                                        }
                                                        coroutineScope.launch {
                                                            delay(100)
                                                            updateCurrentSessionName(activity, termuxService)
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_close),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }

            if (showContextMenu) {
                val currentSession = activity.currentSession
                val isRunning = currentSession?.isRunning ?: false
                sessionPid = currentSession?.pid ?: 0
                val keepScreenOn = terminalView.keepScreenOn

                OverlayDialog(
                    show = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                    title = currentSessionName,
                    content = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.TextFields,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                },
                                text = stringResource(R.string.action_reset_terminal),
                                onClick = { resetTerminal() }
                            )
                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = if (isRunning) Color(0xFFFF5252) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                },
                                text = stringResource(R.string.action_kill_process, sessionPid),
                                enabled = isRunning,
                                onClick = { showKillConfirm = true }
                            )
                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_palette),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                },
                                text = stringResource(R.string.action_style_terminal),
                                onClick = { showStylingDialog() }
                            )
                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        imageVector = if (keepScreenOn) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                },
                                text = stringResource(R.string.action_toggle_keep_screen_on),
                                trailing = if (keepScreenOn) "✓" else "",
                                onClick = { toggleKeepScreenOn() }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.ExpandLess,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                },
                                text = stringResource(R.string.action_toggle_soft_keyboard),
                                onClick = {
                                    toggleKeyboard()
                                    showContextMenu = false
                                }
                            )
                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                },
                                text = stringResource(R.string.action_open_settings),
                                onClick = { openSettings() }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Info,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                },
                                text = stringResource(R.string.action_open_help),
                                onClick = { openHelp() }
                            )
                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Warning,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                },
                                text = stringResource(R.string.action_report_issue),
                                onClick = { reportIssue() }
                            )
                        }
                    }
                )
            }

            if (showRenameDialog) {
                OverlayDialog(
                    show = showRenameDialog,
                    onDismissRequest = { showRenameDialog = false },
                    title = stringResource(R.string.rename_terminal),
                    content = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            top.yukonga.miuix.kmp.basic.TextField(
                                value = renameValue,
                                onValueChange = { renameValue = it },
                                label = stringResource(R.string.terminal_name)
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                top.yukonga.miuix.kmp.basic.TextButton(
                                    text = stringResource(R.string.cancel),
                                    onClick = { showRenameDialog = false },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(20.dp))
                                top.yukonga.miuix.kmp.basic.TextButton(
                                    text = stringResource(R.string.ok),
                                    onClick = { renameSession(renameValue) },
                                    modifier = Modifier.weight(1f),
                                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary()
                                )
                            }
                        }
                    }
                )
            }

            if (showKillConfirm) {
                OverlayDialog(
                    show = showKillConfirm,
                    onDismissRequest = { showKillConfirm = false },
                    title = stringResource(R.string.action_kill_process, sessionPid),
                    content = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.title_confirm_kill_process),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                top.yukonga.miuix.kmp.basic.TextButton(
                                    text = stringResource(android.R.string.cancel),
                                    onClick = { showKillConfirm = false },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(20.dp))
                                top.yukonga.miuix.kmp.basic.TextButton(
                                    text = stringResource(android.R.string.yes),
                                    onClick = { killCurrentProcess() },
                                    modifier = Modifier.weight(1f),
                                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary()
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TerminalToolbar(activity: TermuxActivity) {
    val context = LocalContext.current
    // Use the shared toolbar ViewPager from TermuxActivity (created in
    // setTerminalToolbarView). This ensures legacy code (key event routing
    // to terminal_toolbar_text_input, etc.) keeps working even though the
    // screen is now rendered by Compose.
    val sharedPager = remember {
        val pager = activity.terminalToolbarViewPagerInstance
        // Ensure pager is detached from any previous parent (e.g. the
        // detached pre-inflated layout in preInflateLegacyViews) before
        // AndroidView tries to host it.
        (pager.parent as? android.view.ViewGroup)?.removeView(pager)
        pager
    }
    AndroidView(
        factory = { ctx ->
            sharedPager.apply {
                val properties = activity.properties
                val extraKeysMatrix = properties.extraKeysInfo?.matrix
                val toolbarHeight = (activity.terminalToolbarDefaultHeightValue *
                    (extraKeysMatrix?.size ?: 0) *
                    properties.terminalToolbarHeightScaleFactor).toInt()

                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    toolbarHeight
                )

                setBackgroundColor(android.graphics.Color.BLACK)
                visibility = if (activity.preferences.shouldShowTerminalToolbar())
                    android.view.View.VISIBLE else android.view.View.GONE

                // After the ViewPager is attached and has its adapter laid out,
                // refresh the cached text-input EditText so that legacy
                // TermuxTerminalViewClient code can find the real one.
                post {
                    val editText = findViewById<android.widget.EditText>(R.id.terminal_toolbar_text_input)
                    if (editText != null) {
                        activity.updateCachedToolbarTextInput(editText)
                    }
                }
            }
        },
        update = { viewPager ->
            val properties = activity.properties
            val extraKeysMatrix = properties.extraKeysInfo?.matrix
            val toolbarHeight = (activity.terminalToolbarDefaultHeightValue *
                (extraKeysMatrix?.size ?: 0) *
                properties.terminalToolbarHeightScaleFactor).toInt()

            val params = viewPager.layoutParams
            params.height = toolbarHeight
            viewPager.layoutParams = params
            viewPager.visibility = if (activity.preferences.shouldShowTerminalToolbar())
                android.view.View.VISIBLE else android.view.View.GONE
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ContextMenuItem(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: String = ""
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = alpha),
            modifier = Modifier.weight(1f)
        )
        if (trailing.isNotEmpty()) {
            Text(
                text = trailing,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary
            )
        }
    }
}
