package com.termux.app.compose

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.activities.TextEditorActivity
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 控制台个性化页（独立 Activity）：自动启动终端控制台、编辑器工具、新会话自动执行、编辑欢迎文本。
 *
 * 与控制台设置页同套 UI 范式（玻璃顶栏 + SettingCard + 即时生效）。自动启动走 [LaunchPrefs] 的
 * StateFlow；编辑器工具与新会话命令走 termux_preferences；欢迎文本通过内置编辑器或 vim 打开 motd 文件。
 */
@Composable
fun ConsolePersonalizationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()

    val prefs = context.getSharedPreferences("termux_preferences", Context.MODE_PRIVATE)

    LaunchPrefs.init(context)
    val autoStartConsoleEnabled by LaunchPrefs.autoStartConsole.collectAsState()

    var editorToolIndex by remember {
        mutableStateOf(prefs.getString("editor_tool", "internal")?.let { if (it == "vim") 1 else 0 } ?: 0)
    }
    var startupCmdText by remember { mutableStateOf(prefs.getString("auto_start_command", "") ?: "") }
    var showStartupCmdDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.console_personalization),
                backdrop = glassPage.backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = onBack) {
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
                item(key = "section_personalization") {
                    SmallTitle(text = stringResource(R.string.console_personalization))
                }
                item(key = "card_personalization") {
                    SettingCard {
                        Column {
                            SwitchPreference(
                                title = stringResource(R.string.pref_auto_start_console_title),
                                summary = stringResource(R.string.pref_auto_start_console_summary),
                                checked = autoStartConsoleEnabled,
                                onCheckedChange = { LaunchPrefs.setAutoStartConsole(context, it) },
                                startAction = { SettingIcon(Icons.Rounded.PlayArrow) }
                            )
                            OverlayDropdownPreference(
                                title = stringResource(R.string.editor_tools),
                                summary = if (editorToolIndex == 0) "内置文本编辑器" else "Vim (终端中)",
                                items = listOf("内置", "Vim"),
                                selectedIndex = editorToolIndex,
                                onSelectedIndexChange = { idx ->
                                    editorToolIndex = idx
                                    prefs.edit().putString("editor_tool", if (idx == 0) "internal" else "vim").apply()
                                },
                                startAction = { SettingIcon(Icons.Rounded.Edit) }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.auto_execute_new_session),
                                summary = if (startupCmdText.isBlank()) "设置每次启动新会话自动运行的指令"
                                else "已设置：${startupCmdText.take(40)}${if (startupCmdText.length > 40) "..." else ""}",
                                onClick = { showStartupCmdDialog = true },
                                startAction = { SettingIcon(Icons.Rounded.Terminal) }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.edit_welcome_motd),
                                summary = "直接编辑 /data/data/com.termux/files/usr/etc/motd",
                                onClick = {
                                    val tool = prefs.getString("editor_tool", "internal") ?: "internal"
                                    if (tool == "vim") {
                                        // 在终端中用 vim 打开
                                        val intent = Intent(Intent.ACTION_SEND)
                                        intent.setPackage(context.packageName)
                                        intent.putExtra("command_path", "/data/data/com.termux/files/usr/etc/motd")
                                        intent.putExtra("command", "vim /data/data/com.termux/files/usr/etc/motd")
                                        intent.putExtra("session_name", "motd")
                                        context.startActivity(intent)
                                    } else {
                                        val intent = Intent(context, TextEditorActivity::class.java)
                                        intent.putExtra("file_path", "/data/data/com.termux/files/usr/etc/motd")
                                        context.startActivity(intent)
                                    }
                                },
                                startAction = { SettingIcon(Icons.Rounded.FormatSize) }
                            )
                        }
                    }
                }
            }
        }

        // 新会话自动执行：输入命令后落盘到 termux_preferences
        OverlayDialog(
            show = showStartupCmdDialog,
            onDismissRequest = { showStartupCmdDialog = false },
            title = stringResource(R.string.auto_execute_new_session),
            summary = "设置每次启动新会话自动运行的指令",
            content = {
                TextField(
                    value = startupCmdText,
                    onValueChange = { startupCmdText = it },
                    label = stringResource(R.string.common_auto_execute),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { showStartupCmdDialog = false },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = stringResource(R.string.save),
                        onClick = {
                            prefs.edit().putString("auto_start_command", startupCmdText).apply()
                            showStartupCmdDialog = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        )
    }
}

/** 打开控制台个性化页。返回键由 Activity 栈自然回到设置页，无需手动回传结果。 */
fun openConsolePersonalization(context: Context) {
    context.startActivity(Intent(context, com.termux.app.activities.ConsolePersonalizationActivity::class.java))
}

/** 设置项左侧图标：圆角方块底 + 24dp 矢量图标。与 SettingsScreen / IntegratedToolsConfig 同款视觉。 */
@Composable
private fun SettingIcon(icon: ImageVector, contentDescription: String? = null) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = rememberVectorPainter(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface
        )
    }
}
