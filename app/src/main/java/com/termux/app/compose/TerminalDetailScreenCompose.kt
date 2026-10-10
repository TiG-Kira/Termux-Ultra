package com.termux.app.compose

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
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
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowRight
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
import androidx.compose.ui.unit.Dp
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
import com.termux.app.vortex.VorteXSandbox
import android.widget.Toast
import kotlinx.coroutines.coroutineScope
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
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
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

    // 2. 大 TopAppBar 背景 → 默认沿用系统亮暗主题的固定 opaque 色；
    //    开了动态取色就换成 Miuix 实际渲染的 surface，否则顶栏永远是纯黑白，动态色根本看不出来。
    val isSystemDarkTheme = isSystemInDarkTheme()
    AppThemePrefs.init(context)
    val materialYouEnabled by AppThemePrefs.materialYouEnabled.collectAsState()
    val useMonetTopBar = materialYouEnabled && ApiCompat.isFeatureUsable(context, ApiCompat.Feature.MIUIX_DYNAMIC_COLOR)
    val topBarOpaqueBg = when {
        useMonetTopBar -> MiuixTheme.colorScheme.surface
        isSystemDarkTheme -> Color(0xFF1C1B1F)
        else -> Color(0xFFFFFFFF)
    }

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

    /**
     * 进入 VorteX 沙箱会话。
     * - 总开关关闭时给出提示并直接返回；
     * - 限制同时仅允许手动启动一个沙箱会话：若已存在「沙箱会话」则切换到它，否则新建。
     */
    fun addSandboxSession() {
        val ctx = context
        if (!VorteXSandbox.isEnabled(ctx)) {
            Toast.makeText(ctx, ctx.getString(R.string.vortex_sandbox_disabled_toast), Toast.LENGTH_SHORT).show()
            return
        }
        val title = ctx.getString(R.string.vortex_sandbox_session_title)
        val existing = sessionManager.sessions.value.firstOrNull { it.name == title }
        if (existing != null) {
            sessionManager.switchTo(existing.session.id)
        } else {
            val newSession = sessionManager.createSandboxSession(startImmediately = true)
            sessionManager.switchTo(newSession.id)
            VorteXSandbox.setActiveManualSessionId(newSession.id)
        }
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

    // 键盘底色深浅。导航栏透明后，桌布透出来的是键盘底（工具栏展开时），
    // 图标明暗得跟着键盘走，跟状态栏看 topBarOpaqueBg 是同一个道理。
    val keyboardSurfaceIsLight = MiuixTheme.colorScheme.surface.luminance() > 0.5f

    // 状态栏颜色适配（照搬 Java 版 L286-301）
    LaunchedEffect(isCompact, topBarOpaqueBg, isTerminalDark, showToolbar, keyboardSurfaceIsLight) {
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
            // 导航栏颜色由 TermuxActivity 在 onCreate 里设（首帧即生效，避免闪一下不透明）。
            // 这里只调图标明暗：工具栏展开时该区域透出键盘底色，收起时透出终端底色。
            controller.isAppearanceLightNavigationBars =
                if (showToolbar) keyboardSurfaceIsLight else !isTerminalDark
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

    /**
     * 顶栏沙箱入口按钮：位于「加号」左侧。总开关关闭时图标置灰（仍可点击给出提示）。
     */
    @Composable
    fun SandboxTopBarButton(
        collapsed: Boolean,
        onClick: () -> Unit
    ) {
        val enabled = VorteXSandbox.isEnabled(context)
        val tint = if (enabled) effectiveTopBarContentColor
        else effectiveTopBarContentColor.copy(alpha = 0.35f)
        val glyph: @Composable () -> Unit = {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = context.getString(R.string.vortex_sandbox_title),
                modifier = Modifier.size(24.dp),
                tint = tint
            )
        }
        if (collapsed) {
            GlassIconButton(onClick = onClick, size = 48.dp, padding = 0.dp) { glyph() }
        } else {
            IconButton(onClick = onClick, minWidth = 48.dp, minHeight = 48.dp) { glyph() }
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
                modifier = Modifier.size(24.dp),
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
                modifier = Modifier.size(24.dp),
                tint = effectiveTopBarContentColor
            )
        }
        SandboxTopBarButton(
            collapsed = isCompact,
            onClick = { updateInteractionTime(); addSandboxSession() }
        )
        OverlayIconDropdownMenu(
            entry = addSessionEntry,
            backgroundColor = Color.Transparent,
            minWidth = 40.dp,
            minHeight = 40.dp
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = effectiveTopBarContentColor
            )
        }
        IconButton(
            onClick = {
                updateInteractionTime()
                closeCurrentSession()
            },
            enabled = !currentSessionIsDead,
            minWidth = 40.dp,
            minHeight = 40.dp
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (currentSessionIsDead)
                    effectiveTopBarContentColor.copy(alpha = 0.3f)
                else effectiveTopBarContentColor
            )
        }
        IconButton(onClick = { updateInteractionTime(); showContextMenu = true }, minWidth = 40.dp, minHeight = 40.dp) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
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
                modifier = Modifier.size(24.dp),
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
                modifier = Modifier.size(24.dp),
                tint = effectiveTopBarContentColor
            )
        }
        SandboxTopBarButton(
            collapsed = isCompact,
            onClick = { updateInteractionTime(); addSandboxSession() }
        )
        OverlayIconDropdownMenu(
            entry = addSessionEntry,
            backgroundColor = Color.Transparent,
            minWidth = 40.dp,
            minHeight = 40.dp
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = effectiveTopBarContentColor
            )
        }
        IconButton(
            onClick = {
                updateInteractionTime()
                closeCurrentSession()
            },
            enabled = !currentSessionIsDead,
            minWidth = 40.dp,
            minHeight = 40.dp
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (currentSessionIsDead)
                    effectiveTopBarContentColor.copy(alpha = 0.3f)
                else effectiveTopBarContentColor
            )
        }
        IconButton(onClick = { updateInteractionTime(); showContextMenu = true }, minWidth = 40.dp, minHeight = 40.dp) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = effectiveTopBarContentColor
            )
        }
    }

    /**
     * 顶栏左右两端的键（返回、收缩/展开）。收缩态顶栏只剩这条 56dp 的行压在终端背景上，
     * 图标得自带玻璃底板 + 阴影，否则会糊进终端内容；展开态顶栏是完整 TopAppBar、自带
     * 不透明底色，用普通 IconButton 即可。
     *
     * 两态必须占同样大的盒子，否则图标会随展开/收缩左右跳：IconButton 默认只给 40dp
     * 触碰盒，玻璃底板是 48dp。所以展开态显式把 minWidth/minHeight 提到 48dp，玻璃态
     * 保持 size = 48dp、padding = 0，两者只差画在底板上的那层外观。
     */
    @Composable
    fun TopBarLeafIcon(
        collapsed: Boolean,
        onClick: () -> Unit,
        glyph: @Composable () -> Unit
    ) {
        if (collapsed) {
            GlassIconButton(onClick = onClick, size = 48.dp, padding = 0.dp) {
                glyph()
            }
        } else {
            // 展开态也要占 48dp，和玻璃态一致；IconButton 默认只有 40dp，图标会跟着态切换左右跳 4dp。
            IconButton(onClick = onClick, minWidth = 48.dp, minHeight = 48.dp) { glyph() }
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
                TopBarLeafIcon(
                    collapsed = isCompact,
                    onClick = { updateInteractionTime(); onBack() }
                ) {
                    Icon(
                        imageVector = MiuixGlassIcons.ChevronBackward,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (isCompact)
                            MiuixTheme.colorScheme.onSurface
                        else effectiveTopBarContentColor
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
                TopBarLeafIcon(
                    collapsed = isCompact,
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
                        // 左侧返回键是 chevron（描边 V 形），折叠把手必须换成实心三角才不撞形。
                        // 注意 KeyboardArrow 家族连 Filled 变体也是 chevron，真正的实心楔形是
                        // ArrowLeft/ArrowRight（M10 17l5-5-5-5v10z）。楔形 @24dp 墨迹仅 25dp²，
                        // 取 32dp 后约 44dp²，与返回 chevron 的 ~46dp² 视觉重量相当。
                        imageVector = if (isTopBarCollapsed) Icons.AutoMirrored.Filled.ArrowRight else Icons.AutoMirrored.Filled.ArrowLeft,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = if (isCompact)
                            MiuixTheme.colorScheme.onSurface
                        else effectiveTopBarContentColor
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
                            onSendNamedKey = { keyCode, ctrl, alt ->
                                terminalViewRef.value?.sendKeyEvent(keyCode, ctrl, alt)
                            },
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
    class Simple(val display: String, val repeats: Boolean = false, val onSend: () -> Unit) : ToolbarKey()
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

/**
 * 长按可连发的键，与原版 Termux 的 ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS 保持一致。
 * 只列 extra-keys 的规范键名：内置默认布局里的字面量键（如 `\`）长按连发会连打字符，
 * 不是用户预期，普通可打印字符一律不连发。
 */
private val REPETITIVE_KEY_NAMES = setOf(
    "UP", "DOWN", "LEFT", "RIGHT", "BKSP", "DEL", "PGUP", "PGDN"
)

/** 单个按键的最小宽度：低于此值等分出来的键点不中，改用横向滚动。 */
private val MIN_KEY_WIDTH = 28.dp

/**
 * 小键盘按键标签的字号策略：优先 11.sp，放不下就自动缩小字号，而不是截断成省略号。
 *
 * 按键宽高都是固定值（宽由 [BoxWithConstraints] 等分，高 32.dp），大字号或 CJK 标签
 * （自定义 extra-keys 布局里很常见）会超出按钮边界。11.sp 是原来的固定字号，作为上限
 * 保证正常标签的渲染与改动前完全一致；下限 6.sp 防止极小键宽下字号塌到不可读。
 *
 * 键高固定 32.dp，正常字号（≤11.sp）行高远小于键高，实际只有水平方向会触顶收缩；
 * 极端情况下（系统字体缩放很大）才会连带垂直收缩，同样是"保证显示完整"的预期行为。
 */
private val KeyboardLabelAutoSize = TextAutoSize.StepBased(
    minFontSize = 6.sp,
    maxFontSize = 11.sp,
    stepSize = 0.5.sp
)

/**
 * 把 keyCode + 修饰态交给 libterminal 编码并写入当前会话。
 *
 * 走引擎的 TerminalView.onKeyDown 而不是自己拼字节：KeyInputProcessor 会读
 * KeyEvent 的 metaState 取 ctrl/alt，再叠加 extraKeysModifierReader 快照，
 * 最后由 KeySequenceEncoder 按终端当前 cursorApp 模式生成序列。
 *
 * 每次都同时派发 DOWN + UP：引擎的 onKeyDown 不检查 repeatCount，也没有按键按下状态表，
 * 只处理单次事件，长按连发时每次循环都发一整对 DOWN/UP 才是它的预期用法。
 */
private fun LibTerminalView.sendKeyEvent(keyCode: Int, ctrl: Boolean, alt: Boolean) {
    var metaState = 0
    if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    if (alt) metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    val now = android.os.SystemClock.uptimeMillis()
    onKeyDown(keyCode, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
    onKeyUp(keyCode, KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
}

/** 长按多久后开始连发，接近系统 ViewConfiguration.getLongPressTimeout() 的量级。 */
private const val REPEAT_START_DELAY = 400L

/** 连发间隔，对齐原版 ExtraKeysView 的 DEFAULT_LONG_PRESS_REPEAT_DELAY。 */
private const val REPEAT_INTERVAL = 80L

/**
 * extra-keys 规范键名 → Android keyCode。
 *
 * 这些键不再自己拼 escape 序列，而是构造 KeyEvent 交给 libterminal 的
 * TerminalView.onKeyDown，由引擎的 KeySequenceEncoder 统一编码。原来在这里硬编码
 * 序列有两个实打实的 bug：
 *  1. 终端处于 application cursor mode（DECCKM，vim/less/tmux 会开）时，引擎按
 *     buildCursor 发 `\eOH`/`\eOF`/`\eOA`，而硬编码只发 `\e[H`/`\e[F`/`\e[A`，按键无响应；
 *  2. 修饰键组合（CTRL+HOME 应发 `\e[1;5H`）完全没被处理。
 * 走引擎后这两种情况都由 KeySequenceEncoder 按终端当前模式与 keyMode 正确生成。
 */
private val EXTRA_KEY_TO_KEY_CODE: Map<String, Int> = mapOf(
    "SPACE" to KeyEvent.KEYCODE_SPACE,
    "ESC" to KeyEvent.KEYCODE_ESCAPE,
    "TAB" to KeyEvent.KEYCODE_TAB,
    "BKSP" to KeyEvent.KEYCODE_DEL,
    "ENTER" to KeyEvent.KEYCODE_ENTER,
    "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
    "END" to KeyEvent.KEYCODE_MOVE_END,
    "UP" to KeyEvent.KEYCODE_DPAD_UP,
    "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
    "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
    "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "INS" to KeyEvent.KEYCODE_INSERT,
    "DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
    "PGUP" to KeyEvent.KEYCODE_PAGE_UP,
    "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
    "F1" to KeyEvent.KEYCODE_F1,
    "F2" to KeyEvent.KEYCODE_F2,
    "F3" to KeyEvent.KEYCODE_F3,
    "F4" to KeyEvent.KEYCODE_F4,
    "F5" to KeyEvent.KEYCODE_F5,
    "F6" to KeyEvent.KEYCODE_F6,
    "F7" to KeyEvent.KEYCODE_F7,
    "F8" to KeyEvent.KEYCODE_F8,
    "F9" to KeyEvent.KEYCODE_F9,
    "F10" to KeyEvent.KEYCODE_F10,
    "F11" to KeyEvent.KEYCODE_F11,
    "F12" to KeyEvent.KEYCODE_F12
)

@Composable
private fun TerminalKeyboardToolbar(
    onSendKey: (ByteArray) -> Unit,
    effectiveContentColor: Color,
    modifiers: ExtraKeysModifierState,
    onSendNamedKey: (keyCode: Int, ctrl: Boolean, alt: Boolean) -> Unit = { _, _, _ -> },
    onToggleKeyboard: () -> Unit = {}
) {
    // rows 被 remember(useCustom) 缓存，里面的闭包会一直持有首次组合时的回调。
    // 会话切换是原地替换（useCustom 不变），不取最新值就会把按键发给旧会话。
    val currentSendKey by rememberUpdatedState(onSendKey)
    val currentSendNamedKey by rememberUpdatedState(onSendNamedKey)
    val currentToggleKeyboard by rememberUpdatedState(onToggleKeyboard)

    val surfaceBg = MiuixTheme.colorScheme.surface.copy(alpha = 0.95f)
    val context = LocalContext.current

    // 点按修饰键 = 粘滞：仅作用于下一个按键，发送后自动复位；
    // 长按修饰键 = 锁定：持续生效，直到再次长按解除。
    // 旧实现只用点击切换、且每次发送都清空全部修饰态，导致组合键互相打架、长按形同虚设。
    fun send(bytes: ByteArray?) {
        // 命名键由引擎编码后自行写入会话，没有字节可发；此时只消费粘滞态。
        if (bytes != null) currentSendKey(bytes)
        modifiers.clearSticky()
    }

    fun charBytes(c: Char, ctrl: Boolean, alt: Boolean): ByteArray = when {
        ctrl && alt -> byteArrayOf(0x1B, (c.lowercaseChar().code and 0x1F).toByte())
        ctrl -> byteArrayOf((c.lowercaseChar().code and 0x1F).toByte())
        alt -> byteArrayOf(0x1B, c.code.toByte())
        else -> byteArrayOf(c.code.toByte())
    }

    fun sendChar(c: Char) = send(charBytes(c, modifiers.ctrl, modifiers.alt))

    // 命名键交给引擎编码：引擎会按终端当前的 cursorApp 模式与 keyMode 生成序列，
    // 工具栏自己拼的固定序列在 application cursor mode 下会失效（见 EXTRA_KEY_TO_KEY_CODE）。
    fun dispatchNamedKey(keyCode: Int, ctrl: Boolean, alt: Boolean) {
        currentSendNamedKey(keyCode, ctrl, alt)
        modifiers.clearSticky()
    }

    // extra-keys 里的一个 token（命名键或字面量）+ 修饰态 → 待发送字节。
    // 命名键（方向键 / HOME / END / F 键…）不在这里编码，交给引擎：见 EXTRA_KEY_TO_KEY_CODE
    // 的注释。字面量键仍自行编码，单字符走 charBytes，多字符原样发送——
    // 对字符串套 CTRL 位运算只会产出垃圾字节。
    fun tokenBytes(token: String, ctrl: Boolean, alt: Boolean): ByteArray? {
        val keyCode = EXTRA_KEY_TO_KEY_CODE[token]
        if (keyCode != null) {
            dispatchNamedKey(keyCode, ctrl, alt)
            return null
        }
        if (token.length == 1) return charBytes(token[0], ctrl, alt)
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

    // 内置默认布局里的命名键：显示文本与规范键名分开，连发标记取自 REPETITIVE_KEY_NAMES，
    // 使方向键 / 退格 / 翻页与用户自定义布局的长按行为一致。
    fun namedKey(display: String, keyName: String) = ToolbarKey.Simple(
        display,
        repeats = keyName in REPETITIVE_KEY_NAMES
    ) { sendKeyName(keyName) }

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
            namedKey("⌫", "BKSP")
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
            namedKey("↑", "UP"),
            ToolbarKey.Simple("}") { sendChar('}') },
            namedKey("HOME", "HOME"),
            namedKey("END", "END")
        ),
        listOf(
            ctrlKey(),
            fnKey(),
            altKey(),
            ToolbarKey.Simple("|") { sendChar('|') },
            ToolbarKey.Simple("-") { sendChar('-') },
            ToolbarKey.Simple("+") { sendChar('+') },
            ToolbarKey.Simple("\"") { sendChar('"') },
            namedKey("←", "LEFT"),
            namedKey("↓", "DOWN"),
            namedKey("→", "RIGHT"),
            namedKey("PGUP", "PGUP"),
            namedKey("PGDN", "PGDN")
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
                else -> ToolbarKey.Simple(display, repeats = key in REPETITIVE_KEY_NAMES) { sendKeyName(key) }
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

    // 键宽按最宽的一行等分，铺满可用宽度——对齐原版 Termux 的 GridLayout 等分行为。
    // 旧实现每键固定 36.dp，10 列就是 10×36+9×3+12 = 399dp，比常见手机的 360dp 还宽，
    // 第 10 列起被挤出屏幕（termux.properties 配更多列时更明显），用户只能横向拖动才看得到。
    // 只有列数多到把键压到 MIN_KEY_WIDTH 以下时才退回横向滚动：那种密度下等分出来的键
    // 已点不中，滚动比挤压更可用。
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 先取出工具栏可用宽度：下方嵌套的 Box 也有 fillMaxWidth，直接写 maxWidth
        // 会被内层作用域的同名属性遮蔽。
        val toolbarWidth = maxWidth
        val horizontalPadding = 6.dp
        val gap = 3.dp
        val maxColumns = rows.maxOfOrNull { it.size } ?: 0
        val available = toolbarWidth - horizontalPadding * 2
        val fitWidth = if (maxColumns > 0) {
            (available - gap * (maxColumns - 1)) / maxColumns
        } else {
            available
        }
        val keyWidth = fitWidth.coerceAtLeast(MIN_KEY_WIDTH)
        val needsScroll = keyWidth < fitWidth

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(surfaceBg)
                .navigationBarsPadding()
                .then(if (needsScroll) Modifier.horizontalScroll(hScroll) else Modifier)
        ) {
            Column(
                modifier = Modifier
                    .width(if (needsScroll) keyWidth * maxColumns + gap * (maxColumns - 1) + horizontalPadding * 2 else toolbarWidth)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (row in rows) {
                    Row(
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        for (key in row) {
                            when (key) {
                                is ToolbarKey.Simple -> KeyButton(
                                    label = key.display,
                                    onClick = key.onSend,
                                    repeats = key.repeats,
                                    keyWidth = keyWidth,
                                    effectiveContentColor = effectiveContentColor
                                )
                                is ToolbarKey.ModifierKey -> SpecialKeyButton(
                                    label = key.label,
                                    sticky = key.isSticky(),
                                    locked = key.isLocked(),
                                    onTap = key.onTap,
                                    onLongPress = key.onLongPress,
                                    keyWidth = keyWidth,
                                    effectiveContentColor = effectiveContentColor
                                )
                            }
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
    repeats: Boolean,
    keyWidth: Dp,
    effectiveContentColor: Color
) {
    // repeats 的键长按连发（对齐原版 PRIMARY_REPETITIVE_KEYS 的方向键/退格/翻页）。
    // 不用 combinedClickable：它的 onLongClick 只触发一次，没有持续回调，无法连发。
    //
    // 连发循环与抬手检测放在同一个手势协程里：didRepeat 是普通局部变量而非 Compose 状态，
    // 抬手时读它不跨快照，永不与连发协程产生竞态。
    val currentOnClick by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    val background = if (pressed) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.35f)
    } else {
        MiuixTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = Modifier
            .size(width = keyWidth, height = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .pointerInput(repeats) {
                // coroutineScope 让连发循环与抬手检测共享同一个作用域：
                // 抬手时 cancel 即刻停，指针输入被整体取消时循环随作用域一起结束，
                // 不会留下一个还在发按键的后台协程。
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        pressed = true
                        var didRepeat = false
                        try {
                            if (repeats) {
                                val repeatJob = launch {
                                    delay(REPEAT_START_DELAY)
                                    didRepeat = true
                                    while (true) {
                                        currentOnClick()
                                        delay(REPEAT_INTERVAL)
                                    }
                                }
                                // 抬手或手指划出按钮范围都会返回；cancel 保证连发不会残留。
                                // 放在 finally 里，指针被取消（父手势被打断）时同样收尾。
                                waitForUpOrCancellation()
                                repeatJob.cancel()
                            } else {
                                waitForUpOrCancellation()
                            }
                        } finally {
                            pressed = false
                        }
                        // 连发已经发过就不再补这一次点击，否则长按结束会多出一个字符。
                        if (!didRepeat) currentOnClick()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            autoSize = KeyboardLabelAutoSize,
            fontWeight = FontWeight.Medium,
            color = effectiveContentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
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
    keyWidth: Dp,
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
            .size(width = keyWidth, height = 32.dp)
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
            autoSize = KeyboardLabelAutoSize,
            fontWeight = FontWeight.Bold,
            color = if (locked) MiuixTheme.colorScheme.onPrimary else effectiveContentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

private fun hasHardwareKeyboard(context: android.content.Context): Boolean {
    return context.resources.configuration.keyboard !=
        android.content.res.Configuration.KEYBOARD_NOKEYS
}
