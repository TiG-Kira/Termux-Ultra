package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.paw.agent.device.DevicePermissionManager
import com.paw.agent.device.shizuku.ShizukuStatus
import com.termux.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * AgentPaw 设置页（独立 Activity）。
 *
 * 展示 AgentPaw 全部设置项；LLM 连接类选项（Provider / Base URL / API Key / 模型 /
 * 测试连接）不在此展示——按集成约定直接沿用 Termux Agent 已配置的 LLM。
 *
 * 数值与文本类字段走「编辑草稿 + 保存」语义（沿用原 Dialog 的校验与范围裁剪），
 * 开关与单选项即时落盘；草稿用 [rememberSaveable] 承载，旋转等配置变更后不丢用户输入。
 */
@Composable
fun AgentPawSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 本页在 MainScreen 取景层之外，自建一层供玻璃顶栏折射页面内容
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()

    var autoSwitch by remember { mutableStateOf(AgentPawPrefs.isAutoSwitchEnabled(context)) }
    var stream by remember { mutableStateOf(AgentPawPrefs.isStreamEnabled(context)) }
    var visionMode by remember { mutableStateOf(AgentPawPrefs.getVisionMode(context)) }

    // 数值/文本草稿：rememberSaveable 保证旋转重建后仍是用户刚输入的值，而不是磁盘旧值
    var temperature by rememberSaveable { mutableStateOf(AgentPawPrefs.getTemperature(context).toString()) }
    var topP by rememberSaveable { mutableStateOf(AgentPawPrefs.getTopP(context).toString()) }
    var maxTokens by rememberSaveable { mutableStateOf(AgentPawPrefs.getMaxTokens(context).toString()) }
    var maxToolRounds by rememberSaveable { mutableStateOf(AgentPawPrefs.getMaxToolRounds(context).toString()) }
    var systemPrompt by rememberSaveable { mutableStateOf(AgentPawPrefs.getSystemPrompt(context)) }

    // 权限类状态不走草稿：它们由系统设置页决定，用户可能刚从系统设置返回，故每次 ON_RESUME 重查
    var accessibilityEnabled by remember { mutableStateOf(DevicePermissionManager.isAccessibilityServiceEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = DevicePermissionManager.isAccessibilityServiceEnabled(context)
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Shizuku 状态走 0.1.1 的 StateFlow：binder 到达/授权结果实时刷新（如授权弹窗返回后立即更新）
    val shizukuStatus by remember { DevicePermissionManager.observeShizukuState() }.collectAsState()

    val visionOptions = listOf(
        "AUTO" to (R.string.agentpaw_vision_mode_auto to R.string.agentpaw_vision_mode_auto_desc),
        "FAST" to (R.string.agentpaw_vision_mode_fast to R.string.agentpaw_vision_mode_fast_desc),
        "HIGH" to (R.string.agentpaw_vision_mode_high to R.string.agentpaw_vision_mode_high_desc),
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.agentpaw_settings_title),
                backdrop = glassPage.backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = { onBack() }) {
                        Icon(
                            imageVector = MiuixGlassIcons.ChevronBackward,
                            contentDescription = stringResource(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(glassPage.contentModifier)
                .padding(pagePaddingWithoutTop(padding))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = standaloneContentPadding(padding, bottom = 16.dp)
            ) {
                item(key = "intro") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.agentpaw_settings_intro),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }

                item(key = "card_auto_switch") {
                    SettingCard {
                        SwitchPreference(
                            title = stringResource(R.string.agentpaw_auto_switch_title),
                            summary = stringResource(R.string.agentpaw_auto_switch_desc),
                            checked = autoSwitch,
                            onCheckedChange = {
                                autoSwitch = it
                                AgentPawPrefs.setAutoSwitch(context, it)
                            }
                        )
                    }
                }

                item(key = "section_perm") {
                    SmallTitle(text = stringResource(R.string.agentpaw_perm_category))
                }
                item(key = "card_perm") {
                    SettingCard {
                        Column {
                            StatusPreference(
                                title = stringResource(R.string.agentpaw_accessibility_title),
                                summary = stringResource(R.string.agentpaw_accessibility_desc),
                                statusText = stringResource(
                                    if (accessibilityEnabled) R.string.agentpaw_accessibility_on
                                    else R.string.agentpaw_accessibility_off
                                ),
                                ok = accessibilityEnabled,
                                actionText = if (accessibilityEnabled) null
                                else stringResource(R.string.agentpaw_go_enable),
                                onAction = { DevicePermissionManager.openAccessibilitySettings(context) }
                            )
                            ShizukuPreference(
                                status = shizukuStatus,
                                onAuthorize = {
                                    if (!DevicePermissionManager.requestShizukuPermission()) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.agentpaw_shizuku_not_ready),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onOpenShizuku = {
                                    if (!DevicePermissionManager.openShizukuApp(context)) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.agentpaw_shizuku_not_installed),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                            StatusPreference(
                                title = stringResource(R.string.agentpaw_overlay_title),
                                summary = stringResource(R.string.agentpaw_overlay_desc),
                                statusText = stringResource(
                                    if (overlayGranted) R.string.agentpaw_overlay_granted
                                    else R.string.agentpaw_overlay_denied
                                ),
                                ok = overlayGranted,
                                actionText = if (overlayGranted) null
                                else stringResource(R.string.agentpaw_go_grant),
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
                        }
                    }
                }

                item(key = "section_sampling") {
                    SmallTitle(text = stringResource(R.string.agentpaw_sampling_category))
                }
                item(key = "card_sampling") {
                    SettingCard {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TextField(
                                    value = temperature,
                                    onValueChange = { temperature = it },
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.agentpaw_temperature_label),
                                    useLabelAsPlaceholder = true
                                )
                                TextField(
                                    value = topP,
                                    onValueChange = { topP = it },
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.agentpaw_top_p_label),
                                    useLabelAsPlaceholder = true
                                )
                            }
                            Box(Modifier.height(10.dp))
                            TextField(
                                value = maxTokens,
                                onValueChange = { maxTokens = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.agentpaw_max_tokens_label),
                                useLabelAsPlaceholder = true
                            )
                        }
                    }
                }

                item(key = "section_prompt") {
                    SmallTitle(text = stringResource(R.string.agentpaw_system_prompt_category))
                }
                item(key = "card_prompt") {
                    SettingCard {
                        TextField(
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .heightIn(min = 96.dp),
                            label = stringResource(R.string.agentpaw_system_prompt_label),
                            useLabelAsPlaceholder = true
                        )
                    }
                }

                item(key = "section_vision") {
                    SmallTitle(text = stringResource(R.string.agentpaw_vision_category))
                }
                item(key = "card_tool_rounds") {
                    SettingCard {
                        TextField(
                            value = maxToolRounds,
                            onValueChange = { maxToolRounds = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            label = stringResource(R.string.agentpaw_max_tool_rounds_label),
                            useLabelAsPlaceholder = true
                        )
                    }
                }
                item(key = "card_vision_mode") {
                    SettingCard {
                        Column {
                            visionOptions.forEach { (mode, labels) ->
                                RadioButtonPreference(
                                    title = stringResource(labels.first),
                                    summary = stringResource(labels.second),
                                    selected = visionMode == mode,
                                    onClick = {
                                        visionMode = mode
                                        AgentPawPrefs.setVisionMode(context, mode)
                                    }
                                )
                            }
                        }
                    }
                }

                item(key = "card_stream") {
                    SettingCard {
                        SwitchPreference(
                            title = stringResource(R.string.agentpaw_stream_title),
                            summary = stringResource(R.string.agentpaw_stream_desc),
                            checked = stream,
                            onCheckedChange = {
                                stream = it
                                AgentPawPrefs.setStreamEnabled(context, it)
                            }
                        )
                    }
                }

                item(key = "save_actions") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { onBack() },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = stringResource(R.string.save),
                            onClick = {
                                saveNumericFields(
                                    context = context,
                                    temperature = temperature,
                                    topP = topP,
                                    maxTokens = maxTokens,
                                    maxToolRounds = maxToolRounds,
                                    systemPrompt = systemPrompt
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
    }
}

/**
 * 打开 AgentPaw 设置页。返回键由 Activity 栈自然回到来源页，无需手动回传结果。
 */
fun openAgentPawSettings(context: Context) {
    context.startActivity(Intent(context, com.termux.app.activities.AgentPawSettingsActivity::class.java))
}

/**
 * 校验并落盘数值/文本字段。任一字段非法则整体拒绝写入，保持原 Dialog 的行为。
 */
private fun saveNumericFields(
    context: Context,
    temperature: String,
    topP: String,
    maxTokens: String,
    maxToolRounds: String,
    systemPrompt: String,
) {
    val temp = temperature.toFloatOrNull()?.coerceIn(0f, 2f)
    val p = topP.toFloatOrNull()?.coerceIn(0f, 1f)
    val mt = maxTokens.toIntOrNull()?.coerceIn(256, 32768)
    val mr = maxToolRounds.toIntOrNull()?.coerceIn(1, 50)
    if (temp == null || p == null || mt == null || mr == null) {
        Toast.makeText(context, context.getString(R.string.agentpaw_invalid_number), Toast.LENGTH_SHORT).show()
        return
    }
    AgentPawPrefs.save(
        context = context,
        temperature = temp,
        topP = p,
        maxTokens = mt,
        maxToolRounds = mr,
        // 开关与单选已即时落盘，这里沿用当前内存态保持磁盘一致
        stream = AgentPawPrefs.isStreamEnabled(context),
        visionMode = AgentPawPrefs.getVisionMode(context),
        systemPrompt = systemPrompt.trim(),
    )
    Toast.makeText(context, context.getString(R.string.agentpaw_saved), Toast.LENGTH_SHORT).show()
}

/** 状态行：右侧显示当前状态，未就绪时给出动作按钮。 */
@Composable
private fun StatusPreference(
    title: String,
    summary: String,
    statusText: String,
    ok: Boolean,
    actionText: String?,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(
                summary,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Text(
            text = statusText,
            fontSize = 12.sp,
            color = if (ok) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        if (actionText != null) {
            TextButton(text = actionText, onClick = onAction)
        }
    }
}

/**
 * Shizuku 状态行（0.1.1 ShizukuStatus 三态）：
 * GRANTED → 已授权；RUNNING_NO_PERMISSION → 显示「授权」按钮拉起授权对话框；
 * NOT_RUNNING → 服务未运行或未安装，显示「打开 Shizuku」引导。
 */
@Composable
private fun ShizukuPreference(
    status: ShizukuStatus,
    onAuthorize: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.agentpaw_shizuku_title),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.agentpaw_shizuku_desc),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        when (status) {
            ShizukuStatus.GRANTED -> Text(
                text = stringResource(R.string.agentpaw_shizuku_granted),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.primary
            )
            ShizukuStatus.RUNNING_NO_PERMISSION ->
                TextButton(text = stringResource(R.string.agentpaw_shizuku_authorize), onClick = onAuthorize)
            ShizukuStatus.NOT_RUNNING ->
                TextButton(text = stringResource(R.string.agentpaw_shizuku_open), onClick = onOpenShizuku)
        }
    }
}