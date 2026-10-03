package com.termux.app.plugin

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import com.termux.app.compose.rememberGlassPageBackdrop
import com.termux.app.compose.pagePaddingWithoutTop
import com.termux.app.compose.standaloneContentPadding
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.NavigationHelper
import com.termux.app.utils.SnackbarHelper
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.google.android.material.snackbar.Snackbar

class PluginCenterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                KiTerminalTheme {
                    PluginCenterScreen()
                }
            }
        }
    }
}

/** 列表筛选：全部 / 已启用 / 待处理（未启用、待授权、损坏） */
private enum class PluginFilter { ALL, ENABLED, PENDING }

@Composable
fun PluginCenterScreen() {
    val context = LocalContext.current
    // 本页在 MainScreen 取景层之外，自建一层供玻璃顶栏折射页面内容
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    var plugins by remember { mutableStateOf(PluginManager.getInstalledPlugins(context)) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(PluginFilter.ALL) }
    var showShareDialog by remember { mutableStateOf<InstalledPlugin?>(null) }
    var showPermissionDialog by remember { mutableStateOf<InstalledPlugin?>(null) }
    var showOverwriteDialog by remember { mutableStateOf<InstalledPlugin?>(null) }
    var showUninstallDialog by remember { mutableStateOf<InstalledPlugin?>(null) }
    var showPluginContentDialog by remember { mutableStateOf<InstalledPlugin?>(null) }
    var showDreamSheet by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)

                val displayName = context.contentResolver.query(
                    it, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }

                val ext = displayName?.substringAfterLast('.', "")?.lowercase()
                    ?: uri.path?.substringAfterLast('.', "")?.lowercase()
                    ?: ""
                val validExts = listOf("tup", "zip")
                val fileExt = if (ext in validExts) ".${ext}" else ".tup"

                val tempFile = java.io.File(context.cacheDir, "plugin_install_${System.currentTimeMillis()}${fileExt}")
                tempFile.outputStream().use { output ->
                    inputStream?.copyTo(output)
                }
                inputStream?.close()

                val result = PluginManager.installPlugin(context, tempFile)
                if (result.isSuccess) {
                    val manifest = result.getOrThrow()
                    SnackbarHelper.show(context, "插件「${manifest.name}」安装成功", Snackbar.LENGTH_SHORT)
                    plugins = PluginManager.getInstalledPlugins(context)
                } else {
                    SnackbarHelper.show(context, "安装失败: ${result.exceptionOrNull()?.message}", Snackbar.LENGTH_LONG)
                }
                tempFile.delete()
            } catch (e: Exception) {
                SnackbarHelper.show(context, "安装失败: ${e.message}", Snackbar.LENGTH_LONG)
            }
        }
    }

    fun handleInstall(plugin: InstalledPlugin) {
        val manifest = plugin.manifest
        val needsOverwrite = manifest.systemPrompt?.getPromptMode() == PromptModifyMode.OVERWRITE

        if (needsOverwrite) {
            showOverwriteDialog = plugin
        } else {
            showPermissionDialog = plugin
        }
    }

    fun confirmPermissions(plugin: InstalledPlugin) {
        val manifest = plugin.manifest
        val permissions = manifest.getParsedPermissions().toSet()
        PluginLoader.grantPermissions(context, plugin.id, permissions)
        PluginLoader.setPluginState(context, plugin.id, PluginState.ENABLED)
        SnackbarHelper.show(context, "插件「${manifest.name}」已启用", Snackbar.LENGTH_SHORT)
        plugins = PluginManager.getInstalledPlugins(context)
        showPermissionDialog = null
    }

    fun enablePlugin(plugin: InstalledPlugin) {
        if (PluginManager.enablePlugin(context, plugin.id)) {
            SnackbarHelper.show(context, "插件已启用", Snackbar.LENGTH_SHORT)
            plugins = PluginManager.getInstalledPlugins(context)
        }
    }

    fun disablePlugin(plugin: InstalledPlugin) {
        PluginManager.disablePlugin(context, plugin.id)
        SnackbarHelper.show(context, "插件已禁用", Snackbar.LENGTH_SHORT)
        plugins = PluginManager.getInstalledPlugins(context)
    }

    fun uninstallPlugin(plugin: InstalledPlugin) {
        PluginManager.uninstallPlugin(context, plugin.id)
        SnackbarHelper.show(context, "插件已卸载", Snackbar.LENGTH_SHORT)
        plugins = PluginManager.getInstalledPlugins(context)
        showUninstallDialog = null
    }

    val visiblePlugins = remember(plugins, query, filter) {
        val keyword = query.trim()
        plugins.filter { plugin ->
            val matchesFilter = when (filter) {
                PluginFilter.ALL -> true
                PluginFilter.ENABLED -> plugin.state == PluginState.ENABLED
                PluginFilter.PENDING -> plugin.state != PluginState.ENABLED
            }
            val matchesQuery = keyword.isBlank() || plugin.manifest.name.contains(keyword, true) ||
                plugin.manifest.description.contains(keyword, true) ||
                plugin.manifest.author.contains(keyword, true) ||
                plugin.id.contains(keyword, true)
            matchesFilter && matchesQuery
        }
    }

    fun openH5Home(plugin: InstalledPlugin) {
        val home = plugin.manifest.entryPoints?.h5Home
        if (home?.enabled == true) {
            val title = home.title ?: plugin.manifest.name
            val entry = home.effectiveEntry
            val type = home.type
            if (type == "compose") {
                PluginComposeActivity.start(
                    context = context,
                    pluginId = plugin.id,
                    entryPath = entry,
                    title = title
                )
            } else {
                PluginWebViewActivity.start(
                    context = context,
                    pluginId = plugin.id,
                    entryPath = entry,
                    title = title
                )
            }
        }
    }

    // 列表项渲染：四个分组共用同一套回调，避免复制粘贴出不一致的行为
    val pluginRow: @Composable (InstalledPlugin) -> Unit = { plugin ->
        PluginCard(
            plugin = plugin,
            onEnable = {
                if (plugin.state == PluginState.INSTALLED || plugin.state == PluginState.NEEDS_PERMISSION) {
                    handleInstall(plugin)
                } else {
                    enablePlugin(plugin)
                }
            },
            onDisable = { disablePlugin(plugin) },
            onUninstall = { showUninstallDialog = plugin },
            onDetails = { showPluginContentDialog = plugin },
            onShare = { showShareDialog = plugin },
            onOpenH5Home = { openH5Home(plugin) }
        )
    }

    val pending = visiblePlugins.filter { needsSetup(it.state) || it.state == PluginState.CORRUPTED }
    val enabled = visiblePlugins.filter { it.state == PluginState.ENABLED }
    val rest = visiblePlugins.filter { it.state == PluginState.DISABLED }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                GlassTopAppBar(
                    title = stringResource(R.string.plugin_center),
                    backdrop = glassPage.backdrop,
                    navigationIcon = {
                        GlassIconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pagePaddingWithoutTop(padding))
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = standaloneContentPadding(padding, bottom = 92.dp)
            ) {
                if (plugins.isNotEmpty()) {
                    item {
                        SearchBar(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            inputField = {
                                InputField(
                                    query = query,
                                    onQueryChange = { query = it },
                                    onSearch = { },
                                    expanded = false,
                                    onExpandedChange = { },
                                    label = stringResource(R.string.plugin_search_hint)
                                )
                            },
                            expanded = false,
                            onExpandedChange = { }
                        ) {}
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PluginFilterChip(
                                text = stringResource(R.string.plugin_filter_all),
                                selected = filter == PluginFilter.ALL,
                                onClick = { filter = PluginFilter.ALL }
                            )
                            PluginFilterChip(
                                text = stringResource(R.string.plugin_filter_enabled),
                                selected = filter == PluginFilter.ENABLED,
                                onClick = { filter = PluginFilter.ENABLED }
                            )
                            PluginFilterChip(
                                text = stringResource(R.string.plugin_filter_pending),
                                selected = filter == PluginFilter.PENDING,
                                onClick = { filter = PluginFilter.PENDING }
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp)),
                            colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color.White
                            )
                            Text(
                                text = stringResource(R.string.plugin_install),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Button(
                            onClick = { showDreamSheet = true },
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp)),
                            colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.plugin_dream),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (visiblePlugins.isEmpty()) {
                    item { PluginEmptyState(onInstall = { filePickerLauncher.launch("*/*") }, onDream = { showDreamSheet = true }) }
                } else {
                    if (query.isBlank() && filter == PluginFilter.ALL) {
                        if (pending.isNotEmpty()) {
                            item { SmallTitle(text = stringResource(R.string.plugin_filter_pending)) }
                            items(pending, key = { it.id }) { plugin -> pluginRow(plugin) }
                        }
                        if (enabled.isNotEmpty()) {
                            item { SmallTitle(text = stringResource(R.string.plugin_filter_enabled)) }
                            items(enabled, key = { it.id }) { plugin -> pluginRow(plugin) }
                        }
                        if (rest.isNotEmpty()) {
                            item { SmallTitle(text = stringResource(R.string.plugin_state_disabled)) }
                            items(rest, key = { it.id }) { plugin -> pluginRow(plugin) }
                        }
                    } else {
                        items(visiblePlugins, key = { it.id }) { plugin -> pluginRow(plugin) }
                    }
                }
            }
        }
    }

    PluginShareDialog(
        plugin = showShareDialog,
        onDismiss = { showShareDialog = null },
        onShareMeta = { plugin ->
            runCatching { PluginShare.shareMeta(context, plugin) }
                .onFailure { SnackbarHelper.show(context, "分享失败: ${it.message}", Snackbar.LENGTH_LONG) }
        },
        onSharePackage = { plugin ->
            PluginShare.sharePackage(context, plugin).onFailure {
                SnackbarHelper.show(context, context.getString(R.string.plugin_export_failed, it.message ?: ""), Snackbar.LENGTH_LONG)
            }
        }
    )

    PluginPermissionDialog(
        plugin = showPermissionDialog,
        onConfirm = { showPermissionDialog?.let { confirmPermissions(it) } },
        onDismiss = { showPermissionDialog = null }
    )

    PluginOverwriteDialog(
        plugin = showOverwriteDialog,
        onConfirm = {
            showOverwriteDialog?.let { confirmPermissions(it) }
            showOverwriteDialog = null
        },
        onDismiss = { showOverwriteDialog = null }
    )

    PluginUninstallDialog(
        plugin = showUninstallDialog,
        onConfirm = { showUninstallDialog?.let { uninstallPlugin(it) } },
        onDismiss = { showUninstallDialog = null }
    )

    PluginContentDialog(
        plugin = showPluginContentDialog,
        onDismiss = { showPluginContentDialog = null }
    )

    if (showDreamSheet) {
        PluginDreamDialog(
            onDismiss = { showDreamSheet = false },
            onInstalled = { plugins = PluginManager.getInstalledPlugins(context) }
        )
    }
}

@Composable
private fun PluginFilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MiuixTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        )
    }
}

@Composable
private fun PluginEmptyState(onInstall: () -> Unit, onDream: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_extension),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.plugin_no_plugins),
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.plugin_dream_desc),
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
                )
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onInstall,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                ) {
                    Text(text = stringResource(R.string.plugin_install), fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, color = Color.White)
                }
                Button(
                    onClick = onDream,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Text(text = stringResource(R.string.plugin_dream), fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
