package com.termux.app.compose

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as Material3Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun TerminalListScreen(
    sessions: List<TermuxSession>,
    onSessionClick: (TermuxSession) -> Unit,
    onNewTerminal: () -> Unit,
    onStopTerminal: (TermuxSession) -> Unit,
    onRenameTerminal: (TermuxSession, String) -> Unit,
    isWakeLockEnabled: Boolean,
    onToggleWakeLock: () -> Unit,
    onRefresh: () -> Unit = {}
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

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("terminal_welcome_shown", false)) {
            showWelcomeCard = true
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
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
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
            if (showWelcomeCard) {
                item(span = { GridItemSpan(2) }) {
                    WelcomeCard(
                        text = stringResource(R.string.terminal_welcome_message),
                        onClose = {
                            showWelcomeCard = false
                            val prefs = context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("terminal_welcome_shown", true).apply()
                        }
                    )
                }
            }

            if (showKeepAliveWarning) {
                item(span = { GridItemSpan(2) }) {
                    KeepAliveWarningCard(
                        onClose = {
                            showKeepAliveWarning = false
                            val prefs = context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("keep_alive_warning_dismissed", true).apply()
                        }
                    )
                }
            }

            item(span = { GridItemSpan(2) }) {
                val serviceStatus = remember(termuxService, isWakeLockEnabled, killedSessionName) {
                    when {
                        termuxService?.isMemoryKillActive() == true -> ServiceStatus.MEMORY_KILL
                        termuxService?.isMemoryWarningActive() == true -> ServiceStatus.MEMORY_WARNING
                        killedSessionName != null -> ServiceStatus.SESSION_KILLED
                        termuxService == null -> ServiceStatus.SERVICE_STOPPED
                        isWakeLockEnabled -> ServiceStatus.WAKE_LOCK_ACTIVE
                        else -> ServiceStatus.NORMAL
                    }
                }
                ServiceStatusCard(status = serviceStatus, killedSessionName = killedSessionName)
            }

            if (sessions.isEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 100.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
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
                items(sessions) { session ->
                    TerminalCard(
                        session = session,
                        onClick = { onSessionClick(session) },
                        onStop = { onStopTerminal(session) },
                        onRename = {
                            renameSession = session
                            newName = session.getTerminalSession().mSessionName ?: ""
                            showRenameDialog = true
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

@Composable
private fun TerminalCard(
    session: TermuxSession,
    onClick: () -> Unit,
    onStop: () -> Unit,
    onRename: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                        .background(MiuixTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_terminal),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = session.getTerminalSession().mSessionName ?: stringResource(R.string.terminal),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    lineHeight = 22.sp
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
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

@Composable
fun KeepAliveWarningCard(onClose: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) Color(0xFF3D3514) else Color(0xFFFFF9C4)
    val iconColor = Color(0xFFFDD835)
    val textColor = if (isDark) Color.White else Color.Black

    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(30.dp, 60.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Material3Icon(
                    modifier = Modifier.size(120.dp).alpha(0.8f),
                    imageVector = Icons.Rounded.Warning,
                    tint = iconColor,
                    contentDescription = null
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
        }
    }
}

@Composable
fun WelcomeCard(text: String, onClose: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) Color(0xFF1A1A1A) else Color.White
    val iconColor = if (isDark) Color(0xFF666666) else Color(0xFFCCCCCC)
    val textColor = if (isDark) Color.White else Color.Black

    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(30.dp, 30.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Material3Icon(
                    modifier = Modifier.size(120.dp).alpha(0.8f),
                    imageVector = Icons.Rounded.Info,
                    tint = iconColor,
                    contentDescription = null
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
    killedSessionName: String? = null
) {
    val isDark = isSystemInDarkTheme()
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
    
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(35.dp, 35.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Material3Icon(
                    modifier = Modifier.size(120.dp).alpha(0.8f),
                    imageVector = icon,
                    tint = iconColor,
                    contentDescription = null
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
        }
    }
}