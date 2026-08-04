package com.termux.app.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

enum class ClipboardMode {
    NONE, COPY, CUT
}

private const val ROOT_PATH = "/data/data/com.termux"
private val FILE_CARD_LIGHT = Color.White
private val FILE_CARD_DARK = Color(0xFF2C2C2C)
private val WARNING_CARD_LIGHT = Color(0xFFFFF9C4)
private val WARNING_CARD_DARK = Color(0xFF3D3514)
private val EXECUTION_BLUE = Color(0xFF3482FF)

@Composable
private fun fileCardColors(): CardColors {
    return CardDefaults.defaultColors(
        color = if (isSystemInDarkTheme()) FILE_CARD_DARK else FILE_CARD_LIGHT,
        contentColor = MiuixTheme.colorScheme.onSurface
    )
}

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
    var showFileDetailSheet by remember { mutableStateOf(false) }
    var fileToOpen by remember { mutableStateOf<File?>(null) }
    var forwardHistory by remember { mutableStateOf<List<File>>(emptyList()) }
    var isSftpEnabled by remember { mutableStateOf(false) }
    var sftpNotificationId = 1001
    var sftpChannelId = "sftp_service"
    var sftpPort by remember { mutableStateOf(8021) }
    var sftpUsername by remember { mutableStateOf("") }
    var sftpPassword by remember { mutableStateOf("") }
    var showOperationProgress by remember { mutableStateOf(false) }
    var operationProgressText by remember { mutableStateOf("") }
    var operationProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(currentPath) {
        files = currentPath.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
        selectedFiles = emptySet()
        isInSelectionMode = false
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
                                Switch(
                                    checked = isSftpEnabled,
                                    onCheckedChange = { toggleSftp() },
                                    colors = SwitchDefaults.switchColors(checkedTrackColor = EXECUTION_BLUE)
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
            contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showWarningCard) {
                item {
                    WarningCard(
                        message = stringResource(R.string.files_warning_message),
                        onDismiss = {
                            showWarningCard = false
                            val prefs = context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("files_warning_shown", true).apply()
                        }
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    colors = fileCardColors()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Text(
                            text = currentPath.absolutePath,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    colors = fileCardColors()
                ) {
                    if (files.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 72.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.empty_folder),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                lineHeight = 22.sp
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            files.forEachIndexed { index, fileItem ->
                                val isSelected = selectedFiles.contains(fileItem.absolutePath)
                                FileListItem(
                                    file = fileItem,
                                    isSelected = isSelected,
                                    isInSelectionMode = isInSelectionMode,
                                    onToggleSelection = {
                                        selectedFiles = if (isSelected) {
                                            selectedFiles - fileItem.absolutePath
                                        } else {
                                            selectedFiles + fileItem.absolutePath
                                        }
                                        if (selectedFiles.isEmpty()) isInSelectionMode = false
                                    },
                                    onClick = {
                                        if (isInSelectionMode) {
                                            selectedFiles = if (isSelected) {
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
                                                showFileDetailSheet = true
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (isInSelectionMode) {
                                            selectedFiles = if (isSelected) {
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
                                if (index < files.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        color = MiuixTheme.colorScheme.dividerLine
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFileDetailSheet && fileToOpen != null) {
        val file = fileToOpen!!
        val isShFile = file.name.endsWith(".sh", ignoreCase = true)
        val onDismiss = {
            showFileDetailSheet = false
            fileToOpen = null
        }

        OverlayBottomSheet(
            show = showFileDetailSheet,
            onDismissRequest = onDismiss,
            title = "",
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (file.isDirectory) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else MiuixTheme.colorScheme.surfaceVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(if (file.isDirectory) R.drawable.ic_folder else R.drawable.ic_file),
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                                tint = if (file.isDirectory) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${getFileTypeLabel(file)} · ${formatFileSize(file.length())}",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    DetailRow(label = stringResource(R.string.file_path), value = file.absolutePath)
                    HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
                    DetailRow(label = stringResource(R.string.file_size), value = formatFileSize(file.length()))
                    HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
                    DetailRow(label = stringResource(R.string.file_permission), value = getPermissionString(file))
                    HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
                    DetailRow(label = stringResource(R.string.file_modified_time), value = formatDate(file.lastModified()))

                    Spacer(modifier = Modifier.height(20.dp))

                    DetailActionRow(
                        icon = R.drawable.ic_copy,
                        text = stringResource(R.string.copy_path)
                    ) {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("path", file.absolutePath)
                        clipboard.setPrimaryClip(clip)
                        onDismiss()
                    }
                    DetailActionRow(
                        icon = R.drawable.ic_copy,
                        text = stringResource(R.string.view_content)
                    ) {
                        onOpenFile(file.absolutePath, "cat \"${file.absolutePath}\"")
                        onDismiss()
                    }
                    DetailActionRow(
                        icon = R.drawable.ic_edit,
                        text = stringResource(R.string.edit_file)
                    ) {
                        val vimPath = "/data/data/com.termux/files/usr/bin/vim"
                        if (File(vimPath).exists()) {
                            onOpenFile(file.absolutePath, "vi \"${file.absolutePath}\"")
                        } else {
                            onOpenFile(file.absolutePath, "pkg install vim -y && vi \"${file.absolutePath}\"")
                        }
                        onDismiss()
                    }
                    if (isShFile) {
                        DetailActionRow(
                            icon = R.drawable.ic_terminal,
                            text = stringResource(R.string.execute_bash)
                        ) {
                            onOpenFile(file.absolutePath, "bash \"${file.absolutePath}\"")
                            onDismiss()
                        }
                        DetailActionRow(
                            icon = R.drawable.ic_copy,
                            text = stringResource(R.string.copy_exec_command)
                        ) {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("exec_command", "bash \"${file.absolutePath}\"")
                            clipboard.setPrimaryClip(clip)
                            onDismiss()
                        }
                    }
                    DetailActionRow(
                        icon = R.drawable.ic_launch,
                        text = stringResource(R.string.open_with_other)
                    ) {
                        val uri = android.net.Uri.parse("content://com.termux.files" + file.absolutePath)
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                        intent.setDataAndType(uri, "*/*")
                        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val chooser = android.content.Intent.createChooser(intent, "选择应用打开")
                        context.startActivity(chooser)
                        onDismiss()
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(
                        modifier = Modifier.height(
                            WindowInsets.navigationBars
                                .only(WindowInsetsSides.Bottom)
                                .asPaddingValues()
                                .calculateBottomPadding() + 8.dp
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
            title = stringResource(R.string.new_file),
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
            title = stringResource(R.string.create_new),
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
                        Text(stringResource(R.string.new_file), color = rowTextColor)
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
private fun FileListItem(
    file: File,
    isSelected: Boolean,
    isInSelectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val rowBg = if (isSelected) MiuixTheme.colorScheme.surfaceVariant else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(rowBg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isInSelectionMode) {
            Box(
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Checkbox(
                    state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                    onClick = onToggleSelection
                )
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (file.isDirectory) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MiuixTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(if (file.isDirectory) R.drawable.ic_folder else R.drawable.ic_file),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (file.isDirectory) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 3.dp),
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (file.isDirectory) {
                        val count = file.listFiles()?.size ?: 0
                        "$count ${stringResource(R.string.items)}"
                    } else {
                        "${formatFileSize(file.length())} · ${formatDate(file.lastModified())}"
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (file.isDirectory && !isInSelectionMode) {
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
private fun WarningCard(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = if (isSystemInDarkTheme()) WARNING_CARD_DARK else WARNING_CARD_LIGHT,
            contentColor = MiuixTheme.colorScheme.onSurface
        ),
        insideMargin = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFFFFB300).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color(0xFFFFB300)
                )
            }
            Text(
                text = message,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.onSurface,
                lineHeight = 20.sp
            )
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.ok),
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onDismiss),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun DetailActionRow(
    icon: Int,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MiuixTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface
        )
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

private fun formatDate(timestamp: Long): String {
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    } catch (e: Exception) {
        "-"
    }
}

private fun getFileTypeLabel(file: File): String {
    if (file.isDirectory) return "文件夹"
    val fileNameLower = file.name.lowercase(Locale.getDefault())
    val extLower = file.extension.lowercase(Locale.getDefault())

    // ===== 完整文件名匹配 =====
    when (fileNameLower) {
        "dockerfile", "containerfile" -> return "Docker 容器构建文件"
        ".dockerignore", "dockerignore" -> return "Docker 忽略列表"
        "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml" -> return "Docker Compose 编排文件"
        ".gitignore", "gitignore" -> return "Git 忽略列表"
        ".gitmodules" -> return "Git 子模块配置"
        ".gitattributes" -> return "Git 属性配置"
        ".hgignore" -> return "Mercurial 忽略列表"
        ".svnignore" -> return "SVN 忽略列表"
        "cloudformation.yml", "cloudformation.yaml" -> return "AWS CloudFormation 模板"
        "serverless.yml", "serverless.yaml" -> return "Serverless Framework 配置"
        "pkg.tar.zst", "pkg.tar.xz" -> return "Arch Linux 软件包"
        "termux.properties" -> return "Termux 配置文件"
        "colors.properties" -> return "Termux 颜色主题"
        "termux-url-opener" -> return "Termux URL 打开脚本"
        "termux-file-editor" -> return "Termux 文件编辑脚本"
        "authorized_keys", "known_hosts" -> return "SSH 密钥/配置文件"
        "apktool.yml", "apktool.yaml" -> return "Apktool 配置文件"
        "makefile", "gnumakefile", "Makefile", "GNUmakefile" -> return "Make 构建脚本"
        "Vagrantfile", "vagrantfile" -> return "Vagrant 配置文件"
        "Jenkinsfile" -> return "Jenkins 流水线脚本"
        "Gemfile" -> return "Ruby Bundler Gemfile"
        "Podfile" -> return "CocoaPods Podfile"
        "Cartfile" -> return "Carthage Cartfile"
        "Brewfile" -> return "Homebrew Brewfile"
        "Package.swift" -> return "Swift 包清单"
        "go.mod" -> return "Go 模块清单"
        "go.sum" -> return "Go 模块校验和"
        "requirements.txt" -> return "Python Pip 依赖清单"
        "Pipfile" -> return "Python Pipenv 配置"
        "pyproject.toml" -> return "Python 项目配置"
        "poetry.lock" -> return "Python Poetry 锁定文件"
        "package.json" -> return "Node.js 包配置"
        "package-lock.json" -> return "NPM 包锁定文件"
        "yarn.lock" -> return "Yarn 包锁定文件"
        "pnpm-lock.yaml" -> return "PNPM 包锁定文件"
        "pnpm-workspace.yaml" -> return "PNPM 工作区配置"
        "bun.lockb", "bun.lock" -> return "Bun 包锁定文件"
        "bunfig.toml" -> return "Bun 配置文件"
        "tsconfig.json" -> return "TypeScript 编译配置"
        "jsconfig.json" -> return "JavaScript 项目配置"
        "tsconfig.build.json" -> return "TypeScript 构建配置"
        "tsconfig.node.json" -> return "TypeScript Node 配置"
        "tsconfig.app.json" -> return "TypeScript 应用配置"
        "vite.config.js", "vite.config.ts" -> return "Vite 构建配置"
        "webpack.config.js", "webpack.config.ts" -> return "Webpack 构建配置"
        "rollup.config.js", "rollup.config.ts" -> return "Rollup 构建配置"
        "esbuild.config.js", "esbuild.config.ts" -> return "Esbuild 构建配置"
        "tsup.config.ts", "tsup.config.js" -> return "Tsup 构建配置"
        "next.config.js", "next.config.ts" -> return "Next.js 配置"
        "nuxt.config.js", "nuxt.config.ts" -> return "Nuxt 配置"
        "astro.config.js", "astro.config.ts" -> return "Astro 配置"
        "svelte.config.js", "svelte.config.ts" -> return "SvelteKit 配置"
        "remix.config.js", "remix.config.ts" -> return "Remix 配置"
        "solid.config.js" -> return "SolidStart 配置"
        "qwik.config.js" -> return "Qwik City 配置"
        "Cargo.toml" -> return "Rust Cargo 清单"
        "Cargo.lock" -> return "Rust Cargo 锁定文件"
        "Podfile.lock" -> return "CocoaPods 锁定文件"
        "Gemfile.lock" -> return "Ruby Bundler 锁定文件"
        "Pipfile.lock" -> return "Python Pipenv 锁定文件"
        "pdm.lock" -> return "Python PDM 锁定文件"
        "composer.json" -> return "PHP Composer 配置"
        "composer.lock" -> return "PHP Composer 锁定文件"
        "mix.exs" -> return "Elixir Mix 项目"
        "mix.lock" -> return "Elixir Mix 锁定文件"
        "rebar.config" -> return "Erlang Rebar 配置"
        "rebar.lock" -> return "Erlang Rebar 锁定文件"
        "dune-project", "dune" -> return "OCaml Dune 构建配置"
        "opam" -> return "OCaml OPAM 包配置"
        "stack.yaml" -> return "Haskell Stack 配置"
        "cabal.project" -> return "Haskell Cabal 项目配置"
        "cabal.project.freeze" -> return "Haskell Cabal 冻结文件"
        "pom.xml" -> return "Maven POM 项目对象模型"
        "pom.asc" -> return "Maven POM 签名"
        "settings.gradle", "settings.gradle.kts" -> return "Gradle 设置"
        "build.gradle", "build.gradle.kts" -> return "Gradle 构建脚本"
        "gradlew", "gradlew.bat" -> return "Gradle Wrapper 启动脚本"
        "mvnw", "mvnw.cmd", "mvnwDebug", "mvnwDebug.cmd" -> return "Maven Wrapper 启动脚本"
        ".bashrc", ".bash_profile", ".bash_login", ".bash_logout", ".profile" -> return "Bash Shell 启动配置"
        ".zshrc", ".zshenv", ".zprofile", ".zlogin", ".zlogout" -> return "Zsh Shell 启动配置"
        ".vimrc", ".gvimrc", ".ideavimrc", ".viminfo" -> return "Vim 配置/信息"
        ".tmux.conf" -> return "tmux 配置"
        ".screenrc" -> return "GNU Screen 配置"
        ".nanorc" -> return "GNU nano 配置"
        ".emacs" -> return "GNU Emacs 配置"
        ".inputrc" -> return "Readline 输入配置"
        ".wgetrc" -> return "Wget 配置"
        ".curlrc" -> return "Curl 配置"
        ".gitconfig" -> return "Git 配置"
        ".hgrc" -> return "Mercurial 配置"
        ".npmrc" -> return "NPM 配置"
        ".yarnrc", ".yarnrc.yml" -> return "Yarn 配置"
        ".config" -> return "用户配置目录标识"
        "sudoers" -> return "Sudo 权限配置"
        "fstab" -> return "Linux 文件系统挂载表"
        "crypttab" -> return "Linux 加密卷挂载表"
        "hosts" -> return "系统主机名解析配置"
        "hostname" -> return "系统主机名配置"
        "resolv.conf" -> return "DNS 解析配置"
        "nsswitch.conf" -> return "名称服务开关配置"
        "sysctl.conf" -> return "内核参数配置"
        "sources.list" -> return "APT 源列表"
        "pacman.conf" -> return "Pacman 包管理器配置"
        "makepkg.conf" -> return "Makepkg 构建配置"
        "configuration.nix" -> return "NixOS 系统配置"
        "hardware-configuration.nix" -> return "NixOS 硬件配置"
        "home.nix" -> return "Home Manager 用户配置"
        "flatpakref" -> return "Flatpak 应用引用"
        "flatpakrepo" -> return "Flatpak 仓库引用"
        "snapcraft.yaml" -> return "Snapcraft 构建配置"
    }

    // ===== 扩展名匹配（无重复版） =====
    return when (extLower) {
        // ========== 虚拟硬盘文件 ==========
        "qcow2" -> "QEMU QCOW2 虚拟硬盘文件"
        "qcow" -> "QEMU QCOW 虚拟硬盘文件"
        "qed" -> "QEMU QED 增强型虚拟硬盘文件"
        "vmdk" -> "VMware VMDK 虚拟硬盘文件"
        "vdi" -> "VirtualBox VDI 虚拟硬盘文件"
        "vhd" -> "Microsoft VHD 虚拟硬盘文件"
        "vhdx" -> "Microsoft VHDX 虚拟硬盘文件"
        "vpc" -> "Virtual PC 虚拟硬盘文件"
        "hdd" -> "Parallels 虚拟硬盘文件"
        "dd" -> "原始磁盘镜像 (dd)"
        "chd" -> "MAME CHD 压缩硬盘镜像"
        "nvram", "vram", "stg" -> "虚拟机 NVRAM/BIOS 存储"
        "ovf", "ova" -> "Open Virtualization 虚拟机配置/包"
        "vmx", "vmxf" -> "VMware 虚拟机配置"
        "vmsd", "vmss", "vmsn", "vmrs" -> "VMware 快照/状态文件"
        "vmem", "vmemu" -> "VMware 物理内存文件"
        "box" -> "Vagrant 虚拟机镜像包"

        // ========== 镜像 / 光盘文件 ==========
        "iso" -> "ISO 9660 光盘镜像文件"
        "img" -> "通用磁盘镜像文件"
        "dmg" -> "Apple DMG 磁盘镜像"
        "sparseimage" -> "Apple 稀疏磁盘镜像"
        "cdr" -> "macOS 刻录盘镜像"
        "ccd" -> "CloneCD 光盘镜像描述"
        "sub" -> "CloneCD 子通道数据"
        "nrg" -> "Nero 光盘镜像"
        "mds" -> "Media Descriptor 镜像辅助 (MDS)"
        "mdf" -> "Media Descriptor 镜像主数据 (MDF)"
        "cue", "toc" -> "CUE Sheet 光盘镜像索引"
        "bin" -> "二进制/原始镜像数据"
        "toast" -> "Roxio Toast 光盘镜像"
        "cdi" -> "DiscJuggler 光盘镜像"
        "ashdisc" -> "Ashampoo 光盘镜像"
        "b5t", "b5i" -> "BlindWrite 5 镜像"
        "b6t", "b6i" -> "BlindWrite 6 镜像"
        "pdi" -> "Instant Copy 镜像"
        "uif" -> "MagicISO UIF 通用镜像"
        "isz" -> "UltraISO ISZ 压缩 ISO"
        "daa" -> "PowerISO DAA 直接访问归档"
        "cso", "cso1", "cso2", "zso", "dax", "jiso", "csl" -> "PSP/PS2 CSO 压缩 ISO"
        "rvz", "wia", "gcz" -> "Dolphin RVZ/WIA/GCZ Wii/GC 压缩镜像"
        "wbfs" -> "Wii WBFS 文件系统镜像"
        "wad", "wux", "nus", "cdn" -> "Wii/3DS WAD 安装包/NUS 内容"
        "pbp", "pbx", "pbn", "pbt" -> "PSX PSP 多盘镜像 (PBP)"
        "gdi" -> "Dreamcast GD-ROM GDI 描述"
        "xci", "nsz" -> "Nintendo Switch XCI/NSZ 游戏卡带镜像"
        "nsp" -> "Nintendo Switch NSP 安装包"
        "nca", "nacp" -> "Nintendo Switch NCA 内容/属性"
        "tik", "cetk", "tmd", "h3", "cert" -> "Wii/3DS 证书/票证/TMD"
        "adf", "adz", "dms", "ipf", "scp", "fdi" -> "Amiga 磁盘镜像"
        "hdf", "hdf1", "hdf2", "hdf3", "hdf4" -> "Amiga/通用硬盘镜像"
        "d64", "g64", "t64", "x64", "d81", "d71", "d80", "d82", "m2i", "nib", "nbz", "2mg", "tap", "crt", "p00" -> "Commodore 64 磁盘/磁带/卡带镜像"
        "fds" -> "Famicom 磁盘系统镜像"
        "wud" -> "Wii U WUD 光盘镜像"
        "xvd", "xvi", "xv0", "xv1", "xv2", "xv3" -> "Xbox One/Series X 虚拟磁盘"
        "vpk", "sfm" -> "PS Vita/PS4 VPK 安装包"
        "pkg", "gp4", "gp5" -> "PS3/PS4/PS5 游戏包/工程"
        "rap", "rif" -> "PS3/PS4 许可证激活文件"
        "fself", "self", "sprx", "prx", "psar" -> "PlayStation 可执行/库/存档"
        "orbis" -> "PS4 Orbis 目标文件"

        // ========== Shell / 脚本 ==========
        "sh" -> "POSIX Shell 脚本"
        "bash" -> "Bash 脚本"
        "zsh" -> "Zsh 脚本"
        "fish" -> "Fish Shell 脚本"
        "ksh" -> "Ksh 脚本"
        "csh" -> "C Shell 脚本"
        "tcsh" -> "Tcsh 脚本"
        "bat", "cmd" -> "Windows 批处理脚本"
        "ps1", "psm1", "psd1", "ps1xml", "psc1", "pssc" -> "PowerShell 脚本/模块"
        "vbs", "vbe", "wsf", "wsh" -> "Windows 脚本宿主 VBScript"
        "awk", "gawk", "nawk" -> "AWK 文本处理脚本"
        "sed" -> "Sed 流编辑脚本"
        "mk", "mak", "makefile", "make" -> "Make 构建文件"
        "cmake" -> "CMake 构建脚本"
        "meson", "meson.build" -> "Meson 构建描述"
        "ninja" -> "Ninja 构建文件"
        "ksh", "rc" -> "Plan9 rc / AT&T ksh 脚本"
        "tcl", "tk", "wish" -> "Tcl/Tk 脚本"
        "expect" -> "Expect 自动化脚本"
        "auto", "conf" -> "GNU Autoconf 脚本"
        "m4" -> "GNU M4 宏脚本"

        // ========== 编程语言源文件 ==========
        "py" -> "Python 脚本"
        "pyw" -> "Python GUI 脚本"
        "pyc", "pyo" -> "Python 字节码缓存"
        "pyx" -> "Cython 源代码"
        "pxd" -> "Cython 声明头"
        "pxi" -> "Cython 包含文件"
        "java" -> "Java 源代码"
        "kt", "kts" -> "Kotlin 源代码/脚本"
        "class" -> "Java 编译字节码"
        "jar" -> "Java JAR 归档"
        "war" -> "Java Web 应用 WAR 归档"
        "ear" -> "Java 企业 EAR 归档"
        "jmod" -> "Java 模块归档"
        "jrt" -> "Java 运行时镜像"
        "c", "i" -> "C 源代码/预处理后代码"
        "h", "hh", "hpp", "hxx", "h++", "hp" -> "C/C++ 头文件"
        "cpp", "cxx", "cc", "c++", "cp" -> "C++ 源代码"
        "ii" -> "C++ 预处理后代码"
        "s", "S", "asm", "asmx" -> "汇编语言源代码"
        "m" -> "Objective-C 源代码"
        "mm", "M" -> "Objective-C++ 源代码"
        "swift", "swiftinterface", "swiftmodule" -> "Swift 源代码/模块"
        "cs", "csx", "csi" -> "C# 源代码/脚本"
        "go" -> "Go (Golang) 源代码"
        "rs", "rlib" -> "Rust 源代码/库"
        "rb", "rbw", "rake", "gemspec" -> "Ruby 脚本/Rake/Gem 规范"
        "php", "phtml", "php3", "php4", "php5", "phps" -> "PHP 脚本"
        "phar" -> "PHP 归档 (PHAR)"
        "js", "mjs", "cjs", "jsm" -> "JavaScript 源/模块"
        "ts" -> "TypeScript 源代码"
        "tsx" -> "TypeScript React JSX (TSX)"
        "jsx" -> "React JavaScript JSX"
        "vue" -> "Vue.js 单文件组件"
        "svelte" -> "Svelte 单文件组件"
        "astro" -> "Astro 组件"
        "coffee", "litcoffee", "coffee.md" -> "CoffeeScript 脚本"
        "html", "htm", "xhtml", "shtml", "hta" -> "HTML 网页/应用"
        "css" -> "CSS 层叠样式表"
        "scss", "sass" -> "Sass/SCSS 样式表"
        "less" -> "Less 样式表"
        "styl", "stylus" -> "Stylus 样式表"
        "postcss" -> "PostCSS 配置"
        "pcss", "sss" -> "PostCSS 源文件"
        "lua" -> "Lua 脚本"
        "pl", "pm", "plx", "xs", "t" -> "Perl 脚本/模块/测试"
        "r", "rdata", "rds", "rda", "rhistory", "rprofile" -> "R 语言数据/脚本"
        "rmd" -> "R Markdown 文档"
        "scala", "sc" -> "Scala 源代码/脚本"
        "dart" -> "Dart 编程语言源代码"
        "ex", "exs" -> "Elixir 源代码/脚本"
        "erl", "hrl", "escript" -> "Erlang 源代码/脚本"
        "hs", "boot" -> "Haskell 源代码"
        "lhs" -> "Literate Haskell 源代码"
        "ml", "mli", "mll", "mly" -> "OCaml 源代码/接口/词法/语法"
        "fs", "fsi", "fsx", "fsscript" -> "F# 源代码/脚本"
        "clj", "cljs", "cljc", "edn" -> "Clojure 源代码/数据"
        "groovy", "gvy", "gy", "gsh", "ggradle" -> "Groovy 脚本"
        "gradle" -> "Gradle 构建脚本"
        "sql" -> "SQL 结构化查询语言脚本"
        "pgsql", "psql" -> "PostgreSQL PL/pgSQL 脚本"
        "mysql" -> "MySQL SQL 脚本"
        "sqlite", "sqlite3" -> "SQLite SQL 脚本"
        "db2" -> "IBM DB2 SQL 脚本"
        "mongo", "mongorc.js" -> "MongoDB JS Shell 脚本"
        "redis" -> "Redis 命令脚本"
        "cql", "cqlsh" -> "Cassandra CQL 脚本"
        "graphql", "gql", "graphqls" -> "GraphQL Schema/查询"
        "proto", "protodevel" -> "Protocol Buffers IDL 定义"
        "thrift" -> "Apache Thrift IDL 定义"
        "avsc" -> "Apache Avro Schema 定义"
        "fbs" -> "FlatBuffers Schema 定义"
        "capnp" -> "Cap'n Proto Schema 定义"
        "widl" -> "WebAssembly IDL"
        "nim", "nims", "nimble" -> "Nim 源代码/脚本/包"
        "odin" -> "Odin 编程语言源代码"
        "zig" -> "Zig 编程语言源代码"
        "v" -> "V 语言源代码"
        "vala", "vapi" -> "Vala 源代码/API 文件"
        "d", "di" -> "D 语言源代码/接口"
        "pas", "pp", "inc", "dpr", "dpk", "dproj" -> "Pascal/Delphi 源代码/工程"
        "lisp", "el", "cl", "lsp", "l", "lisp" -> "Common Lisp / Emacs Lisp"
        "scm", "ss", "sld", "rkt", "rktd", "rktl" -> "Scheme / Racket 源代码"
        "pro", "swi", "pl" -> "Prolog 源代码"
        "forth", "fth", "4th", "frt" -> "Forth 编程语言"
        "cob", "cbl", "cobol", "cpy" -> "COBOL 源代码/副本"
        "jcl", "cntl" -> "大型机 JCL 作业控制"
        "fortran", "f", "for", "f90", "f95", "f03", "f08", "f18", "f2k", "fpp" -> "Fortran 源代码/预处理"
        "jl" -> "Julia 编程语言源代码"
        "m", "matlab", "oct" -> "MATLAB/Octave M 文件"
        "mu" -> "Maxima CAS 脚本"
        "stata", "do", "ado", "mata", "CLASS" -> "Stata 脚本/程序"
        "sps", "sas", "sas7bdat", "sas7bcat" -> "SPSS/SAS 语法/数据集"
        "nb" -> "Wolfram Mathematica 笔记本"
        "wl", "wls" -> "Wolfram 语言脚本"
        "cdf", "nbp" -> "Wolfram CDF 文档/播放器"
        "red", "reds" -> "Red / Red/System 编程语言"
        "rebol", "reb" -> "Rebol 编程语言"
        "io" -> "Io 编程语言"
        "factor" -> "Factor 编程语言"
        "pike" -> "Pike 编程语言"
        "clean", "icl", "dcl", "abc" -> "Clean 编程语言"
        "mercury", "m", "mo" -> "Mercury 逻辑/函数式语言"
        "haxe", "hx", "hxml" -> "Haxe 编程语言/构建"
        "opencl", "cl" -> "OpenCL C 内核"
        "cuda", "cu", "cuh" -> "NVIDIA CUDA C/C++ 内核/头"
        "hip", "h", "hpp" -> "AMD HIP C++ GPU 内核"
        "glsl", "glslf", "glslv", "frag", "vert", "geom", "tesc", "tese", "comp", "rgen", "rint", "rahit", "rchit", "rmiss", "rcall", "mesh", "task" -> "OpenGL GLSL 着色器"
        "hlsl", "fx", "fxh", "hlsli", "vs", "ps", "gs", "hs", "ds", "cs" -> "DirectX HLSL/Effect 着色器"
        "metal", "metallib" -> "Apple Metal 着色器/库"
        "wgsl", "wgslx" -> "WebGPU WGSL 着色器"
        "spv", "spirv" -> "SPIR-V 着色器二进制"
        "smali" -> "Dalvik Smali 反汇编"
        "baksmali" -> "Dalvik Baksmali 反汇编产物"
        "dex" -> "Android Dalvik/ART DEX 字节码"
        "odex", "oat", "vdex", "art" -> "Android AOT 预编译字节码"
        "so" -> "ELF 共享对象链接库 (Android/Linux)"
        "a", "o", "obj", "lo", "la" -> "静态库 / 目标文件 / Libtool 库"
        "ko" -> "Linux 可加载内核模块"
        "elf", "out", "exec" -> "ELF 可执行程序"
        "exe" -> "Windows/DOS EXE 可执行文件"
        "dll", "drv", "ocx", "cpl", "scr", "sys", "ax" -> "Windows DLL/驱动/控件/屏幕保护"
        "msi", "msix", "msixbundle", "appx", "appxbundle" -> "Windows 安装包"
        "msu", "msp", "mum", "cat" -> "Windows 更新补丁/安全编录"
        "com" -> "DOS COM 可执行文件"
        "efi" -> "UEFI EFI 固件/应用"

        // ========== Android / Termux ==========
        "keystore", "jks" -> "Java JKS 密钥库"
        "bks", "bks-v1", "uber", "bcfks" -> "Bouncy Castle 密钥库"
        "pk8" -> "Android PKCS#8 私钥"
        "sig", "sign", "idsig" -> "Android 签名/增量签名"
        "rc" -> "Android Init 启动脚本 (.rc)"
        "te", "fc", "if" -> "SELinux 策略/文件上下文/接口"
        "seapp_contexts", "property_contexts", "service_contexts", "mac_permissions.xml" -> "Android SELinux/权限配置"
        "packages.list", "packages.xml", "packages.list", "packages-backup.xml" -> "Android PackageManager 状态"
        "apex" -> "Android APEX 系统分区包"
        "hidl", "hal", "aidl" -> "Android HIDL HAL / AIDL 接口"
        "apks", "apkm", "xapk", "apkk", "split" -> "Android 分包/捆绑安装包"
        "aab" -> "Android App Bundle (AAB)"
        "apk" -> "Android APK 安装包"
        "ipa" -> "iOS IPA 应用安装包"
        "deb", "udeb" -> "Debian/Ubuntu DEB 软件包"
        "rpm" -> "RedHat/Fedora RPM 软件包"
        "tar" -> "Tape Archive TAR 归档"
        "gz", "gzip" -> "GNU Gzip 压缩"
        "bz2", "bzip2", "bz" -> "bzip2 压缩"
        "xz" -> "XZ Utils 压缩"
        "zst", "zstd" -> "Facebook Zstandard 压缩"
        "lz", "lzip" -> "Lzip 压缩"
        "lz4" -> "LZ4 极速压缩"
        "lzma" -> "LZMA 压缩"
        "lzo" -> "LZO 压缩"
        "z", "taz" -> "Unix Compress 压缩"
        "tgz", "taz" -> "Tar + Gzip (.tgz)"
        "tbz", "tbz2" -> "Tar + Bzip2"
        "txz" -> "Tar + XZ"
        "tlz" -> "Tar + LZMA"
        "tzst" -> "Tar + Zstandard"
        "zip", "zipx" -> "PKZIP 压缩归档"
        "7z" -> "7-Zip 7z 归档"
        "s7z" -> "7-Zip 自解压 SFX"
        "rar", "rev", "r00", "r01", "r02", "r03", "r04", "r05", "r06", "r07", "r08", "r09", "r10", "r20", "r30", "r40", "r50", "r60", "r70", "r80", "r90", "r99", "s00", "s01" -> "WinRAR 归档/恢复/分卷"
        "cab" -> "Microsoft Cabinet 安装/压缩"
        "arj" -> "ARJ 压缩归档"
        "lzh", "lha", "lzs" -> "LZH/LHA 压缩"
        "ace" -> "ACE 压缩归档"
        "alz", "egg" -> "ALZ/EGG Korean 压缩"
        "arc", "ark" -> "ARC/Squash 压缩"
        "pak" -> "PAK/PKARC 压缩"
        "sit", "sitx", "sea", "hqx" -> "StuffIt 压缩/自解压/BinHex"
        "uu", "uue", "xx", "xxe", "btoa", "btx", "yenc", "ync" -> "UUEncode/XXEncode/BinHex/YEnc 编码"

        // ========== 文档 / 文本 ==========
        "md", "markdown", "mdown", "mkd", "mdwn", "mdtxt", "mdtext", "workbook" -> "Markdown 轻量标记"
        "txt", "text", "nfo", "diz", "asc", "me", "readme", "1st" -> "纯文本/说明/信息文件"
        "rst", "rest" -> "reStructuredText 文档 (Python/Sphinx)"
        "adoc", "asciidoc", "asc" -> "AsciiDoc 文档"
        "org", "org_archive" -> "Emacs Org-mode 笔记/任务"
        "tex", "ltx", "latex", "sty", "cls", "dtx", "ins", "bib", "bibtex", "bst", "bbl", "blg", "toc", "lof", "lot", "aux", "log", "out", "synctex.gz" -> "LaTeX 排版/参考文献/辅助"
        "pdf" -> "Adobe Acrobat PDF 可移植文档"
        "doc" -> "Microsoft Word 97-2003 文档 (.doc)"
        "docx" -> "Microsoft Word OOXML 文档 (.docx)"
        "docm", "dot", "dotx", "dotm" -> "Word 启用宏/模板"
        "wri" -> "Microsoft Write 文档"
        "wpd", "wpt", "wbx" -> "WordPerfect 文档/模板"
        "sxw", "stw", "sxg", "sgl" -> "OpenOffice.org 1.x 文本文档"
        "odt", "fodt", "odm", "oth", "ott" -> "ODF 文本文档/主文档/模板"
        "uot" -> "UOF 标文通 文本文档"
        "uof", "uos", "uop", "uox" -> "标文通 UOF 统一办公格式"
        "xls" -> "Microsoft Excel 97-2003 工作簿"
        "xlsx" -> "Microsoft Excel OOXML 工作簿"
        "xlsm", "xlsb", "xlt", "xltx", "xltm", "xlam", "xla", "xlm", "xlw", "xlr" -> "Excel 宏/二进制/加载项/图表"
        "csv", "tsv", "psv", "tab", "dsv" -> "字符分隔表格 CSV/TSV/PSV"
        "ods", "fods", "ots", "uos" -> "ODF/UOF 电子表格"
        "ppt" -> "Microsoft PowerPoint 97-2003 演示"
        "pptx" -> "Microsoft PowerPoint OOXML 演示"
        "pptm", "pot", "potx", "potm", "pps", "ppsx", "ppsm", "ppa", "ppam" -> "PowerPoint 宏/模板/放映/加载项"
        "odp", "fodp", "otp", "uop" -> "ODF/UOF 演示文稿"
        "odg", "fodg", "otg" -> "ODF 矢量图形"
        "odf", "fodf", "odc", "odi", "odb", "oxt", "odm", "odp", "odt", "ods", "odg", "odi", "odb" -> "ODF 公式/图表/图像/数据库/扩展"
        "rtf", "rtfd" -> "Microsoft RTF 富文本格式/目录包"
        "wps", "wpt", "wps", "wpp" -> "金山 WPS 文字/模板/演示"
        "et", "ett", "dps", "dpt" -> "金山 WPS 表格/演示 模板"
        "pages" -> "Apple iWork Pages 文档"
        "numbers" -> "Apple iWork Numbers 表格"
        "key", "keynote", "kth", "knote" -> "Apple iWork Keynote 演示文稿"
        "one", "onetoc2", "onenotec", "onepkg", "onetmp" -> "Microsoft OneNote 笔记本/包"
        "eml", "emlx", "mbx", "mail" -> "电子邮件 EML/Apple Mail/Unix MBOX"
        "msg", "oft", "olm" -> "Microsoft Outlook 邮件/模板/Mac 归档"
        "pst", "ost" -> "Outlook 个人/脱机文件夹"
        "ics", "ical", "icalendar", "ifb", "ical" -> "iCalendar 日历事件/忙闲"
        "vcf", "vcard", "hcim" -> "vCard 电子名片"
        "vcs", "vcalendar" -> "vCalendar 日程"
        "epub", "epub3", "kepub", "epub+zip" -> "EPUB 电子出版 2/3/Kobo"
        "mobi", "azw", "azw1", "azw3", "azw4", "azw8", "kfx", "tpz" -> "Kindle/Mobi/Topaz/KF8/KFX 电子书"
        "fb2", "fb3", "fb2.zip" -> "FictionBook 2/3 俄罗斯电子书"
        "djvu", "djv" -> "DjVu/LizardTech 扫描文档"
        "chm", "chi", "chw", "chq", "ews" -> "Microsoft HTML 帮助/索引"
        "hlp", "gid", "cnt", "ftg", "fts" -> "WinHelp 帮助/全文索引"
        "lit" -> "Microsoft Reader LIT 电子书"
        "lrf", "lrx", "lrs", "lrx" -> "Sony BBeB/LRX 电子书"
        "xeb", "ceb", "cebx", "txtc", "txtz", "umd", "jar" -> "方正 Apabi/掌阅 UMD 中文电子书"
        "ibooks" -> "Apple iBooks Author 电子书"
        "pdb", "prc", "mobi", "azw" -> "Palm OS PDB/PRC 资源数据库/电子书"
        "snb", "cbr", "cbz", "cb7", "cbt", "cba", "cbw", "cbe" -> "Sony Reader/SNB/漫画书 CBR/CBZ/CB7/CBT/CBA 打包"
        "impress", "draw", "math", "base", "writer", "calc", "Chart", "Database" -> "LibreOffice/OpenOffice 应用名"

        // ========== 配置 / 数据 ==========
        "json" -> "JavaScript Object Notation JSON 配置"
        "json5" -> "JSON5 人类可读配置"
        "jsonc" -> "带注释 JSON 配置 (VSCode)"
        "jsonl", "ndjson" -> "行分隔 JSON 数据 (JSONL/NDJSON)"
        "xml", "xsl", "xslt", "dtd", "xsd", "xjb", "xaml", "axml", "resx", "plist", "storyboard", "xcworkspacedata", "xcuserstate", "xcsettings", "xcodeproj", "pbxproj", "xcconfig" -> "XML/XSLT/DTD/XSD/XAML/Apple PList/Xcode 项目/配置"
        "yml", "yaml", "yml.dist", "yaml.dist" -> "YAML Ain't Markup Language 配置"
        "conf", "config", "cfg", "cf", "ini", "inf", "reg", "pol", "admx", "adml", "adm", "admx", "sif", "sdi", "wim", "esd", "swm" -> "配置/Windows 注册表/策略/安装信息"
        "properties", "props", "prop", "options" -> "Java/.properties 属性"
        "toml" -> "Tom's Obvious Minimal Language TOML 配置"
        "env", "env.local", "env.development", "env.production", "env.test", "env.ci", "env.staging", "env.example", "env.sample", "env.demo", "env.dist" -> "Dotenv 环境变量文件"
        "desktop", "directory", "service", "socket", "timer", "mount", "automount", "swap", "path", "slice", "scope", "target", "device", "automount" -> "Freedesktop Desktop/Systemd 单元"
        "rules", "modprobe.d", "modules-load.d", "sysctl.d", "udev", "tmpfiles.d", "sysusers.d", "sysctl.d", "binfmt.d", "modules.d", "udev", "hwdb.d", "journald.conf.d", "logind.conf.d", "resolved.conf.d", "networkd.conf.d", "timesyncd.conf.d" -> "Udev/Modprobe/Sysctl/Tmpfiles 配置片段目录"
        "log", "logs", "out", "stdout", "stderr", "trace" -> "程序日志/输出追踪"
        "pid", "lck", "lock", "lockfile", "flock", "sock", "socket" -> "PID 文件/文件锁/套接字"
        "bak", "backup", "old", "orig", "~", "incompete", "partial" -> "备份/原始/损坏文件"
        "tmp", "temp", "swp", "swo", "swn", "sww", "swx", "swl", "swm", "swt", "tempfile" -> "临时/交换/缓存"
        "part", "crdownload", "download", "downloading", "!ut", "ut!", "ut", "bc!", "!bt", "bt!", "filepart", "part.file" -> "浏览器/BitTorrent 下载中临时"
        "dat", "db", "db3", "sqlite", "sqlite3", "sqlite-", "sdb", "sl3", "db-journal", "db-wal", "db-shm" -> "SQLite 数据库/预写日志/WAL/SHM"
        "mdb", "accdb", "accde", "accdr", "accdt", "accdc", "mdw", "mde", "ade", "adp", "mda", "mdn", "mdt" -> "Microsoft Access/Jet 数据库/项目"
        "frm", "ibd", "ibdata1", "ib_logfile0", "ib_logfile1", "myd", "myi", "mrg", "mad", "mag", "mam", "mar", "mat", "mrg", "trn", "arc" -> "MySQL InnoDB/MyISAM 表空间/数据/索引/日志"
        "pg_dump", "dump", "sql", "backup", "pgpass", "pg_service.conf", "pg_hba.conf", "pg_ident.conf", "recovery.conf", "postgresql.auto.conf", "postmaster.pid", "postmaster.opts" -> "PostgreSQL 转储/配置/PID"
        "ora", "dbf", "ctl", "logmnr" -> "Oracle DBF 表空间/控制文件"
        "nsf", "ntf", "box", "ntf", "ds_store" -> "Lotus Notes NSF / macOS DS_Store"
        "fp7", "fmp12", "fmpur", "fp5", "fp3" -> "FileMaker Pro 7-19 数据库"
        "sdf", "sqlce" -> "Microsoft SQL Server Compact 精简数据库"
        "mdf", "ndf", "ldf" -> "SQL Server 主/次数据/日志"
        "bson" -> "MongoDB BSON 二进制 JSON"
        "msgpack" -> "MessagePack 二进制序列化"
        "parquet" -> "Apache Parquet 列式存储"
        "orc" -> "Apache ORC 优化行列存储"
        "avro" -> "Apache Avro 行式数据文件"
        "feather", "featherv2", "ipc", "arrow" -> "Apache Arrow Feather/IPC"
        "h5", "hdf5", "hdf", "he5", "h5ea", "h5ad", "h5mu", "h5seurat" -> "HDF5 层次数据格式/AnnData/Seurat/MuData"
        "nc", "nc4", "cdf", "grb", "grib", "grb2", "grib2" -> "NetCDF/GRIB 科学气象网格数据"
        "fits", "fit", "fts", "imh" -> "FITS 天文图像/数据"
        "bcf", "vcf.gz", "tbi", "csi", "crai", "bai" -> "BCF/VCF Tabix 索引 (遗传变异)"
        "bed", "bim", "fam", "ped", "map", "vcf" -> "PLINK 基因型/样本/变异 VCF"
        "gff", "gff3", "gtf", "gff2", "gff", "refFlat", "psl", "bedgraph", "bigwig", "bw", "bigbed", "bb", "tdf", "tab" -> "基因/基因组注释 GFF/GTF/BED/BigWig/BigBed"
        "fasta", "fa", "fna", "ffn", "faa", "frn", "fas", "seq", "fsa", "fna.gz", "fa.gz" -> "FASTA 核酸/蛋白序列"
        "fastq", "fq", "fastq.gz", "fq.gz" -> "FASTQ 测序reads (质量+序列)"
        "sam", "bam", "cram", "bai", "bam.bai", "crai", "crai.bai" -> "SAM/BAM/CRAM 序列比对/索引"
        "sra", "sralite" -> "NCBI SRA Short Read Archive"
        "ttl", "n3", "nt", "nq", "trig", "trigs", "owl", "rdf", "rdfs", "jsonld", "hdt" -> "RDF/OWL/JSON-LD 语义网三元组/本体"
        "pb", "binpb", "bytes", "data", "bin" -> "Protobuf / 通用二进制数据"
        "flatbuffers", "capnp.bin", "arrowipc", "ipc" -> "FlatBuffers / Cap'n Proto / Arrow 二进制格式"

        // ========== 图像 ==========
        "jpg", "jpeg", "jpe", "jfif", "pjpeg", "pjp", "jfi" -> "JPEG 联合图像专家组"
        "jxl" -> "JPEG XL 下一代图像"
        "png", "apng" -> "PNG 可移植网络/动画 PNG"
        "gif", "giff" -> "CompuServe GIF 图形交换/动图"
        "webp" -> "Google WebP 图像/动画"
        "bmp", "dib", "rle", "dib", "bm" -> "BMP 设备无关位图"
        "tif", "tiff", "ptif", "tf8", "tf2", "btf" -> "TIFF 标记图像文件"
        "svg", "svgz" -> "W3C SVG 可缩放矢量图形"
        "ico", "cur", "ani" -> "Windows ICO 图标/光标/动画光标"
        "icns" -> "Apple ICNS 图标"
        "heic", "heif", "heifs", "hif", "avci", "heicx", "heis" -> "ISO HEIF/HEIC 高效图像/序列"
        "avif", "avifs", "avis" -> "AV1 AVIF 图像/序列"
        "jp2", "j2k", "jpf", "jpg2", "j2c", "jpc", "jpx", "jpm", "jph", "mj2" -> "JPEG 2000 系列 ISO/IEC 15444"
        "psd", "psb", "pdd", "psdt", "pdt", "psb" -> "Adobe Photoshop 文档/大文档/模板"
        "ai", "ait", "eps", "epsf", "epsi", "ept", "ps", "prn" -> "Adobe Illustrator / Encapsulated PostScript"
        "xcf", "xcf.bz2", "xcf.gz", "xcf.xz", "xcf.zst" -> "GIMP XCF 合成图像"
        "ora", "pdn", "kra", "kpp", "kpl", "kpg", "kpq", "kps", "kph", "kpv", "kpf", "kpc", "kpz", "kpb" -> "OpenRaster / Paint.NET / Krita 工程/画笔"
        "cdr", "cdt", "cmx", "csl", "cpgz", "cptx", "cpt", "clk" -> "CorelDRAW 绘图/模板/展示"
        "pat", "abr", "tpl", "csh", "asl", "grd", "aco", "act", "ase", "acb", "gpl", "soc", "pal", "spl" -> "GIMP/Photoshop 图案/画笔/形状/样式/渐变/色板/色书"
        "raw", "cr2", "cr3", "crw" -> "Canon 原始 RAW/CR2/CR3/CRW"
        "nef", "nrw", "nrw2" -> "Nikon 电子格式 NEF/NRW"
        "arw", "srf", "sr2", "srx", "sr1", "srq" -> "Sony α RAW (ARW/SRF/SR2)"
        "dng" -> "Adobe 通用 DNG 数字负片"
        "rw2", "rwl", "rwz", "rawx" -> "Panasonic/Leica RAW (RW2/RWL)"
        "orf", "ori" -> "Olympus 奥林巴斯 ORF 原始文件"
        "raf", "raf.raw" -> "Fujifilm 富士 RAF 原始"
        "pef", "ptx" -> "Pentax 宾得 PEF/PTX"
        "srw", "srf", "srx" -> "Samsung 三星 SRW 原始"
        "kdc", "dcr", "dc2", "k25", "kdc2" -> "Kodak 柯达 KDC/DCR 原始"
        "mrw", "mfw", "mef" -> "Konica Minolta/美能达 MRW/MEF"
        "x3f" -> "Sigma 适马 Foveon X3F 原始"
        "3fr", "fff", "3i" -> "Hasselblad 哈苏 3FR/FFF 原始"
        "iiq" -> "Phase One IIQ 飞思原始"
        "erf", "eip" -> "Epson爱普生 ERF/EIP 增强包 RAW"
        "mef", "mos", "cap" -> "Mamiya Leaf 利图 MOS/CAP 原始"
        "bay" -> "Casio 卡西欧 BAY 原始"
        "bmq" -> "Nokia 诺基亚 BMQ 原始"
        "cin", "dpx", "dpx" -> "Kodak Cineon / SMPTE DPX 电影胶片扫描"
        "exr", "exrs", "sxr", "mxr", "mxr" -> "ILM OpenEXR 高动态范围/HDR 电影工业"
        "hdr", "rgbe", "xyze" -> "Radiance RGBE HDR 图像"
        "map", "tex", "vtf", "gxt", "pvr", "pvr.gz", "pvr.ccz", "pvr.z" -> "通用 / Source / Valve / Imagination PowerVR 纹理贴图"
        "ppm", "pgm", "pbm", "pnm", "pam", "pfm", "pnm" -> "Netpbm 便携任意图 (PPM/PGM/PBM/PAM/PFM)"
        "pcx", "dcx" -> "ZSoft PCX Paintbrush / 多页 DCX"
        "tga", "icb", "vda", "vst", "tpic" -> "Truevision TGA/Targa 图像"
        "sgi", "rgb", "rgba", "bw", "int", "inta" -> "SGI IRIS RGB 图像"
        "cut", "pal", "bull" -> "DR Halo / Dr. Halo CUT 图像"
        "dds", "dxt1", "dxt2", "dxt3", "dxt4", "dxt5", "bc1", "bc2", "bc3", "bc4", "bc5", "bc6h", "bc7" -> "Microsoft DirectX DDS / S3TC/BC 纹理压缩"
        "ktx", "ktx2", "astc", "etc1", "etc2" -> "Khronos KTX 纹理 / ARM ASTC / Ericsson ETC"
        "3fr", "fff", "iiq", "cap", "eip" -> "专业 16-bit 单反/中画幅 RAW 通用"

        // ========== 视频 ==========
        "mp4", "m4v", "mp4v", "mpg4", "f4v", "f4p", "f4a", "f4b" -> "ISO Base MPEG-4 MP4 / Adobe F4V"
        "mkv", "mk3d", "mka", "webm", "ivf" -> "Matroska MKV/WebM 音视频容器"
        "avi", "avx", "divx", "xvid" -> "Microsoft AVI / DivX/XviD 视频"
        "mov", "qt", "movie" -> "Apple QuickTime 视频容器"
        "flv", "f4v", "swf", "swfl", "spl" -> "Adobe Flash FLV / SWF Shockwave"
        "wmv", "wmx", "wvx", "asf", "asx", "wma", "wmd", "wm" -> "Microsoft Windows Media / ASF"
        "3gp", "3gpp", "3g2", "3gp2", "3gpp2" -> "3GPP TS 26.244 移动视频"
        "ts", "m2ts", "mts", "m2t", "tp", "trp", "mts", "mmts", "tsv", "tsa" -> "MPEG-2 Transport Stream 蓝光/广电"
        "mpg", "mpeg", "mpe", "mp1", "mp2", "m1v", "m2v", "mpa", "mpv", "m2a" -> "MPEG-1/2 节目流 PS/系统流"
        "vob", "ifo", "bup" -> "DVD-Video VOB/IFO/BUP DVD 结构"
        "evo", "vob", "m2ts", "mpls", "clpi", "bdmv", "bdjo", "jar" -> "Blu-ray BDMV/EVO 蓝光结构"
        "ogv", "ogm", "ogx", "oga", "spx" -> "Xiph Ogg / Theora / Vorbis / Speex"
        "rm", "rmvb", "rv", "rmf", "rms", "ra", "ram", "rpm", "rt", "rmp", "rmm" -> "RealNetworks RealMedia/RealAudio"
        "dv", "dif", "dvx", "dvc", "dv-avi" -> "IEC 61834 DV 数字摄像机磁带"
        "mxf", "opus", "mxf", "gxf", "m2v", "yuv", "y4m", "xvid", "huffyuv" -> "SMPTE MXF / GXF 广电素材交换"
        "rec", "yuv", "nv12", "nv21", "yv12", "i420", "i422", "i444", "p010", "p016", "p210", "p410", "yuv4mpegpipe", "yuv4mpeg", "y4m" -> "录制 / 原始未压缩 YUV 视频帧"
        "vp8", "vp9", "vp10", "vp6", "vp7", "vp3", "vp4", "vp5" -> "Google On2 VP3-VP10 原始码流"
        "h264", "avc", "h263", "h261", "h265", "hevc", "h266", "vvc", "eac3", "ac3", "truehd", "thd", "mlp", "av1", "avs2", "avs3", "avs", "avs2", "avs3" -> "ITU/ISO H.261-266 / AV1 / AVS2/3 原始视频码流"
        "amv", "smv", "mtv", "dmv", "pmv", "tvg", "tmd", "mpv", "mvi" -> "Actions/Sigmatel 中国山寨芯片 AMV/SMV/MTV 低码率视频"
        "bik", "smk", "sm2", "roq", "cpk", "usm", "sfd", "str", "xa", "xai", "bsf", "idi", "mdp", "pmf", "psmf", "pamf", "pss", "vns", "ikm", "pss", "thp", "mvd", "fli", "flc", "flx", "cel", "seq", "mvb", "mvi", "wp3", "imovieproj", "imovielibrary", "rcproject", "drp" -> "游戏/主机/专业编辑 Bink / Smacker / RoQ / Cri Sofdec / PlayStation Stream / Nintendo THP / Autodesk Animator FLI/FLC / iMovie / Final Cut Pro X / DaVinci Resolve 工程"

        // ========== 音频 ==========
        "mp3", "mp2", "mp1", "mpa", "mpga", "mp2", "mp1", "m2a" -> "MPEG-1/2 Layer III MP3 / Layer II / Layer I"
        "wav", "wave", "bwf", "bw64", "rf64", "caf", "amr", "aif", "aiff", "aifc", "aiffc", "8svx", "16sv", "iff", "snd", "au", "voc", "wma" -> "RIFF WAV / BWF / RF64 / Apple CAF / AMR / AIFF / Amiga IFF 8SVX16SV / Sun AU / Creative VOC / WMA"
        "flac", "fla", "ogg", "oga", "opus", "spx" -> "Xiph FLAC / Ogg / Opus / Speex"
        "aac", "aacp", "adts", "adif", "loas", "latm", "m4a", "m4b", "m4p", "m4r", "m4v", "3gp", "caf" -> "ISO MPEG-4 AAC / Apple HE-AAC / M4A 有声书/铃声/受保护"
        "ape", "mac" -> "Monkey's Audio APE MAC 无损"
        "alac", "m4a", "caf" -> "Apple Lossless ALAC 无损"
        "tta" -> "True Audio TTA 无损"
        "tak" -> "Tom's lossless Audio Kompressor TAK 无损"
        "wv", "wvc" -> "WavPack 混合有损/无损 WV/WVC 校正"
        "dts", "dtshd", "dts-hd", "dtshr", "dts:x" -> "DTS 影院 / DTS-HD MA / DTS Express 码流"
        "ac3", "eac3", "ec3", "eac3-joc", "dolby" -> "Dolby Digital DD/DD+ E-AC-3 / Atmos JOC 码流"
        "truehd", "thd", "ac4", "ddp", "mlp" -> "Dolby TrueHD / Dolby AC-4 / Meridian MLP 无损影院"
        "mpc", "mp+" -> "Andree Buschmann Musepack MPC/MP+"
        "shn" -> "Lossless Shorten SHN 音频"
        "ofr", "ofs", "optimfrog" -> "Florin Ghido OptimFROG 无损"
        "bonk", "boo" -> "Bonk Audio 压缩"
        "lpac" -> "Lossless Predictive Audio Compression"
        "mid", "midi", "kar", "rmi", "gm", "gs", "xmf", "hmi", "cmf", "rmid" -> "MIDI 乐器数字接口 MIDI 1.0 / MIDI 2.0 / XMF / CMF"
        "mod", "xm", "s3m", "it", "669", "amf", "ams", "dbm", "dsm", "far", "gdm", "ice", "imf", "j2b", "m15", "mdl", "med", "mfp", "mgt", "mt2", "mtm", "nst", "okt", "pt36", "ptm", "stm", "stp", "ult", "umx", "wow", "xpk", "y", "mod.00", "mod.01" -> "Tracker 音乐模块 Amiga Protracker / Fasttracker 2 / Impulse Tracker / Scream Tracker / OctaMED 和各种 Amiga/PC 跟踪器格式"
        "nsf", "nsfe", "nsf2", "gbs", "kss", "hes", "ay", "nsd" -> "任天堂 FC NES NSF / Game Boy GBS / MSX KSS / PCEngine HES / ZX Spectrum AY 芯片音乐"
        "spc", "sfc", "gig" -> "SFC 超级任天堂 SPC700 64KB 声音 CPU SPC 转储"
        "gym", "vgm", "vgz", "sap", "cmc", "cmr", "dmc", "dlt", "mpd", "mpt", "rmt", "tmc", "tm8", "tm2", "sc68", "sndh", "abk", "psid", "sid", "mus", "prg" -> "世嘉MD/Genesis YM2612 GYM / 通用 VGM/VGZ / Atari SAP / 任天堂 Famicom 多格式 / Atari ST SC68 / Amiga SNDH/ABK / Commodore 64 SID"
        "psf", "minipsf", "psf2", "minipsf2", "dsf", "dff", "gsf", "minigsf", "usf", "miniusf", "ncsf", "minincsf", "ssf", "minissf", "qsf", "miniqsf", "snsf", "minisnsf" -> "Neill Corlett PSF 系列 PlayStation PSF/PSF2 / Sega Saturn SSF / Nintendo DS NDSF / N64 USF / GBA GSF / QSC QSF 流格式"
        "aax", "aaxc", "aax+", "m4b" -> "Audible 亚马逊 Audible AAX/AAXC 有声书 DRM/新格式"

        // ========== 字体 ==========
        "ttf", "ttc", "otf", "otc", "woff", "woff2", "eot", "pfa", "pfb", "pfm", "afm", "dfont", "suit", "font", "fnt", "fon", "pcf", "pcf.gz", "snf", "bdf", "glyphs", "ufo", "ufoz", "sfd", "ttx", "designspace", "tfm", "ofm", "t1", "pfa", "pfb", "chr", "teckit", "enc", "map", "lig", "mcm", "fd" -> "TrueType/OpenType/WOFF/WOFF2/EOT PostScript/OS X Datafork/Windows Bitmap/X11 PCF/SNF/BDF/Glyphs/UFO/FontForge SFD/Adobe AFM PFA PFB TeX TFM OFM 字体/编码/映射"

        // ========== 证书 / 密钥 / 签名 ==========
        "pem", "crt", "cer", "cert", "p7b", "p7c", "spc", "p7m", "p7s", "p7r", "p7z", "der", "crl", "pkcs7", "pkcs12", "p12", "pfx", "p11" -> "PEM/DER X.509 证书/CRL/OCSP/PKCS#7 包签名加密/PKCS#12 PFX 私钥证书链"
        "key", "pkey", "priv", "sk", "sec", "id_rsa", "id_ecdsa", "id_ed25519", "id_dsa", "id_xmss", "id_rsa.pub", "id_ecdsa.pub", "id_ed25519.pub", "id_dsa.pub", "id_xmss.pub", "ssh", "ssh_config", "sshd_config", "authorized_keys", "known_hosts", "pkcs8", "pk8", "rsa", "pub" -> "公钥/私钥 OpenSSH / PKCS#8 RSA/DSA/ECDSA/Ed25519/XMSS SSH 格式 新版旧版 PUB/PRIV/SSH 配置"
        "csr", "req", "p10" -> "PKCS#10 证书签名请求 CSR"
        "pub", "asc", "pgp", "gpg", "gpg-v21-migrated", "kbx", "gpg-v21-migrated" -> "RSA/DSA/ECDH/EdDSA GnuPG PGP 公钥钥环 KBX 新版 GPGv21 迁移"
        "sig", "sign", "signature", "asc", "detached-sig", "pgp.sig", "gpg.sig", "p7s" -> "通用分离/内嵌 签名/GPG/PGP/PKCS#7/CMS S/MIME"
        "jwt", "jwe", "jwk", "jwks", "jose" -> "RFC 7519 JWT / JWE / JWK / JWKS JSON 对象签名加密密钥集合"
        "cnf", "openssl.cnf", "srl", "srl.pem", "dsaparam", "dhparam", "ecparam", "rsaparam", "ec", "rsa", "dsa", "dh", "truststore", "cacert" -> "OpenSSL 配置/序列号/参数/信任库 CA 证书"
        "keystore", "jks", "bks", "uber", "bcfks", "pkcs12", "p12", "pfx" -> "Java JKS/BKS/UBER/BCFKS/PKCS12 信任库/密钥库"
        "hsm", "pkcs11", "softhsm", "pkcs11.txt", "pkcs11.conf", "yubikey" -> "PKCS#11 硬件安全模块 SoftHSM/YubiKey 配置"

        // ========== 种子 / P2P ==========
        "torrent", "torrent.added", "fastresume", "magnet", "bc!", "ut!", "!ut", "!bt", "bt!", "ut", "metalink", "meta4", "ed2k", "fdht", "dht", "ss", "ss-local", "ss-server", "ss-redir", "ss-tunnel", "v2ray", "xray", "trojan-go", "clash", "clashx", "sing-box", "mihomo", "mihomo-party", "naiveproxy", "hysteria", "tuic" -> "BitTorrent/uTorrent/qBittorrent 种子/磁力链接/恢复状态/ED2K Metalink / 常见代理配置"

        // ========== 字幕 / 歌词 ==========
        "srt", "srt.ori", "srt.bak", "smi", "sami", "srt.ass", "ass", "ssa", "ass.ssa", "sub", "idx", "vobsub", "vtt", "lrc", "sbv", "sub", "psb", "pjs", "mpl2", "tmp", "aqs", "itt", "scc", "cap", "cin", "dts", "stl", "usf", "3gpp", "tx3g", "ttml", "dfxp", "scc", "cap" -> "SubRip SRT / SSA/ASS / VobSub sub+idx / WebVTT LRC歌词/SBV YouTube / MPlayer MicroDVD / PowerDivX PSB / Phoenix PJS / MPL2 / TMPlayer / AquesTalk AQS / iTT iTunes / Scenarist SCC / Cavena 890 / DTS Cinema / EBU STL Spruce / USF / 3GPP TTML TX3G / DFXP W3C 定时文本"

        // ========== 3D / CAD / 工程 ==========
        "obj", "mtl" -> "Wavefront OBJ 3D 几何/材质"
        "stl", "stla", "stlb" -> "STL 3D Systems 立体光刻/3D打印 (ASCII/Binary)"
        "fbx", "fbx.model", "fbx.anim", "fbx.mesh" -> "Autodesk FBX Filmbox 3D 模型动画"
        "dae", "zae" -> "Khronos COLLADA DAE/打包交换"
        "3ds", "prj", "ies", "dwf", "dwfx", "dxb", "dwt", "dws", "dng", "dwg", "dxf", "dwt", "dws" -> "Autodesk 3DS / AutoCAD DWG / DXF / DWF 项目/光照/标准"
        "blend", "blend1", "blend2", "blend3", "blend4", "blend5", "blend6", "blend7", "blend8", "blend9", "blend10", "blend11", "blend12", "blend13", "blend14", "blend15" -> "Blender 2.8-4.x 工程/备份"
        "iges", "igs", "iges+" -> "IGES 初始图形交换规范 ISO 10303-21 IGES 5.3"
        "step", "stp", "step.z", "stp.z", "step.p21", "stp.p21", "stepxml", "stpxml" -> "STEP ISO 10303 AP203/AP214/AP242 STEP 文件 (ASCII压缩/XML)"
        "ifc", "ifczip", "ifcxml", "ifcmap", "bcf", "bcfzip" -> "buildingSMART IFC 工业基础类 BIM BCF BIM协作格式"
        "rfa", "rvt", "rte", "rft", "rnp", "rfa", "rvt", "adsklib", "adskasset", "adsk", "fbx", "dwg", "dxf", "skp", "skb" -> "Autodesk Revit 族/样板/项目/项目样板 Trimble SketchUp"
        "skp", "skb", "skm", "skb", "layout", "stylebuilder", "style" -> "Trimble SketchUp 工程/备份/材质/Layout/StyleBuilder"
        "sldprt", "sldasm", "slddrw", "slddrt", "sldftp", "sldmat", "sldmdb", "sldstd", "lfp", "snp", "sldasm", "sldprt", "slddrw", "slddrt", "sldftp", "sldmat", "sldmdb", "sldstd", "sldlfp", "sldalr", "sldrev" -> "Dassault SolidWorks 零件/装配/工程图/模具/材料/设计库/标准件"
        "prt", "asm", "drw", "frm", "mfg", "sec", "neu", "xpr", "xas", "xdr", "xlg", "mfg", "set", "pim", "prt", "asm", "drw" -> "PTC Creo Parametric / Pro/ENGINEER Wildfire 零件/装配/绘图/格式/制造/截面/Neutral 交换 XPR XAS XDR XLG"
        "catpart", "catproduct", "catdrawing", "catmaterial", "catcatalog", "catprocess", "catresource", "catshape", "cgr", "3dxml", "3dmap", "cgr" -> "Dassault Systèmes CATIA V5/V6 零件/装配/工程图/材料库/标准件库/工艺/资源/可视化 CGR 轻量化 / 3DXML 复合包"
        "model", "dlv", "exp", "session", "dft", "par", "psm", "pwd", "asm", "cfg", "sim", "simmodel", "sim3d", "dgn", "odb", "odb++", "odb++-csv", "odb++-zip", "ipc2581", "ipc2581c", "ipc2581b", "ipc2581a", "ipc-d-356", "ipc-d-356a", "ipc-d-356b", "ipc-2581", "ipc-2581x" -> "Siemens NX/UG Solid Edge 模型/图纸/钣金/装配/配置/计算机仿真 / 专业 DGN 微型 CAD / ODB++ IPC2581 PCB 制造"
        "ipt", "iam", "idw", "ipn", "ide", "ipj", "iam", "ipt", "idw", "ipn", "ide", "ipj" -> "Autodesk Inventor 零件/装配/工程图/表达视图/设计视图/项目工程"
        "gcode", "gc", "nc", "tap", "cnc", "nc1", "dnc", "mpf", "spf", "pte", "apt", "cls", "din", "eia", "ngc", "oob", "oog", "hgh", "drill", "mill", "turn", "router", "grbl", "tinyg", "g2core", "smoothieware", "marlin", "repetier", "sprinter", "teacup", "sailfish" -> "ISO 6983 RS274-D G-code / 3D打印机 GRBL/Marlin/Smoothie/TinyG/g2core 数控铣削车削雕刻 钻孔 切割"
        "3mf", "amf" -> "3MF Consortium AMF 增材制造 / 3D Manufacturing Format"
        "ply", "off", "3d", "vrml", "wrl", "wrz", "x3d", "x3db", "x3dv", "gltf", "glb", "babylon", "bvh", "abc", "usd", "usda", "usdc", "usdz", "ma", "mb", "max", "chr", "c4d", "lwo", "lws", "lxo", "lxm", "cob", "scn", "pz3", "pp2", "cr2", "hr2", "pz2", "fc2", "mc6", "mcx", "mt5", "mcg" -> "Stanford PLY PNM / Geomview OFF / VRML WRL VRML97 / X3D / Khronos glTF GLB / Babylon.js.babylon / Biovision BVH / Sony Imageworks Alembic ABC / Pixar Universal Scene USD USDA USDC USDZ / Autodesk Maya MA MB / 3ds Max MAX / Cinema 4D C4D / NewTek LightWave LWO LWS LXO LXM / trueSpace COB SCN / Smith Micro Poser Pro PZ3 CR2 HR2 FC2/3DS Max MC6 MCX MT5 MCG 角色/场景/姿势/材质"
        "vrm", "pmx", "pmd", "vmd", "vpd", "sph", "spa", "trm", "mmd", "pmx", "pmd" -> "Digital Content Contest VRM 虚拟现实 虚拟人模型/日本 MikuMikuDance PMD PMX VMD VPD TRM SPH SPA 动作姿势空间 空气变形"

        // ========== 游戏 ROM / 存档 / 补丁 ==========
        "nes", "nez", "nez.gz", "fam", "unif", "bs" -> "FC/NES/Famicom/Dendy NES 2.0 / UNIF / iNES 1.0"
        "smc", "sfc", "fig", "swc", "mgd", "mgh", "ufo", "st", "bs", "dx2", "gd3", "gd7", "sfx", "nsrt", "1gm", "2gm", "a52", "jma", "jnc", "nxc", "stk", "smi", "smd" -> "SFC/SNES 超级任天堂 磁碟 BS-X Satellaview / NSRT / Super Famicom 各种 copier 格式"
        "gb", "gbc", "sgb", "sgb2", "gbx", "isx" -> "Game Boy / Game Boy Color / Super Game Boy 1/2 / GBx 多卡带"
        "gba", "agb", "mbv", "srl", "mbc1", "mbc2", "mbc3", "mbc5", "mbc6", "mbc7", "m161", "mmm01", "huc1", "huc3", "tama5" -> "Nintendo Game Boy Advance AGB / EEPROM/SRAM/FLASH 存储器类型 MBC1-MBC7 HuC1 HuC3 TAMA5 MMM01 M161"
        "nds", "dsi", "ids", "srl", "app", "cxi", "cfa", "cci", "cia", "ncch", "exefs", "romfs", "nro", "nso", "kip", "kip1", "ini1", "tik", "cetk", "tmd" -> "Nintendo DS/DSi / 3DS CIA CCI NCCH 分区 / Switch NSO NRO KIP1"
        "smd", "mdx", "gen", "32x", "32X CD", "pce", "sgx", "pcecd", "sms", "gg", "ngp", "ngpc", "ngc", "ws", "wsc", "ws1", "ws2", "wsc1", "wsc2", "min", "min2", "pcfx", "pcfx.gz", "tg16", "tgn", "pcd", "hucard", "ss", "ssf", "segacd", "scd" -> "世嘉 Master System / Game Gear / Mega Drive / Genesis / 32X / Mega CD / Sega Saturn / PC Engine / PC-FX / TurboGrafx-16 / WonderSwan / Neo Geo Pocket / Pokemon mini 游戏机"
        "a26", "a78", "a52", "lnx", "j64", "jag", "int", "intv", "vec", "vpk" -> "Atari 2600/5200/7800/Lynx/Jaguar / Mattel Intellivision / GCE Vectrex"
        "c64", "c128", "vic20", "pet", "plus4", "c16", "c116", "zx81", "zx80", "spectrum", "sinclair", "amstrad", "cpc", "cpc464", "cpc6128", "oric", "oric1", "oricatmos", "sam", "samcoupe", "msx", "msx1", "msx2", "msx2+", "msxturbor", "coleco", "colecovision", "cv", "adam", "ti99", "ti99/4a", "ti99/8", "atom", "bbcmicro", "electron", "master", "archimedes", "riscpc", "riscos", "amiga", "amiga500", "amiga1200", "amiga3000", "amiga4000", "a500", "a1200", "a2000", "a3000", "a4000", "a5000", "a3010", "a3020", "a4000", "a5000", "a7000", "a7000+" -> "8-bit 16-bit 复古家用电脑 Commodore 64 128 / ZX Spectrum / Amstrad CPC / SAM Coupe / MSX / ColecoVision / Texas TI-99 / Acorn BBC Micro Archimedes RISC OS / Commodore Amiga OCS/ECS/AGA A500-A4000"
        "z64", "v64", "n64", "n64dd", "ndd", "pif", "mpk", "big", "little", "byte-swapped" -> "Nintendo 64/Ultra 64 N64DD / Z64 DoctorV64 / DoctorV64 / V64jr 开发机"
        "xci", "sph", "sp_s", "xex", "xex1", "xex2", "xexp", "live", "stfs", "con", "pirs", "svpd", "muis", "mups", "mst", "dbx", "xvd", "xvi", "xv0", "xv1", "xv2", "xv3", "xv2", "xtf", "xvc" -> "Xbox Original/Xbox 360/Xbox One/Series X|S 可执行/容器/XVD/XVC 文件"
        "sav", "srm", "sa1", "sa2", "sa3", "eep", "sra", "sra0", "sra1", "sram", "srm", "frz", "freeze", "state", "st0", "st1", "st2", "st3", "st4", "st5", "st6", "st7", "st8", "st9", "st10", "st11", "st12", "st13", "st14", "st15", "st16", "st17", "st18", "st19", "st20" -> "游戏模拟器 SRAM SAV EEP SRA 存档 / RetroArch Snex9X ZSNES Mupen64 即时存档 st0-st20"
        "cht", "chtx", "sas", "sasm" -> "游戏金手指 Cheat / PPF Patches / SNES9x Cheat / BSNES Cheat"
        "ips", "bps", "ppf", "ppf1", "ppf2", "ppf3", "xdelta", "vcdiff", "patch", "diff", "udiff", "bsdiff", "bsdiff4", "jps", "zippatch", "xdelta3", "bdf", "aps", "goldfinger", "gameshark", "proactionreplay", "xploder", "actionreplay", "codebreaker", "gamegenie" -> "IPS/BPS/PPF1-3/xDelta/VCDIFF/BSDiffs/JPS/ZIPPatch/APS 游戏ROM 补丁/金手指设备"
        "rom", "chd", "dol", "rel", "rpx", "rpl", "wad", "vpk", "pkg", "ma", "mb" -> "通用 MAME MESS ROM / CHD / GameCube Dol / Wii U RPX RPL / WAD VPK PKG / Maya 3D"

        // ========== 漫画打包 ==========
        "cbz", "cbr", "cb7", "cbt", "cba", "cbw", "cbe" -> "Comic Book Archive ZIP/RAR/7Z/TAR/ACE (CBR/CBZ/CB7/CBT/CBA/CBW/CBE 漫画压缩包"

        // ========== Termux / Linux 常见配置 ==========
        "termux", "properties", "fish_variables", "fish_history", "spacemacs", "doom.d", "inputrc", "axelrc", "aria2.conf", "git-credentials", "svnserve.conf", "passwd", "authz", "ssh_known_hosts", "host.conf", "gai.conf", "interfaces", "netplan.yaml", "networkmanager.conf", "wpa_supplicant.conf", "iwd.conf", "mtools.conf", "lvm.conf", "mdadm.conf", "samba.conf", "smbpasswd", "nfs.conf", "exports", "nginx.conf", "apache2.conf", "httpd.conf", "lighttpd.conf", "caddyfile", "php.ini", "php-fpm.conf", "www.conf", "my.cnf", "mariadb.cnf", "postgresql.conf", "pg_hba.conf", "pg_ident.conf", "redis.conf", "mongodb.conf", "mongod.conf", "couchdb.ini", "cassandra.yaml", "named.conf", "zone", "dhcpd.conf", "dnsmasq.conf", "hostapd.conf", "keepalived.conf", "haproxy.cfg", "varnish.vcl", "squid.conf", "privoxy.config", "crontab", "anacrontab", "fcrontab", "sudoers", "pkla", "polkit-1", "ld.so.conf", "ld.so.preload", "sysctl.conf", "sysfs.conf", "limits.conf", "udev", "rules.d", "modprobe.d", "modprobe.conf", "modules", "modules-load.d", "blacklist.conf", "default", "sysconfig", "conf.d", "apt", "sources.list", "dpkg.cfg", "apt.conf", "preferences", "policy-rc.d", "yum.conf", "dnf.conf", "dnf", "repo", "pacman.conf", "makepkg.conf", "alpm-hooks", "reflector.conf", "portage", "make.conf", "package.use", "package.keywords", "package.mask", "package.unmask", "package.license", "package.accept_keywords", "rpmmacros", "rpmrc", "zypp.conf", "zypper", "slackpkg.conf", "slackpkgplus.conf", "void.conf", "xbps", "apk-tools", "apk-protect", "guix", "channels.scm", "nix", "nixos", "configuration.nix", "hardware-configuration.nix", "home.nix", "flatpak", "flatpakref", "flatpakrepo", "snap", "snapcraft.yaml", "appimage" -> "Termux 系统/用户 shell 启动/Git/SSH/网络/挂载/Web/数据库/DNS/DHCP/安全/系统服务/18种 Linux 发行版包管理器/AppImage Flatpak Snap 通用 Linux 包"

        // ========== 其他 ==========
        else -> "未知文件"
    }
}
private fun getPermissionString(file: File): String {
    return try {
        val stat = Os.lstat(file.absolutePath)
        val mode = stat.st_mode
        val type = when {
            OsConstants.S_ISDIR(mode) -> "d"
            OsConstants.S_ISLNK(mode) -> "l"
            else -> "-"
        }
        val perms = listOf(
            OsConstants.S_IRUSR, OsConstants.S_IWUSR, OsConstants.S_IXUSR,
            OsConstants.S_IRGRP, OsConstants.S_IWGRP, OsConstants.S_IXGRP,
            OsConstants.S_IROTH, OsConstants.S_IWOTH, OsConstants.S_IXOTH
        )
        val permChars = listOf("r", "w", "x")
        type + perms.mapIndexed { index, bit ->
            if (mode and bit != 0) permChars[index % 3] else "-"
        }.joinToString("")
    } catch (e: Exception) {
        "----------"
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
        if (src.renameTo(dest)) {
            return true
        }
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
