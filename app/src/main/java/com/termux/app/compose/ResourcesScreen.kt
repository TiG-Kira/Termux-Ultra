package com.termux.app.compose

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ExpandLess
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
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
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
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
    val copyToClipboard: Boolean = false,
    val fallbackScriptUrl: String = "",
    /** 该条目所需的最低 API 功能；为 null 表示无版本限制。不可用时按钮变灰+提示。 */
    val requiredFeature: ApiCompat.Feature? = null
)

data class TerminalSession(val id: String, val name: String)

@Composable
fun ResourcesScreen() {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = stringResource(R.string.resources_center), scrollBehavior = scrollBehavior)
        },
        content = { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = 92.dp)
            ) {
                item {
                    HeroWelcomeCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                item {
                    SmallTitle(
                        text = stringResource(R.string.resource_quick_entry),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        EntryCard(
                            title = stringResource(R.string.utility_center),
                            subtitle = stringResource(R.string.official_maintained_short),
                            iconRes = R.drawable.ic_server,
                            iconBackground = MiuixTheme.colorScheme.primary.copy(alpha = 0.15f),
                            iconTint = MiuixTheme.colorScheme.primary,
                            accentColor = MiuixTheme.colorScheme.primary,
                            onClick = {
                                val intent = Intent(context, com.termux.app.activities.UtilityCenterActivity::class.java)
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        EntryCard(
                            title = stringResource(R.string.third_party_center),
                            subtitle = stringResource(R.string.third_party_maintained_short),
                            iconRes = R.drawable.ic_code,
                            iconBackground = Color(0xFF7C4DFF).copy(alpha = 0.15f),
                            iconTint = Color(0xFF7C4DFF),
                            accentColor = Color(0xFF7C4DFF),
                            onClick = {
                                val intent = Intent(context, com.termux.app.activities.ThirdPartyCenterActivity::class.java)
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    SmallTitle(text = stringResource(R.string.resource_tips))
                }

                item {
                    WarningNoteCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    )
}

@Composable
fun ResourceCard(
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
    val cardBackgroundColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFFAFAFA)

    // 检查该条目是否因 Android 版本过低而被屏蔽（综合静态判断与用户强制启用持久化）
    var forceEnabled by remember {
        mutableStateOf(
            item.requiredFeature != null && ApiCompat.isFeatureForceEnabled(context, item.requiredFeature)
        )
    }
    val isFeatureDisabled = item.requiredFeature != null &&
        !ApiCompat.isAvailable(item.requiredFeature) && !forceEnabled
    val disabledButtonColor = if (isDark) Color(0xFF424242) else Color(0xFFBDBDBD)
    val disabledTextColor = if (isDark) Color(0xFF757575) else Color(0xFF9E9E9E)

    // 强制启用确认弹窗状态
    var showForceEnableDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun showDisabledDialog(action: () -> Unit) {
        if (item.requiredFeature != null) {
            pendingAction = action
            showForceEnableDialog = true
        }
    }

    MiuixCard(
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

            if (item.url.isNotEmpty() || item.scriptUrl.isNotEmpty() || item.type == "qemu_on_vnc" || item.hasHelp) {
                HorizontalDivider(color = dividerColor)
                
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
                                color = if (isDark) Color(0xFF424242) else Color(0xFFE0E0E0)
                            )
                        ) {
                            Text(text = "说明", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor)
                        }
                    }

                    if (item.copyToClipboard) {
                        Button(
                            onClick = {
                                if (isFeatureDisabled) { showDisabledDialog {}; return@Button }
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("执行指令", item.scriptUrl)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "指令已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                            colors = ButtonDefaults.buttonColors(
                                color = if (isFeatureDisabled) disabledButtonColor else MiuixTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = "复制指令",
                                modifier = Modifier.size(16.dp),
                                tint = if (isFeatureDisabled) disabledTextColor else Color.White
                            )
                            Text(text = "复制指令", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isFeatureDisabled) disabledTextColor else Color.White)
                        }
                    } else {
                        val isConfigType = item.type == "qemu_on_vnc"
                        val buttonText = if (isExpanded) "收起" else if (isConfigType) "配置" else context.getString(R.string.execute)
                        Button(
                            onClick = {
                                if (isFeatureDisabled) { showDisabledDialog { onToggleExpand() }; return@Button }
                                onToggleExpand()
                            },
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                            colors = ButtonDefaults.buttonColors(
                                color = if (isFeatureDisabled) disabledButtonColor else MiuixTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                painter = painterResource(if (isExpanded) R.drawable.ic_collapse else R.drawable.ic_play),
                                contentDescription = buttonText,
                                modifier = Modifier.size(16.dp),
                                tint = if (isFeatureDisabled) disabledTextColor else Color.White
                            )
                            Text(text = buttonText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isFeatureDisabled) disabledTextColor else Color.White)
                        }
                    }
                }
            }

            if (isExpanded && !item.copyToClipboard) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .background(cardBackgroundColor)
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
                            onClick = { checkContainerAndExecute { onExecuteInTmux(baseCommand) } },
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
                                    onClick = { checkContainerAndExecute { onExecuteInRunningSession(session.id, baseCommand) } },
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

    // 低版本 Android 强制启用确认弹窗
    if (showForceEnableDialog && item.requiredFeature != null) {
        ForceEnableFeatureDialog(
            feature = item.requiredFeature,
            onConfirmed = {
                forceEnabled = true
                showForceEnableDialog = false
                pendingAction?.invoke()
                pendingAction = null
            },
            onDismiss = {
                showForceEnableDialog = false
                pendingAction = null
            }
        )
    }
}

fun resolveCommand(item: ResourceItem, context: android.content.Context): String {
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
            } else if (item.scriptUrl == "install_lightpanel") {
                val installScriptPath = "/data/data/com.termux/files/home/install_lightpanel.sh"
                try {
                    val inputStream = context.assets.open("install_lightpanel.sh")
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
                val tmpScriptPath = "/data/data/com.termux/files/home/tmp_script.sh"
                val downloadCmd = if (item.fallbackScriptUrl.isNotEmpty()) {
                    "(curl -fsSL -o $tmpScriptPath ${item.scriptUrl} || curl -fsSL -o $tmpScriptPath ${item.fallbackScriptUrl})"
                } else {
                    "curl -sSL -o $tmpScriptPath ${item.scriptUrl}"
                }
                "$downloadCmd && bash $runInContainerPath $tmpScriptPath"
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

fun isTmuxInstalled(): Boolean {
    val tmuxPath = "/data/data/com.termux/files/usr/bin/tmux"
    return java.io.File(tmuxPath).exists()
}

fun getRunningSessions(context: Context, termuxService: TermuxService?): List<TerminalSession> {
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
fun HeroWelcomeCard(modifier: Modifier = Modifier) {
    val gradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF2563EB),
            Color(0xFF4F46E5),
            Color(0xFF7C3AED)
        )
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(gradient)
            .padding(20.dp)
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .offset(30.dp, (-40).dp)
                .align(Alignment.TopEnd)
                .alpha(0.12f)
                .background(Color.White, RoundedCornerShape(60.dp))
        )
        Column {
            Text(
                text = "Termux Ultra",
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.8f)
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.resource_center_welcome_subtitle),
                style = TextStyle(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.resource_center_welcome_desc),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.85f),
                    lineHeight = 22.sp
                )
            )
        }
    }
}

@Composable
fun EntryCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    iconBackground: Color,
    iconTint: Color,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MiuixCard(
        modifier = modifier
            .fillMaxWidth(),
        onClick = onClick,
        showIndication = true
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = title,
                    modifier = Modifier.size(24.dp),
                    tint = iconTint
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                ),
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = TextStyle(
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                ),
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.enter),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor
                    )
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = accentColor
                )
            }
        }
    }
}

@Composable
fun WarningNoteCard(modifier: Modifier = Modifier) {
    val iconColor = Color(0xFFFFA000)
    MiuixCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = iconColor
                )
            }
            Text(
                text = stringResource(R.string.resource_center_warning),
                style = TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** AI Termux 资源中心入口卡片 */
@Composable
fun AiTermuxEntryCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE) }
    var collapsed by remember { mutableStateOf(prefs.getBoolean("ai_termux_entry_collapsed", false)) }

    fun setCollapsed(value: Boolean) {
        collapsed = value
        prefs.edit().putBoolean("ai_termux_entry_collapsed", value).apply()
    }

    val gradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF6366F1),
            Color(0xFF8B5CF6),
            Color(0xFFEC4899)
        )
    )
    MiuixCard(
        modifier = modifier,
        onClick = {
            if (collapsed) {
                setCollapsed(false)
            } else {
                val intent = Intent(context, com.termux.app.activities.AiTermuxActivity::class.java)
                context.startActivity(intent)
            }
        },
        showIndication = true
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(gradient)
                    .drawWithCache {
                        onDrawWithContent {
                            if (!collapsed) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.12f),
                                    radius = 55.dp.toPx(),
                                    center = androidx.compose.ui.geometry.Offset(
                                        x = size.width - 15.dp.toPx(),
                                        y = 5.dp.toPx()
                                    )
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.08f),
                                    radius = 30.dp.toPx(),
                                    center = androidx.compose.ui.geometry.Offset(
                                        x = 10.dp.toPx(),
                                        y = size.height + 10.dp.toPx()
                                    )
                                )
                            }
                            drawContent()
                        }
                    }
                    .padding(
                        horizontal = if (collapsed) 14.dp else 16.dp,
                        vertical = if (collapsed) 10.dp else 14.dp
                    )
            ) {
                if (collapsed) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lightbulb),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = Color.White
                        )
                        Text(
                            text = "Termux Agent",
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = "用自然语言管理 Termux 会话·虚拟机·VNC·文件",
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.85f),
                            ),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lightbulb),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = Color.White
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Termux Agent",
                                style = TextStyle(
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "用自然语言管理 Termux\n会话·虚拟机·VNC·文件",
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = Color.White.copy(alpha = 0.9f),
                                    lineHeight = 19.sp
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.22f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "进入",
                                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                )
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
            if (!collapsed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = 6.dp, y = 4.dp)
                        .size(20.dp)
                        .clickable { setCollapsed(true) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandLess,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
