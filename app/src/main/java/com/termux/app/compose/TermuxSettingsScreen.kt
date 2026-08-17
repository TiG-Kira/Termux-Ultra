package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.models.UserAction
import com.termux.shared.R as SharedR
import com.termux.shared.activities.ReportActivity
import com.termux.shared.file.FileUtils
import com.termux.shared.models.ReportInfo
import com.termux.shared.settings.preferences.TermuxAPIAppSharedPreferences
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.settings.preferences.TermuxFloatAppSharedPreferences
import com.termux.shared.settings.preferences.TermuxTaskerAppSharedPreferences
import com.termux.shared.settings.preferences.TermuxWidgetAppSharedPreferences
import com.termux.shared.logger.Logger
import com.termux.shared.termux.AndroidUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class TermuxSettingsPage {
    MAIN,
    TERMINAL,
    TERMINAL_VIEW,
    TERMINAL_IO,
    DEBUGGING,
    PLUGIN_API,
    PLUGIN_FLOAT,
    PLUGIN_TASKER,
    PLUGIN_WIDGET
}

private fun getPageTitle(context: Context, page: TermuxSettingsPage): String {
    return when (page) {
        TermuxSettingsPage.MAIN -> context.getString(R.string.title_activity_termux_settings)
        TermuxSettingsPage.TERMINAL -> context.getString(R.string.termux_preferences_title)
        TermuxSettingsPage.TERMINAL_VIEW -> context.getString(R.string.termux_terminal_view_preferences_title)
        TermuxSettingsPage.TERMINAL_IO -> context.getString(R.string.termux_terminal_io_preferences_title)
        TermuxSettingsPage.DEBUGGING -> context.getString(R.string.termux_debugging_preferences_title)
        TermuxSettingsPage.PLUGIN_API -> context.getString(R.string.termux_api_preferences_title)
        TermuxSettingsPage.PLUGIN_FLOAT -> context.getString(R.string.termux_float_preferences_title)
        TermuxSettingsPage.PLUGIN_TASKER -> context.getString(R.string.termux_tasker_preferences_title)
        TermuxSettingsPage.PLUGIN_WIDGET -> context.getString(R.string.termux_widget_preferences_title)
    }
}

@Composable
fun TermuxSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentPage by remember { mutableStateOf(TermuxSettingsPage.MAIN) }
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = getPageTitle(context, currentPage),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable {
                                when (currentPage) {
                                    TermuxSettingsPage.MAIN -> onBack()
                                    TermuxSettingsPage.TERMINAL -> { currentPage = TermuxSettingsPage.MAIN }
                                    TermuxSettingsPage.TERMINAL_VIEW -> { currentPage = TermuxSettingsPage.TERMINAL }
                                    TermuxSettingsPage.TERMINAL_IO -> { currentPage = TermuxSettingsPage.TERMINAL }
                                    TermuxSettingsPage.DEBUGGING -> { currentPage = TermuxSettingsPage.TERMINAL }
                                    TermuxSettingsPage.PLUGIN_API -> { currentPage = TermuxSettingsPage.MAIN }
                                    TermuxSettingsPage.PLUGIN_FLOAT -> { currentPage = TermuxSettingsPage.MAIN }
                                    TermuxSettingsPage.PLUGIN_TASKER -> { currentPage = TermuxSettingsPage.MAIN }
                                    TermuxSettingsPage.PLUGIN_WIDGET -> { currentPage = TermuxSettingsPage.MAIN }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = context.getString(R.string.back),
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
                .padding(padding)
        ) {
            when (currentPage) {
                TermuxSettingsPage.MAIN -> {
                    MainTermuxSettingsPage(
                        onNavigate = { currentPage = it },
                        onBack = onBack,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
                TermuxSettingsPage.TERMINAL -> {
                    TerminalSettingsPage(
                        onNavigate = { currentPage = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
                TermuxSettingsPage.TERMINAL_VIEW -> {
                    TerminalViewSettingsPage(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
                TermuxSettingsPage.TERMINAL_IO -> {
                    TerminalIoSettingsPage(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
                TermuxSettingsPage.DEBUGGING -> {
                    DebuggingSettingsPage(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
                TermuxSettingsPage.PLUGIN_API -> {
                    PluginSettingsPage(
                        pluginType = "api",
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
                TermuxSettingsPage.PLUGIN_FLOAT -> {
                    PluginSettingsPage(
                        pluginType = "float",
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
                TermuxSettingsPage.PLUGIN_TASKER -> {
                    PluginSettingsPage(
                        pluginType = "tasker",
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
                TermuxSettingsPage.PLUGIN_WIDGET -> {
                    PluginSettingsPage(
                        pluginType = "widget",
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
            }
        }
    }
}

@Composable
private fun MainTermuxSettingsPage(
    onNavigate: (TermuxSettingsPage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { TermuxAppSharedPreferences.build(context) }

    val pluginItems = remember {
        buildList {
            add(createPluginItem(TermuxSettingsPage.PLUGIN_API, R.drawable.ic_terminal, R.string.termux_api_preferences_title, R.string.termux_api_preferences_summary, context))
            add(createPluginItem(TermuxSettingsPage.PLUGIN_FLOAT, R.drawable.ic_palette, R.string.termux_float_preferences_title, R.string.termux_float_preferences_summary, context))
            add(createPluginItem(TermuxSettingsPage.PLUGIN_TASKER, R.drawable.ic_tools, R.string.termux_tasker_preferences_title, R.string.termux_tasker_preferences_summary, context))
            add(createPluginItem(TermuxSettingsPage.PLUGIN_WIDGET, R.drawable.ic_star, R.string.termux_widget_preferences_title, R.string.termux_widget_preferences_summary, context))
        }
    }
    val visiblePluginItems = pluginItems.filter { it.isVisible }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 92.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.terminal)) }
        item {
            SettingsCard {
                ArrowPreference(
                    title = stringResource(R.string.termux_preferences_title),
                    summary = stringResource(R.string.termux_preferences_summary),
                    onClick = { onNavigate(TermuxSettingsPage.TERMINAL) },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_terminal)
                    }
                )
            }
        }

        if (visiblePluginItems.isNotEmpty()) {
            item { SmallTitle(text = stringResource(R.string.integrated_tools_category)) }
            item {
                SettingsCard {
                    PluginItemsList(
                        items = pluginItems,
                        onNavigate = onNavigate
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.termux_native_about_title)) }
        item {
            SettingsCard {
                ArrowPreference(
                    title = stringResource(R.string.termux_native_about_title),
                    summary = stringResource(R.string.termux_native_about_summary),
                    onClick = {
                        launchAboutReport(context)
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_info)
                    }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun launchAboutReport(context: Context) {
    Thread {
        val title = "About"
        val aboutString = StringBuilder()
        aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, false))
        val termuxPluginAppsInfo = TermuxUtils.getTermuxPluginAppsInfoMarkdownString(context)
        if (termuxPluginAppsInfo != null) {
            aboutString.append("\n\n").append(termuxPluginAppsInfo)
        }
        aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context))
        aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context))

        val userActionName = UserAction.ABOUT.name
        ReportActivity.startReportActivity(
            context,
            ReportInfo(
                userActionName,
                TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME,
                title,
                null,
                aboutString.toString(),
                null,
                false,
                userActionName,
                Environment.getExternalStorageDirectory().toString() + "/" +
                    FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true)
            )
        )
    }.start()
}

private fun createPluginItem(
    page: TermuxSettingsPage,
    iconRes: Int,
    titleRes: Int,
    summaryRes: Int,
    context: Context
): PluginSettingItem {
    val title = context.getString(titleRes)
    val prefs = when (page) {
        TermuxSettingsPage.PLUGIN_API -> TermuxAPIAppSharedPreferences.build(context, false)
        TermuxSettingsPage.PLUGIN_FLOAT -> TermuxFloatAppSharedPreferences.build(context, false)
        TermuxSettingsPage.PLUGIN_TASKER -> TermuxTaskerAppSharedPreferences.build(context, false)
        TermuxSettingsPage.PLUGIN_WIDGET -> TermuxWidgetAppSharedPreferences.build(context, false)
        else -> null
    }
    return PluginSettingItem(
        page = page,
        iconRes = iconRes,
        title = title,
        summary = context.getString(summaryRes),
        isVisible = prefs != null
    )
}

private data class PluginSettingItem(
    val page: TermuxSettingsPage,
    val iconRes: Int,
    val title: String,
    val summary: String,
    val isVisible: Boolean
)

@Composable
private fun PluginItemsList(
    items: List<PluginSettingItem>,
    onNavigate: (TermuxSettingsPage) -> Unit
) {
    val visibleItems = items.filter { it.isVisible }
    visibleItems.forEachIndexed { index, item ->
        ArrowPreference(
            title = item.title,
            summary = item.summary,
            onClick = { onNavigate(item.page) },
            startAction = {
                SettingsIconBox(item.iconRes)
            }
        )
        if (index < visibleItems.size - 1) {
            HorizontalDivider(
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
            )
        }
    }
}

@Composable
private fun TerminalSettingsPage(
    onNavigate: (TermuxSettingsPage) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 92.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.terminal)) }
        item {
            SettingsCard {
                ArrowPreference(
                    title = stringResource(R.string.termux_terminal_io_preferences_title),
                    summary = stringResource(R.string.termux_terminal_io_preferences_summary),
                    onClick = { onNavigate(TermuxSettingsPage.TERMINAL_IO) },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_keyboard)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                ArrowPreference(
                    title = stringResource(R.string.termux_terminal_view_preferences_title),
                    summary = stringResource(R.string.termux_terminal_view_preferences_summary),
                    onClick = { onNavigate(TermuxSettingsPage.TERMINAL_VIEW) },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_screen_rotation)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                ArrowPreference(
                    title = stringResource(R.string.termux_debugging_preferences_title),
                    summary = stringResource(R.string.termux_debugging_preferences_summary),
                    onClick = { onNavigate(TermuxSettingsPage.DEBUGGING) },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_bug)
                    }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TerminalViewSettingsPage(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { TermuxAppSharedPreferences.build(context) }
    var terminalMarginAdjustment by remember { mutableStateOf(prefs?.isTerminalMarginAdjustmentEnabled() ?: false) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 92.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.termux_terminal_view_view_header)) }
        item {
            SettingsCard {
                SwitchPreference(
                    title = stringResource(R.string.termux_terminal_view_terminal_margin_adjustment_title),
                    summary = stringResource(
                        if (terminalMarginAdjustment) R.string.termux_terminal_view_terminal_margin_adjustment_on
                        else R.string.termux_terminal_view_terminal_margin_adjustment_off
                    ),
                    checked = terminalMarginAdjustment,
                    onCheckedChange = {
                        terminalMarginAdjustment = it
                        prefs?.setTerminalMarginAdjustment(it)
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_screen_rotation)
                    }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TerminalIoSettingsPage(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { TermuxAppSharedPreferences.build(context) }
    var softKeyboardEnabled by remember { mutableStateOf(prefs?.isSoftKeyboardEnabled() ?: false) }
    var softKeyboardOnlyIfNoHardware by remember { mutableStateOf(prefs?.isSoftKeyboardEnabledOnlyIfNoHardware() ?: false) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 92.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.termux_keyboard_header)) }
        item {
            SettingsCard {
                SwitchPreference(
                    title = stringResource(R.string.termux_soft_keyboard_enabled_title),
                    summary = stringResource(
                        if (softKeyboardEnabled) R.string.termux_soft_keyboard_enabled_on
                        else R.string.termux_soft_keyboard_enabled_off
                    ),
                    checked = softKeyboardEnabled,
                    onCheckedChange = {
                        softKeyboardEnabled = it
                        prefs?.setSoftKeyboardEnabled(it)
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_keyboard)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.termux_soft_keyboard_enabled_only_if_no_hardware_title),
                    summary = stringResource(
                        if (softKeyboardOnlyIfNoHardware) R.string.termux_soft_keyboard_enabled_only_if_no_hardware_on
                        else R.string.termux_soft_keyboard_enabled_only_if_no_hardware_off
                    ),
                    checked = softKeyboardOnlyIfNoHardware,
                    onCheckedChange = {
                        softKeyboardOnlyIfNoHardware = it
                        prefs?.setSoftKeyboardEnabledOnlyIfNoHardware(it)
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_keyboard)
                    }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DebuggingSettingsPage(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { TermuxAppSharedPreferences.build(context) }
    var keyLoggingEnabled by remember { mutableStateOf(prefs?.isTerminalViewKeyLoggingEnabled() ?: false) }
    var pluginErrorNotifications by remember { mutableStateOf(prefs?.arePluginErrorNotificationsEnabled() ?: true) }
    var crashReportNotifications by remember { mutableStateOf(prefs?.areCrashReportNotificationsEnabled() ?: true) }
    var logLevel by remember { mutableStateOf(prefs?.logLevel ?: Logger.DEFAULT_LOG_LEVEL) }

    val logLevelItems = remember {
        listOf(
            context.getString(SharedR.string.log_level_off),
            context.getString(SharedR.string.log_level_normal),
            context.getString(SharedR.string.log_level_debug),
            context.getString(SharedR.string.log_level_verbose)
        )
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 92.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.termux_logging_header)) }
        item {
            SettingsCard {
                OverlayDropdownPreference(
                    title = stringResource(R.string.termux_log_level_title),
                    summary = logLevelItems[logLevel],
                    items = logLevelItems,
                    selectedIndex = logLevel,
                    onSelectedIndexChange = { idx ->
                        logLevel = idx
                        prefs?.setLogLevel(context, idx)
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_bug)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.termux_terminal_view_key_logging_enabled_title),
                    summary = stringResource(
                        if (keyLoggingEnabled) R.string.termux_terminal_view_key_logging_enabled_on
                        else R.string.termux_terminal_view_key_logging_enabled_off
                    ),
                    checked = keyLoggingEnabled,
                    onCheckedChange = {
                        keyLoggingEnabled = it
                        prefs?.setTerminalViewKeyLoggingEnabled(it)
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_bug)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.termux_plugin_error_notifications_enabled_title),
                    summary = stringResource(
                        if (pluginErrorNotifications) R.string.termux_plugin_error_notifications_enabled_on
                        else R.string.termux_plugin_error_notifications_enabled_off
                    ),
                    checked = pluginErrorNotifications,
                    onCheckedChange = {
                        pluginErrorNotifications = it
                        prefs?.setPluginErrorNotificationsEnabled(it)
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_error)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.termux_crash_report_notifications_enabled_title),
                    summary = stringResource(
                        if (crashReportNotifications) R.string.termux_crash_report_notifications_enabled_on
                        else R.string.termux_crash_report_notifications_enabled_off
                    ),
                    checked = crashReportNotifications,
                    onCheckedChange = {
                        crashReportNotifications = it
                        prefs?.setCrashReportNotificationsEnabled(it)
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_error)
                    }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PluginSettingsPage(
    pluginType: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val (title, summary) = when (pluginType) {
        "api" -> stringResource(R.string.termux_api_preferences_title) to stringResource(R.string.termux_api_preferences_summary)
        "float" -> stringResource(R.string.termux_float_preferences_title) to stringResource(R.string.termux_float_preferences_summary)
        "tasker" -> stringResource(R.string.termux_tasker_preferences_title) to stringResource(R.string.termux_tasker_preferences_summary)
        "widget" -> stringResource(R.string.termux_widget_preferences_title) to stringResource(R.string.termux_widget_preferences_summary)
        else -> "" to ""
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 92.dp)
    ) {
        item { SmallTitle(text = title) }
        item {
            SettingsCard {
                ArrowPreference(
                    title = stringResource(R.string.termux_debugging_preferences_title),
                    summary = stringResource(R.string.termux_debugging_preferences_summary),
                    onClick = { },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_bug)
                    }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SettingsCard(
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun SettingsIconBox(iconRes: Int) {
    Box(
        modifier = Modifier
            .size(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface
        )
    }
}
