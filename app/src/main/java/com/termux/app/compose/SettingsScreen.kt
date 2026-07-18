package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
import androidx.compose.material3.LinearProgressIndicator
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var showRestoreProgressDialog by remember { mutableStateOf(false) }
    var selectedBackupFile by remember { mutableStateOf<File?>(null) }
    var backupFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var restoreProgress by remember { mutableStateOf(0) }
    var restoreTotal by remember { mutableStateOf(100) }
    var restoreMessage by remember { mutableStateOf("") }
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
            title = context.getString(R.string.backup),
            description = context.getString(R.string.backup_description),
            iconRes = R.drawable.ic_backup,
            action = {
                if (!isProcessing) {
                    isProcessing = true
                    android.widget.Toast.makeText(context, "正在备份，请在通知栏查看进度", android.widget.Toast.LENGTH_SHORT).show()
                    NotificationHelper.createNotificationChannel(context)
                    
                    val cancelIntent = Intent("com.termux.BACKUP_CANCEL")
                    val pendingCancelIntent = PendingIntent.getBroadcast(context, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    
                    val cancelReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            BackupManager.cancelBackup()
                        }
                    }
                    context.registerReceiver(cancelReceiver, IntentFilter("com.termux.BACKUP_CANCEL"))
                    
                    NotificationHelper.showProgressNotification(context, "正在备份", 0, 100, "初始化...", pendingCancelIntent)
                    val mainHandler = Handler(Looper.getMainLooper())
                    Thread {
                        val backupPath = BackupManager.createBackup(context) { processed, total, message ->
                            val progress = if (total > 0) (processed * 100 / total) else 0
                            mainHandler.post {
                                NotificationHelper.showProgressNotification(context, "正在备份", progress, 100, message, pendingCancelIntent)
                            }
                        }
                        mainHandler.post {
                            isProcessing = false
                            context.unregisterReceiver(cancelReceiver)
                            if (backupPath != null) {
                                NotificationHelper.showCompleteNotification(context, "备份完成", backupPath, true)
                            } else {
                                NotificationHelper.showCompleteNotification(context, "备份取消", "备份已取消", false)
                            }
                        }
                    }.start()
                }
            }
        ))
        add(SettingItem(
            title = context.getString(R.string.restore),
            description = context.getString(R.string.restore_description),
            iconRes = R.drawable.ic_restore,
            action = {
                backupFiles = BackupManager.getBackupFiles(context)
                showRestoreDialog = true
            }
        ))
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

    if (showRestoreDialog) {
        AlertDialog(
            title = { Text(context.getString(R.string.restore)) },
            onDismissRequest = { showRestoreDialog = false },
            confirmButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text(context.getString(R.string.cancel))
                }
            },
            text = {
                Column(modifier = Modifier.heightIn(max = 300.dp)) {
                    if (backupFiles.isEmpty()) {
                        Text(context.getString(R.string.no_backup_files))
                    } else {
                        backupFiles.forEach { file ->
                            Text(
                                text = file.name,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable {
                                        selectedBackupFile = file
                                        showRestoreDialog = false
                                        showRestoreConfirmDialog = true
                                    },
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        )
    }

    if (showRestoreConfirmDialog) {
        AlertDialog(
            title = { Text(context.getString(R.string.restore)) },
            onDismissRequest = { showRestoreConfirmDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirmDialog = false
                    selectedBackupFile?.let { file ->
                        if (!isProcessing) {
                            isProcessing = true
                            restoreProgress = 0
                            restoreMessage = "初始化..."
                            showRestoreProgressDialog = true
                            val mainHandler = Handler(Looper.getMainLooper())
                            Thread {
                                val success = BackupManager.restoreBackup(context, file.absolutePath) { processed, total, message ->
                                    restoreTotal = total
                                    val progress = if (total > 0) (processed * 100 / total) else 0
                                    mainHandler.post {
                                        restoreProgress = progress
                                        restoreMessage = message
                                    }
                                }
                                mainHandler.post {
                                    isProcessing = false
                                    showRestoreProgressDialog = false
                                    if (success) {
                                        android.widget.Toast.makeText(context, "恢复完成", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "恢复失败或已取消", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.start()
                        }
                    }
                }) {
                    Text(context.getString(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = false }) {
                    Text(context.getString(R.string.cancel))
                }
            },
            text = {
                Text(context.getString(R.string.restore_confirm_message))
            }
        )
    }

    if (showRestoreProgressDialog) {
        AlertDialog(
            title = { Text(context.getString(R.string.restore)) },
            onDismissRequest = {
                BackupManager.cancelRestore()
            },
            confirmButton = {
                TextButton(onClick = {
                    BackupManager.cancelRestore()
                }) {
                    Text(context.getString(R.string.cancel))
                }
            },
            text = {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = restoreMessage,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    LinearProgressIndicator(
                        progress = { restoreProgress.toFloat() / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "$restoreProgress%",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        )
    }

    if (showResultDialog) {
        AlertDialog(
            title = { Text(context.getString(R.string.result)) },
            onDismissRequest = { showResultDialog = false },
            confirmButton = {
                TextButton(onClick = { showResultDialog = false }) {
                    Text(context.getString(R.string.ok))
                }
            },
            text = {
                Text(resultMessage)
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