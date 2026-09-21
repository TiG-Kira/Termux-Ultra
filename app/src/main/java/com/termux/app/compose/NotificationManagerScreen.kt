package com.termux.app.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 通知管理设置页。
 *
 * 第一组「通知」：终端 / Agent / 软件包 三个开关。
 * 第二组「通知方式」：普通 / LiveUpdate 二选一。
 */
@Composable
fun NotificationManagerScreen(
    onBack: () -> Unit,
    navBarBottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()

    var terminalEnabled by remember {
        mutableStateOf(NotificationPrefs.isTerminalEnabled(context))
    }
    var agentEnabled by remember {
        mutableStateOf(NotificationPrefs.isAgentEnabled(context))
    }
    var packageEnabled by remember {
        mutableStateOf(NotificationPrefs.isPackageEnabled(context))
    }

    val modeOptions = listOf(
        NotificationPrefs.MODE_NORMAL to stringResource(R.string.notification_mode_normal),
        NotificationPrefs.MODE_LIVE_UPDATE to stringResource(R.string.notification_mode_live_update),
    )
    val modeSummaries = listOf(
        stringResource(R.string.notification_mode_normal_desc),
        stringResource(R.string.notification_mode_live_update_desc),
    )

    var currentMode by remember {
        mutableStateOf(NotificationPrefs.getMode(context))
    }

    val liveUpdateAvailable = remember { NotificationPrefs.isLiveUpdateAvailable() }

    val disabledModes = remember {
        buildSet {
            if (!liveUpdateAvailable) add(NotificationPrefs.MODE_LIVE_UPDATE)
        }
    }

    val currentModeIndex = modeOptions.indexOfFirst { it.first == currentMode }.coerceAtLeast(0)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.notification_management),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(40.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.width(24.dp).height(24.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = navBarBottomPadding + 16.dp
            )
        ) {
            // ===== 第一组：通知开关 =====
            item(key = "section_notifications") {
                SmallTitle(text = stringResource(R.string.notification_category))
            }
            item(key = "card_notifications") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column {
                        SwitchPreference(
                            title = stringResource(R.string.notification_terminal),
                            summary = stringResource(R.string.notification_terminal_desc),
                            checked = terminalEnabled,
                            onCheckedChange = {
                                terminalEnabled = it
                                NotificationPrefs.setTerminalEnabled(context, it)
                            }
                        )
                        SwitchPreference(
                            title = stringResource(R.string.notification_agent),
                            summary = stringResource(R.string.notification_agent_desc),
                            checked = agentEnabled,
                            onCheckedChange = {
                                agentEnabled = it
                                NotificationPrefs.setAgentEnabled(context, it)
                            }
                        )
                        SwitchPreference(
                            title = stringResource(R.string.notification_package),
                            summary = stringResource(R.string.notification_package_desc),
                            checked = packageEnabled,
                            onCheckedChange = {
                                packageEnabled = it
                                NotificationPrefs.setPackageEnabled(context, it)
                            }
                        )
                    }
                }
            }

            // ===== 第二组：通知方式 =====
            item(key = "section_mode") {
                SmallTitle(text = stringResource(R.string.notification_mode_category))
            }
            item(key = "card_mode") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column {
                        modeOptions.forEachIndexed { index, (modeKey, modeLabel) ->
                            val isDisabled = modeKey in disabledModes
                            val displayLabel = if (isDisabled) {
                                modeLabel + stringResource(R.string.notification_mode_not_available)
                            } else {
                                modeLabel
                            }
                            RadioButtonPreference(
                                title = displayLabel,
                                summary = modeSummaries[index],
                                selected = currentModeIndex == index,
                                onClick = {
                                    if (!isDisabled) {
                                        currentMode = modeKey
                                        NotificationPrefs.setMode(context, modeKey)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // ===== 提示卡片 =====
            item(key = "card_hint") {
                val hintText = when (currentMode) {
                    NotificationPrefs.MODE_LIVE_UPDATE ->
                        stringResource(R.string.notification_live_update_hint)
                    else -> null
                }
                if (hintText != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "ℹ",
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.primary
                            )
                            Text(
                                text = hintText,
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
