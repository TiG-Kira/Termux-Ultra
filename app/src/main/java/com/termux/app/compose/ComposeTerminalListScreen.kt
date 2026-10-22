package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.TermuxService
import com.termux.app.terminal.shell.ComposeSessionManager
import com.termux.app.terminal.shell.pid
import com.termux.app.terminal.shell.pidState
import com.termux.app.terminal.shell.lastCommandState
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.GlassTransformPopup
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Clear
import top.yukonga.miuix.kmp.icon.glass.Lock
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.Rocket
import top.yukonga.miuix.kmp.icon.glass.Sort
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Scaffold
import androidx.compose.ui.platform.LocalContext
import com.termux.app.compose.LocalTopBarClearance
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.app.vortex.VorteXSandbox

/**
 * 终端会话列表页。
 * 100% 复刻 Java 版 TerminalListScreen 的 UI 结构。
 */

// 会话列表排序模式（app_settings: terminal_session_sort_mode）
private const val SESSION_SORT_OPEN_ORDER = 0
private const val SESSION_SORT_BY_NAME = 1
private const val SESSION_SORT_INITIALIZED_FIRST = 2

@Composable
fun ComposeTerminalListScreen(
    context: Context,
    onNewTerminal: () -> Unit,
    isWakeLockEnabled: Boolean = false,
    onToggleWakeLock: () -> Unit = {},
    navBarBottomPadding: Dp = 92.dp,
    onTopBarContent: (@Composable () -> Unit) -> Unit,
    onPopupContent: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) -> Unit,
    active: Boolean = true
) {
    val sessionManager = remember { ComposeSessionManager.getInstance(context) }
    val allSessions by sessionManager.sessions.collectAsState()
    val currentSessionId by sessionManager.currentSessionId.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    // 沙箱「准备中」状态：首次进入需后台复制约 95MB 的 $PREFIX 影子层
    var sandboxPreparing by remember { mutableStateOf(false) }
    val scope = coroutineScope
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetId by remember { mutableStateOf(-1) }
    var newName by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }

    // 「更多」玻璃菜单（对齐 Termux Agent 对话页的 GlassTransformPopup 三件套）
    val moreAnchor = rememberGlassPopupAnchor()
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var sessionSortMode by remember {
        mutableStateOf(
            context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getInt("terminal_session_sort_mode", SESSION_SORT_OPEN_ORDER)
        )
    }
    // 名字经 collectAsState 聚合成快照：排序模式下重命名后顺序立即跟随，
    // 也避免直接读 Flow.value（非快照读不会触发重组）。必须在组合上下文里构建。
    val nameSnapshot = allSessions.map { info ->
        info to info.session.sessionName.collectAsState(initial = "").value
    }

    /** 打开沙盒会话（原顶栏沙盒按钮逻辑，收进「更多」菜单） */
    fun openSandboxSession() {
        if (!VorteXSandbox.isEnabled(context)) {
            Toast.makeText(
                context,
                context.getString(R.string.vortex_sandbox_disabled_toast),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        // 限制同时仅一个手动沙箱会话：已存在则切换过去
        val title = context.getString(R.string.vortex_sandbox_session_title)
        val existing = sessionManager.sessions.value.firstOrNull { it.name == title }
        if (existing != null) {
            sessionManager.switchTo(existing.session.id)
            return
        }
        // 首次进入需复制约 95MB 的 $PREFIX 到影子层，
        // 属磁盘 IO，必须放后台并给出「准备中」提示，不能卡住 UI。
        sandboxPreparing = true
        VorteXSandbox.prepareAsync(context) {
            // 先在后台等待在途回收完成，保证 tmp/ 是干净 slate。
            VorteXSandbox.awaitPendingPurge()
            // 回调在后台线程，需切回主线程操作会话与状态
            scope.launch {
                sandboxPreparing = false
                runCatching {
                    val created = sessionManager.createSandboxSession(startImmediately = true)
                    sessionManager.switchTo(created.id)
                    VorteXSandbox.setActiveManualSessionId(created.id)
                }
            }
        }
    }

    /** 关闭所有会话：走通知「结束会话」按钮的同一链路，交给 TermuxService 处理
     *  （含 QEMU/proot 危险进程检测与数据丢失确认）；Compose 侧列表由
     *  TermuxService.actionStopServiceForce() 收尾统一清空。 */
    fun closeAllSessions() {
        context.startService(
            Intent(context, TermuxService::class.java)
                .setAction(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE)
        )
    }

    /** 清理未初始化会话：移除所有尚未拉起进程（pid == 0）的会话条目。 */
    fun cleanupUninitializedSessions() {
        val targets = sessionManager.sessions.value.filter { it.session.pid == 0 }
        targets.forEach { sessionManager.killSession(it.session.id) }
        if (targets.isNotEmpty()) {
            Toast.makeText(
                context,
                context.getString(R.string.cleanup_uninitialized_toast, targets.size),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 新建安全模式会话：与终端控制台 addNewSession(isFailSafe) 一致，立即启动并切换进入。 */
    fun newFailsafeSession() {
        val created = sessionManager.createDefaultSession(startImmediately = true, isFailsafe = true)
        sessionManager.switchTo(created.id)
    }

    /** 刷新会话列表（StateFlow 会自动 propagate，但手动触发 PullToRefresh 动画） */
    fun refreshNow() {
        // StateFlow 已经自动更新，这里只是触发动画
    }

    // 与「统一顶栏之前」一致：本页自持吸顶状态
    val scrollBehavior = MiuixScrollBehavior()
    // 统一全局顶栏：仅当前激活页把本页的 TopAppBar 内容写入 onTopBarContent 槽
    SideEffect {
        if (active) {
            onTopBarContent {
                GlassTopAppBar(
                    title = stringResource(R.string.terminal),
                    backdrop = LocalGlassTopAppBarBackdrop.current,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        // 对齐目标是「右侧的 + 图标」，不是「+ 的玻璃底板」：
                        // 右侧 GlassIconButton 的图标中心距右缘 =
                        //   actionIconPadding(12) + ButtonPadding(6) + ButtonSize/2(22) = 40dp，
                        // 因为收缩态下 + 嵌在 44dp 底板里、图标只居中而不贴边。
                        // 左侧 navigationIcon 槽只有 Box(buttonPadding 6) 且没底板，offset=0 时锁图标
                        // 中心仅距左缘 12+6+12=30dp，故向内(+x)推 10dp 让两者中心对称。右侧不要动。
                        Row(
                            modifier = Modifier.offset(x = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = MiuixGlassIcons.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Switch(
                                checked = isWakeLockEnabled,
                                onCheckedChange = { onToggleWakeLock() }
                            )
                        }
                    },
                    actions = {
                        GlassIconButton(onClick = {
                            // 直接用 ComposeSessionManager 创建会话，不依赖 Java 版 onNewTerminal。
                            // 效仿 Java 版策略：只创建未初始化的终端条目（不拉起进程、不跳转），
                            // 待用户手动点击该终端卡片进入终端控制台时再初始化。
                            val createdSession = sessionManager.createDefaultSession(startImmediately = false)
                            val count = sessionManager.sessions.value.indexOfFirst { it.session.id == createdSession.id }
                            createdSession.sessionName.value = if (com.termux.app.LocaleHelper.isChinese(context)) {
                                context.getString(R.string.session_count_plus, count + 1)
                            } else {
                                context.getString(R.string.session_count_plus, count + 1)
                            }
                        }) {
                            Icon(
                                imageVector = MiuixGlassIcons.Add,
                                contentDescription = stringResource(R.string.new_terminal),
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        // 「更多」菜单：位于「+」按钮右侧，下拉面板由根级
                        // GlassTransformPopup 承载（对齐 Agent 对话页的写法）。
                        GlassIconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.glassPopupAnchor(
                                moreAnchor,
                                cornerRadius = GlassTopAppBarDefaults.ButtonSize / 2
                            )
                        ) {
                            Icon(
                                imageVector = MiuixGlassIcons.More,
                                contentDescription = stringResource(R.string.menu_more),
                                modifier = Modifier.size(22.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                )
            }
        }
    }

    // ---------- 沙箱准备中 ----------
    // OverlayDialog 是「标题 → 摘要 → 内容」三段式结构，标题 chrome 区固定占位：
    // 空 title 会让上方留出一大片空白。与 SettingsScreen 沙盒 busy 弹窗对齐——
    // 标题承载文案，content 只放转圈。
    // 准备期间禁止关闭：影子层拷贝必须跑完，否则半成品目录会被当成已就绪。
    OverlayDialog(
        show = sandboxPreparing,
        title = stringResource(R.string.vortex_sandbox_preparing),
        onDismissRequest = { /* 准备中不可取消 */ },
        content = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    size = 36.dp,
                    strokeWidth = 3.dp
                )
            }
        }
    )

    // 排序选择弹窗
    OverlayDialog(
        show = showSortDialog,
        onDismissRequest = { showSortDialog = false },
        title = stringResource(R.string.menu_sort_sessions),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val sortOptions = listOf(
                    SESSION_SORT_OPEN_ORDER to R.string.sort_by_open_order,
                    SESSION_SORT_BY_NAME to R.string.sort_by_name,
                    SESSION_SORT_INITIALIZED_FIRST to R.string.sort_by_initialized
                )
                sortOptions.forEachIndexed { index, (mode, labelRes) ->
                    val selected = sessionSortMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                sessionSortMode = mode
                                context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                                    .edit().putInt("terminal_session_sort_mode", mode).apply()
                                showSortDialog = false
                            }
                            .padding(vertical = 14.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurface
                        )
                    }
                    if (index != sortOptions.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 搜索栏是页面顶部的固定条，必须停在玻璃顶栏下方；列表本身仍会滚到顶栏之下。
                .padding(top = LocalTopBarClearance.current)
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
                    AnimatedVisibility(
                        visible = searchExpanded,
                        enter = fadeIn(animationSpec = GlassMotion.fadeIn()) + slideInHorizontally(
                            animationSpec = GlassMotion.navShowHide<IntOffset>()
                        ) { it / 2 },
                        exit = fadeOut(animationSpec = GlassMotion.fadeOut()) + slideOutHorizontally(
                            animationSpec = GlassMotion.navShowHide<IntOffset>()
                        ) { it / 2 }
                    ) {
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
                                    val searchNumber = allSessions.indexOfFirst { it.session.id == info.session.id } + 1
                                    val displayName = sessionName.ifEmpty { info.name.ifEmpty { context.getString(R.string.session_display_number, searchNumber) } }
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
                        // 横屏/平板：更宽的屏幕排 4 列会话卡片；竖屏维持 2 列
                        columns = if (isWideScreenLayout()) GridCells.Fixed(4) else GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        // 顶部让位已由承载搜索栏的那行 padding 吃掉，这里不能再加一次顶栏高度。
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = navBarBottomPadding + 16.dp)
                    ) {
                        if (allSessions.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
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
                            // 展示顺序按用户选定的排序模式（历史打开顺序 / 按字母 / 按是否初始化）
                            val displaySessions = when (sessionSortMode) {
                                SESSION_SORT_BY_NAME -> nameSnapshot
                                    .sortedBy { (info, name) -> name.ifEmpty { info.name }.lowercase() }
                                    .map { it.first }
                                SESSION_SORT_INITIALIZED_FIRST -> allSessions.sortedBy { info ->
                                    if (info.session.pid == 0) 1 else 0
                                }
                                else -> allSessions
                            }
                            itemsIndexed(displaySessions) { index, info ->
                                val isCurrent = info.session.id == currentSessionId
                                val sessionName by info.session.sessionName.collectAsState(initial = "")
                                ComposeTerminalCard(
                                    info = info,
                                    sessionNumber = index + 1,
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
                                        // 沙箱会话禁止重命名：清理逻辑以标题
                                        // 「沙箱会话」为锚点，改名会导致会话结束时
                                        // 影子空间（~95MB）无法被回收。
                                        val sandboxTitle = context.getString(R.string.vortex_sandbox_session_title)
                                        if (sessionName == sandboxTitle) return@ComposeTerminalCard
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

    // 页面失活（切页、滑动离开）时收起「更多」菜单：弹层经 onPopupContent 槽提升到
    // MainScreen 根层级渲染，若带着打开状态离开，会残留盖在切换后的页面上。
    LaunchedEffect(active) {
        if (!active) showMoreMenu = false
    }

    // 玻璃「更多」菜单：页面内容绘制在全局顶栏（MainScreen Scaffold 的 topBar 槽）之下，
    // 弹层直接挂在页面会被顶栏压住（首项被标题行和「+」盖住），故经 onPopupContent 槽
    // 提升到 MainScreen 根层级渲染，与 Agent 对话页「弹层挂根 Box」同效。
    // 宽屏/平板下页面 Box 相对组合根有 NavigationRail 偏移，而 anchor 的
    // containerBounds 是 boundsInRoot()（组合根坐标）；先把零尺寸测量点钉在页面
    // 原点，再把宿主 Box 平移回组合根原点，两个坐标系才能对齐（竖屏偏移为 0，是 no-op）。
    SideEffect {
        if (active) {
            onPopupContent {
                var popupHostOrigin by remember { mutableStateOf(IntOffset.Zero) }
                Box(
                    modifier = Modifier.onGloballyPositioned {
                        popupHostOrigin = IntOffset(
                            it.positionInRoot().x.roundToInt(),
                            it.positionInRoot().y.roundToInt()
                        )
                    }
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(-popupHostOrigin.x, -popupHostOrigin.y) }
                ) {
                    // backdrop 显式传 null：anchor 是 GlassIconButton，会经 GlassButtonSurface
                    // 发布自己的 surface（含折射材质），弹层直接用 anchor 的 surface，
                    // 读 LocalGlassTopAppBarBackdrop 在页面层恒为 null，传它只会埋錆弹。
                    GlassTransformPopup(
                        show = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        anchor = moreAnchor,
                        backdrop = null,
                        anchorContent = {
                            Icon(MiuixGlassIcons.More, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurface)
                        },
                        simplified = true,
                    ) {
                        GlassPopupItem(
                            text = stringResource(R.string.menu_open_sandbox_session),
                            icon = MiuixGlassIcons.Rocket,
                            onClick = { showMoreMenu = false; openSandboxSession() }
                        )
                        GlassPopupItem(
                            text = stringResource(R.string.menu_close_all_sessions),
                            icon = MiuixGlassIcons.Close,
                            onClick = { showMoreMenu = false; closeAllSessions() }
                        )
                        GlassPopupItem(
                            text = stringResource(R.string.menu_cleanup_uninitialized),
                            icon = MiuixGlassIcons.Clear,
                            onClick = { showMoreMenu = false; cleanupUninitializedSessions() }
                        )
                        GlassPopupItem(
                            text = stringResource(R.string.new_failsafe_session),
                            icon = MiuixGlassIcons.Add,
                            onClick = { showMoreMenu = false; newFailsafeSession() }
                        )
                        GlassPopupItem(
                            text = stringResource(R.string.menu_sort_sessions),
                            icon = MiuixGlassIcons.Sort,
                            onClick = { showMoreMenu = false; showSortDialog = true }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 终端卡片，1:1 复刻 Java 版 TerminalCard。
 *
 * 方形 aspectRatio(1f)，圆角 20dp，左上角终端图标+名称+状态，右下角重命名+停止按钮。
 */
@Composable
private fun ComposeTerminalCard(
    info: ComposeSessionManager.SessionInfo,
    sessionNumber: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onStop: () -> Unit,
    onRename: () -> Unit
) {
    val session = info.session
    val context = LocalContext.current
    val sessionName by session.sessionName.collectAsState(initial = "")
    val oscTitle by session.titleState.collectAsState(initial = null)

    // Compose TerminalSession pid 语义与 Java 版一致: 0=未初始化, >0=运行中, -1=已结束
    // pid 是普通字段不触发重组，收集 pidState 流保证状态（未初始化/运行中/已结束）实时刷新
    val sessionPid by session.pidState.collectAsState()
    val isDead = sessionPid == -1
    val isUninitialized = sessionPid == 0
    val displayName = when {
        sessionName.isNotEmpty() -> sessionName
        !oscTitle.isNullOrBlank() -> oscTitle!!
        info.name.isNotEmpty() -> info.name
        else -> context.getString(R.string.session_display_number, sessionNumber)
    }

    val titleColor = if (isDead) Color(0xFFFF5252) else MiuixTheme.colorScheme.onSurface
    // 当前会话高亮底色：暗色模式深灰，亮色模式亮灰白（图标等颜色不变）
    val currentHighlightColor = if (isSystemInDarkTheme()) Color(0xFF424242) else Color(0xFFE0E0E0)
    val statusText: String? = when {
        isDead && session.exitStatus > 0 -> context.getString(R.string.exit_code_session, session.exitStatus)
        isDead -> context.getString(R.string.ended)
        isUninitialized -> context.getString(R.string.uninitialized)
        sessionPid > 0 -> "PID $sessionPid"
        else -> null
    }

    val lastCommand by session.lastCommandState.collectAsState()
    val lastCommandText: String? = if (sessionPid > 0 && lastCommand.isNotEmpty())
        context.getString(R.string.last_command, lastCommand) else null

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
                    isCurrent -> currentHighlightColor
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
                    if (lastCommandText != null) {
                        Text(
                            text = lastCommandText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                            contentDescription = context.getString(R.string.dismiss),
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
