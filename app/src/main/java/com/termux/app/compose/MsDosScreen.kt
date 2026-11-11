package com.termux.app.compose

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MS-DOS 4.0 模拟器页：磁盘资产状态、控制台/VNC 双入口、用户目录挂载与 VNC 端口。
 */
@Composable
fun MsDosScreen(
    onBack: () -> Unit,
    onLaunch: (MsDosLauncher.Mode) -> Unit,
    onOpenDownloadPage: () -> Unit
) {
    val context = LocalContext.current
    val config by MsDosPrefs.config.collectAsState()
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(MsDosAssetDownloader.Phase.CHECKING) }
    var progress by remember { mutableStateOf(-1f) }
    var message by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    // 本地镜像一旦存在即可启动，不等待后台检查/下载完成——资产检查与用户启动异步
    var localReady by remember { mutableStateOf(MsDosAssetDownloader.hasUsableDisk()) }

    suspend fun ensure(force: Boolean) {
        MsDosAssetDownloader.ensureDisk(force) { p, value, msg ->
            phase = p
            progress = value
            message = msg
            // 背景检查期间本地镜像若已就位，立即可解锁启动
            if (MsDosAssetDownloader.hasUsableDisk()) localReady = true
        }
    }

    LaunchedEffect(Unit) {
        localReady = MsDosAssetDownloader.hasUsableDisk()
        ensure(false)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.msdos_title),
                backdrop = glassPage.backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixGlassIcons.ChevronBackward,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(glassPage.contentModifier)
                .background(MiuixTheme.colorScheme.surface)
                .padding(pagePaddingWithoutTop(padding))
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = standaloneContentPadding(padding, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(key = "section_runtime") {
                SmallTitle(text = stringResource(R.string.msdos_section_runtime))
            }
            item(key = "card_runtime") {
                SettingCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val status = when (phase) {
                            MsDosAssetDownloader.Phase.CHECKING -> stringResource(R.string.msdos_asset_checking)
                            MsDosAssetDownloader.Phase.READY -> stringResource(R.string.msdos_asset_ready)
                            MsDosAssetDownloader.Phase.DOWNLOADING -> stringResource(R.string.msdos_asset_downloading) +
                                if (progress >= 0f) " ${(progress * 100).toInt()}%" else ""
                            MsDosAssetDownloader.Phase.EXTRACTING -> stringResource(R.string.msdos_asset_extracting)
                            MsDosAssetDownloader.Phase.FAILED -> if (message.isNotBlank()) {
                                stringResource(
                                    R.string.msdos_asset_failed_detail,
                                    stringResource(R.string.msdos_asset_failed),
                                    message
                                )
                            } else {
                                stringResource(R.string.msdos_asset_failed)
                            }
                        }
                        Text(
                            text = status,
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        if (phase == MsDosAssetDownloader.Phase.DOWNLOADING && progress >= 0f) {
                            Spacer(Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progress)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MiuixTheme.colorScheme.primary)
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = stringResource(R.string.msdos_asset_redownload),
                                onClick = { scope.launch { ensure(true) } },
                                enabled = phase != MsDosAssetDownloader.Phase.CHECKING &&
                                    phase != MsDosAssetDownloader.Phase.DOWNLOADING &&
                                    phase != MsDosAssetDownloader.Phase.EXTRACTING
                            )
                            TextButton(
                                text = stringResource(R.string.msdos_asset_manual),
                                onClick = onOpenDownloadPage
                            )
                        }
                    }
                }
            }

            item(key = "section_launch") {
                SmallTitle(text = stringResource(R.string.msdos_section_launch))
            }
            item(key = "card_launch") {
                val ready = localReady
                Column {
                    LaunchCard(
                        enabled = ready,
                        iconRes = R.drawable.ic_terminal,
                        title = stringResource(R.string.msdos_mode_console),
                        summary = stringResource(R.string.msdos_mode_console_desc),
                        onClick = { onLaunch(MsDosLauncher.Mode.CONSOLE) }
                    )
                    LaunchCard(
                        enabled = ready,
                        iconRes = R.drawable.ic_server,
                        title = stringResource(R.string.msdos_mode_vnc),
                        summary = stringResource(R.string.msdos_mode_vnc_desc),
                        onClick = { onLaunch(MsDosLauncher.Mode.VNC) }
                    )
                }
            }

            item(key = "section_mount") {
                SmallTitle(text = stringResource(R.string.msdos_section_mount))
            }
            item(key = "card_mount") {
                SettingCard {
                    Column {
                        SwitchPreference(
                            title = stringResource(R.string.msdos_mount_enable),
                            summary = stringResource(R.string.msdos_mount_enable_desc),
                            checked = config.mountEnabled,
                            onCheckedChange = { enabled ->
                                MsDosPrefs.update(context) { it.copy(mountEnabled = enabled) }
                                if (enabled && config.mountDir.isBlank()) showPicker = true
                            }
                        )
                        PreferenceDivider()
                        ArrowPreference(
                            title = stringResource(R.string.msdos_mount_pick),
                            summary = if (config.mountDir.isBlank()) {
                                stringResource(R.string.msdos_mount_pick_desc)
                            } else {
                                config.mountDir
                            },
                            onClick = { showPicker = true }
                        )
                        PreferenceDivider()
                        Text(
                            text = stringResource(R.string.msdos_port),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp)
                        )
                        MsDosPrefs.PORT_OPTIONS.forEachIndexed { index, port ->
                            RadioButtonPreference(
                                title = "$port",
                                summary = if (index == 0) stringResource(R.string.msdos_port_desc) else "",
                                selected = config.vncPort == port,
                                onClick = { MsDosPrefs.update(context) { it.copy(vncPort = port) } }
                            )
                        }
                    }
                }
            }

            item(key = "card_hint") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Text(
                        text = stringResource(R.string.msdos_hint),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        TermuxInternalFilePicker(
            show = showPicker,
            title = stringResource(R.string.msdos_mount_pick),
            startDir = if (config.mountDir.isNotBlank() && java.io.File(config.mountDir).isDirectory) {
                config.mountDir
            } else {
                TERMUX_HOME_ABS
            },
            allowFolders = true,
            onDismiss = { showPicker = false },
            onFileSelected = { path ->
                if (java.io.File(path).isDirectory) {
                    MsDosPrefs.update(context) { it.copy(mountDir = path, mountEnabled = true) }
                    showPicker = false
                } else {
                    Toast.makeText(context, R.string.msdos_mount_invalid, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
private fun LaunchCard(
    enabled: Boolean,
    iconRes: Int,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MiuixTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.5f)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f)
                )
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
