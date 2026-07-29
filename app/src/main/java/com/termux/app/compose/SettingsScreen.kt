package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material3.LinearProgressIndicator
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
    var launchRestore by remember { mutableStateOf(false) }
    var showRestartPrompt by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var vncEnabled by remember { mutableStateOf(prefs.getBoolean("vnc_enabled", false)) }

    val languageOptions = listOf(
        context.getString(R.string.chinese),
        context.getString(R.string.english)
    )
    var languageSelectedIndex by remember {
        mutableStateOf(if (LocaleHelper.isChinese(context)) 0 else 1)
    }

    val scrollBehavior = MiuixScrollBehavior()

    val restoreFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            if (!isProcessing) {
                isProcessing = true
                android.widget.Toast.makeText(context, "正在恢复，请在通知栏查看进度", android.widget.Toast.LENGTH_SHORT).show()
                NotificationHelper.createNotificationChannel(context)
                
                val cancelIntent = Intent("com.termux.RESTORE_CANCEL")
                val pendingCancelIntent = PendingIntent.getBroadcast(context, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                
                val cancelReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        BackupManager.cancelRestore()
                    }
                }
                context.registerReceiver(cancelReceiver, IntentFilter("com.termux.RESTORE_CANCEL"))
                
                NotificationHelper.showProgressNotification(context, "正在恢复", 0, 100, "初始化...", pendingCancelIntent)
                val mainHandler = Handler(Looper.getMainLooper())
                Thread {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val tempFile = File(context.cacheDir, "temp_backup.zip")
                    inputStream?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    val result = BackupManager.restoreBackup(context, tempFile.absolutePath) { processed, total, message ->
                        val progress = if (total > 0) (processed * 100 / total) else 0
                        mainHandler.post {
                            NotificationHelper.showProgressNotification(context, "正在恢复", progress, 100, message, pendingCancelIntent)
                        }
                    }
                    tempFile.delete()
                    
                    mainHandler.post {
                        isProcessing = false
                        context.unregisterReceiver(cancelReceiver)
                        if (result) {
                            NotificationHelper.showCompleteNotification(context, "恢复完成", "备份已成功恢复", true)
                        } else {
                            NotificationHelper.showCompleteNotification(context, "恢复失败", "恢复过程中出现错误", false)
                        }
                    }
                }.start()
            }
        }
    }

    LaunchedEffect(launchRestore) {
        if (launchRestore) {
            restoreFileLauncher.launch(arrayOf("application/zip"))
            launchRestore = false
        }
    }

    // ArrowPreference-only settings (language and VNC switch are handled inline with Miuix components)
    val remoteSettings = if (vncEnabled) listOf(
        SettingItem(
            title = context.getString(R.string.vnc_settings),
            description = context.getString(R.string.vnc_settings_desc),
            iconRes = R.drawable.ic_vnc_settings,
            action = {
                val intent = Intent(context, com.gaurav.avnc.ui.prefs.PrefsActivity::class.java)
                context.startActivity(intent)
            }
        )
    ) else emptyList()

    val dataSettings = listOf(
        SettingItem(
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
        ),
        SettingItem(
            title = context.getString(R.string.restore),
            description = context.getString(R.string.restore_description),
            iconRes = R.drawable.ic_restore,
            action = {
                launchRestore = true
            }
        )
    )

    val systemSettings = listOf(
        SettingItem(
            title = context.getString(R.string.termux_settings),
            description = context.getString(R.string.termux_settings_description),
            iconRes = R.drawable.ic_settings,
            action = {
                val intent = Intent(context, SettingsActivity::class.java)
                context.startActivity(intent)
            }
        ),
        SettingItem(
            title = context.getString(R.string.about_preference_title),
            description = context.getString(R.string.about_description),
            iconRes = R.drawable.ic_info,
            action = { onAboutClick() }
        )
    )

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
            // ---------- Appearance ----------
            item { SmallTitle(text = context.getString(R.string.appearance)) }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    OverlayDropdownPreference(
                        title = context.getString(R.string.language),
                        summary = context.getString(R.string.language_description),
                        items = languageOptions,
                        selectedIndex = languageSelectedIndex,
                        onSelectedIndexChange = { idx ->
                            languageSelectedIndex = idx
                            // Apply without restarting; show prompt so users restart.
                            if (idx == 0) {
                                LocaleHelper.setChinese(context)
                            } else {
                                LocaleHelper.setEnglish(context)
                            }
                            showRestartPrompt = true
                        },
                        startAction = {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MiuixTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_language),
                                    contentDescription = context.getString(R.string.language),
                                    modifier = Modifier.size(24.dp),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    )
                }
            }

            // ---------- Remote ----------
            item { SmallTitle(text = context.getString(R.string.remote)) }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    SwitchPreference(
                        title = context.getString(R.string.vnc),
                        summary = context.getString(R.string.vnc_description),
                        checked = vncEnabled,
                        onCheckedChange = {
                            vncEnabled = it
                            prefs.edit().putBoolean("vnc_enabled", it).apply()
                            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            context.startActivity(intent)
                        },
                        startAction = {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MiuixTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_vnc),
                                    contentDescription = context.getString(R.string.vnc),
                                    modifier = Modifier.size(24.dp),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    )
                }
            }
            items(remoteSettings) { SettingItemCard(it) }

            // ---------- Data Backup ----------
            item { SmallTitle(text = context.getString(R.string.backup_category)) }
            items(dataSettings) { SettingItemCard(it) }

            // ---------- System ----------
            item { SmallTitle(text = context.getString(R.string.system_category)) }
            items(systemSettings) { SettingItemCard(it) }
        }
    }

    // ---------- Language restart prompt ----------
    OverlayDialog(
        title = context.getString(R.string.restart_required),
        summary = context.getString(R.string.language_restart_message),
        show = showRestartPrompt,
        onDismissRequest = { showRestartPrompt = false }
    ) {
        TextButton(
            text = context.getString(R.string.ok),
            onClick = { showRestartPrompt = false },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // ---------- Restore: choose backup file ----------
    OverlayDialog(
        title = context.getString(R.string.restore),
        show = showRestoreDialog,
        onDismissRequest = { showRestoreDialog = false }
    ) {
        Column(modifier = Modifier.heightIn(max = 300.dp)) {
            if (backupFiles.isEmpty()) {
                Text(
                    text = context.getString(R.string.no_backup_files),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
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
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = context.getString(R.string.cancel),
                onClick = { showRestoreDialog = false },
                modifier = Modifier.weight(1f)
            )
        }
    }

    // ---------- Restore: confirm ----------
    OverlayDialog(
        title = context.getString(R.string.restore),
        summary = context.getString(R.string.restore_confirm_message),
        show = showRestoreConfirmDialog,
        onDismissRequest = { showRestoreConfirmDialog = false }
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = context.getString(R.string.cancel),
                onClick = { showRestoreConfirmDialog = false },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = context.getString(R.string.confirm),
                onClick = {
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
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }

    // ---------- Restore: progress ----------
    OverlayDialog(
        title = context.getString(R.string.restore),
        summary = restoreMessage,
        show = showRestoreProgressDialog,
        onDismissRequest = { BackupManager.cancelRestore() }
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            LinearProgressIndicator(
                progress = { restoreProgress.toFloat() / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "$restoreProgress%",
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = context.getString(R.string.cancel),
            onClick = { BackupManager.cancelRestore() },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // ---------- Result ----------
    OverlayDialog(
        title = context.getString(R.string.result),
        summary = resultMessage,
        show = showResultDialog,
        onDismissRequest = { showResultDialog = false }
    ) {
        TextButton(
            text = context.getString(R.string.ok),
            onClick = { showResultDialog = false },
            modifier = Modifier.fillMaxWidth()
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
    ) {
        if (item.hasSwitch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable(onClick = item.action),
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
                Switch(
                    checked = item.switchValue,
                    onCheckedChange = item.onSwitchChange
                )
            }
        } else {
            top.yukonga.miuix.kmp.preference.ArrowPreference(
                title = item.title,
                summary = item.description,
                onClick = item.action,
                startAction = {
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
                }
            )
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