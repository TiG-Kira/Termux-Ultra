package com.termux.app.compose

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import com.termux.app.TermuxService
import com.termux.shared.shell.TermuxSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.snapshotFlow

@Composable
fun TerminalListScreen(
    sessions: List<TermuxSession>,
    onSessionClick: (TermuxSession) -> Unit,
    onNewTerminal: () -> Unit,
    onStopTerminal: (TermuxSession) -> Unit,
    onRenameTerminal: (TermuxSession, String) -> Unit,
    isWakeLockEnabled: Boolean,
    onToggleWakeLock: () -> Unit,
    onRefresh: () -> Unit = {},
    navBarBottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameSession by remember { mutableStateOf<TermuxSession?>(null) }
    var newName by remember { mutableStateOf("") }
    var showWelcomeCard by remember { mutableStateOf(false) }
    var showKeepAliveWarning by remember { mutableStateOf(false) }
    var termuxService by remember { mutableStateOf<TermuxService?>(null) }
    var killedSessionName by remember { mutableStateOf<String?>(null) }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
    val aiTermuxEnabled = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        .getBoolean("ai_termux_enabled", true)
    // 卡片布局模式：0 = 垂直网格，1 = 横向滑动（仅提示卡片横向，终端卡片始终竖向）
    val cardLayoutMode = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        .getInt("KEY_CARD_LAYOUT_MODE", 0)
    var horizontalPage by remember { mutableIntStateOf(0) }
    // 已结束（被杀死/自然退出）的会话信息列表，从 TermuxService 拉取。
    // 这些会话已从 mTermuxSessions 移除，不计入会话数量，但以"死亡卡片"形式保留显示，
    // 直到用户手动消除。红色标题 + "退出代码:N" 小字。
    // 本地会话状态：从 TermuxService 实时拉取，避免依赖外部 2 秒轮询延迟
    var localSessions by remember { mutableStateOf(termuxService?.termuxSessions?.toList() ?: sessions) }
    var deadSessions by remember { mutableStateOf<List<TermuxService.DeadSessionInfo>>(termuxService?.deadSessionInfos ?: emptyList()) }
    val useHorizontalLayout = cardLayoutMode == 1
    // 已结束会话卡片过滤（按搜索条件）
    val filteredDeadSessions = deadSessions.filter {
        it.sessionName.contains(searchQuery, ignoreCase = true) || searchQuery.isEmpty()
    }

    // 实时拉取会话列表和死亡会话列表 —— 400ms 轮询，确保状态一变化就能在 UI 上反映
    LaunchedEffect(termuxService) {
        while (true) {
            termuxService?.let { svc ->
                val fresh = svc.termuxSessions.toList()
                if (fresh != localSessions) localSessions = fresh
                val freshDead = svc.deadSessionInfos
                if (freshDead != deadSessions) deadSessions = freshDead
            }
            delay(400)
        }
    }

    /** 立即从 TermuxService 拉取最新状态并刷新 UI */
    fun refreshNow() {
        termuxService?.let { svc ->
            localSessions = svc.termuxSessions.toList()
            deadSessions = svc.deadSessionInfos
        }
    }

    /** 停止会话后立即刷新（调用外部回调 + 立即拉取状态） */
    fun handleStopSession(session: TermuxSession) {
        onStopTerminal(session)
        // 延迟 300ms 后刷新，给 TermuxService 时间处理会话移除
        coroutineScope.launch {
            delay(300)
            refreshNow()
        }
    }

    /** 消除死亡会话卡片 */
    fun dismissDeadSession(sessionName: String, exitedAt: Long) {
        termuxService?.clearDeadSessionInfo(sessionName, exitedAt)
        deadSessions = termuxService?.deadSessionInfos ?: emptyList()
    }

    /** 强制移除会话（用于死亡卡片消除） */
    fun forceRemoveAndRefresh(session: TermuxSession) {
        termuxService?.forceRemoveTermuxSession(session)
        refreshNow()
    }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("terminal_welcome_shown", false)) {
            showWelcomeCard = true
        }

        if (ApiCompat.isAvailable(ApiCompat.Feature.KEEP_ALIVE_WARNING)) {
            if (!prefs.getBoolean("keep_alive_warning_dismissed", false)) {
                showKeepAliveWarning = true
            }
        }
    }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as TermuxService.LocalBinder
                termuxService = binder.service
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                termuxService = null
            }
        }
    }

    DisposableEffect(Unit) {
        val intent = Intent(context, TermuxService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        onDispose {
            context.unbindService(serviceConnection)
        }
    }

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.terminal),
                scrollBehavior = scrollBehavior,
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lock),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        top.yukonga.miuix.kmp.basic.Switch(
                            checked = isWakeLockEnabled,
                            onCheckedChange = { onToggleWakeLock() }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = onNewTerminal) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = stringResource(R.string.new_terminal),
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val filteredSessions = sessions.filter {
                val name = it.getTerminalSession().mSessionName ?: ""
                name.contains(searchQuery, ignoreCase = true) ||
                    searchQuery.isEmpty()
            }
            SearchBar(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = if (searchExpanded) 8.dp else 0.dp),
                inputField = {
                    InputField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { },
                        expanded = searchExpanded,
                        onExpandedChange = {
                            searchExpanded = it
                            if (!it) searchQuery = ""
                        },
                        label = stringResource(R.string.search)
                    )
                },
                expanded = searchExpanded,
                onExpandedChange = {
                    searchExpanded = it
                    if (!it) searchQuery = ""
                },
                outsideEndAction = {
                    if (searchExpanded) {
                        Text(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .clickable(
                                    interactionSource = null,
                                    indication = null
                                ) {
                                    searchExpanded = false
                                    searchQuery = ""
                                },
                            text = stringResource(R.string.cancel),
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                if (searchExpanded) {
                    when {
                        searchQuery.isEmpty() -> {
                            Spacer(Modifier.height(24.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.search_hint_input),
                                    color = androidx.compose.ui.graphics.Color.Gray,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        filteredSessions.isEmpty() -> {
                            Spacer(Modifier.height(24.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.search_no_result),
                                    color = androidx.compose.ui.graphics.Color.Gray,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        else -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                filteredSessions.forEach { session ->
                                    val sName = session.getTerminalSession().mSessionName
                                        ?: stringResource(R.string.terminal)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                onSessionClick(session)
                                                searchExpanded = false
                                                searchQuery = ""
                                            }
                                            .padding(vertical = 12.dp, horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_terminal),
                                            contentDescription = null,
                                            tint = MiuixTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            text = sName,
                                            fontWeight = FontWeight.Bold,
                                            color = MiuixTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (searchExpanded) Modifier.graphicsLayer {
                            translationY = 1_000_000f
                        } else Modifier
                    )
            ) {
                PullToRefresh(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        isRefreshing = true
                        // 立即刷新会话列表和死亡会话列表，而不是等待轮询
                        refreshNow()
                        onRefresh()
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            delay(600)
                            isRefreshing = false
                        }
                    },
                    refreshTexts = listOf(
                        stringResource(R.string.pull_down_to_refresh),
                        stringResource(R.string.release_to_refresh),
                        stringResource(R.string.refreshing),
                        stringResource(R.string.refresh_successful)
                    ),
                    contentPadding = PaddingValues(top = 12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                if (useHorizontalLayout) {
                // 终端卡片区域（竖向网格，始终竖向）
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = navBarBottomPadding + 16.dp)
                ) {
                    // 搜索过滤后的活跃会话
                    if (localSessions.isEmpty() && filteredDeadSessions.isEmpty()) {
                        item(span = { GridItemSpan(2) }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 100.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_terminal),
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.no_terminal),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    } else {
                        items(localSessions) { session ->
                            TerminalCard(
                                session = session,
                                onClick = { onSessionClick(session) },
                                onStop = { handleStopSession(session) },
                                onRename = {
                                    renameSession = session
                                    newName = session.getTerminalSession().mSessionName ?: ""
                                    showRenameDialog = true
                                },
                                onDismissDead = {
                                    forceRemoveAndRefresh(session)
                                }
                            )
                        }

                        items(filteredDeadSessions) { info ->
                            DeadSessionCard(
                                info = info,
                                onDismiss = {
                                    dismissDeadSession(info.sessionName, info.exitedAt)
                                }
                            )
                        }
                    }
                }
            } else {
                // 垂直网格布局（默认）
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = navBarBottomPadding + 16.dp)
                ) {
            // 搜索过滤：同时对活跃会话和死亡会话按名称匹配
            if (localSessions.isEmpty() && filteredDeadSessions.isEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 100.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_terminal),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.no_terminal),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            lineHeight = 22.sp
                        )
                    }
                }
            } else {
                items(localSessions) { session ->
                    TerminalCard(
                        session = session,
                        onClick = { onSessionClick(session) },
                        onStop = { handleStopSession(session) },
                        onRename = {
                            renameSession = session
                            newName = session.getTerminalSession().mSessionName ?: ""
                            showRenameDialog = true
                        },
                        onDismissDead = {
                            // 死亡卡片消除：强制从 TermuxService 移除并刷新
                            forceRemoveAndRefresh(session)
                        }
                    )
                }

                // 已结束会话卡片（从 deadSessions 列表）：红色标题 + "退出代码:N" 小字 + 手动消除按钮
                items(filteredDeadSessions) { info ->
                    DeadSessionCard(
                        info = info,
                        onDismiss = {
                            dismissDeadSession(info.sessionName, info.exitedAt)
                        }
                    )
                }
            }
        }
        }
    }
    }
    }

    if (showRenameDialog && renameSession != null) {
        OverlayDialog(
            show = showRenameDialog,
            onDismissRequest = { showRenameDialog = false },
            title = stringResource(R.string.rename_terminal),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = stringResource(R.string.terminal_name)
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showRenameDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = stringResource(R.string.ok),
                            onClick = {
                                renameSession?.let { session ->
                                    onRenameTerminal(session, newName)
                                }
                                showRenameDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        )
    }
    }
}

@Composable
private fun TerminalCard(
    session: TermuxSession,
    onClick: () -> Unit,
    onStop: () -> Unit,
    onRename: () -> Unit,
    onDismissDead: () -> Unit = {}
) {
    val terminalSession = session.terminalSession

    // 实时订阅终端会话状态 —— 解决 Compose 无法自动检测普通 Java 对象字段变化的问题
    // shellPid: 0=未初始化, >0=运行中, -1=已结束
    var shellPid by remember { mutableStateOf(terminalSession.shellPid) }
    var exitCode by remember { mutableStateOf(terminalSession.exitStatus) }
    var lastCommand by remember { mutableStateOf(terminalSession.lastCommand) }
    LaunchedEffect(terminalSession) {
        while (true) {
            // 始终更新状态（无条件检查），确保会话初始化/杀死后 UI 立即反映
            shellPid = terminalSession.shellPid
            exitCode = terminalSession.exitStatus
            lastCommand = terminalSession.lastCommand
            delay(400)
        }
    }

    val isDead = shellPid == -1
    val isUninitialized = shellPid == 0
    val titleColor = if (isDead) Color(0xFFFF5252) else MiuixTheme.colorScheme.onSurface
    val statusText: String? = when {
        isDead -> "退出代码:$exitCode"
        isUninitialized -> "未初始化"
        lastCommand.isNotEmpty() -> "最近执行:$lastCommand"
        else -> null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = !isDead, onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val iconBgColor = when {
                    isDead -> Color(0xFFFFEBEE)
                    else -> MiuixTheme.colorScheme.surfaceVariant
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_terminal),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (isDead) Color(0xFFFF5252) else MiuixTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = terminalSession.mSessionName ?: stringResource(R.string.terminal),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        lineHeight = 22.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = if (isDead) Color(0xFFD32F2F) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                if (isDead) {
                    IconButton(
                        onClick = onDismissDead,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFFF5252), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "消除",
                            modifier = Modifier.size(20.dp),
                            tint = Color.White
                        )
                    }
                } else {
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier
                            .size(36.dp)
                            .background(MiuixTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = stringResource(R.string.rename),
                            modifier = Modifier.size(20.dp),
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(36.dp)
                            .background(MiuixTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.stop),
                            modifier = Modifier.size(20.dp),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * 已结束会话卡片。
 *
 * 与 [TerminalCard] 的区别：
 *  - 标题文字为红色（提示会话已结束/被杀死）
 *  - 标题下方显示"退出代码:N"小字
 *  - 右下角是"消除"按钮（X 图标），点击后从 [TermuxService] 的死亡会话队列移除并刷新 UI
 *  - 不计入会话数量（已从 mTermuxSessions 移除，LiveUpdate 通知数量自动排除）
 */
@Composable
private fun DeadSessionCard(
    info: TermuxService.DeadSessionInfo,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val titleColor = Color(0xFFFF5252) // 红色标题，深浅色模式统一
    val subColor = if (isDark) Color(0xFFEF9A9A) else Color(0xFFD32F2F)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isDark) Color(0xFF3B1414) else Color(0xFFFFEBEE)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_terminal),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = titleColor
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info.sessionName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        lineHeight = 22.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "退出代码:${info.exitCode}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = subColor,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .background(titleColor, RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "消除",
                        modifier = Modifier.size(20.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun KeepAliveWarningCard(onClose: () -> Unit, horizontalMode: Boolean = false) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val prefs = remember { context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE) }
    var collapsed by remember { mutableStateOf(
        if (horizontalMode) false else prefs.getBoolean("keep_alive_warning_collapsed", false)
    ) }

    fun setCollapsed(value: Boolean) {
        if (!horizontalMode) {
            collapsed = value
            prefs.edit().putBoolean("keep_alive_warning_collapsed", value).apply()
        }
    }

    // 横向模式使用统一的 HorizontalTipCard 组件
    if (horizontalMode) {
        HorizontalTipCard(
            cardColor = if (isDark) Color(0xFF3D3514) else Color(0xFFFFF9C4),
            icon = Icons.Rounded.Warning,
            iconTint = Color.White,
            iconBackgroundColor = Color.Transparent,
            iconStyle = HeroIconStyle.GRADIENT,
            iconGradientColors = listOf(Color(0xFFF59E0B), Color(0xFFFDD835)),
            title = stringResource(R.string.keep_alive_warning_title),
            description = stringResource(R.string.keep_alive_warning_message),
            titleColor = if (isDark) Color.White else Color.Black,
            descriptionColor = if (isDark) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.8f),
            statusBadgeText = "需注意",
            statusBadgeColor = if (isDark) Color(0xFFFCD34D) else Color(0xFFB45309),
            statusBadgeBackgroundColor = if (isDark) Color(0xFFFCD34D).copy(alpha = 0.14f) else Color(0xFFF59E0B).copy(alpha = 0.14f),
            onClose = onClose,
            closeButtonColor = if (isDark) Color(0xFFFCD34D).copy(alpha = 0.15f) else Color(0xFFB45309).copy(alpha = 0.15f),
            closeButtonIconColor = if (isDark) Color(0xFFFCD34D) else Color(0xFFB45309)
        )
        return
    }

    // 竖向模式保持原有设计
    val cardColor = if (isDark) Color(0xFF3D3514) else Color(0xFFFFF9C4)
    val iconColor = Color(0xFFFDD835)
    val textColor = if (isDark) Color.White else Color.Black

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .then(if (collapsed && !horizontalMode) Modifier.clickable { setCollapsed(false) } else Modifier),
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(cardColor)) {
            if (!collapsed && !horizontalMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(30.dp, 60.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Icon(
                        modifier = Modifier.size(120.dp).alpha(0.8f),
                        imageVector = Icons.Rounded.Warning,
                        tint = iconColor,
                        contentDescription = null
                    )
                }
            }
            if (collapsed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        tint = iconColor,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.keep_alive_warning_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        maxLines = 1
                    )
                    Text(
                        text = stringResource(R.string.keep_alive_warning_message),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = textColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = 18.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.ok),
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.End)
                            .clickable(onClick = onClose),
                        tint = textColor.copy(alpha = 0.6f)
                    )
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.keep_alive_warning_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        lineHeight = 26.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.keep_alive_warning_message),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor,
                        lineHeight = 21.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = 6.dp, y = 4.dp)
                        .size(20.dp)
                        .clickable { setCollapsed(true) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandLess,
                        contentDescription = null,
                        tint = textColor.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun WelcomeCard(text: String, onClose: () -> Unit, horizontalMode: Boolean = false) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val prefs = remember { context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE) }
    var collapsed by remember { mutableStateOf(
        if (horizontalMode) false else prefs.getBoolean("welcome_card_collapsed", false)
    ) }

    fun setCollapsed(value: Boolean) {
        if (!horizontalMode) {
            collapsed = value
            prefs.edit().putBoolean("welcome_card_collapsed", value).apply()
        }
    }

    // 横向模式使用统一的 HorizontalTipCard 组件
    if (horizontalMode) {
        val welcomeGradient = if (isDark)
            Brush.linearGradient(listOf(Color(0xFF1E40AF), Color(0xFF5B21B6)))
        else
            Brush.linearGradient(listOf(Color(0xFF2563EB), Color(0xFF7C3AED)))
        HorizontalTipCard(
            cardColor = Color.Transparent,
            gradient = welcomeGradient,
            icon = Icons.Rounded.Info,
            iconTint = Color.White,
            iconBackgroundColor = Color.Transparent,
            iconStyle = HeroIconStyle.FROSTED_GLASS,
            showDecorationCircles = true,
            title = stringResource(R.string.terminal_welcome_title),
            description = text,
            titleColor = Color.White,
            descriptionColor = Color.White.copy(alpha = 0.72f),
            onClose = onClose
        )
        return
    }

    // 竖向模式保持原有设计
    val cardColor = if (isDark) Color(0xFF1A1A1A) else Color.White
    val iconColor = if (isDark) Color(0xFF666666) else Color(0xFFCCCCCC)
    val textColor = if (isDark) Color.White else Color.Black

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .then(if (collapsed && !horizontalMode) Modifier.clickable { setCollapsed(false) } else Modifier),
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(cardColor)) {
            if (!collapsed && !horizontalMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(30.dp, 30.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Icon(
                        modifier = Modifier.size(120.dp).alpha(0.8f),
                        imageVector = Icons.Rounded.Info,
                        tint = iconColor,
                        contentDescription = null
                    )
                }
            }
            if (collapsed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        tint = iconColor,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.terminal_welcome_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        maxLines = 1
                    )
                    Text(
                        text = text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = textColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = 18.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.ok),
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.End)
                            .clickable(onClick = onClose),
                        tint = textColor.copy(alpha = 0.6f)
                    )
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor,
                        lineHeight = 22.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = 6.dp, y = 4.dp)
                        .size(20.dp)
                        .clickable { setCollapsed(true) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandLess,
                        contentDescription = null,
                        tint = textColor.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

enum class ServiceStatus {
    NORMAL,
    WAKE_LOCK_ACTIVE,
    SERVICE_STOPPED,
    MEMORY_WARNING,
    MEMORY_KILL,
    SESSION_KILLED
}

@Composable
fun ServiceStatusCard(
    status: ServiceStatus,
    killedSessionName: String? = null,
    horizontalMode: Boolean = false
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val prefs = remember { context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE) }
    var collapsed by remember { mutableStateOf(
        if (horizontalMode) false else prefs.getBoolean("service_status_collapsed", false)
    ) }

    fun setCollapsed(value: Boolean) {
        if (!horizontalMode) {
            collapsed = value
            prefs.edit().putBoolean("service_status_collapsed", value).apply()
        }
    }

    val title = when (status) {
        ServiceStatus.NORMAL -> stringResource(R.string.service_status_normal)
        ServiceStatus.WAKE_LOCK_ACTIVE -> stringResource(R.string.service_status_wake_lock)
        ServiceStatus.SERVICE_STOPPED -> stringResource(R.string.service_status_stopped)
        ServiceStatus.MEMORY_WARNING -> stringResource(R.string.memory_warning_title)
        ServiceStatus.MEMORY_KILL -> stringResource(R.string.memory_kill_title)
        ServiceStatus.SESSION_KILLED -> stringResource(R.string.service_status_killed)
    }
    
    val description = when (status) {
        ServiceStatus.NORMAL -> stringResource(R.string.service_status_normal_desc)
        ServiceStatus.WAKE_LOCK_ACTIVE -> stringResource(R.string.service_status_wake_lock_desc)
        ServiceStatus.SERVICE_STOPPED -> stringResource(R.string.service_status_stopped_desc)
        ServiceStatus.MEMORY_WARNING -> stringResource(R.string.memory_warning_message)
        ServiceStatus.MEMORY_KILL -> stringResource(R.string.memory_kill_message)
        ServiceStatus.SESSION_KILLED -> {
            val name = killedSessionName ?: "unknown"
            stringResource(R.string.service_status_killed_desc, name)
        }
    }

    // 横向模式使用统一的 HorizontalTipCard 组件
    if (horizontalMode) {
        val (cardColor, iconColor, icon) = when (status) {
            ServiceStatus.NORMAL -> Triple(if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4), Color(0xFF36D167), Icons.Rounded.CheckCircleOutline)
            ServiceStatus.WAKE_LOCK_ACTIVE -> Triple(if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4), Color(0xFF36D167), Icons.Rounded.CheckCircleOutline)
            ServiceStatus.SERVICE_STOPPED -> Triple(if (isDark) Color(0xFF3B1414) else Color(0xFFFFEBEE), Color(0xFFFF5252), Icons.Rounded.ErrorOutline)
            ServiceStatus.MEMORY_WARNING -> Triple(if (isDark) Color(0xFF3D3514) else Color(0xFFFFF9C4), Color(0xFFFDD835), Icons.Rounded.Warning)
            ServiceStatus.MEMORY_KILL -> Triple(if (isDark) Color(0xFF3B1414) else Color(0xFFFFEBEE), Color(0xFFFF5252), Icons.Rounded.Warning)
            ServiceStatus.SESSION_KILLED -> Triple(if (isDark) Color(0xFF3B1414) else Color(0xFFFFEBEE), Color(0xFFFF5252), Icons.Rounded.Warning)
        }
        val iconGradColors = when (status) {
            ServiceStatus.NORMAL, ServiceStatus.WAKE_LOCK_ACTIVE -> listOf(Color(0xFF36D167), Color(0xFF22C55E))
            ServiceStatus.SERVICE_STOPPED, ServiceStatus.MEMORY_KILL, ServiceStatus.SESSION_KILLED -> listOf(Color(0xFFEF4444), Color(0xFFFF5252))
            ServiceStatus.MEMORY_WARNING -> listOf(Color(0xFFF59E0B), Color(0xFFFDD835))
        }
        val (badgeText, badgeColor) = when (status) {
            ServiceStatus.NORMAL, ServiceStatus.WAKE_LOCK_ACTIVE -> "运行中" to Color(0xFF36D167)
            ServiceStatus.SERVICE_STOPPED -> "已停止" to Color(0xFFFF5252)
            ServiceStatus.MEMORY_WARNING -> "需注意" to Color(0xFFF59E0B)
            ServiceStatus.MEMORY_KILL -> "内存不足" to Color(0xFFFF5252)
            ServiceStatus.SESSION_KILLED -> "已终止" to Color(0xFFFF5252)
        }
        HorizontalTipCard(
            cardColor = cardColor,
            icon = icon,
            iconTint = Color.White,
            iconBackgroundColor = Color.Transparent,
            iconStyle = HeroIconStyle.GRADIENT,
            iconGradientColors = iconGradColors,
            title = title,
            description = description,
            titleColor = if (isDark) Color.White else Color.Black,
            descriptionColor = if (isDark) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.8f),
            statusBadgeText = badgeText,
            statusBadgeColor = badgeColor,
            statusBadgeBackgroundColor = badgeColor.copy(alpha = 0.14f)
        )
        return
    }

    // 竖向模式保持原有设计
    val (cardColor, iconColor, icon) = when (status) {
        ServiceStatus.NORMAL -> {
            Triple(if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4), Color(0xFF36D167), Icons.Rounded.CheckCircleOutline)
        }
        ServiceStatus.WAKE_LOCK_ACTIVE -> {
            Triple(if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4), Color(0xFF36D167), Icons.Rounded.CheckCircleOutline)
        }
        ServiceStatus.SERVICE_STOPPED -> {
            Triple(if (isDark) Color(0xFF3B1414) else Color(0xFFFFEBEE), Color(0xFFFF5252), Icons.Rounded.ErrorOutline)
        }
        ServiceStatus.MEMORY_WARNING -> {
            Triple(if (isDark) Color(0xFF3D3514) else Color(0xFFFFF9C4), Color(0xFFFDD835), Icons.Rounded.Warning)
        }
        ServiceStatus.MEMORY_KILL -> {
            Triple(if (isDark) Color(0xFF3B1414) else Color(0xFFFFEBEE), Color(0xFFFF5252), Icons.Rounded.Warning)
        }
        ServiceStatus.SESSION_KILLED -> {
            Triple(if (isDark) Color(0xFF3B1414) else Color(0xFFFFEBEE), Color(0xFFFF5252), Icons.Rounded.Warning)
        }
    }
    val textColor = if (isDark) Color.White else Color.Black

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .then(if (collapsed && !horizontalMode) Modifier.clickable { setCollapsed(false) } else Modifier),
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(cardColor)) {
            if (!collapsed && !horizontalMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(35.dp, 35.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Icon(
                        modifier = Modifier.size(120.dp).alpha(0.8f),
                        imageVector = icon,
                        tint = iconColor,
                        contentDescription = null
                    )
                }
            }
            if (collapsed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        tint = iconColor,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        maxLines = 1
                    )
                    Text(
                        text = description,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = textColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = 18.dp)
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        lineHeight = 26.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = description,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor,
                        lineHeight = 21.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = 6.dp, y = 4.dp)
                        .size(20.dp)
                        .clickable { setCollapsed(true) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandLess,
                        contentDescription = null,
                        tint = textColor.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 低版本 Android 警告卡片。
 *
 *  - 默认：黄底 + 警告图标，提示用户版本过低
 *  - 用户已强制启用任意功能：红底 + 警告图标，提示用户自行承担闪退/卡顿风险
 *  - 若用户没有强制启用功能：沿用原有「Android 版本过低」文案；有则升级为「已强制启用部分功能」+功能列表
 */
@Composable
fun LowAndroidWarningCard(horizontalMode: Boolean = false) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    var forceEnabled by remember { mutableStateOf(ApiCompat.forceEnabledFeatures(context)) }
    val hasForce = forceEnabled.isNotEmpty()
    var showDisableDialog by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE) }
    var collapsed by remember { mutableStateOf(
        if (horizontalMode) false else prefs.getBoolean("low_android_warning_collapsed", false)
    ) }

    fun setCollapsed(value: Boolean) {
        if (!horizontalMode) {
            collapsed = value
            prefs.edit().putBoolean("low_android_warning_collapsed", value).apply()
        }
    }

    val title = if (hasForce) {
        stringResource(R.string.low_android_force_enabled_title)
    } else {
        stringResource(R.string.low_android_warning_title)
    }
    val versionInfo = stringResource(
        R.string.low_android_version_info,
        ApiCompat.androidReleaseName,
        ApiCompat.sdkInt
    )
    val message = if (hasForce) {
        val list = forceEnabled.joinToString("、") { it.label }
        stringResource(R.string.low_android_force_enabled_desc,
            ApiCompat.androidReleaseName, ApiCompat.sdkInt, list)
    } else {
        stringResource(R.string.low_android_warning_message)
    }

    // 横向模式使用统一的 HorizontalTipCard 组件
    if (horizontalMode) {
        val briefDescription = "$versionInfo · $message"
        if (hasForce) {
            // 强制启用模式：红色容器 + 渐变图标 + 状态徽章 + 禁用按钮
            HorizontalTipCard(
                cardColor = if (isDark) Color(0xFF3B1414) else Color(0xFFFFEBEE),
                icon = Icons.Rounded.Warning,
                iconTint = Color.White,
                iconBackgroundColor = Color.Transparent,
                iconStyle = HeroIconStyle.GRADIENT,
                iconGradientColors = listOf(Color(0xFFEF4444), Color(0xFFFF5252)),
                title = title,
                description = briefDescription,
                titleColor = if (isDark) Color.White else Color.Black,
                descriptionColor = if (isDark) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.8f),
                statusBadgeText = "强制启用",
                statusBadgeColor = Color(0xFFFF5252),
                statusBadgeBackgroundColor = Color(0xFFFF5252).copy(alpha = 0.14f),
                actionButton = {
                    Button(
                        onClick = { showDisableDialog = true },
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(50)),
                        colors = ButtonDefaults.buttonColors(
                            color = Color(0xFFFF5252)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.low_android_force_disable_button),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            )
        } else {
            // 普通警告模式：橙色渐变 + 毛玻璃图标 + 装饰圆圈
            val androidGradient = if (isDark)
                Brush.linearGradient(listOf(Color(0xFF9A3412), Color(0xFFB45309)))
            else
                Brush.linearGradient(listOf(Color(0xFFEA580C), Color(0xFFF59E0B)))
            HorizontalTipCard(
                cardColor = Color.Transparent,
                gradient = androidGradient,
                icon = Icons.Rounded.Warning,
                iconTint = Color.White,
                iconBackgroundColor = Color.Transparent,
                iconStyle = HeroIconStyle.FROSTED_GLASS,
                showDecorationCircles = true,
                title = title,
                description = briefDescription,
                titleColor = Color.White,
                descriptionColor = Color.White.copy(alpha = 0.72f)
            )
        }
        return
    }

    // 竖向模式保持原有设计
    val cardColor = if (hasForce) {
        if (isDark) Color(0xFF3B1414) else Color(0xFFFFEBEE)
    } else {
        if (isDark) Color(0xFF3D3514) else Color(0xFFFFF9C4)
    }
    val iconColor = if (hasForce) Color(0xFFFF5252) else Color(0xFFFDD835)
    val textColor = if (isDark) Color.White else Color.Black
    val briefDescription = "$versionInfo · $message"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .then(if (collapsed && !horizontalMode) Modifier.clickable { setCollapsed(false) } else Modifier),
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(cardColor)) {
            if (!collapsed && !horizontalMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(35.dp, 35.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Icon(
                        modifier = Modifier.size(120.dp).alpha(0.8f),
                        imageVector = Icons.Rounded.Warning,
                        tint = iconColor,
                        contentDescription = null
                    )
                }
            }
            if (collapsed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        tint = iconColor,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        maxLines = 1
                    )
                    Text(
                        text = briefDescription,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = textColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = 18.dp)
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        lineHeight = 26.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = versionInfo,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor,
                        lineHeight = 21.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = message,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor,
                        lineHeight = 21.sp
                    )
                    if (hasForce) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = { showDisableDialog = true },
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                                colors = ButtonDefaults.buttonColors(
                                    color = iconColor
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.low_android_force_disable_button),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = 6.dp, y = 4.dp)
                        .size(20.dp)
                        .clickable { setCollapsed(true) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandLess,
                        contentDescription = null,
                        tint = textColor.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    // 关闭强制启用确认弹窗
    if (showDisableDialog) {
        OverlayDialog(
            show = true,
            onDismissRequest = { showDisableDialog = false },
            title = stringResource(R.string.low_android_force_disable_dialog_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.low_android_force_disable_dialog_message),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 21.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            text = stringResource(R.string.low_android_force_disable_cancel),
                            onClick = { showDisableDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = stringResource(R.string.low_android_force_disable_confirm),
                            onClick = {
                                ApiCompat.clearAllForceEnabled(context)
                                forceEnabled = ApiCompat.forceEnabledFeatures(context)
                                showDisableDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        )
    }
}

/**
 * 低版本 Android 强行启用确认弹窗。
 *
 * @param feature 被点击的功能（展示 label 与 minApi 要求版本）
 * @param onConfirmed 用户点击「强行启用」：写入持久化并执行后续动作
 * @param onDismiss 用户点击「取消启用」或外部 dismiss
 */
@Composable
fun ForceEnableFeatureDialog(
    feature: ApiCompat.Feature,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val message = stringResource(
        R.string.force_enable_dialog_message,
        ApiCompat.androidReleaseName,
        ApiCompat.sdkInt,
        feature.label,
        feature.requiredVersionLabel
    )
    OverlayDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.force_enable_dialog_title),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = message,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 21.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        text = stringResource(R.string.force_enable_do_not),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.force_enable_anyway),
                        onClick = {
                            ApiCompat.setFeatureForceEnabled(context, feature, true)
                            onConfirmed()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

/**
 * 统一横向提示卡片布局组件。
 * 所有提示卡片在横向模式下使用此组件，确保统一的设计规范。
 *
 * 设计规范：
 * - 高度：140dp
 * - 内部 padding：16dp
 * - 结构：左侧图标背景 + 右侧标题和描述
 * - 间距：12dp（由外部 LazyRow 控制）
 */
enum class HeroIconStyle {
    SOLID,
    FROSTED_GLASS,
    GRADIENT
}

@Composable
fun HorizontalTipCard(
    cardColor: Color,
    icon: ImageVector? = null,
    iconPainter: androidx.compose.ui.graphics.painter.Painter? = null,
    iconTint: Color = Color.White,
    iconBackgroundColor: Color,
    title: String,
    description: String,
    titleColor: Color = Color.White,
    descriptionColor: Color = Color.White.copy(alpha = 0.85f),
    gradient: Brush? = null,
    onClick: (() -> Unit)? = null,
    iconStyle: HeroIconStyle = HeroIconStyle.SOLID,
    iconGradientColors: List<Color>? = null,
    showDecorationCircles: Boolean = false,
    statusBadgeText: String? = null,
    statusBadgeColor: Color = Color.White,
    statusBadgeBackgroundColor: Color = Color.White.copy(alpha = 0.2f),
    onClose: (() -> Unit)? = null,
    closeButtonColor: Color = Color.White.copy(alpha = 0.15f),
    closeButtonIconColor: Color = Color.White.copy(alpha = 0.85f),
    actionButton: (@Composable () -> Unit)? = null
) {
    val cardModifier = if (onClick != null) {
        Modifier.clickable { onClick() }
    } else {
        Modifier
    }

    Card(
        modifier = Modifier
            .width(340.dp)
            .height(140.dp)
            .then(cardModifier)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(brush = gradient ?: Brush.verticalGradient(listOf(cardColor, cardColor)))
        ) {
            // 关闭按钮
            if (onClose != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(closeButtonColor)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = closeButtonIconColor
                    )
                }
            }

            // 主内容
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 左侧图标区域
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .then(
                            when (iconStyle) {
                                HeroIconStyle.SOLID -> Modifier.background(iconBackgroundColor)
                                HeroIconStyle.FROSTED_GLASS -> Modifier.background(Color.White.copy(alpha = 0.2f))
                                HeroIconStyle.GRADIENT -> Modifier.background(
                                    Brush.linearGradient(
                                        iconGradientColors ?: listOf(iconBackgroundColor, iconBackgroundColor)
                                    )
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        icon != null -> Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                        iconPainter != null -> Icon(
                            painter = iconPainter,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // 右侧文本区域 + 状态徽章
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = titleColor,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        if (statusBadgeText != null) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(statusBadgeBackgroundColor)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(statusBadgeColor)
                                )
                                Text(
                                    text = statusBadgeText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = statusBadgeColor
                                )
                            }
                        }
                    }
                    Text(
                        text = description,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = descriptionColor,
                        lineHeight = 19.sp
                    )
                    if (actionButton != null) {
                        actionButton()
                    }
                }
            }
        }
    }
}

@Composable
fun ForceEnableCriticalDialog(
    feature: ApiCompat.Feature,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var understood by remember { mutableStateOf(false) }
    OverlayDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.critical_force_enable_dialog_title),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF3B30),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.critical_force_enable_dialog_message,
                            ApiCompat.androidReleaseName,
                            ApiCompat.sdkInt,
                            feature.label,
                            feature.requiredVersionLabel
                        ),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Spacer(Modifier.height(16.dp))
                CheckboxPreference(
                    title = stringResource(R.string.critical_force_enable_confirm_text),
                    checked = understood,
                    onCheckedChange = { understood = it }
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        text = stringResource(R.string.critical_force_enable_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.critical_force_enable_action_continue),
                        onClick = {
                            if (understood) {
                                ApiCompat.setFeatureForceEnabled(context, feature, true)
                                onConfirmed()
                            }
                        },
                        enabled = understood,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(
                            color = Color(0xFFFF3B30)
                        )
                    )
                }
            }
        }
    )
}