package com.termux.app.compose

import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.shared.logger.Logger
import com.termux.shared.android.PackageUtils
import com.termux.shared.termux.TermuxConstants
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.app.compose.pagePaddingWithoutTop

@Composable
fun TermuxWidgetScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 本页在 MainScreen 取景层之外，自建一层供玻璃顶栏折射页面内容
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    var showDisableDialog by remember { mutableStateOf(false) }
    var isLauncherDisabled by remember { mutableStateOf(false) }

    Scaffold(
        modifier = glassPage.layerModifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.title_widget_settings),
                backdrop = glassPage.backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = { onBack() }) {
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
                .padding(pagePaddingWithoutTop(padding))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.plugin_info,
                                TermuxConstants.TERMUX_GITHUB_REPO_URL,
                                TermuxConstants.TERMUX_WIDGET_GITHUB_REPO_URL
                            ),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            lineHeight = 20.sp
                        )
                    }
                }

                item {
                    SettingCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.msg_disable_launcher_icon_details),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }

                item {
                    SettingCard {
                        ArrowPreference(
                            title = stringResource(R.string.action_disable_launcher_icon),
                            summary = if (isLauncherDisabled)
                                stringResource(R.string.launcher_icon_disabled)
                            else null,
                            onClick = { showDisableDialog = true },
                            startAction = {
                                SettingIcon(R.drawable.ic_star)
                            }
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            OverlayDialog(
                show = showDisableDialog,
                onDismissRequest = { showDisableDialog = false },
                content = {
                    DisableLauncherContent(
                        onConfirm = {
                            disableLauncherIcon(context)
                            isLauncherDisabled = true
                            showDisableDialog = false
                        },
                        onDismiss = { showDisableDialog = false }
                    )
                }
            )
        }
    }
}

private fun disableLauncherIcon(context: Context) {
    val message = context.getString(R.string.msg_disabling_launcher_icon, TermuxConstants.TERMUX_WIDGET_APP_NAME)
    Logger.logInfo("TermuxWidget", message)
    runCatching {
        PackageUtils.setComponentState(
            context,
            context.packageName,
            TermuxConstants.TERMUX_WIDGET.TERMUX_WIDGET_ACTIVITY_NAME,
            false,
            message,
            true
        )
    }
}

@Composable
private fun DisableLauncherContent(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.disable_launcher_icon_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.disable_launcher_icon_message),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = stringResource(R.string.confirm),
                onClick = onConfirm
            )
        }
    }
}
