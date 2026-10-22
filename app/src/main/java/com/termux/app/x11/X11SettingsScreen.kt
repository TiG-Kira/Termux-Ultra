package com.termux.app.x11

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.ui.graphics.vector.ImageVector
import android.preference.PreferenceManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.app.compose.SettingCard
import com.termux.app.compose.pagePaddingWithoutTop
import com.termux.app.compose.rememberGlassPageBackdrop
import com.termux.app.compose.standaloneContentPadding
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
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
import kotlin.math.roundToInt

/**
 * Termux:X11 设置页宿主（独立 Activity 的内容）。
 *
 * 取代 lorie AAR 的 LoriePreferences（英文 XML 偏好页）作为主设置入口：玻璃顶栏 + SettingCard +
 * miuix 偏好组件，与 GuiSettingsScreen / ConsoleSettingsScreen 同款视觉，文案全部中文并跟随宿主。
 * 仍读写 lorie 的同一份 SharedPreferences（getDefaultSharedPreferences，即 com.termux_preferences），
 * 每次改动后广播 com.termux.x11.ACTION_PREFERENCES_CHANGED，:x11 进程的 MainActivity 即时重载应用。
 *
 * 精简掉集成场景用不上的项：画中画（PIP）、副显示器分开存偏好、通知权限请求、
 * 触控笔/鼠标辅助/手柄等高级指针项；小键盘（ekbar）相关项保留。
 */
@Composable
fun X11SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    // ---------- 显示 ----------
    var resolutionMode by remember { mutableStateOf(prefs.getString("displayResolutionMode", "native") ?: "native") }
    var displayScale by remember { mutableStateOf(prefs.getInt("displayScale", 100)) }
    var resolutionExact by remember { mutableStateOf(prefs.getString("displayResolutionExact", "1280x1024") ?: "1280x1024") }
    var resolutionCustom by remember { mutableStateOf(prefs.getString("displayResolutionCustom", "1280x1024") ?: "1280x1024") }
    var filteringMode by remember { mutableStateOf(prefs.getString("displayFilteringMode", "bilinear") ?: "bilinear") }
    var adjustResolution by remember { mutableStateOf(prefs.getBoolean("adjustResolution", false)) }
    var displayStretch by remember { mutableStateOf(prefs.getBoolean("displayStretch", false)) }
    var reseed by remember { mutableStateOf(prefs.getBoolean("Reseed", false)) }
    var fullscreen by remember { mutableStateOf(prefs.getBoolean("fullscreen", false)) }
    var forceOrientation by remember { mutableStateOf(prefs.getString("forceOrientation", "auto") ?: "auto") }
    var hideCutout by remember { mutableStateOf(prefs.getBoolean("hideCutout", false)) }
    var screenIdleTimeout by remember { mutableStateOf(prefs.getString("screenIdleTimeout", "system") ?: "system") }

    // ---------- 指针 ----------
    var touchMode by remember { mutableStateOf(prefs.getString("touchMode", "1") ?: "1") }
    var scaleTouchpad by remember { mutableStateOf(prefs.getBoolean("scaleTouchpad", true)) }
    var pointerCapture by remember { mutableStateOf(prefs.getBoolean("pointerCapture", false)) }
    var transformCapturedPointer by remember { mutableStateOf(prefs.getString("transformCapturedPointer", "no") ?: "no") }
    var capturedPointerSpeedFactor by remember { mutableStateOf(prefs.getInt("capturedPointerSpeedFactor", 100)) }
    var tapToMove by remember { mutableStateOf(prefs.getBoolean("tapToMove", true)) }
    var zoomFollowsCursor by remember { mutableStateOf(prefs.getBoolean("zoomFollowsCursor", true)) }

    // ---------- 键盘 ----------
    var showAdditionalKbd by remember { mutableStateOf(prefs.getBoolean("showAdditionalKbd", true)) }
    var showIMEWhileExternalConnected by remember { mutableStateOf(prefs.getBoolean("showIMEWhileExternalConnected", true)) }
    var preferScancodes by remember { mutableStateOf(prefs.getBoolean("preferScancodes", false)) }
    var hardwareKbdScancodesWorkaround by remember { mutableStateOf(prefs.getBoolean("hardwareKbdScancodesWorkaround", true)) }
    var dexMetaKeyCapture by remember { mutableStateOf(prefs.getBoolean("dexMetaKeyCapture", false)) }
    var pauseKeyInterceptingWithEsc by remember { mutableStateOf(prefs.getBoolean("pauseKeyInterceptingWithEsc", false)) }
    var filterOutWinkey by remember { mutableStateOf(prefs.getBoolean("filterOutWinkey", false)) }
    var enforceCharBasedInput by remember { mutableStateOf(prefs.getBoolean("enforceCharBasedInput", false)) }
    var enableAccessibilityServiceAutomatically by remember { mutableStateOf(prefs.getBoolean("enableAccessibilityServiceAutomatically", false)) }

    // ---------- 小键盘 ----------
    var adjustHeightForEK by remember { mutableStateOf(prefs.getBoolean("adjustHeightForEK", true)) }
    var useTermuxEKBarBehaviour by remember { mutableStateOf(prefs.getBoolean("useTermuxEKBarBehaviour", true)) }
    var ekbarPosition by remember { mutableStateOf(prefs.getString("ekbarPosition", "bottom") ?: "bottom") }
    var ekbarPositionIgnoreOrientation by remember { mutableStateOf(prefs.getBoolean("ekbarPositionIgnoreOrientation", false)) }
    var opacityEKBar by remember { mutableStateOf(prefs.getInt("opacityEKBar", 100)) }
    var extraKeysConfig by remember { mutableStateOf(prefs.getString("extra_keys_config", "") ?: "") }

    // ---------- 其他 ----------
    var clipboardEnable by remember { mutableStateOf(prefs.getBoolean("clipboardEnable", true)) }
    var swipeUpAction by remember { mutableStateOf(prefs.getString("swipeUpAction", "no action") ?: "no action") }
    var swipeDownAction by remember { mutableStateOf(prefs.getString("swipeDownAction", "toggle additional key bar") ?: "toggle additional key bar") }
    var volumeUpAction by remember { mutableStateOf(prefs.getString("volumeUpAction", "no action") ?: "no action") }
    var volumeDownAction by remember { mutableStateOf(prefs.getString("volumeDownAction", "no action") ?: "no action") }
    var backButtonAction by remember { mutableStateOf(prefs.getString("backButtonAction", "toggle soft keyboard") ?: "toggle soft keyboard") }
    var notificationTapAction by remember { mutableStateOf(prefs.getString("notificationTapAction", "open preferences") ?: "open preferences") }
    var notificationButton0Action by remember { mutableStateOf(prefs.getString("notificationButton0Action", "open preferences") ?: "open preferences") }
    var notificationButton1Action by remember { mutableStateOf(prefs.getString("notificationButton1Action", "exit") ?: "exit") }
    var mediaKeysAction by remember { mutableStateOf(prefs.getString("mediaKeysAction", "no action") ?: "no action") }

    // 文本编辑弹窗（自定义分辨率 / 小键盘配置）
    var editingDialog by remember { mutableStateOf<X11TextPref?>(null) }

    fun commit(key: String, edit: (SharedPreferences.Editor) -> Unit) {
        val editor = prefs.edit()
        edit(editor)
        editor.apply()
        context.notifyX11PrefsChanged()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.x11_settings_title),
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
                item(key = "section_output") { SmallTitle(text = stringResource(R.string.x11_section_output)) }
                item(key = "card_output") {
                    SettingCard {
                        Column {
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_displayResolutionMode),
                                values = RESOLUTION_MODES, labels = RESOLUTION_MODE_LABELS,
                                current = resolutionMode,
                                onSelect = { v ->
                                    resolutionMode = v
                                    commit("displayResolutionMode") { it.putString("displayResolutionMode", v) }
                                },
                                startAction = { X11SettingIcon(Icons.Rounded.Tune) }
                            )
                            if (resolutionMode == "scaled") {
                                X11SliderPref(
                                    title = stringResource(R.string.lorie_pref_displayScale),
                                    value = displayScale, range = 30..300, step = 10,
                                    onChange = { v ->
                                        displayScale = v
                                        commit("displayScale") { it.putInt("displayScale", v) }
                                    }
                                )
                            }
                            if (resolutionMode == "exact") {
                                X11DropdownPref(
                                    title = stringResource(R.string.lorie_pref_displayResolutionExact),
                                    values = RESOLUTIONS, labels = RESOLUTIONS,
                                    current = resolutionExact,
                                    onSelect = { v ->
                                        resolutionExact = v
                                        commit("displayResolutionExact") { it.putString("displayResolutionExact", v) }
                                    },
                                    startAction = { X11SettingIcon(Icons.Rounded.Tune) }
                                )
                            }
                            if (resolutionMode == "custom") {
                                ArrowPreference(
                                    title = stringResource(R.string.lorie_pref_displayResolutionCustom),
                                    summary = resolutionCustom,
                                    onClick = { editingDialog = X11TextPref.CUSTOM_RESOLUTION },
                                    startAction = { X11SettingIcon(Icons.Rounded.Tune) }
                                )
                            }
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_displayFilteringMode),
                                values = FILTERING_MODES, labels = FILTERING_LABELS,
                                current = filteringMode,
                                onSelect = { v ->
                                    filteringMode = v
                                    commit("displayFilteringMode") { it.putString("displayFilteringMode", v) }
                                },
                                startAction = { X11SettingIcon(Icons.Rounded.Tune) }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_adjustResolution),
                                checked = adjustResolution,
                                onCheckedChange = {
                                    adjustResolution = it
                                    commit("adjustResolution") { e -> e.putBoolean("adjustResolution", it) }
                                }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_displayStretch),
                                checked = displayStretch,
                                onCheckedChange = {
                                    displayStretch = it
                                    commit("displayStretch") { e -> e.putBoolean("displayStretch", it) }
                                }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_Reseed),
                                summary = stringResource(R.string.lorie_pref_Reseed_summary),
                                checked = reseed,
                                onCheckedChange = {
                                    reseed = it
                                    commit("Reseed") { e -> e.putBoolean("Reseed", it) }
                                }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_fullscreen),
                                summary = stringResource(R.string.lorie_pref_fullscreen_summary),
                                checked = fullscreen,
                                onCheckedChange = {
                                    fullscreen = it
                                    commit("fullscreen") { e -> e.putBoolean("fullscreen", it) }
                                }
                            )
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_forceOrientation),
                                values = ORIENTATIONS, labels = ORIENTATION_LABELS,
                                current = forceOrientation,
                                onSelect = { v ->
                                    forceOrientation = v
                                    commit("forceOrientation") { it.putString("forceOrientation", v) }
                                },
                                startAction = { X11SettingIcon(Icons.Rounded.ScreenRotation) }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_hideCutout),
                                checked = hideCutout,
                                onCheckedChange = {
                                    hideCutout = it
                                    commit("hideCutout") { e -> e.putBoolean("hideCutout", it) }
                                }
                            )
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_screenIdleTimeout),
                                values = IDLE_TIMEOUTS, labels = IDLE_TIMEOUT_LABELS,
                                current = screenIdleTimeout,
                                onSelect = { v ->
                                    screenIdleTimeout = v
                                    commit("screenIdleTimeout") { it.putString("screenIdleTimeout", v) }
                                },
                                startAction = { X11SettingIcon(Icons.Rounded.Timer) }
                            )
                        }
                    }
                }

                item(key = "section_pointer") { SmallTitle(text = stringResource(R.string.x11_section_pointer)) }
                item(key = "card_pointer") {
                    SettingCard {
                        Column {
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_touchMode),
                                values = TOUCH_MODES, labels = TOUCH_MODE_LABELS,
                                current = touchMode,
                                onSelect = { v ->
                                    touchMode = v
                                    commit("touchMode") { it.putString("touchMode", v) }
                                },
                                startAction = { X11SettingIcon(Icons.Rounded.Mouse) }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_scaleTouchpad),
                                checked = scaleTouchpad,
                                onCheckedChange = {
                                    scaleTouchpad = it
                                    commit("scaleTouchpad") { e -> e.putBoolean("scaleTouchpad", it) }
                                }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_pointerCapture),
                                summary = stringResource(R.string.lorie_pref_pointerCapture_summary),
                                checked = pointerCapture,
                                onCheckedChange = {
                                    pointerCapture = it
                                    commit("pointerCapture") { e -> e.putBoolean("pointerCapture", it) }
                                }
                            )
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_transformCapturedPointer),
                                values = POINTER_TRANSFORMS, labels = POINTER_TRANSFORM_LABELS,
                                current = transformCapturedPointer,
                                onSelect = { v ->
                                    transformCapturedPointer = v
                                    commit("transformCapturedPointer") { it.putString("transformCapturedPointer", v) }
                                },
                                startAction = { X11SettingIcon(Icons.Rounded.Mouse) }
                            )
                            if (transformCapturedPointer != "no") {
                                X11SliderPref(
                                    title = stringResource(R.string.lorie_pref_capturedPointerSpeedFactor),
                                    value = capturedPointerSpeedFactor, range = 1..300, step = 1,
                                    onChange = { v ->
                                        capturedPointerSpeedFactor = v
                                        commit("capturedPointerSpeedFactor") { it.putInt("capturedPointerSpeedFactor", v) }
                                    }
                                )
                            }
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_tapToMove),
                                checked = tapToMove,
                                onCheckedChange = {
                                    tapToMove = it
                                    commit("tapToMove") { e -> e.putBoolean("tapToMove", it) }
                                }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_zoomFollowsCursor),
                                checked = zoomFollowsCursor,
                                onCheckedChange = {
                                    zoomFollowsCursor = it
                                    commit("zoomFollowsCursor") { e -> e.putBoolean("zoomFollowsCursor", it) }
                                }
                            )
                        }
                    }
                }

                item(key = "section_kbd") { SmallTitle(text = stringResource(R.string.x11_section_kbd)) }
                item(key = "card_kbd") {
                    SettingCard {
                        Column {
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_showAdditionalKbd),
                                summary = stringResource(R.string.lorie_pref_showAdditionalKbd_summary),
                                checked = showAdditionalKbd,
                                onCheckedChange = {
                                    showAdditionalKbd = it
                                    commit("showAdditionalKbd") { e -> e.putBoolean("showAdditionalKbd", it) }
                                }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_showIMEWhileExternalConnected),
                                summary = stringResource(R.string.lorie_pref_showIMEWhileExternalConnected_summary),
                                checked = showIMEWhileExternalConnected,
                                onCheckedChange = {
                                    showIMEWhileExternalConnected = it
                                    commit("showIMEWhileExternalConnected") { e -> e.putBoolean("showIMEWhileExternalConnected", it) }
                                }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_preferScancodes),
                                summary = stringResource(R.string.lorie_pref_preferScancodes_summary),
                                checked = preferScancodes,
                                onCheckedChange = {
                                    preferScancodes = it
                                    commit("preferScancodes") { e -> e.putBoolean("preferScancodes", it) }
                                }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_hardwareKbdScancodesWorkaround),
                                summary = stringResource(R.string.lorie_pref_hardwareKbdScancodesWorkaround_summary),
                                checked = hardwareKbdScancodesWorkaround,
                                onCheckedChange = {
                                    hardwareKbdScancodesWorkaround = it
                                    commit("hardwareKbdScancodesWorkaround") { e -> e.putBoolean("hardwareKbdScancodesWorkaround", it) }
                                }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_dexMetaKeyCapture),
                                summary = stringResource(R.string.lorie_pref_dexMetaKeyCapture_summary),
                                checked = dexMetaKeyCapture,
                                onCheckedChange = {
                                    dexMetaKeyCapture = it
                                    commit("dexMetaKeyCapture") { e -> e.putBoolean("dexMetaKeyCapture", it) }
                                }
                            )
                            if (dexMetaKeyCapture) {
                                SwitchPreference(
                                    title = stringResource(R.string.lorie_pref_filterOutWinkey),
                                    summary = stringResource(R.string.lorie_pref_filterOutWinkey_summary),
                                    checked = filterOutWinkey,
                                    onCheckedChange = {
                                        filterOutWinkey = it
                                        commit("filterOutWinkey") { e -> e.putBoolean("filterOutWinkey", it) }
                                    }
                                )
                                SwitchPreference(
                                    title = stringResource(R.string.lorie_pref_pauseKeyInterceptingWithEsc),
                                    checked = pauseKeyInterceptingWithEsc,
                                    onCheckedChange = {
                                        pauseKeyInterceptingWithEsc = it
                                        commit("pauseKeyInterceptingWithEsc") { e -> e.putBoolean("pauseKeyInterceptingWithEsc", it) }
                                    }
                                )
                            }
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_enforceCharBasedInput),
                                summary = stringResource(R.string.lorie_pref_enforceCharBasedInput_summary),
                                checked = enforceCharBasedInput,
                                onCheckedChange = {
                                    enforceCharBasedInput = it
                                    commit("enforceCharBasedInput") { e -> e.putBoolean("enforceCharBasedInput", it) }
                                }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_enableAccessibilityServiceAutomatically),
                                summary = stringResource(R.string.lorie_pref_enableAccessibilityServiceAutomatically_summary),
                                checked = enableAccessibilityServiceAutomatically,
                                onCheckedChange = {
                                    enableAccessibilityServiceAutomatically = it
                                    commit("enableAccessibilityServiceAutomatically") { e -> e.putBoolean("enableAccessibilityServiceAutomatically", it) }
                                }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.lorie_pref_enableAccessibilityService),
                                summary = stringResource(R.string.lorie_pref_enableAccessibilityService_summary),
                                onClick = {
                                    context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            )
                        }
                    }
                }

                item(key = "section_ekbar") { SmallTitle(text = stringResource(R.string.x11_section_ekbar)) }
                item(key = "card_ekbar") {
                    SettingCard {
                        Column {
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_adjustHeightForEK),
                                checked = adjustHeightForEK,
                                onCheckedChange = {
                                    adjustHeightForEK = it
                                    commit("adjustHeightForEK") { e -> e.putBoolean("adjustHeightForEK", it) }
                                }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_useTermuxEKBarBehaviour),
                                summary = stringResource(R.string.lorie_pref_useTermuxEKBarBehaviour_summary),
                                checked = useTermuxEKBarBehaviour,
                                onCheckedChange = {
                                    useTermuxEKBarBehaviour = it
                                    commit("useTermuxEKBarBehaviour") { e -> e.putBoolean("useTermuxEKBarBehaviour", it) }
                                }
                            )
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_ekbarPosition),
                                values = EKBAR_POSITIONS, labels = EKBAR_POSITION_LABELS,
                                current = ekbarPosition,
                                onSelect = { v ->
                                    ekbarPosition = v
                                    commit("ekbarPosition") { it.putString("ekbarPosition", v) }
                                },
                                startAction = { X11SettingIcon(Icons.Rounded.Build) }
                            )
                            SwitchPreference(
                                title = stringResource(R.string.lorie_pref_ekbarPositionIgnoreOrientation),
                                checked = ekbarPositionIgnoreOrientation,
                                onCheckedChange = {
                                    ekbarPositionIgnoreOrientation = it
                                    commit("ekbarPositionIgnoreOrientation") { e -> e.putBoolean("ekbarPositionIgnoreOrientation", it) }
                                }
                            )
                            X11SliderPref(
                                title = stringResource(R.string.lorie_pref_opacityEKBar),
                                value = opacityEKBar, range = 1..100, step = 1,
                                onChange = { v ->
                                    opacityEKBar = v
                                    commit("opacityEKBar") { it.putInt("opacityEKBar", v) }
                                }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.lorie_pref_extra_keys_config),
                                summary = if (extraKeysConfig.isBlank()) null else extraKeysConfig,
                                onClick = { editingDialog = X11TextPref.EXTRA_KEYS_CONFIG }
                            )
                        }
                    }
                }

                item(key = "section_other") { SmallTitle(text = stringResource(R.string.x11_section_other)) }
                item(key = "card_other") {
                    SettingCard {
                        SwitchPreference(
                            title = stringResource(R.string.lorie_pref_clipboardEnable),
                            checked = clipboardEnable,
                            onCheckedChange = {
                                clipboardEnable = it
                                commit("clipboardEnable") { e -> e.putBoolean("clipboardEnable", it) }
                            }
                        )
                    }
                }

                item(key = "section_actions") { SmallTitle(text = stringResource(R.string.lorie_pref_userActions)) }
                item(key = "card_actions") {
                    SettingCard {
                        Column {
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_swipeUpAction),
                                values = BASE_ACTIONS, labels = BASE_ACTION_LABELS,
                                current = swipeUpAction,
                                onSelect = { v ->
                                    swipeUpAction = v
                                    commit("swipeUpAction") { it.putString("swipeUpAction", v) }
                                }
                            )
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_swipeDownAction),
                                values = BASE_ACTIONS, labels = BASE_ACTION_LABELS,
                                current = swipeDownAction,
                                onSelect = { v ->
                                    swipeDownAction = v
                                    commit("swipeDownAction") { it.putString("swipeDownAction", v) }
                                }
                            )
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_volumeUpAction),
                                values = VOLUME_UP_ACTIONS, labels = VOLUME_UP_ACTION_LABELS,
                                current = volumeUpAction,
                                onSelect = { v ->
                                    volumeUpAction = v
                                    commit("volumeUpAction") { it.putString("volumeUpAction", v) }
                                }
                            )
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_volumeDownAction),
                                values = VOLUME_DOWN_ACTIONS, labels = VOLUME_DOWN_ACTION_LABELS,
                                current = volumeDownAction,
                                onSelect = { v ->
                                    volumeDownAction = v
                                    commit("volumeDownAction") { it.putString("volumeDownAction", v) }
                                }
                            )
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_backButtonAction),
                                values = BASE_ACTIONS, labels = BASE_ACTION_LABELS,
                                current = backButtonAction,
                                onSelect = { v ->
                                    backButtonAction = v
                                    commit("backButtonAction") { it.putString("backButtonAction", v) }
                                }
                            )
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_notificationTapAction),
                                values = BASE_ACTIONS, labels = BASE_ACTION_LABELS,
                                current = notificationTapAction,
                                onSelect = { v ->
                                    notificationTapAction = v
                                    commit("notificationTapAction") { it.putString("notificationTapAction", v) }
                                }
                            )
                            X11DropdownPref(
                                title = stringResource(R.string.x11_pref_notificationButton0),
                                values = BASE_ACTIONS, labels = BASE_ACTION_LABELS,
                                current = notificationButton0Action,
                                onSelect = { v ->
                                    notificationButton0Action = v
                                    commit("notificationButton0Action") { it.putString("notificationButton0Action", v) }
                                }
                            )
                            X11DropdownPref(
                                title = stringResource(R.string.x11_pref_notificationButton1),
                                values = BASE_ACTIONS, labels = BASE_ACTION_LABELS,
                                current = notificationButton1Action,
                                onSelect = { v ->
                                    notificationButton1Action = v
                                    commit("notificationButton1Action") { it.putString("notificationButton1Action", v) }
                                }
                            )
                            X11DropdownPref(
                                title = stringResource(R.string.lorie_pref_mediaKeysAction),
                                values = MEDIA_KEY_ACTIONS, labels = MEDIA_KEY_ACTION_LABELS,
                                current = mediaKeysAction,
                                onSelect = { v ->
                                    mediaKeysAction = v
                                    commit("mediaKeysAction") { it.putString("mediaKeysAction", v) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 文本编辑弹窗 ----------
    editingDialog?.let { pref ->
        var input by remember(pref) { mutableStateOf(if (pref == X11TextPref.CUSTOM_RESOLUTION) resolutionCustom else extraKeysConfig) }
        OverlayDialog(
            title = stringResource(if (pref == X11TextPref.CUSTOM_RESOLUTION) R.string.lorie_pref_displayResolutionCustom else R.string.lorie_pref_extra_keys_config),
            show = true,
            onDismissRequest = { editingDialog = null },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        useLabelAsPlaceholder = true
                    )
                    Row(horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { editingDialog = null },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(16.dp))
                        TextButton(
                            text = stringResource(R.string.save),
                            onClick = {
                                if (pref == X11TextPref.CUSTOM_RESOLUTION) {
                                    resolutionCustom = input
                                    commit("displayResolutionCustom") { it.putString("displayResolutionCustom", input) }
                                } else {
                                    extraKeysConfig = input
                                    commit("extra_keys_config") { it.putString("extra_keys_config", input) }
                                }
                                editingDialog = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        )
    }
}

// ---------- 取值集与中文标签 ----------

private val RESOLUTION_MODES = listOf("native", "scaled", "exact", "custom")
private val RESOLUTION_MODE_LABELS = listOf("原生分辨率", "缩放至屏幕", "固定分辨率", "自定义分辨率")
private val RESOLUTIONS = listOf(
    "320x200", "320x240", "352x288", "400x240", "640x350", "640x480", "800x480", "800x600",
    "1024x768", "1280x720", "1280x960", "1280x1024", "1366x768", "1400x1050", "1600x1024",
    "1600x1200", "1680x1050", "1920x1080", "1920x1200", "2048x1536", "2560x1600", "2560x2048",
    "3840x2160", "7680x4320"
)
private val FILTERING_MODES = listOf("bilinear", "nearest")
private val FILTERING_LABELS = listOf("双线性过滤", "最近邻采样")
private val ORIENTATIONS = listOf("auto", "portrait", "landscape", "reverse portrait", "reverse landscape")
private val ORIENTATION_LABELS = listOf("自动", "竖屏", "横屏", "反向竖屏", "反向横屏")
private val IDLE_TIMEOUTS = listOf("never", "1", "5", "10", "20", "60", "system")
private val IDLE_TIMEOUT_LABELS = listOf("常亮（保持屏幕常开）", "1 分钟", "5 分钟", "10 分钟", "20 分钟", "1 小时", "跟随系统")
private val TOUCH_MODES = listOf("1", "2", "3")
private val TOUCH_MODE_LABELS = listOf("触摸板", "模拟触摸屏", "直接触摸")
private val POINTER_TRANSFORMS = listOf("no", "c", "cc", "ud", "at")
private val POINTER_TRANSFORM_LABELS = listOf("不旋转", "顺时针", "逆时针", "上下翻转", "自动（触摸板）")
private val EKBAR_POSITIONS = listOf("top", "bottom", "left", "right")
private val EKBAR_POSITION_LABELS = listOf("顶部", "底部", "左侧", "右侧")

private val BASE_ACTIONS = listOf(
    "no action", "toggle soft keyboard", "toggle additional key bar", "open preferences",
    "release pointer and keyboard capture", "toggle fullscreen", "restart activity", "exit"
)
private val BASE_ACTION_LABELS = listOf(
    "无动作", "切换软键盘", "切换小键盘", "打开设置",
    "释放指针与键盘捕获", "切换全屏", "重启活动", "退出"
)
private val VOLUME_UP_ACTIONS = BASE_ACTIONS + "send volume up"
private val VOLUME_UP_ACTION_LABELS = BASE_ACTION_LABELS + "发送音量+"
private val VOLUME_DOWN_ACTIONS = BASE_ACTIONS + "send volume down"
private val VOLUME_DOWN_ACTION_LABELS = BASE_ACTION_LABELS + "发送音量-"
private val MEDIA_KEY_ACTIONS = BASE_ACTIONS + "send media action"
private val MEDIA_KEY_ACTION_LABELS = BASE_ACTION_LABELS + "发送媒体键"

private enum class X11TextPref { CUSTOM_RESOLUTION, EXTRA_KEYS_CONFIG }

/** 改动落盘后通知 :x11 进程重载（LorieApp.onBroadcastReceive 处理 ACTION_PREFERENCES_CHANGED）。 */
internal fun Context.notifyX11PrefsChanged() {
    sendBroadcast(
        Intent("com.termux.x11.ACTION_PREFERENCES_CHANGED")
            .setPackage(packageName)
            .putExtra("fromBroadcast", true)
    )
}

@Composable
private fun X11DropdownPref(
    title: String,
    values: List<String>,
    labels: List<String>,
    current: String,
    onSelect: (String) -> Unit,
    startAction: (@Composable () -> Unit)? = null
) {
    val index = values.indexOf(current).coerceAtLeast(0)
    OverlayDropdownPreference(
        title = title,
        summary = labels.getOrNull(index),
        items = labels,
        selectedIndex = index,
        onSelectedIndexChange = { idx -> values.getOrNull(idx)?.let(onSelect) },
        startAction = startAction
    )
}

@Composable
private fun X11SliderPref(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text("${value}%", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val snapped = (((raw.roundToInt() - range.first) / step) * step + range.first).coerceIn(range.first, range.last)
                onChange(snapped)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step) - 1
        )
    }
}

@Composable
private fun X11SettingIcon(icon: ImageVector) {
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface
        )
    }
}
