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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.material3.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import com.termux.app.TermuxService
import com.termux.shared.shell.TermuxSession

@Composable
fun TerminalListScreen(
    sessions: List<TermuxSession>,
    onSessionClick: (TermuxSession) -> Unit,
    onNewTerminal: () -> Unit,
    onStopTerminal: (TermuxSession) -> Unit,
    onRenameTerminal: (TermuxSession, String) -> Unit,
    isWakeLockEnabled: Boolean,
    onToggleWakeLock: () -> Unit
) {
    val context = LocalContext.current
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameSession by remember { mutableStateOf<TermuxSession?>(null) }
    var newName by remember { mutableStateOf("") }
    var showWelcomeCard by remember { mutableStateOf(false) }
    var showKeepAliveWarning by remember { mutableStateOf(false) }
    var termuxService by remember { mutableStateOf<TermuxService?>(null) }

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
                        Spacer(modifier = Modifier.width(8.dp))
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                ServiceStatusCard(isRunning = termuxService != null, isWakeLockActive = isWakeLockEnabled)
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
                            fontSize = 16.sp,
                            color = Color.Gray
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

    if (showRenameDialog && renameSession != null) {
        AlertDialog(
            title = { Text(stringResource(R.string.rename_terminal)) },
            onDismissRequest = { showRenameDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        renameSession?.let { session ->
                            onRenameTerminal(session, newName)
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            text = {
                Column {
                    TextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.terminal_name)) }
                    )
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
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (isSystemInDarkTheme()) Color(0xFF1A1A1A) else Color.White)
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
                Icon(
                    painter = painterResource(R.drawable.ic_terminal),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = session.getTerminalSession().mSessionName ?: stringResource(R.string.terminal),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.primary)
                        .clickable(onClick = onRename),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.rename),
                        modifier = Modifier.size(20.dp),
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.primary)
                        .clickable(onClick = onStop),
                    contentAlignment = Alignment.Center
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
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(all = 16.dp)
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
                    color = textColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.keep_alive_warning_message),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
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
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(all = 16.dp)
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
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
            }
        }
    }
}

@Composable
fun ServiceStatusCard(isRunning: Boolean, isWakeLockActive: Boolean) {
    val isDark = isSystemInDarkTheme()
    val (cardColor, iconColor) = if (isRunning) {
        Pair(if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4), Color(0xFF36D167))
    } else {
        Pair(if (isDark) Color(0xFF3B1414) else Color(0xFFFFEBEE), Color(0xFFFF5252))
    }
    val textColor = if (isDark) Color.White else Color.Black
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(35.dp, 35.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Icon(
                    modifier = Modifier.size(120.dp).alpha(0.8f),
                    imageVector = if (isRunning) Icons.Rounded.CheckCircleOutline 
                        else Icons.Rounded.ErrorOutline,
                    tint = iconColor,
                    contentDescription = null
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(all = 16.dp)
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = if (isRunning) {
                        if (isWakeLockActive) "Termux 服务正常 (已激活唤醒锁)" else "Termux 服务正常"
                    } else "Termux 服务异常",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = if (isRunning) "当前服务正常，会话可以保持运行" else "当前服务被退出，若您有正在进行的进程，运行状态可能会丢失",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
            }
        }
    }
}