package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.app.compose.terminal.ComposeSessionManager
import com.termux.app.compose.terminal.ComposeTerminalSettings
import com.termux.app.compose.terminal.ComposeTerminalScreen
import com.termux.app.compose.terminal.engine.TerminalSession as LibTerminalSession
import com.termux.shared.view.KeyboardUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun TerminalDetailScreenCompose(
    sessionManager: ComposeSessionManager,
    session: LibTerminalSession,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    // 订阅 sessionManager 的 sessions 列表和当前 id
    val allSessions by sessionManager.sessions.collectAsState()
    val currentSessionId by sessionManager.currentSessionId.collectAsState()

    // 当前活跃会话（sessionManager 管理的 currentSession）
    val currentSession = allSessions.firstOrNull { it.session.id == currentSessionId }?.session ?: session

    ComposeTerminalSettings.init(context)

    val textSize by ComposeTerminalSettings.fontSize.collectAsState()
    val cursorBlink by ComposeTerminalSettings.cursorBlink.collectAsState()
    val colorScheme by ComposeTerminalSettings.colorScheme.collectAsState()
    val stylingColorScheme by ComposeTerminalSettings.stylingColorScheme.collectAsState()
    val stylingTypeface by ComposeTerminalSettings.stylingTypeface.collectAsState()
    val softKeyboardEnabled by ComposeTerminalSettings.softKeyboard.collectAsState()
    val isKeepScreenOn by ComposeTerminalSettings.keepScreenOn.collectAsState()
    val showToolbar by ComposeTerminalSettings.showToolbar.collectAsState()

    // Styling 磁盘主题优先于内置 color_scheme（与 Java 模式共用 ~/.termux/colors.properties）
    val effectiveColorScheme = stylingColorScheme ?: colorScheme

    var isCompact by remember { mutableStateOf(false) }
    var isTopBarTransitioning by remember { mutableStateOf(false) }
    var isTopBarCollapsed by remember { mutableStateOf(false) }
    var showLargeContent by remember { mutableStateOf(true) }
    var topBarSlideProgress by remember { mutableFloatStateOf(0f) }
    var smallTitleAlpha by remember { mutableFloatStateOf(0f) }
    var useLargeButtons by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var sessionKey by remember { mutableIntStateOf(0) }
    var showNewSessionLabel by remember { mutableStateOf(false) }
    var sessionLabelTimer by remember { mutableStateOf(0L) }

    var showContextMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var showSessionList by remember { mutableStateOf(false) }

    val rawSessionName by currentSession.sessionName.collectAsState(initial = "")
    val oscTitle by currentSession.titleState.collectAsState(initial = null)
    // 完全照搬 Java 版逻辑：用在会话列表中的 index + 1 当序号，不是 session.id + 1！
    val currentSessionIndex = allSessions.indexOfFirst { it.session.id == currentSession.id }
    val sessionDisplayNumber = if (currentSessionIndex >= 0) currentSessionIndex + 1 else 1
    val currentSessionName = when {
        rawSessionName.isNotEmpty() -> rawSessionName
        !oscTitle.isNullOrBlank() -> oscTitle!!
        else -> context.getString(R.string.session_display_number, sessionDisplayNumber)
    }

    // pid 语义与 Java 版一致：0=未初始化（不算已结束），-1=已结束。
    // pid 是普通字段不触发重组，收集 sessionExited 流保证会话结束的瞬间
    // 就立刻显示context.getString(R.string.session_ended)并展开 TopAppBar（对齐 Java 版行为）
    val sessionExited by currentSession.sessionExited.collectAsState()
    val removeRequested by currentSession.isRemove.collectAsState()
    val currentSessionIsDead = currentSession.pid == -1 || sessionExited
    val sessionExitCode = currentSession.exitStatus

    // Termux 标准关会话逻辑：死会话内按 Enter（[Process completed - press Enter]）→ 从列表移除
    LaunchedEffect(removeRequested) {
        if (removeRequested) {
            sessionManager.killSession(currentSession.id)
        }
    }

    // 当前会话切换后（会话列表点击 / 主页卡片点击 / 第三方镜像切换），未初始化的会话
    // 在真正进入终端控制台的那一刻才初始化（拉起进程），效仿 Java 版策略
    LaunchedEffect(currentSessionId) {
        val cs = allSessions.firstOrNull { it.session.id == currentSessionId }?.session
        if (cs != null && cs.pid == 0) {
            cs.execute()
        }
    }

    // ===== 颜色逻辑（完全照搬 Java 版 TerminalDetailScreen.kt L239-251）=====
    // 1. 终端实际渲染背景色 → SmallTopAppBar 图标亮暗、状态栏图标亮暗
    val terminalBgInt = effectiveColorScheme.background
    val terminalBgColor = Color(terminalBgInt)
    val isTerminalDark = terminalBgColor.luminance() < 0.5f

    // 2. 大 TopAppBar 背景 → 用系统亮暗主题的固定 opaque 色
    val isSystemDarkTheme = isSystemInDarkTheme()
    val topBarOpaqueBg = if (isSystemDarkTheme) Color(0xFF1C1B1F) else Color(0xFFFFFFFF)

    // 3. 大 TopAppBar 图标 → 从 opaque 背景 luminance 算
    val topBarOpaqueContent = if (topBarOpaqueBg.luminance() > 0.5f) Color(0xFF000000) else Color(0xFFFFFFFF)
    val topBarOpaqueContentSecondary = topBarOpaqueContent.copy(alpha = 0.7f)

    // 4. SmallTopAppBar 图标 → 从终端实际背景算
    val topBarTerminalContent = if (isTerminalDark) Color.White else Color.Black
    val topBarTerminalContentSecondary = if (isTerminalDark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f)

    // 5. 关键！effective 根据 isCompact 切换来源：
    //    - 大 TopAppBar 模式 (!isCompact) → opaque 颜色（系统主题）
    //    - SmallTopAppBar 模式 (isCompact) → terminal 颜色（终端实际背景）
    val effectiveTopBarContentColor = if (!isCompact) topBarOpaqueContent else topBarTerminalContent
    val effectiveTopBarContentColorSecondary = if (!isCompact) topBarOpaqueContentSecondary else topBarTerminalContentSecondary

    val topBarIndication = LocalIndication.current

    fun updateInteractionTime() {
        lastInteractionTime = System.currentTimeMillis()
    }

    fun showSnack(message: String) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    fun toggleKeyboard() {
        KeyboardUtils.toggleSoftKeyboard(context)
        updateInteractionTime()
    }

    fun toggleKeepScreenOn() {
        ComposeTerminalSettings.setKeepScreenOn(!isKeepScreenOn)
        val activity = context as? android.app.Activity
        activity?.window?.let { window ->
            if (!isKeepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        showContextMenu = false
        showSnack(if (!isKeepScreenOn) context.getString(R.string.keep_screen_on_enabled) else context.getString(R.string.keep_screen_on_disabled))
    }

    fun resetSession() {
        currentSession.reset()
        showContextMenu = false
        showSnack(context.getString(R.string.terminal_reset))
    }

    fun killSessionProcess() {
        currentSession.finishIfRunning()
        showContextMenu = false
        showSnack(context.getString(R.string.process_killed))
    }

    fun closeCurrentSession() {
        sessionManager.killSession(currentSession.id)
        sessionKey++
        showSnack(context.getString(R.string.sessions_closed))
    }

    fun renameSession(newName: String) {
        val fallbackNumber = allSessions.indexOfFirst { it.session.id == currentSession.id }.let { if (it >= 0) it + 1 else 1 }
        currentSession.sessionName.value = newName.ifEmpty { context.getString(R.string.session_fallback_number, fallbackNumber) }
        showRenameDialog = false
    }

    fun switchToSession(id: Int) {
        // 未初始化会话的初始化由下方 LaunchedEffect(currentSessionId) 统一处理
        sessionManager.switchTo(id)
        showSessionList = false
    }

    fun shareTranscript() {
        val text = try {
            val emulatorField = LibTerminalSession::class.java.getDeclaredField("emulator")
            emulatorField.isAccessible = true
            val emulator = emulatorField.get(session)
            val methods = emulator.javaClass.methods
            val getTextMethod = methods.firstOrNull {
                it.name.contains("text", ignoreCase = true) && it.parameterCount == 0
            }
            getTextMethod?.invoke(emulator) as? String ?: ""
        } catch (_: Exception) { "" }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, "${currentSessionName.ifEmpty { context.getString(R.string.terminal) }} session dump")
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_session)))
        showContextMenu = false
    }

    fun openSettings() {
        // 直接跳转主页设置页（MainActivity 设置 tab）
        try {
            val intent = Intent(
                context,
                com.termux.app.MainActivity::class.java
            ).apply {
                putExtra(com.termux.app.MainActivity.EXTRA_OPEN_SETTINGS_TAB, true)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
        showContextMenu = false
    }

    fun requestPermissions() {
        try {
            context.startActivity(Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.packageName)
            ))
        } catch (_: Exception) {}
        showContextMenu = false
    }

    fun showTopBarTemporarily() {
        if (isTopBarTransitioning) return
        if (isCompact) {
            isTopBarTransitioning = true
            useLargeButtons = false
            showLargeContent = false
            showNewSessionLabel = false
            sessionKey++
            lastInteractionTime = System.currentTimeMillis()
            coroutineScope.launch {
                animate(initialValue = smallTitleAlpha, targetValue = 0f, animationSpec = tween(100, easing = FastOutLinearInEasing)) { value, _ ->
                    smallTitleAlpha = value
                }
                topBarSlideProgress = 1f
                isCompact = false
                isTopBarCollapsed = false
                delay(200)
                animate(initialValue = 1f, targetValue = 0f, animationSpec = tween(220, easing = FastOutSlowInEasing)) { value, _ ->
                    topBarSlideProgress = value
                }
                showLargeContent = true
                delay(150)
                isTopBarTransitioning = false
                useLargeButtons = true
            }
        } else {
            showLargeContent = true
            showNewSessionLabel = false
            sessionKey++
            lastInteractionTime = System.currentTimeMillis()
        }
    }

    fun collapseTopBarAnimated() {
        if (!isCompact && !isTopBarTransitioning) {
            isTopBarTransitioning = true
            useLargeButtons = true
            showNewSessionLabel = false
            coroutineScope.launch {
                showLargeContent = false
                delay(100)
                animate(initialValue = topBarSlideProgress, targetValue = 1f, animationSpec = tween(220, easing = FastOutSlowInEasing)) { value, _ ->
                    topBarSlideProgress = value
                }
                isCompact = true
                delay(200)
                if (isTopBarCollapsed) {
                    animate(initialValue = smallTitleAlpha, targetValue = 1f, animationSpec = tween(120)) { value, _ ->
                        smallTitleAlpha = value
                    }
                }
                isTopBarTransitioning = false
                useLargeButtons = false
            }
        }
    }

    fun addNewSession(isFailSafe: Boolean = false) {
        // 效仿 Java 版控制台行为：新建终端（含安全会话）直接进入新会话
        val newSession = sessionManager.createDefaultSession(startImmediately = true, isFailsafe = isFailSafe)
        sessionManager.switchTo(newSession.id)
        // TopAppBar 自动触发逻辑看齐 Java 版：
        // 展开大 TopAppBar + 显示context.getString(R.string.new_session)副标题 + 重启 3 秒自动收起计时
        if (isCompact) {
            showTopBarTemporarily()
        } else {
            showLargeContent = true
        }
        showNewSessionLabel = true
        sessionKey++
        lastInteractionTime = System.currentTimeMillis()
    }

    // 状态栏颜色适配（照搬 Java 版 L286-301）
    LaunchedEffect(isCompact, topBarOpaqueBg, isTerminalDark) {
        val act = context as? android.app.Activity
        if (act != null) {
            val window = act.window
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            if (!isCompact) {
                // TopAppBar visible: status bar matches TopAppBar background, fully opaque
                window.statusBarColor = android.graphics.Color.argb(
                    255,
                    (topBarOpaqueBg.red * 255).toInt(),
                    (topBarOpaqueBg.green * 255).toInt(),
                    (topBarOpaqueBg.blue * 255).toInt()
                )
                controller.isAppearanceLightStatusBars = topBarOpaqueBg.luminance() > 0.5f
            } else {
                // SmallTopAppBar mode: status bar transparent
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                controller.isAppearanceLightStatusBars = !isTerminalDark
            }
            controller.isAppearanceLightNavigationBars = !isTerminalDark
        }
    }

    LaunchedEffect(sessionKey, currentSessionIsDead) {
        lastInteractionTime = System.currentTimeMillis()
        while (true) {
            if (currentSessionIsDead) {
                if (isCompact) showTopBarTemporarily()
                delay(500)
                continue
            }
            val now = System.currentTimeMillis()
            val elapsed = now - lastInteractionTime
            if (showSessionList || showContextMenu || showRenameDialog) {
                delay(100)
                continue
            }
            if (elapsed >= 3000) {
                collapseTopBarAnimated()
                delay(500)
                showNewSessionLabel = false
                break
            }
            delay(100)
        }
    }

    LaunchedEffect(isKeepScreenOn) {
        val activity = context as? android.app.Activity
        if (activity != null) {
            val window = activity.window
            if (isKeepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val addSessionEntry = DropdownEntry(
        items = listOf(
            DropdownItem(
                text = context.getString(R.string.new_session),
                onClick = { addNewSession() }
            ),
            DropdownItem(
                text = context.getString(R.string.new_failsafe_session),
                onClick = { addNewSession(isFailSafe = true) }
            )
        )
    )

    @Composable
    fun TopBarActionCard(content: @Composable () -> Unit) {
        val cardColor = if (!isCompact) {
            if (topBarOpaqueBg.luminance() > 0.5f) {
                Color.Black.copy(alpha = 0.08f)
            } else {
                Color.White.copy(alpha = 0.12f)
            }
        } else {
            if (isTerminalDark) {
                Color.White.copy(alpha = 0.12f)
            } else {
                Color.Black.copy(alpha = 0.08f)
            }
        }
        Card(
            cornerRadius = 21.dp,
            colors = CardDefaults.defaultColors(
                color = cardColor,
                contentColor = effectiveTopBarContentColor
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                content()
            }
        }
    }

    @Composable
    fun SmallTopActionButtons() {
        val terminalInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .combinedClickable(
                    interactionSource = terminalInteractionSource,
                    indication = topBarIndication,
                    onClick = { updateInteractionTime(); showSessionList = true },
                    onLongClick = {
                        updateInteractionTime()
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
                tint = effectiveTopBarContentColor
            )
        }
        if (softKeyboardEnabled) {
            IconButton(onClick = { updateInteractionTime(); toggleKeyboard() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = effectiveTopBarContentColor
                )
            }
        }
        OverlayIconDropdownMenu(
            entry = addSessionEntry,
            backgroundColor = Color.Transparent,
            minWidth = 40.dp,
            minHeight = 40.dp
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = effectiveTopBarContentColor
            )
        }
        IconButton(
            onClick = {
                updateInteractionTime()
                closeCurrentSession()
            },
            enabled = !currentSessionIsDead
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (currentSessionIsDead)
                    effectiveTopBarContentColor.copy(alpha = 0.3f)
                else effectiveTopBarContentColor
            )
        }
        IconButton(onClick = { updateInteractionTime(); showContextMenu = true }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = effectiveTopBarContentColor
            )
        }
    }

    @Composable
    fun LargeTopActionButtons() {
        val terminalInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .combinedClickable(
                    interactionSource = terminalInteractionSource,
                    indication = topBarIndication,
                    onClick = { updateInteractionTime(); showSessionList = true },
                    onLongClick = {
                        updateInteractionTime()
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
                tint = effectiveTopBarContentColor
            )
        }
        if (softKeyboardEnabled) {
            IconButton(onClick = { updateInteractionTime(); toggleKeyboard() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = effectiveTopBarContentColor
                )
            }
        }
        OverlayIconDropdownMenu(
            entry = addSessionEntry,
            backgroundColor = Color.Transparent,
            minWidth = 40.dp,
            minHeight = 40.dp
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = effectiveTopBarContentColor
            )
        }
        IconButton(
            onClick = {
                updateInteractionTime()
                closeCurrentSession()
            },
            enabled = !currentSessionIsDead
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (currentSessionIsDead)
                    effectiveTopBarContentColor.copy(alpha = 0.3f)
                else effectiveTopBarContentColor
            )
        }
        IconButton(onClick = { updateInteractionTime(); showContextMenu = true }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = effectiveTopBarContentColor
            )
        }
    }

    @Composable
    fun TopBarButtonRow() {
        val showLargeButtons = if (isTopBarTransitioning) useLargeButtons else !isCompact

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .then(
                    if (isCompact) {
                        Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showTopBarTemporarily() }
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.padding(start = 16.dp)) {
                IconButton(onClick = { updateInteractionTime(); onBack() }) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = effectiveTopBarContentColor
                    )
                }
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (!showLargeButtons) {
                    Text(
                        text = currentSessionName.ifEmpty { context.getString(R.string.terminal) },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Normal,
                        color = effectiveTopBarContentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .alpha(smallTitleAlpha)
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isTopBarCollapsed,
                    enter = expandHorizontally(
                        expandFrom = Alignment.End,
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) + fadeIn(tween(150)),
                    exit = shrinkHorizontally(
                        shrinkTowards = Alignment.End,
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) + fadeOut(tween(150))
                ) {
                    TopBarActionCard {
                        if (showLargeButtons) LargeTopActionButtons() else SmallTopActionButtons()
                    }
                }
            }

            Row(modifier = Modifier.padding(start = 8.dp, end = 16.dp)) {
                IconButton(
                    onClick = {
                        updateInteractionTime()
                        val newCollapsed = !isTopBarCollapsed
                        isTopBarCollapsed = newCollapsed
                        if (isCompact) {
                            coroutineScope.launch {
                                animate(
                                    initialValue = smallTitleAlpha,
                                    targetValue = if (newCollapsed) 1f else 0f,
                                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                                ) { value, _ ->
                                    smallTitleAlpha = value
                                }
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (isTopBarCollapsed) Icons.Rounded.KeyboardArrowRight else Icons.Rounded.KeyboardArrowLeft,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = effectiveTopBarContentColor
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    state = snackbarHostState,
                    modifier = Modifier
                        .padding(WindowInsets.navigationBars.asPaddingValues())
                        .padding(bottom = 5.dp)
                )
            },
            topBar = {
                val bgAlpha by animateFloatAsState(
                    targetValue = if (!isCompact) 1f else 0f,
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    label = "topBarBg"
                )
                val topBarColor = topBarOpaqueBg.copy(alpha = topBarOpaqueBg.alpha * bgAlpha)
                val titleAlpha by animateFloatAsState(
                    targetValue = if (showLargeContent) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = if (showLargeContent) 150 else 100,
                        easing = FastOutLinearInEasing
                    ),
                    label = "titleAlpha"
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(topBarColor)
                ) {
                    TopBarButtonRow()
                    if (!isCompact || isTopBarTransitioning) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Transparent)
                                .clipToBounds()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    val visibleHeight =
                                        (placeable.height * (1f - topBarSlideProgress)).roundToInt()
                                    layout(placeable.width, visibleHeight) {
                                        placeable.placeRelative(
                                            0,
                                            -(placeable.height * topBarSlideProgress).roundToInt()
                                        )
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .alpha(titleAlpha)
                        ) {
                            Column {
                                when {
                                    currentSessionIsDead -> {
                                        Text(
                                            text = if (sessionExitCode >= 0)
                                                context.getString(R.string.session_exit_code_label, sessionExitCode)
                                            else context.getString(R.string.session_ended),
                                            fontSize = 13.sp,
                                            color = Color(0xFFFF5252),
                                            modifier = Modifier.padding(bottom = 1.dp)
                                        )
                                    }
                                    showNewSessionLabel -> {
                                        val handleText = sessionDisplayNumber.toString()
                                        Text(
                                            text = context.getString(R.string.new_session_handle, handleText),
                                            fontSize = 13.sp,
                                            color = effectiveTopBarContentColorSecondary,
                                            modifier = Modifier.padding(bottom = 1.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = currentSessionName.ifEmpty { context.getString(R.string.terminal) },
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = effectiveTopBarContentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (showToolbar) {
                    Box(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .imePadding()
                    ) {
                        TerminalKeyboardToolbar(
                            onSendKey = { bytes -> currentSession.write(bytes) },
                            effectiveContentColor = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                ComposeTerminalScreen(
                    session = currentSession,
                    modifier = Modifier.fillMaxSize(),
                    useLightTheme = false,
                    textSize = textSize,
                    cursorBlink = cursorBlink,
                    colorScheme = effectiveColorScheme,
                    typeface = stylingTypeface
                )
            }

            if (showSessionList) {
                OverlayDialog(
                    show = showSessionList,
                    onDismissRequest = { showSessionList = false },
                    title = context.getString(R.string.session_list),
                    content = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((allSessions.size.coerceAtLeast(3) * 72).coerceAtMost(360).dp)
                                .verticalScroll(scrollState)
                        ) {
                            allSessions.forEach { info ->
                                val s = info.session
                                val isActive = s.id == currentSessionId
                                // pid 语义与 Java 版一致：0=未初始化, >0=运行中, -1=已结束
                                val isDead = s.pid == -1
                                val isUninitialized = s.pid == 0
                                // 当前会话高亮底色：暗色模式深灰，亮色模式亮灰白（图标等颜色不变）
                                val currentHighlightColor =
                                    if (isSystemDarkTheme) Color(0xFF424242) else Color(0xFFE0E0E0)
                                val titleColor = when {
                                    isDead -> Color(0xFFFF5252)
                                    isActive -> MiuixTheme.colorScheme.primary
                                    else -> MiuixTheme.colorScheme.onSurface
                                }
                                // 每个会话独立订阅 sessionName 和 titleState，确保重命名和 shell OSC 都能实时更新
                                val sessionName by s.sessionName.collectAsState()
                                val sessionOscTitle by s.titleState.collectAsState(initial = null)
                                val sessionIndexInList = allSessions.indexOfFirst { it.session.id == s.id }
                                val displayName = when {
                                    sessionName.isNotEmpty() -> sessionName
                                    !sessionOscTitle.isNullOrBlank() -> sessionOscTitle!!
                                    else -> "Session ${if (sessionIndexInList >= 0) sessionIndexInList + 1 else 1}"
                                }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { switchToSession(s.id) }
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
                                                    if (isActive) currentHighlightColor
                                                    else MiuixTheme.colorScheme.surfaceVariant
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_terminal),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = if (isActive) MiuixTheme.colorScheme.primary
                                                else MiuixTheme.colorScheme.onSurface
                                            )
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = displayName,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = titleColor
                                            )
                                            val pidText = when {
                                                isUninitialized -> context.getString(R.string.uninitialized)
                                                isDead -> if (s.exitStatus >= 0) context.getString(R.string.card_ended_code, s.exitStatus)
                                                          else context.getString(R.string.ended)
                                                else -> "PID ${s.pid}"
                                            }
                                            Text(
                                                text = pidText,
                                                fontSize = 12.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            )
                                        }
                                        if (isActive) {
                                            Text(
                                                text = context.getString(R.string.active),
                                                fontSize = 11.sp,
                                                color = MiuixTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }

            if (showContextMenu) {
                OverlayDialog(
                    show = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                    title = currentSessionName.ifEmpty { context.getString(R.string.terminal) },
                    content = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
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
                                text = context.getString(R.string.reset_terminal),
                                onClick = { resetSession() }
                            )
                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = if (currentSession.isRunning) Color(0xFFFF5252)
                                        else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                },
                                text = if (currentSession.isRunning) context.getString(R.string.kill_process_pid, currentSession.pid) else context.getString(R.string.process_not_running),
                                enabled = currentSession.isRunning,
                                onClick = { killSessionProcess() }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        imageVector = if (isKeepScreenOn) Icons.Rounded.KeyboardArrowUp
                                        else Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                },
                                text = if (isKeepScreenOn) context.getString(R.string.disable_keep_screen_on) else context.getString(R.string.keep_screen_on),
                                trailing = if (isKeepScreenOn) "✓" else "",
                                onClick = { toggleKeepScreenOn() }
                            )
                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.ExpandLess,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                },
                                text = context.getString(R.string.toggle_soft_keyboard),
                                onClick = { toggleKeyboard(); showContextMenu = false }
                            )
                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Info,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                },
                                text = context.getString(R.string.share_session_dump),
                                onClick = { shareTranscript() }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            ContextMenuItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                },
                                text = context.getString(R.string.app_settings_entry),
                                onClick = { openSettings() }
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
                                text = context.getString(R.string.system_permissions),
                                onClick = { requestPermissions() }
                            )
                        }
                    }
                )
            }

            if (showRenameDialog) {
                OverlayDialog(
                    show = showRenameDialog,
                    onDismissRequest = { showRenameDialog = false },
                    title = context.getString(R.string.rename_session),
                    content = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            TextField(
                                value = renameValue,
                                onValueChange = { renameValue = it },
                                label = context.getString(R.string.session_name)
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TextButton(
                                    text = context.getString(R.string.cancel),
                                    onClick = { showRenameDialog = false },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(20.dp))
                                TextButton(
                                    text = context.getString(R.string.ok),
                                    onClick = { renameSession(renameValue) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.textButtonColorsPrimary()
                                )
                            }
                        }
                    }
                )
            }

            // 挂载风险确认宿主：收集增强防护 Snackbar 事件（仅提示/完全拦截），与 Java 版控制台行为一致
            RiskConfirmDialogHost(snackbarHostState)
        }
    }
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

@Composable
private fun TerminalKeyboardToolbar(
    onSendKey: (ByteArray) -> Unit,
    effectiveContentColor: Color
) {
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var fnActive by remember { mutableStateOf(false) }

    val surfaceBg = MiuixTheme.colorScheme.surface.copy(alpha = 0.95f)

    fun send(bytes: ByteArray) {
        onSendKey(bytes)
        ctrlActive = false
        altActive = false
        fnActive = false
    }

    fun sendChar(c: Char) {
        when {
            ctrlActive -> {
                val ctrlCode = (c.lowercaseChar().toInt() - 'a'.toInt() + 1).coerceIn(1, 26).toByte()
                send(byteArrayOf(ctrlCode))
            }
            altActive -> {
                send(byteArrayOf(0x1B, c.toInt().toByte()))
            }
            else -> {
                send(byteArrayOf(c.toInt().toByte()))
            }
        }
    }

    fun sendEscape(seq: String) {
        send(byteArrayOf(0x1B) + seq.toByteArray())
    }

    val hScroll = rememberScrollState()

    // 横向滚动放在最外层统一处理：滚动范围由最宽的一行决定，
    // 三行保持同步滚动且都能滚到最右（此前三行共用一个 ScrollState，
    // maxValue 被内容较窄的行钳制，导致第一行末尾按钮被屏幕边缘截断无法显示）。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceBg)
            .navigationBarsPadding()
            .imePadding()
            .horizontalScroll(hScroll)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                KeyButton("ESC", { send(byteArrayOf(0x1B)) }, effectiveContentColor)
                KeyButton("<", { sendChar('<') }, effectiveContentColor)
                KeyButton(">", { sendChar('>') }, effectiveContentColor)
                KeyButton("\\", { sendChar('\\') }, effectiveContentColor)
                KeyButton("=", { sendChar('=') }, effectiveContentColor)
                KeyButton("^", { sendChar('^') }, effectiveContentColor)
                KeyButton("$", { sendChar('$') }, effectiveContentColor)
                KeyButton("(", { sendChar('(') }, effectiveContentColor)
                KeyButton(")", { sendChar(')') }, effectiveContentColor)
                KeyButton("{", { sendChar('{') }, effectiveContentColor)
                KeyButton("}", { sendChar('}') }, effectiveContentColor)
                KeyButton("[", { sendChar('[') }, effectiveContentColor)
                KeyButton("]", { sendChar(']') }, effectiveContentColor)
                KeyButton("⌫", { send(byteArrayOf(0x7F)) }, effectiveContentColor)
            }
            Row(
                modifier = Modifier.padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                KeyButton("⇥", { send(byteArrayOf(0x09)) }, effectiveContentColor)
                KeyButton("&", { sendChar('&') }, effectiveContentColor)
                KeyButton(";", { sendChar(';') }, effectiveContentColor)
                KeyButton("/", { sendChar('/') }, effectiveContentColor)
                KeyButton("~", { sendChar('~') }, effectiveContentColor)
                KeyButton("%", { sendChar('%') }, effectiveContentColor)
                KeyButton("*", { sendChar('*') }, effectiveContentColor)
                KeyButton("HOME", { sendEscape("[H") }, effectiveContentColor)
                KeyButton("↑", { sendEscape("[A") }, effectiveContentColor)
                KeyButton("END", { sendEscape("[F") }, effectiveContentColor)
                KeyButton("PGUP", { sendEscape("[5~") }, effectiveContentColor)
            }
            Row(
                modifier = Modifier.padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                SpecialKeyButton("CTRL", ctrlActive, { ctrlActive = !ctrlActive }, effectiveContentColor)
                SpecialKeyButton("FN", fnActive, { fnActive = !fnActive }, effectiveContentColor)
                SpecialKeyButton("ALT", altActive, { altActive = !altActive }, effectiveContentColor)
                KeyButton("|", { sendChar('|') }, effectiveContentColor)
                KeyButton("-", { sendChar('-') }, effectiveContentColor)
                KeyButton("+", { sendChar('+') }, effectiveContentColor)
                KeyButton("\"", { sendChar('"') }, effectiveContentColor)
                KeyButton("←", { sendEscape("[D") }, effectiveContentColor)
                KeyButton("↓", { sendEscape("[B") }, effectiveContentColor)
                KeyButton("→", { sendEscape("[C") }, effectiveContentColor)
                KeyButton("PGDN", { sendEscape("[6~") }, effectiveContentColor)
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit,
    effectiveContentColor: Color
) {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = effectiveContentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun SpecialKeyButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    effectiveContentColor: Color = Color.White
) {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) Color.White else effectiveContentColor,
            maxLines = 1
        )
    }
}
