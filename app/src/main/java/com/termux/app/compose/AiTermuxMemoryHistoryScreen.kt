package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「记忆与历史」设置页（独立 Activity）。
 *
 * 归类自原 SettingsScreen 的 Termux Agent 分组：长期记忆、完整对话记录（从开发者选项迁出，
 * 现为常规选项）、清空对话记录。所有 OverlayDialog 均置于 Scaffold 内部，确保正常显示。
 */
@Composable
fun AiTermuxMemoryHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()

    var showMemoryEditor by remember { mutableStateOf(false) }
    var showFullHistoryViewer by remember { mutableStateOf(false) }
    var showAiClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.memory_history_title),
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
                item(key = "card_memory_history") {
                    SettingCard {
                        Column {
                            ArrowPreference(
                                title = stringResource(R.string.agent_memory),
                                summary = stringResource(R.string.agent_memory_desc),
                                onClick = { showMemoryEditor = true },
                                startAction = {
                                    SettingIcon(Icons.Rounded.Psychology, stringResource(R.string.agent_memory))
                                }
                            )

                            ArrowPreference(
                                title = stringResource(R.string.full_chat_history),
                                summary = stringResource(R.string.chat_history_full_desc),
                                onClick = { showFullHistoryViewer = true },
                                startAction = {
                                    SettingIcon(Icons.Rounded.FolderOpen, stringResource(R.string.full_chat_history))
                                }
                            )

                            ArrowPreference(
                                title = stringResource(R.string.clear_chat_history),
                                summary = stringResource(R.string.clear_agent_history_desc),
                                onClick = { showAiClearConfirm = true },
                                startAction = {
                                    SettingIcon(Icons.Rounded.Delete, stringResource(R.string.clear_chat_history))
                                }
                            )
                        }
                    }
                }
            }

            // ---------- 本页所有 OverlayDialog 必须位于 Scaffold 内部 ----------
            AgentMemoryDialog(show = showMemoryEditor, onDismiss = { showMemoryEditor = false })
            AgentFullHistoryDialog(show = showFullHistoryViewer, onDismiss = { showFullHistoryViewer = false }, context = context)
            AgentClearChatDialog(show = showAiClearConfirm, onDismiss = { showAiClearConfirm = false }, context = context)
        }
    }
}

/** 打开「记忆与历史」设置页。 */
fun openAiTermuxMemoryHistory(context: Context) {
    context.startActivity(Intent(context, com.termux.app.activities.AiTermuxMemoryHistoryActivity::class.java))
}
