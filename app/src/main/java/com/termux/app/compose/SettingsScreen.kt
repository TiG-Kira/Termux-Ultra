package com.termux.app.compose

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Divider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.LinearProgressIndicator
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import com.termux.R
import com.termux.app.LocaleHelper
import com.termux.app.activities.SettingsActivity
import java.io.File


data class SettingItem(
    val title: String,
    val description: String,
    val iconRes: Int,
    val action: () -> Unit,
    val hasSwitch: Boolean = false,
    val switchValue: Boolean = false,
    val onSwitchChange: (Boolean) -> Unit = {}
)

/** Copy a command string to the system clipboard and show a short toast. */
private fun clipCopy(context: Context, command: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("termux-cmd", command))
    }.onFailure { /* ignore */ }
    val message = if (command.length > 48) command.take(48) + "…" else command
    Toast.makeText(context, "Copied: $message", Toast.LENGTH_SHORT).show()
}

/** One row of Termux:API / Boot help: clickable (whole-row) command block + 1-line description on the right. */
@Composable
private fun CopyCommandRow(
    command: String,
    description: String,
    onClickCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surface)
            .clickable { onClickCopy() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = command,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            if (description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "COPY",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.primary
        )
    }
}

@Composable
fun SettingsScreen(onAboutClick: () -> Unit) {
    val context = LocalContext.current
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var showRestoreProgressDialog by remember { mutableStateOf(false) }
    var selectedBackupFile by remember { mutableStateOf<File?>(null) }
    var backupFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var restoreProgress by remember { mutableStateOf(0) }
    var restoreTotal by remember { mutableStateOf(100) }
    var restoreMessage by remember { mutableStateOf("") }
    var launchRestore by remember { mutableStateOf(false) }
    var showRestartPrompt by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var vncEnabled by remember { mutableStateOf(prefs.getBoolean("vnc_enabled", false)) }

    // Integrated Termux tools (default off)
    var termuxApiEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_API)) }
    var termuxBootEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_BOOT)) }
    var termuxStylingEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_STYLING)) }
    var termuxTaskerEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_TASKER)) }
    var termuxWidgetEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_WIDGET)) }

    // Official standalone APK detection. Keys match the add-on app package names; when a standalone
    // APK is installed, the integrated toggle is forced OFF and disabled, with the row shows
    // "Replaced by the official standalone plugin" instead of the normal help summary.
    val apiStandaloneInstalled = IntegratedTools.isStandaloneInstalled(context, IntegratedTools.Tool.TERMUX_API)
    val bootStandaloneInstalled = IntegratedTools.isStandaloneInstalled(context, IntegratedTools.Tool.TERMUX_BOOT)
    val stylingStandaloneInstalled = IntegratedTools.isStandaloneInstalled(context, IntegratedTools.Tool.TERMUX_STYLING)
    val taskerStandaloneInstalled = IntegratedTools.isStandaloneInstalled(context, IntegratedTools.Tool.TERMUX_TASKER)
    val widgetStandaloneInstalled = IntegratedTools.isStandaloneInstalled(context, IntegratedTools.Tool.TERMUX_WIDGET)

    val replacedSummary = context.getString(R.string.standalone_plugin_installed_summary)

    // Tool configuration / help dialog visibility
    var showApiHelpDialog by remember { mutableStateOf(false) }
    var showBootHelpDialog by remember { mutableStateOf(false) }

    val languageOptions = listOf(
        context.getString(R.string.chinese),
        context.getString(R.string.english)
    )
    var languageSelectedIndex by remember {
        mutableStateOf(if (LocaleHelper.isChinese(context)) 0 else 1)
    }

    val scrollBehavior = MiuixScrollBehavior()

    val restoreFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            if (!isProcessing) {
                isProcessing = true
                android.widget.Toast.makeText(context, context.getString(R.string.restore_view_progress_toast), android.widget.Toast.LENGTH_SHORT).show()
                NotificationHelper.createNotificationChannel(context)

                val cancelIntent = Intent("com.termux.RESTORE_CANCEL")
                val pendingCancelIntent = PendingIntent.getBroadcast(context, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val cancelReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        BackupManager.cancelRestore()
                    }
                }
                context.registerReceiver(cancelReceiver, IntentFilter("com.termux.RESTORE_CANCEL"))

                val restoreTitle = context.getString(R.string.restore_in_progress)
                NotificationHelper.showProgressNotification(context, restoreTitle, 0, -1, context.getString(R.string.initializing), pendingCancelIntent)
                val mainHandler = Handler(Looper.getMainLooper())
                Thread {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val tempFile = File(context.cacheDir, "temp_backup.tar")
                    inputStream?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    val result = BackupManager.restoreBackup(context, tempFile.absolutePath) { processed, total, message ->
                        val display = when {
                            message.isNotBlank() -> message
                            processed > 0 -> context.getString(R.string.restored_size, processed)
                            else -> context.getString(R.string.initializing)
                        }
                        mainHandler.post {
                            NotificationHelper.showProgressNotification(context, restoreTitle, 0, total, display, pendingCancelIntent)
                        }
                    }
                    tempFile.delete()

                    mainHandler.post {
                        isProcessing = false
                        context.unregisterReceiver(cancelReceiver)
                        if (result) {
                            NotificationHelper.showCompleteNotification(context, context.getString(R.string.restore_complete), context.getString(R.string.restore_restart_hint), true)
                        } else {
                            NotificationHelper.showCompleteNotification(context, context.getString(R.string.restore_failed), context.getString(R.string.restore_failed_error), false)
                        }
                    }
                }.start()
            }
        }
    }

    LaunchedEffect(launchRestore) {
        if (launchRestore) {
            restoreFileLauncher.launch(arrayOf("application/zip", "application/x-tar", "application/gzip", "application/x-gzip", "application/x-xz", "application/octet-stream", "*/*"))
            launchRestore = false
        }
    }

    val remoteSettings = listOfNotNull(
        if (vncEnabled) SettingItem(
            title = context.getString(R.string.vnc_settings),
            description = context.getString(R.string.vnc_settings_desc),
            iconRes = R.drawable.ic_vnc_settings,
            action = {
                val intent = Intent(context, com.gaurav.avnc.ui.prefs.PrefsActivity::class.java)
                context.startActivity(intent)
            }
        ) else null
    )

    val dataSettings = listOf(
        SettingItem(
            title = context.getString(R.string.backup),
            description = context.getString(R.string.backup_description),
            iconRes = R.drawable.ic_backup,
            action = {
                if (!isProcessing) {
                    isProcessing = true
                    android.widget.Toast.makeText(context, context.getString(R.string.backup_view_progress_toast), android.widget.Toast.LENGTH_SHORT).show()
                    NotificationHelper.createNotificationChannel(context)

                    val cancelIntent = Intent("com.termux.BACKUP_CANCEL")
                    val pendingCancelIntent = PendingIntent.getBroadcast(context, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                    val cancelReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            BackupManager.cancelBackup()
                        }
                    }
                    context.registerReceiver(cancelReceiver, IntentFilter("com.termux.BACKUP_CANCEL"))

                    val backupTitle = context.getString(R.string.backup_in_progress)
                    NotificationHelper.showProgressNotification(context, backupTitle, 0, -1, context.getString(R.string.initializing), pendingCancelIntent)
                    val mainHandler = Handler(Looper.getMainLooper())
                    Thread {
                        val backupPath = BackupManager.createBackup(context) { processed, total, message ->
                            val display = when {
                                message.isNotBlank() -> message
                                processed > 0 -> context.getString(R.string.backed_up_size, processed)
                                else -> context.getString(R.string.initializing)
                            }
                            mainHandler.post {
                                NotificationHelper.showProgressNotification(context, backupTitle, 0, total, display, pendingCancelIntent)
                            }
                        }
                        mainHandler.post {
                            isProcessing = false
                            context.unregisterReceiver(cancelReceiver)
                            if (backupPath != null) {
                                NotificationHelper.showCompleteNotification(context, context.getString(R.string.backup_complete), backupPath, true)
                            } else {
                                NotificationHelper.showCompleteNotification(context, context.getString(R.string.backup_cancelled), context.getString(R.string.backup_cancelled), false)
                            }
                        }
                    }.start()
                }
            }
        ),
        SettingItem(
            title = context.getString(R.string.restore),
            description = context.getString(R.string.restore_description),
            iconRes = R.drawable.ic_restore,
            action = {
                launchRestore = true
            }
        )
    )

    val systemSettings = listOf(
        SettingItem(
            title = context.getString(R.string.termux_settings),
            description = context.getString(R.string.termux_settings_description),
            iconRes = R.drawable.ic_settings,
            action = {
                val intent = Intent(context, SettingsActivity::class.java)
                context.startActivity(intent)
            }
        ),
        SettingItem(
            title = context.getString(R.string.about_preference_title),
            description = context.getString(R.string.about_description),
            iconRes = R.drawable.ic_info,
            action = { onAboutClick() }
        )
    )

    // Tool configuration entries — only shown for tools that are enabled. Tools with a dedicated
    // settings UI open their Activity; tools without one (API/Boot) show a usage guide dialog.
    val toolConfigItems = remember(
        termuxApiEnabled, termuxBootEnabled, termuxStylingEnabled,
        termuxTaskerEnabled, termuxWidgetEnabled
    ) {
        buildList {
            if (termuxApiEnabled) {
                add(SettingItem(
                    title = context.getString(R.string.termux_api_help),
                    description = context.getString(R.string.termux_api_help_summary),
                    iconRes = R.drawable.ic_terminal,
                    action = { showApiHelpDialog = true }
                ))
            }
            if (termuxBootEnabled) {
                add(SettingItem(
                    title = context.getString(R.string.termux_boot_help),
                    description = context.getString(R.string.termux_boot_help_summary),
                    iconRes = R.drawable.ic_launch,
                    action = { showBootHelpDialog = true }
                ))
            }
            if (termuxStylingEnabled) {
                add(SettingItem(
                    title = context.getString(R.string.termux_styling_config),
                    description = context.getString(R.string.termux_styling_config_summary),
                    iconRes = R.drawable.ic_palette,
                    action = {
                        val intent = Intent().apply {
                            component = ComponentName(context.packageName, "com.termux.styling.TermuxStyleActivity")
                        }
                        runCatching { context.startActivity(intent) }
                    }
                ))
            }
            if (termuxTaskerEnabled) {
                add(SettingItem(
                    title = context.getString(R.string.termux_tasker_config),
                    description = context.getString(R.string.termux_tasker_config_summary),
                    iconRes = R.drawable.ic_tools,
                    action = {
                        val intent = Intent().apply {
                            component = ComponentName(context.packageName, "com.termux.tasker.EditConfigurationActivity")
                        }
                        runCatching { context.startActivity(intent) }
                    }
                ))
            }
            if (termuxWidgetEnabled) {
                add(SettingItem(
                    title = context.getString(R.string.termux_widget_config),
                    description = context.getString(R.string.termux_widget_config_summary),
                    iconRes = R.drawable.ic_star,
                    action = {
                        val intent = Intent().apply {
                            component = ComponentName(context.packageName, "com.termux.widget.activities.TermuxWidgetActivity")
                        }
                        runCatching { context.startActivity(intent) }
                    }
                ))
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = context.getString(R.string.settings_title), scrollBehavior = scrollBehavior)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            // ---------- Appearance ----------
            item { SmallTitle(text = context.getString(R.string.appearance)) }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    OverlayDropdownPreference(
                        title = context.getString(R.string.language),
                        summary = context.getString(R.string.language_description),
                        items = languageOptions,
                        selectedIndex = languageSelectedIndex,
                        onSelectedIndexChange = { idx ->
                            languageSelectedIndex = idx
                            // Apply without restarting; show prompt so users restart.
                            if (idx == 0) {
                                LocaleHelper.setChinese(context)
                            } else {
                                LocaleHelper.setEnglish(context)
                            }
                            showRestartPrompt = true
                        },
                        startAction = {
                            SettingIcon(R.drawable.ic_language, contentDescription = context.getString(R.string.language))
                        }
                    )
                }
            }

            // ---------- Remote ----------
            item { SmallTitle(text = context.getString(R.string.remote)) }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column {
                        SwitchPreference(
                            title = context.getString(R.string.vnc),
                            summary = context.getString(R.string.vnc_description),
                            checked = vncEnabled,
                            onCheckedChange = {
                                vncEnabled = it
                                prefs.edit().putBoolean("vnc_enabled", it).apply()
                                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                context.startActivity(intent)
                            },
                            startAction = {
                                SettingIcon(R.drawable.ic_vnc, contentDescription = context.getString(R.string.vnc))
                            }
                        )
                        remoteSettings.firstOrNull()?.let { item ->
                            Divider(color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f))
                            ArrowPreference(
                                title = item.title,
                                summary = item.description,
                                onClick = item.action,
                                startAction = {
                                    SettingIcon(item.iconRes, contentDescription = item.title)
                                }
                            )
                        }
                    }
                }
            }

            // ---------- Data Backup ----------
            item { SmallTitle(text = context.getString(R.string.backup_category)) }
            item { SettingsGroupCard(items = dataSettings) }

            // ---------- Integrated Tools ----------
            item { SmallTitle(text = context.getString(R.string.integrated_tools_category)) }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column {
                        IntegratedToolSwitch(
                            title = context.getString(R.string.termux_api_tool),
                            summary = if (apiStandaloneInstalled) replacedSummary
                                      else context.getString(R.string.termux_api_tool_summary),
                            iconRes = R.drawable.ic_terminal,
                            checked = termuxApiEnabled,
                            onCheckedChange = {
                                termuxApiEnabled = it
                                IntegratedTools.setEnabled(context, IntegratedTools.Tool.TERMUX_API, it)
                                IntegratedTools.applyComponentState(context, IntegratedTools.Tool.TERMUX_API, it)
                            },
                            enabled = !apiStandaloneInstalled,
                            onDisabledClick = {
                                IntegratedTools.showStandaloneConflictPrompt(context, IntegratedTools.Tool.TERMUX_API)
                            }
                        )
                        IntegratedToolSwitch(
                            title = context.getString(R.string.termux_boot_tool),
                            summary = if (bootStandaloneInstalled) replacedSummary
                                      else context.getString(R.string.termux_boot_tool_summary),
                            iconRes = R.drawable.ic_launch,
                            checked = termuxBootEnabled,
                            onCheckedChange = {
                                termuxBootEnabled = it
                                IntegratedTools.setEnabled(context, IntegratedTools.Tool.TERMUX_BOOT, it)
                                // Enable/disable the boot receiver component to match the toggle.
                                IntegratedTools.applyComponentState(context, IntegratedTools.Tool.TERMUX_BOOT, it)
                            },
                            enabled = !bootStandaloneInstalled,
                            onDisabledClick = {
                                IntegratedTools.showStandaloneConflictPrompt(context, IntegratedTools.Tool.TERMUX_BOOT)
                            }
                        )
                        IntegratedToolSwitch(
                            title = context.getString(R.string.termux_styling_tool),
                            summary = if (stylingStandaloneInstalled) replacedSummary
                                      else context.getString(R.string.termux_styling_tool_summary),
                            iconRes = R.drawable.ic_palette,
                            checked = termuxStylingEnabled,
                            onCheckedChange = {
                                termuxStylingEnabled = it
                                IntegratedTools.setEnabled(context, IntegratedTools.Tool.TERMUX_STYLING, it)
                                IntegratedTools.applyComponentState(context, IntegratedTools.Tool.TERMUX_STYLING, it)
                            },
                            enabled = !stylingStandaloneInstalled,
                            onDisabledClick = {
                                IntegratedTools.showStandaloneConflictPrompt(context, IntegratedTools.Tool.TERMUX_STYLING)
                            }
                        )
                        IntegratedToolSwitch(
                            title = context.getString(R.string.termux_tasker_tool),
                            summary = if (taskerStandaloneInstalled) replacedSummary
                                      else context.getString(R.string.termux_tasker_tool_summary),
                            iconRes = R.drawable.ic_tools,
                            checked = termuxTaskerEnabled,
                            onCheckedChange = {
                                termuxTaskerEnabled = it
                                IntegratedTools.setEnabled(context, IntegratedTools.Tool.TERMUX_TASKER, it)
                                IntegratedTools.applyComponentState(context, IntegratedTools.Tool.TERMUX_TASKER, it)
                            },
                            enabled = !taskerStandaloneInstalled,
                            onDisabledClick = {
                                IntegratedTools.showStandaloneConflictPrompt(context, IntegratedTools.Tool.TERMUX_TASKER)
                            }
                        )
                        IntegratedToolSwitch(
                            title = context.getString(R.string.termux_widget_tool),
                            summary = if (widgetStandaloneInstalled) replacedSummary
                                      else context.getString(R.string.termux_widget_tool_summary),
                            iconRes = R.drawable.ic_star,
                            checked = termuxWidgetEnabled,
                            onCheckedChange = {
                                termuxWidgetEnabled = it
                                IntegratedTools.setEnabled(context, IntegratedTools.Tool.TERMUX_WIDGET, it)
                                IntegratedTools.applyComponentState(context, IntegratedTools.Tool.TERMUX_WIDGET, it)
                            },
                            enabled = !widgetStandaloneInstalled,
                            onDisabledClick = {
                                IntegratedTools.showStandaloneConflictPrompt(context, IntegratedTools.Tool.TERMUX_WIDGET)
                            }
                        )
                    }
                }
            }

            // ---------- Tool Configuration (conditional, only for enabled tools) ----------
            if (toolConfigItems.isNotEmpty()) {
                item { SmallTitle(text = context.getString(R.string.tool_config_category)) }
                item { SettingsGroupCard(items = toolConfigItems) }
            }

            // ---------- System ----------
            item { SmallTitle(text = context.getString(R.string.system_category)) }
            item { SettingsGroupCard(items = systemSettings) }

            // Extra bottom spacing for comfortable scroll
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // ---------- Language restart prompt ----------
    OverlayDialog(
        title = context.getString(R.string.restart_required),
        summary = context.getString(R.string.language_restart_message),
        show = showRestartPrompt,
        onDismissRequest = { showRestartPrompt = false }
    ) {
        TextButton(
            text = context.getString(R.string.ok),
            onClick = { showRestartPrompt = false },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // ---------- Termux:API usage guide ----------
    OverlayDialog(
        title = context.getString(R.string.termux_api_help),
        show = showApiHelpDialog,
        onDismissRequest = { showApiHelpDialog = false }
    ) {
        Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            // Install hint
            Text(
                text = context.getString(R.string.termux_api_setup_hint),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                lineHeight = 20.sp
            )
            CopyCommandRow(
                command = "pkg install termux-api",
                description = "",
                onClickCopy = { clipCopy(context, "pkg install termux-api") }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = context.getString(R.string.termux_api_commands_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            val apiCmds = listOf(
                "termux-battery-status" to context.getString(R.string.cmd_battery_status),
                "termux-camera-photo -c 0 photo.jpg" to context.getString(R.string.cmd_camera_photo),
                "termux-clipboard-get" to context.getString(R.string.cmd_clipboard_get),
                "termux-clipboard-set \"hello\"" to context.getString(R.string.cmd_clipboard_set),
                "termux-notification --title Hi --content Hello" to context.getString(R.string.cmd_notification),
                "termux-toast \"message\"" to context.getString(R.string.cmd_toast),
                "termux-vibrate -d 500" to context.getString(R.string.cmd_vibrate),
                "termux-location" to context.getString(R.string.cmd_location),
                "termux-sms-list -l 5" to context.getString(R.string.cmd_sms_list),
                "termux-sms-send -n 10086 \"hello\"" to context.getString(R.string.cmd_sms_send),
                "termux-tts-speak \"hello world\"" to context.getString(R.string.cmd_tts_speak),
                "termux-wifi-connectioninfo" to context.getString(R.string.cmd_wifi_info)
            )
            apiCmds.forEach { (cmd, desc) ->
                CopyCommandRow(
                    command = cmd,
                    description = desc,
                    onClickCopy = { clipCopy(context, cmd) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = context.getString(R.string.ok),
            onClick = { showApiHelpDialog = false },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // ---------- Termux:Boot startup guide ----------
    OverlayDialog(
        title = context.getString(R.string.termux_boot_help),
        show = showBootHelpDialog,
        onDismissRequest = { showBootHelpDialog = false }
    ) {
        Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text(
                text = context.getString(R.string.termux_boot_help_content),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = context.getString(R.string.boot_clickable_examples_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            val bootCmds = listOf(
                "mkdir -p ~/.termux/boot" to context.getString(R.string.boot_cmd_mkdir_desc),
                "#!/data/data/com.termux/files/usr/bin/sh" to context.getString(R.string.boot_cmd_shebang_desc),
                "termux-wake-lock" to context.getString(R.string.boot_cmd_wakelock_desc),
                "sshd" to context.getString(R.string.boot_cmd_sshd_desc)
            )
            bootCmds.forEach { (cmd, desc) ->
                CopyCommandRow(
                    command = cmd,
                    description = desc,
                    onClickCopy = { clipCopy(context, cmd) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = context.getString(R.string.ok),
            onClick = { showBootHelpDialog = false },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // ---------- Restore: choose backup file ----------
    OverlayDialog(
        title = context.getString(R.string.restore),
        show = showRestoreDialog,
        onDismissRequest = { showRestoreDialog = false }
    ) {
        Column(modifier = Modifier.heightIn(max = 300.dp)) {
            if (backupFiles.isEmpty()) {
                Text(
                    text = context.getString(R.string.no_backup_files),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
            } else {
                backupFiles.forEach { file ->
                    Text(
                        text = file.name,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                selectedBackupFile = file
                                showRestoreDialog = false
                                showRestoreConfirmDialog = true
                            },
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = context.getString(R.string.cancel),
                onClick = { showRestoreDialog = false },
                modifier = Modifier.weight(1f)
            )
        }
    }

    // ---------- Restore: confirm ----------
    OverlayDialog(
        title = context.getString(R.string.restore),
        summary = context.getString(R.string.restore_confirm_message),
        show = showRestoreConfirmDialog,
        onDismissRequest = { showRestoreConfirmDialog = false }
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = context.getString(R.string.cancel),
                onClick = { showRestoreConfirmDialog = false },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = context.getString(R.string.confirm),
                onClick = {
                    showRestoreConfirmDialog = false
                    selectedBackupFile?.let { file ->
                        if (!isProcessing) {
                            isProcessing = true
                            restoreProgress = 0
                            restoreMessage = context.getString(R.string.initializing)
                            showRestoreProgressDialog = true
                            val mainHandler = Handler(Looper.getMainLooper())
                            Thread {
                                val success = BackupManager.restoreBackup(context, file.absolutePath) { processed, total, message ->
                                    restoreTotal = total
                                    val progress = if (total > 0) (processed * 100 / total) else 0
                                    val display = when {
                                        message.isNotBlank() -> message
                                        processed > 0 -> context.getString(R.string.restored_size, processed)
                                        else -> context.getString(R.string.initializing)
                                    }
                                    mainHandler.post {
                                        restoreProgress = progress
                                        restoreMessage = display
                                    }
                                }
                                mainHandler.post {
                                    isProcessing = false
                                    showRestoreProgressDialog = false
                                    if (success) {
                                        android.widget.Toast.makeText(context, context.getString(R.string.restore_complete), android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, context.getString(R.string.restore_failed), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.start()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }

    // ---------- Restore: progress ----------
    OverlayDialog(
        title = context.getString(R.string.restore),
        summary = restoreMessage,
        show = showRestoreProgressDialog,
        onDismissRequest = { BackupManager.cancelRestore() }
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            if (restoreTotal > 0) {
                LinearProgressIndicator(
                    progress = { restoreProgress.toFloat() / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "$restoreProgress%",
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurface
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = restoreMessage.ifBlank { context.getString(R.string.initializing) },
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = context.getString(R.string.cancel),
            onClick = { BackupManager.cancelRestore() },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // ---------- Result ----------
    OverlayDialog(
        title = context.getString(R.string.result),
        summary = resultMessage,
        show = showResultDialog,
        onDismissRequest = { showResultDialog = false }
    ) {
        TextButton(
            text = context.getString(R.string.ok),
            onClick = { showResultDialog = false },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingIcon(iconRes: Int, contentDescription: String?) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SettingsGroupCard(items: List<SettingItem>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column {
            items.forEachIndexed { index, item ->
                if (item.hasSwitch) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clickable(onClick = item.action),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingIcon(item.iconRes, contentDescription = item.title)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp)
                        ) {
                            Text(
                                text = item.title,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(bottom = 4.dp),
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Text(
                                text = item.description,
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                        Switch(
                            checked = item.switchValue,
                            onCheckedChange = item.onSwitchChange
                        )
                    }
                } else {
                    ArrowPreference(
                        title = item.title,
                        summary = item.description,
                        onClick = item.action,
                        startAction = {
                            SettingIcon(item.iconRes, contentDescription = item.title)
                        }
                    )
                }
                if (index < items.lastIndex) {
                    Divider(
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                        modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun IntegratedToolSwitch(
    title: String,
    summary: String,
    iconRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .let { m ->
                if (!enabled && onDisabledClick != null) {
                    m.clickable(onClick = onDisabledClick)
                } else m
            }
    ) {
        SwitchPreference(
            title = title,
            summary = summary,
            checked = checked,
            onCheckedChange = { newValue ->
                if (enabled) onCheckedChange(newValue)
                else onDisabledClick?.invoke()
            },
            startAction = {
                SettingIcon(iconRes, contentDescription = title)
            }
        )
    }
}
