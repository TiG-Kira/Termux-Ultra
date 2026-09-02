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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.app.utils.SnackbarHelper
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties

private const val DEFAULT_FILENAME = "Default"

private val DEFAULT_TERMINAL_COLORS = TerminalColors(
    foreground = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    cursor = Color(0xFFFFFFFF),
    colors = listOf(
        Color(0xFF000000), // color0 black
        Color(0xFFCD0000), // color1 red
        Color(0xFF00CD00), // color2 green
        Color(0xFFCDCD00), // color3 yellow
        Color(0xFF6495ED), // color4 blue
        Color(0xFFCD00CD), // color5 magenta
        Color(0xFF00CDCD), // color6 cyan
        Color(0xFFE5E5E5), // color7 white
        Color(0xFF7F7F7F), // color8 bright black
        Color(0xFFFF0000), // color9 bright red
        Color(0xFF00FF00), // color10 bright green
        Color(0xFFFFFF00), // color11 bright yellow
        Color(0xFF5C5CFF), // color12 bright blue
        Color(0xFFFF00FF), // color13 bright magenta
        Color(0xFF00FFFF), // color14 bright cyan
        Color(0xFFFFFFFF)  // color15 bright white
    )
)

private data class TerminalColors(
    val foreground: Color,
    val background: Color,
    val cursor: Color,
    val colors: List<Color> // color0-color15
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
            // Derive cursor from background luminance
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
        context.assets.open("fonts/$fileName").use { input ->
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
    val scrollBehavior = MiuixScrollBehavior()

    val colorItems = remember { loadStyleItems(context, "colors", ".properties") }
    val fontItems = remember { loadStyleItems(context, "fonts", ".ttf") }

    var showColorDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showColorLicense by remember { mutableStateOf<StyleItem?>(null) }
    var showFontLicense by remember { mutableStateOf<StyleItem?>(null) }
    var currentColor by remember { mutableStateOf(getCurrentStyle(context, "colors.properties", "color")) }
    var currentFont by remember { mutableStateOf(getCurrentStyle(context, "font.ttf", "font")) }

    // Preview state: default to current saved style
    val currentColorItem = remember {
        val prefs = context.getSharedPreferences("termux_styling", Context.MODE_PRIVATE)
        val saved = prefs.getString("selected_color_file", null)
        colorItems.firstOrNull { it.fileName == saved } ?: colorItems.first()
    }
    val currentFontItem = remember {
        val prefs = context.getSharedPreferences("termux_styling", Context.MODE_PRIVATE)
        val saved = prefs.getString("selected_font_file", null)
        fontItems.firstOrNull { it.fileName == saved } ?: fontItems.first()
    }

    // Colors and font for preview (reactive to selection)
    var previewColors by remember { mutableStateOf(loadColorScheme(context, currentColorItem.fileName.takeIf { it != DEFAULT_FILENAME })) }
    var previewTypeface by remember { mutableStateOf(loadFontTypeface(context, currentFontItem.fileName.takeIf { it != DEFAULT_FILENAME })) }

    fun selectColor(item: StyleItem) {
        previewColors = loadColorScheme(context, item.fileName.takeIf { it != DEFAULT_FILENAME })
        copyStyleFile(context, item, true)
        currentColor = item.displayName
    }

    fun selectFont(item: StyleItem) {
        previewTypeface = loadFontTypeface(context, item.fileName.takeIf { it != DEFAULT_FILENAME })
        copyStyleFile(context, item, false)
        currentFont = item.displayName
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.title_styling),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
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
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item { SmallTitle(text = stringResource(R.string.styling_header)) }

                // Terminal preview card
                item {
                    Spacer(Modifier.height(8.dp))
                    TerminalPreviewCard(
                        colors = previewColors,
                        typeface = previewTypeface
                    )
                    Spacer(Modifier.height(12.dp))
                }

                item {
                    SettingCard {
                        ArrowPreference(
                            title = stringResource(R.string.choose_color),
                            summary = currentColor,
                            onClick = { showColorDialog = true },
                            startAction = {
                                SettingIcon(R.drawable.ic_palette)
                            }
                        )
                        HorizontalDivider(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f),
                            modifier = Modifier.padding(start = 72.dp, end = 16.dp)
                        )
                        ArrowPreference(
                            title = stringResource(R.string.choose_font),
                            summary = currentFont,
                            onClick = { showFontDialog = true },
                            startAction = {
                                SettingIcon(R.drawable.ic_edit)
                            }
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            OverlayDialog(
                show = showColorDialog,
                onDismissRequest = { showColorDialog = false },
                content = {
                    StyleListContent(
                        title = stringResource(R.string.choose_color),
                        items = colorItems,
                        currentItem = currentColor,
                        onSelect = { item ->
                            selectColor(item)
                            showColorDialog = false
                        },
                        onLongPress = { item ->
                            showColorLicense = item
                        }
                    )
                }
            )

            OverlayDialog(
                show = showFontDialog,
                onDismissRequest = { showFontDialog = false },
                content = {
                    StyleListContent(
                        title = stringResource(R.string.choose_font),
                        items = fontItems,
                        currentItem = currentFont,
                        onSelect = { item ->
                            selectFont(item)
                            showFontDialog = false
                        },
                        onLongPress = { item ->
                            showFontLicense = item
                        }
                    )
                }
            )

            showColorLicense?.let { item ->
                OverlayDialog(
                    show = showColorLicense != null,
                    onDismissRequest = { showColorLicense = null },
                    content = {
                        LicenseContent(
                            context = context,
                            item = item,
                            isColors = true
                        )
                    }
                )
            }

            showFontLicense?.let { item ->
                OverlayDialog(
                    show = showFontLicense != null,
                    onDismissRequest = { showFontLicense = null },
                    content = {
                        LicenseContent(
                            context = context,
                            item = item,
                            isColors = false
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun TerminalPreviewCard(
    colors: TerminalColors,
    typeface: Typeface?
) {
    val fontFamily = typeface?.let { FontFamily(it) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.background),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Title bar dots (macOS-style window)
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

            // Sample terminal text
            val baseTextStyle = TextStyle(
                fontFamily = fontFamily,
                fontSize = 13.sp,
                lineHeight = 17.sp
            )

            // Line 1: prompt + path (normal text)
            Text(
                text = "~ \$ ",
                color = colors.colors[4], // blue prompt
                style = baseTextStyle
            )

            // Line 2: echo with ANSI-like colored words
            Row {
                Text(
                    text = "echo ",
                    color = colors.colors[2], // green command
                    style = baseTextStyle
                )
                Text(
                    text = "\"",
                    color = colors.foreground,
                    style = baseTextStyle
                )
                Text(
                    text = "Hello",
                    color = colors.colors[1], // red for word
                    style = baseTextStyle
                )
                Text(
                    text = ", ",
                    color = colors.colors[6], // cyan
                    style = baseTextStyle
                )
                Text(
                    text = "Termux",
                    color = colors.colors[3], // yellow
                    style = baseTextStyle
                )
                Text(
                    text = "\"",
                    color = colors.foreground,
                    style = baseTextStyle
                )
            }

            // Line 3: command output
            Text(
                text = "Hello, Termux",
                color = colors.foreground,
                style = baseTextStyle
            )

            Spacer(Modifier.height(4.dp))

            // Line 4: secondary command
            Row {
                Text(
                    text = "~ \$ ",
                    color = colors.colors[4],
                    style = baseTextStyle
                )
                Text(
                    text = "ls -la",
                    color = colors.colors[2],
                    style = baseTextStyle
                )
            }

            // Line 5: ls output
            Text(
                text = "drwxr-xr-x 2 u0_a155 u0_a155 4096 .termux",
                color = colors.foreground.copy(alpha = 0.85f),
                style = baseTextStyle
            )

            Spacer(Modifier.height(14.dp))

            // Color swatches row: 16 colors in 2 rows of 8
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

private fun getCurrentStyle(context: Context, fileName: String, styleType: String): String {
    val prefs = context.getSharedPreferences("termux_styling", Context.MODE_PRIVATE)
    val savedName = prefs.getString("selected_${styleType}_name", null)
    if (savedName != null) return savedName
    return try {
        val termuxDir = getTermuxDir(context)
        val file = File(termuxDir, fileName)
        if (file.exists()) {
            val content = file.readText().trim()
            if (content.startsWith("# Using default")) "Default" else "Custom"
        } else "Default"
    } catch (_: Exception) {
        "Default"
    }
}

private fun getTermuxDir(context: Context): File {
    val termuxContext = context.createPackageContext("com.termux", Context.CONTEXT_IGNORE_SECURITY)
    val homeDir = File(termuxContext.filesDir, "home")
    val termuxDir = File(homeDir, ".termux")
    if (!termuxDir.isDirectory) termuxDir.mkdirs()
    return termuxDir
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
        } else {
            val assetFolder = if (isColors) "colors" else "fonts"
            context.assets.open("$assetFolder/${item.fileName}").use { input ->
                input.copyTo(out)
            }
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
private fun StyleListContent(
    title: String,
    items: List<StyleItem>,
    currentItem: String,
    onSelect: (StyleItem) -> Unit,
    onLongPress: (StyleItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(24.dp)
            )
        }
        items(items.size) { index ->
            val item = items[index]
            val isSelected = item.displayName == currentItem
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(item) }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.displayName,
                    fontSize = 16.sp,
                    color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isSelected) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (index < items.size - 1) {
                HorizontalDivider(
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f),
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun LicenseContent(
    context: Context,
    item: StyleItem,
    isColors: Boolean
) {
    val licenseText = remember(item) {
        try {
            val assetFolder = if (isColors) "colors" else "fonts"
            var fileName = item.fileName
            val dotIndex = fileName.lastIndexOf('.')
            if (dotIndex != -1) fileName = fileName.substring(0, dotIndex)
            fileName += ".txt"
            context.assets.open("$assetFolder/$fileName").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            context.getString(R.string.no_license_available)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = item.displayName,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
        ) {
            item {
                Text(
                    text = licenseText,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                text = context.getString(android.R.string.ok),
                onClick = { }
            )
        }
    }
}
