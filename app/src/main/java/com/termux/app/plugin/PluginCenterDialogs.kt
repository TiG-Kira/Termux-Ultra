package com.termux.app.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.termux.R
import com.termux.app.vortex.VorteXSandbox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PluginPermissionDialog(
    plugin: InstalledPlugin?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val permissions = plugin?.manifest?.getParsedPermissions() ?: emptyList()

    WindowDialog(
        show = plugin != null,
        title = stringResource(R.string.plugin_dialog_permission_title),
        summary = plugin?.let {
            stringResource(R.string.plugin_dialog_permission_message, it.manifest.name)
        } ?: "",
        onDismissRequest = onDismiss,
        content = {
            if (plugin == null) return@WindowDialog
            Column(modifier = Modifier.padding(top = 12.dp)) {
                permissions.forEach { perm ->
                    val riskLevel = permissionRiskMap[perm] ?: PermissionRiskLevel.LOW
                    val riskColor = riskColorOf(riskLevel)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(riskColor, RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = PluginSecurity.getPermissionDisplayName(perm),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = PluginSecurity.getPermissionDescription(perm),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(riskColor.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = PluginSecurity.getRiskLevelDisplayName(riskLevel),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 10.sp,
                                    color = riskColor
                                )
                            )
                        }
                    }
                }

                if (permissions.any { permissionRiskMap[it] == PermissionRiskLevel.HIGH }) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.plugin_warning_high_permission),
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            color = Color(0xFFFFA000)
                        )
                    )
                }

                Spacer(Modifier.height(20.dp))
                DialogButtons(onDismiss = onDismiss, onConfirm = onConfirm,
                    dismissText = stringResource(R.string.plugin_dialog_cancel),
                    confirmText = stringResource(R.string.plugin_dialog_confirm))
            }
        }
    )
}

@Composable
fun PluginOverwriteDialog(
    plugin: InstalledPlugin?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = plugin != null,
        title = stringResource(R.string.plugin_dialog_overwrite_title),
        summary = plugin?.let {
            stringResource(R.string.plugin_dialog_overwrite_message, it.manifest.name)
        } ?: "",
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                DialogButtons(
                    onDismiss = onDismiss,
                    onConfirm = onConfirm,
                    dismissText = stringResource(R.string.plugin_dialog_overwrite_cancel),
                    confirmText = stringResource(R.string.plugin_dialog_overwrite_confirm),
                    confirmColor = Color(0xFFF44336)
                )
            }
        }
    )
}

@Composable
fun PluginUninstallDialog(
    plugin: InstalledPlugin?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = plugin != null,
        title = stringResource(R.string.plugin_uninstall),
        summary = plugin?.let { stringResource(R.string.plugin_uninstall_confirm, it.manifest.name) } ?: "",
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                DialogButtons(
                    onDismiss = onDismiss,
                    onConfirm = onConfirm,
                    dismissText = stringResource(R.string.cancel),
                    confirmText = stringResource(R.string.confirm),
                    confirmColor = Color(0xFFF44336)
                )
            }
        }
    )
}

/** 分享方式选择：分享元信息文本 / 导出并分享 .tup 文件 */
@Composable
fun PluginShareDialog(
    plugin: InstalledPlugin?,
    onDismiss: () -> Unit,
    onShareMeta: (InstalledPlugin) -> Unit,
    onSharePackage: (InstalledPlugin) -> Unit
) {
    WindowDialog(
        show = plugin != null,
        title = stringResource(R.string.plugin_share_title),
        summary = plugin?.manifest?.name ?: "",
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                DialogActionRow(text = stringResource(R.string.plugin_share_meta)) {
                    plugin?.let { onShareMeta(it) }
                    onDismiss()
                }
                DialogActionRow(text = stringResource(R.string.plugin_share_package)) {
                    plugin?.let { onSharePackage(it) }
                    onDismiss()
                }
                DialogActionRow(text = stringResource(R.string.cancel), onClick = onDismiss)
            }
        }
    )
}

/** 插件详情：概览 / 权限 / 技能 / 资源卡片 / System Prompt / 页面入口 */
@Composable
fun PluginContentDialog(
    plugin: InstalledPlugin?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val skills = plugin?.let { p ->
        PluginManager.getPluginSkills(context).filter { skill -> skill.id.startsWith(p.id) }
    } ?: emptyList()
    val resourceCards = plugin?.let { p ->
        PluginManager.getPluginResourceCards(context).filter { card -> card.id.startsWith(p.id) }
    } ?: emptyList()

    val summaryText = plugin?.let { p ->
        buildString {
            append("${p.manifest.version} · ${p.manifest.author}")
            if (p.manifest.description.isNotBlank()) {
                append("\n\n")
                append(p.manifest.description)
            }
        }
    } ?: ""

    WindowDialog(
        show = plugin != null,
        title = plugin?.manifest?.name ?: "",
        summary = summaryText,
        onDismissRequest = onDismiss,
        content = {
            if (plugin == null) return@WindowDialog
            val activePlugin = plugin
            val h5Entries = activePlugin.manifest.getAllH5Entries()

            Column(modifier = Modifier.padding(top = 12.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                DetailSection(title = stringResource(R.string.plugin_info)) {
                    DetailLine(stringResource(R.string.plugin_version), activePlugin.manifest.version)
                    DetailLine(stringResource(R.string.plugin_author), activePlugin.manifest.author.ifBlank { stringResource(R.string.plugin_author_unknown) })
                    DetailLine("ID", activePlugin.id)
                    DetailLine(
                        stringResource(R.string.plugin_installed_at_label),
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(activePlugin.installedAt))
                    )
                    DetailLine(stringResource(R.string.plugin_state_label), stateLabelOf(activePlugin.state))
                }

                val permissions = activePlugin.manifest.getParsedPermissions()
                if (permissions.isNotEmpty()) {
                    DetailSection(title = stringResource(R.string.plugin_permissions_required)) {
                        permissions.forEach { perm ->
                            val level = permissionRiskMap[perm] ?: PermissionRiskLevel.LOW
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = PluginSecurity.getPermissionDisplayName(perm),
                                    style = androidx.compose.ui.text.TextStyle(
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                )
                                Text(
                                    text = PluginSecurity.getRiskLevelDisplayName(level),
                                    style = androidx.compose.ui.text.TextStyle(
                                        fontSize = 12.sp,
                                        color = riskColorOf(level)
                                    )
                                )
                            }
                        }
                    }
                }

                if (skills.isNotEmpty()) {
                    DetailSection(title = stringResource(R.string.plugin_skill_card)) {
                        skills.forEach { skill ->
                            Text(
                                text = "• ${skill.name} — ${skill.description}",
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                        }
                    }
                }

                if (resourceCards.isNotEmpty()) {
                    DetailSection(title = stringResource(R.string.plugin_entry_resource)) {
                        resourceCards.forEach { card ->
                            Text(
                                text = "• ${card.title} — ${card.description}",
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                        }
                    }
                }

                activePlugin.manifest.systemPrompt?.let { sp ->
                    DetailSection(title = stringResource(R.string.plugin_sys_prompt)) {
                        val modeText = when (sp.getPromptMode()) {
                            PromptModifyMode.APPEND -> stringResource(R.string.plugin_prompt_append)
                            PromptModifyMode.MODIFY -> stringResource(R.string.plugin_prompt_modify)
                            PromptModifyMode.OVERWRITE -> stringResource(R.string.plugin_prompt_overwrite)
                        }
                        Text(
                            text = "${stringResource(R.string.plugin_prompt_mode)}: $modeText",
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                    }
                }

                if (h5Entries.isNotEmpty() && activePlugin.state == PluginState.ENABLED) {
                    DetailSection(title = stringResource(R.string.plugin_h5_pages)) {
                        activePlugin.manifest.entryPoints?.pages?.forEach { page ->
                            PageButton(title = page.title) {
                                if (page.type == "compose") {
                                    PluginComposeActivity.start(context, activePlugin.id, page.entry ?: "", page.title)
                                } else {
                                    PluginWebViewActivity.start(context, activePlugin.id, page.entry ?: "", page.title)
                                }
                            }
                        }
                        h5Entries.filter { (_, entry) ->
                            activePlugin.manifest.entryPoints?.pages?.none { it.entry == entry } != false
                        }.forEach { (title, entry) ->
                            PageButton(title = title) {
                                PluginWebViewActivity.start(context, activePlugin.id, entry, title)
                            }
                        }
                    }
                }

                // VorteX 沙箱：插件「使用沙箱运行」开关（总开关关闭时此开关不可用）
                val vortexEnabled = VorteXSandbox.isEnabled(context)
                val pluginSandboxState = remember(plugin.id) {
                    mutableStateOf(VorteXSandbox.isPluginSandboxEnabled(context, plugin.id))
                }
                SwitchPreference(
                    title = stringResource(R.string.vortex_sandbox_plugin_title),
                    summary = if (vortexEnabled)
                        stringResource(R.string.vortex_sandbox_plugin_summary)
                    else stringResource(R.string.vortex_sandbox_plugin_disabled_summary),
                    checked = pluginSandboxState.value,
                    enabled = vortexEnabled,
                    onCheckedChange = {
                        pluginSandboxState.value = it
                        VorteXSandbox.setPluginSandboxEnabled(context, plugin.id, it)
                    }
                )

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp)),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }
    )
}

@Composable
private fun DialogButtons(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dismissText: String,
    confirmText: String,
    confirmColor: Color = MiuixTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(10.dp)),
            colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                text = dismissText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)),
            colors = ButtonDefaults.buttonColors(color = confirmColor)
        ) {
            Text(
                text = confirmText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun DialogActionRow(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .padding(vertical = 2.dp),
        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
        )
        Spacer(Modifier.height(6.dp))
        content()
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = "$label: ",
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        )
        Text(
            text = value,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface
            )
        )
    }
}

@Composable
private fun PageButton(title: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .padding(vertical = 2.dp),
        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

fun riskColorOf(level: PermissionRiskLevel): Color = when (level) {
    PermissionRiskLevel.LOW -> Color(0xFF4CAF50)
    PermissionRiskLevel.MEDIUM -> Color(0xFFFFA000)
    PermissionRiskLevel.HIGH -> Color(0xFFF44336)
}
