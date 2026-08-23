package com.termux.app.plugin

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import com.termux.app.compose.NavigationHelper
import com.termux.app.utils.SnackbarHelper
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.google.android.material.snackbar.Snackbar

class PluginCenterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                PluginCenterScreen()
            }
        }
    }
}

@Composable
fun PluginCenterScreen() {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    var plugins by remember { mutableStateOf(PluginManager.getInstalledPlugins(context)) }
    var showPermissionDialog by remember { mutableStateOf<InstalledPlugin?>(null) }
    var showOverwriteDialog by remember { mutableStateOf<InstalledPlugin?>(null) }
    var showUninstallDialog by remember { mutableStateOf<InstalledPlugin?>(null) }
    var showPluginContentDialog by remember { mutableStateOf<InstalledPlugin?>(null) }

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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = stringResource(R.string.plugin_center),
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { (context as? ComponentActivity)?.finish() },
                            contentAlignment = Alignment.Center
                        ) {
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
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = 92.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmallTitle(text = stringResource(R.string.plugin_manage))
                    Button(
                        onClick = {
                            filePickerLauncher.launch("*/*")
                        },
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
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
                }
            }

            if (plugins.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
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
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.plugin_install_desc),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
                                )
                            )
                        }
                    }
                }
            } else {
                items(plugins) { plugin ->
                    PluginItemCard(
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
                        onViewContent = { showPluginContentDialog = plugin }
                    )
                }
            }
        }
    }

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
    }
}

@Composable
private fun PluginItemCard(
    plugin: InstalledPlugin,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onUninstall: () -> Unit,
    onViewContent: () -> Unit
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFFAFAFA)
    val onSurface = MiuixTheme.colorScheme.onSurface
    val stateColor = when (plugin.state) {
        PluginState.ENABLED -> Color(0xFF4CAF50)
        PluginState.DISABLED -> Color(0xFF9E9E9E)
        PluginState.CORRUPTED -> Color(0xFFF44336)
        PluginState.NEEDS_PERMISSION -> Color(0xFFFFA000)
        else -> Color(0xFF2196F3)
    }

    MiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_extension),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = plugin.manifest.name,
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = onSurface
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(stateColor.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = when (plugin.state) {
                                    PluginState.ENABLED -> "启用"
                                    PluginState.DISABLED -> "禁用"
                                    PluginState.CORRUPTED -> "损坏"
                                    PluginState.NEEDS_PERMISSION -> "待授权"
                                    else -> "已安装"
                                },
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 10.sp,
                                    color = stateColor
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${plugin.manifest.version} · ${plugin.manifest.author}",
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    )
                }
            }

            if (plugin.manifest.description.isNotBlank()) {
                Text(
                    text = plugin.manifest.description,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                )
                Spacer(Modifier.height(12.dp))
            }

            val hasEntries = plugin.manifest.entryPoints?.let { ep ->
                !ep.resourceCards.isNullOrEmpty() ||
                !ep.agentSkills.isNullOrEmpty() ||
                ep.h5Home?.enabled == true ||
                !ep.pages.isNullOrEmpty()
            } ?: false

            if (hasEntries || plugin.manifest.systemPrompt != null) {
                HorizontalDivider(color = onSurface.copy(alpha = 0.15f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (plugin.manifest.entryPoints?.resourceCards?.isNotEmpty() == true) {
                        EntryTag(text = "资源卡片", color = MiuixTheme.colorScheme.primary)
                    }
                    if (plugin.manifest.entryPoints?.agentSkills?.isNotEmpty() == true) {
                        EntryTag(text = "技能卡片", color = Color(0xFF4CAF50))
                    }
                    if (plugin.manifest.entryPoints?.h5Home?.enabled == true ||
                        plugin.manifest.entryPoints?.pages?.isNotEmpty() == true) {
                        EntryTag(text = stringResource(R.string.plugin_h5_pages), color = Color(0xFFFF9800))
                    }
                    if (plugin.manifest.systemPrompt != null) {
                        EntryTag(text = "System Prompt", color = Color(0xFF9C27B0))
                    }
                }
            }

            HorizontalDivider(color = onSurface.copy(alpha = 0.15f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val needsSetup = plugin.state == PluginState.INSTALLED || plugin.state == PluginState.NEEDS_PERMISSION

                Button(
                    onClick = onViewContent,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    colors = ButtonDefaults.buttonColors(
                        color = if (isDark) Color(0xFF424242) else Color(0xFFE0E0E0)
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = onSurface
                    )
                }

                if (needsSetup) {
                    Button(
                        onClick = onEnable,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Text(
                            text = "授权并启用",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else if (plugin.state == PluginState.ENABLED) {
                    Button(
                        onClick = onDisable,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        colors = ButtonDefaults.buttonColors(
                            color = if (isDark) Color(0xFF424242) else Color(0xFFE0E0E0)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = onSurface
                        )
                        Text(
                            text = "禁用",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurface
                        )
                    }
                } else if (plugin.state == PluginState.DISABLED) {
                    Button(
                        onClick = onEnable,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Text(
                            text = "启用",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Button(
                    onClick = onUninstall,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                    colors = ButtonDefaults.buttonColors(color = Color(0xFFF44336).copy(alpha = 0.15f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFF44336)
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = color
            )
        )
    }
}

@Composable
private fun PluginPermissionDialog(
    plugin: InstalledPlugin?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val permissions = plugin?.manifest?.getParsedPermissions() ?: emptyList()

    WindowDialog(
        show = plugin != null,
        title = stringResource(R.string.plugin_dialog_permission_title),
        summary = plugin?.let {
            stringResource(R.string.plugin_dialog_permission_message, it.manifest.name)
        } ?: "",
        onDismissRequest = onDismiss,
        content = {
            if (plugin == null) return@WindowDialog
            val context = LocalContext.current
            Column(modifier = Modifier.padding(top = 12.dp)) {
                permissions.forEach { perm ->
                    val riskLevel = permissionRiskMap[perm] ?: PermissionRiskLevel.LOW
                    val riskColor = when (riskLevel) {
                        PermissionRiskLevel.LOW -> Color(0xFF4CAF50)
                        PermissionRiskLevel.MEDIUM -> Color(0xFFFFA000)
                        PermissionRiskLevel.HIGH -> Color(0xFFF44336)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(riskColor, RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = PluginSecurity.getPermissionDisplayName(perm),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = PluginSecurity.getPermissionDescription(perm),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(riskColor.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = PluginSecurity.getRiskLevelDisplayName(riskLevel),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 10.sp,
                                    color = riskColor
                                )
                            )
                        }
                    }
                }

                if (permissions.any { permissionRiskMap[it] == PermissionRiskLevel.HIGH }) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.plugin_warning_high_permission),
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            color = Color(0xFFFFA000)
                        )
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.plugin_dialog_cancel),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                    ) {
                        Text(
                            text = stringResource(R.string.plugin_dialog_confirm),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun PluginOverwriteDialog(
    plugin: InstalledPlugin?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = plugin != null,
        title = stringResource(R.string.plugin_dialog_overwrite_title),
        summary = plugin?.let {
            stringResource(R.string.plugin_dialog_overwrite_message, it.manifest.name)
        } ?: "",
        onDismissRequest = onDismiss,
        content = {
            if (plugin == null) return@WindowDialog
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.plugin_dialog_overwrite_cancel),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(color = Color(0xFFF44336))
                    ) {
                        Text(
                            text = stringResource(R.string.plugin_dialog_overwrite_confirm),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun PluginUninstallDialog(
    plugin: InstalledPlugin?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = plugin != null,
        title = stringResource(R.string.plugin_uninstall),
        summary = plugin?.let { stringResource(R.string.plugin_uninstall_confirm, it.manifest.name) } ?: "",
        onDismissRequest = onDismiss,
        content = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                    colors = ButtonDefaults.buttonColors(color = Color(0xFFF44336))
                ) {
                    Text(
                        text = stringResource(R.string.confirm),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    )
}

@Composable
private fun PluginContentDialog(
    plugin: InstalledPlugin?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val skills = plugin?.let { p -> PluginManager.getPluginSkills(context).filter { skill -> skill.id.startsWith(p.id) } } ?: emptyList()
    val resourceCards = plugin?.let { p -> PluginManager.getPluginResourceCards(context).filter { card -> card.id.startsWith(p.id) } } ?: emptyList()

    val summaryText = plugin?.let { p ->
        buildString {
            append("${p.manifest.version} · ${p.manifest.author}")
            if (p.manifest.description.isNotBlank()) {
                append("\n\n")
                append(p.manifest.description)
            }
        }
    } ?: ""

    WindowDialog(
        show = plugin != null,
        title = plugin?.manifest?.name ?: "",
        summary = summaryText,
        onDismissRequest = onDismiss,
        content = {
            if (plugin == null) return@WindowDialog
            val activePlugin = plugin
            val h5Entries = activePlugin.manifest.getAllH5Entries()

            Column(modifier = Modifier.padding(top = 12.dp)) {
                if (skills.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.plugin_skill_card),
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    skills.forEach { skill ->
                        Text(
                            text = "• ${skill.name} — ${skill.description}",
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                    }
                }

                if (resourceCards.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.plugin_entry_resource),
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    resourceCards.forEach { card ->
                        Text(
                            text = "• ${card.title} — ${card.description}",
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                    }
                }

                activePlugin.manifest.systemPrompt?.let { sp ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.plugin_sys_prompt),
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    val modeText = when (sp.getPromptMode()) {
                        PromptModifyMode.APPEND -> "追加"
                        PromptModifyMode.MODIFY -> "修改"
                        PromptModifyMode.OVERWRITE -> "覆盖"
                    }
                    Text(
                        text = "模式: $modeText",
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    )
                }

                if (h5Entries.isNotEmpty() && activePlugin.state == PluginState.ENABLED) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.plugin_h5_pages),
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    h5Entries.forEach { (title, entry) ->
                        Button(
                            onClick = {
                                PluginWebViewActivity.start(
                                    context,
                                    activePlugin.id,
                                    entry,
                                    title
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .padding(vertical = 2.dp),
                            colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                        ) {
                            Text(
                                text = title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp)),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }
    )
}