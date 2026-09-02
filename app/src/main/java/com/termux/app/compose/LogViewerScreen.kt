package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.app.utils.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val logsClearedMessage = stringResource(R.string.logs_cleared)
    val noLogsToClearMessage = stringResource(R.string.no_logs_to_clear)

    var selectedLevel by remember { mutableStateOf<Int?>(null) }
    var logs by remember { mutableStateOf<List<LogManager.LogEntry>>(emptyList()) }
    var showClearDialog by remember { mutableStateOf(false) }
    var lastFileModTime by remember { mutableStateOf(0L) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    // 防抖后的搜索词（用户停止输入 300ms 后才更新）
    var debouncedSearchQuery by remember { mutableStateOf("") }

    val logManager = remember { LogManager.getInstance() }

    val loadLogs: suspend () -> Unit = {
        val newLogs = withContext(Dispatchers.IO) {
            if (selectedLevel == null) {
                logManager.allLogs
            } else {
                logManager.getLogsByLevel(selectedLevel!!)
            }
        }
        logs = newLogs
    }

    // 防抖：searchQuery 停止变化 300ms 后才更新 debouncedSearchQuery
    LaunchedEffect(searchQuery) {
        if (searchQuery.isEmpty()) {
            debouncedSearchQuery = ""
        } else {
            delay(300)
            debouncedSearchQuery = searchQuery
        }
    }

    // 使用 derivedStateOf 包装过滤，让 Compose 仅在真正影响结果的状态变化时才重算
    val filteredLogs by remember {
        derivedStateOf {
            val query = debouncedSearchQuery.trim().lowercase()
            if (query.isEmpty()) {
                logs
            } else {
                logs.filter { entry ->
                    entry.message.contains(query, ignoreCase = true) ||
                    entry.tag.contains(query, ignoreCase = true)
                }
            }
        }
    }

    LaunchedEffect(selectedLevel) {
        loadLogs()
    }

    DisposableEffect(Unit) {
        logManager.startLogcatCollection()
        onDispose {
            logManager.stopLogcatCollection()
        }
    }

    // 智能刷新：每 2 秒检查文件修改时间，仅当文件变更时才重新解析
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            val currentModTime = logManager.logFileLastModified
            if (currentModTime != lastFileModTime) {
                lastFileModTime = currentModTime
                loadLogs()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                SnackbarHost(state = snackbarHostState)
            }
        },
        topBar = {
            if (showSearchBar) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surface)
                        .padding(top = 48.dp, bottom = 8.dp, start = 8.dp, end = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable {
                                    showSearchBar = false
                                    searchQuery = ""
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = "搜索日志内容或标签...",
                            useLabelAsPlaceholder = true,
                            singleLine = true
                        )
                        if (searchQuery.isNotEmpty()) {
                            Spacer(Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable { searchQuery = "" },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_clear),
                                    contentDescription = "清除搜索",
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "找到 ${filteredLogs.size} 条结果",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
                    }
                }
            } else {
                TopAppBar(
                    title = stringResource(R.string.log_management),
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { onBack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // 搜索图标按钮
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .clickable { showSearchBar = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_search),
                                    contentDescription = "搜索",
                                    tint = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            // 清除日志图标按钮
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .clickable { showClearDialog = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = stringResource(R.string.clear_logs),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LogFilterBar(
                    selectedLevel = selectedLevel,
                    onLevelSelected = { selectedLevel = it }
                )

                if (filteredLogs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (logs.isEmpty()) stringResource(R.string.no_logs) else "没有匹配的日志",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 16.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        itemsIndexed(
                            items = filteredLogs,
                            key = { index, entry -> "log_${index}_${entry.timestamp}_${entry.message.hashCode()}" }
                        ) { index, logEntry ->
                            LogItem(logEntry = logEntry)
                        }
                    }
                }
            }

            if (showClearDialog) {
                OverlayDialog(
                    show = showClearDialog,
                    onDismissRequest = { showClearDialog = false },
                    title = stringResource(R.string.confirm_clear_logs),
                    summary = stringResource(R.string.confirm_clear_logs_message),
                    content = {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showClearDialog = false }
                        )
                        TextButton(
                            text = stringResource(R.string.confirm),
                            onClick = {
                                scope.launch {
                                    logManager.stopLogcatCollection()
                                    val cleared = logManager.clearLogs()
                                    showClearDialog = false
                                    if (cleared) {
                                        lastFileModTime = 0L
                                        logs = emptyList()
                                        snackbarHostState.showSnackbar(
                                            message = logsClearedMessage,
                                            duration = SnackbarDuration.Short
                                        )
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            message = noLogsToClearMessage,
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                    logManager.startLogcatCollection()
                                }
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun LogFilterBar(
    selectedLevel: Int?,
    onLevelSelected: (Int?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            text = stringResource(R.string.all),
            selected = selectedLevel == null,
            onClick = { onLevelSelected(null) },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            text = stringResource(R.string.log_level_info),
            selected = selectedLevel == LogManager.LEVEL_INFO,
            onClick = { onLevelSelected(LogManager.LEVEL_INFO) },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            text = stringResource(R.string.log_level_warning),
            selected = selectedLevel == LogManager.LEVEL_WARNING,
            onClick = { onLevelSelected(LogManager.LEVEL_WARNING) },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            text = stringResource(R.string.log_level_exception),
            selected = selectedLevel == LogManager.LEVEL_EXCEPTION,
            onClick = { onLevelSelected(LogManager.LEVEL_EXCEPTION) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(
                if (selected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) MiuixTheme.colorScheme.onPrimary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun LogItem(
    logEntry: LogManager.LogEntry
) {
    val levelColor = when (logEntry.level) {
        LogManager.LEVEL_INFO -> Color(0xFF4CAF50)
        LogManager.LEVEL_WARNING -> Color(0xFFFFC107)
        LogManager.LEVEL_EXCEPTION -> Color(0xFFF44336)
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = logEntry.formattedTime,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
                Box(
                    modifier = Modifier
                        .background(
                            levelColor.copy(alpha = 0.2f),
                            CircleShape
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = when (logEntry.level) {
                            LogManager.LEVEL_INFO -> stringResource(R.string.log_level_info)
                            LogManager.LEVEL_WARNING -> stringResource(R.string.log_level_warning)
                            LogManager.LEVEL_EXCEPTION -> stringResource(R.string.log_level_exception)
                            else -> logEntry.levelString
                        },
                        color = levelColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = logEntry.tag,
                color = MiuixTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = logEntry.message,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            )
        }
    }
}
