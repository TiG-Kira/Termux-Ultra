package com.termux.app.compose

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.compose.terminal.ComposeSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compose 模式专用的终端会话列表页。
 * 100% 复刻 Java 版 TerminalListScreen 的 UI 结构。
 */
@Composable
fun ComposeTerminalListScreen(
    context: Context,
    onNewTerminal: () -> Unit,
    isWakeLockEnabled: Boolean = false,
    onToggleWakeLock: () -> Unit = {},
    navBarBottomPadding: Dp = 92.dp
) {
    val sessionManager = remember { ComposeSessionManager.getInstance(context) }
    val allSessions by sessionManager.sessions.collectAsState()
    val currentSessionId by sessionManager.currentSessionId.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetId by remember { mutableStateOf(-1) }
    var newName by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }

    val scrollBehavior = MiuixScrollBehavior()

    /** 刷新会话列表（StateFlow 会自动 propagate，但手动触发 PullToRefresh 动画） */
    fun refreshNow() {
        // StateFlow 已经自动更新，这里只是触发动画
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
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
                        Switch(
                            checked = isWakeLockEnabled,
                            onCheckedChange = { onToggleWakeLock() }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = {
                            // Compose 模式：直接用 ComposeSessionManager 创建会话，不依赖 Java 版 onNewTerminal。
                            // 效仿 Java 版策略：只创建未初始化的终端条目（不拉起进程、不跳转），
                            // 待用户手动点击该终端卡片进入终端控制台时再初始化。
                            val createdSession = sessionManager.createDefaultSession(startImmediately = false)
                            val count = sessionManager.sessions.value.indexOfFirst { it.session.id == createdSession.id }
                            createdSession.sessionName.value = if (com.termux.app.LocaleHelper.isChinese(context)) {
                                "会话 ${count + 1}"
                            } else {
                                "Session ${count + 1}"
                            }
                        }) {
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
            val filteredSessions = allSessions.filter { info ->
                val sessionName by info.session.sessionName.collectAsState(initial = "")
                val name = sessionName.ifEmpty { info.name }
                name.contains(searchQuery, ignoreCase = true) || searchQuery.isEmpty()
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
                                filteredSessions.forEach { info ->
                                    val sessionName by info.session.sessionName.collectAsState(initial = "")
                                    val displayName = sessionName.ifEmpty { info.name.ifEmpty { stringResource(R.string.terminal) } }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                sessionManager.switchTo(info.session.id)
                                                val intent = Intent(context, TermuxActivity::class.java)
                                                context.startActivity(intent)
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
                                            text = displayName,
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
                        refreshNow()
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 默认垂直网格布局
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = navBarBottomPadding + 16.dp)
                    ) {
                        if (allSessions.isEmpty()) {
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
                            items(allSessions) { info ->
                                val isCurrent = info.session.id == currentSessionId
                                val sessionName by info.session.sessionName.collectAsState(initial = "")
                                ComposeTerminalCard(
                                    info = info,
                                    isCurrent = isCurrent,
                                    onClick = {
                                        sessionManager.switchTo(info.session.id)
                                        val intent = Intent(context, TermuxActivity::class.java)
                                        context.startActivity(intent)
                                    },
                                    onStop = {
                                        sessionManager.killSession(info.session.id)
                                    },
                                    onRename = {
                                        renameTargetId = info.session.id
                                        newName = sessionName.ifEmpty { info.name }
                                        showRenameDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 重命名 OverlayDialog（必须在 Scaffold content 内）
        if (showRenameDialog && renameTargetId > 0) {
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
                                    val target = allSessions.firstOrNull { it.session.id == renameTargetId }
                                    target?.session?.sessionName?.value = newName
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

/**
 * Compose 模式的终端卡片，1:1 复刻 Java 版 TerminalCard。
 *
 * 方形 aspectRatio(1f)，圆角 20dp，左上角终端图标+名称+状态，右下角重命名+停止按钮。
 */
@Composable
private fun ComposeTerminalCard(
    info: ComposeSessionManager.SessionInfo,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onStop: () -> Unit,
    onRename: () -> Unit
) {
    val session = info.session
    val sessionName by session.sessionName.collectAsState(initial = "")
    val oscTitle by session.titleState.collectAsState(initial = null)

    // Compose TerminalSession pid 语义与 Java 版一致: 0=未初始化, >0=运行中, -1=已结束
    val isDead = session.pid == -1
    val isUninitialized = session.pid == 0
    val displayName = when {
        sessionName.isNotEmpty() -> sessionName
        !oscTitle.isNullOrBlank() -> oscTitle!!
        info.name.isNotEmpty() -> info.name
        else -> stringResource(R.string.terminal)
    }

    val titleColor = if (isDead) Color(0xFFFF5252) else MiuixTheme.colorScheme.onSurface
    val statusText: String? = when {
        isDead && session.exitStatus > 0 -> "退出代码:${session.exitStatus}"
        isDead -> "已结束"
        isUninitialized -> "未初始化"
        session.pid > 0 -> "PID ${session.pid}"
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
                    isCurrent -> MiuixTheme.colorScheme.primaryContainer
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
                        tint = if (isDead) Color(0xFFFF5252)
                        else if (isCurrent) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
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
                        onClick = onStop,
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
