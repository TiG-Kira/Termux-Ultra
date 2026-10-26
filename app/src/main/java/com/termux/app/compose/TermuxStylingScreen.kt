package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.AtomicFile
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.snackbar.Snackbar
import com.awkoo.libterminal.color.TerminalColorScheme
import com.termux.R
import com.termux.app.terminal.shell.TerminalThemes
import com.termux.app.utils.FontDownloader
import com.termux.app.utils.SnackbarHelper
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties
import com.termux.app.compose.pagePaddingWithoutTop
import kotlin.math.roundToInt

private const val DEFAULT_FILENAME = "Default"

// 主题来源：Termux:Styling 预设 / LibTerminal 内置主题 / 自定义
private const val THEME_SOURCE_STYLING = "styling"
private const val THEME_SOURCE_LIBTERMINAL = "libterminal"
private const val THEME_SOURCE_CUSTOM = "custom"

private val DEFAULT_TERMINAL_COLORS = run {
    val scheme = TerminalColorScheme.dark()
    TerminalColors(
        foreground = Color(scheme.foreground),
        background = Color(scheme.background),
        cursor = Color(scheme.cursor),
        colors = (0..15).map { Color(scheme.color(it)) }
    )
}

private data class TerminalColors(
    val foreground: Color,
    val background: Color,
    val cursor: Color,
    val colors: List<Color> // color0-color15
)

/** 由 libterminal 的 TerminalColorScheme 构造预览用的 TerminalColors。 */
private fun terminalColorsFromScheme(scheme: TerminalColorScheme): TerminalColors =
    TerminalColors(
        foreground = Color(scheme.foreground),
        background = Color(scheme.background),
        cursor = Color(scheme.cursor),
        colors = (0..15).map { Color(scheme.color(it)) }
    )

private fun parseHexColor(value: String): Color {
    return try {
        val clean = value.trim().removePrefix("#")
        val fullHex = if (clean.length == 6) "FF$clean" else clean
        Color(android.graphics.Color.parseColor("#$fullHex"))
    } catch (_: Exception) {
        Color.White
    }
}

private fun parseArgbOrNull(value: String?): Int? {
    if (value == null) return null
    return try {
        val clean = value.trim().removePrefix("#")
        when (clean.length) {
            6 -> android.graphics.Color.parseColor("#FF$clean")
            8 -> android.graphics.Color.parseColor("#$clean")
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/** 由背景色亮度推导光标色（与既有逻辑保持一致）。 */
private fun deriveCursor(bg: Int): Int =
    if (android.graphics.Color.luminance(bg) < 0.5f) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

/** 转成 #RRGGBB（丢弃 alpha，终端颜色不透明）。 */
private fun toHex6(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

private fun loadColorScheme(context: Context, fileName: String?): TerminalColors {
    if (fileName == null || fileName == DEFAULT_FILENAME) return DEFAULT_TERMINAL_COLORS
    return try {
        val props = Properties()
        context.assets.open("colors/$fileName").use { props.load(it) }

        val foreground = parseHexColor(props.getProperty("foreground", "#FFFFFF"))
        val backgroundArgb = android.graphics.Color.parseColor(
            props.getProperty("background", "#000000").let {
                if (it.startsWith("#") && it.length == 7) "#FF${it.removePrefix("#")}" else it
            }
        )
        val background = Color(backgroundArgb)
        val cursor = if (props.containsKey("cursor")) {
            parseHexColor(props.getProperty("cursor")!!)
        } else {
            val lum = android.graphics.Color.luminance(backgroundArgb)
            if (lum < 0.5f) Color.White else Color.Black
        }
        val colors = (0..15).map { index ->
            val key = "color$index"
            val value = props.getProperty(key)
            if (value != null) parseHexColor(value)
            else DEFAULT_TERMINAL_COLORS.colors[index]
        }
        TerminalColors(foreground, background, cursor, colors)
    } catch (_: Exception) {
        DEFAULT_TERMINAL_COLORS
    }
}

private fun loadFontTypeface(context: Context, fileName: String?): Typeface? {
    if (fileName == null || fileName == DEFAULT_FILENAME) return null
    return try {
        TerminalFontCatalog.open(context, fileName)?.use { input ->
            val file = File.createTempFile("font_", ".ttf", context.cacheDir)
            file.deleteOnExit()
            file.outputStream().use { input.copyTo(it) }
            Typeface.createFromFile(file)
        }
    } catch (_: Exception) {
        null
    }
}

private fun capitalize(str: String): String {
    var lastWhitespace = true
    val chars = str.toCharArray()
    for (i in chars.indices) {
        if (Character.isLetter(chars[i])) {
            if (lastWhitespace) chars[i] = Character.toUpperCase(chars[i])
            lastWhitespace = false
        } else {
            lastWhitespace = Character.isWhitespace(chars[i])
        }
    }
    return String(chars)
}

private data class StyleItem(val fileName: String) {
    val displayName: String
        get() {
            var name = fileName.replace('-', ' ')
            val dotIndex = name.lastIndexOf('.')
            if (dotIndex != -1) name = name.substring(0, dotIndex)
            return capitalize(name)
        }
}

@Composable
fun TermuxStylingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()

    val colorItems = remember { loadStyleItems(context, "colors", ".properties") }
    val fontItems = remember { (listOf(DEFAULT_FILENAME) + TerminalFontCatalog.allFonts(context)).map(::StyleItem) }

    val prefs = remember { context.getSharedPreferences("termux_styling", Context.MODE_PRIVATE) }

    // 主题来源（默认 styling 以保证向后兼容：已配置的用户行为与旧版完全一致）
    var themeSource by remember { mutableStateOf(prefs.getString("theme_source", THEME_SOURCE_STYLING) ?: THEME_SOURCE_STYLING) }
    // LibTerminal 预设主题名（默认 Default Dark）
    var libTerminalThemeName by remember { mutableStateOf(prefs.getString("libterminal_theme", "Default Dark") ?: "Default Dark") }
    // 自定义主题颜色（默认取 LibTerminal 深色方案的背景/前景）
    var customBg by remember { mutableStateOf(prefs.getString("custom_bg", null)?.let { parseArgbOrNull(it) } ?: TerminalColorScheme.dark().background) }
    var customFg by remember { mutableStateOf(prefs.getString("custom_fg", null)?.let { parseArgbOrNull(it) } ?: TerminalColorScheme.dark().foreground) }

    var showBgPicker by remember { mutableStateOf(false) }
    var showFgPicker by remember { mutableStateOf(false) }
    var downloadingFont by remember { mutableStateOf<String?>(null) }
    var restoringFont by remember { mutableStateOf<String?>(null) }
    var pendingFonts by remember { mutableStateOf(pendingFontSet(context, fontItems)) }
    val scope = rememberCoroutineScope()

    // Styling 预设当前项（spinner 选中态）
    var currentColorItem by remember {
        val saved = prefs.getString("selected_color_file", null)
        mutableStateOf(colorItems.firstOrNull { it.fileName == saved } ?: colorItems.first())
    }
    var currentColor by remember {
        mutableStateOf(prefs.getString("selected_color_name", null) ?: currentColorItem.displayName)
    }

    // 字体当前项
    var currentFontItem by remember {
        val saved = prefs.getString("selected_font_file", null)
        mutableStateOf(fontItems.firstOrNull { it.fileName == saved } ?: fontItems.first())
    }
    var currentFont by remember { mutableStateOf(prefs.getString("selected_font_name", null) ?: currentFontItem.displayName) }

    val previewColors = remember(themeSource, currentColorItem, libTerminalThemeName, customBg, customFg) {
        when (themeSource) {
            THEME_SOURCE_STYLING -> loadColorScheme(context, currentColorItem.fileName.takeIf { it != DEFAULT_FILENAME })
            THEME_SOURCE_LIBTERMINAL -> terminalColorsFromScheme(
                TerminalThemes.findByName(libTerminalThemeName)?.terminalColorScheme ?: TerminalColorScheme.dark()
            )
            else -> terminalColorsFromScheme(
                TerminalColorScheme.custom(foreground = customFg, background = customBg, cursor = deriveCursor(customBg))
            )
        }
    }
    var previewTypeface by remember { mutableStateOf(loadFontTypeface(context, currentFontItem.fileName.takeIf { it != DEFAULT_FILENAME })) }

    // 物化一个 TerminalColorScheme 到 ~/.termux/colors.properties 并广播 reload_style，
    // 与 Java 引擎、Compose 终端共用同一份事实来源，保持原有持久化与广播机制不变。
    fun applyScheme(scheme: TerminalColorScheme) {
        writeColorSchemeToTermux(context, scheme)
    }

    fun selectColor(item: StyleItem) {
        currentColorItem = item
        currentColor = if (item.fileName == DEFAULT_FILENAME) "Default" else item.displayName
        copyStyleFile(context, item, isColors = true)
    }

    fun selectLibTerminalTheme(name: String) {
        libTerminalThemeName = name
        prefs.edit().putString("libterminal_theme", name).apply()
        val scheme = TerminalThemes.findByName(name)?.terminalColorScheme ?: TerminalColorScheme.dark()
        applyScheme(scheme)
    }

    fun applyCustomTheme() {
        val scheme = TerminalColorScheme.custom(foreground = customFg, background = customBg, cursor = deriveCursor(customBg))
        applyScheme(scheme)
    }

    fun applyFont(item: StyleItem) {
        currentFontItem = item
        currentFont = if (item.fileName == DEFAULT_FILENAME) "Default" else item.displayName
        previewTypeface = loadFontTypeface(context, item.fileName.takeIf { it != DEFAULT_FILENAME })
        copyStyleFile(context, item, isColors = false)
    }

    fun selectFont(item: StyleItem) {
        if (item.fileName == DEFAULT_FILENAME || TerminalFontCatalog.isAvailable(context, item.fileName)) {
            applyFont(item)
            return
        }
        downloadingFont = item.fileName
        scope.launch {
            val result = FontDownloader.download(context, item.fileName)
            downloadingFont = null
            if (result.isSuccess) {
                pendingFonts = pendingFontSet(context, fontItems)
                applyFont(item)
            } else {
                SnackbarHelper.show(
                    context,
                    context.getString(R.string.font_download_failed, item.displayName),
                    Snackbar.LENGTH_LONG
                )
            }
        }
    }

    fun onThemeSourceChange(source: String) {
        themeSource = source
        prefs.edit().putString("theme_source", source).apply()
        // 切换到某来源时立刻把该来源的方案落到 colors.properties，终端实时刷新
        when (source) {
            THEME_SOURCE_STYLING -> selectColor(currentColorItem)
            THEME_SOURCE_LIBTERMINAL -> selectLibTerminalTheme(libTerminalThemeName)
            THEME_SOURCE_CUSTOM -> applyCustomTheme()
        }
    }

    // 已选中字体本地缺失时自动补下载（升级后字体被移出 APK 的情况），补不回则回退默认字体。
    LaunchedEffect(Unit) {
        val savedFile = prefs.getString("selected_font_file", null)
        if (savedFile == null || TerminalFontCatalog.isAvailable(context, savedFile)) return@LaunchedEffect

        restoringFont = savedFile
        val result = FontDownloader.download(context, savedFile)
        restoringFont = null
        if (result.isSuccess) {
            pendingFonts = pendingFontSet(context, fontItems)
            previewTypeface = loadFontTypeface(context, savedFile)
        } else {
            copyStyleFile(context, StyleItem(DEFAULT_FILENAME), isColors = false)
            previewTypeface = null
            currentFont = DEFAULT_FILENAME
            currentFontItem = StyleItem(DEFAULT_FILENAME)
            SnackbarHelper.show(
                context,
                context.getString(R.string.font_missing_fallback, StyleItem(savedFile).displayName),
                Snackbar.LENGTH_LONG
            )
        }
    }

    // 构造下拉项：字体项在本地不可用时追加「未安装」副标题
    val fontDropdownItems = remember(pendingFonts) {
        fontItems.map { item ->
            val unavailable = item.fileName != DEFAULT_FILENAME && !TerminalFontCatalog.isAvailable(context, item.fileName)
            DropdownItem(
                text = item.displayName,
                summary = if (unavailable) context.getString(R.string.font_not_installed) else null
            )
        }
    }
    val fontSelectedIndex = remember(currentFontItem) {
        fontItems.indexOfFirst { it.fileName == currentFontItem.fileName }.coerceAtLeast(0)
    }

    val colorDropdownItems = remember { colorItems.map { DropdownItem(text = it.displayName) } }
    val colorSelectedIndex = remember(currentColorItem) {
        colorItems.indexOfFirst { it.fileName == currentColorItem.fileName }.coerceAtLeast(0)
    }

    val libTerminalDropdownItems = remember { TerminalThemes.ALL.map { DropdownItem(text = it.name) } }
    val libTerminalSelectedIndex = remember(libTerminalThemeName) {
        TerminalThemes.ALL.indexOfFirst { it.name == libTerminalThemeName }.coerceAtLeast(0)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.title_styling),
                backdrop = glassPage.backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = { onBack() }) {
                        Icon(
                            imageVector = MiuixGlassIcons.ChevronBackward,
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
            modifier = modifier
                .then(glassPage.contentModifier)
                // 取景层内铺不透明 Miuix 底色，带区模糊采不到透明（防墨团）
                .background(MiuixTheme.colorScheme.surface)
                .fillMaxSize()
                .padding(pagePaddingWithoutTop(padding))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = standaloneContentPadding(padding, bottom = 16.dp)
            ) {
                item { SmallTitle(text = stringResource(R.string.styling_header)) }

                // Terminal preview card（实时预览）
                item {
                    Spacer(Modifier.height(8.dp))
                    TerminalPreviewCard(
                        colors = previewColors,
                        typeface = previewTypeface
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // 主题来源三选一（RadioButtonPreference）
                item {
                    SettingCard {
                        Column {
                            RadioButtonPreference(
                                title = stringResource(R.string.theme_source_styling),
                                summary = stringResource(R.string.theme_source_styling_desc),
                                selected = themeSource == THEME_SOURCE_STYLING,
                                onClick = { onThemeSourceChange(THEME_SOURCE_STYLING) }
                            )
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            RadioButtonPreference(
                                title = stringResource(R.string.theme_source_libterminal),
                                summary = stringResource(R.string.theme_source_libterminal_desc),
                                selected = themeSource == THEME_SOURCE_LIBTERMINAL,
                                onClick = { onThemeSourceChange(THEME_SOURCE_LIBTERMINAL) }
                            )
                            HorizontalDivider(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                            )
                            RadioButtonPreference(
                                title = stringResource(R.string.theme_source_custom),
                                summary = stringResource(R.string.theme_source_custom_desc),
                                selected = themeSource == THEME_SOURCE_CUSTOM,
                                onClick = { onThemeSourceChange(THEME_SOURCE_CUSTOM) }
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(12.dp)) }

                // 按来源显示对应的配色选择
                item {
                    SettingCard {
                        Column {
                            when (themeSource) {
                                THEME_SOURCE_STYLING -> {
                                    OverlaySpinnerPreference(
                                        title = stringResource(R.string.choose_color),
                                        summary = currentColor,
                                        items = colorDropdownItems,
                                        selectedIndex = colorSelectedIndex,
                                        onSelectedIndexChange = { idx ->
                                            selectColor(colorItems[idx])
                                        },
                                        startAction = { SettingIcon(R.drawable.ic_palette) }
                                    )
                                }
                                THEME_SOURCE_LIBTERMINAL -> {
                                    OverlaySpinnerPreference(
                                        title = stringResource(R.string.preset_theme),
                                        summary = libTerminalThemeName,
                                        items = libTerminalDropdownItems,
                                        selectedIndex = libTerminalSelectedIndex,
                                        onSelectedIndexChange = { idx ->
                                            selectLibTerminalTheme(TerminalThemes.ALL[idx].name)
                                        },
                                        startAction = { SettingIcon(R.drawable.ic_palette) }
                                    )
                                }
                                else -> {
                                    ArrowPreference(
                                        title = stringResource(R.string.custom_bg_color),
                                        summary = toHex6(customBg),
                                        onClick = { showBgPicker = true },
                                        startAction = { SettingIcon(R.drawable.ic_palette) }
                                    )
                                    HorizontalDivider(
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                                        modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                                    )
                                    ArrowPreference(
                                        title = stringResource(R.string.custom_fg_color),
                                        summary = toHex6(customFg),
                                        onClick = { showFgPicker = true },
                                        startAction = { SettingIcon(R.drawable.ic_edit) }
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(12.dp)) }

                // 字体（始终显示，使用 OverlaySpinnerPreference；未安装字体显示「未安装」）
                item {
                    SettingCard {
                        OverlaySpinnerPreference(
                            title = stringResource(R.string.choose_font),
                            summary = currentFont,
                            items = fontDropdownItems,
                            selectedIndex = fontSelectedIndex,
                            onSelectedIndexChange = { idx ->
                                selectFont(fontItems[idx])
                            },
                            startAction = { SettingIcon(R.drawable.ic_edit) }
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            // 自定义背景色选择器
            if (showBgPicker) {
                ColorPickerDialog(
                    title = stringResource(R.string.custom_bg_color),
                    initialColor = customBg,
                    onDismiss = { showBgPicker = false },
                    onConfirm = { color ->
                        customBg = color
                        prefs.edit().putString("custom_bg", toHex6(color)).apply()
                        if (themeSource == THEME_SOURCE_CUSTOM) applyCustomTheme()
                        showBgPicker = false
                    }
                )
            }

            // 自定义字体色（前景色）选择器
            if (showFgPicker) {
                ColorPickerDialog(
                    title = stringResource(R.string.custom_fg_color),
                    initialColor = customFg,
                    onDismiss = { showFgPicker = false },
                    onConfirm = { color ->
                        customFg = color
                        prefs.edit().putString("custom_fg", toHex6(color)).apply()
                        if (themeSource == THEME_SOURCE_CUSTOM) applyCustomTheme()
                        showFgPicker = false
                    }
                )
            }

            (restoringFont ?: downloadingFont)?.let { fileName ->
                OverlayDialog(
                    show = true,
                    onDismissRequest = { },
                    content = { FontDownloadingContent(fileName) }
                )
            }
        }
    }
}

/** 自定义颜色选择器：R/G/B 三个滑块（0-255）+ 实时预览。 */
@Composable
private fun ColorPickerDialog(
    title: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val init = Color(initialColor)
    var r by remember { mutableIntStateOf((init.red * 255f).roundToInt().coerceIn(0, 255)) }
    var g by remember { mutableIntStateOf((init.green * 255f).roundToInt().coerceIn(0, 255)) }
    var b by remember { mutableIntStateOf((init.blue * 255f).roundToInt().coerceIn(0, 255)) }
    val current = Color(r, g, b)

    OverlayDialog(
        show = true,
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))

                // 实时预览
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(current)
                )
                Spacer(Modifier.height(16.dp))

                Text(stringResource(R.string.color_red), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Slider(
                    value = r.toFloat(),
                    onValueChange = { r = it.roundToInt().coerceIn(0, 255) },
                    valueRange = 0f..255f,
                    steps = 254
                )
                Text(stringResource(R.string.color_green), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Slider(
                    value = g.toFloat(),
                    onValueChange = { g = it.roundToInt().coerceIn(0, 255) },
                    valueRange = 0f..255f,
                    steps = 254
                )
                Text(stringResource(R.string.color_blue), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Slider(
                    value = b.toFloat(),
                    onValueChange = { b = it.roundToInt().coerceIn(0, 255) },
                    valueRange = 0f..255f,
                    steps = 254
                )

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = onDismiss
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = { onConfirm(android.graphics.Color.rgb(r, g, b)) }
                    )
                }
            }
        }
    )
}

@Composable
private fun TerminalPreviewCard(
    colors: TerminalColors,
    typeface: Typeface?
) {
    val fontFamily = typeface?.let { FontFamily(it) }

    Card(
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(colors.background),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF5F57))
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFEBC2E))
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF28C840))
                )
            }

            Spacer(Modifier.height(12.dp))

            val baseTextStyle = TextStyle(
                fontFamily = fontFamily,
                fontSize = 13.sp,
                lineHeight = 17.sp
            )

            Text(
                text = "~ \$ ",
                color = colors.colors[4],
                style = baseTextStyle
            )

            Row {
                Text(text = "echo ", color = colors.colors[2], style = baseTextStyle)
                Text(text = "\"", color = colors.foreground, style = baseTextStyle)
                Text(text = "Hello", color = colors.colors[1], style = baseTextStyle)
                Text(text = ", ", color = colors.colors[6], style = baseTextStyle)
                Text(text = "Termux", color = colors.colors[3], style = baseTextStyle)
                Text(text = "\"", color = colors.foreground, style = baseTextStyle)
            }

            Text(
                text = "Hello, Termux",
                color = colors.foreground,
                style = baseTextStyle
            )

            Spacer(Modifier.height(4.dp))

            Row {
                Text(text = "~ \$ ", color = colors.colors[4], style = baseTextStyle)
                Text(text = "ls -la", color = colors.colors[2], style = baseTextStyle)
            }

            Text(
                text = "drwxr-xr-x 2 u0_a155 u0_a155 4096 .termux",
                color = colors.foreground.copy(alpha = 0.85f),
                style = baseTextStyle
            )

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                colors.colors.take(8).forEach { color ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                colors.colors.drop(8).forEach { color ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color)
                    )
                }
            }
        }
    }
}

private fun loadStyleItems(context: Context, assetFolder: String, extension: String): List<StyleItem> {
    val items = mutableListOf(StyleItem(DEFAULT_FILENAME))
    try {
        context.assets.list(assetFolder)
            ?.filter { it.endsWith(extension) }
            ?.forEach { items.add(StyleItem(it)) }
    } catch (_: Exception) {}
    return items
}

private fun pendingFontSet(context: Context, fontItems: List<StyleItem>): Set<String> =
    fontItems
        .filter { it.fileName != DEFAULT_FILENAME && !TerminalFontCatalog.isAvailable(context, it.fileName) }
        .map { it.fileName }
        .toSet()

private fun getTermuxDir(context: Context): File {
    val termuxContext = context.createPackageContext("com.termux", Context.CONTEXT_IGNORE_SECURITY)
    val homeDir = File(termuxContext.filesDir, "home")
    val termuxDir = File(homeDir, ".termux")
    if (!termuxDir.isDirectory) termuxDir.mkdirs()
    return termuxDir
}

/**
 * 将选中的颜色方案物化到 ~/.termux/colors.properties，并广播 reload_style。
 * 与既有 Styling 机制共用同一份事实来源，Java 引擎与 Compose 终端均据此刷新。
 */
private fun writeColorSchemeToTermux(context: Context, scheme: TerminalColorScheme) {
    try {
        val termuxDir = getTermuxDir(context)
        val destinationFile = File(termuxDir, "colors.properties").canonicalFile
        destinationFile.setWritable(true)
        destinationFile.parentFile?.setWritable(true)
        destinationFile.parentFile?.setExecutable(true)

        val sb = StringBuilder()
        sb.append("# Generated by KiTerminal Styling\n")
        sb.append("foreground=${toHex6(scheme.foreground)}\n")
        sb.append("background=${toHex6(scheme.background)}\n")
        sb.append("cursor=${toHex6(scheme.cursor)}\n")
        for (i in 0..15) {
            sb.append("color$i=${toHex6(scheme.color(i))}\n")
        }

        val atomicFile = AtomicFile(destinationFile)
        val out = atomicFile.startWrite()
        out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        atomicFile.finishWrite(out)

        // 让读取 selected_color_file 的代码（如有）保持一致的「非默认」状态
        context.getSharedPreferences("termux_styling", Context.MODE_PRIVATE)
            .edit()
            .putString("selected_color_name", "Custom/LibTerminal")
            .putString("selected_color_file", null)
            .apply()

        val actionReload = "com.termux.app.reload_style"
        val executeIntent = Intent(actionReload)
        executeIntent.putExtra(actionReload, "colors")
        context.sendBroadcast(executeIntent)
    } catch (e: Exception) {
        Log.w("TermuxStyling", "Failed to write color scheme", e)
        val message = context.getString(R.string.writing_failed) + e.message
        SnackbarHelper.show(context, message, Snackbar.LENGTH_LONG)
    }
}

private fun copyStyleFile(context: Context, item: StyleItem, isColors: Boolean) {
    try {
        val termuxDir = getTermuxDir(context)
        val outputFile = if (isColors) "colors.properties" else "font.ttf"
        val destinationFile = File(termuxDir, outputFile).canonicalFile
        destinationFile.setWritable(true)
        destinationFile.parentFile?.setWritable(true)
        destinationFile.parentFile?.setExecutable(true)

        val isDefault = item.fileName == DEFAULT_FILENAME
        val atomicFile = AtomicFile(destinationFile)
        val out = atomicFile.startWrite()
        if (isDefault) {
            if (isColors) {
                out.write("# Using default color theme.".toByteArray(StandardCharsets.UTF_8))
            }
        } else if (isColors) {
            context.assets.open("colors/${item.fileName}").use { input ->
                input.copyTo(out)
            }
        } else {
            val fontStream = TerminalFontCatalog.open(context, item.fileName)
                ?: throw IllegalStateException("font unavailable: ${item.fileName}")
            fontStream.use { input -> input.copyTo(out) }
        }
        atomicFile.finishWrite(out)

        val styleType = if (isColors) "color" else "font"
        val displayName = if (isDefault) "Default" else item.displayName
        context.getSharedPreferences("termux_styling", Context.MODE_PRIVATE)
            .edit()
            .putString("selected_${styleType}_name", displayName)
            .putString("selected_${styleType}_file", if (isDefault) null else item.fileName)
            .apply()

        val actionReload = "com.termux.app.reload_style"
        val executeIntent = Intent(actionReload)
        executeIntent.putExtra(actionReload, if (isColors) "colors" else "font")
        context.sendBroadcast(executeIntent)
    } catch (e: Exception) {
        Log.w("TermuxStyling", "Failed to write style file", e)
        val message = context.getString(R.string.writing_failed) + e.message
        SnackbarHelper.show(context, message, Snackbar.LENGTH_LONG)
    }
}

@Composable
private fun FontDownloadingContent(fileName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.font_downloading),
            fontSize = 16.sp,
            color = MiuixTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = StyleItem(fileName).displayName,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}
