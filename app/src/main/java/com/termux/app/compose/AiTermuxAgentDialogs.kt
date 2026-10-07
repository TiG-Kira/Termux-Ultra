package com.termux.app.compose

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.google.android.material.snackbar.Snackbar
import com.termux.app.activities.AiTermuxActivity
import com.termux.app.utils.SnackbarHelper
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.res.stringResource
import android.content.ClipboardManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 信任白名单选择对话框（自包含）。
 *
 * 从 [AiTermuxPrefs.getAutoExecConfig] 初始化临时勾选集合，确认后写回。原内联于
 * SettingsScreen 的 AI Termux 分组，现供「模型和生成」页复用。
 */
@Composable
internal fun AgentWhitelistDialog(show: Boolean, onDismiss: () -> Unit, context: Context) {
    var tempWhitelistSkills by remember(show) {
        mutableStateOf<Set<SkillType>>(
            AiTermuxPrefs.getAutoExecConfig(context).autoExecSkills.mapNotNull {
                runCatching { SkillType.valueOf(it) }.getOrNull()
            }.toSet()
        )
    }
    // 可白名单化的技能定义（与 SkillType.requiresClick() 中保持一致）
    val whitelistSkillLabels = listOf(
        SkillType.CAPTURE_OUTPUT to context.getString(R.string.capture_output_desc),
        SkillType.SUB_AGENT to context.getString(R.string.whitelist_sub_agent_desc),
        SkillType.SEARCH_AGENT to context.getString(R.string.whitelist_search_agent_desc),
        SkillType.COMPILE_CODE to context.getString(R.string.whitelist_compile_code_desc),
    )

    OverlayDialog(
        show = show,
        onDismissRequest = onDismiss,
        title = context.getString(R.string.trust_whitelist),
        summary = context.getString(R.string.whitelist_select_desc),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = context.getString(R.string.whitelist_warning),
                    fontSize = 13.sp,
                    color = Color(0xFFDC2626),
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                whitelistSkillLabels.forEach { (skill, label) ->
                    val checked = tempWhitelistSkills.contains(skill)
                    CheckboxPreference(
                        title = label,
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            tempWhitelistSkills = if (isChecked) {
                                tempWhitelistSkills + skill
                            } else {
                                tempWhitelistSkills - skill
                            }
                        },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = context.getString(R.string.auto_exec_skills_note),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = context.getString(R.string.agent_permissions_examples),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        text = context.getString(R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = context.getString(R.string.ok),
                        onClick = {
                            // 未选择任何技能时白名单关闭
                            val enabled = tempWhitelistSkills.isNotEmpty()
                            val config = AiTermuxPrefs.getAutoExecConfig(context).copy(
                                autoExecEnabled = enabled,
                                autoExecSkills = tempWhitelistSkills.map { it.name }.toSet()
                            )
                            AiTermuxPrefs.saveAutoExecConfig(context, config)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

/**
 * 重置配置状态警告对话框（自包含）。确认后清空所有 AI 状态并强制重新初始化。
 */
@Composable
internal fun AgentResetConfigDialog(show: Boolean, onDismiss: () -> Unit, context: Context) {
    OverlayDialog(
        show = show,
        title = context.getString(R.string.reset_config_warning_title),
        summary = context.getString(R.string.reset_config_warning_message),
        onDismissRequest = onDismiss,
        content = {
            Column {
                Text(
                    text = context.getString(R.string.reset_config_warning_hint),
                    modifier = Modifier.padding(bottom = 12.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        text = context.getString(R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = context.getString(R.string.reset_config_confirm_btn),
                        onClick = {
                            onDismiss()
                            AiTermuxPrefs.resetAllAiState(context)
                            val intent = Intent(context, AiTermuxActivity::class.java)
                            intent.putExtra("force_setup", true)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(color = Color(0xFFF44336))
                    )
                }
            }
        }
    )
}

/**
 * 清空对话记录确认对话框（自包含）。
 */
@Composable
internal fun AgentClearChatDialog(show: Boolean, onDismiss: () -> Unit, context: Context) {
    OverlayDialog(
        show = show,
        title = context.getString(R.string.clear_chat_title),
        summary = context.getString(R.string.clear_chat_confirm),
        onDismissRequest = onDismiss,
        content = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    text = context.getString(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = context.getString(R.string.clear),
                    onClick = {
                        onDismiss()
                        AiTermuxPrefs.clearAllConversationsExceptDefault(context)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    )
}

/**
 * 备用在线大模型参数编辑对话框（本地模式专属，自包含）。
 *
 * 内部持有 fb* 草稿状态，确认后写回 [AiTermuxPrefs.FallbackOnlineConfig]。支持从已保存的
 * LLM Profile 一键载入。
 */
@Composable
internal fun AgentFallbackLlmDialog(show: Boolean, onDismiss: () -> Unit, context: Context) {
    val cfg0 = AiTermuxPrefs.getFallbackOnlineConfig(context)
    var fbUrl by remember(show) { mutableStateOf(cfg0.baseUrl) }
    var fbKey by remember(show) { mutableStateOf(cfg0.apiKey) }
    var fbModel by remember(show) { mutableStateOf(cfg0.model) }
    var fbTemp by remember(show) { mutableStateOf(cfg0.temperature) }

    OverlayDialog(
        title = context.getString(R.string.configure_backup_llm),
        summary = context.getString(R.string.fallback_desc),
        show = show,
        onDismissRequest = onDismiss,
        content = {
            Box(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 从 LLM Profile 一键载入
                    val profiles = AiTermuxPrefs.getLlmProfiles(context)
                    if (profiles.isNotEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().height(40.dp)
                            .clickable {
                                val p = profiles.first()
                                fbUrl = p.apiBaseUrl
                                fbKey = p.apiKey
                                fbModel = p.model
                                fbTemp = p.temperature
                                AiTermuxPrefs.saveFallbackOnlineConfig(
                                    context,
                                    AiTermuxPrefs.FallbackOnlineConfig(
                                        enabled = true, apiKey = p.apiKey, baseUrl = p.apiBaseUrl,
                                        model = p.model, temperature = p.temperature
                                    )
                                )
                                SnackbarHelper.show(
                                    context,
                                    "已从 Profile「" + p.name + "」载入备用大模型",
                                    Snackbar.LENGTH_SHORT, null
                                )
                                onDismiss()
                            }
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                "📋 从 LLM Profile 一键载入 (" + profiles.first().name + ")",
                                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                color = MiuixTheme.colorScheme.primary
                            )
                        }
                    }

                    TextField(
                        value = fbUrl,
                        onValueChange = { v -> fbUrl = v },
                        modifier = Modifier.fillMaxWidth(),
                        label = context.getString(R.string.api_base_url_hint),
                        useLabelAsPlaceholder = true
                    )
                    TextField(
                        value = fbKey,
                        onValueChange = { v -> fbKey = v },
                        modifier = Modifier.fillMaxWidth(),
                        label = context.getString(R.string.api_key_hint),
                        useLabelAsPlaceholder = true
                    )
                    TextField(
                        value = fbModel,
                        onValueChange = { v -> fbModel = v },
                        modifier = Modifier.fillMaxWidth(),
                        label = context.getString(R.string.model_name_hint),
                        useLabelAsPlaceholder = true
                    )
                    Text(
                        text = context.getString(R.string.temperature_current, fbTemp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Slider(
                        value = fbTemp,
                        onValueChange = { fbTemp = it },
                        valueRange = 0f..2f,
                        steps = 39,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = context.getString(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = context.getString(R.string.save),
                    onClick = {
                        AiTermuxPrefs.saveFallbackOnlineConfig(
                            context,
                            AiTermuxPrefs.FallbackOnlineConfig(
                                enabled = true,
                                apiKey = fbKey,
                                baseUrl = fbUrl,
                                model = fbModel,
                                temperature = fbTemp
                            )
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

/**
 * 完整对话记录对话框（自包含）。
 *
 * 支持多会话切换、查看 System Prompt、每条消息的详情（角色、内容、技能调用卡片、深度思考、报错）、
 * 单条消息复制、全局格式化导出复制与系统分享。
 */
@Composable
internal fun AgentFullHistoryDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    context: Context,
    initialConversationId: String? = null
) {
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val conversations = remember(show) { AiTermuxPrefs.getConversations(context) }
    val activeId = remember(show, initialConversationId) {
        initialConversationId ?: AiTermuxPrefs.getActiveConversationId(context)
    }
    var selectedConvId by remember(show, activeId) {
        mutableStateOf(
            if (conversations.any { it.id == activeId }) activeId!!
            else conversations.firstOrNull()?.id ?: DEFAULT_CONVERSATION_ID
        )
    }

    val selectedConv = conversations.firstOrNull { it.id == selectedConvId } ?: conversations.firstOrNull()
    val messages = selectedConv?.messages ?: emptyList()
    val fullSystemPrompt = remember(show) { AiTermuxPrefs.buildFullSystemPrompt(context) }

    OverlayDialog(
        title = stringResource(R.string.full_chat_history),
        summary = stringResource(R.string.chat_history_full_desc),
        show = show,
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 多会话切换标签栏
                if (conversations.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        conversations.forEach { conv ->
                            val isSelected = conv.id == selectedConvId
                            val count = conv.messages.size
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (isSelected) MiuixTheme.colorScheme.primary
                                        else MiuixTheme.colorScheme.surfaceContainer
                                    )
                                    .clickable { selectedConvId = conv.id }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${conv.title} ($count)",
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MiuixTheme.colorScheme.onPrimary
                                            else MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.no_chat_history),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "当前会话暂无聊天记录",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column {
                            // 会话信息卡片
                            val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                            val timeStr = selectedConv?.let { dateFormat.format(Date(it.updatedAt)) } ?: ""
                            Text(
                                text = "会话：${selectedConv?.title ?: "默认"} · ${messages.size} 条消息 · $timeStr",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            // System Prompt 卡片
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "System Prompt",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MiuixTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = stringResource(R.string.copy),
                                            fontSize = 11.sp,
                                            color = MiuixTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clickable {
                                                    clipboard.setPrimaryClip(
                                                        android.content.ClipData.newPlainText("System Prompt", fullSystemPrompt)
                                                    )
                                                    SnackbarHelper.show(context, "System Prompt 已复制", Snackbar.LENGTH_SHORT)
                                                }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = fullSystemPrompt,
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        lineHeight = 16.sp,
                                        maxLines = 25
                                    )
                                }
                            }

                            // 消息列表
                            val msgTimeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                            messages.forEachIndexed { index, msg ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = when (msg.role) {
                                                    "user" -> stringResource(R.string.tab_user)
                                                    "assistant" -> "🤖 AI"
                                                    "system" -> stringResource(R.string.tab_system)
                                                    else -> msg.role
                                                },
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = when (msg.role) {
                                                    "user" -> MiuixTheme.colorScheme.primary
                                                    "assistant" -> MiuixTheme.colorScheme.onSurface
                                                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                                }
                                            )
                                            Text(
                                                text = "#${index + 1}  ${msgTimeFormat.format(Date(msg.timestamp))}",
                                                fontSize = 10.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                                            )
                                        }

                                        // 深度思考折叠/展开内容展示
                                        if (!msg.reasoningContent.isNullOrBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                                    .padding(8.dp)
                                            ) {
                                                Column {
                                                    Text(
                                                        text = "💡 思考过程：",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                                    )
                                                    Text(
                                                        text = msg.reasoningContent,
                                                        fontSize = 11.sp,
                                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                        lineHeight = 15.sp,
                                                        maxLines = 15
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(4.dp))
                                        val mainContent = when {
                                            msg.content.isNotBlank() -> msg.content
                                            msg.skillCard != null -> "⚙ [${msg.skillCard.skillType.name}] ${msg.skillCard.title}"
                                            else -> stringResource(R.string.empty)
                                        }
                                        Text(
                                            text = mainContent,
                                            fontSize = 13.sp,
                                            color = MiuixTheme.colorScheme.onSurface,
                                            lineHeight = 18.sp,
                                            maxLines = 50
                                        )

                                        // 技能调用详细卡片
                                        if (msg.skillCard != null) {
                                            Spacer(Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(MiuixTheme.colorScheme.surfaceContainer)
                                                    .padding(6.dp)
                                            ) {
                                                Column {
                                                    Text(
                                                        text = "技能: ${msg.skillCard.title.ifBlank { msg.skillCard.skillType.name }} (${msg.skillCard.status})",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = MiuixTheme.colorScheme.primary
                                                    )
                                                    if (!msg.skillCard.command.isNullOrBlank()) {
                                                        Text(
                                                            text = "$ ${msg.skillCard.command}",
                                                            fontSize = 10.sp,
                                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                            maxLines = 4
                                                        )
                                                    }
                                                    if (!msg.skillCard.output.isNullOrBlank()) {
                                                        Text(
                                                            text = msg.skillCard.output,
                                                            fontSize = 10.sp,
                                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                                                            maxLines = 6
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // 错误信息
                                        if (!msg.errorMessage.isNullOrBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "⚠️ ${msg.errorMessage}",
                                                fontSize = 11.sp,
                                                color = Color(0xFFE53935)
                                            )
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            Text(
                                                text = stringResource(R.string.copy),
                                                fontSize = 11.sp,
                                                color = MiuixTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clickable {
                                                        val clipText = buildString {
                                                            if (!msg.reasoningContent.isNullOrBlank()) {
                                                                appendLine("【思考过程】")
                                                                appendLine(msg.reasoningContent)
                                                                appendLine()
                                                            }
                                                            append(mainContent)
                                                            if (msg.skillCard != null && !msg.skillCard.command.isNullOrBlank()) {
                                                                appendLine("\n命令: ${msg.skillCard.command}")
                                                            }
                                                        }
                                                        clipboard.setPrimaryClip(
                                                            android.content.ClipData.newPlainText(
                                                                context.getString(R.string.messages), clipText
                                                            )
                                                        )
                                                        SnackbarHelper.show(context, "已复制该条消息", Snackbar.LENGTH_SHORT)
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val buildExportText = {
                        buildString {
                            appendLine("# Termux Agent 对话记录")
                            appendLine("会话：${selectedConv?.title ?: "默认"}")
                            appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                            appendLine()
                            appendLine("=== System Prompt ===")
                            appendLine(fullSystemPrompt)
                            appendLine()
                            appendLine("=== 对话记录 (${messages.size} 条) ===")
                            messages.forEachIndexed { i, msg ->
                                val roleLabel = when (msg.role) {
                                    "user" -> "用户"
                                    "assistant" -> "AI"
                                    "system" -> "系统"
                                    else -> msg.role
                                }
                                appendLine("[$roleLabel] (#${i + 1})")
                                if (!msg.reasoningContent.isNullOrBlank()) {
                                    appendLine("> 思考: ${msg.reasoningContent.replace("\n", "\n> ")}")
                                }
                                val body = msg.content.ifBlank { msg.skillCard?.title.orEmpty() }
                                appendLine(body)
                                if (msg.skillCard != null && !msg.skillCard.command.isNullOrBlank()) {
                                    appendLine("`$ ${msg.skillCard.command}`")
                                }
                                if (!msg.errorMessage.isNullOrBlank()) {
                                    appendLine("⚠️ 错误: ${msg.errorMessage}")
                                }
                                appendLine()
                            }
                        }
                    }

                    TextButton(
                        text = stringResource(R.string.copy_all),
                        onClick = {
                            val allContent = buildExportText()
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    context.getString(R.string.full_chat_history), allContent
                                )
                            )
                            SnackbarHelper.show(context, "完整对话记录已复制到剪贴板", Snackbar.LENGTH_SHORT)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "分享",
                        onClick = {
                            val allContent = buildExportText()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, allContent)
                                putExtra(Intent.EXTRA_TITLE, "Termux Agent 完整对话记录")
                            }
                            context.startActivity(Intent.createChooser(intent, "分享完整对话记录"))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = stringResource(R.string.off),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}
