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
import com.termux.shared.termux.TermuxConstants
import java.io.File
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
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

/** 欢迎文本脚本路径：login（bootstrap bin/login）在新会话时若存在则执行它，优先于 /usr/etc/motd。 */
private val MOTD_SCRIPT_PATH = TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/motd.sh"

/**
 * 控制台个性化页（独立 Activity）：自动启动终端控制台、编辑器工具、新会话自动执行、编辑欢迎文本。
 *
 * 与控制台设置页同套 UI 范式（玻璃顶栏 + SettingCard + 即时生效）。自动启动走 [LaunchPrefs] 的
 * StateFlow；编辑器工具与新会话命令走 termux_preferences；欢迎文本脚本 ~/.termux/motd.sh 通过
 * 内置编辑器或 vim 打开（首次自动落地模板），见 #336。
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
                // 取景层内铺不透明 Miuix 底色，带区模糊采不到透明（防墨团）
                .background(MiuixTheme.colorScheme.surface)
                .padding(pagePaddingWithoutTop(padding))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = standaloneContentPadding(padding, bottom = 16.dp)
            ) {
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
                                summary = "编辑 ~/.termux/motd.sh，每次新会话执行，优先于 /usr/etc/motd",
                                onClick = {
                                    ensureMotdScript()
                                    val tool = prefs.getString("editor_tool", "internal") ?: "internal"
                                    if (tool == "vim") {
                                        // 在终端会话中用 vim 打开。本页是独立 Activity，无终端服务
                                        // 上下文，复用主页 autoStartConsole 同款机制：ComposeSessionManager
                                        // 建会话 + stdin 注入命令（与「新会话自动执行」同一通道，pty
                                        // 缓冲可靠），再切到终端页。旧 ACTION_SEND intent（command_path/
                                        // command/session_name extras）无任何消费端，startActivity 必崩。
                                        try {
                                            val composeSessionManager =
                                                com.termux.app.terminal.shell.ComposeSessionManager.getInstance(context)
                                            // 幂等：已有存活的 motd 会话直接切过去，避免连点堆出一串 vim 会话
                                            val session = composeSessionManager.sessions.value
                                                .firstOrNull {
                                                    it.session.sessionName.value == "motd" && it.session.isRunning.value
                                                }?.session
                                                ?: composeSessionManager
                                                    .createDefaultSession(startImmediately = true)
                                                    .also { newSession ->
                                                        newSession.sessionName.value = "motd"
                                                        newSession.write("vim \"$MOTD_SCRIPT_PATH\"\n".toByteArray())
                                                    }
                                            composeSessionManager.switchTo(session.id)
                                            context.startActivity(Intent(context, com.termux.app.TermuxActivity::class.java))
                                        } catch (e: Throwable) {
                                            // 建会话失败兜底：退回内置编辑器，不让设置页崩掉
                                            android.widget.Toast.makeText(
                                                context, "无法打开终端会话，已改用内置编辑器", android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                            context.startActivity(Intent(context, TextEditorActivity::class.java).apply {
                                                putExtra("file_path", MOTD_SCRIPT_PATH)
                                            })
                                        }
                                    } else {
                                        val intent = Intent(context, TextEditorActivity::class.java)
                                        intent.putExtra("file_path", MOTD_SCRIPT_PATH)
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

/**
 * 首次编辑时落地 motd.sh 模板。motd.sh 是被 login 直接执行的脚本（缺执行位时 login 会自动
 * chmod u+x），纯文本须包在 heredoc 内才原样输出，模板默认给出该结构，用户改文本行即可。
 */
private fun ensureMotdScript() {
    try {
        val file = File(MOTD_SCRIPT_PATH)
        if (file.exists()) return
        file.parentFile?.mkdirs()
        file.writeText(
            "#!/data/data/com.termux/files/usr/bin/sh\n" +
                "# 欢迎文本脚本：每次打开新终端会话时执行，输出内容即欢迎文本（优先于 /usr/etc/motd）。\n" +
                "# 在下面两个 MOTD_EOF 之间编辑多行文本即可；也可替换为任意 shell 命令。\n" +
                "cat <<'MOTD_EOF'\n" +
                "Welcome to Termux!\n" +
                "MOTD_EOF\n"
        )
    } catch (_: Throwable) {}
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
