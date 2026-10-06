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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

/** 自动保存防抖窗口：输入停止这么久后才写盘，避免每敲一个字符就落盘一次。 */
private const val AUTOSAVE_DELAY_MS = 400L

/**
 * AgentPaw 设置页（独立 Activity）。
 *
 * 展示 AgentPaw 全部设置项；LLM 连接类选项（Provider / Base URL / API Key / 模型 /
 * 测试连接）不在此展示——按集成约定直接沿用 Termux Agent 已配置的 LLM。
 *
 * 全部设置项即时生效：开关与单选项点击即落盘，数值/文本字段走「草稿 + 防抖自动保存」，
 * 页面不再提供取消/保存按钮。草稿用 [rememberSaveable] 承载，旋转等配置变更后不丢用户输入；
 * 页面暂停或销毁时会同步补写一次，确保最后的改动不丢。
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
    var unlimitedToolRounds by remember { mutableStateOf(AgentPawPrefs.isUnlimitedToolRounds(context)) }

    // 数值/文本草稿：rememberSaveable 保证旋转重建后仍是用户刚输入的值，而不是磁盘旧值
    var temperature by rememberSaveable { mutableStateOf(AgentPawPrefs.getTemperature(context).toString()) }
    var topP by rememberSaveable { mutableStateOf(AgentPawPrefs.getTopP(context).toString()) }
    var maxTokens by rememberSaveable { mutableStateOf(AgentPawPrefs.getMaxTokens(context).toString()) }
    var maxToolRounds by rememberSaveable { mutableStateOf(AgentPawPrefs.getMaxToolRounds(context).toString()) }
    var systemPrompt by rememberSaveable { mutableStateOf(AgentPawPrefs.getSystemPrompt(context)) }

    val draft = AgentPawDraft(temperature, topP, maxTokens, maxToolRounds, systemPrompt)
    // 进入页面时的磁盘值：草稿与它相等说明没有真正改动，不必把同一份数据反复写回
    val initialDraft = remember { draft }
    // 始终指向最新草稿，供暂停/退出时的兜底落盘读取，避免闭包捕获到旧值
    val latestDraft by rememberUpdatedState(draft)

    /**
     * 同步补写一次未落盘的改动（返回键/手势/熄屏/切后台前调用），不等防抖窗口。
     */
    val flushPendingSave: () -> Unit = {
        val pending = latestDraft
        if (pending != initialDraft) {
            val written = writeDraft(context, pending, sync = true)
            when {
                !written -> Toast.makeText(
                    context, context.getString(R.string.agentpaw_save_failed), Toast.LENGTH_SHORT
                ).show()
                !pending.valid -> Toast.makeText(
                    context, context.getString(R.string.agentpaw_field_not_saved), Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 防抖自动保存：写盘切到 IO 线程，输入、滚动等 UI 动作不受影响
    LaunchedEffect(draft) {
        if (draft == initialDraft) return@LaunchedEffect
        delay(AUTOSAVE_DELAY_MS)
        val written = withContext(Dispatchers.IO) { writeDraft(context, draft, sync = false) }
        if (!written) {
            Toast.makeText(context, context.getString(R.string.agentpaw_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // 权限类状态不走草稿：它们由系统设置页决定，用户可能刚从系统设置返回，故每次 ON_RESUME 重查
    var accessibilityEnabled by remember { mutableStateOf(DevicePermissionManager.isAccessibilityServiceEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    accessibilityEnabled = DevicePermissionManager.isAccessibilityServiceEnabled(context)
                    overlayGranted = Settings.canDrawOverlays(context)
                }
                // 跳到系统设置页授权时同样会暂停，先把未落盘的改动写完再离开
                Lifecycle.Event.ON_PAUSE -> flushPendingSave()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(lifecycleOwner) {
        onDispose { flushPendingSave() }
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
                                Column(modifier = Modifier.weight(1f)) {
                                    TextField(
                                        value = temperature,
                                        onValueChange = { temperature = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = stringResource(R.string.agentpaw_temperature_label),
                                        useLabelAsPlaceholder = true
                                    )
                                    FieldHint(
                                        text = stringResource(R.string.agentpaw_temperature_desc),
                                        isError = parseTemperature(temperature) == null
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    TextField(
                                        value = topP,
                                        onValueChange = { topP = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = stringResource(R.string.agentpaw_top_p_label),
                                        useLabelAsPlaceholder = true
                                    )
                                    FieldHint(
                                        text = stringResource(R.string.agentpaw_top_p_desc),
                                        isError = parseTopP(topP) == null
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            TextField(
                                value = maxTokens,
                                onValueChange = { maxTokens = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.agentpaw_max_tokens_label),
                                useLabelAsPlaceholder = true
                            )
                            FieldHint(
                                text = stringResource(R.string.agentpaw_max_tokens_desc),
                                isError = parseMaxTokens(maxTokens) == null
                            )
                        }
                    }
                }

                item(key = "section_prompt") {
                    SmallTitle(text = stringResource(R.string.agentpaw_system_prompt_category))
                }
                item(key = "card_prompt") {
                    SettingCard {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            TextField(
                                value = systemPrompt,
                                onValueChange = { systemPrompt = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 96.dp),
                                label = stringResource(R.string.agentpaw_system_prompt_label),
                                useLabelAsPlaceholder = true
                            )
                            FieldHint(text = stringResource(R.string.agentpaw_system_prompt_desc))
                        }
                    }
                }

                item(key = "section_vision") {
                    SmallTitle(text = stringResource(R.string.agentpaw_vision_category))
                }
                item(key = "card_tool_rounds") {
                    SettingCard {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            SwitchPreference(
                                title = stringResource(R.string.agentpaw_unlimited_tool_rounds_title),
                                summary = stringResource(R.string.agentpaw_unlimited_tool_rounds_desc),
                                checked = unlimitedToolRounds,
                                onCheckedChange = {
                                    unlimitedToolRounds = it
                                    AgentPawPrefs.setUnlimitedToolRounds(context, it)
                                }
                            )
                            // 开关开启时启用 0.1.3 步数无上限机制，隐藏执行步数输入框；
                            // 关闭时恢复输入框并沿用原有的步数上限。
                            if (!unlimitedToolRounds) {
                                Spacer(Modifier.height(10.dp))
                                TextField(
                                    value = maxToolRounds,
                                    onValueChange = { maxToolRounds = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = stringResource(R.string.agentpaw_max_tool_rounds_label),
                                    useLabelAsPlaceholder = true
                                )
                                FieldHint(
                                    text = stringResource(R.string.agentpaw_max_tool_rounds_desc),
                                    isError = parseMaxToolRounds(maxToolRounds) == null
                                )
                            }
                        }
                    }
                }
                item(key = "card_vision_mode") {
                    SettingCard {
                        Column {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.agentpaw_vision_mode_title),
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                FieldHint(text = stringResource(R.string.agentpaw_vision_mode_desc))
                            }
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

/** 数值/文本字段的草稿快照：自动保存与退出前补写都以它为单位。 */
private data class AgentPawDraft(
    val temperature: String,
    val topP: String,
    val maxTokens: String,
    val maxToolRounds: String,
    val systemPrompt: String,
) {
    /** 四项数值是否都可解析；越界会被裁剪进范围，不算无效。 */
    val valid: Boolean
        get() = parseTemperature(temperature) != null &&
            parseTopP(topP) != null &&
            parseMaxTokens(maxTokens) != null &&
            parseMaxToolRounds(maxToolRounds) != null
}

private fun parseTemperature(raw: String): Float? = raw.trim().toFloatOrNull()?.coerceIn(0f, 2f)
private fun parseTopP(raw: String): Float? = raw.trim().toFloatOrNull()?.coerceIn(0f, 1f)
private fun parseMaxTokens(raw: String): Int? = raw.trim().toIntOrNull()?.coerceIn(256, 32768)
private fun parseMaxToolRounds(raw: String): Int? = raw.trim().toIntOrNull()?.coerceIn(1, 50)

/**
 * 落盘数值/文本字段，返回是否写入成功（无效字段被跳过，不计入失败）。
 *
 * 沿用原「保存」按钮的校验语义：可解析但越界的值裁剪进范围，无法解析的字段跳过写入，
 * 其余字段照常保存，避免一处输错就丢掉全部修改。
 *
 * [sync] = true 时用 commit() 同步等待写盘完成，仅用于离开页面前的补写——apply() 的异步
 * 写入有可能来不及在页面被回收前落盘。
 */
private fun writeDraft(context: Context, draft: AgentPawDraft, sync: Boolean): Boolean {
    return runCatching {
        AgentPawPrefs.saveFields(
            context = context,
            temperature = parseTemperature(draft.temperature),
            topP = parseTopP(draft.topP),
            maxTokens = parseMaxTokens(draft.maxTokens),
            maxToolRounds = parseMaxToolRounds(draft.maxToolRounds),
            systemPrompt = draft.systemPrompt.trim(),
            sync = sync
        )
    }.isSuccess
}

/** 配置项说明：作用、取值范围与对行为的影响；[isError] 时改为错误色，提示当前输入未生效。 */
@Composable
private fun FieldHint(text: String, isError: Boolean = false) {
    Text(
        text = if (isError) stringResource(R.string.agentpaw_field_not_saved) else text,
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = if (isError) MiuixTheme.colorScheme.error
        else MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
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
