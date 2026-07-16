package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val isTmux: Boolean = false
)

@Composable
fun ResourcesScreen(onExecuteScript: (String, String) -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    var showTmuxDialog by remember { mutableStateOf(false) }
    var pendingCommand by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showTmuxHelpDialog by remember { mutableStateOf(false) }

    val resources = listOf(
        ResourceItem(
            title = context.getString(R.string.tmux_resource_title),
            description = context.getString(R.string.tmux_resource_description),
            url = "tmux_help",
            scriptUrl = "pkg install tmux -y",
            iconRes = R.drawable.ic_terminal,
            isTmux = true
        ),
        ResourceItem(
            title = "MOE 全能",
            description = "TMOE Linux 管理器，一键配置 chroot/PRoot 容器、安装各种 Linux 发行版",
            url = "https://github.trss.me/Install/TMOE.html",
            scriptUrl = "https://gitee.com/mo2/linux/raw/2/2.awk",
            iconRes = R.drawable.ic_terminal
        ),
        ResourceItem(
            title = context.getString(R.string.resource_termux_setup),
            description = context.getString(R.string.resource_termux_setup_desc),
            url = "https://termux.dev/en/",
            scriptUrl = "https://raw.githubusercontent.com/termux/termux-packages/master/packages/bash/build.sh",
            iconRes = R.drawable.ic_terminal
        ),
        ResourceItem(
            title = context.getString(R.string.resource_minecraft_server),
            description = context.getString(R.string.resource_minecraft_server_desc),
            url = "https://github.com/TheRemote/MinecraftBedrockServer",
            scriptUrl = "https://raw.githubusercontent.com/TheRemote/MinecraftBedrockServer/master/SetupMinecraft.sh",
            iconRes = R.drawable.ic_game
        ),
        ResourceItem(
            title = context.getString(R.string.resource_linux_server),
            description = context.getString(R.string.resource_linux_server_desc),
            url = "https://github.com/teddysun/lamp",
            scriptUrl = "https://raw.githubusercontent.com/teddysun/lamp/master/lamp.sh",
            iconRes = R.drawable.ic_server
        ),
        ResourceItem(
            title = context.getString(R.string.resource_web_server),
            description = context.getString(R.string.resource_web_server_desc),
            url = "https://nginx.org/",
            scriptUrl = "https://raw.githubusercontent.com/angristan/nginx-autoinstall/master/nginx-autoinstall.sh",
            iconRes = R.drawable.ic_web
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
            url = "https://python-poetry.org/",
            scriptUrl = "https://install.python-poetry.org",
            iconRes = R.drawable.ic_code
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
                    onExecuteScript = onExecuteScript,
                    onShowTmuxDialog = { scriptName, command ->
                        pendingCommand = Pair(scriptName, command)
                        showTmuxDialog = true
                    },
                    onShowTmuxHelp = { showTmuxHelpDialog = true }
                )
            }
        }
    }

    if (showTmuxDialog) {
        AlertDialog(
            title = { Text(context.getString(R.string.tmux_not_installed)) },
            text = { Text(context.getString(R.string.tmux_install_hint)) },
            onDismissRequest = {
                showTmuxDialog = false
                pendingCommand = null
            },
            confirmButton = {
                Button(onClick = {
                    showTmuxDialog = false
                    pendingCommand = null
                }) {
                    Text(context.getString(R.string.ok))
                }
            }
        )
    }

    if (showTmuxHelpDialog) {
        AlertDialog(
            title = { Text("Tmux 使用办法") },
            text = {
                Column {
                    Text("新建任务容器：tmux new -s my_task （my_task 可以改为您的任务名，比如“挂脚本”）")
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
    onExecuteScript: (String, String) -> Unit,
    onShowTmuxDialog: (String, String) -> Unit,
    onShowTmuxHelp: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
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
                onClick = {
                    if (item.isTmux) {
                        onExecuteScript("tmux", item.scriptUrl)
                    } else {
                        val scriptName = item.scriptUrl.substringAfterLast("/")
                        val baseCommand = if (item.scriptUrl.endsWith(".awk")) {
                            "curl -sSL -o /data/data/com.termux/files/home/tmp_script ${item.scriptUrl} && awk -f /data/data/com.termux/files/home/tmp_script"
                        } else if (item.scriptUrl.endsWith(".py")) {
                            "curl -sSL -o /data/data/com.termux/files/home/tmp_script.py ${item.scriptUrl} && python /data/data/com.termux/files/home/tmp_script.py"
                        } else {
                            "curl -sSL -o /data/data/com.termux/files/home/tmp_script.sh ${item.scriptUrl} && bash /data/data/com.termux/files/home/tmp_script.sh"
                        }

                        if (isTmuxInstalled()) {
                            val tmuxCommand = "tmux new -s ${scriptName.replace(".", "_")} \"$baseCommand\""
                            onExecuteScript(scriptName, tmuxCommand)
                        } else {
                            onShowTmuxDialog(scriptName, baseCommand)
                        }
                    }
                },
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.primary
                )
            ) {
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
}

private fun isTmuxInstalled(): Boolean {
    val tmuxPath = "/data/data/com.termux/files/usr/bin/tmux"
    return java.io.File(tmuxPath).exists()
}