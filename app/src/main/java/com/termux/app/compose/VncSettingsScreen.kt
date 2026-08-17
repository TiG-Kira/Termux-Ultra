package com.termux.app.compose

import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.gaurav.avnc.util.deleteTrustedCertificates
import com.gaurav.avnc.util.forgetKnownHosts
import com.termux.R
import com.termux.app.vnc.VncConnectionManager
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class VncSettingsPage {
    MAIN,
    VIEWER,
    INPUT,
    SERVER
}

private fun getPageTitle(context: Context, page: VncSettingsPage): String {
    return when (page) {
        VncSettingsPage.MAIN -> context.getString(R.string.vnc_settings_title)
        VncSettingsPage.VIEWER -> context.getString(R.string.pref_viewer)
        VncSettingsPage.INPUT -> context.getString(R.string.pref_input)
        VncSettingsPage.SERVER -> context.getString(R.string.pref_servers)
    }
}

@Composable
fun VncSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentPage by remember { mutableStateOf(VncSettingsPage.MAIN) }
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
                                    VncSettingsPage.MAIN -> onBack()
                                    VncSettingsPage.VIEWER -> { currentPage = VncSettingsPage.MAIN }
                                    VncSettingsPage.INPUT -> { currentPage = VncSettingsPage.MAIN }
                                    VncSettingsPage.SERVER -> { currentPage = VncSettingsPage.MAIN }
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
                VncSettingsPage.MAIN -> {
                    MainVncSettingsPage(
                        onNavigate = { currentPage = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
                VncSettingsPage.VIEWER -> {
                    ViewerSettingsPage(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
                VncSettingsPage.INPUT -> {
                    InputSettingsPage(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
                VncSettingsPage.SERVER -> {
                    ServerSettingsPage(
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
private fun MainVncSettingsPage(
    onNavigate: (VncSettingsPage) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 92.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.vnc_settings_title)) }
        item {
            SettingsCard {
                ArrowPreference(
                    title = stringResource(R.string.pref_viewer),
                    summary = stringResource(R.string.pref_viewer_summary),
                    onClick = { onNavigate(VncSettingsPage.VIEWER) },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_video)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                ArrowPreference(
                    title = stringResource(R.string.pref_input),
                    summary = stringResource(R.string.pref_input_summary),
                    onClick = { onNavigate(VncSettingsPage.INPUT) },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_keyboard_vnc)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                ArrowPreference(
                    title = stringResource(R.string.pref_servers),
                    summary = stringResource(R.string.pref_server_summary),
                    onClick = { onNavigate(VncSettingsPage.SERVER) },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_server)
                    }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ViewerSettingsPage(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    var viewerOrientation by remember { mutableStateOf(prefs.getString("viewer_orientation", "auto") ?: "auto") }
    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", true)) }
    var fullscreenDisplay by remember { mutableStateOf(prefs.getBoolean("fullscreen_display", false)) }
    var drawBehindCutout by remember { mutableStateOf(prefs.getBoolean("viewer_draw_behind_cutout", false)) }
    var pipEnabled by remember { mutableStateOf(prefs.getBoolean("pip_enabled", false)) }
    var pauseFbUpdates by remember { mutableStateOf(prefs.getBoolean("pause_fb_updates_in_background", false)) }
    var zoomMin by remember { mutableStateOf(prefs.getInt("zoom_min", 50)) }
    var zoomMax by remember { mutableStateOf(prefs.getInt("zoom_max", 500)) }
    var perOrientationZoom by remember { mutableStateOf(prefs.getBoolean("per_orientation_zoom", true)) }
    var toolbarAlignment by remember { mutableStateOf(prefs.getString("toolbar_alignment", "start") ?: "start") }
    var toolbarOpenWithSwipe by remember { mutableStateOf(prefs.getBoolean("toolbar_open_with_swipe", true)) }
    var toolbarOpenWithButton by remember { mutableStateOf(prefs.getBoolean("toolbar_open_with_button", false)) }
    var toolbarShowGestureStyleToggle by remember { mutableStateOf(prefs.getBoolean("toolbar_show_gesture_style_toggle", true)) }

    val hasPiPSupport = Build.VERSION.SDK_INT >= 26 &&
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    val orientationEntries = remember {
        listOf(
            context.getString(R.string.pref_orientation_option_auto),
            context.getString(R.string.pref_orientation_option_portrait),
            context.getString(R.string.pref_orientation_option_landscape)
        )
    }
    val orientationValues = listOf("auto", "portrait", "landscape")
    val orientationIndex = orientationValues.indexOf(viewerOrientation).coerceAtLeast(0)

    val toolbarAlignmentEntries = remember {
        listOf(
            context.getString(R.string.pref_toolbar_alignment_option_start),
            context.getString(R.string.pref_toolbar_alignment_option_end)
        )
    }
    val toolbarAlignmentValues = listOf("start", "end")
    val toolbarAlignmentIndex = toolbarAlignmentValues.indexOf(toolbarAlignment).coerceAtLeast(0)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 92.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.pref_viewer)) }
        item {
            SettingsCard {
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_orientation),
                    summary = "选择屏幕显示方向",
                    items = orientationEntries,
                    selectedIndex = orientationIndex,
                    onSelectedIndexChange = { idx ->
                        viewerOrientation = orientationValues[idx]
                        prefs.edit().putString("viewer_orientation", orientationValues[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_screen_rotation)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_keep_screen_on),
                    summary = null,
                    checked = keepScreenOn,
                    onCheckedChange = {
                        keepScreenOn = it
                        prefs.edit().putBoolean("keep_screen_on", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_wake_on_lan)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_fullscreen),
                    summary = null,
                    checked = fullscreenDisplay,
                    onCheckedChange = {
                        fullscreenDisplay = it
                        prefs.edit().putBoolean("fullscreen_display", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_fullscreen)
                    }
                )
                if (fullscreenDisplay) {
                    HorizontalDivider(
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                        modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                    )
                    SwitchPreference(
                        title = stringResource(R.string.pref_display_cutout),
                        summary = null,
                        checked = drawBehindCutout,
                        onCheckedChange = {
                            drawBehindCutout = it
                            prefs.edit().putBoolean("viewer_draw_behind_cutout", it).apply()
                        },
                        startAction = {
                            SettingsIconBox(R.drawable.ic_cut)
                        }
                    )
                }
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_enable_pip),
                    summary = if (hasPiPSupport) null else stringResource(R.string.msg_pip_not_supported),
                    checked = pipEnabled,
                    enabled = hasPiPSupport,
                    onCheckedChange = {
                        pipEnabled = it
                        prefs.edit().putBoolean("pip_enabled", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_video)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = "后台暂停画面更新",
                    summary = null,
                    checked = pauseFbUpdates,
                    onCheckedChange = {
                        pauseFbUpdates = it
                        prefs.edit().putBoolean("pause_fb_updates_in_background", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_video_off)
                    }
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.pref_zoom)) }
        item {
            SettingsCard {
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_zoom_min),
                    summary = "调整最小缩放比例",
                    items = (10..100 step 10).map { "$it%" },
                    selectedIndex = ((zoomMin - 10) / 10).coerceIn(0, 9),
                    onSelectedIndexChange = { idx ->
                        val value = 10 + idx * 10
                        zoomMin = value
                        prefs.edit().putInt("zoom_min", value).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_zoom_in)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_zoom_max),
                    summary = "调整最大缩放比例",
                    items = (100..1000 step 100).map { "$it%" },
                    selectedIndex = ((zoomMax - 100) / 100).coerceIn(0, 9),
                    onSelectedIndexChange = { idx ->
                        val value = 100 + idx * 100
                        zoomMax = value
                        prefs.edit().putInt("zoom_max", value).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_zoom_options)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_per_orientation_zoom),
                    summary = null,
                    checked = perOrientationZoom,
                    onCheckedChange = {
                        perOrientationZoom = it
                        prefs.edit().putBoolean("per_orientation_zoom", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_screen_rotation)
                    }
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.pref_toolbar)) }
        item {
            SettingsCard {
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_toolbar_alignment),
                    summary = "设置工具栏位置",
                    items = toolbarAlignmentEntries,
                    selectedIndex = toolbarAlignmentIndex,
                    onSelectedIndexChange = { idx ->
                        toolbarAlignment = toolbarAlignmentValues[idx]
                        prefs.edit().putString("toolbar_alignment", toolbarAlignmentValues[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_toolbar)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_toolbar_open_with_swipe),
                    summary = null,
                    checked = toolbarOpenWithSwipe,
                    onCheckedChange = {
                        toolbarOpenWithSwipe = it
                        prefs.edit().putBoolean("toolbar_open_with_swipe", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_gesture)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_toolbar_open_with_button),
                    summary = null,
                    checked = toolbarOpenWithButton,
                    onCheckedChange = {
                        toolbarOpenWithButton = it
                        prefs.edit().putBoolean("toolbar_open_with_button", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_tap)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_show_gesture_style_toggle),
                    summary = null,
                    checked = toolbarShowGestureStyleToggle,
                    onCheckedChange = {
                        toolbarShowGestureStyleToggle = it
                        prefs.edit().putBoolean("toolbar_show_gesture_style_toggle", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_gesture)
                    }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun InputSettingsPage(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    var gestureStyle by remember { mutableStateOf(prefs.getString("gesture_style", "touchscreen") ?: "touchscreen") }
    var gestureTap2 by remember { mutableStateOf(prefs.getString("gesture_tap2", "open-keyboard") ?: "open-keyboard") }
    var gestureTap3 by remember { mutableStateOf(prefs.getString("gesture_tap3", "none") ?: "none") }
    var gestureDoubleTap by remember { mutableStateOf(prefs.getString("gesture_double_tap", "double-click") ?: "double-click") }
    var gestureLongPress by remember { mutableStateOf(prefs.getString("gesture_long_press", "right-click") ?: "right-click") }
    var gestureSwipe1 by remember { mutableStateOf(prefs.getString("gesture_swipe1", "pan") ?: "pan") }
    var gestureSwipe2 by remember { mutableStateOf(prefs.getString("gesture_swipe2", "pan") ?: "pan") }
    var gestureSwipe3 by remember { mutableStateOf(prefs.getString("gesture_swipe3", "pan") ?: "pan") }
    var gestureDoubleTapSwipe by remember { mutableStateOf(prefs.getString("gesture_double_tap_swipe", "remote-drag") ?: "remote-drag") }
    var gestureLongPressSwipe by remember { mutableStateOf(prefs.getString("gesture_long_press_swipe", "none") ?: "none") }
    var swipeSensitivity by remember { mutableStateOf(prefs.getInt("gesture_swipe_sensitivity", 10)) }
    var invertVerticalScrolling by remember { mutableStateOf(prefs.getBoolean("invert_vertical_scrolling", false)) }

    var mousePassthrough by remember { mutableStateOf(prefs.getBoolean("mouse_passthrough", true)) }
    var capturePointer by remember { mutableStateOf(prefs.getBoolean("capture_pointer", false)) }
    var hideLocalCursor by remember { mutableStateOf(prefs.getBoolean("hide_local_cursor", false)) }
    var hideRemoteCursor by remember { mutableStateOf(prefs.getBoolean("hide_remote_cursor", false)) }
    var mouseBack by remember { mutableStateOf(prefs.getString("mouse_back", "right-click") ?: "right-click") }

    var vkOpenWithKeyboard by remember { mutableStateOf(prefs.getBoolean("vk_open_with_keyboard", false)) }
    var vkUseSuperWithSingleTap by remember { mutableStateOf(prefs.getBoolean("vk_use_super_with_single_tap", false)) }
    var vkRowCount by remember { mutableStateOf(prefs.getString("vk_row_count", "2") ?: "2") }

    var kmRightAltToSuper by remember { mutableStateOf(prefs.getBoolean("km_right_alt_to_super", false)) }
    var kmLanguageSwitchToSuper by remember { mutableStateOf(prefs.getBoolean("km_language_switch_to_super", false)) }
    var kmBackToEscape by remember { mutableStateOf(prefs.getBoolean("km_back_to_escape", false)) }

    val canChangePtrIcon = Build.VERSION.SDK_INT >= 24
    val capturePointerSupported = Build.VERSION.SDK_INT >= 26

    val gestureStyleEntries = remember {
        listOf(
            context.getString(R.string.pref_gesture_style_touchscreen),
            context.getString(R.string.pref_gesture_style_touchpad)
        )
    }
    val gestureStyleValues = listOf("touchscreen", "touchpad")
    val gestureStyleIndex = gestureStyleValues.indexOf(gestureStyle).coerceAtLeast(0)

    val tap2Entries = remember {
        listOf(
            context.getString(R.string.pref_gesture_action_none),
            context.getString(R.string.pref_gesture_action_right_click),
            context.getString(R.string.pref_gesture_action_open_keyboard)
        )
    }
    val tap2Values = listOf("none", "right-click", "open-keyboard")
    val tap2Index = tap2Values.indexOf(gestureTap2).coerceAtLeast(0)

    val tap3Entries = remember {
        listOf(
            context.getString(R.string.pref_gesture_action_none),
            context.getString(R.string.pref_gesture_action_right_click),
            context.getString(R.string.pref_gesture_action_middle_click)
        )
    }
    val tap3Values = listOf("none", "right-click", "middle-click")
    val tap3Index = tap3Values.indexOf(gestureTap3).coerceAtLeast(0)

    val doubleTapEntries = remember {
        listOf(
            context.getString(R.string.pref_gesture_action_none),
            context.getString(R.string.pref_gesture_action_double_click),
            context.getString(R.string.pref_gesture_action_middle_click),
            context.getString(R.string.pref_gesture_action_right_click)
        )
    }
    val doubleTapValues = listOf("none", "double-click", "middle-click", "right-click")
    val doubleTapIndex = doubleTapValues.indexOf(gestureDoubleTap).coerceAtLeast(0)

    val longPressEntries = remember {
        listOf(
            context.getString(R.string.pref_gesture_action_none),
            context.getString(R.string.pref_gesture_action_double_click),
            context.getString(R.string.pref_gesture_action_middle_click),
            context.getString(R.string.pref_gesture_action_right_click),
            context.getString(R.string.pref_gesture_action_left_press)
        )
    }
    val longPressValues = listOf("none", "double-click", "middle-click", "right-click", "left-press")
    val longPressIndex = longPressValues.indexOf(gestureLongPress).coerceAtLeast(0)

    val swipeEntries = remember {
        listOf(
            context.getString(R.string.pref_gesture_action_none),
            context.getString(R.string.pref_gesture_action_pan),
            context.getString(R.string.pref_gesture_action_remote_scroll),
            context.getString(R.string.pref_gesture_action_remote_drag)
        )
    }
    val swipeValues = listOf("none", "pan", "remote-scroll", "remote-drag")

    val swipe1Index = swipeValues.indexOf(gestureSwipe1).coerceAtLeast(0)
    val swipe2Entries = remember {
        listOf(
            context.getString(R.string.pref_gesture_action_none),
            context.getString(R.string.pref_gesture_action_pan),
            context.getString(R.string.pref_gesture_action_remote_scroll)
        )
    }
    val swipe2Values = listOf("none", "pan", "remote-scroll")
    val swipe2Index = swipe2Values.indexOf(gestureSwipe2).coerceAtLeast(0)

    val swipe3Index = swipeValues.indexOf(gestureSwipe3).coerceAtLeast(0)

    val doubleTapSwipeEntries = remember {
        listOf(
            context.getString(R.string.pref_gesture_action_none),
            context.getString(R.string.pref_gesture_action_remote_drag),
            context.getString(R.string.pref_gesture_action_remote_drag_middle),
            context.getString(R.string.pref_gesture_action_pan),
            context.getString(R.string.pref_gesture_action_remote_scroll)
        )
    }
    val doubleTapSwipeValues = listOf("none", "remote-drag", "remote-drag-middle", "pan", "remote-scroll")
    val doubleTapSwipeIndex = doubleTapSwipeValues.indexOf(gestureDoubleTapSwipe).coerceAtLeast(0)

    val longPressSwipeEntries = doubleTapSwipeEntries
    val longPressSwipeValues = doubleTapSwipeValues
    val longPressSwipeIndex = longPressSwipeValues.indexOf(gestureLongPressSwipe).coerceAtLeast(0)

    val longPressSwipeEnabled = gestureLongPress != "left-press"
    val swipe1Enabled = gestureStyle != "touchpad"

    val invertScrollVisible = swipeValues.contains(gestureSwipe1) || swipeValues.contains(gestureSwipe2) || swipeValues.contains(gestureSwipe3)

    val mouseBackEntries = remember {
        listOf(
            context.getString(R.string.pref_mouse_back_action_default),
            context.getString(R.string.pref_gesture_action_middle_click),
            context.getString(R.string.pref_gesture_action_right_click),
            context.getString(R.string.pref_gesture_action_remote_back_press)
        )
    }
    val mouseBackValues = listOf("default", "middle-click", "right-click", "remote-back-press")
    val mouseBackIndex = mouseBackValues.indexOf(mouseBack).coerceAtLeast(0)

    val vkRowCountEntries = listOf("1", "2", "3")
    val vkRowCountIndex = vkRowCountEntries.indexOf(vkRowCount).coerceAtLeast(0)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 92.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.pref_gesture)) }
        item {
            SettingsCard {
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_gesture_style),
                    summary = "选择触控交互模式",
                    items = gestureStyleEntries,
                    selectedIndex = gestureStyleIndex,
                    onSelectedIndexChange = { idx ->
                        gestureStyle = gestureStyleValues[idx]
                        prefs.edit().putString("gesture_style", gestureStyleValues[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_gesture)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_double_tap),
                    summary = "设置双击操作",
                    items = doubleTapEntries,
                    selectedIndex = doubleTapIndex,
                    onSelectedIndexChange = { idx ->
                        gestureDoubleTap = doubleTapValues[idx]
                        prefs.edit().putString("gesture_double_tap", doubleTapValues[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_tap)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_long_press),
                    summary = "设置长按操作",
                    items = longPressEntries,
                    selectedIndex = longPressIndex,
                    onSelectedIndexChange = { idx ->
                        gestureLongPress = longPressValues[idx]
                        prefs.edit().putString("gesture_long_press", longPressValues[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_gesture)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_two_finger_tap),
                    summary = "设置双指轻点操作",
                    items = tap2Entries,
                    selectedIndex = tap2Index,
                    onSelectedIndexChange = { idx ->
                        gestureTap2 = tap2Values[idx]
                        prefs.edit().putString("gesture_tap2", tap2Values[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_tap)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_three_finger_tap),
                    summary = "设置三指轻点操作",
                    items = tap3Entries,
                    selectedIndex = tap3Index,
                    onSelectedIndexChange = { idx ->
                        gestureTap3 = tap3Values[idx]
                        prefs.edit().putString("gesture_tap3", tap3Values[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_tap)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_swipe1),
                    summary = "设置单指滑动操作",
                    items = swipeEntries,
                    selectedIndex = swipe1Index,
                    enabled = swipe1Enabled,
                    onSelectedIndexChange = { idx ->
                        gestureSwipe1 = swipeValues[idx]
                        prefs.edit().putString("gesture_swipe1", swipeValues[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_gesture)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_swipe2),
                    summary = "设置双指滑动操作",
                    items = swipe2Entries,
                    selectedIndex = swipe2Index,
                    onSelectedIndexChange = { idx ->
                        gestureSwipe2 = swipe2Values[idx]
                        prefs.edit().putString("gesture_swipe2", swipe2Values[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_gesture)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_swipe3),
                    summary = "设置三指滑动操作",
                    items = swipeEntries,
                    selectedIndex = swipe3Index,
                    onSelectedIndexChange = { idx ->
                        gestureSwipe3 = swipeValues[idx]
                        prefs.edit().putString("gesture_swipe3", swipeValues[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_gesture)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_double_tap_swipe),
                    summary = "设置双击后滑动操作",
                    items = doubleTapSwipeEntries,
                    selectedIndex = doubleTapSwipeIndex,
                    onSelectedIndexChange = { idx ->
                        gestureDoubleTapSwipe = doubleTapSwipeValues[idx]
                        prefs.edit().putString("gesture_double_tap_swipe", doubleTapSwipeValues[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_gesture)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_long_press_swipe),
                    summary = if (longPressSwipeEnabled) "设置长按后滑动操作" else "长按设为鼠标按下时不可用",
                    items = longPressSwipeEntries,
                    selectedIndex = longPressSwipeIndex,
                    enabled = longPressSwipeEnabled,
                    onSelectedIndexChange = { idx ->
                        gestureLongPressSwipe = longPressSwipeValues[idx]
                        prefs.edit().putString("gesture_long_press_swipe", longPressSwipeValues[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_gesture)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_swipe_sensitivity),
                    summary = "调整滑动灵敏度",
                    items = (5..15).map { it.toString() },
                    selectedIndex = (swipeSensitivity - 5).coerceIn(0, 10),
                    onSelectedIndexChange = { idx ->
                        val value = 5 + idx
                        swipeSensitivity = value
                        prefs.edit().putInt("gesture_swipe_sensitivity", value).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_gesture)
                    }
                )
                if (invertScrollVisible) {
                    HorizontalDivider(
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                        modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                    )
                    SwitchPreference(
                        title = stringResource(R.string.pref_invert_vertical_scrolling),
                        summary = null,
                        checked = invertVerticalScrolling,
                        onCheckedChange = {
                            invertVerticalScrolling = it
                            prefs.edit().putBoolean("invert_vertical_scrolling", it).apply()
                        },
                        startAction = {
                            SettingsIconBox(R.drawable.ic_swap)
                        }
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.pref_mouse)) }
        item {
            SettingsCard {
                SwitchPreference(
                    title = stringResource(R.string.pref_mouse_passthrough),
                    summary = if (mousePassthrough) stringResource(R.string.pref_mouse_passthrough_summary_on) else stringResource(R.string.pref_mouse_passthrough_summary_off),
                    checked = mousePassthrough,
                    onCheckedChange = {
                        mousePassthrough = it
                        prefs.edit().putBoolean("mouse_passthrough", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_mouse)
                    }
                )
                if (mousePassthrough) {
                    HorizontalDivider(
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                        modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                    )
                    SwitchPreference(
                        title = stringResource(R.string.pref_capture_pointer),
                        summary = if (capturePointerSupported) null else stringResource(R.string.msg_pip_not_supported),
                        checked = capturePointer,
                        enabled = capturePointerSupported,
                        onCheckedChange = {
                            capturePointer = it
                            prefs.edit().putBoolean("capture_pointer", it).apply()
                        },
                        startAction = {
                            SettingsIconBox(R.drawable.ic_mouse)
                        }
                    )
                }
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_hide_local_cursor),
                    summary = if (canChangePtrIcon) null else stringResource(R.string.msg_ptr_hiding_not_supported),
                    checked = hideLocalCursor,
                    enabled = canChangePtrIcon,
                    onCheckedChange = {
                        hideLocalCursor = it
                        prefs.edit().putBoolean("hide_local_cursor", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_visibility)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.title_hide_remote_cursor),
                    summary = null,
                    checked = hideRemoteCursor,
                    onCheckedChange = {
                        hideRemoteCursor = it
                        prefs.edit().putBoolean("hide_remote_cursor", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_visibility)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_mouse_back),
                    summary = "设置鼠标返回键功能",
                    items = mouseBackEntries,
                    selectedIndex = mouseBackIndex,
                    onSelectedIndexChange = { idx ->
                        mouseBack = mouseBackValues[idx]
                        prefs.edit().putString("mouse_back", mouseBackValues[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_arrow_back)
                    }
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.pref_vk)) }
        item {
            SettingsCard {
                SwitchPreference(
                    title = stringResource(R.string.pref_vk_open_with_keyboard),
                    summary = null,
                    checked = vkOpenWithKeyboard,
                    onCheckedChange = {
                        vkOpenWithKeyboard = it
                        prefs.edit().putBoolean("vk_open_with_keyboard", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_keyboard_mini)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_vk_use_super_with_single_tap),
                    summary = null,
                    checked = vkUseSuperWithSingleTap,
                    onCheckedChange = {
                        vkUseSuperWithSingleTap = it
                        prefs.edit().putBoolean("vk_use_super_with_single_tap", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_super_key)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_vk_row_count),
                    summary = "设置虚拟键盘行数",
                    items = vkRowCountEntries,
                    selectedIndex = vkRowCountIndex,
                    onSelectedIndexChange = { idx ->
                        vkRowCount = vkRowCountEntries[idx]
                        prefs.edit().putString("vk_row_count", vkRowCountEntries[idx]).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_keyboard_mini)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                ArrowPreference(
                    title = stringResource(R.string.pref_customize_virtual_keys),
                    summary = null,
                    onClick = { },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_edit)
                    }
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.pref_km)) }
        item {
            SettingsCard {
                SwitchPreference(
                    title = stringResource(R.string.pref_km_right_alt_to_super),
                    summary = null,
                    checked = kmRightAltToSuper,
                    onCheckedChange = {
                        kmRightAltToSuper = it
                        prefs.edit().putBoolean("km_right_alt_to_super", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_super_key)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_km_language_switch_to_super),
                    summary = null,
                    checked = kmLanguageSwitchToSuper,
                    onCheckedChange = {
                        kmLanguageSwitchToSuper = it
                        prefs.edit().putBoolean("km_language_switch_to_super", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_super_key)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_km_back_to_escape),
                    summary = stringResource(R.string.pref_km_back_to_escape_summary),
                    checked = kmBackToEscape,
                    onCheckedChange = {
                        kmBackToEscape = it
                        prefs.edit().putBoolean("km_back_to_escape", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_arrow_back)
                    }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ServerSettingsPage(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    var clipboardSync by remember { mutableStateOf(prefs.getBoolean("clipboard_sync", true)) }
    var autoReconnect by remember { mutableStateOf(prefs.getBoolean("auto_reconnect", false)) }
    var showForgetDialog by remember { mutableStateOf(false) }
    var showForgetConfirmDialog by remember { mutableStateOf(false) }
    val vncConnectionManager = remember { VncConnectionManager(context) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 92.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.pref_servers)) }
        item {
            SettingsCard {
                SwitchPreference(
                    title = stringResource(R.string.pref_clipboard_sync),
                    summary = null,
                    checked = clipboardSync,
                    onCheckedChange = {
                        clipboardSync = it
                        prefs.edit().putBoolean("clipboard_sync", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_paste)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_auto_reconnect),
                    summary = null,
                    checked = autoReconnect,
                    onCheckedChange = {
                        autoReconnect = it
                        prefs.edit().putBoolean("auto_reconnect", it).apply()
                    },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_refresh)
                    }
                )
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                )
                ArrowPreference(
                    title = stringResource(R.string.pref_forget_known_hosts),
                    summary = null,
                    onClick = { showForgetDialog = true },
                    startAction = {
                        SettingsIconBox(R.drawable.ic_delete)
                    }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    OverlayDialog(
        title = stringResource(R.string.pref_forget_known_hosts),
        summary = stringResource(R.string.pref_forget_known_hosts_question),
        show = showForgetDialog,
        onDismissRequest = { showForgetDialog = false },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.title_forget),
                    onClick = {
                        val success = forgetKnownHosts(context) && deleteTrustedCertificates(context)
                        if (success) {
                            vncConnectionManager.clearAllConnections()
                        }
                        showForgetDialog = false
                        if (success) {
                            showForgetConfirmDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                TextButton(
                    text = stringResource(R.string.title_cancel),
                    onClick = { showForgetDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )

    OverlayDialog(
        title = stringResource(R.string.msg_done),
        show = showForgetConfirmDialog,
        onDismissRequest = { showForgetConfirmDialog = false },
        content = {
            TextButton(
                text = stringResource(R.string.ok),
                onClick = { showForgetConfirmDialog = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
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