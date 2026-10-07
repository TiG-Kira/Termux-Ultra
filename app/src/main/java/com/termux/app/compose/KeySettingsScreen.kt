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
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Keyboard
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
import com.termux.R
import com.termux.app.terminal.shell.ComposeTerminalSettings
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 按键设置页（独立 Activity）：启用软键盘、使用物理键盘时禁用软键盘、使用自定义按键布局。
 *
 * 与控制台设置页同套 UI 范式（玻璃顶栏 + SettingCard + StateFlow 即时生效）。
 */
@Composable
fun KeySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()

    ComposeTerminalSettings.init(context)
    val softKeyboard by ComposeTerminalSettings.softKeyboard.collectAsState()
    val softKeyboardOnlyIfNoHardware by ComposeTerminalSettings.softKeyboardOnlyIfNoHardware.collectAsState()
    val useCustomKeyboardLayout by ComposeTerminalSettings.useCustomKeyboardLayout.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.key_settings),
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
                item(key = "card_key") {
                    SettingCard {
                        Column {
                            SwitchPreference(
                                title = stringResource(R.string.enable_softkeyboard),
                                summary = if (softKeyboard) stringResource(R.string.enabled) else stringResource(R.string.disabled),
                                checked = softKeyboard,
                                onCheckedChange = { ComposeTerminalSettings.setSoftKeyboard(it) },
                                startAction = { SettingIcon(Icons.Rounded.Keyboard) }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.enable_soft_keyboard_no_hw),
                                summary = stringResource(R.string.soft_keyboard_only_if_no_hardware_desc),
                                checked = softKeyboardOnlyIfNoHardware,
                                onCheckedChange = { ComposeTerminalSettings.setSoftKeyboardOnlyIfNoHardware(it) },
                                startAction = { SettingIcon(Icons.Rounded.DesktopWindows) }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.use_custom_keyboard_layout),
                                summary = stringResource(R.string.use_custom_keyboard_layout_desc),
                                checked = useCustomKeyboardLayout,
                                onCheckedChange = { ComposeTerminalSettings.setUseCustomKeyboardLayout(it) },
                                startAction = { SettingIcon(Icons.Rounded.Dashboard) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 打开按键设置页。返回键由 Activity 栈自然回到设置页，无需手动回传结果。 */
fun openKeySettings(context: Context) {
    context.startActivity(Intent(context, com.termux.app.activities.KeySettingsActivity::class.java))
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
