package com.termux.app.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.BackHandler
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.termux.shared.shell.TermuxShellUtils
import com.termux.app.ftp.FtpServer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import java.io.File
import java.util.Date

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
    var showSftpInfoDialog by remember { mutableStateOf(false) }
    var isEditingSftpCredentials by remember { mutableStateOf(false) }
    var sftpUsername by remember { mutableStateOf("") }
    var sftpPassword by remember { mutableStateOf("") }
    var ftpServer: FtpServer? by remember { mutableStateOf(null) }

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
        
        val appPrefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        if (appPrefs.getBoolean("showSftpInfo", false)) {
            showSftpInfoDialog = true
            appPrefs.edit().putBoolean("showSftpInfo", false).apply()
        }
        
        if (appPrefs.getBoolean("ftp_enabled", false) && isPortInUse(8021)) {
            val rootDir = "/data/data/com.termux/files/home"
            ftpServer = FtpServer(8021, sftpUsername, sftpPassword, rootDir)
            ftpServer?.start()
            isSftpEnabled = true
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
            .setContentText("地址: ftp://$ipAddress:8021\n点击通知显示 FTP 详情")
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
    }

    fun toggleSftp() {
        isSftpEnabled = !isSftpEnabled
        val appPrefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        
        if (isSftpEnabled) {
            try {
                val rootDir = "/data/data/com.termux/files/home"
                ftpServer = FtpServer(8021, sftpUsername, sftpPassword, rootDir)
                ftpServer?.start()
                Thread.sleep(500)
                showSftpNotification()
                appPrefs.edit().putBoolean("ftp_enabled", true).apply()
            } catch (e: Exception) {
                e.printStackTrace()
                isSftpEnabled = false
                ftpServer = null
                appPrefs.edit().putBoolean("ftp_enabled", false).apply()
            }
        } else {
            try {
                ftpServer?.stop()
                ftpServer = null
                hideSftpNotification()
                appPrefs.edit().putBoolean("ftp_enabled", false).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
                        Row(modifier = Modifier.padding(start = 16.dp)) {
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
                        Row(modifier = Modifier.padding(start = 16.dp)) {
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
                    Row(modifier = Modifier.padding(end = 16.dp)) {
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
                            clipboardFiles.forEach { srcPath ->
                                val srcFile = File(srcPath)
                                val destFile = File(currentPath, srcFile.name)
                                if (clipboardMode == ClipboardMode.CUT) {
                                    srcFile.renameTo(destFile)
                                } else {
                                    copyFile(srcFile, destFile)
                                }
                            }
                            clipboardMode = ClipboardMode.NONE
                            clipboardFiles = emptySet()
                            refreshFiles()
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
                            IconButton(onClick = { showSftpInfoDialog = true }) {
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
        val dialogBackgroundColor = MiuixTheme.colorScheme.surface

        AlertDialog(
            onDismissRequest = {
                showOpenWithDialog = false
                fileToOpen = null
            },
            containerColor = dialogBackgroundColor,
            title = { Text(file.name, color = dialogTextColor) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "大小: ${android.text.format.Formatter.formatFileSize(context, file.length())}",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isShFile) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSystemInDarkTheme()) Color(0xFF3D3514) else Color(0xFFFFF9C4)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.shell_script_warning),
                                    fontSize = 12.sp,
                                    color = dialogTextColor
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showOpenWithDialog = false
                    fileToOpen = null
                }) {
                    Text(stringResource(R.string.cancel), color = dialogTextColor)
                }
            },
            dismissButton = {}
        )
    }

    if (showDeleteDialog) {
        val dialogTextColor = MiuixTheme.colorScheme.onSurface
        val dialogBackgroundColor = MiuixTheme.colorScheme.surface
        
        AlertDialog(
            containerColor = dialogBackgroundColor,
            title = { Text(stringResource(R.string.delete_confirm_title), color = dialogTextColor) },
            text = { Text("${stringResource(R.string.delete_confirm_message)} (${selectedFiles.size})", color = dialogTextColor) },
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                Button(onClick = {
                    selectedFiles.forEach { path ->
                        File(path).deleteRecursively()
                    }
                    selectedFiles = emptySet()
                    isInSelectionMode = false
                    refreshFiles()
                    showDeleteDialog = false
                }) {
                    Text(stringResource(R.string.delete_confirm), color = dialogTextColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel), color = dialogTextColor)
                }
            }
        )
    }

    if (showSftpInfoDialog) {
        val dialogTextColor = MiuixTheme.colorScheme.onSurface
        val dialogBackgroundColor = MiuixTheme.colorScheme.surface
        
        AlertDialog(
            containerColor = dialogBackgroundColor,
            title = { Text("FTP 连接信息", color = dialogTextColor) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "地址: ftp://${getLocalIpAddress()}:8021",
                        fontSize = 14.sp,
                        color = dialogTextColor
                    )
                    
                    if (isEditingSftpCredentials) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextField(
                                value = sftpUsername,
                                onValueChange = { sftpUsername = it },
                                label = { Text("用户名") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextField(
                                value = sftpPassword,
                                onValueChange = { sftpPassword = it },
                                label = { Text("密码") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "用户名: $sftpUsername",
                                fontSize = 14.sp,
                                color = dialogTextColor
                            )
                            Text(
                                text = "密码: $sftpPassword",
                                fontSize = 14.sp,
                                color = dialogTextColor
                            )
                        }
                    }
                }
            },
            onDismissRequest = { showSftpInfoDialog = false },
            confirmButton = {
                if (isEditingSftpCredentials) {
                    Button(onClick = {
                        saveSftpCredentials()
                        isEditingSftpCredentials = false
                        showSftpInfoDialog = false
                    }) {
                        Text("保存", color = dialogTextColor)
                    }
                } else {
                    Button(onClick = {
                        isEditingSftpCredentials = true
                    }) {
                        Text("修改", color = dialogTextColor)
                    }
                }
            },
            dismissButton = {
                if (isEditingSftpCredentials) {
                    TextButton(onClick = {
                        isEditingSftpCredentials = false
                    }) {
                        Text("取消修改", color = dialogTextColor)
                    }
                } else {
                    TextButton(onClick = { showSftpInfoDialog = false }) {
                        Text("关闭", color = dialogTextColor)
                    }
                }
            }
        )
    }

    if (showRenameDialog) {
        val renameFile = File(selectedFiles.first())
        val dialogTextColor = MiuixTheme.colorScheme.onSurface
        val dialogBackgroundColor = MiuixTheme.colorScheme.surface
        
        AlertDialog(
            containerColor = dialogBackgroundColor,
            title = { Text(stringResource(R.string.rename), color = dialogTextColor) },
            onDismissRequest = { showRenameDialog = false },
            confirmButton = {
                Button(onClick = {
                    val newFile = File(renameFile.parentFile, newFileName)
                    renameFile.renameTo(newFile)
                    selectedFiles = emptySet()
                    isInSelectionMode = false
                    refreshFiles()
                    showRenameDialog = false
                }) {
                    Text(stringResource(R.string.ok), color = dialogTextColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel), color = dialogTextColor)
                }
            },
            text = {
                TextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text(stringResource(R.string.file_name), color = dialogTextColor) }
                )
            }
        )
    }

    if (showNewFolderDialog) {
        val dialogTextColor = MiuixTheme.colorScheme.onSurface
        val dialogBackgroundColor = MiuixTheme.colorScheme.surface
        
        AlertDialog(
            containerColor = dialogBackgroundColor,
            title = { Text(stringResource(R.string.folder), color = dialogTextColor) },
            onDismissRequest = { showNewFolderDialog = false },
            confirmButton = {
                Button(onClick = {
                    val newFolder = File(currentPath, newFolderName)
                    newFolder.mkdirs()
                    refreshFiles()
                    showNewFolderDialog = false
                }) {
                    Text(stringResource(R.string.ok), color = dialogTextColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text(stringResource(R.string.cancel), color = dialogTextColor)
                }
            },
            text = {
                TextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.file_name), color = dialogTextColor) }
                )
            }
        )
    }

    if (showNewFileDialog) {
        val dialogTextColor = MiuixTheme.colorScheme.onSurface
        val dialogBackgroundColor = MiuixTheme.colorScheme.surface
        
        AlertDialog(
            containerColor = dialogBackgroundColor,
            title = { Text("新建文件", color = dialogTextColor) },
            onDismissRequest = { showNewFileDialog = false },
            confirmButton = {
                Button(onClick = {
                    val newFile = File(currentPath, newFileInputName)
                    newFile.createNewFile()
                    refreshFiles()
                    showNewFileDialog = false
                }) {
                    Text(stringResource(R.string.ok), color = dialogTextColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) {
                    Text(stringResource(R.string.cancel), color = dialogTextColor)
                }
            },
            text = {
                TextField(
                    value = newFileInputName,
                    onValueChange = { newFileInputName = it },
                    label = { Text(stringResource(R.string.file_name), color = dialogTextColor) }
                )
            }
        )
    }

    if (showNewTypeDialog) {
        val rowTextColor = MiuixTheme.colorScheme.onSurface
        val dialogBackgroundColor = MiuixTheme.colorScheme.surface

        AlertDialog(
            containerColor = dialogBackgroundColor,
            onDismissRequest = {
                showNewTypeDialog = false
            },
            title = { Text("新建", color = rowTextColor) },
            text = {
                Column(
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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewTypeDialog = false
                }) {
                    Text(stringResource(R.string.cancel), color = rowTextColor)
                }
            },
            dismissButton = {}
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