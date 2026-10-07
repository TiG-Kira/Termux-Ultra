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

/**
 * 完整对话记录对话框（自包含）。从原开发者模式选项迁出，现为「记忆与历史」页的常规选项。
 */
@Composable
private fun AgentFullHistoryDialog(show: Boolean, onDismiss: () -> Unit, context: Context) {
    OverlayDialog(
        title = stringResource(R.string.full_chat_history),
        summary = stringResource(R.string.chat_history_full_desc),
        show = show,
        onDismissRequest = onDismiss,
        content = {
            val messages = remember { AiTermuxPrefs.getChatHistory(context) }
            val clipboard = remember {
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            }
            val copyLabel = stringResource(R.string.messages)
            val chatHistoryHeader = stringResource(R.string.chat_history_header)
            val fullChatHistoryLabel = stringResource(R.string.full_chat_history)
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_chat_history),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column {
                        // System Prompt
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "System Prompt",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = AiTermuxPrefs.buildFullSystemPrompt(context),
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    lineHeight = 16.sp,
                                    maxLines = 30
                                )
                            }
                        }
                        // Messages
                        messages.forEach { msg ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
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
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = msg.content.ifBlank { stringResource(R.string.empty) },
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurface,
                                        lineHeight = 18.sp,
                                        maxLines = 50
                                    )
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
                                                    val clip = android.content.ClipData.newPlainText(
                                                        copyLabel, msg.content
                                                    )
                                                    clipboard.setPrimaryClip(clip)
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
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.copy_all),
                    onClick = {
                        val allContent = buildString {
                            appendLine("=== System Prompt ===")
                            appendLine(AiTermuxPrefs.buildFullSystemPrompt(context))
                            appendLine()
                            appendLine(chatHistoryHeader)
                            messages.forEach { msg ->
                                appendLine("[${msg.role}] ${msg.content}")
                            }
                        }
                        val clip = android.content.ClipData.newPlainText(
                            fullChatHistoryLabel, allContent
                        )
                        clipboard.setPrimaryClip(clip)
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(R.string.off),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

/** 打开「记忆与历史」设置页。 */
fun openAiTermuxMemoryHistory(context: Context) {
    context.startActivity(Intent(context, com.termux.app.activities.AiTermuxMemoryHistoryActivity::class.java))
}
