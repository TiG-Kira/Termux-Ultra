package com.termux.app.compose

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
import com.termux.app.terminal.shell.ComposeSessionManager
import com.termux.app.terminal.shell.ComposeTerminalSettings
import com.termux.app.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.extrakeys.ExtraKeyButton
import com.termux.app.terminal.shell.ComposeTerminalScreen
import com.awkoo.libterminal.engine.TerminalSession as LibTerminalSession
import com.awkoo.libterminal.view.ExtraKeysModifierSnapshot
import com.awkoo.libterminal.view.TerminalView as LibTerminalView
import com.termux.app.terminal.shell.pid
import com.termux.app.terminal.shell.sessionExited
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
    val cursorStyleName by ComposeTerminalSettings.cursorStyleName.collectAsState()
    val cursorStyle = run {
        try { com.awkoo.libterminal.engine.TerminalCursorStyle.valueOf(cursorStyleName) }
        catch (_: Throwable) { com.awkoo.libterminal.engine.TerminalCursorStyle.BAR }
    }
    val textBlinking by ComposeTerminalSettings.textBlinking.collectAsState()
    val colorScheme by ComposeTerminalSettings.colorScheme.collectAsState()
    val stylingColorScheme by ComposeTerminalSettings.stylingColorScheme.collectAsState()
    val stylingTypeface by ComposeTerminalSettings.stylingTypeface.collectAsState()
    val softKeyboardEnabled by ComposeTerminalSettings.softKeyboard.collectAsState()
    val softKeyboardOnlyIfNoHardware by ComposeTerminalSettings.softKeyboardOnlyIfNoHardware.collectAsState()
    val isKeepScreenOn by ComposeTerminalSettings.keepScreenOn.collectAsState()
    val showToolbar by ComposeTerminalSettings.showToolbar.collectAsState()

    // Styling 磁盘主题优先于内置 color_scheme（与 Java 模式共用 ~/.termux/colors.properties）
    val effectiveColorScheme = stylingColorScheme ?: colorScheme

    // 修饰键状态提升到这里，供工具栏写入、终端视图读取（extraKeysModifierReader）。
    val extraKeysModifiers = remember { ExtraKeysModifierState() }

    var isCompact by remember { mutableStateOf(false) }
    var isTopBarTransitioning by remember { mutableStateOf(false) }
    var isTopBarCollapsed by remember { mutableStateOf(false) }
    var showLargeContent by remember { mutableStateOf(true) }
    var topBarSlideProgress by remember { mutableFloatStateOf(0f) }
    var smallTitleAlpha by remember { mutableFloatStateOf(0f) }
    var useLargeButtons by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastInteractionFromTopBar by remember { mutableStateOf(false) }
    var sessionKey by remember { mutableIntStateOf(0) }
    var showNewSessionLabel by remember { mutableStateOf(false) }
    var sessionLabelTimer by remember { mutableStateOf(0L) }

    var showContextMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var showSessionList by remember { mutableStateOf(false) }
    var showQuickCommandSheet by remember { mutableStateOf(false) }

    val terminalViewRef = remember { mutableStateOf<LibTerminalView?>(null) }

    val terminalActive = !(showSessionList || showContextMenu || showRenameDialog)
    LaunchedEffect(terminalActive) {
        if (!terminalActive) {
            terminalViewRef.value?.hideIme()
            terminalViewRef.value?.clearFocus()
        }
    }
    LaunchedEffect(allSessions.size) {
        if (allSessions.isNotEmpty() && terminalActive) {
            terminalViewRef.value?.toggleIme(true)
        }
    }

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

    // 死会话内按 Enter → 移除该会话；若无剩余会话则返回，否则已切换到其余会话
    LaunchedEffect(removeRequested) {
        if (removeRequested) {
            sessionManager.killSession(currentSession.id)
            if (sessionManager.sessions.value.isEmpty()) {
                onBack()
            }
        }
    }

    // 当前会话切换后（会话列表点击 / 主页卡片点击 / 第三方句柄切换），未初始化的会话
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

    fun updateInteractionTime(fromTopBar: Boolean = true) {
        lastInteractionTime = System.currentTimeMillis()
        lastInteractionFromTopBar = fromTopBar
    }

    fun markOutsideInteraction() {
        lastInteractionTime = System.currentTimeMillis()
        lastInteractionFromTopBar = false
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

    /** 长按快捷命令面板不受软键盘禁用设置影响，短按需先过设置约束。 */
    fun toggleKeyboardRespectingSettings() {
        if (!softKeyboardEnabled) {
            showSnack(context.getString(R.string.soft_keyboard_disabled_by_settings))
            return
        }
        if (softKeyboardOnlyIfNoHardware && hasHardwareKeyboard(context)) {
            showSnack(context.getString(R.string.soft_keyboard_disabled_by_hardware))
            return
        }
        toggleKeyboard()
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
            lastInteractionFromTopBar = true
            coroutineScope.launch {
                try {
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
                } finally {
                    // 协程被取消时落到完整展开态，避免停在「已收起颜色 + 未收起底色」的白条中间态
                    isCompact = false
                    showLargeContent = true
                    topBarSlideProgress = 0f
                    isTopBarCollapsed = false
                    smallTitleAlpha = 0f
                    useLargeButtons = true
                    isTopBarTransitioning = false
                }
            }
        } else {
            showLargeContent = true
            showNewSessionLabel = false
            sessionKey++
            lastInteractionTime = System.currentTimeMillis()
            lastInteractionFromTopBar = true
        }
    }

    fun collapseTopBarAnimated() {
        if (!isCompact && !isTopBarTransitioning) {
            isTopBarTransitioning = true
            useLargeButtons = true
            showNewSessionLabel = false
            coroutineScope.launch {
                try {
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
                } finally {
                    // 协程被取消时落到完整收缩态，避免顶栏停在实体白底 + 白图标的中间态
                    isCompact = true
                    showLargeContent = false
                    topBarSlideProgress = 1f
                    useLargeButtons = false
                    isTopBarTransitioning = false
                }
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
        lastInteractionFromTopBar = true
    }

    // 状态栏颜色适配（照搬 Java 版 L286-301）
    LaunchedEffect(isCompact, topBarOpaqueBg, isTerminalDark) {
        val act = context as? android.app.Activity
        if (act != null) {
            val window = act.window
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            if (!isCompact) {
                // TopAppBar 模式：状态栏同样透明，底色由顶栏 Column 背景绘制。
                // 若锁成 opaque 窗口色，对话框暗色遮罩只会压暗应用内容，
                // 状态栏一条纯白漏在外面（浅色主题下尤其明显）。
                window.statusBarColor = android.graphics.Color.TRANSPARENT
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
            if (lastInteractionFromTopBar || showSessionList || showContextMenu || showRenameDialog) {
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
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = topBarIndication,
                    onClick = { updateInteractionTime(); toggleKeyboardRespectingSettings() },
                    onLongClick = {
                        updateInteractionTime()
                        showQuickCommandSheet = true
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_keyboard),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = effectiveTopBarContentColor
            )
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
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = topBarIndication,
                    onClick = { updateInteractionTime(); toggleKeyboardRespectingSettings() },
                    onLongClick = {
                        updateInteractionTime()
                        showQuickCommandSheet = true
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_keyboard),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = effectiveTopBarContentColor
            )
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
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
                // 收缩态用终端实际背景色：顶栏透明到底会让窗口默认底色（浅色主题为白）
                // 从透明处漏出，白色图标又叠在白底上导致整条顶栏看不见
                val topBarColor by animateColorAsState(
                    targetValue = if (isCompact) terminalBgColor else topBarOpaqueBg,
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    label = "topBarBg"
                )
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TerminalKeyboardToolbar(
                            onSendKey = { bytes -> currentSession.write(bytes) },
                            effectiveContentColor = MiuixTheme.colorScheme.onSurface,
                            modifiers = extraKeysModifiers,
                            onToggleKeyboard = { toggleKeyboardRespectingSettings() }
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            markOutsideInteraction()
                        }
                    }
            ) {
                ComposeTerminalScreen(
                    session = currentSession,
                    modifier = Modifier.fillMaxSize(),
                    terminalViewRef = terminalViewRef,
                    useLightTheme = false,
                    textSize = textSize,
                    cursorBlink = cursorBlink,
                    cursorStyle = cursorStyle,
                    textBlinking = textBlinking,
                    colorScheme = effectiveColorScheme,
                    typeface = stylingTypeface,
                    extraKeysModifierReader = {
                        // libterminal 为输入法/虚拟键盘来源的输入读取该快照（走 inputCodePoint）。
                        // 读一次即消费粘滞态，于是粘滞的 CTRL/ALT 只作用于紧随其后的那一次输入；
                        // 代价是若这一次输入最终没产出字节（如只按了 BACK），粘滞态会白丢一次，可接受。
                        val snapshot = ExtraKeysModifierSnapshot(
                            extraKeysModifiers.ctrl,
                            extraKeysModifiers.alt,
                            false,
                            extraKeysModifiers.fn
                        )
                        extraKeysModifiers.clearSticky()
                        snapshot
                    }
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
                                        tint = if (currentSession.isRunning.value) Color(0xFFFF5252)
                                        else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                },
                                text = if (currentSession.isRunning.value) context.getString(R.string.kill_process_pid, currentSession.pid) else context.getString(R.string.process_not_running),
                                enabled = currentSession.isRunning.value,
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
                                onClick = { showContextMenu = false; toggleKeyboardRespectingSettings() }
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

            // 挂载风险确认宿主：收集VorteX Guard Engine Snackbar 事件（仅提示/完全拦截），与 Java 版控制台行为一致
            RiskConfirmDialogHost(snackbarHostState)

            // 快捷指令 BottomSheet：长按 TopBar 键盘按钮触发
            QuickCommandSheet(
                show = showQuickCommandSheet,
                onDismiss = { showQuickCommandSheet = false },
                onExecuteCommand = { cmd ->
                    executeQuickCommand(context, cmd)
                }
            )
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

private sealed class ToolbarKey {
    class Simple(val display: String, val onSend: () -> Unit) : ToolbarKey()
    class ModifierKey(
        val label: String,
        val isSticky: () -> Boolean,
        val isLocked: () -> Boolean,
        val onTap: () -> Unit,
        val onLongPress: () -> Unit
    ) : ToolbarKey()
}

/**
 * 工具栏修饰键状态。提升到工具栏之外是因为它不只服务于工具栏自己的按键：
 * 终端的 extraKeysModifierReader 也要读它，粘滞/锁定的 CTRL/ALT 才能作用于输入法输入。
 * ctrl/alt/fn 是「粘滞或锁定」的合成结果，即当前真正生效的修饰态。
 */
private class ExtraKeysModifierState {
    var ctrlSticky by mutableStateOf(false)
    var ctrlLocked by mutableStateOf(false)
    var altSticky by mutableStateOf(false)
    var altLocked by mutableStateOf(false)
    var fnSticky by mutableStateOf(false)
    var fnLocked by mutableStateOf(false)

    val ctrl: Boolean get() = ctrlSticky || ctrlLocked
    val alt: Boolean get() = altSticky || altLocked
    val fn: Boolean get() = fnSticky || fnLocked

    fun clearSticky() {
        ctrlSticky = false
        altSticky = false
        fnSticky = false
    }
}

private fun escSeq(seq: String) = byteArrayOf(0x1B) + seq.toByteArray(Charsets.UTF_8)

// extra-keys 命名键 → 字节。escape 序列必须与 KeyHandler.getCode() 一致，
// 否则方向键 / F 键在应用模式下的终端里会错位。
private val EXTRA_KEY_BYTES: Map<String, ByteArray> = mapOf(
    "SPACE" to byteArrayOf(0x20),
    "ESC" to byteArrayOf(0x1B),
    "TAB" to byteArrayOf(0x09),
    "BKSP" to byteArrayOf(0x7F),
    "ENTER" to byteArrayOf(0x0D),
    "HOME" to escSeq("[H"),
    "END" to escSeq("[F"),
    "UP" to escSeq("[A"),
    "DOWN" to escSeq("[B"),
    "LEFT" to escSeq("[D"),
    "RIGHT" to escSeq("[C"),
    "INS" to escSeq("[2~"),
    "DEL" to escSeq("[3~"),
    "PGUP" to escSeq("[5~"),
    "PGDN" to escSeq("[6~"),
    "F1" to escSeq("OP"),
    "F2" to escSeq("OQ"),
    "F3" to escSeq("OR"),
    "F4" to escSeq("OS"),
    "F5" to escSeq("[15~"),
    "F6" to escSeq("[17~"),
    "F7" to escSeq("[18~"),
    "F8" to escSeq("[19~"),
    "F9" to escSeq("[20~"),
    "F10" to escSeq("[21~"),
    "F11" to escSeq("[23~"),
    "F12" to escSeq("[24~")
)

@Composable
private fun TerminalKeyboardToolbar(
    onSendKey: (ByteArray) -> Unit,
    effectiveContentColor: Color,
    modifiers: ExtraKeysModifierState,
    onToggleKeyboard: () -> Unit = {}
) {
    // rows 被 remember(useCustom) 缓存，里面的闭包会一直持有首次组合时的回调。
    // 会话切换是原地替换（useCustom 不变），不取最新值就会把按键发给旧会话。
    val currentSendKey by rememberUpdatedState(onSendKey)
    val currentToggleKeyboard by rememberUpdatedState(onToggleKeyboard)

    val surfaceBg = MiuixTheme.colorScheme.surface.copy(alpha = 0.95f)
    val context = LocalContext.current

    // 点按修饰键 = 粘滞：仅作用于下一个按键，发送后自动复位；
    // 长按修饰键 = 锁定：持续生效，直到再次长按解除。
    // 旧实现只用点击切换、且每次发送都清空全部修饰态，导致组合键互相打架、长按形同虚设。
    fun send(bytes: ByteArray) {
        currentSendKey(bytes)
        modifiers.clearSticky()
    }

    fun charBytes(c: Char, ctrl: Boolean, alt: Boolean): ByteArray = when {
        ctrl && alt -> byteArrayOf(0x1B, (c.lowercaseChar().code and 0x1F).toByte())
        ctrl -> byteArrayOf((c.lowercaseChar().code and 0x1F).toByte())
        alt -> byteArrayOf(0x1B, c.code.toByte())
        else -> byteArrayOf(c.code.toByte())
    }

    fun sendChar(c: Char) = send(charBytes(c, modifiers.ctrl, modifiers.alt))

    fun sendEscape(seq: String) {
        send(escSeq(seq))
    }

    // extra-keys 里的一个 token（命名键或字面量）+ 修饰态 → 待发送字节。
    // 命名键本身已是完整 escape 序列，只给单字节控制键补 ALT 的 ESC 前缀
    // （ALT+BKSP → ESC 0x7F、ALT+ENTER → ESC CR，与 KeyHandler.getCode() 一致）；
    // 给方向键再套一层 ESC 只会得到非 xterm 序列，故不加。
    fun tokenBytes(token: String, ctrl: Boolean, alt: Boolean): ByteArray {
        EXTRA_KEY_BYTES[token]?.let { bytes ->
            return if (alt && bytes.size == 1) byteArrayOf(0x1B) + bytes else bytes
        }
        if (token.length == 1) return charBytes(token[0], ctrl, alt)
        // 多字符字面量原样发送：对字符串套 CTRL 位运算只会产出垃圾字节
        return token.toByteArray(Charsets.UTF_8)
    }

    fun sendKeyName(key: String) {
        send(tokenBytes(key, modifiers.ctrl, modifiers.alt))
    }

    // 宏：空格分隔的 token 依次发送，CTRL/ALT 只作用于紧随其后的单个 token。
    fun sendMacro(rawTokens: List<String>) {
        val tokens = rawTokens.filter { it.isNotBlank() }
        var pendingCtrl = false
        var pendingAlt = false
        for (tok in tokens) {
            when (tok) {
                "CTRL" -> pendingCtrl = true
                "ALT" -> pendingAlt = true
                // FN/SHIFT 只切换 extra-keys 的显示行，Compose 工具栏没有对应行为
                "FN", "SHIFT" -> Unit
                else -> {
                    send(tokenBytes(tok, pendingCtrl, pendingAlt))
                    pendingCtrl = false
                    pendingAlt = false
                }
            }
        }
    }

    fun modifierKey(
        label: String,
        isSticky: () -> Boolean,
        isLocked: () -> Boolean,
        setSticky: (Boolean) -> Unit,
        setLocked: (Boolean) -> Unit
    ) = ToolbarKey.ModifierKey(
        label = label,
        isSticky = isSticky,
        isLocked = isLocked,
        // 已锁定时点按不再改动粘滞态，避免「锁定 + 粘滞」的无意义叠加。
        onTap = { if (!isLocked()) setSticky(!isSticky()) },
        onLongPress = { setLocked(!isLocked()) }
    )

    fun ctrlKey() = modifierKey("CTRL", { modifiers.ctrlSticky }, { modifiers.ctrlLocked }, { modifiers.ctrlSticky = it }, { modifiers.ctrlLocked = it })
    fun altKey() = modifierKey("ALT", { modifiers.altSticky }, { modifiers.altLocked }, { modifiers.altSticky = it }, { modifiers.altLocked = it })
    fun fnKey() = modifierKey("FN", { modifiers.fnSticky }, { modifiers.fnLocked }, { modifiers.fnSticky = it }, { modifiers.fnLocked = it })

    // 内置默认布局。第二行刻意是 ↑ 在 } 位、} 在 HOME 位、HOME 在 ↑ 位，
    // 看着像错位，但是用户指定的顺序，不要顺手「修正」。
    fun buildDefaultLayout(): List<List<ToolbarKey>> = listOf(
        listOf(
            ToolbarKey.Simple("ESC") { send(byteArrayOf(0x1B)) },
            ToolbarKey.Simple("<") { sendChar('<') },
            ToolbarKey.Simple(">") { sendChar('>') },
            ToolbarKey.Simple("\\") { sendChar('\\') },
            ToolbarKey.Simple("=") { sendChar('=') },
            ToolbarKey.Simple("^") { sendChar('^') },
            ToolbarKey.Simple("$") { sendChar('$') },
            ToolbarKey.Simple("(") { sendChar('(') },
            ToolbarKey.Simple(")") { sendChar(')') },
            ToolbarKey.Simple("[") { sendChar('[') },
            ToolbarKey.Simple("]") { sendChar(']') },
            ToolbarKey.Simple("⌫") { send(byteArrayOf(0x7F)) }
        ),
        listOf(
            ToolbarKey.Simple("⇥") { send(byteArrayOf(0x09)) },
            ToolbarKey.Simple("&") { sendChar('&') },
            ToolbarKey.Simple(";") { sendChar(';') },
            ToolbarKey.Simple("/") { sendChar('/') },
            ToolbarKey.Simple("~") { sendChar('~') },
            ToolbarKey.Simple("%") { sendChar('%') },
            ToolbarKey.Simple("*") { sendChar('*') },
            ToolbarKey.Simple("{") { sendChar('{') },
            ToolbarKey.Simple("↑") { sendEscape("[A") },
            ToolbarKey.Simple("}") { sendChar('}') },
            ToolbarKey.Simple("HOME") { sendEscape("[H") },
            ToolbarKey.Simple("END") { sendEscape("[F") }
        ),
        listOf(
            ctrlKey(),
            fnKey(),
            altKey(),
            ToolbarKey.Simple("|") { sendChar('|') },
            ToolbarKey.Simple("-") { sendChar('-') },
            ToolbarKey.Simple("+") { sendChar('+') },
            ToolbarKey.Simple("\"") { sendChar('"') },
            ToolbarKey.Simple("←") { sendEscape("[D") },
            ToolbarKey.Simple("↓") { sendEscape("[B") },
            ToolbarKey.Simple("→") { sendEscape("[C") },
            ToolbarKey.Simple("PGUP") { sendEscape("[5~") },
            ToolbarKey.Simple("PGDN") { sendEscape("[6~") }
        )
    )

    // Termux 的 PASTE 键：把剪贴板文本写入终端，并消费掉粘滞修饰态，
    // 否则粘滞的 CTRL/ALT 会污染粘贴之后的下一个按键。
    fun sendClipboard() {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
        if (text.isNullOrEmpty()) return
        onSendKey(text.toByteArray(Charsets.UTF_8))
        modifiers.clearSticky()
    }

    // 从 Termux 键盘布局配置文件（termux.properties 的 extra-keys）读取布局。
    fun mapButton(btn: ExtraKeyButton): ToolbarKey {
        val key = btn.getKey()
        val display = btn.getDisplay()
        if (!btn.isMacro()) {
            return when (key) {
                "CTRL" -> ctrlKey()
                "ALT" -> altKey()
                "FN" -> fnKey()
                // DRAWER/SCROLL/SHIFT 是 Termux 自身界面的动作（抽屉、滚动模式、切换
                // extra-keys 显示行），Compose 工具栏没有对应物：保留按键位还原配置出的
                // 布局形状，但不发送任何字节——否则会把这些名字当字面量打进终端。
                "DRAWER", "SCROLL", "SHIFT" -> ToolbarKey.Simple(display) { }
                "KEYBOARD" -> ToolbarKey.Simple(display) { currentToggleKeyboard() }
                "PASTE" -> ToolbarKey.Simple(display) { sendClipboard() }
                else -> ToolbarKey.Simple(display) { sendKeyName(key) }
            }
        } else {
            return ToolbarKey.Simple(display) { sendMacro(key.split(" ")) }
        }
    }

    fun buildCustomLayout(matrix: Array<Array<ExtraKeyButton>>): List<List<ToolbarKey>> {
        return matrix.map { row -> row.map { btn -> mapButton(btn) } }
    }

    val useCustom by com.termux.app.terminal.shell.ComposeTerminalSettings.useCustomKeyboardLayout.collectAsState()

    val rows: List<List<ToolbarKey>> = remember(useCustom) {
        if (useCustom) {
            // 读取 Termux 键盘布局配置文件（~/.termux/termux.properties 的 extra-keys）。
            // 不能走静态 TermuxAppSharedProperties.getProperties()：全仓库没有任何地方调用它的
            // init()，该单例恒为 null，会静默退化成内置默认布局。这里自建 app 侧实例并显式
            // 从磁盘加载，拿到的是与经典终端完全一致的解析结果（未配置时即 Termux 内置默认布局）。
            // 单文件同步读取，与 app 启动路径一致，不值得为此引入异步状态。
            val matrix = try {
                TermuxAppSharedProperties(context)
                    .apply { loadTermuxPropertiesFromDisk() }
                    .getExtraKeysInfo()
                    ?.getMatrix()
            } catch (e: Exception) {
                android.util.Log.w("TerminalKeyboardToolbar", "读取自定义键盘布局失败，回退内置默认布局", e)
                null
            }
            if (matrix != null) buildCustomLayout(matrix) else buildDefaultLayout()
        } else {
            buildDefaultLayout()
        }
    }

    val hScroll = rememberScrollState()

    // 横向滚动放在最外层统一处理：滚动范围由最宽的一行决定，三行保持同步滚动且都能滚到最右。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceBg)
            .navigationBarsPadding()
            .horizontalScroll(hScroll)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (row in rows) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    for (key in row) {
                        when (key) {
                            is ToolbarKey.Simple -> KeyButton(key.display, key.onSend, effectiveContentColor)
                            is ToolbarKey.ModifierKey -> SpecialKeyButton(
                                label = key.label,
                                sticky = key.isSticky(),
                                locked = key.isLocked(),
                                onTap = key.onTap,
                                onLongPress = key.onLongPress,
                                effectiveContentColor = effectiveContentColor
                            )
                        }
                    }
                }
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
    sticky: Boolean,
    locked: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    effectiveContentColor: Color = Color.White
) {
    // 三态：未激活 / 粘滞（半透明，只作用于下一个按键）/ 锁定（实心 + 描边，持续生效到再次长按）。
    val background = when {
        locked -> MiuixTheme.colorScheme.primary
        sticky -> MiuixTheme.colorScheme.primary.copy(alpha = 0.4f)
        else -> MiuixTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(
                if (locked) Modifier.border(2.dp, MiuixTheme.colorScheme.onSurface, RoundedCornerShape(8.dp))
                else Modifier
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (locked) MiuixTheme.colorScheme.onPrimary else effectiveContentColor,
            maxLines = 1
        )
    }
}

private fun hasHardwareKeyboard(context: android.content.Context): Boolean {
    return context.resources.configuration.keyboard !=
        android.content.res.Configuration.KEYBOARD_NOKEYS
}
