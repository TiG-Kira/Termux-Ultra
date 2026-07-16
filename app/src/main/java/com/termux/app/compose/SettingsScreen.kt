package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import com.termux.app.LocaleHelper
import com.termux.app.activities.SettingsActivity

data class SettingItem(
    val title: String,
    val description: String,
    val iconRes: Int,
    val action: () -> Unit,
    val hasSwitch: Boolean = false,
    val switchValue: Boolean = false,
    val onSwitchChange: (Boolean) -> Unit = {}
)

@Composable
fun SettingsScreen(onAboutClick: () -> Unit) {
    val context = LocalContext.current
    var showLanguageDialog by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var vncEnabled by remember { mutableStateOf(prefs.getBoolean("vnc_enabled", false)) }

    val scrollBehavior = MiuixScrollBehavior()

    val settings = mutableListOf<SettingItem>().apply {
        add(SettingItem(
            title = context.getString(R.string.language),
            description = context.getString(R.string.language_description),
            iconRes = R.drawable.ic_language,
            action = { showLanguageDialog = true }
        ))
        add(SettingItem(
            title = context.getString(R.string.vnc),
            description = context.getString(R.string.vnc_description),
            iconRes = R.drawable.ic_vnc,
            action = {},
            hasSwitch = true,
            switchValue = vncEnabled,
            onSwitchChange = {
                vncEnabled = it
                prefs.edit().putBoolean("vnc_enabled", it).apply()
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
            }
        ))
        if (vncEnabled) {
            add(SettingItem(
                title = context.getString(R.string.vnc_settings),
                description = context.getString(R.string.vnc_settings_desc),
                iconRes = R.drawable.ic_vnc_settings,
                action = {
                    val intent = Intent(context, com.gaurav.avnc.ui.prefs.PrefsActivity::class.java)
                    context.startActivity(intent)
                }
            ))
        }
        add(SettingItem(
            title = context.getString(R.string.termux_settings),
            description = context.getString(R.string.termux_settings_description),
            iconRes = R.drawable.ic_settings,
            action = {
                val intent = Intent(context, SettingsActivity::class.java)
                context.startActivity(intent)
            }
        ))
        add(SettingItem(
            title = context.getString(R.string.about_preference_title),
            description = context.getString(R.string.about_description),
            iconRes = R.drawable.ic_info,
            action = { onAboutClick() }
        ))
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = context.getString(R.string.settings_title), scrollBehavior = scrollBehavior)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            items(settings) { item ->
                SettingItemCard(item = item)
            }
        }
    }

    if (showLanguageDialog) {
        AlertDialog(
            title = { Text(context.getString(R.string.language)) },
            onDismissRequest = { showLanguageDialog = false },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(context.getString(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(context.getString(R.string.cancel))
                }
            },
            text = {
                Column {
                    LanguageOption(context.getString(R.string.english), "en", context)
                    LanguageOption(context.getString(R.string.chinese), "zh", context)
                }
            }
        )
    }
}

@Composable
private fun SettingItemCard(item: SettingItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = item.action)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.title,
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onSurface
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = item.title,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = item.description,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            if (item.hasSwitch) {
                Switch(
                    checked = item.switchValue,
                    onCheckedChange = item.onSwitchChange
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

@Composable
private fun LanguageOption(name: String, code: String, context: Context) {
    Text(
        text = name,
        fontSize = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable {
                if (code == "zh") {
                    LocaleHelper.setChinese(context)
                } else {
                    LocaleHelper.setEnglish(context)
                }
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
            },
        color = MiuixTheme.colorScheme.onSurface
    )
}