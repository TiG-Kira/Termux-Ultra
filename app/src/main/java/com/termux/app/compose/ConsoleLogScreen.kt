package com.termux.app.compose

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeveloperMode
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
import com.termux.app.terminal.shell.ComposeTerminalSettings
import com.termux.shared.logger.Logger
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
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
 * 控制台日志页（独立 Activity）：终端按键日志、日志级别。
 *
 * 与控制台设置页同套 UI 范式（玻璃顶栏 + SettingCard + 即时生效）。按键日志走
 * [ComposeTerminalSettings] 的 StateFlow；日志级别走 [TermuxAppSharedPreferences]（同时被 Logger 消费）。
 */
@Composable
fun ConsoleLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()

    ComposeTerminalSettings.init(context)
    val keyLogging by ComposeTerminalSettings.keyLogging.collectAsState()

    val terminalPrefs = remember { TermuxAppSharedPreferences.build(context) }
    var logLevel by remember { mutableStateOf(terminalPrefs?.logLevel ?: Logger.DEFAULT_LOG_LEVEL) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.console_log),
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
                item(key = "section_log") {
                    SmallTitle(text = stringResource(R.string.console_log))
                }
                item(key = "card_log") {
                    SettingCard {
                        Column {
                            SwitchPreference(
                                title = stringResource(R.string.terminal_key_logging),
                                summary = stringResource(R.string.terminal_key_logging_desc),
                                checked = keyLogging,
                                onCheckedChange = { ComposeTerminalSettings.setKeyLogging(it) },
                                startAction = { SettingIcon(Icons.Rounded.DeveloperMode) }
                            )
                            OverlayDropdownPreference(
                                title = stringResource(R.string.log_level),
                                summary = stringResource(R.string.log_level_desc),
                                items = listOf(
                                    stringResource(R.string.off),
                                    stringResource(R.string.normal),
                                    stringResource(R.string.debug),
                                    stringResource(R.string.verbose)
                                ),
                                selectedIndex = logLevel.coerceIn(0, 3),
                                onSelectedIndexChange = { idx ->
                                    logLevel = idx
                                    terminalPrefs?.setLogLevel(context, idx)
                                },
                                startAction = { SettingIcon(Icons.Rounded.BugReport) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 打开控制台日志页。返回键由 Activity 栈自然回到设置页，无需手动回传结果。 */
fun openConsoleLog(context: Context) {
    context.startActivity(Intent(context, com.termux.app.activities.ConsoleLogActivity::class.java))
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
