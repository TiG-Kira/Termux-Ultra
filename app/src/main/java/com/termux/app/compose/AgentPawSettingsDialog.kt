package com.termux.app.compose

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paw.agent.device.DevicePermissionManager
import com.paw.agent.device.shizuku.ShizukuStatus
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * AgentPaw 设置弹窗。
 *
 * 展示 AgentPaw 全部设置项；LLM 连接类选项（Provider / Base URL / API Key / 模型 /
 * 测试连接）不在此展示——按集成约定直接沿用 Termux Agent 已配置的 LLM。
 */
@Composable
fun AgentPawSettingsDialog(show: Boolean, onDismiss: () -> Unit) {
    if (!show) return
    val context = LocalContext.current

    var autoSwitch by remember(show) { mutableStateOf(AgentPawPrefs.isAutoSwitchEnabled(context)) }
    var temperature by remember(show) { mutableStateOf(AgentPawPrefs.getTemperature(context).toString()) }
    var topP by remember(show) { mutableStateOf(AgentPawPrefs.getTopP(context).toString()) }
    var maxTokens by remember(show) { mutableStateOf(AgentPawPrefs.getMaxTokens(context).toString()) }
    var maxToolRounds by remember(show) { mutableStateOf(AgentPawPrefs.getMaxToolRounds(context).toString()) }
    var stream by remember(show) { mutableStateOf(AgentPawPrefs.isStreamEnabled(context)) }
    var visionMode by remember(show) { mutableStateOf(AgentPawPrefs.getVisionMode(context)) }
    var systemPrompt by remember(show) { mutableStateOf(AgentPawPrefs.getSystemPrompt(context)) }

    // 无障碍状态每次打开弹窗时实时检测（用户可能刚从系统设置回来）
    val accessibilityEnabled = remember(show) {
        DevicePermissionManager.isAccessibilityServiceEnabled(context)
    }
    // Shizuku 状态走 0.1.1 的 StateFlow：binder 到达/授权结果实时刷新（如授权弹窗返回后立即更新）
    val shizukuStatus by remember { DevicePermissionManager.observeShizukuState() }.collectAsState()
    val overlayGranted = remember(show) { Settings.canDrawOverlays(context) }

    OverlayDialog(
        title = "AgentPaw 设置",
        summary = "手机操控 Agent；LLM 连接沿用 Termux Agent 配置",
        show = show,
        onDismissRequest = onDismiss,
        content = {
            Box(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogSwitchRow(
                        title = "自动切换到 AgentPaw",
                        summary = "对话中识别到需要操控手机的任务时临时交给 AgentPaw，完成后自动切回",
                        checked = autoSwitch,
                        onCheckedChange = {
                            autoSwitch = it
                            AgentPawPrefs.setAutoSwitch(context, it)
                        }
                    )

                    DialogSectionTitle("手机控制与系统权限")
                    DialogStatusRow(
                        title = "无障碍服务",
                        summary = "执行界面点击、滑动、截屏的基础通道",
                        ok = accessibilityEnabled,
                        okText = "已开启",
                        badText = "未开启",
                        actionText = if (accessibilityEnabled) null else "去开启",
                        onAction = { DevicePermissionManager.openAccessibilitySettings(context) }
                    )
                    ShizukuStatusRow(
                        status = shizukuStatus,
                        onAuthorize = {
                            if (!DevicePermissionManager.requestShizukuPermission()) {
                                android.widget.Toast.makeText(
                                    context, "Shizuku 未就绪，请先启动 Shizuku 服务", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onOpenShizuku = {
                            if (!DevicePermissionManager.openShizukuApp(context)) {
                                android.widget.Toast.makeText(
                                    context, "未检测到 Shizuku 应用", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                    DialogStatusRow(
                        title = "悬浮窗停止按钮",
                        summary = "AgentPaw 操控手机时显示可拖拽的停止按钮，点击随时中止",
                        ok = overlayGranted,
                        okText = "已授权",
                        badText = "未授权",
                        actionText = if (overlayGranted) null else "去授权",
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    )

                    DialogSectionTitle("采样参数")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextField(
                            value = temperature,
                            onValueChange = { temperature = it },
                            modifier = Modifier.weight(1f),
                            label = "Temperature",
                            useLabelAsPlaceholder = true
                        )
                        TextField(
                            value = topP,
                            onValueChange = { topP = it },
                            modifier = Modifier.weight(1f),
                            label = "Top-P",
                            useLabelAsPlaceholder = true
                        )
                    }
                    TextField(
                        value = maxTokens,
                        onValueChange = { maxTokens = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "单轮回复最大 Token",
                        useLabelAsPlaceholder = true
                    )

                    DialogSectionTitle("系统提示词")
                    TextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        label = "留空使用 AgentPaw 内置提示词",
                        useLabelAsPlaceholder = true
                    )

                    DialogSectionTitle("手机控制与视觉策略")
                    TextField(
                        value = maxToolRounds,
                        onValueChange = { maxToolRounds = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "最大执行步数（工具调用轮数）",
                        useLabelAsPlaceholder = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("截屏分辨率模式", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("AUTO", "FAST", "HIGH").forEach { mode ->
                                val selected = visionMode == mode
                                TextButton(
                                    text = mode,
                                    onClick = { visionMode = mode },
                                    colors = if (selected) {
                                        ButtonDefaults.textButtonColorsPrimary()
                                    } else {
                                        ButtonDefaults.textButtonColors()
                                    }
                                )
                            }
                        }
                    }

                    DialogSwitchRow(
                        title = "流式输出",
                        summary = "关闭后等待完整回复再显示",
                        checked = stream,
                        onCheckedChange = { stream = it }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = "保存",
                    onClick = {
                        val temp = temperature.toFloatOrNull()?.coerceIn(0f, 2f)
                        val p = topP.toFloatOrNull()?.coerceIn(0f, 1f)
                        val mt = maxTokens.toIntOrNull()?.coerceIn(256, 32768)
                        val mr = maxToolRounds.toIntOrNull()?.coerceIn(1, 50)
                        if (temp == null || p == null || mt == null || mr == null) {
                            android.widget.Toast.makeText(
                                context, "采样参数 / Token 上限 / 执行步数须为有效数字（已按范围校验）",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@TextButton
                        }
                        AgentPawPrefs.save(
                            context = context,
                            temperature = temp,
                            topP = p,
                            maxTokens = mt,
                            maxToolRounds = mr,
                            stream = stream,
                            visionMode = visionMode,
                            systemPrompt = systemPrompt.trim(),
                        )
                        android.widget.Toast.makeText(context, "AgentPaw 设置已保存", android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

@Composable
private fun DialogSectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun DialogSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(summary, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Shizuku 状态行（0.1.1 ShizukuStatus 三态）：
 * GRANTED → 已授权；RUNNING_NO_PERMISSION → 显示「授权」按钮拉起授权对话框；
 * NOT_RUNNING → 服务未运行或未安装，显示「打开 Shizuku」引导。
 */
@Composable
private fun ShizukuStatusRow(
    status: ShizukuStatus,
    onAuthorize: () -> Unit,
    onOpenShizuku: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Shizuku 提权（可选）", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(
                "已授权时优先通过 Shizuku 执行按键操作，未开启时自动使用无障碍通道",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        when (status) {
            ShizukuStatus.GRANTED -> Text(
                "已授权", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary
            )
            ShizukuStatus.RUNNING_NO_PERMISSION -> {
                Spacer(Modifier.width(8.dp))
                TextButton(text = "授权", onClick = onAuthorize)
            }
            ShizukuStatus.NOT_RUNNING -> {
                Spacer(Modifier.width(8.dp))
                TextButton(text = "打开 Shizuku", onClick = onOpenShizuku)
            }
        }
    }
}

@Composable
private fun DialogStatusRow(
    title: String,
    summary: String,
    ok: Boolean,
    okText: String,
    badText: String,
    actionText: String? = null,
    onAction: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(summary, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Text(
            text = if (ok) okText else badText,
            fontSize = 12.sp,
            color = if (ok) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        if (!ok && actionText != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(text = actionText, onClick = onAction)
        }
    }
}
