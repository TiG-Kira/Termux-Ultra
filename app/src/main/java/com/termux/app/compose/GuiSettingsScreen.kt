package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Javascript
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
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
import androidx.core.app.NotificationManagerCompat
import com.termux.R
import com.termux.gui.GUIService
import com.termux.gui.Settings
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Termux:GUI 设置页（独立 Activity）。
 *
 * 原为 vendor 里的旧式 Preference 布局（已删除的 GUIConfigActivity XML 表单），
 * 现按 Termux Ultra 的 UI 范式重做：玻璃顶栏 + SettingCard + miuix 偏好组件，与
 * [ConsoleSettingsScreen] / IntegratedToolsConfigScreen 同款视觉。设置项仍读写
 * [com.termux.gui.Settings] 单例（GUIService 同进程直接读），改动即时落盘、无保存按钮。
 *
 * 顺带补上旧实现漏接的「允许 JavaScript 免对话框」开关：旧布局有开关但 Activity 从未读写
 * Settings.javascript，实际不生效。
 */
@Composable
fun GuiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 本页在 MainScreen 取景层之外，自建一层供玻璃顶栏折射页面内容
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()

    var timeout by remember { mutableStateOf(Settings.instance.timeout) }
    var background by remember { mutableStateOf(Settings.instance.background) }
    var loglevel by remember { mutableStateOf(Settings.instance.loglevel) }
    var javascript by remember { mutableStateOf(Settings.instance.javascript) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var timeoutInput by remember(showTimeoutDialog) { mutableStateOf(timeout.toString()) }

    // 导出 logcat：CreateDocument 选文件 → 后台线程写 logcat -d → toast → 清空缓冲区
    val logcatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            Thread {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    ProcessBuilder().command("logcat", "-d").start().inputStream.use { it.copyTo(out) }
                }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, R.string.gui_logcat_written, Toast.LENGTH_SHORT).show()
                }
                // 导出后清空，与旧实现一致
                ProcessBuilder().command("logcat", "-c").start()
            }.start()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.gui_settings),
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
                item(key = "section_service") {
                    SmallTitle(text = stringResource(R.string.gui_settings_service))
                }
                item(key = "card_service") {
                    SettingCard {
                        Column {
                            ArrowPreference(
                                title = stringResource(R.string.gui_service_timeout),
                                summary = if (timeout < 0) {
                                    stringResource(R.string.gui_service_timeout_never)
                                } else {
                                    stringResource(R.string.gui_service_timeout_value, timeout)
                                },
                                onClick = { showTimeoutDialog = true },
                                startAction = { SettingIcon(Icons.Rounded.Timer) }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.gui_service_background),
                                summary = stringResource(R.string.gui_service_background_desc),
                                checked = background,
                                onCheckedChange = {
                                    background = it
                                    Settings.instance.background = it
                                    Settings.instance.save(context)
                                },
                                startAction = { SettingIcon(Icons.Rounded.Notifications) }
                            )
                        }
                    }
                }

                item(key = "section_log") {
                    SmallTitle(text = stringResource(R.string.gui_settings_log))
                }
                item(key = "card_log") {
                    SettingCard {
                        Column {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.gui_loglevel),
                                summary = stringResource(R.string.gui_loglevel_desc),
                                items = listOf("0", "1", "2", "3", "4", "5"),
                                selectedIndex = loglevel.coerceIn(0, 5),
                                onSelectedIndexChange = { idx ->
                                    loglevel = idx
                                    Settings.instance.loglevel = idx
                                    Settings.instance.save(context)
                                },
                                startAction = { SettingIcon(Icons.Rounded.Description) }
                            )
                            // 通知渠道 API 26+ 才有；低版本直接隐藏（与旧实现一致）
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                ArrowPreference(
                                    title = stringResource(R.string.gui_delete_channels),
                                    summary = stringResource(R.string.gui_delete_channels_desc),
                                    onClick = { deleteNotificationChannels(context) },
                                    startAction = { SettingIcon(Icons.Rounded.DeleteSweep) }
                                )
                            }
                            ArrowPreference(
                                title = stringResource(R.string.gui_dump_logcat),
                                summary = stringResource(R.string.gui_dump_logcat_desc),
                                onClick = { logcatLauncher.launch("termux-gui-logcat.txt") },
                                startAction = { SettingIcon(Icons.Rounded.SaveAlt) }
                            )
                        }
                    }
                }

                item(key = "section_webview") {
                    SmallTitle(text = stringResource(R.string.gui_settings_webview))
                }
                item(key = "card_webview") {
                    SettingCard {
                        SwitchPreference(
                            title = stringResource(R.string.gui_javascript),
                            summary = stringResource(R.string.gui_javascript_desc),
                            checked = javascript,
                            onCheckedChange = {
                                javascript = it
                                Settings.instance.javascript = it
                                Settings.instance.save(context)
                            },
                            startAction = { SettingIcon(Icons.Rounded.Javascript) }
                        )
                    }
                }
            }
        }

        // ---------- 服务超时输入弹窗 ----------
        OverlayDialog(
            title = stringResource(R.string.gui_service_timeout),
            show = showTimeoutDialog,
            onDismissRequest = { showTimeoutDialog = false },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = stringResource(R.string.gui_service_timeout_desc))
                    TextField(
                        value = timeoutInput,
                        onValueChange = { v ->
                            // 只放行空串 / 纯负号 / 合法整数，其余输入直接丢弃
                            if (v.isEmpty() || v == "-" || v.toIntOrNull() != null) timeoutInput = v
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.gui_service_timeout),
                        useLabelAsPlaceholder = true
                    )
                    Row(horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showTimeoutDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(16.dp))
                        TextButton(
                            text = stringResource(R.string.save),
                            onClick = {
                                timeoutInput.toIntOrNull()?.let { v ->
                                    timeout = v
                                    Settings.instance.timeout = v
                                    Settings.instance.save(context)
                                }
                                showTimeoutDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        )
    }
}

/**
 * 删除 GUI 插件创建的所有通知渠道：GUIService 的 [GUIService.CHANNEL_ID] 前台服务渠道 +
 * 脚本经协议创建的渠道（[Settings.channels] 跟踪）。
 *
 * 宿主自己的渠道（termux_notification_channel 等）不在此列——内置化后与应用同包，删除全部
 * 渠道会误删宿主渠道；而前台服务占用中的渠道删除即抛 SecurityException（曾因此闪退）。
 * 单个渠道删除失败（不存在/被占用）只跳过，不影响其余渠道。
 */
private fun deleteNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val m = NotificationManagerCompat.from(context)
        val ids = LinkedHashSet<String>()
        ids.add(GUIService.CHANNEL_ID)
        ids.addAll(Settings.instance.channels)
        for (id in ids) {
            try {
                m.deleteNotificationChannel(id)
            } catch (e: Exception) {
                Log.w("GuiSettings", "delete notification channel failed: $id", e)
            }
        }
        Settings.instance.channels.clear()
        Settings.instance.save(context)
        Toast.makeText(context, R.string.gui_channels_deleted, Toast.LENGTH_SHORT).show()
    }
}

/** 打开 GUI 设置页。返回键由 Activity 栈自然回到「配置集成工具」页，无需手动回传结果。 */
fun openGuiSettings(context: Context) {
    context.startActivity(Intent(context, com.termux.app.activities.GuiSettingsActivity::class.java))
}

/**
 * 设置项左侧图标：圆角方块底 + 24dp 矢量图标。与 ConsoleSettingsScreen 等同款视觉
 * （各设置页文件各持一份私有副本，本项目现状）。
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
