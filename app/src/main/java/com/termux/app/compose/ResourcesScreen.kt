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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.material3.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import com.termux.app.TermuxService

data class ResourceItem(
    val title: String,
    val description: String,
    val url: String,
    val scriptUrl: String,
    val iconRes: Int,
    val isTmux: Boolean = false,
    val hasHelp: Boolean = false,
    val type: String = "default",
    val needsLinuxContainer: Boolean = false,
    val needsContainerCheck: Boolean = false,
    val copyToClipboard: Boolean = false
)

data class TerminalSession(val id: String, val name: String)

@Composable
fun ResourcesScreen(onExecuteScript: (String, String) -> Unit, onTypeInSession: (String, String) -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    var expandedCard by remember { mutableStateOf<String?>(null) }
    var showTmuxHelpDialog by remember { mutableStateOf(false) }
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
        onDispose {
            context.unbindService(serviceConnection)
        }
    }

    LaunchedEffect(termuxService) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            refreshSessions()
        }
    }

    val resources = listOf(
        ResourceItem(
            title = context.getString(R.string.tmux_resource_title),
            description = context.getString(R.string.tmux_resource_description),
            url = "tmux_help",
            scriptUrl = "pkg install tmux -y",
            iconRes = R.drawable.ic_terminal,
            isTmux = true,
            hasHelp = true
        ),
        ResourceItem(
            title = "MOE 全能",
            description = "TMOE Linux 管理器，一键配置 chroot/PRoot 容器、安装各种 Linux 发行版",
            url = "https://github.trss.me/Install/TMOE.html",
            scriptUrl = "https://gitee.com/mo2/linux/raw/2/2.awk",
            iconRes = R.drawable.ic_terminal
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
            title = "QEMU 安装",
            description = "在 Linux 容器内安装 QEMU 虚拟机套件，包括 qemu-system-x86_64、qemu-utils 和 genisoimage",
            url = "",
            scriptUrl = "install_qemu",
            iconRes = R.drawable.ic_server,
            type = "install_qemu_in_container",
            needsContainerCheck = true
        ),
        ResourceItem(
            title = "Debian QEMU",
            description = "在 Termux 的 QEMU 中安装 Debian Linux 稳定发行版，支持 Docker",
            url = "",
            scriptUrl = "debian_qemu",
            iconRes = R.drawable.ic_server,
            type = "qemu_termux",
            needsContainerCheck = true
        ),
        ResourceItem(
            title = "Windows 7 QEMU",
            description = "在 QEMU 中运行 Windows 7，需先下载镜像文件",
            url = "",
            scriptUrl = "win7_qemu",
            iconRes = R.drawable.ic_server
        ),
        ResourceItem(
            title = context.getString(R.string.resource_minecraft_server),
            description = context.getString(R.string.resource_minecraft_server_desc),
            url = "https://github.com/TheRemote/MinecraftBedrockServer",
            scriptUrl = "curl -sSL https://raw.githubusercontent.com/TheRemote/MinecraftBedrockServer/master/SetupMinecraft.sh | bash",
            iconRes = R.drawable.ic_game,
            needsLinuxContainer = true,
            needsContainerCheck = true,
            copyToClipboard = true
        ),
        ResourceItem(
            title = context.getString(R.string.resource_linux_server),
            description = context.getString(R.string.resource_linux_server_desc),
            url = "https://github.com/teddysun/lamp",
            scriptUrl = "curl -sSL https://raw.githubusercontent.com/teddysun/lamp/master/lamp.sh | bash",
            iconRes = R.drawable.ic_server,
            needsLinuxContainer = true,
            needsContainerCheck = true,
            copyToClipboard = true
        ),
        ResourceItem(
            title = context.getString(R.string.resource_web_server),
            description = context.getString(R.string.resource_web_server_desc),
            url = "https://nginx.org/",
            scriptUrl = "curl -sSL https://raw.githubusercontent.com/angristan/nginx-autoinstall/master/nginx-autoinstall.sh | bash",
            iconRes = R.drawable.ic_web,
            needsLinuxContainer = true,
            needsContainerCheck = true,
            copyToClipboard = true
        ),
        ResourceItem(
            title = context.getString(R.string.resource_node_js),
            description = context.getString(R.string.resource_node_js_desc),
            url = "https://nodejs.org/",
            scriptUrl = "https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh",
            iconRes = R.drawable.ic_code
        ),
        ResourceItem(
            title = context.getString(R.string.resource_python_env),
            description = context.getString(R.string.resource_python_env_desc),
            url = "https://www.python.org/",
            scriptUrl = "pkg install python -y",
            iconRes = R.drawable.ic_code,
            type = "python_pkg"
        )
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = context.getString(R.string.resources), scrollBehavior = scrollBehavior)
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
                PersistentHintCard()
            }
            
            items(resources) { item ->
                ResourceCard(
                    item = item,
                    isExpanded = expandedCard == item.title,
                    hasRunningSessions = sessions.isNotEmpty(),
                    sessions = sessions,
                    onToggleExpand = {
                        expandedCard = if (expandedCard == item.title) null else item.title
                    },
                    onExecuteInNewSession = { command ->
                        onExecuteScript(item.title, command)
                        expandedCard = null
                        refreshSessions()
                    },
                    onExecuteInTmux = { command ->
                        val tmuxName = item.title.replace(".", "_").replace(" ", "_")
                        val tmuxCommand = "tmux new -s $tmuxName -d && tmux send-keys -t $tmuxName '$command' C-m && tmux attach -t $tmuxName"
                        onExecuteScript(item.title, tmuxCommand)
                        expandedCard = null
                        refreshSessions()
                    },
                    onExecuteInRunningSession = { sessionId, command ->
                        onTypeInSession(sessionId, command)
                        expandedCard = null
                        refreshSessions()
                    },
                    onShowTmuxHelp = { showTmuxHelpDialog = true }
                )
            }
        }
    }

    if (showTmuxHelpDialog) {
        AlertDialog(
            title = { Text("Tmux 使用办法") },
            text = {
                Column {
                    Text("新建任务容器：tmux new -s my_task （my_task 可以改为您的任务名，比如「挂脚本」）")
                    Text("容器挂到后台：按「Ctrl + B」，再按「D」，就能关掉窗口，后台继续运行脚本。")
                    Text("回到任务容器：tmux attach -t my_task")
                }
            },
            onDismissRequest = {
                showTmuxHelpDialog = false
            },
            confirmButton = {
                Button(onClick = {
                    showTmuxHelpDialog = false
                }) {
                    Text(context.getString(R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun ResourceCard(
    item: ResourceItem,
    isExpanded: Boolean,
    hasRunningSessions: Boolean,
    sessions: List<TerminalSession>,
    onToggleExpand: () -> Unit,
    onExecuteInNewSession: (String) -> Unit,
    onExecuteInTmux: (String) -> Unit,
    onExecuteInRunningSession: (String, String) -> Unit,
    onShowTmuxHelp: () -> Unit
) {
    val context = LocalContext.current
    val canUseTmux = isTmuxInstalled()
    val onSurfaceColor = MiuixTheme.colorScheme.onSurface
    val surfaceVariantColor = MiuixTheme.colorScheme.surfaceVariant
    val dividerColor = onSurfaceColor.copy(alpha = 0.15f)
    val isDark = isSystemInDarkTheme()
    val cardBackgroundColor = if (isDark) Color(0xFF1A1A1A) else Color.White

    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = cardBackgroundColor)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(item.iconRes),
                        contentDescription = item.title,
                        modifier = Modifier.size(24.dp),
                        tint = Color.White
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = item.title,
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor
                        ),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Text(
                        text = item.description,
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    )
                }
            }

            if (item.url.isNotEmpty() || item.scriptUrl.isNotEmpty()) {
                Divider(color = dividerColor)
                
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.url.isNotEmpty()) {
                        Button(
                            onClick = {
                                if (item.url == "tmux_help") {
                                    onShowTmuxHelp()
                                } else {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(item.url)
                                    )
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFF424242) else Color(0xFFE0E0E0)
                            )
                        ) {
                            Text(text = "说明", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor)
                        }
                    }

                    if (item.copyToClipboard) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("执行指令", item.scriptUrl)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "指令已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MiuixTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = "复制指令",
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Text(text = "复制指令", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = onToggleExpand,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MiuixTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_play),
                                contentDescription = context.getString(R.string.execute),
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Text(text = context.getString(R.string.execute), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            if (isExpanded && !item.copyToClipboard) {
                Divider(color = dividerColor)
                
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clip(RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "选择执行方式",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor
                        ),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    val baseCommand = resolveCommand(item, context)

                    fun checkContainerAndExecute(execute: () -> Unit) {
                        if (item.needsContainerCheck) {
                            val containerDir = "/data/data/com.termux/files/home/debian-container"
                            val runScript = java.io.File("$containerDir/run.sh")
                            val rootfsBash = java.io.File("$containerDir/rootfs/bin/bash")
                            if (!runScript.exists() || !rootfsBash.exists()) {
                                android.widget.Toast.makeText(
                                    context,
                                    "请先安装 Ubuntu 容器！请到资源页点击\"Ubuntu 容器安装\"",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                return
                            }
                        }
                        execute()
                    }

                    Button(
                        onClick = { checkContainerAndExecute { onExecuteInNewSession(baseCommand) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MiuixTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_terminal),
                            contentDescription = "新会话",
                            modifier = Modifier.size(18.dp),
                            tint = Color.White
                        )
                        Text(text = "在新会话执行", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    if (canUseTmux) {
                        Button(
                            onClick = { checkContainerAndExecute { onExecuteInTmux(baseCommand) } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = surfaceVariantColor
                            )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_terminal),
                                contentDescription = "tmux",
                                modifier = Modifier.size(18.dp),
                                tint = onSurfaceColor
                            )
                            Text(text = "在新会话执行 (tmux)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor)
                        }
                    }

                    if (hasRunningSessions) {
                        Text(
                            text = "在运行的会话内执行:",
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            ),
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            sessions.forEach { session ->
                                Button(
                                    onClick = { checkContainerAndExecute { onExecuteInRunningSession(session.id, baseCommand) } },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp)),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = surfaceVariantColor
                                    )
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_terminal),
                                        contentDescription = session.name,
                                        modifier = Modifier.size(18.dp),
                                        tint = onSurfaceColor
                                    )
                                    Text(text = "复制到 \"${session.name}\"", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolveCommand(item: ResourceItem, context: android.content.Context): String {
    return when {
        item.isTmux -> item.scriptUrl
        item.type == "python_pkg" -> item.scriptUrl
        item.type == "install_debian_container" -> {
            val scriptPath = "/data/data/com.termux/files/home/install_linux_container.sh"
            try {
                val inputStream = context.assets.open("install_linux_container.sh")
                val outputStream = java.io.FileOutputStream(scriptPath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                java.io.File(scriptPath).setExecutable(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            "bash $scriptPath"
        }
        item.type == "install_qemu_in_container" -> {
            val containerDir = "/data/data/com.termux/files/home/debian-container"
            val runScript = "$containerDir/run.sh"
            val installScriptPath = "/data/data/com.termux/files/home/install_qemu.sh"
            val runInContainerPath = "/data/data/com.termux/files/home/run_in_container.sh"
            try {
                val inputStream = context.assets.open("install_qemu.sh")
                val outputStream = java.io.FileOutputStream(installScriptPath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                java.io.File(installScriptPath).setExecutable(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                val inputStream = context.assets.open("run_in_container.sh")
                val outputStream = java.io.FileOutputStream(runInContainerPath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                java.io.File(runInContainerPath).setExecutable(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            "bash $runInContainerPath $installScriptPath"
        }
        item.scriptUrl == "win7_qemu" -> {
            val scriptPath = "/data/data/com.termux/files/home/win7_qemu.sh"
            val patchPath = "/data/data/com.termux/files/home/win7_patch.zip"
            try {
                val inputStream = context.assets.open("win7_qemu.sh")
                val outputStream = java.io.FileOutputStream(scriptPath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                java.io.File(scriptPath).setExecutable(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                val patchStream = context.assets.open("win7_patch.zip")
                val patchOutputStream = java.io.FileOutputStream(patchPath)
                patchStream.copyTo(patchOutputStream)
                patchStream.close()
                patchOutputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            "bash $scriptPath"
        }
        item.type == "qemu_termux" -> {
            val setupScriptPath = "/data/data/com.termux/files/home/qemu_termux_setup.sh"
            val genSeedIsoPath = "/data/data/com.termux/files/home/gen_seed_iso.sh"
            try {
                val inputStream = context.assets.open("qemu_termux_setup.sh")
                val outputStream = java.io.FileOutputStream(setupScriptPath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                java.io.File(setupScriptPath).setExecutable(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                val inputStream = context.assets.open("gen_seed_iso.sh")
                val outputStream = java.io.FileOutputStream(genSeedIsoPath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                java.io.File(genSeedIsoPath).setExecutable(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            "bash $setupScriptPath"
        }
        item.needsLinuxContainer && !item.copyToClipboard -> {
            val runInContainerPath = "/data/data/com.termux/files/home/run_in_container.sh"
            try {
                val inputStream = context.assets.open("run_in_container.sh")
                val outputStream = java.io.FileOutputStream(runInContainerPath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                java.io.File(runInContainerPath).setExecutable(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                val runShPath = "/data/data/com.termux/files/home/container_run.sh"
                val inputStream = context.assets.open("container_run.sh")
                val outputStream = java.io.FileOutputStream(runShPath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                java.io.File(runShPath).setExecutable(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                val resolvPath = "/data/data/com.termux/files/home/resolv.conf"
                val inputStream = context.assets.open("resolv.conf")
                val outputStream = java.io.FileOutputStream(resolvPath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (item.scriptUrl == "install_qemu") {
                val installScriptPath = "/data/data/com.termux/files/home/install_qemu.sh"
                try {
                    val inputStream = context.assets.open("install_qemu.sh")
                    val outputStream = java.io.FileOutputStream(installScriptPath)
                    inputStream.copyTo(outputStream)
                    inputStream.close()
                    outputStream.close()
                    java.io.File(installScriptPath).setExecutable(true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                "bash $runInContainerPath $installScriptPath"
            } else {
                "curl -sSL -o /data/data/com.termux/files/home/tmp_script.sh ${item.scriptUrl} && bash $runInContainerPath /data/data/com.termux/files/home/tmp_script.sh"
            }
        }
        else -> {
            if (item.scriptUrl.endsWith(".awk")) {
                "curl -sSL -o /data/data/com.termux/files/home/tmp_script ${item.scriptUrl} && awk -f /data/data/com.termux/files/home/tmp_script"
            } else if (item.scriptUrl.endsWith(".py")) {
                "curl -sSL -o /data/data/com.termux/files/home/tmp_script.py ${item.scriptUrl} && python /data/data/com.termux/files/home/tmp_script.py"
            } else {
                "curl -sSL -o /data/data/com.termux/files/home/tmp_script.sh ${item.scriptUrl} && bash /data/data/com.termux/files/home/tmp_script.sh"
            }
        }
    }
}

private fun isTmuxInstalled(): Boolean {
    val tmuxPath = "/data/data/com.termux/files/usr/bin/tmux"
    return java.io.File(tmuxPath).exists()
}

private fun getRunningSessions(context: Context, termuxService: TermuxService?): List<TerminalSession> {
    return if (termuxService != null) {
        try {
            val sessions = termuxService.getTermuxSessions()
            sessions.map {
                TerminalSession(it.getTerminalSession().mHandle, it.getTerminalSession().mSessionName ?: "Terminal")
            }
        } catch (e: Exception) {
            emptyList()
        }
    } else {
        emptyList()
    }
}

@Composable
fun PersistentHintCard() {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) Color(0xFF2C2C2C) else Color(0xFFF0F0F0)
    val iconColor = if (isDark) Color(0xFF666666) else Color(0xFFCCCCCC)
    val textColor = if (isDark) Color.White else Color.Black
    
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(30.dp, 65.dp),
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
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = "欢迎访问 Termux Ultra 资源中心",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = "资源中心内资源部分来自于第三方仓库，来自于第三方仓库的资源不在 Termux Ultra 项目的维护范围之内。另请注意，部分资源需要特定容器或环境下才可运行。资源中心为此类资源提供执行指令复制功能，您需要自行返回终端粘贴命令来执行。",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
            }
        }
    }
}
