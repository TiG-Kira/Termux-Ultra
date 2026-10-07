package com.termux.app.compose

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.activities.AiLocalTrainerActivity
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「模型和生成」设置页（独立 Activity）。
 *
 * 归类自原 SettingsScreen 的 Termux Agent 分组：在线主配置、模型配置档、对话参数、信任白名单、
 * 重置配置状态，以及本地模式专属的本地训练入口与备用在线大模型。所有 OverlayDialog 均置于
 * Scaffold 内部，确保正常显示。
 */
@Composable
fun AiTermuxModelGenScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()

    val aiProvider = remember { AiTermuxPrefs.getConfig(context).providerConfig.provider }
    val isLocalMode = remember { aiProvider == "local" }
    val hasFallbackCached = remember { AiTermuxPrefs.isFallbackOnlineConfigReady(context) }
    var fallbackEnabled by remember { mutableStateOf(AiTermuxPrefs.isFallbackOnlineEnabled(context)) }

    var showOnlineConfigEditor by remember { mutableStateOf(false) }
    var showProfileManager by remember { mutableStateOf(false) }
    var showChatParamsEditor by remember { mutableStateOf(false) }
    var showWhitelistDialog by remember { mutableStateOf(false) }
    var showResetConfigWarning by remember { mutableStateOf(false) }
    var showFallbackEditor by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.model_gen_title),
                backdrop = glassPage.backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixGlassIcons.ChevronBackward,
                            contentDescription = stringResource(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.height(24.dp)
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
                item(key = "section_model_params") {
                    SmallTitle(text = stringResource(R.string.model_params_settings))
                }
                item(key = "card_model_params") {
                    SettingCard {
                        Column {
                            val onlineCfg = AiTermuxPrefs.getConfig(context).providerConfig
                            ArrowPreference(
                                title = stringResource(R.string.agent_online_config),
                                summary = if (onlineCfg.apiKey.isBlank()) {
                                    stringResource(R.string.api_key_empty)
                                } else {
                                    stringResource(
                                        R.string.model_url_key,
                                        onlineCfg.model.ifBlank { stringResource(R.string.not_set) },
                                        onlineCfg.apiBaseUrl.ifBlank { stringResource(R.string.not_set) },
                                        "********"
                                    )
                                },
                                onClick = { showOnlineConfigEditor = true },
                                startAction = {
                                    SettingIcon(Icons.Rounded.Cloud, stringResource(R.string.agent_online_config))
                                }
                            )

                            ArrowPreference(
                                title = stringResource(R.string.agent_profiles),
                                summary = stringResource(R.string.agent_profiles_desc),
                                onClick = { showProfileManager = true },
                                startAction = {
                                    SettingIcon(Icons.Rounded.Dashboard, stringResource(R.string.agent_profiles))
                                }
                            )

                            ArrowPreference(
                                title = stringResource(R.string.agent_chat_params),
                                summary = stringResource(R.string.agent_chat_params_desc),
                                onClick = { showChatParamsEditor = true },
                                startAction = {
                                    SettingIcon(Icons.Rounded.Settings, stringResource(R.string.agent_chat_params))
                                }
                            )

                            if (aiProvider == "local") {
                                ArrowPreference(
                                    title = stringResource(R.string.train_local_model),
                                    summary = if (hasFallbackCached) {
                                        stringResource(R.string.training_online_full_auto)
                                    } else {
                                        stringResource(R.string.training_manual_mode)
                                    },
                                    onClick = {
                                        context.startActivity(Intent(context, AiLocalTrainerActivity::class.java))
                                    },
                                    startAction = {
                                        SettingIcon(Icons.Rounded.Tune, stringResource(R.string.train_local_model))
                                    }
                                )
                            }

                            // 本地模式专属：备用在线大模型（fallback）
                            if (isLocalMode) {
                                SwitchPreference(
                                    title = stringResource(R.string.backup_online_llm),
                                    summary = if (fallbackEnabled) {
                                        val ready = AiTermuxPrefs.isFallbackOnlineConfigReady(context)
                                        if (ready) stringResource(R.string.fallback_enabled_ready)
                                        else stringResource(R.string.fallback_not_configured)
                                    } else {
                                        stringResource(R.string.fallback_off_desc)
                                    },
                                    checked = fallbackEnabled,
                                    onCheckedChange = {
                                        fallbackEnabled = it
                                        AiTermuxPrefs.setFallbackOnlineEnabled(context, it)
                                    },
                                    startAction = {
                                        SettingIcon(Icons.Rounded.Autorenew, stringResource(R.string.backup_online_llm))
                                    }
                                )
                                if (fallbackEnabled) {
                                    val c = AiTermuxPrefs.getFallbackOnlineConfig(context)
                                    val urlShown = if (c.baseUrl.isBlank()) stringResource(R.string.not_set) else c.baseUrl
                                    val modelShown = if (c.model.isBlank()) stringResource(R.string.not_set) else c.model
                                    val keyShown = if (c.apiKey.isBlank()) stringResource(R.string.api_key_empty) else "********"
                                    ArrowPreference(
                                        title = stringResource(R.string.configure_backup_params),
                                        summary = stringResource(R.string.model_url_key, modelShown, urlShown, keyShown),
                                        onClick = { showFallbackEditor = true },
                                        startAction = {
                                            SettingIcon(Icons.Rounded.Edit, stringResource(R.string.configure_backup_params))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "section_security") {
                    SmallTitle(text = stringResource(R.string.security_settings))
                }
                item(key = "card_security") {
                    SettingCard {
                        Column {
                            val autoExecConfig = AiTermuxPrefs.getAutoExecConfig(context)
                            val unlimitedMode = AiTermuxPrefs.isUnlimitedMode(context)
                            val whitelistCount = autoExecConfig.autoExecSkills.size
                            val whitelistSummary = when {
                                unlimitedMode -> stringResource(R.string.unrestricted_opened)
                                whitelistCount == 0 -> stringResource(R.string.whitelist_off)
                                else -> stringResource(R.string.whitelist_count_selected, whitelistCount)
                            }
                            ArrowPreference(
                                title = stringResource(R.string.trust_whitelist),
                                summary = whitelistSummary,
                                enabled = !unlimitedMode,
                                onClick = { showWhitelistDialog = true },
                                startAction = {
                                    SettingIcon(Icons.Rounded.Shield, stringResource(R.string.trust_whitelist))
                                }
                            )

                            ArrowPreference(
                                title = stringResource(R.string.reconfigure_ai),
                                summary = stringResource(R.string.reset_config_desc),
                                onClick = { showResetConfigWarning = true },
                                startAction = {
                                    SettingIcon(Icons.Rounded.Autorenew, stringResource(R.string.reconfigure_ai))
                                }
                            )
                        }
                    }
                }
            }

            // ---------- 本页所有 OverlayDialog 必须位于 Scaffold 内部 ----------
            AgentOnlineConfigDialog(show = showOnlineConfigEditor, onDismiss = { showOnlineConfigEditor = false })
            AgentProfileDialog(show = showProfileManager, onDismiss = { showProfileManager = false })
            AgentChatParamsDialog(show = showChatParamsEditor, onDismiss = { showChatParamsEditor = false })
            AgentWhitelistDialog(show = showWhitelistDialog, onDismiss = { showWhitelistDialog = false }, context = context)
            AgentResetConfigDialog(show = showResetConfigWarning, onDismiss = { showResetConfigWarning = false }, context = context)
            AgentFallbackLlmDialog(show = showFallbackEditor, onDismiss = { showFallbackEditor = false }, context = context)
        }
    }
}

/** 打开「模型和生成」设置页。 */
fun openAiTermuxModelGen(context: Context) {
    context.startActivity(Intent(context, com.termux.app.activities.AiTermuxModelGenActivity::class.java))
}
