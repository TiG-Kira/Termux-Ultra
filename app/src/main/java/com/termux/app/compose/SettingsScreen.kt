package com.termux.app.compose

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import com.termux.app.RuntimeEnvironmentResetter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.painter.Painter
import com.termux.app.LocaleHelper
import com.termux.app.compose.AiTermuxPrefs
import com.termux.app.compose.LocalTopBarClearance
import com.termux.app.compose.AiLocalModel
import com.termux.app.compose.SkillType
import com.termux.app.utils.SnackbarHelper
import com.google.android.material.snackbar.Snackbar
import java.io.File

data class SettingItem(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val action: () -> Unit,
    val hasSwitch: Boolean = false,
    val switchValue: Boolean = false,
    val onSwitchChange: (Boolean) -> Unit = {},
    val badgeIcon: ImageVector? = null
)

/** 搜索用设置项元数据 */
private data class SearchableSetting(
    val section: String,
    val title: String,
    val summary: String,
    val keywords: List<String>,
    val render: @Composable () -> Unit
)

@Composable
fun SettingsScreen(
    onAboutClick: () -> Unit,
    navBarBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onTopBarContent: (@Composable () -> Unit) -> Unit,
    active: Boolean = true
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
    var showResetConfigWarning by remember { mutableStateOf(false) }
    var showWhitelistDialog by remember { mutableStateOf(false) }
    var tempWhitelistSkills by remember { mutableStateOf<Set<SkillType>>(emptySet()) }

    // ---------- Reset Runtime Env state ----------
    var showResetEnvConfirm by remember { mutableStateOf(false) }
    var showResetEnvProgress by remember { mutableStateOf(false) }
    var resetEnvStep by remember { mutableStateOf(RuntimeEnvironmentResetter.Step.DOWNLOADING) }
    var resetEnvMessage by remember { mutableStateOf("") }
    var resetEnvFailMode by remember { mutableStateOf<Int?>(null) }
    var resetEnvErrorMsg by remember { mutableStateOf("") }

    // Whitelistable skills definition：与 SkillType.requiresClick() 中可白名单化的类型保持一致
    val whitelistSkillLabels = remember {
        listOf(
            SkillType.CAPTURE_OUTPUT to context.getString(R.string.capture_output_desc),
            SkillType.SUB_AGENT to context.getString(R.string.whitelist_sub_agent_desc),
            SkillType.SEARCH_AGENT to context.getString(R.string.whitelist_search_agent_desc),
            SkillType.COMPILE_CODE to context.getString(R.string.whitelist_compile_code_desc),
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

    // 设置页搜索
    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
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

    // Agent 在线主配置 / 模型配置档 / 对话参数 / 长期记忆（具体内容由各自的弹窗自持）
    var showOnlineConfigEditor by remember { mutableStateOf(false) }
    var showProfileManager by remember { mutableStateOf(false) }
    var showChatParamsEditor by remember { mutableStateOf(false) }
    var showMemoryEditor by remember { mutableStateOf(false) }

    // 高风险命令二次确认
    var riskConfirmEnabled by remember { mutableStateOf(RiskConfirmManager.getProtectionLevel(context) != RiskConfirmManager.ProtectionLevel.OFF) }

    // 防护等级
    var protectionLevel by remember { mutableStateOf(RiskConfirmManager.getProtectionLevel(context)) }
    var protectionLevelIndex by remember { mutableIntStateOf(RiskConfirmManager.getProtectionLevel(context).ordinal) }

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



    // 终端相关设置已拆分到独立子页面（控制台设置 / 按键设置 / 控制台日志 / 控制台个性化），
    // 各自在对应 Activity 中完成 ComposeTerminalSettings 等初始化，设置主页不再内联这些项。

    // Material You 动态取色：与上面终端设置同一套路（偏好即 StateFlow）
    AppThemePrefs.init(context)
    val materialYouEnabled by AppThemePrefs.materialYouEnabled.collectAsState()

    // 启动行为偏好：落地页 + 启动即开控制台。"自动启动终端控制台"开着时落地页固定总览。
    LaunchPrefs.init(context)
    val launchPagePreference by LaunchPrefs.launchPage.collectAsState()
    val autoStartConsoleEnabled by LaunchPrefs.autoStartConsole.collectAsState()
    // 下拉显示的下标完全由偏好推导：锁死时显示总览，但磁盘上的用户选择不动。
    val launchPageSelectedIndex =
        if (autoStartConsoleEnabled || launchPagePreference == LaunchPrefs.LaunchPage.OVERVIEW) 0 else 1

    // Official standalone APK detection. Keys match the add-on app package names; when a standalone
    // APK is installed, the integrated toggle is forced OFF and disabled, with the row shows
    // "Replaced by the official standalone plugin" instead of the normal help summary.
    val apiStandaloneInstalled = IntegratedTools.isStandaloneInstalled(context, IntegratedTools.Tool.TERMUX_API)
    val bootStandaloneInstalled = IntegratedTools.isStandaloneInstalled(context, IntegratedTools.Tool.TERMUX_BOOT)
    val stylingStandaloneInstalled = IntegratedTools.isStandaloneInstalled(context, IntegratedTools.Tool.TERMUX_STYLING)
    val taskerStandaloneInstalled = IntegratedTools.isStandaloneInstalled(context, IntegratedTools.Tool.TERMUX_TASKER)
    val widgetStandaloneInstalled = IntegratedTools.isStandaloneInstalled(context, IntegratedTools.Tool.TERMUX_WIDGET)

    val replacedSummary = context.getString(R.string.standalone_plugin_installed_summary)

    val languageOptions = listOf(
        context.getString(R.string.chinese),
        context.getString(R.string.english)
    )
    var languageSelectedIndex by remember {
        mutableStateOf(if (LocaleHelper.isChinese(context)) 0 else 1)
    }

    val navBarStyleOptions = listOf(
        context.getString(R.string.navigation_bar_floating),
        context.getString(R.string.navigation_bar_default),
        context.getString(R.string.navigation_bar_liquid_glass)
    )
    var navBarSelectedIndex by remember {
        mutableStateOf(
            when (prefs.getString("navigation_bar_style", null)) {
                "classic" -> 1
                "liquid_glass" -> 2
                // 旧 "soft_light"（柔光，已移除）以及历史值（旧 default / 旧 floating）统一迁到新的浮动玻璃底栏
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

    // 软件包管理显示方式
    var pkgViewModeIndex by remember {
        mutableStateOf(prefs.getInt("KEY_PKG_VIEW_MODE", 0))
    }

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
                    val tempFile = File(context.cacheDir, "temp_backup.tar")
                    var result = false
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        inputStream?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        result = BackupManager.restoreBackup(context, tempFile.absolutePath) { processed, total, message ->
                            val display = when {
                                message.isNotBlank() -> message
                                processed > 0 -> context.getString(R.string.restored_size, processed)
                                else -> context.getString(R.string.initializing)
                            }
                            mainHandler.post {
                                NotificationHelper.showProgressNotification(context, restoreTitle, 0, total, display, pendingCancelIntent)
                            }
                        }
                    } catch (_: Throwable) {
                        result = false
                    } finally {
                        // 无论成功或异常都删除临时文件，避免缓存目录残留
                        tempFile.delete()
                    }
                    mainHandler.post {
                        isProcessing = false
                        // 无论成功或异常都反注册接收器，避免 BroadcastReceiver 泄漏
                        try {
                            context.unregisterReceiver(cancelReceiver)
                        } catch (_: IllegalArgumentException) {
                        }
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
        if (vncEnabled)
                            SettingItem(
            title = context.getString(R.string.vnc_settings),
            description = context.getString(R.string.vnc_settings_desc),
            icon = Icons.Rounded.DesktopWindows,
            badgeIcon = Icons.Rounded.Settings,
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
            icon = Icons.Rounded.Backup,
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
                        var backupPath: String? = null
                        try {
                            backupPath = BackupManager.createBackup(context) { processed, total, message ->
                                val display = when {
                                    message.isNotBlank() -> message
                                    processed > 0 -> context.getString(R.string.backed_up_size, processed)
                                    else -> context.getString(R.string.initializing)
                                }
                                mainHandler.post {
                                    NotificationHelper.showProgressNotification(context, backupTitle, 0, total, display, pendingCancelIntent)
                                }
                            }
                        } catch (_: Throwable) {
                            backupPath = null
                        }
                        mainHandler.post {
                            isProcessing = false
                            // 无论成功或异常都反注册接收器，避免 BroadcastReceiver 泄漏
                            try {
                                context.unregisterReceiver(cancelReceiver)
                            } catch (_: IllegalArgumentException) {
                            }
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
            icon = Icons.Rounded.Restore,
            action = {
                launchRestore = true
            }
        )
    )

    val systemSettings = remember {
        buildList {
            add(
                SettingItem(
                    title = context.getString(R.string.notification_management),
                    description = context.getString(R.string.notification_management_desc),
                    icon = Icons.Rounded.Notifications,
                    action = {
                        val intent = Intent(context, com.termux.app.activities.NotificationManagerActivity::class.java)
                        context.startActivity(intent)
                    }
                )
            )
            add(
                SettingItem(
                    title = context.getString(R.string.log_management),
                    description = context.getString(R.string.log_management_desc),
                    icon = Icons.Rounded.BugReport,
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
                    icon = Icons.Rounded.Storage,
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
                    icon = Icons.Rounded.Info,
                    action = { onAboutClick() }
                )
            )
        }
    }

    // 「配置集成工具」入口可见性：五个开关任一为开即显示，全关即隐藏。
    // 判断放在渲染处而不是缓存成 val，开关 onCheckedChange 改的是 termuxXxxEnabled，
    // 重组时重新求值才能做到即时显隐。
    val anyIntegratedToolEnabled = termuxApiEnabled || termuxBootEnabled || termuxStylingEnabled ||
        termuxTaskerEnabled || termuxWidgetEnabled
    // 与「统一顶栏之前」一致：本页自持吸顶状态
    val scrollBehavior = MiuixScrollBehavior()
    // 统一全局顶栏：仅当前激活页把本页的 TopAppBar 内容写入 onTopBarContent 槽
    SideEffect {
        if (active) {
            onTopBarContent {
                GlassTopAppBar(
                    title = context.getString(R.string.settings_title),
                    backdrop = LocalGlassTopAppBarBackdrop.current,
                    scrollBehavior = scrollBehavior,
                )
            }
        }
    }

    // ===== 搜索索引：所有可搜索设置项（独立 Card 展示 + 保留交互）=====
    val sec_appearance = context.getString(R.string.appearance)
    val sec_pkg_mgmt = context.getString(R.string.pkg_mgmt_category)
    val sec_remote = context.getString(R.string.remote)
    val sec_terminal = context.getString(R.string.terminal)
    val sec_tools = context.getString(R.string.integrated_tools_category)
    val sec_ai = "Termux Agent"
    val sec_security = context.getString(R.string.security_settings)
    val sec_system = context.getString(R.string.system_category)
    val sec_backup = context.getString(R.string.backup_category)

    // 将 SettingItem 转换为 SearchableSetting 的扩展函数
    fun SettingItem.toSearchable(section: String): SearchableSetting {
        return SearchableSetting(
            section = section,
            title = this.title,
            summary = this.description,
            keywords = listOf(this.title, this.description),
            render = {
                if (this.hasSwitch) {
                    SwitchPreference(
                        title = this.title,
                        summary = this.description,
                        checked = this.switchValue,
                        onCheckedChange = this.onSwitchChange,
                        startAction = { SettingIcon(this.icon, contentDescription = this.title) }
                    )
                } else {
                    ArrowPreference(
                        title = this.title,
                        summary = this.description,
                        onClick = this.action,
                        startAction = { SettingIcon(this.icon, contentDescription = this.title) }
                    )
                }
            }
        )
    }

    val searchableItems = listOf(
        // ===== Appearance =====
        SearchableSetting(sec_appearance, context.getString(R.string.pref_material_you_title), context.getString(R.string.pref_material_you_summary),
            keywords = listOf("material you", "monet", "动态取色", "配色", "主题", "theme", "dynamic color"),
            render = {
                MaterialYouSwitch(
                    context = context,
                    checked = materialYouEnabled,
                    onCheckedChange = { AppThemePrefs.setMaterialYouEnabled(context, it) }
                )
            }),
        SearchableSetting(sec_appearance, context.getString(R.string.pref_launch_page_title),
            context.getString(
                if (autoStartConsoleEnabled) R.string.pref_launch_page_locked_summary
                else R.string.pref_launch_page_summary
            ),
            keywords = listOf("启动", "启动页", "launch", "startup", "home", "default page"),
            render = {
                OverlayDropdownPreference(
                    title = context.getString(R.string.pref_launch_page_title),
                    summary = context.getString(
                        if (autoStartConsoleEnabled) R.string.pref_launch_page_locked_summary
                        else R.string.pref_launch_page_summary
                    ),
                    items = listOf(
                        context.getString(R.string.pref_launch_page_overview),
                        context.getString(R.string.pref_launch_page_terminal)
                    ),
                    // 开着自动控制台时强制显示总览，磁盘上的用户选择保持不动。
                    selectedIndex = if (autoStartConsoleEnabled) 0 else launchPageSelectedIndex,
                    enabled = !autoStartConsoleEnabled,
                    onSelectedIndexChange = { idx ->
                        LaunchPrefs.setLaunchPage(
                            context,
                            if (idx == 1) LaunchPrefs.LaunchPage.TERMINAL else LaunchPrefs.LaunchPage.OVERVIEW
                        )
                    },
                    startAction = { SettingIcon(Icons.Rounded.Home, contentDescription = context.getString(R.string.pref_launch_page_title)) }
                )
            }),
        SearchableSetting(sec_appearance, context.getString(R.string.language), context.getString(R.string.language_description),
            keywords = listOf("语言", "language", "中文", "英文", "locale"),
            render = {
                OverlayDropdownPreference(
                    title = context.getString(R.string.language),
                    summary = context.getString(R.string.language_description),
                    items = languageOptions,
                    selectedIndex = languageSelectedIndex,
                    onSelectedIndexChange = { idx ->
                        languageSelectedIndex = idx
                        if (idx == 0) LocaleHelper.setChinese(context) else LocaleHelper.setEnglish(context)
                        showRestartPrompt = true
                    },
                    startAction = { SettingIcon(Icons.Rounded.Language, contentDescription = context.getString(R.string.language)) }
                )
            }),
        SearchableSetting(sec_appearance, context.getString(R.string.navigation_bar_style), context.getString(R.string.navigation_bar_style_description),
            keywords = listOf("导航栏", "navigation", "navbar", "玻璃", "classic", "liquid"),
            render = {
                OverlayDropdownPreference(
                    title = context.getString(R.string.navigation_bar_style),
                    summary = context.getString(R.string.navigation_bar_style_description),
                    items = navBarStyleOptions,
                    selectedIndex = navBarSelectedIndex,
                    onSelectedIndexChange = { idx ->
                        if (idx == 2 && !ApiCompat.isFeatureUsable(context, ApiCompat.Feature.GLASS_NAVIGATION_BAR)) {
                            pendingNavStyleIndex = idx; showCriticalNavDialog = true; return@OverlayDropdownPreference
                        }
                        navBarSelectedIndex = idx
                        val style = when (idx) { 1 -> "classic"; 2 -> "liquid_glass"; else -> "glass" }
                        prefs.edit().putString("navigation_bar_style", style).apply()
                        showNavRestartPrompt = true
                    },
                    startAction = { SettingIcon(Icons.Rounded.Navigation, contentDescription = context.getString(R.string.navigation_bar_style)) }
                )
            }),
        SearchableSetting(sec_appearance, context.getString(R.string.horizontal_tip_layout), context.getString(R.string.overview_horizontal_cards_desc),
            keywords = listOf("横排", "布局", "layout", "horizontal", "cards"),
            render = {
                SwitchPreference(
                    title = context.getString(R.string.horizontal_tip_layout),
                    summary = context.getString(R.string.overview_horizontal_cards_desc),
                    checked = cardLayoutMode == 1,
                    onCheckedChange = { cardLayoutMode = if (it) 1 else 0; prefs.edit().putInt("KEY_CARD_LAYOUT_MODE", cardLayoutMode).apply() },
                    startAction = { SettingIcon(Icons.Rounded.SwapHoriz, contentDescription = context.getString(R.string.horizontal_tip_layout)) }
                )
            }),
        SearchableSetting(sec_pkg_mgmt, context.getString(R.string.pkg_view_mode), context.getString(R.string.pkg_view_mode_desc),
            keywords = listOf("软件包", "包管理", "package", "分类", "列表", "view mode"),
            render = {
                OverlayDropdownPreference(
                    title = context.getString(R.string.pkg_view_mode),
                    summary = context.getString(R.string.pkg_view_mode_desc),
                    items = listOf(context.getString(R.string.pkg_view_mode_category), context.getString(R.string.pkg_view_mode_list)),
                    selectedIndex = pkgViewModeIndex,
                    onSelectedIndexChange = { idx -> pkgViewModeIndex = idx; prefs.edit().putInt("KEY_PKG_VIEW_MODE", idx).apply() },
                    startAction = { SettingIcon(Icons.Rounded.Folder, contentDescription = context.getString(R.string.pkg_view_mode)) }
                )
            }),
        SearchableSetting(sec_pkg_mgmt, context.getString(R.string.pkg_edit_sources), context.getString(R.string.pkg_edit_sources_desc),
            keywords = listOf("软件源", "源", "source", "mirror", "apt", "repository"),
            render = {
                ArrowPreference(
                    title = context.getString(R.string.pkg_edit_sources),
                    summary = context.getString(R.string.pkg_edit_sources_desc),
                    onClick = { openSoftwareSourceSettings(context) },
                    startAction = { SettingIcon(Icons.Rounded.Dns, contentDescription = context.getString(R.string.pkg_edit_sources)) }
                )
            }),

        // ===== Remote =====
        SearchableSetting(sec_remote, context.getString(R.string.vnc), context.getString(R.string.vnc_description),
            keywords = listOf("vnc", "远程", "remote", "桌面"),
            render = {
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
                    startAction = { SettingIcon(Icons.Rounded.DesktopWindows, contentDescription = context.getString(R.string.vnc)) }
                )
            }),

        // ===== Terminal =====
        SearchableSetting(sec_terminal, context.getString(R.string.console_settings), context.getString(R.string.console_settings_desc),
            keywords = listOf("控制台", "字体", "光标", "闪烁", "滚动", "缓冲区", "console", "font", "cursor", "blink", "scrollback"),
            render = {
                ArrowPreference(
                    title = context.getString(R.string.console_settings),
                    summary = context.getString(R.string.console_settings_desc),
                    onClick = { openConsoleSettings(context) },
                    startAction = { SettingIcon(Icons.Rounded.Tune) }
                )
            }),
        SearchableSetting(sec_terminal, context.getString(R.string.key_settings), context.getString(R.string.key_settings_desc),
            keywords = listOf("按键", "键盘", "软键盘", "物理键盘", "自定义布局", "key", "keyboard", "layout"),
            render = {
                ArrowPreference(
                    title = context.getString(R.string.key_settings),
                    summary = context.getString(R.string.key_settings_desc),
                    onClick = { openKeySettings(context) },
                    startAction = { SettingIcon(Icons.Rounded.Keyboard) }
                )
            }),
        SearchableSetting(sec_terminal, context.getString(R.string.console_log), context.getString(R.string.console_log_desc),
            keywords = listOf("日志", "按键日志", "日志级别", "log", "debug", "verbose", "key logging"),
            render = {
                ArrowPreference(
                    title = context.getString(R.string.console_log),
                    summary = context.getString(R.string.console_log_desc),
                    onClick = { openConsoleLog(context) },
                    startAction = { SettingIcon(Icons.Rounded.BugReport) }
                )
            }),
        SearchableSetting(sec_terminal, context.getString(R.string.console_personalization), context.getString(R.string.console_personalization_desc),
            keywords = listOf("个性化", "自动启动", "编辑器", "欢迎文本", "motd", "personalization", "auto-start", "editor", "welcome"),
            render = {
                ArrowPreference(
                    title = context.getString(R.string.console_personalization),
                    summary = context.getString(R.string.console_personalization_desc),
                    onClick = { openConsolePersonalization(context) },
                    startAction = { SettingIcon(Icons.Rounded.Palette) }
                )
            }),

        // ===== Integrated Tools =====
        SearchableSetting(sec_tools, context.getString(R.string.termux_api_tool), context.getString(R.string.termux_api_tool_summary),
            keywords = listOf("termux-api", "api", "termux api"),
            render = {
                IntegratedToolSwitch(
                    title = context.getString(R.string.termux_api_tool),
                    summary = if (apiStandaloneInstalled) replacedSummary else context.getString(R.string.termux_api_tool_summary),
                    icon = Icons.Rounded.Terminal,
                    checked = termuxApiEnabled,
                    onCheckedChange = {
                        termuxApiEnabled = it
                        IntegratedTools.setEnabled(context, IntegratedTools.Tool.TERMUX_API, it)
                        IntegratedTools.applyComponentState(context, IntegratedTools.Tool.TERMUX_API, it)
                    },
                    enabled = !apiStandaloneInstalled,
                    onDisabledClick = { IntegratedTools.showStandaloneConflictPrompt(context, IntegratedTools.Tool.TERMUX_API) }
                )
            }),
        SearchableSetting(sec_tools, context.getString(R.string.termux_boot_tool), context.getString(R.string.termux_boot_tool_summary),
            keywords = listOf("termux-boot", "boot", "开机"),
            render = {
                IntegratedToolSwitch(
                    title = context.getString(R.string.termux_boot_tool),
                    summary = if (bootStandaloneInstalled) replacedSummary else context.getString(R.string.termux_boot_tool_summary),
                    icon = Icons.AutoMirrored.Rounded.Launch,
                    checked = termuxBootEnabled,
                    onCheckedChange = {
                        termuxBootEnabled = it
                        IntegratedTools.setEnabled(context, IntegratedTools.Tool.TERMUX_BOOT, it)
                        IntegratedTools.applyComponentState(context, IntegratedTools.Tool.TERMUX_BOOT, it)
                    },
                    enabled = !bootStandaloneInstalled,
                    onDisabledClick = { IntegratedTools.showStandaloneConflictPrompt(context, IntegratedTools.Tool.TERMUX_BOOT) }
                )
            }),
        SearchableSetting(sec_tools, context.getString(R.string.termux_tasker_tool), context.getString(R.string.termux_tasker_tool_summary),
            keywords = listOf("termux-tasker", "tasker", "自动化"),
            render = {
                IntegratedToolSwitch(
                    title = context.getString(R.string.termux_tasker_tool),
                    summary = if (taskerStandaloneInstalled) replacedSummary else context.getString(R.string.termux_tasker_tool_summary),
                    icon = Icons.Rounded.Tune,
                    checked = termuxTaskerEnabled,
                    onCheckedChange = {
                        termuxTaskerEnabled = it
                        IntegratedTools.setEnabled(context, IntegratedTools.Tool.TERMUX_TASKER, it)
                        IntegratedTools.applyComponentState(context, IntegratedTools.Tool.TERMUX_TASKER, it)
                    },
                    enabled = !taskerStandaloneInstalled,
                    onDisabledClick = { IntegratedTools.showStandaloneConflictPrompt(context, IntegratedTools.Tool.TERMUX_TASKER) }
                )
            }),
        SearchableSetting(sec_tools, context.getString(R.string.termux_styling_tool), context.getString(R.string.termux_styling_tool_summary),
            keywords = listOf("termux-styling", "styling", "主题", "theme"),
            render = {
                IntegratedToolSwitch(
                    title = context.getString(R.string.termux_styling_tool),
                    summary = if (stylingStandaloneInstalled) replacedSummary else context.getString(R.string.termux_styling_tool_summary),
                    icon = Icons.Rounded.Palette,
                    checked = termuxStylingEnabled,
                    onCheckedChange = {
                        termuxStylingEnabled = it
                        IntegratedTools.setEnabled(context, IntegratedTools.Tool.TERMUX_STYLING, it)
                        IntegratedTools.applyComponentState(context, IntegratedTools.Tool.TERMUX_STYLING, it)
                    },
                    enabled = !stylingStandaloneInstalled,
                    onDisabledClick = { IntegratedTools.showStandaloneConflictPrompt(context, IntegratedTools.Tool.TERMUX_STYLING) }
                )
            }),
        SearchableSetting(sec_tools, context.getString(R.string.termux_widget_tool), context.getString(R.string.termux_widget_tool_summary),
            keywords = listOf("termux-widget", "widget", "小部件"),
            render = {
                IntegratedToolSwitch(
                    title = context.getString(R.string.termux_widget_tool),
                    summary = if (widgetStandaloneInstalled) replacedSummary else context.getString(R.string.termux_widget_tool_summary),
                    icon = Icons.Rounded.Star,
                    checked = termuxWidgetEnabled,
                    onCheckedChange = {
                        termuxWidgetEnabled = it
                        IntegratedTools.setEnabled(context, IntegratedTools.Tool.TERMUX_WIDGET, it)
                        IntegratedTools.applyComponentState(context, IntegratedTools.Tool.TERMUX_WIDGET, it)
                    },
                    enabled = !widgetStandaloneInstalled,
                    onDisabledClick = { IntegratedTools.showStandaloneConflictPrompt(context, IntegratedTools.Tool.TERMUX_WIDGET) }
                )
            }),

        // ===== AI Agent =====
        SearchableSetting(sec_ai, "Termux Agent", context.getString(R.string.agent_entry_card_desc),
            keywords = listOf("agent", "ai", "智能体", "大模型", "llm"),
            render = {
                SwitchPreference(
                    title = "Termux Agent",
                    summary = context.getString(R.string.agent_entry_card_desc),
                    checked = aiTermuxEnabled,
                    onCheckedChange = { aiTermuxEnabled = it; prefs.edit().putBoolean("ai_termux_enabled", it).apply() },
                    startAction = { SettingIcon(Icons.Rounded.Lightbulb, contentDescription = "Termux Agent") }
                )
            }),
        SearchableSetting(sec_ai, context.getString(R.string.trust_whitelist), "",
            keywords = listOf("白名单", "whitelist", "信任", "trust"),
            render = {
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
                    onClick = { openAiTermuxModelGen(context) },
                    startAction = { SettingIcon(Icons.Rounded.Shield, contentDescription = context.getString(R.string.trust_whitelist)) }
                )
            }),
        SearchableSetting(sec_ai, context.getString(R.string.reconfigure_ai), context.getString(R.string.reset_config_desc),
            keywords = listOf("重新配置", "重置", "reset", "reconfigure", "重新设置", "恢复"),
            render = {
                ArrowPreference(
                    title = context.getString(R.string.reconfigure_ai),
                    summary = context.getString(R.string.reset_config_desc),
                    onClick = { openAiTermuxModelGen(context) },
                    startAction = { SettingIcon(Icons.Rounded.Autorenew, contentDescription = context.getString(R.string.reconfigure_ai)) }
                )
            }),
        SearchableSetting(sec_ai, context.getString(R.string.clear_chat_history), context.getString(R.string.clear_agent_history_desc),
            keywords = listOf("清除", "clear", "历史", "history", "聊天记录"),
            render = {
                ArrowPreference(
                    title = context.getString(R.string.clear_chat_history),
                    summary = context.getString(R.string.clear_agent_history_desc),
                    onClick = { openAiTermuxMemoryHistory(context) },
                    startAction = { SettingIcon(Icons.Rounded.Delete, contentDescription = context.getString(R.string.clear_chat_history)) }
                )
            }),
        *if (isLocalMode) listOf(SearchableSetting(sec_ai, context.getString(R.string.agent_online_config), context.getString(R.string.agent_online_config_desc),
            keywords = listOf("在线", "online", "api", "key", "模型", "model", "地址", "url"),
            render = {
                ArrowPreference(
                    title = context.getString(R.string.agent_online_config),
                    summary = context.getString(R.string.agent_online_config_desc),
                    onClick = { openAiTermuxModelGen(context) },
                    startAction = { SettingIcon(Icons.Rounded.Cloud, contentDescription = context.getString(R.string.agent_online_config)) }
                )
            })).toTypedArray() else emptyArray(),
        SearchableSetting(sec_ai, context.getString(R.string.agent_profiles), context.getString(R.string.agent_profiles_desc),
            keywords = listOf("配置档", "profile", "多模型", "切换", "switch"),
            render = {
                ArrowPreference(
                    title = context.getString(R.string.agent_profiles),
                    summary = context.getString(R.string.agent_profiles_desc),
                    onClick = { openAiTermuxModelGen(context) },
                    startAction = { SettingIcon(Icons.Rounded.Dashboard, contentDescription = context.getString(R.string.agent_profiles)) }
                )
            }),
        SearchableSetting(sec_ai, "AgentPaw 设置", "手机操控 Agent，LLM 沿用 Termux Agent 配置",
            keywords = listOf("agentpaw", "paw", "手机操控", "自动化", "无障碍"),
            render = {
                ArrowPreference(
                    title = "AgentPaw 设置",
                    summary = "手机操控 Agent，LLM 沿用 Termux Agent 配置",
                    onClick = { openAgentPawSettings(context) },
                    startAction = { SettingIcon(painterResource(R.drawable.ic_agentpaw_settings), contentDescription = "AgentPaw 设置") }
                )
            }),
        SearchableSetting(sec_ai, context.getString(R.string.agent_chat_params), context.getString(R.string.agent_chat_params_desc),
            keywords = listOf("上下文", "context", "压缩", "compress", "token", "参数"),
            render = {
                ArrowPreference(
                    title = context.getString(R.string.agent_chat_params),
                    summary = context.getString(R.string.agent_chat_params_desc),
                    onClick = { openAiTermuxModelGen(context) },
                    startAction = { SettingIcon(Icons.Rounded.Settings, contentDescription = context.getString(R.string.agent_chat_params)) }
                )
            }),
        SearchableSetting(sec_ai, context.getString(R.string.agent_memory), context.getString(R.string.agent_memory_desc),
            keywords = listOf("记忆", "memory", "md", "偏好", "长期"),
            render = {
                ArrowPreference(
                    title = context.getString(R.string.agent_memory),
                    summary = context.getString(R.string.agent_memory_desc),
                    onClick = { openAiTermuxMemoryHistory(context) },
                    startAction = { SettingIcon(Icons.Rounded.Psychology, contentDescription = context.getString(R.string.agent_memory)) }
                )
            }),
        SearchableSetting(sec_ai, context.getString(R.string.developer_settings_title), context.getString(R.string.developer_settings_nav_desc),
            keywords = listOf("开发者", "developer", "调试", "debug", "无限制", "unrestricted", "系统提示", "system prompt", "自定义技能", "custom skill"),
            render = {
                ArrowPreference(
                    title = context.getString(R.string.developer_settings_title),
                    summary = context.getString(R.string.developer_settings_nav_desc),
                    onClick = { openAiTermuxDeveloper(context) },
                    startAction = { SettingIcon(Icons.Rounded.Build, contentDescription = context.getString(R.string.developer_settings_title)) }
                )
            }),

        // ===== Security =====
        SearchableSetting(sec_security, context.getString(R.string.protection_level_title), "",
            keywords = listOf("保护", "protection", "vortex", "guard", "安全等级"),
            render = {
                val protectionItems = RiskConfirmManager.ProtectionLevel.entries.map { level ->
                    DropdownItem(text = level.displayName, summary = level.description)
                }
                WindowSpinnerPreference(
                    title = context.getString(R.string.protection_level_title),
                    summary = protectionLevel.description,
                    items = protectionItems,
                    selectedIndex = protectionLevelIndex,
                    onSelectedIndexChange = { idx ->
                        val newLevel = RiskConfirmManager.ProtectionLevel.entries[idx]
                        if (newLevel == RiskConfirmManager.ProtectionLevel.OFF || newLevel == RiskConfirmManager.ProtectionLevel.WARN_ONLY) {
                            RiskConfirmManager.showDisableWarning(context, newLevel)
                        } else {
                            protectionLevelIndex = idx; protectionLevel = newLevel
                            RiskConfirmManager.setProtectionLevel(context, newLevel); riskConfirmEnabled = true
                        }
                    },
                    startAction = { SettingIcon(Icons.Rounded.Shield, contentDescription = context.getString(R.string.protection_level_title)) }
                )
            }),

        // SettingsGroupCard 里的条目（动态展开）
    ) + buildList {
        addAll(dataSettings.map { it.toSearchable(sec_backup) })
        addAll(systemSettings.map { it.toSearchable(sec_system) })
        // 「配置集成工具」入口随开关显隐，搜索结果里同步跟随，避免搜到点进去是空页
        if (anyIntegratedToolEnabled) {
            add(
                SearchableSetting(
                    sec_tools,
                    context.getString(R.string.integrated_tools_config_entry),
                    context.getString(R.string.integrated_tools_config_entry_summary),
                    keywords = listOf("工具配置", "tool config", "集成工具", "integrated tools", "配置"),
                    render = {
                        ArrowPreference(
                            title = context.getString(R.string.integrated_tools_config_entry),
                            summary = context.getString(R.string.integrated_tools_config_entry_summary),
                            onClick = { openIntegratedToolsConfig(context) },
                            startAction = {
                                SettingIcon(Icons.Rounded.Extension, contentDescription = context.getString(R.string.integrated_tools_config_entry))
                            }
                        )
                    }
                )
            )
        }
    }



    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { Box(modifier = Modifier.fillMaxSize().padding(bottom = navBarBottomPadding), contentAlignment = Alignment.BottomCenter) { SnackbarHost(state = snackbarHostState) } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = LocalTopBarClearance.current,
                bottom = navBarBottomPadding + 16.dp
            )
        ) {
            // ---------- 搜索栏（永远在最顶部）----------
            item(key = "search_bar") {
                SearchBar(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = if (searchExpanded) 8.dp else 0.dp),
                    inputField = {
                        InputField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onSearch = { },
                            expanded = searchExpanded,
                            onExpandedChange = {
                                searchExpanded = it
                                if (!it) searchQuery = ""
                            },
                            label = "搜索设置项"
                        )
                    },
                    expanded = searchExpanded,
                    onExpandedChange = {
                        searchExpanded = it
                        if (!it) searchQuery = ""
                    },
                    outsideEndAction = {
                        if (searchExpanded) {
                            Text(
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .clickable(interactionSource = null, indication = null) {
                                        searchExpanded = false
                                        searchQuery = ""
                                    },
                                text = stringResource(R.string.cancel),
                                color = MiuixTheme.colorScheme.primary
                            )
                        }
                    }
                ) { }
            }

            if (searchQuery.isBlank()) {
            // ---------- GitHub 账户 ----------
            item(key = "github_account") { GitHubAccountCard() }

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
                        MaterialYouSwitch(
                            context = context,
                            checked = materialYouEnabled,
                            onCheckedChange = { AppThemePrefs.setMaterialYouEnabled(context, it) }
                        )
                                OverlayDropdownPreference(
                            title = context.getString(R.string.pref_launch_page_title),
                            summary = context.getString(
                                if (autoStartConsoleEnabled) R.string.pref_launch_page_locked_summary
                                else R.string.pref_launch_page_summary
                            ),
                            items = listOf(
                                context.getString(R.string.pref_launch_page_overview),
                                context.getString(R.string.pref_launch_page_terminal)
                            ),
                            // 开着自动控制台时强制显示总览，磁盘上的用户选择保持不动。
                            selectedIndex = if (autoStartConsoleEnabled) 0 else launchPageSelectedIndex,
                            enabled = !autoStartConsoleEnabled,
                            onSelectedIndexChange = { idx ->
                                LaunchPrefs.setLaunchPage(
                                    context,
                                    if (idx == 1) LaunchPrefs.LaunchPage.TERMINAL else LaunchPrefs.LaunchPage.OVERVIEW
                                )
                            },
                            startAction = {
                                SettingIcon(Icons.Rounded.Home, contentDescription = context.getString(R.string.pref_launch_page_title))
                            }
                        )
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
                                SettingIcon(Icons.Rounded.Language, contentDescription = context.getString(R.string.language))
                            }
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
                                    1 -> "classic"
                                    2 -> "liquid_glass"
                                    else -> "glass"
                                }
                                prefs.edit().putString("navigation_bar_style", style).apply()
                                showNavRestartPrompt = true
                            },
                            startAction = {
                                SettingIcon(Icons.Rounded.Navigation, contentDescription = context.getString(R.string.navigation_bar_style))
                            }
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
                                SettingIcon(Icons.Rounded.SwapHoriz, contentDescription = context.getString(R.string.horizontal_tip_layout))
                            }
                        )
                    }
                }
            }

            // ---------- Package Manager ----------
            item(key = "section_pkg_mgmt") { SmallTitle(text = context.getString(R.string.pkg_mgmt_category)) }
            item(key = "card_pkg_mgmt") {
                                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column {
                        OverlayDropdownPreference(
                            title = context.getString(R.string.pkg_view_mode),
                            summary = context.getString(R.string.pkg_view_mode_desc),
                            items = listOf(
                                context.getString(R.string.pkg_view_mode_category),
                                context.getString(R.string.pkg_view_mode_list)
                            ),
                            selectedIndex = pkgViewModeIndex,
                            onSelectedIndexChange = { idx ->
                                pkgViewModeIndex = idx
                                prefs.edit().putInt("KEY_PKG_VIEW_MODE", idx).apply()
                            },
                            startAction = {
                                SettingIcon(Icons.Rounded.Folder, contentDescription = context.getString(R.string.pkg_view_mode))
                            }
                        )
                        ArrowPreference(
                            title = context.getString(R.string.pkg_edit_sources),
                            summary = context.getString(R.string.pkg_edit_sources_desc),
                            onClick = { openSoftwareSourceSettings(context) },
                            startAction = {
                                SettingIcon(Icons.Rounded.Dns, contentDescription = context.getString(R.string.pkg_edit_sources))
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
                                SettingIcon(Icons.Rounded.DesktopWindows, contentDescription = context.getString(R.string.vnc))
                            }
                        )
                        remoteSettings.firstOrNull()?.let { item ->                            ArrowPreference(
                                title = item.title,
                                summary = item.description,
                                onClick = item.action,
                                startAction = {
                                SettingIcon(item.icon, contentDescription = item.title, badge = item.badgeIcon)
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
            item(key = "card_terminal_modules") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column {
                        ArrowPreference(
                            title = context.getString(R.string.console_settings),
                            summary = context.getString(R.string.console_settings_desc),
                            onClick = { openConsoleSettings(context) },
                            startAction = { SettingIcon(Icons.Rounded.Tune) }
                        )
                        ArrowPreference(
                            title = context.getString(R.string.key_settings),
                            summary = context.getString(R.string.key_settings_desc),
                            onClick = { openKeySettings(context) },
                            startAction = { SettingIcon(Icons.Rounded.Keyboard) }
                        )
                        ArrowPreference(
                            title = context.getString(R.string.console_log),
                            summary = context.getString(R.string.console_log_desc),
                            onClick = { openConsoleLog(context) },
                            startAction = { SettingIcon(Icons.Rounded.BugReport) }
                        )
                        ArrowPreference(
                            title = context.getString(R.string.console_personalization),
                            summary = context.getString(R.string.console_personalization_desc),
                            onClick = { openConsolePersonalization(context) },
                            startAction = { SettingIcon(Icons.Rounded.Palette) }
                        )
                    }
                }
            }




// ---------- Integrated Tools ----------
            item(key = "section_tools") { SmallTitle(text = context.getString(R.string.integrated_tools_category)) }
            item(key = "card_integrated_tools") {
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
                            icon = Icons.Rounded.Terminal,
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
                            icon = Icons.AutoMirrored.Rounded.Launch,
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
                            icon = Icons.Rounded.Tune,
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
                            icon = Icons.Rounded.Star,
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

                        // Styling always available regardless of runtime core
                        IntegratedToolSwitch(
                            title = context.getString(R.string.termux_styling_tool),
                            summary = if (stylingStandaloneInstalled) replacedSummary
                                      else context.getString(R.string.termux_styling_tool_summary),
                            icon = Icons.Rounded.Palette,
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

                        // 五个开关任一为开才出现；全关时整行消失，配置页进不去
                        if (anyIntegratedToolEnabled) {
                            ArrowPreference(
                                title = context.getString(R.string.integrated_tools_config_entry),
                                summary = context.getString(R.string.integrated_tools_config_entry_summary),
                                onClick = { openIntegratedToolsConfig(context) },
                                startAction = {
                                    SettingIcon(Icons.Rounded.Extension, contentDescription = context.getString(R.string.integrated_tools_config_entry))
                                }
                            )
                        }
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
                                SettingIcon(Icons.Rounded.Lightbulb, contentDescription = "Termux Agent")
                            }
                        )
                        if (aiTermuxEnabled) {                            val whitelistCount = autoExecConfig.autoExecSkills.size
                            ArrowPreference(
                                title = context.getString(R.string.model_gen_title),
                                summary = context.getString(R.string.model_gen_nav_desc),
                                onClick = { openAiTermuxModelGen(context) },
                                startAction = {
                                    SettingIcon(Icons.Rounded.Tune, contentDescription = context.getString(R.string.model_gen_title))
                                }
                            )
                            ArrowPreference(
                                title = context.getString(R.string.memory_history_title),
                                summary = context.getString(R.string.memory_history_nav_desc),
                                onClick = { openAiTermuxMemoryHistory(context) },
                                startAction = {
                                    SettingIcon(Icons.Rounded.History, contentDescription = context.getString(R.string.memory_history_title))
                                }
                            )
                            ArrowPreference(
                                title = context.getString(R.string.developer_settings_title),
                                summary = context.getString(R.string.developer_settings_nav_desc),
                                onClick = { openAiTermuxDeveloper(context) },
                                startAction = {
                                    SettingIcon(Icons.Rounded.Build, contentDescription = context.getString(R.string.developer_settings_title))
                                }
                            )
                            ArrowPreference(
                                title = "AgentPaw 设置",
                                summary = "手机操控 Agent，LLM 沿用 Termux Agent 配置",
                                onClick = { openAgentPawSettings(context) },
                                startAction = {
                                    SettingIcon(painterResource(R.drawable.ic_agentpaw_settings), contentDescription = "AgentPaw 设置")
                                }
                            )
                        }
                    }
                }
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
                                SettingIcon(Icons.Rounded.Shield, contentDescription = context.getString(R.string.protection_level_title))
                            }
                        )

                        val agentJudgeEnabled = protectionLevel != RiskConfirmManager.ProtectionLevel.OFF
                        val hasAgentCfg = remember {
                            val cfg = AiTermuxPrefs.getConfig(context).providerConfig
                            val hasApi = cfg.apiKey.isNotBlank() || cfg.provider == "local"
                            val localReady = cfg.provider != "local" || AiLocalModel.isLocalModelReady()
                            hasApi && localReady
                        }
                        var agentScriptJudge by remember {
                            mutableStateOf(
                                context.getSharedPreferences("termux_preferences", android.content.Context.MODE_PRIVATE)
                                    .getBoolean("agent_script_judge", false)
                            )
                        }
                        SwitchPreference(
                            title = "脚本运行前 Agent 参与判定",
                            summary = when {
                                !agentJudgeEnabled -> "需开启VorteX Guard Engine后可用"
                                !hasAgentCfg -> "需先在 Termux Agent 中配置模型"
                                agentScriptJudge -> "已启用：执行脚本时 Agent 先判定，超时退回本地检测"
                                else -> "关闭"
                            },
                            checked = agentScriptJudge && agentJudgeEnabled && hasAgentCfg,
                            onCheckedChange = {
                                val newValue = it && agentJudgeEnabled && hasAgentCfg
                                agentScriptJudge = newValue
                                context.getSharedPreferences("termux_preferences", android.content.Context.MODE_PRIVATE)
                                    .edit().putBoolean("agent_script_judge", newValue).apply()
                            },
                            enabled = agentJudgeEnabled && hasAgentCfg,
                            startAction = {
                                SettingIcon(Icons.Rounded.SmartToy, contentDescription = "Agent 脚本判定")
                            }
                        )
                        // ---------- Agent 判定历史 ----------
                        var agentHistory by remember {
                            mutableStateOf(com.termux.app.compose.AgentScriptJudge.getHistory(context))
                        }
                        var showAgentHistory by remember { mutableStateOf(false) }
                        ArrowPreference(
                            title = "Agent 判定历史",
                            summary = if (agentHistory.isEmpty()) {
                                "暂无记录"
                            } else {
                                "共 ${agentHistory.size} 条（仅记录 Agent 实际判定的脚本）"
                            },
                            onClick = { showAgentHistory = true },
                            startAction = {
                                SettingIcon(Icons.Rounded.Restore, contentDescription = "Agent 判定历史")
                            }
                        )
                        // ---------- Agent 判定历史 dialog ----------
                        OverlayDialog(
                            title = "Agent 判定历史",
                            summary = if (agentHistory.isEmpty()) {
                                "暂无 Agent 参与判定的记录"
                            } else {
                                "共 ${agentHistory.size} 条，仅展示最近判定"
                            },
                            show = showAgentHistory,
                            onDismissRequest = { showAgentHistory = false },
                            content = {
                                if (agentHistory.isEmpty()) {
                                    Text(
                                        text = "暂无记录",
                                        modifier = Modifier.padding(vertical = 16.dp),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 380.dp)
                                    ) {
                                        items(agentHistory) { entry ->
                                            AgentHistoryItem(entry)
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    TextButton(
                                        text = "清空历史",
                                        onClick = {
                                            com.termux.app.compose.AgentScriptJudge.clearHistory(context)
                                            agentHistory = emptyList()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // ---------- 重置运行环境 ----------
            item(key = "card_reset_runtime_env") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column {
                        ArrowPreference(
                            title = context.getString(R.string.reset_runtime_env_title),
                            summary = context.getString(R.string.reset_runtime_env_summary),
                            onClick = { showResetEnvConfirm = true },
                            startAction = {
                                SettingIcon(
                                    Icons.Rounded.Warning,
                                    contentDescription = context.getString(R.string.reset_runtime_env_title)
                                )
                            }
                        )

                        // ---------- 二次确认弹窗 ----------
                        OverlayDialog(
                            title = context.getString(R.string.reset_runtime_env_warning_title),
                            summary = context.getString(R.string.reset_runtime_env_warning_body),
                            show = showResetEnvConfirm,
                            onDismissRequest = { showResetEnvConfirm = false },
                            content = {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    TextButton(
                                        text = context.getString(R.string.reset_runtime_env_cancel),
                                        onClick = { showResetEnvConfirm = false },
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        text = context.getString(R.string.reset_runtime_env_confirm),
                                        onClick = {
                                            showResetEnvConfirm = false
                                            resetEnvFailMode = null
                                            resetEnvErrorMsg = ""
                                            RuntimeEnvironmentResetter.reset(
                                                context,
                                                object : RuntimeEnvironmentResetter.Listener {
                                                    override fun onStepUpdate(step: RuntimeEnvironmentResetter.Step, msg: String) {
                                                        resetEnvStep = step; resetEnvMessage = msg
                                                    }
                                                    override fun onSuccess() {
                                                        resetEnvFailMode = null; showResetEnvProgress = true
                                                    }
                                                    override fun onFailBeforePurge(msg: String) {
                                                        resetEnvFailMode = 0; resetEnvErrorMsg = msg; showResetEnvProgress = true
                                                    }
                                                    override fun onFailAfterPurge(msg: String) {
                                                        resetEnvFailMode = 1; resetEnvErrorMsg = msg; showResetEnvProgress = true
                                                    }
                                                }
                                            )
                                            showResetEnvProgress = true
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        )

                        // ---------- 进度 + 结果弹窗 ----------
                        OverlayDialog(
                            title = when (resetEnvFailMode) {
                                null -> if (resetEnvStep == RuntimeEnvironmentResetter.Step.DONE)
                                    context.getString(R.string.reset_runtime_env_success_title)
                                    else context.getString(R.string.reset_runtime_env_title)
                                0 -> context.getString(R.string.reset_runtime_env_title) + " — 下载/校验失败"
                                1 -> context.getString(R.string.reset_runtime_env_title) + " — 环境已受损"
                                else -> context.getString(R.string.reset_runtime_env_title)
                            },
                            summary = when (resetEnvFailMode) {
                                null -> if (resetEnvStep == RuntimeEnvironmentResetter.Step.DONE)
                                    "prefix 与 home 已恢复到全新安装状态"
                                    else resetEnvMessage
                                0 -> context.getString(R.string.reset_runtime_env_fail_before_purge, resetEnvErrorMsg)
                                1 -> context.getString(R.string.reset_runtime_env_fail_after_purge, resetEnvErrorMsg)
                                else -> resetEnvMessage
                            },
                            show = showResetEnvProgress,
                            onDismissRequest = { if (resetEnvFailMode != 0) showResetEnvProgress = false },
                            content = {
                                when {
                                    resetEnvFailMode == null && resetEnvStep == RuntimeEnvironmentResetter.Step.DONE -> {
                                        TextButton(
                                            text = context.getString(R.string.reset_runtime_env_done_button),
                                            onClick = { showResetEnvProgress = false },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    resetEnvFailMode == 0 -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            TextButton(
                                                text = context.getString(R.string.reset_runtime_env_cancel),
                                                onClick = { showResetEnvProgress = false },
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(
                                                text = context.getString(R.string.reset_runtime_env_retry),
                                                onClick = {
                                                    showResetEnvProgress = false
                                                    resetEnvFailMode = null; resetEnvErrorMsg = ""
                                                    RuntimeEnvironmentResetter.reset(
                                                        context,
                                                        object : RuntimeEnvironmentResetter.Listener {
                                                            override fun onStepUpdate(step: RuntimeEnvironmentResetter.Step, msg: String) {
                                                                resetEnvStep = step; resetEnvMessage = msg
                                                            }
                                                            override fun onSuccess() {
                                                                resetEnvFailMode = null; showResetEnvProgress = true
                                                            }
                                                            override fun onFailBeforePurge(msg: String) {
                                                                resetEnvFailMode = 0; resetEnvErrorMsg = msg; showResetEnvProgress = true
                                                            }
                                                            override fun onFailAfterPurge(msg: String) {
                                                                resetEnvFailMode = 1; resetEnvErrorMsg = msg; showResetEnvProgress = true
                                                            }
                                                        }
                                                    )
                                                    showResetEnvProgress = true
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    resetEnvFailMode == 1 -> {
                                        TextButton(
                                            text = context.getString(R.string.ok),
                                            onClick = { showResetEnvProgress = false },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    else -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.padding(end = 16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Text(text = resetEnvMessage)
                                        }
                                    }
                                }
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
            } else {
                // ---------- 搜索结果 ----------
                val q = searchQuery.trim()
                val matched = searchableItems.filter { item ->
                    item.title.contains(q, ignoreCase = true) ||
                    item.summary.contains(q, ignoreCase = true) ||
                    item.section.contains(q, ignoreCase = true) ||
                    item.keywords.any { it.contains(q, ignoreCase = true) }
                }
                if (matched.isEmpty()) {
                    item(key = "search_empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "没有找到匹配的设置项",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                } else {
                    items(matched, key = { "search_${it.section}_${it.title}" }) { entry ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            ) {
                                Column {
                                    entry.render()
                                }
                            }
                        }
                    }
                }
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
                    1 -> "classic"
                    2 -> "liquid_glass"
                    else -> "glass"
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

    }
}

@Composable
private fun AgentHistoryItem(entry: com.termux.app.compose.AgentScriptJudge.JudgeHistoryEntry) {
    val timeStr = remember(entry.timestamp) {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(entry.timestamp))
    }
    val isDanger = entry.verdict == "dangerous"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isDanger) "危险" else "安全",
                color = if (isDanger) Color(0xFFE53935) else Color(0xFF43A047),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = timeStr,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.weight(1f))
            if (entry.provider.isNotBlank()) {
                Text(
                    text = entry.provider,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        Text(
            text = entry.scriptPath,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (entry.reason.isNotBlank()) {
            Text(
                text = entry.reason,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
internal fun SettingIcon(icon: ImageVector, contentDescription: String? = null, badge: ImageVector? = null) {
                                Box(
        modifier = Modifier.size(40.dp)
    ) {
                                Box(
            modifier = Modifier
                .matchParentSize()
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
        if (badge != null) {
            // 主图标回答「是什么」，徽标回答「这一类动作是什么性质」，两者职责分开才不用为每个
            // 「XX 设置」再造一个新图标。徽标挂在外层 Box：圆角底色带 clip，挂进去会被裁掉右下角。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = rememberVectorPainter(badge),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
internal fun SettingIcon(painter: Painter, contentDescription: String? = null) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface
        )
    }
}

/** Material You 动态取色开关；外观卡片与搜索结果共用同一份渲染。 */
@Composable
private fun MaterialYouSwitch(
    context: Context,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SwitchPreference(
        title = context.getString(R.string.pref_material_you_title),
        summary = context.getString(R.string.pref_material_you_summary),
        checked = checked,
        enabled = ApiCompat.isFeatureUsable(context, ApiCompat.Feature.MIUIX_DYNAMIC_COLOR),
        onCheckedChange = onCheckedChange,
        startAction = {
            SettingIcon(Icons.Rounded.Palette, contentDescription = context.getString(R.string.pref_material_you_title))
        }
    )
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
                                SettingIcon(item.icon, contentDescription = item.title, badge = item.badgeIcon)
                        }
                    )
                } else {
                                ArrowPreference(
                        title = item.title,
                        summary = item.description,
                        onClick = item.action,
                        startAction = {
                                SettingIcon(item.icon, contentDescription = item.title, badge = item.badgeIcon)
                        }
                    )
                }
                if (index < items.lastIndex) {                }
            }
        }
    }
}

@Composable
private fun IntegratedToolSwitch(
    title: String,
    summary: String,
    icon: ImageVector,
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
                                SettingIcon(icon, contentDescription = title)
            }
        )
    }
}


/** Agent 在线模型配置弹窗：直接编辑当前生效的主配置 */
@Composable
internal fun AgentOnlineConfigDialog(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cfg = AiTermuxPrefs.getConfig(context).providerConfig
    var baseUrl by remember(show) { mutableStateOf(cfg.apiBaseUrl) }
    var apiKey by remember(show) { mutableStateOf(cfg.apiKey) }
    var model by remember(show) { mutableStateOf(cfg.model) }
    var temperature by remember(show) { mutableStateOf(cfg.temperature) }

    OverlayDialog(
        title = stringResource(R.string.agent_online_config),
        summary = stringResource(R.string.agent_online_config_desc),
        show = show,
        onDismissRequest = onDismiss,
        content = {
            Box(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.label_base_url),
                        useLabelAsPlaceholder = true
                    )
                    TextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.label_api_key),
                        useLabelAsPlaceholder = true
                    )
                    TextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.label_model),
                        useLabelAsPlaceholder = true
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(R.string.save),
                    onClick = {
                        val current = AiTermuxPrefs.getConfig(context)
                        AiTermuxPrefs.saveConfig(
                            context,
                            current.copy(
                                providerConfig = current.providerConfig.copy(
                                    apiKey = apiKey.trim(),
                                    apiBaseUrl = baseUrl.trim(),
                                    model = model.trim(),
                                    temperature = temperature
                                )
                            )
                        )
                        android.widget.Toast.makeText(context, R.string.agent_online_config_saved, android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

/** 模型配置档：列出已保存的 profile，可启用 / 删除 / 新建 */
@Composable
internal fun AgentProfileDialog(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var profiles by remember(show) { mutableStateOf(AiTermuxPrefs.getLlmProfiles(context)) }
    var activeId by remember(show) { mutableStateOf(AiTermuxPrefs.getActiveLlmProfileId(context)) }
    var editing by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var draftKey by remember { mutableStateOf("") }
    var draftUrl by remember { mutableStateOf("") }
    var draftModel by remember { mutableStateOf("") }

    OverlayDialog(
        title = stringResource(R.string.agent_profiles),
        summary = stringResource(R.string.agent_profiles_desc),
        show = show,
        onDismissRequest = onDismiss,
        content = {
            Box(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!editing) {
                        if (profiles.isEmpty()) {
                            Text(
                                text = stringResource(R.string.empty),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                        profiles.forEach { profile ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = profile.name, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                                    Text(
                                        text = "${profile.model} · ${profile.apiBaseUrl}",
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                if (profile.id == activeId) {
                                    Text(
                                        text = stringResource(R.string.profile_active_badge),
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                } else {
                                    TextButton(
                                        text = stringResource(R.string.profile_set_active),
                                        onClick = {
                                            AiTermuxPrefs.applyLlmProfile(context, profile)
                                            AiTermuxPrefs.setActiveLlmProfileId(context, profile.id)
                                            activeId = profile.id
                                            android.widget.Toast.makeText(context, R.string.agent_online_config_saved, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                                TextButton(
                                    text = stringResource(R.string.delete),
                                    onClick = {
                                        AiTermuxPrefs.deleteLlmProfile(context, profile.id)
                                        profiles = AiTermuxPrefs.getLlmProfiles(context)
                                        activeId = AiTermuxPrefs.getActiveLlmProfileId(context)
                                    }
                                )
                            }
                        }
                    } else {
                        TextField(
                            value = draftName,
                            onValueChange = { draftName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.profile_name),
                            useLabelAsPlaceholder = true
                        )
                        TextField(
                            value = draftUrl,
                            onValueChange = { draftUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.label_base_url),
                            useLabelAsPlaceholder = true
                        )
                        TextField(
                            value = draftKey,
                            onValueChange = { draftKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.label_api_key),
                            useLabelAsPlaceholder = true
                        )
                        TextField(
                            value = draftModel,
                            onValueChange = { draftModel = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.label_model),
                            useLabelAsPlaceholder = true
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { if (editing) editing = false else onDismiss() },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = if (editing) stringResource(R.string.save) else stringResource(R.string.profile_new),
                    onClick = {
                        if (!editing) {
                            val current = AiTermuxPrefs.getConfig(context).providerConfig
                            draftName = ""
                            draftKey = current.apiKey
                            draftUrl = current.apiBaseUrl
                            draftModel = current.model
                            editing = true
                        } else if (draftName.isNotBlank()) {
                            val profile = LlmProfile(
                                name = draftName.trim(),
                                provider = "custom",
                                apiKey = draftKey.trim(),
                                apiBaseUrl = draftUrl.trim(),
                                model = draftModel.trim()
                            )
                            AiTermuxPrefs.upsertLlmProfile(context, profile)
                            AiTermuxPrefs.applyLlmProfile(context, profile)
                            AiTermuxPrefs.setActiveLlmProfileId(context, profile.id)
                            profiles = AiTermuxPrefs.getLlmProfiles(context)
                            activeId = profile.id
                            editing = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

/** 对话参数：上下文条数、压缩阈值、保留条数、单轮 maxTokens */
@Composable
internal fun AgentChatParamsDialog(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var ctx by remember(show) { mutableStateOf(AiTermuxPrefs.getContextMessages(context)) }
    var threshold by remember(show) { mutableStateOf(AiTermuxPrefs.getCompressThreshold(context)) }
    var keepRecent by remember(show) { mutableStateOf(AiTermuxPrefs.getCompressKeepRecent(context)) }
    var maxTokens by remember(show) { mutableStateOf(AiTermuxPrefs.getMaxTokens(context)) }
    var maxRounds by remember(show) { mutableStateOf(AiTermuxPrefs.getMaxRounds(context)) }

    OverlayDialog(
        title = stringResource(R.string.agent_chat_params),
        summary = stringResource(R.string.agent_chat_params_desc),
        show = show,
        onDismissRequest = onDismiss,
        content = {
            Box(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        label = stringResource(R.string.label_context_messages),
                        value = ctx,
                        onValueChange = { ctx = it },
                        range = 4..100
                    )
                    NumberField(
                        label = stringResource(R.string.label_compress_threshold),
                        value = threshold,
                        onValueChange = { threshold = it },
                        range = 10..200
                    )
                    NumberField(
                        label = stringResource(R.string.label_compress_keep_recent),
                        value = keepRecent,
                        onValueChange = { keepRecent = it },
                        range = 2..100
                    )
                    NumberField(
                        label = stringResource(R.string.label_max_tokens),
                        value = maxTokens,
                        onValueChange = { maxTokens = it },
                        range = 1024..65536
                    )
                    NumberField(
                        label = stringResource(R.string.label_max_rounds),
                        value = maxRounds,
                        onValueChange = { maxRounds = it },
                        range = 5..200
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(R.string.save),
                    onClick = {
                        AiTermuxPrefs.setContextMessages(context, ctx)
                        AiTermuxPrefs.setCompressThreshold(context, threshold)
                        AiTermuxPrefs.setCompressKeepRecent(context, keepRecent)
                        AiTermuxPrefs.setMaxTokens(context, maxTokens)
                        AiTermuxPrefs.setMaxRounds(context, maxRounds)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange
) {
    Column {
        Text(text = label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
        TextField(
            value = value.toString(),
            onValueChange = { raw ->
                val parsed = raw.filter { it.isDigit() }.toIntOrNull() ?: return@TextField
                onValueChange(parsed.coerceIn(range.first, range.last))
            },
            modifier = Modifier.fillMaxWidth(),
            useLabelAsPlaceholder = true
        )
    }
}

/** 长期记忆：查看 / 编辑 / 清空 Agent 的 MEMORY.md */
@Composable
internal fun AgentMemoryDialog(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember(show) { mutableStateOf(AiTermuxPrefs.getMemory(context)) }

    OverlayDialog(
        title = stringResource(R.string.agent_memory),
        summary = stringResource(R.string.agent_memory_desc),
        show = show,
        onDismissRequest = onDismiss,
        content = {
            Box(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Column {
                    if (text.isBlank()) {
                        Text(
                            text = stringResource(R.string.agent_memory_empty),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                        label = stringResource(R.string.agent_memory),
                        useLabelAsPlaceholder = true,
                        maxLines = Int.MAX_VALUE,
                        minLines = 6
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.clear),
                    onClick = {
                        AiTermuxPrefs.setMemory(context, "")
                        text = ""
                        android.widget.Toast.makeText(context, R.string.agent_memory_cleared, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(R.string.save),
                    onClick = {
                        AiTermuxPrefs.setMemory(context, text)
                        android.widget.Toast.makeText(context, R.string.agent_memory_saved, android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}
