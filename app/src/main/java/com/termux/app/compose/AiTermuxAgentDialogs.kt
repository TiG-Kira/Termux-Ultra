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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
