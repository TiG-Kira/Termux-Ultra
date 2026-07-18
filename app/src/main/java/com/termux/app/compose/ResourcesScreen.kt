package com.termux.app.compose

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import top.yukonga.miuix.kmp.basic.*
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
    val needsLinuxContainer: Boolean = false
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
            title = "QEMU 安装",
            description = "在 Linux 容器内安装 QEMU 虚拟机套件，包括 qemu-system-x86_64 和 qemu-utils",
            url = "",
            scriptUrl = "install_qemu",
            iconRes = R.drawable.ic_server,
            needsLinuxContainer = true
        ),
        ResourceItem(
            title = "Debian QEMU",
            description = "在 Termux 的 QEMU 中安装 Debian Linux 稳定发行版，支持 Docker",
            url = "",
            scriptUrl = "debian_qemu",
            iconRes = R.drawable.ic_server,
            type = "qemu_termux"
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
            scriptUrl = "https://raw.githubusercontent.com/TheRemote/MinecraftBedrockServer/master/SetupMinecraft.sh",
            iconRes = R.drawable.ic_game,
            needsLinuxContainer = true
        ),
        ResourceItem(
            title = context.getString(R.string.resource_linux_server),
            description = context.getString(R.string.resource_linux_server_desc),
            url = "https://github.com/teddysun/lamp",
            scriptUrl = "https://raw.githubusercontent.com/teddysun/lamp/master/lamp.sh",
            iconRes = R.drawable.ic_server,
            needsLinuxContainer = true
        ),
        ResourceItem(
            title = context.getString(R.string.resource_web_server),
            description = context.getString(R.string.resource_web_server_desc),
            url = "https://nginx.org/",
            scriptUrl = "https://raw.githubusercontent.com/angristan/nginx-autoinstall/master/nginx-autoinstall.sh",
            iconRes = R.drawable.ic_web,
            needsLinuxContainer = true
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
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
                Divider(color = Color.DarkGray)
                
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
                                color = surfaceVariantColor
                            )
                        ) {
                            Text(text = "说明", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor)
                        }
                    }

                    Button(
                        onClick = onToggleExpand,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary
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

            if (isExpanded) {
                Divider(color = Color.DarkGray)
                
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

                    Button(
                        onClick = { onExecuteInNewSession(baseCommand) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary
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
                            onClick = { onExecuteInTmux(baseCommand) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            colors = ButtonDefaults.buttonColors(
                                color = surfaceVariantColor
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
                                    onClick = { onExecuteInRunningSession(session.id, baseCommand) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp)),
                                    colors = ButtonDefaults.buttonColors(
                                        color = surfaceVariantColor
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
            val genisoimageDebPath = "/data/data/com.termux/files/home/genisoimage.deb"
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
                val inputStream = context.assets.open("genisoimage.deb")
                val outputStream = java.io.FileOutputStream(genisoimageDebPath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            "bash $setupScriptPath"
        }
        item.needsLinuxContainer -> {
            val containerScriptPath = "/data/data/com.termux/files/home/install_linux_container.sh"
            val runInContainerPath = "/data/data/com.termux/files/home/run_in_container.sh"
            try {
                val inputStream = context.assets.open("install_linux_container.sh")
                val outputStream = java.io.FileOutputStream(containerScriptPath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                java.io.File(containerScriptPath).setExecutable(true)
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
                "bash $containerScriptPath && bash $runInContainerPath $installScriptPath"
            } else {
                "bash $containerScriptPath && curl -sSL -o /data/data/com.termux/files/home/tmp_script.sh ${item.scriptUrl} && bash $runInContainerPath /data/data/com.termux/files/home/tmp_script.sh"
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
