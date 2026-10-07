package com.termux.app.compose

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.awkoo.libterminal.engine.TerminalCursorStyle
import com.termux.R
import com.termux.app.terminal.shell.ComposeTerminalSettings
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 控制台设置页（独立 Activity）：字体大小、光标闪烁、光标样式、文本闪烁、滚动缓冲区。
 *
 * 与 AgentPaw / 集成工具设置页保持同一套 UI 范式：玻璃顶栏 + SettingCard + 偏好即 StateFlow
 * （经 [ComposeTerminalSettings] 读写，改动即时生效，无需保存按钮）。
 */
@Composable
fun ConsoleSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()

    ComposeTerminalSettings.init(context)
    val fontSize by ComposeTerminalSettings.fontSize.collectAsState()
    val cursorBlink by ComposeTerminalSettings.cursorBlink.collectAsState()
    val cursorStyleName by ComposeTerminalSettings.cursorStyleName.collectAsState()
    val textBlinking by ComposeTerminalSettings.textBlinking.collectAsState()
    val scrollbackLines by ComposeTerminalSettings.scrollbackLines.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.console_settings),
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
                item(key = "section_cursor") {
                    SmallTitle(text = stringResource(R.string.cursor_settings))
                }
                item(key = "card_cursor") {
                    SettingCard {
                        Column {
                            SwitchPreference(
                                title = stringResource(R.string.cursor_blink),
                                summary = if (cursorBlink) stringResource(R.string.enabled) else stringResource(R.string.disabled),
                                checked = cursorBlink,
                                onCheckedChange = { ComposeTerminalSettings.setCursorBlink(it) },
                                startAction = { SettingIcon(Icons.Rounded.Terminal) }
                            )
                            OverlayDropdownPreference(
                                title = stringResource(R.string.cursor_style),
                                summary = stringResource(R.string.cursor_style_desc),
                                items = listOf("Bar I", "Underline ▁", "Block ■"),
                                selectedIndex = listOf("BAR", "UNDERLINE", "BLOCK").indexOf(cursorStyleName).coerceAtLeast(0),
                                onSelectedIndexChange = { idx ->
                                    ComposeTerminalSettings.setCursorStyle(
                                        TerminalCursorStyle.valueOf(listOf("BAR", "UNDERLINE", "BLOCK")[idx])
                                    )
                                },
                                startAction = { SettingIcon(Icons.Rounded.Terminal) }
                            )
                        }
                    }
                }

                item(key = "section_text") {
                    SmallTitle(text = stringResource(R.string.text_settings))
                }
                item(key = "card_text") {
                    SettingCard {
                        Column {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.font_size),
                                summary = stringResource(R.string.font_size_desc),
                                items = listOf("10sp", "12sp", "14sp", "16sp", "18sp", "20sp", "24sp"),
                                selectedIndex = listOf(10, 12, 14, 16, 18, 20, 24).indexOf(fontSize).coerceAtLeast(0),
                                onSelectedIndexChange = { idx ->
                                    ComposeTerminalSettings.setFontSize(listOf(10, 12, 14, 16, 18, 20, 24)[idx])
                                },
                                startAction = { SettingIcon(Icons.Rounded.FormatSize) }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.text_blinking),
                                summary = stringResource(R.string.text_blinking_desc),
                                checked = textBlinking,
                                onCheckedChange = { ComposeTerminalSettings.setTextBlinking(it) },
                                startAction = { SettingIcon(Icons.Rounded.Terminal) }
                            )
                            OverlayDropdownPreference(
                                title = stringResource(R.string.scrollback_buffer),
                                summary = stringResource(R.string.scrollback_desc),
                                items = listOf(
                                    stringResource(R.string.lines_1000),
                                    stringResource(R.string.lines_5000),
                                    stringResource(R.string.lines_10000),
                                    stringResource(R.string.lines_50000)
                                ),
                                selectedIndex = listOf(1000, 5000, 10000, 50000).indexOf(scrollbackLines).coerceAtLeast(0),
                                onSelectedIndexChange = { idx ->
                                    ComposeTerminalSettings.setScrollbackLines(listOf(1000, 5000, 10000, 50000)[idx])
                                },
                                startAction = { SettingIcon(Icons.Rounded.ScreenRotation) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 打开控制台设置页。返回键由 Activity 栈自然回到设置页，无需手动回传结果。 */
fun openConsoleSettings(context: Context) {
    context.startActivity(Intent(context, com.termux.app.activities.ConsoleSettingsActivity::class.java))
}

/**
 * 设置项左侧图标：圆角方块底 + 24dp 矢量图标。与 SettingsScreen / IntegratedToolsConfig 同款视觉。
 */
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
