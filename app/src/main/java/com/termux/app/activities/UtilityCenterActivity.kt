package com.termux.app.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.termux.R
import com.termux.app.TermuxService
import com.termux.app.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Utility Center: Show officially maintained tools (QEMU with VNC, Debian QEMU, Ubuntu container, tmux, QEMU installation).
 */
class UtilityCenterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val appVM: com.termux.app.AppViewModel by viewModels()
        setContent {
            com.termux.app.compose.KiTerminalTheme {
                val context = this@UtilityCenterActivity
                val scrollBehavior = MiuixScrollBehavior()

                val utilityItems = remember {
                    listOf(
                        ResourceItem(
                            title = "QEMU with VNC",
                            description = "在 Termux 中通过 VNC 运行虚拟机，支持创建/导入磁盘，支持自定义 CPU、内存、CD-ROM、共享目录、引导顺序等",
                            url = "",
                            scriptUrl = "",
                            iconRes = R.drawable.ic_server,
                            type = "qemu_on_vnc",
                            requiredFeature = ApiCompat.Feature.QEMU_VM_MANAGER
                        ),
                        ResourceItem(
                            title = "Debian QEMU",
                            description = "在 Termux 的 QEMU 中安装 Debian Linux 稳定发行版，支持 Docker",
                            url = "",
                            scriptUrl = "debian_qemu",
                            iconRes = R.drawable.ic_server,
                            type = "qemu_termux",
                            needsContainerCheck = true,
                            requiredFeature = ApiCompat.Feature.DEBIAN_QEMU
                        ),
                        ResourceItem(
                            title = "Ubuntu 容器安装",
                            description = "安装 Ubuntu Linux 容器（PRoot），为 QEMU 和其他服务提供运行环境",
                            url = "",
                            scriptUrl = "install_debian_container",
                            iconRes = R.drawable.ic_ubuntu,
                            type = "install_debian_container"
                        ),
                        ResourceItem(
                            title = "tmux",
                            description = "在 tmux 中后台执行任务，防止终端关闭导致进程结束",
                            url = "tmux_help",
                            scriptUrl = "pkg install tmux -y",
                            iconRes = R.drawable.ic_terminal,
                            isTmux = true,
                            hasHelp = true
                        ),
                        ResourceItem(
                            title = "QEMU 安装",
                            description = "在 Linux 容器内安装 QEMU 虚拟机套件",
                            url = "",
                            scriptUrl = "install_qemu",
                            iconRes = R.drawable.ic_server,
                            type = "install_qemu_in_container",
                            needsContainerCheck = true,
                            requiredFeature = ApiCompat.Feature.QEMU_VM_MANAGER
                        )
                    )
                }

                var expandedCard by remember { mutableStateOf<String?>(null) }
                var showTmuxHelpDialog by remember { mutableStateOf(false) }
                var showQemuSheet by remember { mutableStateOf(false) }
                var sessions by remember { mutableStateOf<List<TerminalSession>>(emptyList()) }
                var termuxService by remember { mutableStateOf<TermuxService?>(null) }

                fun refreshSessions() {
                    sessions = getRunningSessions(context, termuxService)
                }

                val serviceConnection = remember {
                    object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            val binder = service as TermuxService.LocalBinder
                            termuxService = binder.service
                            refreshSessions()
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {
                            termuxService = null
                        }
                    }
                }

                DisposableEffect(Unit) {
                    val intent = Intent(context, TermuxService::class.java)
                    context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                    refreshSessions()
                    onDispose { context.unbindService(serviceConnection) }
                }

                LaunchedEffect(termuxService) {
                    while (true) {
                        kotlinx.coroutines.delay(3000)
                        refreshSessions()
                    }
                }

                val onExecuteScript: (String, String) -> Unit = { scriptName, command ->
                    val sessionName = scriptName
                    val newSession = termuxService?.createTermuxSession(
                        null,
                        arrayOf("-c", command),
                        null,
                        null,
                        false,
                        sessionName
                    )
                    refreshSessions()
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        kotlinx.coroutines.delay(500)
                        refreshSessions()
                    }
                    if (newSession != null) {
                        val intent = Intent(context, com.termux.app.TermuxActivity::class.java)
                        intent.putExtra("sessionHandle", newSession.getTerminalSession().mHandle)
                        startActivity(intent)
                    }
                }

                fun onExecuteInRunningSession(sessionId: String, command: String) {
                    try {
                        val allSessions = termuxService?.getTermuxSessions() ?: return
                        val targetSession = allSessions.find {
                            val ts = it.getTerminalSession()
                            ts.mHandle.toString() == sessionId || ts.mSessionName == sessionId
                        }
                        targetSession?.let { tsItem ->
                            val terminalSession = tsItem.getTerminalSession()
                            if (!terminalSession.isRunning) {
                                val intent = Intent(context, com.termux.app.TermuxActivity::class.java)
                                intent.putExtra("sessionHandle", terminalSession.mHandle)
                                startActivity(intent)
                                android.os.Handler().postDelayed({
                                    if (terminalSession.isRunning) {
                                        terminalSession.write(command + "\n")
                                    }
                                }, 2000)
                            } else {
                                terminalSession.write(command + "\n")
                                val intent = Intent(context, com.termux.app.TermuxActivity::class.java)
                                intent.putExtra("sessionHandle", terminalSession.mHandle)
                                startActivity(intent)
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }

                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        TopAppBar(
                            title = stringResource(R.string.utility_center),
                            scrollBehavior = scrollBehavior,
                            navigationIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .clickable { finish() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Back,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        )
                    }
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MiuixTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = stringResource(R.string.official_maintained),
                                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                    )
                                    Text(
                                        text = stringResource(R.string.official_maintained_desc),
                                        style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }

                        items(utilityItems) { item ->
                            ResourceCard(
                                item = item,
                                isExpanded = expandedCard == item.title,
                                hasRunningSessions = sessions.isNotEmpty(),
                                sessions = sessions,
                                onToggleExpand = {
                                    if (item.type == "qemu_on_vnc") {
                                        // 跳转到虚拟机列表管理页面（QemuVmActivity）
                                        val intent = Intent(context, QemuVmActivity::class.java)
                                        context.startActivity(intent)
                                    } else {
                                        expandedCard = if (expandedCard == item.title) null else item.title
                                    }
                                },
                                onExecuteInNewSession = { command: String ->
                                    onExecuteScript(item.title, command)
                                    expandedCard = null
                                    refreshSessions()
                                },
                                onExecuteInTmux = { command: String ->
                                    val tmuxName = item.title.replace(".", "_").replace(" ", "_")
                                    val tmuxCommand = "tmux new -s $tmuxName -d && tmux send-keys -t $tmuxName '$command' C-m && tmux attach -t $tmuxName"
                                    onExecuteScript(item.title, tmuxCommand)
                                    expandedCard = null
                                    refreshSessions()
                                },
                                onExecuteInRunningSession = { sessionId: String, command: String ->
                                    onExecuteInRunningSession(sessionId, command)
                                    expandedCard = null
                                    refreshSessions()
                                },
                                onShowTmuxHelp = { showTmuxHelpDialog = true }
                            )
                        }
                    }

                    // tmux help dialog — MUST be inside Scaffold so MiuixPopupHost renders it
                    OverlayDialog(
                        show = showTmuxHelpDialog,
                        onDismissRequest = { showTmuxHelpDialog = false },
                        title = stringResource(R.string.tmux_help_title),
                        content = {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = stringResource(R.string.tmux_help_new),
                                style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = stringResource(R.string.tmux_help_detach),
                                style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = stringResource(R.string.tmux_help_attach),
                                style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface),
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    text = stringResource(R.string.ok),
                                    onClick = { showTmuxHelpDialog = false }
                                )
                            }
                        }
                        }
                    )

                    QemuOnVncSheet(
                        show = showQemuSheet,
                        onDismiss = { showQemuSheet = false },
                        onExecuteScript = onExecuteScript
                    )
                }
            }
        }
    }
}
