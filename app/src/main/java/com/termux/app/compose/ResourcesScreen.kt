package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    var showSessionDialog by remember { mutableStateOf(false) }
    var pendingExecuteItem by remember { mutableStateOf<ResourceItem?>(null) }
    var pendingExecuteBaseCommand by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf<List<TerminalSession>>(emptyList()) }

    LaunchedEffect(Unit) {
        sessions = getRunningSessions()
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
            title = "Alpine QEMU",
            description = "在 Termux 的 QEMU 中安装 Alpine Linux 轻量级发行版",
            url = "",
            scriptUrl = "alpine_qemu",
            iconRes = R.drawable.ic_server,
            type = "qemu_termux"
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
                    onToggleExpand = {
                        expandedCard = if (expandedCard == item.title) null else item.title
                    },
                    onExecuteInNewSession = { command ->
                        onExecuteScript(item.title, command)
                        expandedCard = null
                    },
                    onExecuteInTmux = { command ->
                        val tmuxName = item.title.replace(".", "_").replace(" ", "_")
                        val tmuxCommand = "tmux new -s $tmuxName -d && tmux send-keys -t $tmuxName '$command' C-m && tmux attach -t $tmuxName"
                        onExecuteScript(item.title, tmuxCommand)
                        expandedCard = null
                    },
                    onExecuteInRunningSession = { command ->
                        pendingExecuteItem = item
                        pendingExecuteBaseCommand = command
                        sessions = getRunningSessions()
                        showSessionDialog = true
                        expandedCard = null
                    },
                    onShowTmuxHelp = { showTmuxHelpDialog = true }
                )
            }
        }
    }

    if (showSessionDialog && pendingExecuteItem != null) {
        AlertDialog(
            onDismissRequest = {
                showSessionDialog = false
                pendingExecuteItem = null
                pendingExecuteBaseCommand = ""
            },
            confirmButton = {
                Button(onClick = {
                    showSessionDialog = false
                    pendingExecuteItem = null
                    pendingExecuteBaseCommand = ""
                }) {
                    Text("取消")
                }
            },
            title = { Text("选择会话") },
            text = {
                Column(modifier = Modifier.heightIn(max = 300.dp)) {
                    if (sessions.isEmpty()) {
                        Text("没有正在运行的会话")
                    } else {
                        sessions.forEach { session ->
                            Text(
                                text = session.name,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable {
                                        onTypeInSession(session.id, pendingExecuteBaseCommand)
                                        showSessionDialog = false
                                        pendingExecuteItem = null
                                        pendingExecuteBaseCommand = ""
                                    },
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        )
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
    onToggleExpand: () -> Unit,
    onExecuteInNewSession: (String) -> Unit,
    onExecuteInTmux: (String) -> Unit,
    onExecuteInRunningSession: (String) -> Unit,
    onShowTmuxHelp: () -> Unit
) {
    val context = LocalContext.current
    val canUseTmux = isTmuxInstalled()
    val hasRunningSessions = getRunningSessions().isNotEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(item.iconRes),
                        contentDescription = item.title,
                        modifier = Modifier.size(28.dp),
                        tint = Color.White
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = item.title,
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = item.description,
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    )
                }

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
                            .clip(RoundedCornerShape(12.dp)),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(text = "说明", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = onToggleExpand,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.primary
                    )
                ) {
                    if (isExpanded) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_right),
                            contentDescription = "收起",
                            modifier = Modifier.size(18.dp),
                            tint = Color.White
                        )
                        Text(text = "收起", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_play),
                            contentDescription = context.getString(R.string.execute),
                            modifier = Modifier.size(18.dp),
                            tint = Color.White
                        )
                        Text(text = context.getString(R.string.execute), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clip(RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "选择执行方式",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
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
                                color = MiuixTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_terminal),
                                contentDescription = "tmux",
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                            Text(text = "在新会话执行 (tmux)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (hasRunningSessions) {
                        Button(
                            onClick = { onExecuteInRunningSession(baseCommand) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp)),
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_terminal),
                                contentDescription = "运行中",
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                            Text(text = "在运行的会话内执行", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            val genisoimagePath = "/data/data/com.termux/files/home/genisoimage"
            val distro = if (item.scriptUrl == "alpine_qemu") "alpine" else "debian"
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
                val inputStream = context.assets.open("genisoimage")
                val outputStream = java.io.FileOutputStream(genisoimagePath)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                java.io.File(genisoimagePath).setExecutable(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            "bash $setupScriptPath $distro"
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

private fun getRunningSessions(): List<TerminalSession> {
    return try {
        val process = Runtime.getRuntime().exec("tmux list-sessions -F '#{session_id} #{session_name}'")
        val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
        val sessions = mutableListOf<TerminalSession>()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val parts = line?.split(" ", limit = 2) ?: continue
            if (parts.size >= 2) {
                sessions.add(TerminalSession(parts[0], parts[1]))
            }
        }
        process.waitFor()
        sessions
    } catch (e: Exception) {
        emptyList()
    }
}
