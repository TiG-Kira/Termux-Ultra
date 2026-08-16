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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
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
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.LinearProgressIndicator
import com.termux.R
import com.termux.app.LocaleHelper
import com.termux.app.activities.SettingsActivity
import com.termux.app.compose.AiTermuxPrefs
import com.termux.app.compose.AiTermuxConfig
import com.termux.app.compose.SkillType
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
    var showAiReconfigConfirm by remember { mutableStateOf(false) }
    var showAiClearConfirm by remember { mutableStateOf(false) }
    var showRestartPrompt by remember { mutableStateOf(false) }
    var showSystemPromptEditor by remember { mutableStateOf(false) }
    var showCustomSkillManager by remember { mutableStateOf(false) }
    var showFullHistoryViewer by remember { mutableStateOf(false) }
    var showAddEditSkillDialog by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<CustomSkill?>(null) }
    var showSystemPromptFilePicker by remember { mutableStateOf(false) }
    var showSystemPromptRestoreConfirm by remember { mutableStateOf(false) }
    var systemPromptSource by remember { mutableStateOf("") }
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var vncEnabled by remember { mutableStateOf(prefs.getBoolean("vnc_enabled", false)) }
    var aiTermuxEnabled by remember { mutableStateOf(prefs.getBoolean("ai_termux_enabled", true)) }
    var aiDeveloperMode by remember { mutableStateOf(AiTermuxPrefs.isDeveloperMode(context)) }
    var autoExecConfig by remember { mutableStateOf(AiTermuxPrefs.getAutoExecConfig(context)) }
    var useCustomSystemPrompt by remember { mutableStateOf(AiTermuxPrefs.isUsingCustomSystemPrompt(context)) }

    // 高风险命令二次确认
    var riskConfirmEnabled by remember { mutableStateOf(RiskConfirmManager.isEnabled(context)) }

    // 监听关闭警告弹窗的结果，同步开关状态
    val disableWarningState by RiskConfirmManager.disableWarningState.collectAsState()
    LaunchedEffect(disableWarningState.show) {
        if (!disableWarningState.show) {
            riskConfirmEnabled = RiskConfirmManager.isEnabled(context)
        }
    }

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

    val navBarStyleOptions = listOf(
        context.getString(R.string.navigation_bar_default),
        context.getString(R.string.navigation_bar_floating),
        context.getString(R.string.navigation_bar_liquid_glass)
    )
    var navBarSelectedIndex by remember {
        mutableStateOf(
            when (prefs.getString("navigation_bar_style", "default")) {
                "floating" -> 1
                "liquid_glass" -> 2
                else -> 0
            }
        )
    }
    var showNavRestartPrompt by remember { mutableStateOf(false) }
    var showCriticalNavDialog by remember { mutableStateOf(false) }
    var pendingNavStyleIndex by remember { mutableStateOf(-1) }

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

    val systemPromptFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val content = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (content.isNotBlank()) {
                    AiTermuxPrefs.setCustomSystemPrompt(context, content)
                    AiTermuxPrefs.setUseCustomSystemPrompt(context, true)
                    useCustomSystemPrompt = true
                    val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "custom_prompt.md"
                    systemPromptSource = fileName
                    android.widget.Toast.makeText(context, "已加载自定义 System Prompt", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "文件内容为空", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "读取文件失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
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

    val systemSettings = remember(riskConfirmEnabled) { listOf(
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
        ),
        SettingItem(
            title = context.getString(R.string.risk_confirm_toggle_title),
            description = context.getString(R.string.risk_confirm_toggle_summary),
            iconRes = R.drawable.ic_shield,
            action = {},
            hasSwitch = true,
            switchValue = riskConfirmEnabled,
            onSwitchChange = { newVal ->
                if (newVal) {
                    riskConfirmEnabled = true
                    RiskConfirmManager.setEnabled(context, true)
                } else {
                    RiskConfirmManager.showDisableWarning()
                }
            }
        )
    )
}

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
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = 92.dp)
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
                    Column {
                        OverlayDropdownPreference(
                            title = context.getString(R.string.language),
                            summary = context.getString(R.string.language_description),
                            items = languageOptions,
                            selectedIndex = languageSelectedIndex,
                            onSelectedIndexChange = { idx ->
                                languageSelectedIndex = idx
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
                        Divider(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                            modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                        )
                        OverlayDropdownPreference(
                            title = context.getString(R.string.navigation_bar_style),
                            summary = context.getString(R.string.navigation_bar_style_description),
                            items = navBarStyleOptions,
                            selectedIndex = navBarSelectedIndex,
                            onSelectedIndexChange = { idx ->
                                if (idx == 2) {
                                    if (!ApiCompat.isFeatureUsable(context, ApiCompat.Feature.GLASS_NAVIGATION_BAR)) {
                                        pendingNavStyleIndex = idx
                                        showCriticalNavDialog = true
                                        return@OverlayDropdownPreference
                                    }
                                }
                                navBarSelectedIndex = idx
                                val style = when (idx) {
                                    1 -> "floating"
                                    2 -> "liquid_glass"
                                    else -> "default"
                                }
                                prefs.edit().putString("navigation_bar_style", style).apply()
                                showNavRestartPrompt = true
                            },
                            startAction = {
                                SettingIcon(R.drawable.ic_navigation, contentDescription = context.getString(R.string.navigation_bar_style))
                            }
                        )
                    }
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

            // ---------- AI Termux ----------
            item { SmallTitle(text = "Termux Agent") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column {
                        SwitchPreference(
                            title = "Termux Agent",
                            summary = "开启后显示终端页 Termux Agent 入口卡片及相关设置",
                            checked = aiTermuxEnabled,
                            onCheckedChange = {
                                aiTermuxEnabled = it
                                prefs.edit().putBoolean("ai_termux_enabled", it).apply()
                            },
                            startAction = {
                                SettingIcon(R.drawable.ic_lightbulb, contentDescription = "Termux Agent")
                            }
                        )
                        if (aiTermuxEnabled) {
                            Divider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            SwitchPreference(
                                title = "信任白名单",
                                summary = "允许将某些技能（如 CAPTURE_OUTPUT）设为自动执行，无需点击确认。安全由你掌控。",
                                checked = autoExecConfig.enabled,
                                onCheckedChange = { enabled ->
                                    autoExecConfig = autoExecConfig.copy(enabled = enabled)
                                    AiTermuxPrefs.saveAutoExecConfig(context, autoExecConfig)
                                },
                                startAction = {
                                    SettingIcon(R.drawable.ic_shield, contentDescription = "信任白名单")
                                }
                            )
                            if (autoExecConfig.enabled) {
                                Divider(
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                                )
                                Column(modifier = Modifier.padding(start = 72.dp)) {
                                    Text(
                                        text = "信任白名单中的技能将自动执行，无需点击确认。",
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                    Text(
                                        text = "⚠️ 仅勾选你完全信任的技能。自动执行意味着 AI 可以直接触发操作，跳过人工确认。",
                                        style = MiuixTheme.textStyles.body2,
                                        color = Color(0xFFDC2626),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )

                                    val whitelistSkills = listOf(
                                        SkillType.CAPTURE_OUTPUT to "CAPTURE_OUTPUT — 执行命令并捕获输出",
                                    )

                                    whitelistSkills.forEach { (skill, label) ->
                                        val checked = autoExecConfig.autoExecSkills.contains(skill)
                                        CheckboxPreference(
                                            title = label,
                                            checked = checked,
                                            onCheckedChange = { isChecked ->
                                                val newSet = if (isChecked) {
                                                    autoExecConfig.autoExecSkills + skill
                                                } else {
                                                    autoExecConfig.autoExecSkills - skill
                                                }
                                                autoExecConfig = autoExecConfig.copy(autoExecSkills = newSet)
                                                AiTermuxPrefs.saveAutoExecConfig(context, autoExecConfig)
                                            },
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }

                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "以下技能无需加入白名单，默认自动执行：",
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                    Text(
                                        text = "GET_CURRENT_SESSION、CLIPBOARD_READ、CLIPBOARD_WRITE、FILE_READ、FILE_WRITE、FILE_DELETE 等",
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                            Divider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            ArrowPreference(
                                title = "重新配置 AI",
                                summary = "返回配置页面修改 API Key、模型等参数",
                                onClick = { showAiReconfigConfirm = true },
                                startAction = {
                                    SettingIcon(R.drawable.ic_refresh, contentDescription = "重新配置 AI")
                                }
                            )
                            Divider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            ArrowPreference(
                                title = "清空对话记录",
                                summary = "清空当前 Termux Agent 的全部聊天记录",
                                onClick = { showAiClearConfirm = true },
                                startAction = {
                                    SettingIcon(R.drawable.ic_delete, contentDescription = "清空对话记录")
                                }
                            )
                            Divider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            SwitchPreference(
                                title = "开发者模式",
                                summary = "允许编辑 System Prompt、管理自定义技能、查看完整对话记录",
                                checked = aiDeveloperMode,
                                onCheckedChange = {
                                    aiDeveloperMode = it
                                    AiTermuxPrefs.setDeveloperMode(context, it)
                                },
                                startAction = {
                                    SettingIcon(R.drawable.ic_wrench, contentDescription = "开发者模式")
                                }
                            )
                            if (aiDeveloperMode) {
                                Divider(
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                                )
                                if (useCustomSystemPrompt) {
                                    ArrowPreference(
                                        title = "使用官方 System Prompt",
                                        summary = "当前使用: ${systemPromptSource.ifBlank { "自定义文件" }}。点击切换回官方默认 System Prompt",
                                        onClick = { showSystemPromptRestoreConfirm = true },
                                        startAction = {
                                            SettingIcon(R.drawable.ic_restore, contentDescription = "使用官方 System Prompt")
                                        }
                                    )
                                } else {
                                    ArrowPreference(
                                        title = "使用自定义 System Prompt",
                                        summary = "从文件加载 System Prompt（支持 .md 文件）",
                                        onClick = { showSystemPromptFilePicker = true },
                                        startAction = {
                                            SettingIcon(R.drawable.ic_edit, contentDescription = "使用自定义 System Prompt")
                                        }
                                    )
                                }
                                Divider(
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                                )
                                ArrowPreference(
                                    title = "自定义技能",
                                    summary = "创建和管理用户自定义技能卡（官方技能不可修改）",
                                    onClick = { showCustomSkillManager = true },
                                    startAction = {
                                        SettingIcon(R.drawable.ic_code, contentDescription = "自定义技能")
                                    }
                                )
                                Divider(
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                                )
                                ArrowPreference(
                                    title = "完整对话记录",
                                    summary = "查看包含 System 提示在内的完整对话历史",
                                    onClick = { showFullHistoryViewer = true },
                                    startAction = {
                                        SettingIcon(R.drawable.ic_files, contentDescription = "完整对话记录")
                                    }
                                )
                            }
                        }
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
        onDismissRequest = { showRestartPrompt = false },
        content = {
        TextButton(
            text = context.getString(R.string.ok),
            onClick = { showRestartPrompt = false },
            modifier = Modifier.fillMaxWidth()
        )
        }
    )

    // ---------- Navigation bar style restart prompt ----------
    OverlayDialog(
        title = context.getString(R.string.restart_required),
        summary = context.getString(R.string.navigation_bar_restart_message),
        show = showNavRestartPrompt,
        onDismissRequest = { showNavRestartPrompt = false },
        content = {
        TextButton(
            text = context.getString(R.string.ok),
            onClick = { showNavRestartPrompt = false },
            modifier = Modifier.fillMaxWidth()
        )
        }
    )

    // ---------- Critical glass nav bar incompatibility dialog ----------
    if (showCriticalNavDialog) {
        ForceEnableCriticalDialog(
            feature = ApiCompat.Feature.GLASS_NAVIGATION_BAR,
            onConfirmed = {
                showCriticalNavDialog = false
                val idx = pendingNavStyleIndex
                navBarSelectedIndex = idx
                val style = when (idx) {
                    1 -> "floating"
                    2 -> "liquid_glass"
                    else -> "default"
                }
                prefs.edit().putString("navigation_bar_style", style).apply()
                pendingNavStyleIndex = -1
                showNavRestartPrompt = true
            },
            onDismiss = {
                showCriticalNavDialog = false
                pendingNavStyleIndex = -1
            }
        )
    }

    // ---------- Termux:API usage guide ----------
    OverlayDialog(
        title = context.getString(R.string.termux_api_help),
        show = showApiHelpDialog,
        onDismissRequest = { showApiHelpDialog = false },
        content = {
        Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            HelpContentWithCopyableCommands(
                content = context.getString(R.string.termux_api_help_content),
                context = context
            )
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = context.getString(R.string.ok),
            onClick = { showApiHelpDialog = false },
            modifier = Modifier.fillMaxWidth()
        )
        }
    )

    // ---------- Termux:Boot startup guide ----------
    OverlayDialog(
        title = context.getString(R.string.termux_boot_help),
        show = showBootHelpDialog,
        onDismissRequest = { showBootHelpDialog = false },
        content = {
        Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            HelpContentWithCopyableCommands(
                content = context.getString(R.string.termux_boot_help_content),
                context = context
            )
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = context.getString(R.string.ok),
            onClick = { showBootHelpDialog = false },
            modifier = Modifier.fillMaxWidth()
        )
        }
    )

    // ---------- Restore: choose backup file ----------
    OverlayDialog(
        title = context.getString(R.string.restore),
        show = showRestoreDialog,
        onDismissRequest = { showRestoreDialog = false },
        content = {
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
    )

    // ---------- Restore: confirm ----------
    OverlayDialog(
        title = context.getString(R.string.restore),
        summary = context.getString(R.string.restore_confirm_message),
        show = showRestoreConfirmDialog,
        onDismissRequest = { showRestoreConfirmDialog = false },
        content = {
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
    )

    // ---------- Restore: progress ----------
    OverlayDialog(
        title = context.getString(R.string.restore),
        summary = restoreMessage,
        show = showRestoreProgressDialog,
        onDismissRequest = { BackupManager.cancelRestore() },
        content = {
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
    )

    // ---------- Result ----------
    OverlayDialog(
        title = context.getString(R.string.result),
        summary = resultMessage,
        show = showResultDialog,
        onDismissRequest = { showResultDialog = false },
        content = {
        TextButton(
            text = context.getString(R.string.ok),
            onClick = { showResultDialog = false },
            modifier = Modifier.fillMaxWidth()
        )
        }
    )

    // ---------- AI Termux：重新配置确认 ----------
    if (showAiReconfigConfirm) {
        OverlayDialog(
            show = true,
            title = "重新配置 AI？",
            summary = "返回配置页面可以修改 API Key、模型等参数，历史对话会被保留。",
            onDismissRequest = { showAiReconfigConfirm = false },
            content = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showAiReconfigConfirm = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "重配置",
                    onClick = {
                        showAiReconfigConfirm = false
                        val cfg = AiTermuxPrefs.getConfig(context)
                        AiTermuxPrefs.saveConfig(
                            context,
                            AiTermuxConfig(
                                providerConfig = cfg.providerConfig.copy(apiKey = cfg.providerConfig.apiKey),
                                customSystemPrompt = cfg.customSystemPrompt,
                                isConfigured = false
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        )
    }

    // ---------- AI Termux：清空对话确认 ----------
    if (showAiClearConfirm) {
        OverlayDialog(
            show = true,
            title = "清空对话记录？",
            summary = "当前对话将被清空且不可恢复，是否继续？",
            onDismissRequest = { showAiClearConfirm = false },
            content = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showAiClearConfirm = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "清空",
                    onClick = {
                        showAiClearConfirm = false
                        AiTermuxPrefs.clearChatHistory(context)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        )
    }

    // ---------- 关闭高危命令二次确认：风险警告弹窗（由 RiskConfirmManager 统一处理） ----------

    // ---------- AI Termux：编辑 System Prompt ----------
    var systemPromptText by remember { mutableStateOf(AiTermuxPrefs.getConfig(context).customSystemPrompt) }
    OverlayDialog(
        title = "编辑 System Prompt",
        summary = "自定义额外的系统指令，将附加在官方 System Prompt 之后。\n修改需谨慎，错误配置可能导致 AI 行为异常。",
        show = showSystemPromptEditor,
        onDismissRequest = { showSystemPromptEditor = false },
        content = {
        Column(
            modifier = Modifier
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState())
        ) {
            TextField(
                value = systemPromptText,
                onValueChange = { systemPromptText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp),
                label = "在此输入自定义 System Prompt 内容...",
                useLabelAsPlaceholder = true,
                maxLines = Int.MAX_VALUE,
                minLines = 5
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = "恢复默认",
                onClick = {
                    systemPromptText = ""
                    val cfg = AiTermuxPrefs.getConfig(context)
                    AiTermuxPrefs.saveConfig(context, cfg.copy(customSystemPrompt = ""))
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = "保存",
                onClick = {
                    val cfg = AiTermuxPrefs.getConfig(context)
                    AiTermuxPrefs.saveConfig(context, cfg.copy(customSystemPrompt = systemPromptText))
                    showSystemPromptEditor = false
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
        }
    )

    // ---------- AI Termux：选择 System Prompt 文件 ----------
    var showInternalPromptPicker by remember { mutableStateOf(false) }
    OverlayDialog(
        title = "选择 System Prompt 文件",
        summary = "请选择一个 .md 文件作为自定义 System Prompt。\n此文件将完全替代官方默认 System Prompt。",
        show = showSystemPromptFilePicker,
        onDismissRequest = { showSystemPromptFilePicker = false },
        content = {
        Column {
            Text(
                text = "请选择使用哪种文件选择器：",
                style = TextStyle(fontSize = 14.sp)
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "Termux 内部",
                    onClick = {
                        showSystemPromptFilePicker = false
                        showInternalPromptPicker = true
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "系统选择器",
                    onClick = {
                        showSystemPromptFilePicker = false
                        // 使用系统文件选择器
                        systemPromptFileLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        }
    )

    // Termux 内部文件选择器
    TermuxInternalFilePicker(
        show = showInternalPromptPicker,
        title = "选择 System Prompt 文件",
        fileExtensions = listOf("md", "txt"),
        onDismiss = { showInternalPromptPicker = false },
        onFileSelected = { path ->
            showInternalPromptPicker = false
            try {
                val file = java.io.File(path.replace("\$HOME", TERMUX_HOME_ABS))
                if (file.exists() && file.isFile) {
                    val content = file.readText()
                    if (content.isNotBlank()) {
                        AiTermuxPrefs.setCustomSystemPrompt(context, content)
                        AiTermuxPrefs.setUseCustomSystemPrompt(context, true)
                        useCustomSystemPrompt = true
                        systemPromptSource = file.name
                        android.widget.Toast.makeText(context, "已加载自定义 System Prompt", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(context, "文件内容为空", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    android.widget.Toast.makeText(context, "文件不存在", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "读取文件失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    )

    // ---------- AI Termux：确认还原官方 System Prompt ----------
    OverlayDialog(
        title = "还原官方 System Prompt",
        summary = "确定要切换回官方默认 System Prompt 吗？\n您的自定义文件将不再被使用。",
        show = showSystemPromptRestoreConfirm,
        onDismissRequest = { showSystemPromptRestoreConfirm = false },
        content = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                text = "取消",
                onClick = { showSystemPromptRestoreConfirm = false }
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = "确定还原",
                onClick = {
                    AiTermuxPrefs.setUseCustomSystemPrompt(context, false)
                    useCustomSystemPrompt = false
                    systemPromptSource = ""
                    showSystemPromptRestoreConfirm = false
                    android.widget.Toast.makeText(context, "已切换回官方 System Prompt", android.widget.Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
        }
    )

    // ---------- AI Termux：自定义技能管理 ----------
    var skillsRefreshKey by remember { mutableStateOf(0) }
    OverlayDialog(
        title = "自定义技能",
        summary = "管理用户自定义的技能卡。官方技能不可修改，仅支持添加、编辑和删除您自己创建的技能。",
        show = showCustomSkillManager,
        onDismissRequest = { showCustomSkillManager = false },
        content = {
        val customSkills = remember(skillsRefreshKey) { AiTermuxPrefs.getCustomSkills(context) }
        if (customSkills.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无自定义技能，点击下方按钮添加",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .heightIn(max = 350.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                customSkills.forEach { skill ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                text = skill.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            if (skill.description.isNotBlank()) {
                                Text(
                                    text = skill.description,
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    text = "编辑",
                                    onClick = {
                                        editingSkill = skill
                                        showAddEditSkillDialog = true
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    text = "删除",
                                    onClick = {
                                        AiTermuxPrefs.deleteCustomSkill(context, skill.id)
                                        skillsRefreshKey++
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = "关闭",
                onClick = { showCustomSkillManager = false },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = "添加技能",
                onClick = {
                    editingSkill = null
                    showAddEditSkillDialog = true
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
        }
    )

    // ---------- AI Termux：添加/编辑自定义技能 ----------
    var skillName by remember { mutableStateOf("") }
    var skillDescription by remember { mutableStateOf("") }
    var skillSystemPrompt by remember { mutableStateOf("") }
    var skillJson by remember { mutableStateOf("") }
    var skillImplementationType by remember { mutableStateOf("shell_command") }

    val implOptions = listOf(
        "shell_command" to "Shell 命令",
        "open_activity" to "打开页面",
        "send_broadcast" to "发送广播",
        "custom" to "自定义"
    )

    val implJsonTemplates = mapOf(
        "shell_command" to """{"skillType":"CUSTOM_COMMAND","params":{"command":"ls -la ~"}}""",
        "open_activity" to """{"skillType":"CUSTOM_COMMAND","params":{"activityClass":"com.example.MyActivity","extras":{"key":"value"}}}""",
        "send_broadcast" to """{"skillType":"CUSTOM_COMMAND","params":{"action":"com.example.MY_ACTION","extras":{"key":"value"}}}""",
        "custom" to """{"skillType":"CUSTOM_COMMAND","params":{"key":"value"}}"""
    )

    LaunchedEffect(editingSkill) {
        editingSkill?.let { skill ->
            skillName = skill.name
            skillDescription = skill.description
            skillSystemPrompt = skill.systemPrompt
            skillJson = skill.skillJson
            skillImplementationType = skill.implementationType
        } ?: run {
            skillName = ""
            skillDescription = ""
            skillSystemPrompt = ""
            skillJson = ""
            skillImplementationType = "shell_command"
        }
    }

    OverlayDialog(
        title = if (editingSkill != null) "编辑技能" else "添加自定义技能",
        summary = "创建一个供 AI 调用的自定义技能。技能将作为系统指令的一部分注入。",
        show = showAddEditSkillDialog,
        onDismissRequest = { showAddEditSkillDialog = false },
        content = {
        Column(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
        ) {
            TextField(
                value = skillName,
                onValueChange = { skillName = it },
                modifier = Modifier.fillMaxWidth(),
                label = "技能名称（必填）",
                useLabelAsPlaceholder = true,
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = skillDescription,
                onValueChange = { skillDescription = it },
                modifier = Modifier.fillMaxWidth(),
                label = "技能描述",
                useLabelAsPlaceholder = true,
                singleLine = false,
                maxLines = 2
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "实现方式",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                implOptions.forEach { (value, label) ->
                    val selected = skillImplementationType == value
                    TextButton(
                        text = label,
                        onClick = {
                            skillImplementationType = value
                            if (skillJson.isBlank() || skillJson == implJsonTemplates.values.first()) {
                                skillJson = implJsonTemplates[value] ?: ""
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = if (selected) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors()
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "调用方式（AI 输出的 skillType 结构）",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            TextField(
                value = skillJson,
                onValueChange = { skillJson = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
                label = "示例：${implJsonTemplates[skillImplementationType]}",
                useLabelAsPlaceholder = true,
                maxLines = Int.MAX_VALUE,
                minLines = 3
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "实现方式说明（可选）",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            TextField(
                value = skillSystemPrompt,
                onValueChange = { skillSystemPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
                label = "详细说明该技能如何实现，会注入到 System Prompt 中指导 AI",
                useLabelAsPlaceholder = true,
                maxLines = Int.MAX_VALUE,
                minLines = 3
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = "取消",
                onClick = { showAddEditSkillDialog = false },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = "保存",
                onClick = {
                    if (skillName.isBlank()) return@TextButton
                    val existing = editingSkill
                    if (existing != null) {
                        AiTermuxPrefs.updateCustomSkill(context, existing.copy(
                            name = skillName,
                            description = skillDescription,
                            systemPrompt = skillSystemPrompt,
                            skillJson = skillJson,
                            implementationType = skillImplementationType
                        ))
                    } else {
                        AiTermuxPrefs.addCustomSkill(context, CustomSkill(
                            name = skillName,
                            description = skillDescription,
                            systemPrompt = skillSystemPrompt,
                            skillJson = skillJson,
                            implementationType = skillImplementationType
                        ))
                    }
                    skillsRefreshKey++
                    showAddEditSkillDialog = false
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
        }
    )

    // ---------- AI Termux：完整对话记录 ----------
    OverlayDialog(
        title = "完整对话记录",
        summary = "包含 System 系统提示在内的完整对话历史。仅显示 Termux Agent 保存的最近 100 条消息。",
        show = showFullHistoryViewer,
        onDismissRequest = { showFullHistoryViewer = false },
        content = {
        val messages = remember { AiTermuxPrefs.getChatHistory(context) }
        val clipboard = remember {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        }
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无对话记录",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
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
                                    "user" -> "👤 用户"
                                    "assistant" -> "🤖 AI"
                                    "system" -> "⚙️ 系统"
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
                            if (msg.reasoningContent != null && msg.reasoningContent.isNotBlank()) {
                                Text(
                                    text = "💭 ${msg.reasoningContent.take(200)}${if (msg.reasoningContent.length > 200) "..." else ""}",
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            Text(
                                text = msg.content.ifBlank { "(空)" },
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
                                    text = "复制",
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable {
                                            val clip = android.content.ClipData.newPlainText("消息", msg.content)
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
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = "复制全部",
                onClick = {
                    val allContent = buildString {
                        appendLine("=== System Prompt ===")
                        appendLine(AiTermuxPrefs.buildFullSystemPrompt(context))
                        appendLine()
                        appendLine("=== 对话记录 ===")
                        messages.forEach { msg ->
                            appendLine("[${msg.role}] ${msg.content}")
                        }
                    }
                    val clip = android.content.ClipData.newPlainText("完整对话记录", allContent)
                    clipboard.setPrimaryClip(clip)
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = "关闭",
                onClick = { showFullHistoryViewer = false },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
        }
    )
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
                    SwitchPreference(
                        title = item.title,
                        summary = item.description,
                        checked = item.switchValue,
                        onCheckedChange = item.onSwitchChange,
                        startAction = {
                            SettingIcon(item.iconRes, contentDescription = item.title)
                        }
                    )
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

/**
 * 解析帮助文本，将命令行渲染为可一键复制的行，其余渲染为普通文本。
 *
 * 判定规则（行首去空格后）：
 *  - 以 `•` 开头 → 命令描述行，其中 ` — ` 后为说明，前面是命令 → 提取命令部分可复制
 *  - 以 `pkg ` / `mkdir ` / `termux-` / `#!/` / `#` / `sshd` / `termux-wake-lock` 开头 → 整行可复制
 *  - 以数字+`.` 开头（如 `1. `）→ 步骤说明行，不可复制
 *  - 其余 → 普通文本
 */
@Composable
private fun HelpContentWithCopyableCommands(
    content: String,
    context: Context
) {
    val lines = content.split("\n")
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }

    lines.forEachIndexed { index, rawLine ->
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            return@forEachIndexed
        }

        // 提取命令部分
        val commandText: String? = when {
            // "• termux-battery-status — 电池信息" → 命令是 "termux-battery-status"
            trimmed.startsWith("• ") -> {
                val afterBullet = trimmed.substring(2).trim()
                val dashIdx = afterBullet.indexOf(" — ")
                if (dashIdx > 0) afterBullet.substring(0, dashIdx).trim()
                else if (afterBullet.startsWith("termux-") || afterBullet.startsWith("pkg ")) afterBullet
                else null
            }
            // 独立命令行
            trimmed.startsWith("pkg ") ||
            trimmed.startsWith("mkdir ") ||
            trimmed.startsWith("termux-") ||
            trimmed.startsWith("#!/") ||
            trimmed.startsWith("sshd") ||
            trimmed.startsWith("termux-wake-lock") -> trimmed
            // "   #!/data/..." 缩进的脚本行
            rawLine.trimStart().startsWith("#!/") -> rawLine.trimStart()
            rawLine.trimStart().startsWith("termux-wake-lock") -> rawLine.trimStart()
            rawLine.trimStart().startsWith("sshd") -> rawLine.trimStart()
            else -> null
        }

        if (commandText != null) {
            // 可复制命令行：命令文本 + 复制图标
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = rawLine,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    lineHeight = 20.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable {
                            val clip = android.content.ClipData.newPlainText("命令", commandText)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(
                                context, "已复制: $commandText",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = "复制",
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                }
            }
        } else {
            // 普通文本行
            Text(
                text = rawLine,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface,
                lineHeight = 22.sp
            )
        }
    }
}
