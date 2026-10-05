package com.termux.app.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R

/** 插件状态展示：颜色 + 文案，卡片和各种列表共用一套口径 */
fun stateColorOf(state: PluginState): Color = when (state) {
    PluginState.ENABLED -> Color(0xFF4CAF50)
    PluginState.DISABLED -> Color(0xFF9E9E9E)
    PluginState.CORRUPTED -> Color(0xFFF44336)
    PluginState.NEEDS_PERMISSION -> Color(0xFFFFA000)
    PluginState.INSTALLED -> Color(0xFF2196F3)
}

@Composable
fun stateLabelOf(state: PluginState): String = when (state) {
    PluginState.ENABLED -> stringResource(R.string.plugin_state_enabled)
    PluginState.DISABLED -> stringResource(R.string.plugin_state_disabled)
    PluginState.CORRUPTED -> stringResource(R.string.plugin_state_corrupted)
    PluginState.NEEDS_PERMISSION -> stringResource(R.string.plugin_state_needs_permission)
    PluginState.INSTALLED -> stringResource(R.string.plugin_state_installed)
}

/** 插件是否还需要用户操作（授权或修复） */
fun needsSetup(state: PluginState): Boolean =
    state == PluginState.INSTALLED || state == PluginState.NEEDS_PERMISSION

@Composable
fun PluginCard(
    plugin: InstalledPlugin,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onDetails: () -> Unit,
    onShare: () -> Unit,
    onUninstall: () -> Unit,
    onOpenH5Home: () -> Unit
) {
    val context = LocalContext.current
    val manifest = plugin.manifest
    val stateColor = stateColorOf(plugin.state)
    val iconBitmap = remember(plugin.id, manifest.icon) { PluginShare.loadIcon(context, plugin) }
    val hasH5Home = manifest.entryPoints?.h5Home?.enabled == true
    val permissions = remember(plugin.id) { manifest.getParsedPermissions() }
    val highestRisk = remember(plugin.id) {
        permissions.mapNotNull { permissionRiskMap[it] }.maxByOrNull { riskWeight(it) }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_extension),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = manifest.name,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "v${manifest.version} · ${manifest.author.ifBlank { stringResource(R.string.plugin_author_unknown) }}",
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(8.dp))
                StateBadge(state = plugin.state, color = stateColor)
            }

            if (manifest.description.isNotBlank()) {
                Text(
                    text = manifest.description,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                manifest.entryPoints?.let { ep ->
                    if (!ep.resourceCards.isNullOrEmpty()) AbilityTag(stringResource(R.string.plugin_entry_resource))
                    if (!ep.agentSkills.isNullOrEmpty()) AbilityTag(stringResource(R.string.plugin_skill_card))
                    if (ep.h5Home?.enabled == true) AbilityTag(stringResource(R.string.plugin_h5_pages))
                }
                if (manifest.systemPrompt != null) AbilityTag(stringResource(R.string.plugin_sys_prompt))
                if (highestRisk != null && highestRisk != PermissionRiskLevel.LOW) {
                    RiskTag(level = highestRisk)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.plugin_permission_count, permissions.size),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasH5Home) {
                    CardAction(
                        text = stringResource(R.string.plugin_open_home),
                        primary = false,
                        onClick = onOpenH5Home
                    )
                }
                Spacer(Modifier.weight(1f))
                CardAction(text = stringResource(R.string.plugin_details), onClick = onDetails)
                CardAction(text = stringResource(R.string.plugin_share), onClick = onShare)
                if (needsSetup(plugin.state) || plugin.state == PluginState.ENABLED) {
                    CardAction(
                        text = if (needsSetup(plugin.state)) {
                            stringResource(R.string.plugin_enable)
                        } else {
                            stringResource(R.string.plugin_disable)
                        },
                        primary = true,
                        onClick = if (needsSetup(plugin.state)) onEnable else onDisable
                    )
                }
                CardAction(
                    text = stringResource(R.string.plugin_uninstall),
                    danger = true,
                    onClick = onUninstall
                )
            }
        }
    }
}

@Composable
private fun StateBadge(state: PluginState, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = stateLabelOf(state),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = color
            )
        )
    }
}

@Composable
private fun AbilityTag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun RiskTag(level: PermissionRiskLevel) {
    val color = when (level) {
        PermissionRiskLevel.MEDIUM -> Color(0xFFFFA000)
        PermissionRiskLevel.HIGH -> Color(0xFFF44336)
        else -> Color(0xFF4CAF50)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = PluginSecurity.getRiskLevelDisplayName(level),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = color
            )
        )
    }
}

@Composable
private fun CardAction(
    text: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val color = when {
        danger -> Color(0xFFF44336)
        primary -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp,
                fontWeight = if (primary || danger) FontWeight.Bold else FontWeight.Medium,
                color = color
            )
        )
    }
}

private fun riskWeight(level: PermissionRiskLevel): Int = when (level) {
    PermissionRiskLevel.LOW -> 0
    PermissionRiskLevel.MEDIUM -> 1
    PermissionRiskLevel.HIGH -> 2
}
