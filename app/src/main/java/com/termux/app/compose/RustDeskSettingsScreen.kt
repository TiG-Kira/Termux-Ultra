package com.termux.app.compose

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.termux.R
import com.termux.app.rustdesk.RustDeskClient
import com.termux.app.rustdesk.RustDeskConnectionManager
import com.termux.app.rustdesk.RustDeskSettings
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
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

enum class RustDeskSettingsPage {
    MAIN,
    CONNECTION,
    SECURITY,
    CLIENT
}

private fun getRustDeskPageTitle(context: Context, page: RustDeskSettingsPage): String {
    return when (page) {
        RustDeskSettingsPage.MAIN -> context.getString(R.string.rustdesk_settings)
        RustDeskSettingsPage.CONNECTION -> context.getString(R.string.rustdesk_settings_connection)
        RustDeskSettingsPage.SECURITY -> context.getString(R.string.rustdesk_settings_security)
        RustDeskSettingsPage.CLIENT -> context.getString(R.string.rustdesk_settings_client)
    }
}

/**
 * 「RustDesk 设置」页（独立 Activity）。
 *
 * 版式与 [VncSettingsScreen] 一致：玻璃顶栏 + SmallTitle + SettingCard + miuix 偏好组件，
 * 主页面下钻到「连接 / 安全 / 客户端」三个子页。所有项都在发起交接时被 [RustDeskClient] 读取，
 * 改动即时落盘、无保存按钮。
 */
@Composable
fun RustDeskSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var currentPage by remember { mutableStateOf(RustDeskSettingsPage.MAIN) }
    // 本页在 MainScreen 取景层之外，自建一层供玻璃顶栏折射页面内容
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()

    fun handleBack() {
        if (currentPage == RustDeskSettingsPage.MAIN) onBack()
        else currentPage = RustDeskSettingsPage.MAIN
    }

    BackHandler(enabled = true) {
        handleBack()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = getRustDeskPageTitle(context, currentPage),
                backdrop = glassPage.backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = { handleBack() }) {
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
        CompositionLocalProvider(LocalTopBarClearance provides topBarClearance(padding)) {
            Box(
                modifier = Modifier
                    .then(glassPage.contentModifier)
                    // 取景层内铺不透明 Miuix 底色，带区模糊采不到透明（防墨团）
                    .background(MiuixTheme.colorScheme.surface)
                    .fillMaxSize()
                    .padding(pagePaddingWithoutTop(padding))
            ) {
                when (currentPage) {
                    RustDeskSettingsPage.MAIN -> RustDeskMainPage(
                        onNavigate = { currentPage = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )

                    RustDeskSettingsPage.CONNECTION -> RustDeskConnectionPage(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )

                    RustDeskSettingsPage.SECURITY -> RustDeskSecurityPage(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    )

                    RustDeskSettingsPage.CLIENT -> RustDeskClientPage(
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
private fun RustDeskMainPage(
    onNavigate: (RustDeskSettingsPage) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val defaults = remember { RustDeskSettings.defaults(context) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = LocalTopBarClearance.current, bottom = 16.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.rustdesk_settings)) }
        item {
            SettingCard {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.rustdesk_settings_connection),
                        summary = if (defaults.server.isBlank()) {
                            stringResource(R.string.rustdesk_official_server)
                        } else {
                            defaults.server
                        },
                        onClick = { onNavigate(RustDeskSettingsPage.CONNECTION) },
                        startAction = { SettingIcon(Icons.Rounded.Dns) }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.rustdesk_settings_security),
                        summary = if (defaults.askPassword) {
                            stringResource(R.string.rustdesk_ask_password)
                        } else {
                            stringResource(R.string.rustdesk_save_password)
                        },
                        onClick = { onNavigate(RustDeskSettingsPage.SECURITY) },
                        startAction = { SettingIcon(Icons.Rounded.Lock) }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.rustdesk_settings_client),
                        summary = stringResource(R.string.rustdesk_client_required),
                        onClick = { onNavigate(RustDeskSettingsPage.CLIENT) },
                        startAction = { SettingIcon(Icons.Rounded.Devices) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun RustDeskConnectionPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var server by remember { mutableStateOf(RustDeskSettings.server(context)) }
    var publicKey by remember { mutableStateOf(RustDeskSettings.publicKey(context)) }
    var forceRelay by remember { mutableStateOf(RustDeskSettings.forceRelay(context)) }
    var requestApproval by remember { mutableStateOf(RustDeskSettings.requestApproval(context)) }
    var editingServer by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(false) }

    val loginMethodItems = listOf(
        stringResource(R.string.rustdesk_login_by_password),
        stringResource(R.string.rustdesk_login_by_approval)
    )

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = LocalTopBarClearance.current, bottom = 16.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.rustdesk_settings_connection)) }
        item {
            SettingCard {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.rustdesk_default_server),
                        summary = server.ifBlank {
                            stringResource(R.string.rustdesk_official_server)
                        },
                        onClick = { editingServer = true },
                        startAction = { SettingIcon(Icons.Rounded.Dns) }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.rustdesk_public_key),
                        summary = if (publicKey.isBlank()) {
                            stringResource(R.string.rustdesk_public_key_unset)
                        } else {
                            stringResource(R.string.rustdesk_public_key_set)
                        },
                        onClick = { editingKey = true },
                        startAction = { SettingIcon(Icons.Rounded.Password) }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.rustdesk_force_relay),
                        summary = stringResource(R.string.rustdesk_force_relay_desc),
                        checked = forceRelay,
                        onCheckedChange = {
                            forceRelay = it
                            RustDeskSettings.setForceRelay(context, it)
                        },
                        startAction = { SettingIcon(Icons.Rounded.SwapVert) }
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.rustdesk_login_method),
                        summary = stringResource(R.string.rustdesk_login_method_desc),
                        items = loginMethodItems,
                        selectedIndex = if (requestApproval) 1 else 0,
                        onSelectedIndexChange = { index ->
                            requestApproval = index == 1
                            RustDeskSettings.setRequestApproval(context, requestApproval)
                        },
                        startAction = { SettingIcon(Icons.Rounded.Lock) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    if (editingServer) {
        RustDeskTextInputDialog(
            title = stringResource(R.string.rustdesk_default_server),
            value = server,
            hint = stringResource(R.string.rustdesk_default_server_summary),
            onConfirm = {
                server = it
                RustDeskSettings.setServer(context, it)
                editingServer = false
            },
            onDismiss = { editingServer = false }
        )
    }

    if (editingKey) {
        RustDeskTextInputDialog(
            title = stringResource(R.string.rustdesk_public_key),
            value = publicKey,
            hint = stringResource(R.string.rustdesk_public_key_summary),
            onConfirm = {
                publicKey = it
                RustDeskSettings.setPublicKey(context, it)
                editingKey = false
            },
            onDismiss = { editingKey = false }
        )
    }
}

@Composable
private fun RustDeskSecurityPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var savePassword by remember { mutableStateOf(RustDeskSettings.savePassword(context)) }
    var askPassword by remember { mutableStateOf(RustDeskSettings.askPassword(context)) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = LocalTopBarClearance.current, bottom = 16.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.rustdesk_settings_security)) }
        item {
            SettingCard {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.rustdesk_save_password),
                        summary = stringResource(R.string.rustdesk_save_password_desc),
                        checked = savePassword,
                        onCheckedChange = {
                            savePassword = it
                            RustDeskSettings.setSavePassword(context, it)
                        },
                        startAction = { SettingIcon(Icons.Rounded.Password) }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.rustdesk_ask_password),
                        summary = stringResource(R.string.rustdesk_ask_password_desc),
                        checked = askPassword,
                        onCheckedChange = {
                            askPassword = it
                            RustDeskSettings.setAskPassword(context, it)
                        },
                        startAction = { SettingIcon(Icons.Rounded.Lock) }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.rustdesk_clear_passwords),
                        summary = null,
                        onClick = { showClearConfirm = true },
                        startAction = { SettingIcon(Icons.Rounded.DeleteSweep) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    OverlayDialog(
        title = stringResource(R.string.rustdesk_clear_passwords),
        summary = stringResource(R.string.rustdesk_clear_passwords_confirm),
        show = showClearConfirm,
        onDismissRequest = { showClearConfirm = false },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.rustdesk_clear),
                    onClick = {
                        val cleared = RustDeskConnectionManager(context).clearPasswords()
                        showClearConfirm = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.rustdesk_clear_passwords_done, cleared),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { showClearConfirm = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

@Composable
private fun RustDeskClientPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clientInstalled = remember { RustDeskClient.isInstalled(context) }
    val clientVersion = remember { RustDeskClient.versionName(context) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var connectionCount by remember { mutableStateOf(RustDeskConnectionManager(context).count()) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = LocalTopBarClearance.current, bottom = 16.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.rustdesk_settings_client)) }
        item {
            SettingCard {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.rustdesk_client_status),
                        summary = if (clientInstalled) {
                            if (clientVersion.isNullOrBlank()) {
                                stringResource(R.string.rustdesk_client_installed_no_version)
                            } else {
                                stringResource(R.string.rustdesk_client_installed, clientVersion)
                            }
                        } else {
                            stringResource(R.string.rustdesk_client_not_installed)
                        },
                        onClick = {
                            if (clientInstalled) {
                                if (!RustDeskClient.openClient(context)) {
                                    RustDeskClient.openDownloadPage(context)
                                }
                            } else {
                                RustDeskClient.openDownloadPage(context)
                            }
                        },
                        startAction = { SettingIcon(Icons.Rounded.Devices) }
                    )
                    if (!clientInstalled) {
                        ArrowPreference(
                            title = stringResource(R.string.rustdesk_download),
                            summary = stringResource(R.string.rustdesk_download_desc),
                            onClick = { RustDeskClient.openDownloadPage(context) },
                            startAction = { SettingIcon(Icons.Rounded.Download) }
                        )
                    }
                    ArrowPreference(
                        title = stringResource(R.string.rustdesk_clear_connections),
                        summary = stringResource(R.string.rustdesk_clear_connections_summary, connectionCount),
                        onClick = { showClearConfirm = true },
                        startAction = { SettingIcon(Icons.Rounded.DeleteSweep) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    OverlayDialog(
        title = stringResource(R.string.rustdesk_clear_connections),
        summary = stringResource(R.string.rustdesk_clear_connections_confirm),
        show = showClearConfirm,
        onDismissRequest = { showClearConfirm = false },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.rustdesk_clear),
                    onClick = {
                        RustDeskConnectionManager(context).clearAll()
                        connectionCount = 0
                        showClearConfirm = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { showClearConfirm = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

@Composable
private fun RustDeskTextInputDialog(
    title: String,
    value: String,
    hint: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(value) }
    var show by remember { mutableStateOf(true) }

    OverlayDialog(
        show = show,
        onDismissRequest = {
            show = false
            onDismiss()
        },
        title = title,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = hint)
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = title,
                    useLabelAsPlaceholder = true
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = {
                            show = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(16.dp))
                    TextButton(
                        text = stringResource(R.string.save),
                        onClick = {
                            show = false
                            onConfirm(input.trim())
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}
