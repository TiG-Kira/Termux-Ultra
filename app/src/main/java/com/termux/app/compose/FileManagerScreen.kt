package com.termux.app.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.activity.compose.BackHandler
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.termux.shared.shell.TermuxShellUtils
import com.termux.app.ftp.FtpServer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.ui.graphics.Color
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
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import java.io.File
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

enum class ClipboardMode {
    NONE, COPY, CUT
}

private const val ROOT_PATH = "/data/data/com.termux"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(
    onOpenFile: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf(File("/data/data/com.termux/files")) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isInSelectionMode by remember { mutableStateOf(false) }
    var clipboardMode by remember { mutableStateOf(ClipboardMode.NONE) }
    var clipboardFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileInputName by remember { mutableStateOf("") }
    var showNewTypeDialog by remember { mutableStateOf(false) }
    var showWarningCard by remember { mutableStateOf(false) }
    var showOpenWithDialog by remember { mutableStateOf(false) }
    var fileToOpen by remember { mutableStateOf<File?>(null) }
    var forwardHistory by remember { mutableStateOf<List<File>>(emptyList()) }
    var isSftpEnabled by remember { mutableStateOf(false) }
    var sftpNotificationId = 1001
    var sftpChannelId = "sftp_service"
    var sftpPort by remember { mutableStateOf(8021) }
    var sftpUsername by remember { mutableStateOf("") }
    var sftpPassword by remember { mutableStateOf("") }
    var isFileRefreshing by remember { mutableStateOf(false) }
    var showOperationProgress by remember { mutableStateOf(false) }
    var operationProgressText by remember { mutableStateOf("") }
    var operationProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(currentPath) {
        files = currentPath.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
        selectedFiles = emptySet()
        isInSelectionMode = false
    }

    fun isPortInUse(port: Int): Boolean {
        try {
            java.net.Socket("127.0.0.1", port).use {
                it.close()
                return true
            }
        } catch (e: java.net.ConnectException) {
            return false
        } catch (e: Exception) {
            return false
        }
    }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("files_warning_shown", false)) {
            showWarningCard = true
        }
        sftpUsername = prefs.getString("sftp_username", "termux") ?: "termux"
        sftpPassword = prefs.getString("sftp_password", "termux123") ?: "termux123"
        sftpPort = prefs.getInt("sftp_port", 8021)

        val appPrefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val ftpEnabled = appPrefs.getBoolean("ftp_enabled", false)
        val ftpRunning = com.termux.app.ftp.FtpServiceManager.isRunning()

        if (ftpEnabled && !ftpRunning) {
            com.termux.app.ftp.FtpServiceManager.start(context)
        }

        isSftpEnabled = com.termux.app.ftp.FtpServiceManager.isRunning()
        if (!isSftpEnabled && ftpEnabled) {
            appPrefs.edit().putBoolean("ftp_enabled", false).apply()
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    val canGoUp = currentPath.parentFile != null && !currentPath.absolutePath.equals(ROOT_PATH)

    BackHandler(enabled = canGoUp) {
        forwardHistory = forwardHistory + currentPath
        currentPath = currentPath.parentFile!!
    }

    fun refreshFiles() {
        files = currentPath.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "FTP 服务"
            val descriptionText = "FTP 服务通知"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(sftpChannelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    fun showSftpNotification() {
        createNotificationChannel()
        val ipAddress = getLocalIpAddress()

        val intent = android.content.Intent(context, com.termux.app.ftp.FtpInfoActivity::class.java)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, sftpChannelId)
            .setContentTitle("正在使用 FTP 服务")
            .setContentText("地址: ftp://$ipAddress:$sftpPort\n点击通知显示 FTP 详情")
            .setSmallIcon(R.drawable.ic_web)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(sftpNotificationId, notification)
    }

    fun hideSftpNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(sftpNotificationId)
    }

    fun saveSftpCredentials() {
        val prefs = context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("sftp_username", sftpUsername)
            .putString("sftp_password", sftpPassword)
            .apply()
        if (com.termux.app.ftp.FtpServiceManager.isRunning()) {
            com.termux.app.ftp.FtpServiceManager.restartWithNewConfig(context)
        }
    }

    fun saveSftpPort() {
        val prefs = context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("sftp_port", sftpPort)
            .apply()
        if (com.termux.app.ftp.FtpServiceManager.isRunning()) {
            com.termux.app.ftp.FtpServiceManager.restartWithNewConfig(context)
            hideSftpNotification()
            showSftpNotification()
        }
    }

    fun toggleSftp() {
        val newState = !isSftpEnabled
        if (newState) {
            val started = com.termux.app.ftp.FtpServiceManager.start(context)
            isSftpEnabled = started
            if (started) {
                showSftpNotification()
            }
        } else {
            com.termux.app.ftp.FtpServiceManager.stop(context)
            isSftpEnabled = false
            hideSftpNotification()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = if (isInSelectionMode) {
                    "${selectedFiles.size} ${stringResource(R.string.items)}"
                } else {
                    stringResource(R.string.files_title)
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (isInSelectionMode) {
                        Row {
                            IconButton(onClick = {
                                selectedFiles = emptySet()
                                isInSelectionMode = false
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    } else {
                        Row {
                            IconButton(onClick = {
                                if (canGoUp) {
                                    forwardHistory = forwardHistory + currentPath
                                    currentPath = currentPath.parentFile!!
                                }
                            }, enabled = canGoUp) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_up),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (canGoUp) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            IconButton(onClick = {
                                if (forwardHistory.isNotEmpty()) {
                                    val nextPath = forwardHistory.last()
                                    forwardHistory = forwardHistory.dropLast(1)
                                    currentPath = nextPath
                                }
                            }, enabled = forwardHistory.isNotEmpty()) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_down),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (forwardHistory.isNotEmpty()) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                },
                actions = {
                    Row {
                    if (isInSelectionMode && selectedFiles.isNotEmpty()) {
                        IconButton(onClick = {
                            clipboardMode = ClipboardMode.COPY
                            clipboardFiles = selectedFiles.toSet()
                            selectedFiles = emptySet()
                            isInSelectionMode = false
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = stringResource(R.string.copy),
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = {
                            clipboardMode = ClipboardMode.CUT
                            clipboardFiles = selectedFiles.toSet()
                            selectedFiles = emptySet()
                            isInSelectionMode = false
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_cut),
                                contentDescription = stringResource(R.string.cut),
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        if (selectedFiles.size == 1) {
                            IconButton(onClick = {
                                newFileName = File(selectedFiles.first()).name
                                showRenameDialog = true
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_edit),
                                    contentDescription = stringResource(R.string.rename),
                                    modifier = Modifier.size(24.dp),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                        IconButton(onClick = {
                            showDeleteDialog = true
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.delete),
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (clipboardMode != ClipboardMode.NONE && clipboardFiles.isNotEmpty()) {
                        IconButton(onClick = {
                            showOperationProgress = true
                            operationProgressText = if (clipboardMode == ClipboardMode.CUT) "移动中..." else "复制中..."
                            operationProgress = 0f
                            val filesToProcess = clipboardFiles.toList()
                            val modeAtStart = clipboardMode
                            val targetPath = currentPath
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                filesToProcess.forEachIndexed { index, srcPath ->
                                    val srcFile = File(srcPath)
                                    val destFile = File(targetPath, srcFile.name)
                                    if (modeAtStart == ClipboardMode.CUT) {
                                        moveFile(srcFile, destFile)
                                    } else {
                                        copyFile(srcFile, destFile)
                                    }
                                    operationProgress = (index + 1).toFloat() / filesToProcess.size
                                }
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    clipboardMode = ClipboardMode.NONE
                                    clipboardFiles = emptySet()
                                    refreshFiles()
                                    showOperationProgress = false
                                }
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_paste),
                                contentDescription = stringResource(R.string.paste),
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (!isInSelectionMode) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                com.termux.app.ftp.FtpInfoActivity.start(context)
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_web),
                                    contentDescription = "FTP 信息",
                                    modifier = Modifier.size(20.dp),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            top.yukonga.miuix.kmp.basic.Switch(
                                checked = isSftpEnabled,
                                onCheckedChange = { toggleSftp() }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = {
                                showNewTypeDialog = true
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_add),
                                    contentDescription = stringResource(R.string.folder),
                                    modifier = Modifier.size(24.dp),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showWarningCard) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSystemInDarkTheme()) Color(0xFF2C2C2C) else Color.White
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.files_warning_message),
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.ok),
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable {
                                        showWarningCard = false
                                        val prefs = context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
                                        prefs.edit().putBoolean("files_warning_shown", true).apply()
                                    },
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSystemInDarkTheme()) Color(0xFF2C2C2C) else Color.White
                    )
                ) {
                    Text(
                        text = currentPath.absolutePath,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (files.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 100.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.empty_folder),
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            } else {
                items(files) { fileItem ->
                    FileItem(
                        file = fileItem,
                        isSelected = selectedFiles.contains(fileItem.absolutePath),
                        isInSelectionMode = isInSelectionMode,
                        onClick = {
                            if (isInSelectionMode) {
                                selectedFiles = if (selectedFiles.contains(fileItem.absolutePath)) {
                                    selectedFiles - fileItem.absolutePath
                                } else {
                                    selectedFiles + fileItem.absolutePath
                                }
                                if (selectedFiles.isEmpty()) isInSelectionMode = false
                            } else {
                                if (fileItem.isDirectory) {
                                    forwardHistory = emptyList()
                                    currentPath = fileItem
                                } else {
                                    fileToOpen = fileItem
                                    showOpenWithDialog = true
                                }
                            }
                        },
                        onLongClick = {
                            if (isInSelectionMode) {
                                selectedFiles = if (selectedFiles.contains(fileItem.absolutePath)) {
                                    selectedFiles - fileItem.absolutePath
                                } else {
                                    selectedFiles + fileItem.absolutePath
                                }
                            } else {
                                selectedFiles = setOf(fileItem.absolutePath)
                                isInSelectionMode = true
                            }
                        }
                    )
                }
            }
        }
    }

    if (showOpenWithDialog && fileToOpen != null) {
        val file = fileToOpen!!
        val isShFile = file.name.endsWith(".sh", ignoreCase = true)
        val dialogTextColor = MiuixTheme.colorScheme.onSurface

        OverlayBottomSheet(
            show = showOpenWithDialog,
            onDismissRequest = {
                showOpenWithDialog = false
                fileToOpen = null
            },
            title = file.name,
            content = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "大小: ${android.text.format.Formatter.formatFileSize(context, file.length())}",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    if (isShFile) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSystemInDarkTheme()) Color(0xFF3D3514) else Color(0xFFFFF9C4)
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.shell_script_warning),
                                fontSize = 12.sp,
                                color = dialogTextColor,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.select_open_method), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onOpenFile(file.absolutePath, "cat \"${file.absolutePath}\"")
                                showOpenWithDialog = false
                                fileToOpen = null
                            }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = dialogTextColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(stringResource(R.string.view_content), color = dialogTextColor)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val vimPath = "/data/data/com.termux/files/usr/bin/vim"
                                if (File(vimPath).exists()) {
                                    onOpenFile(file.absolutePath, "vi \"${file.absolutePath}\"")
                                } else {
                                    onOpenFile(file.absolutePath, "pkg install vim -y && vi \"${file.absolutePath}\"")
                                }
                                showOpenWithDialog = false
                                fileToOpen = null
                            }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = dialogTextColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(stringResource(R.string.edit_file), color = dialogTextColor)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (isShFile) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("exec_command", "bash \"${file.absolutePath}\"")
                                    clipboard.setPrimaryClip(clip)
                                    showOpenWithDialog = false
                                    fileToOpen = null
                                }
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = dialogTextColor
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(stringResource(R.string.copy_exec_command), color = dialogTextColor)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onOpenFile(file.absolutePath, "bash \"${file.absolutePath}\"")
                                showOpenWithDialog = false
                                fileToOpen = null
                            }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_terminal),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = dialogTextColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(stringResource(R.string.execute_bash), color = dialogTextColor)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("path", file.absolutePath)
                                clipboard.setPrimaryClip(clip)
                                showOpenWithDialog = false
                                fileToOpen = null
                            }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = dialogTextColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(stringResource(R.string.copy_path), color = dialogTextColor)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val uri = android.net.Uri.parse("content://com.termux.files" + file.absolutePath)
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                intent.setDataAndType(uri, "*/*")
                                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                val chooser = android.content.Intent.createChooser(intent, "选择应用打开")
                                context.startActivity(chooser)
                                showOpenWithDialog = false
                                fileToOpen = null
                            }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_launch),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = dialogTextColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("用其他方式打开", color = dialogTextColor)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = {
                            showOpenWithDialog = false
                            fileToOpen = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars
                                .only(WindowInsetsSides.Bottom)
                                .asPaddingValues()
                                .calculateBottomPadding()
                        )
                    )
                }
            }
        )
    }

    if (showDeleteDialog) {
        OverlayDialog(
            show = showDeleteDialog,
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(R.string.delete_confirm_title),
            summary = "${stringResource(R.string.delete_confirm_message)} (${selectedFiles.size})",
            content = {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { showDeleteDialog = false },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.delete_confirm),
                        onClick = {
                            showDeleteDialog = false
                            showOperationProgress = true
                            operationProgressText = "删除中..."
                            operationProgress = 0f
                            val filesToDelete = selectedFiles.toList()
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                filesToDelete.forEachIndexed { index, path ->
                                    File(path).deleteRecursively()
                                    operationProgress = (index + 1).toFloat() / filesToDelete.size
                                }
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    selectedFiles = emptySet()
                                    isInSelectionMode = false
                                    refreshFiles()
                                    showOperationProgress = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        )
    }

    if (showRenameDialog) {
        val renameFile = File(selectedFiles.first())
        OverlayDialog(
            show = showRenameDialog,
            onDismissRequest = { showRenameDialog = false },
            title = stringResource(R.string.rename),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = stringResource(R.string.file_name)
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showRenameDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = stringResource(R.string.ok),
                            onClick = {
                                val newFile = File(renameFile.parentFile, newFileName)
                                renameFile.renameTo(newFile)
                                selectedFiles = emptySet()
                                isInSelectionMode = false
                                refreshFiles()
                                showRenameDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        )
    }

    if (showNewFolderDialog) {
        OverlayDialog(
            show = showNewFolderDialog,
            onDismissRequest = { showNewFolderDialog = false },
            title = stringResource(R.string.folder),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = stringResource(R.string.file_name)
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showNewFolderDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = stringResource(R.string.ok),
                            onClick = {
                                showNewFolderDialog = false
                                showOperationProgress = true
                                operationProgressText = "创建文件夹中..."
                                operationProgress = 0f
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val newFolder = File(currentPath, newFolderName)
                                    newFolder.mkdirs()
                                    operationProgress = 1f
                                    kotlinx.coroutines.delay(200)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        refreshFiles()
                                        showOperationProgress = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        )
    }

    if (showNewFileDialog) {
        OverlayDialog(
            show = showNewFileDialog,
            onDismissRequest = { showNewFileDialog = false },
            title = "新建文件",
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        value = newFileInputName,
                        onValueChange = { newFileInputName = it },
                        label = stringResource(R.string.file_name)
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showNewFileDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = stringResource(R.string.ok),
                            onClick = {
                                showNewFileDialog = false
                                showOperationProgress = true
                                operationProgressText = "创建文件中..."
                                operationProgress = 0f
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val newFile = File(currentPath, newFileInputName)
                                    newFile.createNewFile()
                                    operationProgress = 1f
                                    kotlinx.coroutines.delay(200)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        refreshFiles()
                                        showOperationProgress = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        )
    }

    if (showNewTypeDialog) {
        val rowTextColor = MiuixTheme.colorScheme.onSurface
        OverlayDialog(
            show = showNewTypeDialog,
            onDismissRequest = { showNewTypeDialog = false },
            title = "新建",
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                newFolderName = ""
                                showNewTypeDialog = false
                                showNewFolderDialog = true
                            }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = rowTextColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(stringResource(R.string.folder), color = rowTextColor)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                newFileInputName = ""
                                showNewTypeDialog = false
                                showNewFileDialog = true
                            }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_file),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = rowTextColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("文件", color = rowTextColor)
                    }

                    Spacer(Modifier.height(12.dp))

                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { showNewTypeDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )
    }

    if (showOperationProgress) {
        OverlayDialog(
            show = showOperationProgress,
            onDismissRequest = {},
            title = operationProgressText,
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = operationProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "${(operationProgress * 100).toInt()}%",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    file: File,
    isSelected: Boolean,
    isInSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MiuixTheme.colorScheme.surfaceVariant else MiuixTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isInSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            Icon(
                painter = painterResource(if (file.isDirectory) R.drawable.ic_folder else R.drawable.ic_file),
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .padding(end = 12.dp),
                tint = MiuixTheme.colorScheme.onSurface
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = if (file.isDirectory) {
                        val count = file.listFiles()?.size ?: 0
                        "$count ${stringResource(R.string.items)}"
                    } else {
                        "${formatFileSize(file.length())} - ${Date(file.lastModified()).toString()}"
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            if (file.isDirectory && !isInSelectionMode) {
                IconButton(onClick = { onClick() }) {
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
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${String.format("%.2f", bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${String.format("%.2f", bytes / (1024.0 * 1024))} MB"
        else -> "${String.format("%.2f", bytes / (1024.0 * 1024 * 1024))} GB"
    }
}

private fun copyFile(src: File, dest: File) {
    if (src.isDirectory) {
        dest.mkdirs()
        src.listFiles()?.forEach { child ->
            copyFile(child, File(dest, child.name))
        }
    } else {
        src.copyTo(dest, overwrite = true)
    }
}

/**
 * 可靠的文件移动：先尝试 renameTo（同文件系统），失败则复制后删除（跨文件系统）。
 */
private fun moveFile(src: File, dest: File): Boolean {
    return try {
        // 同文件系统直接重命名
        if (src.renameTo(dest)) {
            return true
        }
        // 跨文件系统：复制后删除
        if (src.isDirectory) {
            copyFile(src, dest)
            src.deleteRecursively()
        } else {
            src.copyTo(dest, overwrite = true)
            src.delete()
        }
        true
    } catch (e: Exception) {
        false
    }
}