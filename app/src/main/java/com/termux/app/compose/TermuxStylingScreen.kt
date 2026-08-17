package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import android.util.Log
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.shared.termux.TermuxConstants
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.nio.charset.StandardCharsets

private const val DEFAULT_FILENAME = "Default"

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
    var currentColor by remember { mutableStateOf(getCurrentStyle(context, "colors.properties")) }
    var currentFont by remember { mutableStateOf(getCurrentStyle(context, "font.ttf")) }

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
                contentPadding = PaddingValues(bottom = 92.dp)
            ) {
                item { SmallTitle(text = stringResource(R.string.styling_header)) }
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
                            copyStyleFile(context, item, true)
                            currentColor = item.displayName
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
                            copyStyleFile(context, item, false)
                            currentFont = item.displayName
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

private fun loadStyleItems(context: Context, assetFolder: String, extension: String): List<StyleItem> {
    val items = mutableListOf(StyleItem(DEFAULT_FILENAME))
    try {
        context.assets.list(assetFolder)
            ?.filter { it.endsWith(extension) }
            ?.forEach { items.add(StyleItem(it)) }
    } catch (_: Exception) {}
    return items
}

private fun getCurrentStyle(context: Context, fileName: String): String {
    return try {
        val termuxDir = getTermuxDir(context)
        val file = File(termuxDir, fileName)
        if (file.exists()) "Custom" else "Default"
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

        val actionReload = "com.termux.app.reload_style"
        val executeIntent = Intent(actionReload)
        executeIntent.putExtra(actionReload, if (isColors) "colors" else "font")
        context.sendBroadcast(executeIntent)
    } catch (e: Exception) {
        Log.w("TermuxStyling", "Failed to write style file", e)
        val message = context.getString(R.string.writing_failed) + e.message
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
