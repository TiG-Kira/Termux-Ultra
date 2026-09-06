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
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
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
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import com.termux.app.LocaleHelper
import com.termux.app.compose.AiTermuxPrefs
import com.termux.app.compose.AiTermuxConfig
import com.termux.app.compose.SkillType
import com.termux.app.utils.SnackbarHelper
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.logger.Logger
import com.google.android.material.snackbar.Snackbar
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
fun SettingsScreen(
    onAboutClick: () -> Unit,
    navBarBottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun showSnackbar(message: String, isLong: Boolean = false) {
        scope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                duration = if (isLong) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
    }
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
    var showAiClearConfirm by remember { mutableStateOf(false) }
    var showWhitelistDialog by remember { mutableStateOf(false) }
    var tempWhitelistSkills by remember { mutableStateOf<Set<SkillType>>(emptySet()) }

    // Whitelistable skills definition
    val whitelistSkillLabels = remember {
        listOf(
            SkillType.CAPTURE_OUTPUT to context.getString(R.string.capture_output_desc),
        )
    }
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
    var unlimitedMode by remember { mutableStateOf(AiTermuxPrefs.isUnlimitedMode(context)) }
    var rootAutoShell by remember { mutableStateOf(AiTermuxPrefs.isRootAutoShell(context)) }
    // 本地大模型备用在线配置（fallback online）
    val aiProvider = remember { AiTermuxPrefs.getConfig(context).providerConfig.provider }
    val isLocalMode = remember { aiProvider == "local" }
    val hasFallbackCached = remember { AiTermuxPrefs.isFallbackOnlineConfigReady(context) }
    var fallbackEnabled by remember { mutableStateOf(AiTermuxPrefs.isFallbackOnlineEnabled(context)) }
    var showFallbackEditor by remember { mutableStateOf(false) }
    var fbKey by remember { mutableStateOf("") }
    var fbUrl by remember { mutableStateOf("") }
    var fbModel by remember { mutableStateOf("") }
    var fbTemp by remember { mutableStateOf(0.7f) }
    var showUnlimitedModeConfirm by remember { mutableStateOf(false) }

    // 高风险命令二次确认
    var riskConfirmEnabled by remember { mutableStateOf(RiskConfirmManager.isEnabled(context)) }

    // 防护等级和检测模式
    var protectionLevel by remember { mutableStateOf(RiskConfirmManager.getProtectionLevel(context)) }
    var detectionMode by remember { mutableStateOf(RiskConfirmManager.getDetectionMode(context)) }
    var protectionLevelIndex by remember { mutableIntStateOf(RiskConfirmManager.getProtectionLevel(context).ordinal) }
    var detectionModeIndex by remember { mutableIntStateOf(RiskConfirmManager.getDetectionMode(context).ordinal) }

    // 监听关闭警告弹窗的结果，同步状态
    val disableWarningState by RiskConfirmManager.disableWarningState.collectAsState()
    LaunchedEffect(disableWarningState.show) {
        if (!disableWarningState.show) {
            protectionLevel = RiskConfirmManager.getProtectionLevel(context)
            protectionLevelIndex = protectionLevel.ordinal
            riskConfirmEnabled = protectionLevel != RiskConfirmManager.ProtectionLevel.OFF
            // 宽松模式 + ROOT + 开发者模式：自动开启无限制模式
            if (protectionLevel == RiskConfirmManager.ProtectionLevel.OFF
                && aiDeveloperMode
                && AiTermuxPrefs.isRootAvailable()
                && !unlimitedMode) {
                unlimitedMode = true
                AiTermuxPrefs.setUnlimitedMode(context, true)
                rootAutoShell = true
                AiTermuxPrefs.setRootAutoShell(context, true)
            }
        }
    }

    // Integrated Termux tools (default off)
    var termuxApiEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_API)) }
    var termuxBootEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_BOOT)) }
    var termuxStylingEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_STYLING)) }
    var termuxTaskerEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_TASKER)) }
    var termuxWidgetEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_WIDGET)) }

    // Terminal runtime core
    var runtimeCore by remember { mutableStateOf(TerminalRuntimeCore.getCurrent(context)) }

    // Terminal settings - Java+NDK mode
    val terminalPrefs = remember { TermuxAppSharedPreferences.build(context) }
    var softKeyboardEnabled by remember { mutableStateOf(terminalPrefs?.isSoftKeyboardEnabled() ?: false) }
    var softKeyboardOnlyIfNoHardware by remember { mutableStateOf(terminalPrefs?.isSoftKeyboardEnabledOnlyIfNoHardware() ?: false) }
    var terminalMarginAdjustment by remember { mutableStateOf(terminalPrefs?.isTerminalMarginAdjustmentEnabled() ?: false) }
    var keyLoggingEnabled by remember { mutableStateOf(terminalPrefs?.isTerminalViewKeyLoggingEnabled() ?: false) }
    var logLevel by remember { mutableStateOf(terminalPrefs?.logLevel ?: Logger.DEFAULT_LOG_LEVEL) }

    // Terminal settings - Kotlin+Compose mode（订阅 ComposeTerminalSettings StateFlow，
    // 单一事实来源：写入经 setter 持久化到 SP，显示实时同步，重进设置页不回退）
    com.termux.app.compose.terminal.ComposeTerminalSettings.init(context)
    val composeFontSize by com.termux.app.compose.terminal.ComposeTerminalSettings.fontSize.collectAsState()
    val composeCursorBlink by com.termux.app.compose.terminal.ComposeTerminalSettings.cursorBlink.collectAsState()
    val composeScrollbackLines by com.termux.app.compose.terminal.ComposeTerminalSettings.scrollbackLines.collectAsState()

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
        context.getString(R.string.navigation_bar_liquid_glass),
        context.getString(R.string.navigation_bar_os4)
    )
    var navBarSelectedIndex by remember {
        mutableStateOf(
            when (prefs.getString("navigation_bar_style", "default")) {
                "floating" -> 1
                "liquid_glass" -> 2
                "soft_light" -> 3
                else -> 0
            }
        )
    }
    var showNavRestartPrompt by remember { mutableStateOf(false) }
    var showCriticalNavDialog by remember { mutableStateOf(false) }
    var pendingNavStyleIndex by remember { mutableStateOf(-1) }

    // 卡片布局模式
    var cardLayoutMode by remember {
        mutableStateOf(prefs.getInt("KEY_CARD_LAYOUT_MODE", 0))
    }

    val scrollBehavior = MiuixScrollBehavior()

    val restoreFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            if (!isProcessing) {
                isProcessing = true
                showSnackbar(context.getString(R.string.restore_view_progress_toast))
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
                    showSnackbar(context.getString(R.string.custom_prompt_loaded))
                } else {
                    showSnackbar(context.getString(R.string.file_empty))
                }
            } catch (e: Exception) {
                showSnackbar(context.getString(R.string.read_file_failed, e.message))
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
                    showSnackbar(context.getString(R.string.backup_view_progress_toast))
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

    val systemSettings = remember {
        buildList {
            add(
                SettingItem(
                    title = context.getString(R.string.log_management),
                    description = context.getString(R.string.log_management_desc),
                    iconRes = R.drawable.ic_bug,
                    action = {
                        val intent = Intent(context, com.termux.app.activities.LogViewerActivity::class.java)
                        context.startActivity(intent)
                    }
                )
            )
            add(
                SettingItem(
                    title = context.getString(R.string.storage_title),
                    description = context.getString(R.string.storage_description),
                    iconRes = R.drawable.ic_storage,
                    action = {
                        val intent = Intent(context, com.termux.app.activities.StorageActivity::class.java)
                        context.startActivity(intent)
                    }
                )
            )
            add(
                SettingItem(
                    title = context.getString(R.string.about_preference_title),
                    description = context.getString(R.string.about_description),
                    iconRes = R.drawable.ic_info,
                    action = { onAboutClick() }
                )
            )
        }
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
                            component = ComponentName(context.packageName, "com.termux.app.activities.TermuxStylingActivity")
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
                            component = ComponentName(context.packageName, "com.termux.app.activities.TermuxTaskerActivity")
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
                            component = ComponentName(context.packageName, "com.termux.app.activities.TermuxWidgetActivity")
                        }
                        runCatching { context.startActivity(intent) }
                    }
                ))
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { Box(modifier = Modifier.fillMaxSize().padding(bottom = navBarBottomPadding), contentAlignment = Alignment.BottomCenter) { SnackbarHost(state = snackbarHostState) } },
        topBar = {
            TopAppBar(title = context.getString(R.string.settings_title), scrollBehavior = scrollBehavior)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = navBarBottomPadding + 16.dp)
        ) {
            // ---------- Appearance ----------
            item(key = "section_appearance") { SmallTitle(text = context.getString(R.string.appearance)) }
            item(key = "card_appearance") {
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
                        HorizontalDivider(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                            modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                        )
                        OverlayDropdownPreference(
                            title = context.getString(R.string.navigation_bar_style),
                            summary = context.getString(R.string.navigation_bar_style_description),
                            items = navBarStyleOptions,
                            selectedIndex = navBarSelectedIndex,
                            onSelectedIndexChange = { idx ->
                                if (idx == 2 || idx == 3) {
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
                                    3 -> "soft_light"
                                    else -> "default"
                                }
                                prefs.edit().putString("navigation_bar_style", style).apply()
                                showNavRestartPrompt = true
                            },
                            startAction = {
                                SettingIcon(R.drawable.ic_navigation, contentDescription = context.getString(R.string.navigation_bar_style))
                            }
                        )
                        HorizontalDivider(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                            modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                        )
                        SwitchPreference(
                            title = context.getString(R.string.horizontal_tip_layout),
                            summary = context.getString(R.string.overview_horizontal_cards_desc),
                            checked = cardLayoutMode == 1,
                            onCheckedChange = { enabled ->
                                cardLayoutMode = if (enabled) 1 else 0
                                prefs.edit().putInt("KEY_CARD_LAYOUT_MODE", cardLayoutMode).apply()
                            },
                            startAction = {
                                SettingIcon(R.drawable.ic_swap, contentDescription = context.getString(R.string.horizontal_tip_layout))
                            }
                        )
                    }
                }
            }

            // ---------- Remote ----------
            item(key = "section_remote") { SmallTitle(text = context.getString(R.string.remote)) }
            item(key = "card_remote") {
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
                            HorizontalDivider(color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f))
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
            item(key = "section_backup") { SmallTitle(text = context.getString(R.string.backup_category)) }
            item(key = "card_data_group") { SettingsGroupCard(items = dataSettings) }

            // ---------- 终端 ----------
            item(key = "section_terminal") { SmallTitle(text = context.getString(R.string.terminal)) }
            item(key = "card_terminal_runtime") {
                val isComposeMode = runtimeCore == TerminalRuntimeCore.Core.KOTLIN_COMPOSE
                val composeSupported = TerminalRuntimeCore.isComposeSupported
                val runtimeCoreItems = TerminalRuntimeCore.Core.entries.map { it.displayName(context) }
                val currentCoreIndex = TerminalRuntimeCore.Core.entries.indexOf(runtimeCore)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column {
                        OverlayDropdownPreference(
                            title = context.getString(R.string.terminal_runtime_core),
                            summary = context.getString(R.string.runtime_core_switch_desc),
                            items = runtimeCoreItems,
                            selectedIndex = currentCoreIndex,
                            onSelectedIndexChange = { idx ->
                                val selected = TerminalRuntimeCore.Core.entries[idx]
                                // SDK < 28 时禁止选 Kotlin+Compose
                                if (selected == TerminalRuntimeCore.Core.KOTLIN_COMPOSE && !composeSupported) return@OverlayDropdownPreference
                                if (selected != runtimeCore) {
                                    TerminalRuntimeCore.killAllSessions(context)
                                    TerminalRuntimeCore.applyPluginState(context, selected)
                                    TerminalRuntimeCore.setCurrent(context, selected)
                                    runtimeCore = selected
                                    termuxApiEnabled = IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_API)
                                    termuxBootEnabled = IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_BOOT)
                                    termuxStylingEnabled = IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_STYLING)
                                    termuxTaskerEnabled = IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_TASKER)
                                    termuxWidgetEnabled = IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_WIDGET)
                                    showSnackbar(context.getString(R.string.switched_core_selected, selected.displayName(context)))
                                }
                            },
                            startAction = {
                                SettingIcon(R.drawable.ic_terminal)
                            }
                        )

                        // ===== Java+NDK 模式设置 =====
                        if (!isComposeMode) {
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            SwitchPreference(
                                title = context.getString(R.string.enable_softkeyboard),
                                summary = if (softKeyboardEnabled) context.getString(R.string.enabled) else context.getString(R.string.disabled),
                                checked = softKeyboardEnabled,
                                onCheckedChange = {
                                    softKeyboardEnabled = it
                                    terminalPrefs?.setSoftKeyboardEnabled(it)
                                },
                                startAction = { SettingIcon(R.drawable.ic_keyboard) }
                            )
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            SwitchPreference(
                                title = context.getString(R.string.enable_soft_keyboard_no_hw),
                                summary = context.getString(R.string.soft_keyboard_only_if_no_hardware_desc),
                                checked = softKeyboardOnlyIfNoHardware,
                                onCheckedChange = {
                                    softKeyboardOnlyIfNoHardware = it
                                    terminalPrefs?.setSoftKeyboardEnabledOnlyIfNoHardware(it)
                                },
                                startAction = { SettingIcon(R.drawable.ic_keyboard_disabled) }
                            )
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            SwitchPreference(
                                title = context.getString(R.string.terminal_margin_adjustment),
                                summary = context.getString(R.string.terminal_margin_adjustment_desc),
                                checked = terminalMarginAdjustment,
                                onCheckedChange = {
                                    terminalMarginAdjustment = it
                                    terminalPrefs?.setTerminalMarginAdjustment(it)
                                },
                                startAction = { SettingIcon(R.drawable.ic_terminal) }
                            )
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            OverlayDropdownPreference(
                                title = context.getString(R.string.log_level),
                                summary = context.getString(R.string.log_level_desc),
                                items = listOf(context.getString(R.string.off), context.getString(R.string.normal), context.getString(R.string.debug), context.getString(R.string.verbose)),
                                selectedIndex = logLevel.coerceIn(0, 3),
                                onSelectedIndexChange = { idx ->
                                    logLevel = idx
                                    terminalPrefs?.setLogLevel(context, idx)
                                },
                                startAction = { SettingIcon(R.drawable.ic_bug) }
                            )
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            SwitchPreference(
                                title = context.getString(R.string.terminal_key_logging),
                                summary = context.getString(R.string.terminal_key_logging_desc),
                                checked = keyLoggingEnabled,
                                onCheckedChange = {
                                    keyLoggingEnabled = it
                                    terminalPrefs?.setTerminalViewKeyLoggingEnabled(it)
                                },
                                startAction = { SettingIcon(R.drawable.ic_bug_keyboard) }
                            )
                        }

                        // ===== Kotlin+Compose 模式设置 =====
                        if (isComposeMode) {
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            OverlayDropdownPreference(
                                title = context.getString(R.string.font_size),
                                summary = context.getString(R.string.font_size_desc),
                                items = listOf("10sp", "12sp", "14sp", "16sp", "18sp", "20sp", "24sp"),
                                selectedIndex = listOf(10, 12, 14, 16, 18, 20, 24).indexOf(composeFontSize).coerceAtLeast(0),
                                onSelectedIndexChange = { idx ->
                                    com.termux.app.compose.terminal.ComposeTerminalSettings.setFontSize(
                                        listOf(10, 12, 14, 16, 18, 20, 24)[idx]
                                    )
                                },
                                startAction = { SettingIcon(R.drawable.ic_text_size) }
                            )
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            SwitchPreference(
                                title = context.getString(R.string.cursor_blink),
                                summary = if (composeCursorBlink) context.getString(R.string.enabled) else context.getString(R.string.disabled),
                                checked = composeCursorBlink,
                                onCheckedChange = {
                                    com.termux.app.compose.terminal.ComposeTerminalSettings.setCursorBlink(it)
                                },
                                startAction = { SettingIcon(R.drawable.ic_terminal) }
                            )
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            OverlayDropdownPreference(
                                title = context.getString(R.string.scrollback_buffer),
                                summary = context.getString(R.string.scrollback_desc),
                                items = listOf(context.getString(R.string.lines_1000), context.getString(R.string.lines_5000), context.getString(R.string.lines_10000), context.getString(R.string.lines_50000)),
                                selectedIndex = listOf(1000, 5000, 10000, 50000).indexOf(composeScrollbackLines).coerceAtLeast(0),
                                onSelectedIndexChange = { idx ->
                                    com.termux.app.compose.terminal.ComposeTerminalSettings.setScrollbackLines(
                                        listOf(1000, 5000, 10000, 50000)[idx]
                                    )
                                },
                                startAction = { SettingIcon(R.drawable.ic_screen_rotation) }
                            )
                        }
                    }
                }
            }

                        // ---------- Integrated Tools ----------
            item(key = "section_tools") { SmallTitle(text = context.getString(R.string.integrated_tools_category)) }
            item(key = "card_integrated_tools") {
                val isComposeMode = runtimeCore == TerminalRuntimeCore.Core.KOTLIN_COMPOSE
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column {
                        // Termux:API 已适配新星(Nova)引擎，任何核心模式下都可启用
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
                                // 同步 am 包装器：开启后无需重启应用即可使用 termux-* 命令
                                try {
                                    if (it) TermuxApiBroadcastFix.applyAmWrapper(context)
                                    else TermuxApiBroadcastFix.removeAmWrapper()
                                } catch (_: Exception) {}
                            },
                            enabled = !apiStandaloneInstalled,
                            onDisabledClick = {
                                IntegratedTools.showStandaloneConflictPrompt(context, IntegratedTools.Tool.TERMUX_API)
                            }
                        )
                        if (!isComposeMode) {
                        IntegratedToolSwitch(
                            title = context.getString(R.string.termux_boot_tool),
                            summary = if (bootStandaloneInstalled) replacedSummary
                                      else context.getString(R.string.termux_boot_tool_summary),
                            iconRes = R.drawable.ic_launch,
                            checked = termuxBootEnabled,
                            onCheckedChange = {
                                termuxBootEnabled = it
                                IntegratedTools.setEnabled(context, IntegratedTools.Tool.TERMUX_BOOT, it)
                                IntegratedTools.applyComponentState(context, IntegratedTools.Tool.TERMUX_BOOT, it)
                            },
                            enabled = !bootStandaloneInstalled,
                            onDisabledClick = {
                                IntegratedTools.showStandaloneConflictPrompt(context, IntegratedTools.Tool.TERMUX_BOOT)
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

                        // Styling always available regardless of runtime core
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
                    }
                }
            }

// ---------- AI Termux ----------
            item(key = "section_ai") { SmallTitle(text = "Termux Agent") }
            item(key = "card_ai") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column {
                        SwitchPreference(
                            title = "Termux Agent",
                            summary = context.getString(R.string.agent_entry_card_desc),
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
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            val whitelistCount = autoExecConfig.autoExecSkills.size
                            val whitelistSummary = when {
                                unlimitedMode -> context.getString(R.string.unrestricted_opened)
                                whitelistCount == 0 -> context.getString(R.string.whitelist_off)
                                else -> context.getString(R.string.whitelist_count_selected, whitelistCount)
                            }
                            ArrowPreference(
                                title = context.getString(R.string.trust_whitelist),
                                summary = whitelistSummary,
                                enabled = !unlimitedMode,
                                onClick = { showWhitelistDialog = true },
                                startAction = {
                                    SettingIcon(R.drawable.ic_shield, contentDescription = context.getString(R.string.trust_whitelist))
                                }
                            )

                            if (aiProvider == "local") {
                                HorizontalDivider(color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f), modifier = Modifier.padding(start = 72.dp, end = 16.dp))
                                ArrowPreference(
                                    title = context.getString(R.string.train_local_model),
                                    summary = if (hasFallbackCached) {
                                            context.getString(R.string.training_online_full_auto)
                                        } else {
                                            context.getString(R.string.training_manual_mode)
                                        },
                                    onClick = {
                                        context.startActivity(android.content.Intent(context, com.termux.app.activities.AiLocalTrainerActivity::class.java))
                                    },
                                    startAction = {
                                        SettingIcon(R.drawable.ic_tools, contentDescription = context.getString(R.string.train_local_model))
                                    }
                                )
                            }

                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            ArrowPreference(
                                title = context.getString(R.string.reconfigure_ai),
                                summary = context.getString(R.string.back_to_config_desc),
                                onClick = {
                                    val intent = Intent(context, com.termux.app.activities.AiTermuxActivity::class.java)
                                    intent.putExtra("force_setup", true)
                                    context.startActivity(intent)
                                },
                                startAction = {
                                    SettingIcon(R.drawable.ic_refresh, contentDescription = context.getString(R.string.reconfigure_ai))
                                }
                            )
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            ArrowPreference(
                                title = context.getString(R.string.clear_chat_history),
                                summary = context.getString(R.string.clear_agent_history_desc),
                                onClick = { showAiClearConfirm = true },
                                startAction = {
                                    SettingIcon(R.drawable.ic_delete, contentDescription = context.getString(R.string.clear_chat_history))
                                }
                            )
                            // 本地模式专属：备用在线大模型（fallback）
                            if (isLocalMode) {
                                HorizontalDivider(
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                                )
                                SwitchPreference(
                                    title = context.getString(R.string.backup_online_llm),
                                    summary = if (fallbackEnabled) {
                                        val ready = AiTermuxPrefs.isFallbackOnlineConfigReady(context)
                                        if (ready) context.getString(R.string.fallback_enabled_ready)
                                        else context.getString(R.string.fallback_not_configured)
                                    } else {
                                        context.getString(R.string.fallback_off_desc)
                                    },
                                    checked = fallbackEnabled,
                                    onCheckedChange = {
                                        fallbackEnabled = it
                                        AiTermuxPrefs.setFallbackOnlineEnabled(context, it)
                                    },
                                    startAction = {
                                        SettingIcon(R.drawable.ic_refresh, contentDescription = context.getString(R.string.backup_online_llm))
                                    }
                                )
                                if (fallbackEnabled) {
                                    HorizontalDivider(
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                        modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                                    )
                                    ArrowPreference(
                                        title = context.getString(R.string.configure_backup_params),
                                        summary = run {
                                            val c = AiTermuxPrefs.getFallbackOnlineConfig(context)
                                            val urlShown = if (c.baseUrl.isBlank()) context.getString(R.string.not_set) else c.baseUrl
                                            val modelShown = if (c.model.isBlank()) context.getString(R.string.not_set) else c.model
                                            val keyShown = if (c.apiKey.isBlank()) context.getString(R.string.api_key_empty) else "********"
                                            context.getString(R.string.model_url_key, modelShown, urlShown, keyShown)
                                        },
                                        onClick = {
                                            // 打开对话框前，载入当前保存的值
                                            val cfg = AiTermuxPrefs.getFallbackOnlineConfig(context)
                                            fbKey = cfg.apiKey
                                            fbUrl = cfg.baseUrl
                                            fbModel = cfg.model
                                            fbTemp = cfg.temperature
                                            showFallbackEditor = true
                                        },
                                        startAction = {
                                            SettingIcon(R.drawable.ic_edit, contentDescription = context.getString(R.string.configure_backup_params))
                                        }
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            SwitchPreference(
                                title = context.getString(R.string.developer_mode),
                                summary = context.getString(R.string.developer_mode_desc),
                                checked = aiDeveloperMode,
                                onCheckedChange = {
                                    aiDeveloperMode = it
                                    AiTermuxPrefs.setDeveloperMode(context, it)
                                },
                                startAction = {
                                    SettingIcon(R.drawable.ic_wrench, contentDescription = context.getString(R.string.developer_mode))
                                }
                            )
                            if (aiDeveloperMode) {
                                HorizontalDivider(
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                                )
                                if (useCustomSystemPrompt) {
                                    ArrowPreference(
                                        title = context.getString(R.string.use_official_prompt),
                                        summary = context.getString(
                                            R.string.currently_using_source,
                                            systemPromptSource.ifBlank { context.getString(R.string.custom_file) }
                                        ),
                                        onClick = { showSystemPromptRestoreConfirm = true },
                                        startAction = {
                                            SettingIcon(R.drawable.ic_restore, contentDescription = context.getString(R.string.use_official_prompt))
                                        }
                                    )
                                } else {
                                    ArrowPreference(
                                        title = context.getString(R.string.use_custom_prompt),
                                        summary = context.getString(R.string.load_prompt_from_file),
                                        onClick = { showSystemPromptFilePicker = true },
                                        startAction = {
                                            SettingIcon(R.drawable.ic_edit, contentDescription = context.getString(R.string.use_custom_prompt))
                                        }
                                    )
                                }
                                HorizontalDivider(
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                                )
                                ArrowPreference(
                                    title = context.getString(R.string.custom_skills),
                                    summary = context.getString(R.string.custom_skill_create_manage),
                                    onClick = { showCustomSkillManager = true },
                                    startAction = {
                                        SettingIcon(R.drawable.ic_code, contentDescription = context.getString(R.string.custom_skills))
                                    }
                                )
                                HorizontalDivider(
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                                )
                                ArrowPreference(
                                        title = context.getString(R.string.full_chat_history),
                                        summary = context.getString(R.string.view_full_history_desc),
                                        onClick = { showFullHistoryViewer = true },
                                        startAction = {
                                            SettingIcon(R.drawable.ic_files, contentDescription = context.getString(R.string.full_chat_history))
                                        }
                                    )
                                    SwitchPreference(
                                        title = context.getString(R.string.unrestricted_mode),
                                        summary = if (unlimitedMode) {
                                            context.getString(R.string.fallback_enabled_desc)
                                        } else {
                                            context.getString(R.string.unrestricted_mode_desc)
                                        },
                                        checked = unlimitedMode,
                                        onCheckedChange = { newValue ->
                                            if (newValue) {
                                                showUnlimitedModeConfirm = true
                                            } else {
                                                unlimitedMode = false
                                                AiTermuxPrefs.setUnlimitedMode(context, false)
                                            }
                                        },
                                        startAction = {
                                            SettingIcon(R.drawable.ic_shield, contentDescription = context.getString(R.string.unrestricted_mode))
                                        }
                                    )
                                    if (unlimitedMode) {
                                        HorizontalDivider(
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                            modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                                        )
                                        SwitchPreference(
                                            title = context.getString(R.string.root_exec_agent),
                                            summary = context.getString(R.string.root_auto_su_desc),
                                            checked = rootAutoShell,
                                            onCheckedChange = {
                                                rootAutoShell = it
                                                AiTermuxPrefs.setRootAutoShell(context, it)
                                            },
                                            startAction = {
                                                SettingIcon(R.drawable.ic_root_skull, contentDescription = context.getString(R.string.root_exec_agent))
                                            }
                                        )
                                    }
                            }
                        }
                    }
                }
            }

            // ---------- Tool Configuration (conditional, only for enabled tools) ----------
            if (toolConfigItems.isNotEmpty()) {
                item(key = "section_tool_config") { SmallTitle(text = context.getString(R.string.tool_config_category)) }
                item(key = "card_tool_config") { SettingsGroupCard(items = toolConfigItems) }
            }

            // ---------- Security Settings ----------
            item(key = "section_security") { SmallTitle(text = context.getString(R.string.security_settings)) }
            item(key = "card_security") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column {
                        val protectionItems = RiskConfirmManager.ProtectionLevel.entries.map { level ->
                            DropdownItem(
                                text = level.displayName,
                                summary = level.description
                            )
                        }
                        WindowSpinnerPreference(
                            title = context.getString(R.string.protection_level_title),
                            summary = protectionLevel.description,
                            items = protectionItems,
                            selectedIndex = protectionLevelIndex,
                            onSelectedIndexChange = { idx ->
                                val newLevel = RiskConfirmManager.ProtectionLevel.entries[idx]
                                // 如果选择 OFF 或 WARN_ONLY，触发关闭弹窗确认
                                if (newLevel == RiskConfirmManager.ProtectionLevel.OFF ||
                                    newLevel == RiskConfirmManager.ProtectionLevel.WARN_ONLY) {
                                    RiskConfirmManager.showDisableWarning(context, newLevel)
                                } else {
                                    protectionLevelIndex = idx
                                    protectionLevel = newLevel
                                    RiskConfirmManager.setProtectionLevel(context, newLevel)
                                    riskConfirmEnabled = true
                                }
                            },
                            startAction = {
                                SettingIcon(R.drawable.ic_shield, contentDescription = context.getString(R.string.protection_level_title))
                            }
                        )
                        HorizontalDivider(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                            modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                        )
                        val detectionEnabled = protectionLevel != RiskConfirmManager.ProtectionLevel.OFF
                        val detectionItems = listOf(
                            RiskConfirmManager.DetectionMode.STATIC to R.string.detection_mode_static_desc,
                            RiskConfirmManager.DetectionMode.RUNTIME to R.string.detection_mode_runtime_desc
                        ).map { (mode, descRes) ->
                            DropdownItem(
                                text = mode.displayName,
                                summary = context.getString(descRes)
                            )
                        }
                        // 计算在过滤后的列表中的索引
                        val filteredDetectionIndex = when (detectionMode) {
                            RiskConfirmManager.DetectionMode.RUNTIME -> 1
                            else -> 0
                        }
                        WindowSpinnerPreference(
                            title = context.getString(R.string.detection_mode_title),
                            summary = if (detectionEnabled) {
                                when (detectionMode) {
                                    RiskConfirmManager.DetectionMode.RUNTIME -> context.getString(R.string.detection_mode_runtime_desc)
                                    else -> context.getString(R.string.detection_mode_static_desc)
                                }
                            } else {
                                context.getString(R.string.detection_mode_none_desc)
                            },
                            items = detectionItems,
                            selectedIndex = filteredDetectionIndex,
                            onSelectedIndexChange = { idx ->
                                if (!detectionEnabled) return@WindowSpinnerPreference
                                val mode = if (idx == 1) {
                                    RiskConfirmManager.DetectionMode.RUNTIME
                                } else {
                                    RiskConfirmManager.DetectionMode.STATIC
                                }
                                detectionModeIndex = idx
                                detectionMode = mode
                                RiskConfirmManager.setDetectionMode(context, mode)
                            },
                            enabled = detectionEnabled,
                            startAction = {
                                SettingIcon(R.drawable.ic_detection, contentDescription = context.getString(R.string.detection_mode_title))
                            }
                        )
                    }
                }
            }

            // ---------- System ----------
            item(key = "section_system") { SmallTitle(text = context.getString(R.string.system_category)) }
            item(key = "card_system") { SettingsGroupCard(items = systemSettings) }

            // Extra bottom spacing for comfortable scroll
            item(key = "spacer_bottom") { Spacer(Modifier.height(16.dp)) }
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
                    3 -> "soft_light"
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
            Box(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                HelpContentWithCopyableCommands(
                    content = context.getString(R.string.termux_api_help_content),
                    context = context,
                    snackbarHostState = snackbarHostState
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
            Box(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                HelpContentWithCopyableCommands(
                    content = context.getString(R.string.termux_boot_help_content),
                    context = context,
                    snackbarHostState = snackbarHostState
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
                                        showSnackbar(context.getString(R.string.restore_complete))
                                    } else {
                                        showSnackbar(context.getString(R.string.restore_failed))
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
                    progress = restoreProgress.toFloat() / 100f,
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

    // ---------- AI Termux：清空对话确认 ----------
    if (showAiClearConfirm) {
        OverlayDialog(
            show = true,
            title = context.getString(R.string.clear_chat_title),
            summary = context.getString(R.string.clear_chat_confirm),
            onDismissRequest = { showAiClearConfirm = false },
            content = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    text = context.getString(R.string.cancel),
                    onClick = { showAiClearConfirm = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = context.getString(R.string.clear),
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

    // ---------- AI Termux：信任白名单选择对话框 ----------
    if (showWhitelistDialog && aiTermuxEnabled) {
        // Initialize temp skills from current config when dialog opens
        LaunchedEffect(showWhitelistDialog) {
            tempWhitelistSkills = autoExecConfig.autoExecSkills.mapNotNull { runCatching { SkillType.valueOf(it) }.getOrNull() }.toSet()
        }
        OverlayDialog(
            show = showWhitelistDialog,
            onDismissRequest = { showWhitelistDialog = false },
            title = context.getString(R.string.trust_whitelist),
            summary = context.getString(R.string.whitelist_select_desc),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = context.getString(R.string.whitelist_warning),
                        fontSize = 13.sp,
                        color = Color(0xFFDC2626),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    whitelistSkillLabels.forEach { (skill, label) ->
                        val checked = tempWhitelistSkills.contains(skill)
                        CheckboxPreference(
                            title = label,
                            checked = checked,
                            onCheckedChange = { isChecked ->
                                tempWhitelistSkills = if (isChecked) {
                                    tempWhitelistSkills + skill
                                } else {
                                    tempWhitelistSkills - skill
                                }
                            },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = context.getString(R.string.auto_exec_skills_note),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = context.getString(R.string.agent_permissions_examples),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    Spacer(Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            text = context.getString(R.string.cancel),
                            onClick = { showWhitelistDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = context.getString(R.string.ok),
                            onClick = {
                                // If no skills selected, whitelist is disabled
                                val enabled = tempWhitelistSkills.isNotEmpty()
                                autoExecConfig = autoExecConfig.copy(
                                    autoExecEnabled = enabled,
                                    autoExecSkills = tempWhitelistSkills.map { it.name }.toSet()
                                )
                                AiTermuxPrefs.saveAutoExecConfig(context, autoExecConfig)
                                showWhitelistDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        )
    }

    // ---------- 关闭高危命令二次确认：风险警告弹窗（由 RiskConfirmManager 统一处理） ----------

    // 其余对话框...

    // ---------- AI Termux：编辑 System Prompt ----------
    var systemPromptText by remember { mutableStateOf(AiTermuxPrefs.getConfig(context).customSystemPrompt) }
    OverlayDialog(
        title = context.getString(R.string.edit_system_prompt),
        summary = context.getString(R.string.custom_extra_instructions_desc),
        show = showSystemPromptEditor,
        onDismissRequest = { showSystemPromptEditor = false },
        content = {
        Box(
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
                label = context.getString(R.string.custom_prompt_hint),
                useLabelAsPlaceholder = true,
                maxLines = Int.MAX_VALUE,
                minLines = 5
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = context.getString(R.string.reset_default),
                onClick = {
                    systemPromptText = ""
                    val cfg = AiTermuxPrefs.getConfig(context)
                    AiTermuxPrefs.saveConfig(context, cfg.copy(customSystemPrompt = ""))
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = context.getString(R.string.save),
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

    // ---------- AI Termux：备用在线模型参数编辑 ----------
    OverlayDialog(
        title = context.getString(R.string.configure_backup_llm),
        summary = context.getString(R.string.fallback_desc),
        show = showFallbackEditor,
        onDismissRequest = { showFallbackEditor = false },
        content = {
        Box(
            modifier = Modifier
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = fbUrl,
                    onValueChange = { v -> fbUrl = v },
                    modifier = Modifier.fillMaxWidth(),
                    label = context.getString(R.string.api_base_url_hint),
                    useLabelAsPlaceholder = true
                )
                TextField(
                    value = fbKey,
                    onValueChange = { v -> fbKey = v },
                    modifier = Modifier.fillMaxWidth(),
                    label = context.getString(R.string.api_key_hint),
                    useLabelAsPlaceholder = true
                )
                TextField(
                    value = fbModel,
                    onValueChange = { v -> fbModel = v },
                    modifier = Modifier.fillMaxWidth(),
                    label = context.getString(R.string.model_name_hint),
                    useLabelAsPlaceholder = true
                )
                Text(
                    text = context.getString(R.string.temperature_current, fbTemp),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                androidx.compose.material3.Slider(
                    value = fbTemp,
                    onValueChange = { fbTemp = it },
                    valueRange = 0f..2f,
                    steps = 39,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = context.getString(R.string.cancel),
                onClick = { showFallbackEditor = false },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = context.getString(R.string.save),
                onClick = {
                    AiTermuxPrefs.saveFallbackOnlineConfig(
                        context,
                        AiTermuxPrefs.FallbackOnlineConfig(
                            enabled = true,
                            apiKey = fbKey,
                            baseUrl = fbUrl,
                            model = fbModel,
                            temperature = fbTemp
                        )
                    )
                    showFallbackEditor = false
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
        title = context.getString(R.string.select_prompt_file),
        summary = context.getString(R.string.custom_prompt_pick_md),
        show = showSystemPromptFilePicker,
        onDismissRequest = { showSystemPromptFilePicker = false },
        content = {
        Column {
            Text(
                text = context.getString(R.string.file_picker_choice),
                style = TextStyle(fontSize = 14.sp)
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = context.getString(R.string.termux_builtin),
                    onClick = {
                        showSystemPromptFilePicker = false
                        showInternalPromptPicker = true
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = context.getString(R.string.system_picker),
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
        title = context.getString(R.string.select_prompt_file),
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
                        showSnackbar(context.getString(R.string.custom_prompt_loaded))
                    } else {
                        showSnackbar(context.getString(R.string.file_empty))
                    }
                }
            } catch (e: Exception) {
                showSnackbar(context.getString(R.string.read_file_failed, e.message))
            }
        }
    )

    // ---------- AI Termux：确认还原官方 System Prompt ----------
    OverlayDialog(
        title = context.getString(R.string.restore_official_prompt),
        summary = context.getString(R.string.restore_official_prompt_confirm),
        show = showSystemPromptRestoreConfirm,
        onDismissRequest = { showSystemPromptRestoreConfirm = false },
        content = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                text = context.getString(R.string.cancel),
                onClick = { showSystemPromptRestoreConfirm = false }
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = context.getString(R.string.confirm_restore),
                onClick = {
                    AiTermuxPrefs.setUseCustomSystemPrompt(context, false)
                    useCustomSystemPrompt = false
                    systemPromptSource = ""
                    showSystemPromptRestoreConfirm = false
                    showSnackbar(context.getString(R.string.switched_official_prompt))
                },
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
        }
    )

    // ---------- AI Termux：自定义技能管理 ----------
    var skillsRefreshKey by remember { mutableStateOf(0) }
    OverlayDialog(
        title = context.getString(R.string.custom_skills),
        summary = context.getString(R.string.custom_skill_manage_desc),
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
                    text = context.getString(R.string.no_custom_skills),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .heightIn(max = 350.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Column {
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
                                    text = context.getString(R.string.edit),
                                    onClick = {
                                        editingSkill = skill
                                        showAddEditSkillDialog = true
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    text = context.getString(R.string.delete),
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
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = context.getString(R.string.off),
                onClick = { showCustomSkillManager = false },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = context.getString(R.string.add_skill),
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
        "shell_command" to context.getString(R.string.shell_command),
        "open_activity" to context.getString(R.string.open_page),
        "send_broadcast" to context.getString(R.string.send_broadcast),
        "custom" to context.getString(R.string.custom)
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
        title = if (editingSkill != null) context.getString(R.string.edit_skill) else context.getString(R.string.add_custom_skill),
        summary = context.getString(R.string.custom_skill_create_desc),
        show = showAddEditSkillDialog,
        onDismissRequest = { showAddEditSkillDialog = false },
        content = {
        Box(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Column {
            TextField(
                value = skillName,
                onValueChange = { skillName = it },
                modifier = Modifier.fillMaxWidth(),
                label = context.getString(R.string.skill_name_hint),
                useLabelAsPlaceholder = true,
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = skillDescription,
                onValueChange = { skillDescription = it },
                modifier = Modifier.fillMaxWidth(),
                label = context.getString(R.string.skill_description),
                useLabelAsPlaceholder = true,
                singleLine = false,
                maxLines = 2
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = context.getString(R.string.implementation),
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
                text = context.getString(R.string.skill_invocation_desc),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            TextField(
                value = skillJson,
                onValueChange = { skillJson = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
                label = context.getString(R.string.impl_example_prefix, implJsonTemplates[skillImplementationType]),
                useLabelAsPlaceholder = true,
                maxLines = Int.MAX_VALUE,
                minLines = 3
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = context.getString(R.string.impl_notes_hint),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            TextField(
                value = skillSystemPrompt,
                onValueChange = { skillSystemPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
                label = context.getString(R.string.skill_impl_detail_hint),
                useLabelAsPlaceholder = true,
                maxLines = Int.MAX_VALUE,
                minLines = 3
            )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = context.getString(R.string.cancel),
                onClick = { showAddEditSkillDialog = false },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = context.getString(R.string.save),
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
        title = context.getString(R.string.full_chat_history),
        summary = context.getString(R.string.chat_history_full_desc),
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
                    text = context.getString(R.string.no_chat_history),
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
                                    "user" -> context.getString(R.string.tab_user)
                                    "assistant" -> "🤖 AI"
                                    "system" -> context.getString(R.string.tab_system)
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
                            // reasoningContent 已移除
                            if (false) {
                                Text(
                                    text = "",
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            Text(
                                text = msg.content.ifBlank { context.getString(R.string.empty) },
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
                                    text = context.getString(R.string.copy),
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable {
                                            val clip = android.content.ClipData.newPlainText(context.getString(R.string.messages), msg.content)
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
                text = context.getString(R.string.copy_all),
                onClick = {
                    val allContent = buildString {
                        appendLine("=== System Prompt ===")
                        appendLine(AiTermuxPrefs.buildFullSystemPrompt(context))
                        appendLine()
                        appendLine(context.getString(R.string.chat_history_header))
                        messages.forEach { msg ->
                            appendLine("[${msg.role}] ${msg.content}")
                        }
                    }
                    val clip = android.content.ClipData.newPlainText(context.getString(R.string.full_chat_history), allContent)
                    clipboard.setPrimaryClip(clip)
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = context.getString(R.string.off),
                onClick = { showFullHistoryViewer = false },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
        }
    )

    // ---------- 无限制模式二次确认弹窗 ----------
    var unlimitedCheckboxChecked by remember { mutableStateOf(false) }
    var isUnlimitedAuthenticating by remember { mutableStateOf(false) }
    LaunchedEffect(showUnlimitedModeConfirm) {
        if (!showUnlimitedModeConfirm) {
            unlimitedCheckboxChecked = false
            isUnlimitedAuthenticating = false
        }
    }
    val unlimitedScope = rememberCoroutineScope()
    val unlimitedShowBlocked: () -> Unit = {
        val msg = context.getString(R.string.accessibility_guard_blocked_toast)
        SnackbarHelper.show(context, msg, Snackbar.LENGTH_LONG)
    }
    OverlayDialog(
        show = showUnlimitedModeConfirm,
        onDismissRequest = {
            showUnlimitedModeConfirm = false
        },
        title = context.getString(R.string.enable_unrestricted),
        summary = context.getString(R.string.unrestricted_mode_banner),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Text(
                    text = context.getString(R.string.unrestricted_mode_warning),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                CheckboxPreference(
                    title = context.getString(R.string.confirm_unrestricted),
                    checked = unlimitedCheckboxChecked,
                    onCheckedChange = { unlimitedCheckboxChecked = it },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Button(
                        onClick = {
                            showUnlimitedModeConfirm = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = Color.Transparent
                        )
                    ) {
                        Text(
                            text = context.getString(R.string.cancel),
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Button(
                        onClick = {
                            isUnlimitedAuthenticating = true
                            val activity = context as? FragmentActivity
                            if (activity != null) {
                                launchBiometricAuth(activity) { success ->
                                    isUnlimitedAuthenticating = false
                                    if (success) {
                                        unlimitedMode = true
                                        AiTermuxPrefs.setUnlimitedMode(context, true)
                                        showUnlimitedModeConfirm = false
                                    } else {
                                        val msg = context.getString(R.string.risk_command_biometric_prompt)
                                        SnackbarHelper.show(context, msg, Snackbar.LENGTH_SHORT)
                                    }
                                }
                            } else {
                                unlimitedMode = true
                                AiTermuxPrefs.setUnlimitedMode(context, true)
                                showUnlimitedModeConfirm = false
                            }
                        },
                        enabled = unlimitedCheckboxChecked && !isUnlimitedAuthenticating,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = if (unlimitedCheckboxChecked && !isUnlimitedAuthenticating) Color(0xFFD32F2F) else Color(0xFFBDBDBD)
                        )
                    ) {
                        Text(
                            text = context.getString(R.string.confirm_enable),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
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
                    HorizontalDivider(
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
    context: Context,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    fun showSnackbar(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }
    val lines = content.split("\n")
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        lines.forEachIndexed { index, rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                return@forEachIndexed
            }

            val commandText: String? = when {
                trimmed.startsWith("• ") -> {
                    val afterBullet = trimmed.substring(2).trim()
                    val dashIdx = afterBullet.indexOf(" — ")
                    if (dashIdx > 0) afterBullet.substring(0, dashIdx).trim()
                    else if (afterBullet.startsWith("termux-") || afterBullet.startsWith("pkg ")) afterBullet
                    else null
                }
                trimmed.startsWith("pkg ") ||
                trimmed.startsWith("mkdir ") ||
                trimmed.startsWith("termux-") ||
                trimmed.startsWith("#!/") ||
                trimmed.startsWith("sshd") ||
                trimmed.startsWith("termux-wake-lock") -> trimmed
                rawLine.trimStart().startsWith("#!/") -> rawLine.trimStart()
                rawLine.trimStart().startsWith("termux-wake-lock") -> rawLine.trimStart()
                rawLine.trimStart().startsWith("sshd") -> rawLine.trimStart()
                else -> null
            }

            if (commandText != null) {
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
                                val clip = android.content.ClipData.newPlainText(context.getString(R.string.command), commandText)
                                clipboard.setPrimaryClip(clip)
                                showSnackbar(context.getString(R.string.copied_command, commandText))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = context.getString(R.string.copy),
                            modifier = Modifier.size(16.dp),
                            tint = MiuixTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                Text(
                    text = rawLine,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    lineHeight = 22.sp
                )
            }
        }
    }
}
