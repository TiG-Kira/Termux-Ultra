package com.termux.app.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.BreadcrumbBar
import top.yukonga.miuix.kmp.basic.BreadcrumbItem
import top.yukonga.miuix.kmp.basic.joinToPath
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.basic.Checkbox
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
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
        modifier = Modifier.fillMaxSize(),
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
            contentPadding = PaddingValues(top = 8.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showWarningCard) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSystemInDarkTheme()) Color(0xFF3D3514) else Color(0xFFFFF9C4))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (isSystemInDarkTheme()) Color(0xFFB88600).copy(alpha = 0.2f) else Color(0xFFFFA000).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_info),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (isSystemInDarkTheme()) Color(0xFFFFB300) else Color(0xFFFF8F00)
                                )
                            }
                            Text(
                                text = stringResource(R.string.files_warning_message),
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
                val breadcrumbItems = remember(currentPath) { pathToBreadcrumbItems(currentPath) }
                BreadcrumbBar(
                    items = breadcrumbItems,
                    onItemClick = { index ->
                        val targetPath = breadcrumbItems[index].path
                        val targetFile = File(targetPath)
                        if (targetFile.isDirectory && targetFile != currentPath) {
                            forwardHistory = emptyList()
                            currentPath = targetFile
                        }
                    },
                    highlightIndex = breadcrumbItems.lastIndex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
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
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            lineHeight = 22.sp
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
                        },
                        onDetailClick = if (fileItem.isDirectory) {
                            {
                                fileToOpen = fileItem
                                showOpenWithDialog = true
                            }
                        } else null
                    )
                }
            }
        }
    }

    if (showOpenWithDialog && fileToOpen != null) {
        val file = fileToOpen!!
        val isShFile = file.name.endsWith(".sh", ignoreCase = true)
        val isDark = isSystemInDarkTheme()
        val dialogTextColor = MiuixTheme.colorScheme.onSurface
        val dialogSubtextColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

        fun getPermissionsDesc(f: File): String {
            val r = if (f.canRead()) "r" else "-"
            val w = if (f.canWrite()) "w" else "-"
            val x = if (f.canExecute()) "x" else "-"
            return "-${r}${w}${x}"
        }

        fun formatDate(f: File): String {
            val d = Date(f.lastModified())
            val cal = java.util.Calendar.getInstance()
            cal.time = d
            val year = cal.get(java.util.Calendar.YEAR)
            val month = String.format("%02d", cal.get(java.util.Calendar.MONTH) + 1)
            val day = String.format("%02d", cal.get(java.util.Calendar.DAY_OF_MONTH))
            val hour = String.format("%02d", cal.get(java.util.Calendar.HOUR_OF_DAY))
            val min = String.format("%02d", cal.get(java.util.Calendar.MINUTE))
            return "$year-$month-$day $hour:$min"
        }

        OverlayBottomSheet(
            show = showOpenWithDialog,
            onDismissRequest = {
                showOpenWithDialog = false
                fileToOpen = null
            },
            title = "",
            content = {
                Column(modifier = Modifier.heightIn(max = 600.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (file.isDirectory) {
                                        MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    } else if (isShFile) {
                                        if (isDark) Color(0xFF1A5A96) else Color(0xFF3F8DD6)
                                    } else {
                                        if (isDark) Color(0xFF3A3A3A) else Color(0xFFEEEEEE)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(if (file.isDirectory) R.drawable.ic_folder else R.drawable.ic_file),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = if (file.isDirectory) MiuixTheme.colorScheme.primary else Color.White
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = dialogTextColor,
                                lineHeight = 22.sp
                            )
                            Text(
                                text = if (file.isDirectory) {
                                    val count = file.listFiles()?.size ?: 0
                                    "${stringResource(R.string.folder)} · $count ${stringResource(R.string.items)}"
                                } else {
                                    "${getFileTypeDescription(file)} · ${android.text.format.Formatter.formatFileSize(context, file.length())}"
                                },
                                fontSize = 13.sp,
                                color = dialogSubtextColor,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    // ── 可滚动内容区域：文件详情 + 操作选项 ──
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .background(if (isDark) Color(0xFF252525) else Color(0xFFF5F5F5))
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.file_info_path),
                                    fontSize = 13.sp,
                                    color = dialogSubtextColor,
                                    modifier = Modifier.width(70.dp)
                                )
                                Text(
                                    text = file.absolutePath,
                                    fontSize = 13.sp,
                                    color = dialogTextColor,
                                    lineHeight = 18.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (!file.isDirectory) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.file_info_ext),
                                    fontSize = 13.sp,
                                    color = dialogSubtextColor,
                                    modifier = Modifier.width(70.dp)
                                )
                                Text(
                                    text = getCanonicalExtension(file),
                                    fontSize = 13.sp,
                                    color = dialogTextColor,
                                    lineHeight = 18.sp
                                )
                            }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.file_info_type),
                                    fontSize = 13.sp,
                                    color = dialogSubtextColor,
                                    modifier = Modifier.width(70.dp)
                                )
                                Text(
                                    text = if (file.isDirectory) stringResource(R.string.folder) else getFileTypeDescription(file),
                                    fontSize = 13.sp,
                                    color = dialogTextColor,
                                    lineHeight = 18.sp
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (file.isDirectory) stringResource(R.string.items) else stringResource(R.string.file_info_size),
                                    fontSize = 13.sp,
                                    color = dialogSubtextColor,
                                    modifier = Modifier.width(70.dp)
                                )
                                Text(
                                    text = if (file.isDirectory) {
                                        val count = file.listFiles()?.size ?: 0
                                        "$count ${stringResource(R.string.items)}"
                                    } else {
                                        android.text.format.Formatter.formatFileSize(context, file.length())
                                    },
                                    fontSize = 13.sp,
                                    color = dialogTextColor,
                                    lineHeight = 18.sp
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.file_info_permissions),
                                    fontSize = 13.sp,
                                    color = dialogSubtextColor,
                                    modifier = Modifier.width(70.dp)
                                )
                                Text(
                                    text = getPermissionsDesc(file),
                                    fontSize = 13.sp,
                                    color = dialogTextColor,
                                    lineHeight = 18.sp
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.file_info_modified),
                                    fontSize = 13.sp,
                                    color = dialogSubtextColor,
                                    modifier = Modifier.width(70.dp)
                                )
                                Text(
                                    text = formatDate(file),
                                    fontSize = 13.sp,
                                    color = dialogTextColor,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    if (isShFile) {
                        Spacer(Modifier.height(10.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .background(if (isDark) Color(0xFF3D3514) else Color(0xFFFFF9C4))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (isDark) Color(0xFFB88600).copy(alpha = 0.2f) else Color(0xFFFFA000).copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_info),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (isDark) Color(0xFFFFB300) else Color(0xFFFF8F00)
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.shell_script_warning),
                                    fontSize = 12.sp,
                                    color = dialogTextColor,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = if (isDark) Color(0xFF3A3A3A) else Color(0xFFE0E0E0)
                    )

                    Spacer(Modifier.height(4.dp))

                    if (file.isDirectory) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    forwardHistory = emptyList()
                                    currentPath = file
                                    showOpenWithDialog = false
                                    fileToOpen = null
                                }
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MiuixTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = stringResource(R.string.action_file_received_open_directory),
                                color = dialogTextColor,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_right),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = dialogSubtextColor
                            )
                        }
                    } else {
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
                            modifier = Modifier.size(22.dp),
                            tint = dialogTextColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.view_content),
                            color = dialogTextColor,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_right),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = dialogSubtextColor
                        )
                    }

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
                            modifier = Modifier.size(22.dp),
                            tint = dialogTextColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.edit_file),
                            color = dialogTextColor,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_right),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = dialogSubtextColor
                        )
                    }

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
                            modifier = Modifier.size(22.dp),
                            tint = dialogTextColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "用其他方式打开",
                            color = dialogTextColor,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_right),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = dialogSubtextColor
                        )
                    }
                    } // ── 文件操作 else 结束 ──
                    } // ── 可滚动内容区域结束 ──

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color(0xFF3A3A3A) else Color(0xFFE8E8E8))
                                .clickable {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("path", file.absolutePath)
                                    clipboard.setPrimaryClip(clip)
                                    showOpenWithDialog = false
                                    fileToOpen = null
                                }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = dialogTextColor
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.copy_path),
                                color = dialogTextColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (isShFile) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MiuixTheme.colorScheme.primary)
                                    .clickable {
                                        onOpenFile(file.absolutePath, "bash \"${file.absolutePath}\"")
                                        showOpenWithDialog = false
                                        fileToOpen = null
                                    }
                                    .padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_terminal),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color.White
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.execute_script),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars
                                .only(WindowInsetsSides.Bottom)
                                .asPaddingValues()
                                .calculateBottomPadding() + 16.dp
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
    onLongClick: () -> Unit,
    onDetailClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (isInSelectionMode) Modifier.clickable { onClick() }
                else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            )
            .background(if (isSelected && isInSelectionMode) MiuixTheme.colorScheme.primary.copy(alpha = 0.08f) else MiuixTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isInSelectionMode) {
                Checkbox(
                    state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                    onClick = { onClick() },
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
            }

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
                    lineHeight = 20.sp
                )
                Text(
                    text = if (file.isDirectory) {
                        val count = file.listFiles()?.size ?: 0
                        "$count ${stringResource(R.string.items)}"
                    } else {
                        "${formatFileSize(file.length())} · ${Date(file.lastModified()).toString()}"
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 16.sp
                )
            }

            if (file.isDirectory && !isInSelectionMode) {
                if (onDetailClick != null) {
                    IconButton(onClick = { onDetailClick() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
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

private fun pathToBreadcrumbItems(currentPath: File): List<BreadcrumbItem> {
    val rootPath = ROOT_PATH
    val rootName = "Termux"
    val currentAbsolutePath = currentPath.absolutePath

    if (currentAbsolutePath == rootPath) {
        return listOf(BreadcrumbItem(path = rootPath, text = rootName))
    }

    val relativePath = currentAbsolutePath.removePrefix(rootPath).trimStart('/')
    val segments = relativePath.split('/').filter { it.isNotEmpty() }

    val items = mutableListOf(BreadcrumbItem(path = rootPath, text = rootName))
    var builtPath = rootPath
    for (segment in segments) {
        builtPath = "$builtPath/$segment"
        items.add(BreadcrumbItem(path = builtPath, text = segment))
    }
    return items
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

// ============== 上千种文件扩展名映射表 ==============

private val EXTENSION_MAP: Map<String, String> by lazy {
    val map = HashMap<String, String>(1500)

    // ========= 图片 Image (300+) =========
    val imageExts = arrayOf(
        "jpg" to "JPEG 图片", "jpeg" to "JPEG 图片", "jpe" to "JPEG 图片",
        "jfif" to "JPEG 文件交换格式", "jif" to "JPEG 图片",
        "png" to "PNG 图片", "apng" to "动画 PNG",
        "gif" to "GIF 动图",
        "bmp" to "位图图像", "dib" to "设备无关位图", "rle" to "RLE 位图",
        "webp" to "WebP 图片",
        "svg" to "SVG 矢量图", "svgz" to "SVG 压缩矢量图",
        "ico" to "图标文件", "cur" to "光标文件",
        "tif" to "TIFF 图像", "tiff" to "TIFF 图像", "tif8" to "TIFF 8位",
        "heic" to "HEIC 高效图像", "heif" to "HEIF 高效图像", "heics" to "HEIC 序列",
        "avif" to "AVIF 图像", "avifs" to "AVIF 序列",
        "raw" to "RAW 原始图像", "cr2" to "Canon RAW", "cr3" to "Canon RAW 3",
        "nef" to "Nikon RAW", "nrw" to "Nikon RAW",
        "arw" to "Sony RAW", "sr2" to "Sony RAW", "srf" to "Sony RAW",
        "dng" to "Adobe DNG", "orf" to "Olympus RAW",
        "rw2" to "Panasonic RAW", "raf" to "Fujifilm RAW",
        "pef" to "Pentax RAW", "srw" to "Samsung RAW",
        "3fr" to "Hasselblad RAW", "x3f" to "Sigma RAW",
        "mrw" to "Minolta RAW", "kdc" to "Kodak RAW",
        "dcr" to "Kodak DCR RAW", "iiq" to "Phase One RAW",
        "3gp" to "3GPP 图片", "3g2" to "3GPP2 图片",
        "psd" to "Photoshop 文档", "psb" to "Photoshop 大文档",
        "ai" to "Illustrator 文档", "eps" to "Encapsulated PostScript",
        "pdf" to "PDF 文档",
        "indd" to "InDesign 文档", "indt" to "InDesign 模板",
        "cdr" to "CorelDRAW 文档", "cmx" to "CorelDRAW 元交换",
        "odg" to "ODF 图形", "otg" to "ODF 图形模板",
        "wmf" to "Windows 图元文件", "emf" to "增强图元文件", "emz" to "压缩 EMF",
        "pict" to "PICT 图像", "pct" to "PICT 图像", "pic" to "PICT 图像",
        "pxr" to "Pixar 图像",
        "sgi" to "SGI 图像", "rgb" to "SGI RGB", "rgba" to "SGI RGBA",
        "bw" to "SGI 黑白",
        "cel" to "Cel 动画帧", "pic" to "PIC 图像",
        "tga" to "Targa 图像", "vda" to "Targa VDA", "icb" to "Targa ICB",
        "vst" to "Targa VST",
        "exr" to "OpenEXR 高动态", "sxr" to "OpenEXR 多部分",
        "hdr" to "HDR 辐射图像", "rgbe" to "RGBE 图像", "xyze" to "XYZE 图像",
        "ppm" to "可移植像素图", "pgm" to "可移植灰度图",
        "pbm" to "可移植位图", "pnm" to "可移植任意图",
        "pfm" to "可移植浮点图",
        "jbig" to "JBIG 图像", "jbg" to "JBIG 图像",
        "j2k" to "JPEG 2000", "jp2" to "JPEG 2000",
        "jpf" to "JPEG 2000", "jpx" to "JPEG 2000",
        "jpm" to "JPEG 2000 混合", "mj2" to "Motion JPEG 2000",
        "wdp" to "Windows Media 照片", "hdp" to "HD 照片", "jxr" to "JPEG XR",
        "ora" to "OpenRaster 图像",
        "kra" to "Krita 图像",
        "xcf" to "GIMP 图像",
        "pdn" to "Paint.NET 图像",
        "cpt" to "Corel PHOTO-PAINT",
        "pat" to "GIMP 图案", "gbr" to "GIMP 笔刷",
        "abr" to "Photoshop 笔刷", "asl" to "Photoshop 样式",
        "act" to "Photoshop 色表", "aco" to "Photoshop 色板",
        "ase" to "Adobe 色板交换", "acb" to "Adobe 色簿",
        "alv" to "Adobe Levels", "amp" to "Adobe AMP",
        "shc" to "Adobe SHC",
        "dt0" to "DTED 地形 0级", "dt1" to "DTED 地形 1级",
        "dt2" to "DTED 地形 2级",
        "ig1" to "IGES 图形", "igs" to "IGES 图形", "iges" to "IGES 图形",
        "step" to "STEP 3D 模型", "stp" to "STEP 3D 模型",
        "iges" to "IGES 模型",
        "stl" to "STL 3D 模型", "obj" to "Wavefront OBJ",
        "3ds" to "3D Studio 模型", "max" to "3ds Max 场景",
        "ma" to "Maya ASCII", "mb" to "Maya 二进制",
        "blend" to "Blender 3D 文件", "dae" to "Collada DAE",
        "fbx" to "Autodesk FBX", "lwo" to "LightWave 3D",
        "lws" to "LightWave 场景",
        "c4d" to "Cinema 4D 场景",
        "skp" to "SketchUp 模型",
        "dwg" to "AutoCAD 绘图", "dxf" to "AutoCAD 交换",
        "dwt" to "AutoCAD 模板",
        "ipt" to "Inventor 零件", "iam" to "Inventor 装配",
        "catpart" to "CATIA 零件", "catproduct" to "CATIA 装配",
        "catdrawing" to "CATIA 工程图", "cgr" to "CATIA CGR",
        "par" to "Solid Edge 零件", "asm" to "Solid Edge 装配",
        "prt" to "NX 零件", "sldprt" to "SolidWorks 零件",
        "sldasm" to "SolidWorks 装配",
        "slddrw" to "SolidWorks 工程图",
        "ifc" to "IFC 建筑模型",
        "rvt" to "Revit 项目", "rfa" to "Revit 族",
        "rte" to "Revit 模板",
        "nwd" to "Navisworks", "nwf" to "Navisworks 文件集",
        "dgn" to "MicroStation DGN",
        "pln" to "ArchiCAD 项目",
        "bmp" to "位图", "cut" to "Dr. Halo CUT",
        "dds" to "DirectDraw 表面", "pmp" to "Photoshop 宏",
        "dib" to "DIB 位图",
        "fpx" to "FlashPix 图像",
        "it8" to "IT8 色标",
        "ktx" to "Khronos 纹理", "ktx2" to "Khronos 纹理 2",
        "astc" to "ASTC 纹理",
        "basis" to "Basis 纹理",
        "pvr" to "PowerVR 纹理",
        "etc" to "ETC 纹理", "etc1" to "ETC1 纹理",
        "etcpak" to "ETCPak 纹理",
        "webp2" to "WebP2 图像",
        "farbfeld" to "Farbfeld 图像", "ff" to "Farbfeld 图像",
        "qoi" to "Quite OK 图像",
        "pam" to "PAM 任意图",
        "pcx" to "PC Paintbrush",
        "dcx" to "多页 PCX",
        "msp" to "Microsoft Paint",
        "sai" to "PaintTool SAI",
        "sai2" to "PaintTool SAI2",
        "ufo" to "Ulead PhotoImpact",
        "yuv" to "YUV 图像",
        "cin" to "Cineon 图像",
        "dpx" to "DPX 数字图像",
        "psd" to "PSD",
        "mpo" to "多帧图片对象",
        "jps" to "JPEG 立体",
        "pns" to "PNG 立体",
        "vst" to "Visio 模板",
        "vsd" to "Visio 绘图", "vsdx" to "Visio 绘图 X",
        "vss" to "Visio 模具", "vssx" to "Visio 模具 X",
        "vst" to "Visio 模板", "vstx" to "Visio 模板 X",
        "vdx" to "Visio XML 绘图",
        "pub" to "Publisher 文档",
        "eml" to "电子邮件", "msg" to "Outlook 邮件",
        "pst" to "Outlook 数据", "ost" to "Outlook 离线",
        "oft" to "Outlook 模板",
        "olm" to "Outlook for Mac",
        "mbox" to "Mbox 邮件箱", "mbx" to "Mbox 邮箱",
        "emlx" to "Apple 邮件",
        "ics" to "iCalendar", "ical" to "iCalendar",
        "ifb" to "iCalendar 忙闲",
        "vcf" to "vCard 名片", "vcard" to "vCard",
        "hwp" to "Hangul 文档", "hwpx" to "Hangul 文档 X",
        "hwt" to "Hangul 模板",
        "cell" to "Hancom 表格", "cells" to "Hancom 表格模板",
        "show" to "Hancom 演示", "hshow" to "Hancom 演示模板",
        "txt" to "纯文本",
        null
    ).filterNotNull()

    for ((ext, desc) in imageExts) {
        if (map.putIfAbsent(ext, desc) == null) map[ext] = desc
    }

    // ========= 音频 Audio =========
    val audioExts = arrayOf(
        "mp3" to "MP3 音频", "mp2" to "MP2 音频", "mp1" to "MP1 音频",
        "wav" to "WAV 波形", "wave" to "WAVE 音频",
        "flac" to "FLAC 无损", "fla" to "FLAC 音频",
        "aac" to "AAC 音频", "m4a" to "MPEG-4 音频",
        "m4b" to "有声书 M4B", "m4p" to "受保护 M4A",
        "m4r" to "iPhone 铃声",
        "ogg" to "Ogg 音频", "oga" to "Ogg 音频",
        "ogx" to "Ogg 多路复用", "spx" to "Speex 音频",
        "opus" to "Opus 音频",
        "wma" to "Windows Media 音频", "wmv" to "Windows Media 视频",
        "wmd" to "Windows Media DRM",
        "aiff" to "AIFF 音频", "aif" to "AIFF 音频", "aifc" to "AIFF 压缩",
        "au" to "Sun Audio", "snd" to "NeXT 声音",
        "raw" to "原始音频", "pcm" to "PCM 音频",
        "caf" to "Core Audio 格式",
        "amr" to "AMR 音频", "awb" to "AMR 宽带",
        "3ga" to "3GPP 音频",
        "mmf" to "MA-2/MA-3 铃声", "smf" to "SMAF 音频",
        "imy" to "iMelody 铃声", "imy" to "iMelody",
        "mid" to "MIDI 音乐", "midi" to "MIDI 音乐",
        "rmi" to "RIFF MIDI", "kar" to "MIDI 卡拉OK",
        "mod" to "模块音乐", "xm" to "FastTracker",
        "s3m" to "Scream Tracker", "it" to "Impulse Tracker",
        "669" to "669 模块", "mtm" to "MultiTracker",
        "stm" to "Scream Tracker", "ult" to "Ultra Tracker",
        "wow" to "Grave Composer", "dmf" to "X-Tracker",
        "dsm" to "DSIK 格式",
        "dsf" to "DSD 流", "dff" to "DSD 文件",
        "dsd" to "DSD 原始",
        "wv" to "WavPack", "wvp" to "WavPack 无损",
        "shn" to "Shorten",
        "tak" to "TAK 无损",
        "ofr" to "OptimFROG", "ofs" to "OptimFROG 数据流",
        "la" to "LA 无损", "pac" to "LPAC",
        "ape" to "Monkey's Audio", "mac" to "Monkey's Audio 旧版",
        "tta" to "True Audio",
        "acd" to "ACD ACD", "acd-zip" to "ACD 压缩",
        "gsm" to "GSM 音频",
        "ra" to "RealAudio", "rm" to "RealMedia",
        "ram" to "RealAudio 元", "rmm" to "RealMedia 元",
        "rmj" to "Real Jukebox",
        "ra" to "Real Audio",
        "voc" to "Creative Voice",
        "act" to "ACT 语音",
        "aat" to "AAT 声音",
        "3gpp" to "3GPP 音频", "3gpp2" to "3GPP2 音频",
        null
    ).filterNotNull()

    for ((ext, desc) in audioExts) {
        if (map.putIfAbsent(ext, desc) == null) map[ext] = desc
    }

    // ========= 视频 Video =========
    val videoExts = arrayOf(
        "mp4" to "MP4 视频", "m4v" to "MPEG-4 视频",
        "m4s" to "MPEG-DASH 段",
        "avi" to "AVI 视频",
        "mkv" to "Matroska 视频", "mk3d" to "Matroska 3D",
        "mka" to "Matroska 音频", "mks" to "Matroska 字幕",
        "mov" to "QuickTime 视频", "qt" to "QuickTime 电影",
        "wmv" to "Windows Media 视频",
        "flv" to "Flash 视频", "f4v" to "Flash MP4 视频",
        "f4p" to "Flash 受保护", "f4a" to "Flash 音频",
        "f4b" to "Flash 有声书",
        "webm" to "WebM 视频",
        "mpeg" to "MPEG 视频", "mpg" to "MPEG 视频",
        "mpe" to "MPEG 视频", "m1v" to "MPEG-1 视频",
        "m2v" to "MPEG-2 视频", "mp2v" to "MPEG-2 视频",
        "mpeg2" to "MPEG-2 视频",
        "ts" to "MPEG TS 流", "m2ts" to "M2TS 蓝光",
        "mts" to "MTS AVCHD", "tp" to "MPEG-2 TS",
        "trp" to "MPEG-2 TRP",
        "3gp" to "3GPP 视频", "3g2" to "3GPP2 视频",
        "3gpp" to "3GPP 视频", "3gpp2" to "3GPP2 视频",
        "ogv" to "Ogg 视频", "ogg" to "Ogg 视频",
        "ogx" to "Ogg 扩展",
        "rm" to "RealMedia", "rmvb" to "RealMedia 变码率",
        "rv" to "RealVideo",
        "asf" to "ASF 视频", "wma" to "WMA 音频",
        "divx" to "DivX 视频",
        "xvid" to "XviD 视频",
        "dat" to "VCD DAT",
        "vob" to "DVD VOB",
        "ifo" to "DVD IFO", "bup" to "DVD 备份",
        "evo" to "HD DVD EVO",
        "m2v" to "MPEG-2 视频",
        "dvr-ms" to "Windows DVR", "dvr" to "DVR 文件",
        "wtv" to "Windows TV",
        "rec" to "录制视频",
        "dv" to "DV 视频", "dif" to "DIF 视频",
        "avchd" to "AVCHD 高级",
        "h264" to "H.264 视频", "h265" to "H.265 视频",
        "hevc" to "HEVC H.265",
        "264" to "H.264 原始", "265" to "H.265 原始",
        "av1" to "AV1 视频",
        "vp8" to "VP8 视频", "vp9" to "VP9 视频",
        "webp" to "WebP 动画",
        "svq3" to "Sorenson 3",
        "vc1" to "VC-1 视频",
        "wmv3" to "WMV3",
        "mpegts" to "MPEG TS",
        "m4u" to "M3U 播放列表",
        "m3u8" to "M3U8 HLS 播放列表", "m3u" to "M3U 播放列表",
        "pls" to "PLS 播放列表",
        "asx" to "ASX 元文件", "wmx" to "WMX 元文件",
        "wax" to "WAX 元文件",
        "wvx" to "WVX Windows 视频",
        "cue" to "CUE 提示表",
        "srt" to "SRT 字幕", "ass" to "ASS 字幕",
        "ssa" to "SSA 字幕", "sub" to "SUB 字幕",
        "vtt" to "WebVTT 字幕",
        "smi" to "SAMI 字幕", "sami" to "SAMI 字幕",
        "idx" to "SUB/IDX 字幕",
        "sup" to "PGS 蓝光字幕",
        "pgs" to "PGS 字幕",
        "lrc" to "LRC 歌词",
        "scc" to "SCC 字幕",
        "mcc" to "MCC 字幕",
        "ttml" to "TTML 字幕",
        "dfxp" to "DFXP 字幕",
        null
    ).filterNotNull()

    for ((ext, desc) in videoExts) {
        if (map.putIfAbsent(ext, desc) == null) map[ext] = desc
    }

    // ========= 文档 Document =========
    val docExts = arrayOf(
        "txt" to "纯文本", "text" to "纯文本",
        "md" to "Markdown 文档", "markdown" to "Markdown 文档",
        "mdown" to "Markdown", "mkd" to "Markdown", "mkdn" to "Markdown",
        "mdwn" to "Markdown", "mdtxt" to "Markdown 文本",
        "mdx" to "MDX 文档",
        "rtf" to "富文本", "rtfd" to "RTF 附件",
        "doc" to "Word 97-2003", "docx" to "Word 文档",
        "docm" to "启用宏 Word", "dot" to "Word 模板",
        "dotx" to "Word 模板", "dotm" to "启用宏模板",
        "wbk" to "Word 备份",
        "xls" to "Excel 97-2003", "xlsx" to "Excel 工作簿",
        "xlsm" to "启用宏工作簿", "xlsb" to "二进制工作簿",
        "xlt" to "Excel 模板", "xltx" to "Excel 模板",
        "xltm" to "启用宏模板",
        "xlam" to "Excel 加载项",
        "xla" to "Excel 97-2003 加载项",
        "xlw" to "Excel 工作区",
        "ppt" to "PowerPoint 97-2003", "pptx" to "PowerPoint 演示",
        "pptm" to "启用宏演示", "pot" to "PowerPoint 模板",
        "potx" to "PowerPoint 模板", "potm" to "启用宏模板",
        "pps" to "PowerPoint 放映", "ppsx" to "PowerPoint 放映",
        "ppsm" to "启用宏放映",
        "ppam" to "PowerPoint 加载项", "ppa" to "旧版加载项",
        "odt" to "ODF 文本文档", "ods" to "ODF 电子表格",
        "odp" to "ODF 演示", "odg" to "ODF 图形",
        "odc" to "ODF 图表", "odf" to "ODF 公式",
        "odi" to "ODF 图像", "odm" to "ODF 主文档",
        "odb" to "ODF 数据库",
        "ott" to "ODF 文模板", "ots" to "ODF 表模板",
        "otp" to "ODF 演模板", "otg" to "ODF 图模板",
        "pdf" to "PDF 文档",
        "fdf" to "PDF 表单数据", "xfdf" to "XML 表单数据",
        "pdf/a" to "PDF/A 档案", "pdf/e" to "PDF/E 工程",
        "pdf/x" to "PDF/X 印刷", "pdf/vt" to "PDF/VT 可变",
        "pdf/ua" to "PDF/UA 通用",
        "eps" to "封装 PostScript",
        "ps" to "PostScript",
        "xps" to "XPS 文档",
        "oxps" to "OpenXPS 文档",
        "djvu" to "DjVu 文档", "djv" to "DjVu",
        "epub" to "EPUB 电子书",
        "mobi" to "Mobipocket 电子书",
        "azw" to "Kindle 电子书", "azw3" to "Kindle KF8",
        "azw4" to "Kindle 课本", "kfx" to "Kindle KFX",
        "cbz" to "漫画 ZIP", "cbr" to "漫画 RAR",
        "cb7" to "漫画 7z", "cbt" to "漫画 TAR",
        "cba" to "漫画 ACE",
        "fb2" to "FictionBook 2",
        "lrf" to "BBeB 电子书", "lrx" to "BBeB 受保护",
        "pdb" to "PalmDOC",
        "pml" to "eReader PML", "pmlz" to "eReader 压缩",
        "rb" to "Rocket 电子书",
        "rtf" to "RTF 富文本",
        "sxw" to "OpenOffice 文", "sxc" to "OpenOffice 表",
        "sxi" to "OpenOffice 演", "sxd" to "OpenOffice 图",
        "sxm" to "OpenOffice 数学",
        "stw" to "OpenOffice 模板",
        "602" to "T602 文档",
        "wpd" to "WordPerfect",
        "wps" to "WPS 文字", "et" to "WPS 表格",
        "dps" to "WPS 演示",
        "wpt" to "WPS 模板", "ett" to "WPS 表格模板",
        "dpt" to "WPS 演示模板",
        "uof" to "标文通 文", "uos" to "标文通 表",
        "uop" to "标文通 演",
        "xml" to "XML 文件",
        "xhtml" to "XHTML 网页", "html" to "HTML 网页",
        "htm" to "HTML 网页", "shtml" to "SHTML 页面",
        "mht" to "MHTML 网页", "mhtml" to "MHTML 归档",
        "php" to "PHP 脚本", "phar" to "PHP 归档",
        "phtml" to "PHP 页面", "php3" to "PHP 3",
        "php4" to "PHP 4", "php5" to "PHP 5",
        "php7" to "PHP 7", "php8" to "PHP 8",
        "asp" to "ASP 页面", "aspx" to "ASPX 页面",
        "ascx" to "ASP.NET 用户控件", "asax" to "ASP.NET 应用",
        "ashx" to "ASP.NET 处理程序", "asmx" to "ASP.NET Web 服务",
        "cshtml" to "Razor 视图", "vbhtml" to "Razor VB",
        "jsp" to "JSP 页面", "jspx" to "JSP XML",
        "jsf" to "JSF 页面",
        "cfm" to "ColdFusion", "cfml" to "ColdFusion",
        "cfc" to "ColdFusion 组件",
        "pl" to "Perl 脚本", "pm" to "Perl 模块",
        "t" to "Perl 测试",
        "r" to "R 语言", "rdata" to "R 数据",
        "rds" to "R 序列化", "rda" to "R 数据",
        "rmd" to "R Markdown", "rnw" to "R Sweave",
        "sas" to "SAS 程序", "sav" to "SPSS 数据",
        "spv" to "SPSS 输出", "sps" to "SPSS 脚本",
        "do" to "Stata 脚本", "dta" to "Stata 数据",
        "gph" to "Stata 图形",
        "m" to "MATLAB", "mat" to "MATLAB 数据",
        "fig" to "MATLAB 图", "mlx" to "MATLAB Live",
        "mlapp" to "MATLAB App",
        "nb" to "Wolfram Notebook", "cdf" to "Wolfram CDF",
        "nbp" to "Wolfram 插件",
        "wls" to "Wolfram 脚本",
        "mojolicious" to "Mojolicious",
        "dancer" to "Dancer Perl",
        "catalyst" to "Catalyst",
        "cabal" to "Cabal 描述", "hs" to "Haskell 源",
        "lhs" to "Haskell 文学",
        "elm" to "Elm 源",
        "clj" to "Clojure 源", "cljs" to "ClojureScript",
        "cljc" to "Clojure 通用",
        "edn" to "EDN 数据",
        "scala" to "Scala 源", "sc" to "Scala 脚本",
        "scm" to "Scheme 源", "ss" to "Scheme 源",
        "rkt" to "Racket 源", "rktd" to "Racket 数据",
        "rktl" to "Racket 链接",
        "cl" to "Common Lisp", "lisp" to "Lisp",
        "fasl" to "编译 Lisp",
        "jl" to "Julia 源",
        "ex" to "Elixir 源", "exs" to "Elixir 脚本",
        "eex" to "EEx 模板", "leex" to "Live EEx",
        "heex" to "HEEx 模板",
        "hrl" to "Erlang 头", "erl" to "Erlang 源",
        "escript" to "Erlang 脚本",
        "d" to "D 语言", "di" to "D 接口",
        "dlang" to "D 语言",
        "v" to "V 语言",
        "odin" to "Odin 源",
        "zig" to "Zig 源",
        "nim" to "Nim 源",
        "cr" to "Crystal 源",
        "lua" to "Lua 脚本", "luac" to "Lua 字节码",
        "toc" to "LuaTeX",
        "moonscript" to "MoonScript", "moon" to "MoonScript",
        "fnl" to "Fennel 语言",
        "janet" to "Janet 源",
        "ruby" to "Ruby 源", "rake" to "Rakefile",
        "gemspec" to "Ruby Gem 描述",
        "rb" to "Ruby", "rhtml" to "ERB 模板",
        "erb" to "ERB 模板", "haml" to "HAML 模板",
        "slim" to "Slim 模板",
        "jbuilder" to "Jbuilder JSON",
        "rabl" to "RABL 模板",
        "python" to "Python 源", "pyc" to "Python 字节码",
        "pyo" to "优化字节码", "pyd" to "Python 扩展",
        "pyx" to "Cython 源", "pxd" to "Cython 头",
        "pxi" to "Cython 包含",
        "ipynb" to "Jupyter Notebook",
        "pyi" to "Python 存根",
        "whl" to "Python 轮",
        "egg" to "Python Egg",
        "egg-info" to "Python 元",
        "js" to "JavaScript", "mjs" to "ES Module",
        "cjs" to "CommonJS",
        "jsx" to "JSX React",
        "ts" to "TypeScript", "tsx" to "TSX React",
        "d.ts" to "TS 类型声明",
        "map" to "Source Map",
        "vue" to "Vue 组件",
        "svelte" to "Svelte 组件",
        "solid" to "Solid 组件",
        "lit" to "Lit 组件",
        "stencil" to "Stencil 组件",
        "coffee" to "CoffeeScript", "litcoffee" to "Coffee 文学",
        "iced" to "IcedCoffeeScript",
        "ts" to "TypeScript",
        "dart" to "Dart 源",
        "go" to "Go 源",
        "rs" to "Rust 源", "rlib" to "Rust 库",
        "cargo" to "Cargo 清单",
        "lock" to "依赖锁定",
        "java" to "Java 源", "class" to "Java 类",
        "jar" to "Java 归档", "war" to "Web 归档",
        "ear" to "企业归档", "jmod" to "JMOD 模块",
        "jlink" to "JLink",
        "jmod" to "Java 模块",
        "kt" to "Kotlin 源", "kts" to "Kotlin 脚本",
        "ktm" to "Kotlin 模块",
        "c" to "C 源", "h" to "C 头",
        "cpp" to "C++ 源", "cxx" to "C++ 源",
        "cc" to "C++ 源", "c++" to "C++ 源",
        "hpp" to "C++ 头", "hxx" to "C++ 头",
        "hh" to "C++ 头", "h++" to "C++ 头",
        "inl" to "内联 C++",
        "ipp" to "Intel IPP",
        "tcc" to "Tiny C 编译",
        "s" to "汇编", "asm" to "汇编",
        "a51" to "8051 汇编",
        "srec" to "S-Record",
        "hex" to "Intel HEX",
        "ihex" to "Intel HEX",
        "cs" to "C# 源", "aspx" to "ASPX 页面",
        "vb" to "VB.NET 源",
        "fs" to "F# 源", "fsi" to "F# 脚本",
        "fsx" to "F# 脚本",
        "xaml" to "XAML 标记",
        "axaml" to "Avalonia XAML",
        "baml" to "二进制 XAML",
        "razor" to "Razor 视图",
        "blazor" to "Blazor 组件",
        "proto" to "Protobuf 模式",
        "pb" to "Protobuf 编译", "protodevel" to "Protobuf 开发",
        "grpc" to "gRPC 定义",
        "thrift" to "Thrift 定义",
        "avro" to "Avro 模式",
        "json" to "JSON 文件", "geojson" to "GeoJSON",
        "ndjson" to "NDJSON 行",
        "jsonl" to "JSON Lines",
        "yaml" to "YAML 文件", "yml" to "YAML 文件",
        "yaml" to "YAML",
        "toml" to "TOML 配置",
        "ini" to "INI 配置",
        "conf" to "配置文件", "config" to "配置",
        "cfg" to "配置", "cf" to "配置",
        "properties" to "Java 属性",
        "env" to "环境变量", "env.example" to "环境示例",
        "env.local" to "环境本地",
        "env.development" to "环境开发",
        "env.production" to "环境生产",
        "env.test" to "环境测试",
        "ini" to "INI",
        "inf" to "INF 安装",
        "reg" to "注册表文件",
        "pol" to "策略文件",
        "htaccess" to "Apache 访问",
        "htpasswd" to "Apache 密码",
        "htgroups" to "Apache 组",
        "npmrc" to "npm 配置",
        "yarnrc" to "Yarn 配置",
        "gitignore" to "Git 忽略",
        "gitattributes" to "Git 属性",
        "gitmodules" to "Git 子模块",
        "editorconfig" to "EditorConfig",
        "dockerfile" to "Dockerfile",
        "dockerignore" to "Docker 忽略",
        "makefile" to "Makefile",
        "mk" to "Makefile 包含",
        "cmake" to "CMake 脚本", "cmakelists" to "CMake 列表",
        "ninja" to "Ninja 构建",
        "bazel" to "Bazel 构建",
        "buck" to "Buck 构建",
        "gn" to "GN 构建",
        "meson" to "Meson 构建",
        "gradle" to "Gradle 构建",
        "kts" to "Gradle Kotlin",
        "sbt" to "SBT 构建",
        "pom" to "Maven POM",
        "ivy" to "Ivy 描述",
        "ant" to "Ant 构建",
        "xcodeproj" to "Xcode 项目",
        "xcworkspace" to "Xcode 工作区",
        "xcscheme" to "Xcode 方案",
        "storyboard" to "Storyboard 界面",
        "xib" to "XIB 界面",
        "nib" to "NIB 已编译",
        "plist" to "Apple 属性列表",
        "pbxproj" to "Xcode 项目数据",
        "xcconfig" to "Xcode 配置",
        "xcassets" to "Xcode 资源",
        "strings" to "Apple 字符串",
        "stringsdict" to "Apple 字符串字典",
        "apk" to "Android APK",
        "aab" to "Android App Bundle",
        "apks" to "APK 拆分",
        "xapk" to "XAPK 扩展",
        "apkm" to "APKM 拆分",
        "dex" to "Dalvik EXE",
        "odex" to "优化 DEX",
        "vdex" to "验证 DEX",
        "art" to "ART 映像",
        "oat" to "OAT 已编译",
        "ipa" to "iPhone 应用",
        "deb" to "Debian 包", "udeb" to "Debian 微包",
        "rpm" to "RPM 包",
        "srpm" to "源 RPM", "spec" to "RPM 规范",
        "pkg" to "macOS 安装包",
        "mpkg" to "多 PKG 安装",
        "dmg" to "Apple 磁盘映像",
        "iso" to "ISO 映像",
        "img" to "磁盘映像",
        "toast" to "Toast 映像",
        "ccd" to "CloneCD 描述",
        "img" to "CloneCD 映像",
        "sub" to "CloneCD 子通道",
        "cue" to "CUE 表",
        "bin" to "二进制文件",
        "mdf" to "MDF 映像", "mds" to "MDS 描述",
        "nrg" to "Nero 映像",
        "pdi" to "InstantCopy 映像",
        "b5t" to "BlindWrite 5",
        "b5i" to "BlindWrite 映像",
        "bwt" to "BlindWrite 4",
        "p01" to "CDRWin",
        "c2d" to "WinOnCD",
        "daa" to "DAA 映像",
        "uif" to "UIF 映像",
        "isz" to "压缩 ISO",
        "qcow" to "QEMU 写时复制",
        "qcow2" to "QEMU 写时复制 2",
        "qed" to "QEMU 增强磁盘",
        "vmdk" to "VMware 虚拟磁盘",
        "vdi" to "VirtualBox 磁盘",
        "vhd" to "Hyper-V 磁盘", "vhdx" to "Hyper-V 磁盘 2",
        "hdd" to "Parallels 磁盘",
        "parallels" to "Parallels 桌面",
        "hds" to "HDS 磁盘",
        "sparseimage" to "Mac 稀疏映像",
        "sparsebundle" to "Mac 稀疏绑定",
        "dmgpart" to "DMG 部分",
        "pkg.tar.zst" to "Arch 包",
        "zst" to "Zstandard 压缩",
        "zstd" to "Zstandard",
        "tar" to "TAR 归档", "gzip" to "Gzip 压缩",
        "gz" to "Gzip 压缩",
        "tgz" to "Tar Gzip",
        "bz2" to "Bzip2 压缩", "bzip2" to "Bzip2",
        "tbz2" to "Tar Bzip2",
        "xz" to "XZ 压缩",
        "txz" to "Tar XZ",
        "lz" to "Lzip 压缩",
        "lzma" to "LZMA 压缩",
        "tar.lzma" to "Tar LZMA",
        "7z" to "7-Zip 压缩",
        "7zip" to "7-Zip",
        "7z.001" to "7z 拆分第一",
        "zip" to "ZIP 压缩", "zipx" to "ZipX 压缩",
        "z" to "LZW 压缩",
        "taz" to "Tar LZW",
        "lz4" to "LZ4 压缩",
        "sz" to "Snappy 压缩",
        "snappy" to "Snappy",
        "zlib" to "zlib",
        "zstd" to "Zstandard",
        "rar" to "RAR 压缩", "r00" to "RAR 拆分",
        "r01" to "RAR 拆分第一",
        "rev" to "RAR 恢复卷",
        "ace" to "ACE 压缩",
        "arc" to "ARC 压缩",
        "arj" to "ARJ 压缩",
        "pak" to "PAK 压缩",
        "lzh" to "LZH 压缩",
        "lha" to "LHA 压缩",
        "sit" to "StuffIt",
        "sitx" to "StuffIt X",
        "sea" to "自展开存档",
        "hqx" to "BinHex 4.0",
        "uu" to "UUencode",
        "uue" to "UUencode 编码",
        "xxe" to "XXencode",
        "yenc" to "yEncode",
        "base64" to "Base64 编码",
        "b64" to "Base64",
        "mime" to "MIME 编码",
        "mmdb" to "MaxMind GeoIP",
        "sqlite" to "SQLite 数据库", "sqlite3" to "SQLite 3",
        "db" to "数据库文件", "db3" to "数据库 3",
        "sdb" to "SDB 数据库",
        "s3db" to "S3DB 数据库",
        "sl3" to "SQLite 3",
        "sql" to "SQL 脚本",
        "mysql" to "MySQL 脚本",
        "psql" to "PostgreSQL 脚本",
        "pgsql" to "PostgreSQL 脚本",
        "mdb" to "Access 数据库",
        "accdb" to "Access 2007+",
        "accde" to "Access 编译",
        "mdw" to "Access 工作组",
        "mdf" to "SQL Server 主",
        "ldf" to "SQL Server 日志",
        "ndf" to "SQL Server 次",
        "bak" to "备份文件",
        "backup" to "备份",
        "bacpac" to "BACPAC 包",
        "dacpac" to "DACPAC 包",
        "oracle" to "Oracle 数据库",
        "ora" to "Oracle",
        "dbf" to "dBase/FOXPRO",
        "fpt" to "FPT 备注",
        "dbc" to "Visual FoxPro",
        "dcx" to "数据库索引",
        "mda" to "Access 加载项",
        "mde" to "Access MDE",
        "ade" to "Access ADE",
        "adp" to "Access 项目",
        "ods" to "开放数据服务",
        "frm" to "MySQL 表单",
        "myd" to "MySQL 数据",
        "myi" to "MySQL 索引",
        "ibd" to "InnoDB 表空间",
        "ibdata" to "InnoDB 共享",
        "maria" to "MariaDB",
        "rocksdb" to "RocksDB",
        "leveldb" to "LevelDB",
        "ldb" to "LevelDB",
        "log" to "日志文件",
        "csv" to "逗号分隔值",
        "tsv" to "制表分隔值",
        "psv" to "管道分隔值",
        "ssv" to "空格分隔值",
        "tab" to "TAB 分隔",
        "ics" to "ICS 日历",
        "vcf" to "vCard",
        "cer" to "安全证书", "crt" to "证书",
        "pem" to "PEM 证书",
        "p12" to "PKCS#12 证书",
        "pfx" to "PFX 证书包",
        "p7b" to "PKCS#7 证书",
        "p7c" to "PKCS#7 加密",
        "p7m" to "PKCS#7 MIME",
        "p7s" to "PKCS#7 签名",
        "der" to "DER 证书",
        "key" to "私钥文件",
        "pub" to "公钥文件",
        "ppk" to "PuTTY 私钥",
        "openssh" to "OpenSSH 密钥",
        "ssh" to "SSH 配置",
        "known_hosts" to "SSH 主机",
        "authorized_keys" to "SSH 授权",
        "pgp" to "PGP 密钥",
        "gpg" to "GNU Privacy Guard",
        "sig" to "签名文件",
        "sign" to "签名",
        "asc" to "ASCII 装甲",
        "md5" to "MD5 校验",
        "sha" to "SHA 校验",
        "sha1" to "SHA-1 校验",
        "sha256" to "SHA-256 校验",
        "sha384" to "SHA-384 校验",
        "sha512" to "SHA-512 校验",
        "crc" to "CRC 校验",
        "sfv" to "SFV 校验",
        "par" to "PAR 恢复",
        "par2" to "PAR2 恢复",
        "ttf" to "TrueType 字体",
        "otf" to "OpenType 字体",
        "ttc" to "TrueType 集合",
        "otc" to "OpenType 集合",
        "woff" to "WOFF 字体",
        "woff2" to "WOFF2 字体",
        "eot" to "Embedded OpenType",
        "pfa" to "PostScript Type 1",
        "pfb" to "PostScript 二进制",
        "pfm" to "PostScript 度量",
        "afm" to "Adobe 字体度量",
        "tfm" to "TeX 字体度量",
        "vf" to "虚拟字体",
        "fnt" to "GDI 字体",
        "fon" to "点阵字体",
        "bdf" to "位图分布格式",
        "pcf" to "便携式编译字体",
        "snf" to "服务器自然字体",
        "sfd" to "FontForge 源",
        "glyphs" to "Glyphs 设计",
        "ufo" to "统一字体对象",
        "designspace" to "Designspace 文档",
        "fea" to "OpenType 特性",
        "fontj" to "字体 JSON",
        "fontp" to "字体项目",
        "swf" to "Flash SWF",
        "fla" to "Flash FLA",
        "flv" to "Flash 视频",
        "swc" to "Flash 组件",
        "swt" to "SWT 模板",
        "swz" to "缓存 SWF",
        "xfl" to "Flash 项目",
        "flex" to "Apache Flex",
        "mxml" to "MXML",
        "fxg" to "FXG 图形",
        "as" to "ActionScript",
        "abc" to "ActionScript 字节码",
        "bat" to "批处理文件", "cmd" to "命令脚本",
        "sh" to "Shell 脚本", "bash" to "Bash 脚本",
        "zsh" to "Zsh 脚本", "fish" to "Fish 脚本",
        "csh" to "C Shell 脚本", "ksh" to "Ksh 脚本",
        "tcsh" to "TCsh 脚本",
        "ps1" to "PowerShell 脚本",
        "psm1" to "PowerShell 模块",
        "psd1" to "PowerShell 数据",
        "ps1xml" to "PowerShell XML",
        "psc1" to "PowerShell 控制台",
        "vbs" to "VBScript",
        "vbe" to "VBScript 编码",
        "wsf" to "Windows Script File",
        "wsc" to "Windows Script 组件",
        "hta" to "HTML 应用",
        "jar" to "Java JAR",
        "apk" to "Android APK",
        "exe" to "Windows 可执行",
        "msi" to "Windows 安装",
        "com" to "DOS 命令",
        "dll" to "动态链接库",
        "ocx" to "ActiveX 控件",
        "sys" to "Windows 驱动",
        "drv" to "驱动程序",
        "cpl" to "控制面板扩展",
        "scr" to "屏幕保护",
        "so" to "共享对象库",
        "dylib" to "动态库 Mach-O",
        "a" to "静态归档",
        "la" to "Libtool 归档",
        "ko" to "内核模块",
        "o" to "目标文件",
        "obj" to "目标文件",
        "elf" to "ELF 可执行",
        "out" to "a.out 编译输出",
        "app" to "macOS 应用",
        "service" to "Systemd 服务",
        "socket" to "Systemd 套接字",
        "device" to "Systemd 设备",
        "mount" to "Systemd 挂载",
        "automount" to "Systemd 自动挂载",
        "swap" to "Systemd 交换",
        "target" to "Systemd 目标",
        "path" to "Systemd 路径",
        "timer" to "Systemd 定时",
        "slice" to "Systemd 切片",
        "scope" to "Systemd 作用域",
        "rules" to "udev 规则",
        "modprobe" to "modprobe 配置",
        "modules-load" to "模块加载配置",
        "sysctl" to "sysctl 配置",
        "network" to "Systemd 网络",
        "netdev" to "Systemd 网络设备",
        "link" to "Systemd 网络链接",
        "vim" to "Vim 脚本",
        "vimrc" to "Vim 配置",
        "nvim" to "Neovim 配置",
        "emacs" to "Emacs 配置",
        "el" to "Emacs Lisp",
        "eln" to "Emacs Lisp Native",
        "xmodmap" to "X 键映射",
        "Xresources" to "X 资源",
        "Xdefaults" to "X 默认",
        "tmux.conf" to "tmux 配置",
        "screenrc" to "screen 配置",
        "zshrc" to "zsh 配置",
        "bashrc" to "bash 配置",
        "bash_profile" to "bash 登录配置",
        "bash_login" to "bash 登录",
        "profile" to "Shell 登录配置",
        "inputrc" to "Readline 配置",
        "curlrc" to "curl 配置",
        "wgetrc" to "wget 配置",
        "nanorc" to "nano 配置",
        "hushlogin" to "无登录消息",
        "ssh_config" to "SSH 客户端配置",
        "sshd_config" to "SSH 守护配置",

        // ========= 前端样式表 =========
        "css" to "CSS 级联样式表",
        "scss" to "Sass/SCSS 样式表",
        "sass" to "Sass 样式表",
        "less" to "Less 样式表",
        "styl" to "Stylus 样式表",

        // ========= WebAssembly =========
        "wasm" to "WebAssembly 二进制",
        "wast" to "WebAssembly 文本",
        "wat" to "WebAssembly 文本格式",

        // ========= 设计软件 =========
        "sketch" to "Sketch 设计文件",
        "xd" to "Adobe XD 设计",
        "afdesign" to "Affinity Designer",
        "afphoto" to "Affinity Photo",
        "afpub" to "Affinity Publisher",
        "affont" to "Affinity Font",

        // ========= 游戏音乐 =========
        "nsf" to "NES 音乐",
        "spc" to "SNES SPC700 音乐",
        "gbs" to "Game Boy 音乐",
        "gym" to "Genesis/MD 音乐",
        "sap" to "Atari 8-bit 音乐",
        "ay" to "ZX Spectrum 音乐",
        "sid" to "Commodore 64 SID 音乐",
        "psf" to "PlayStation PSF 音乐",
        "psf2" to "PlayStation PSF2 音乐",
        "minipsf" to "MiniPSF 音乐",

        // ========= 视频RAW/专业视频 =========
        "braw" to "Blackmagic RAW 视频",
        "ari" to "ARRI ARRIRAW 视频",
        "r3d" to "RED REDCODE RAW 视频",

        // ========= 电子书/文档补充 =========
        "chm" to "Windows 压缩帮助",
        "hlp" to "Windows 帮助",
        "lit" to "Microsoft Reader 电子书",

        // ========= P2P/下载 =========
        "torrent" to "BitTorrent 种子",
        "crdownload" to "Chrome 下载临时",
        "part" to "下载临时文件",

        // ========= 临时/系统补充 =========
        "tmp" to "临时文件",
        "temp" to "临时文件",
        "bak" to "备份文件",
        "old" to "旧文件",
        "swp" to "Vim 交换文件",
        "swo" to "Vim 交换文件",
        "swx" to "Vim 交换文件",

        // ========= 压缩补充 =========
        "lrz" to "LRZIP 压缩",
        "zoo" to "ZOO 压缩",
        "zpaq" to "ZPAQ 压缩",

        // ========= CD音频 =========
        "cda" to "CD 音频轨道",

        // ========= 网络/安全补充 =========
        "cert" to "安全证书",
        "cer" to "安全证书",
        "crt" to "安全证书",
        "p12" to "PKCS#12 证书",
        "pfx" to "PFX 证书包",
        "p7b" to "PKCS#7 证书",
        "p7c" to "PKCS#7 加密",
        "p7m" to "PKCS#7 MIME",
        "p7s" to "PKCS#7 签名",
        "der" to "DER 证书",
        "key" to "私钥文件",
        "pub" to "公钥文件",
        "ppk" to "PuTTY 私钥",
        "openssh" to "OpenSSH 密钥",
        "ssh" to "SSH 配置",
        "known_hosts" to "SSH 主机",
        "authorized_keys" to "SSH 授权",
        "pgp" to "PGP 密钥",
        "gpg" to "GNU Privacy Guard",
        "sig" to "签名文件",
        "sign" to "签名",
        "asc" to "ASCII 装甲",

        // ========= 校验和补充 =========
        "md5" to "MD5 校验",
        "sha" to "SHA 校验",
        "sha1" to "SHA-1 校验",
        "sha256" to "SHA-256 校验",
        "sha384" to "SHA-384 校验",
        "sha512" to "SHA-512 校验",
        "crc" to "CRC 校验",
        "sfv" to "SFV 校验",
        "par" to "PAR 恢复",
        "par2" to "PAR2 恢复",

        // ========= 数据库补充 =========
        "db" to "数据库文件",
        "db3" to "数据库 3",
        "sdb" to "SDB 数据库",
        "s3db" to "S3DB 数据库",
        "sl3" to "SQLite 3",
        "sql" to "SQL 脚本",
        "mysql" to "MySQL 脚本",
        "psql" to "PostgreSQL 脚本",
        "pgsql" to "PostgreSQL 脚本",

        // ========= 网络/配置补充 =========
        "htaccess" to "Apache 访问",
        "htpasswd" to "Apache 密码",
        "htgroups" to "Apache 组",
        "npmrc" to "npm 配置",
        "yarnrc" to "Yarn 配置",
        "gitignore" to "Git 忽略",
        "gitattributes" to "Git 属性",
        "gitmodules" to "Git 子模块",
        "editorconfig" to "EditorConfig",
        "dockerfile" to "Dockerfile",
        "dockerignore" to "Docker 忽略",
        "makefile" to "Makefile",
        "mk" to "Makefile 包含",

        // ========= WordPerfect/其他 =========
        "wpd" to "WordPerfect",
        "wps" to "WPS 文字",
        "et" to "WPS 表格",
        "dps" to "WPS 演示",
        "wpt" to "WPS 模板",
        "ett" to "WPS 表格模板",
        "dpt" to "WPS 演示模板",

        // ========= iWork =========
        "pages" to "Pages 文档",
        "numbers" to "Numbers 表格",
        "key" to "Keynote 演示",

        // ========= OneNote =========
        "one" to "OneNote 分区",
        "onepkg" to "OneNote 包",
        "onezip" to "OneNote 压缩",

        null
    ).filterNotNull()

    for ((ext, desc) in docExts) {
        if (map.putIfAbsent(ext, desc) == null) map[ext] = desc
    }

    map
}

/**
 * 根据文件名获取扩展名(小写,去除前导点)
 */
private fun getFileExtension(filename: String): String {
    val lastDot = filename.lastIndexOf('.')
    if (lastDot < 0 || lastDot == filename.length - 1) return ""
    var ext = filename.substring(lastDot + 1).lowercase()
    // 检查双扩展名如 .tar.gz
    val prevDot = filename.lastIndexOf('.', lastDot - 1)
    if (prevDot >= 0) {
        val doubleExt = filename.substring(prevDot + 1).lowercase()
        if (EXTENSION_MAP.containsKey(doubleExt)) {
            ext = doubleExt
        }
    }
    return ext
}

/**
 * 根据扩展名返回文件类型中文描述（支持上千种扩展名映射）
 */
private fun getFileTypeDescription(file: File): String {
    if (file.isDirectory) return "文件夹"
    val ext = getFileExtension(file.name)
    if (ext.isEmpty()) return "未知文件"
    return EXTENSION_MAP[ext] ?: run {
        val upper = ext.uppercase()
        if (upper.length <= 10) "$upper 文件" else "未知文件"
    }
}

/**
 * 返回规范扩展名显示（如 .SH → .sh, .TAR.GZ → .tar.gz）
 */
private fun getCanonicalExtension(file: File): String {
    if (file.isDirectory) return "—"
    val ext = getFileExtension(file.name)
    return if (ext.isEmpty()) "—" else ".$ext"
}