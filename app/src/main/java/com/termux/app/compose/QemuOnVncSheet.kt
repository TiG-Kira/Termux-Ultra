package com.termux.app.compose

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * 新建/编辑 QEMU 虚拟机底部弹窗（Dialog）。
 * 支持选择现有磁盘、从 ISO 安装并新建磁盘、或仅新建空白磁盘。
 * 选定的硬盘镜像/光盘映像文件路径逻辑与现有实现保持一致。
 */
@Composable
fun QemuOnVncSheet(
    show: Boolean,
    existingVm: QemuVmConfig? = null,
    onDismiss: () -> Unit,
    onExecuteScript: (String, String) -> Unit
) {
    val isEditMode = existingVm != null
    val title = if (isEditMode) "编辑 QEMU 虚拟机" else "新建 QEMU 虚拟机"
    val context = LocalContext.current

    OverlayBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = title,
        content = {
            VmWizardContent(
                existingVm = existingVm,
                onComplete = { config ->
                    QemuVmManager.saveVm(context, config)
                    onDismiss()
                    if (isEditMode) {
                        // 编辑模式仅保存配置，不自动启动
                    } else {
                        onExecuteScript(config.name, config.generateScript())
                    }
                },
                onCancel = onDismiss
            )
        }
    )
}

@Composable
private fun VmWizardContent(
    existingVm: QemuVmConfig?,
    onComplete: (QemuVmConfig) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 配置状态
    var vmName by remember { mutableStateOf(existingVm?.name ?: "") }
    var mode by remember { mutableStateOf(existingVm?.mode ?: "existing_disk") }
    var diskPath by remember { mutableStateOf(existingVm?.diskPath ?: "") }
    var newDiskSizeGB by remember { mutableStateOf(existingVm?.newDiskSizeGB ?: 20) }
    var newDiskFormat by remember { mutableStateOf(existingVm?.newDiskFormat ?: "qcow2") }
    var isoPath by remember { mutableStateOf(existingVm?.isoPath ?: "") }
    var mountIso by remember { mutableStateOf(existingVm?.isoPath != null) }
    var cpuCores by remember { mutableStateOf(existingVm?.cpuCores ?: 2) }
    var memoryMB by remember { mutableStateOf(existingVm?.memoryMB ?: 1024) }
    var hasSound by remember { mutableStateOf(existingVm?.hasSound ?: false) }
    var shareDir by remember {
        mutableStateOf(existingVm?.shareDir ?: "\$HOME/storage/shared/qemu_share")
    }
    var bootDevice1 by remember {
        mutableStateOf(existingVm?.bootOrder?.getOrElse(0) { "c" } ?: "c")
    }
    var bootDevice2 by remember {
        mutableStateOf(existingVm?.bootOrder?.getOrElse(1) { "" } ?: "")
    }
    var vncPort by remember { mutableStateOf((existingVm?.vncPort ?: 5900).toString()) }

    // 复制进度状态
    var showCopyProgress by remember { mutableStateOf(false) }
    var copyProgress by remember { mutableFloatStateOf(0f) }
    var copyProgressText by remember { mutableStateOf("正在复制到虚拟机目录...") }

    // install_iso 模式强制挂载 ISO
    if (mode == "install_iso") {
        mountIso = true
    }

    // 统一的文件选择处理：先尝试快速解析，失败则在后台复制并显示进度
    fun handleFileSelected(uri: Uri, defaultName: String, onResult: (String) -> Unit) {
        val quick = tryQuickResolvePath(context, uri)
        if (quick != null) {
            onResult(quick)
            return
        }
        coroutineScope.launch {
            showCopyProgress = true
            copyProgress = 0f
            copyProgressText = "正在复制到虚拟机目录..."
            val result = withContext(Dispatchers.IO) {
                copyToSharedDir(context, uri, defaultName) { p ->
                    copyProgress = p
                }
            }
            showCopyProgress = false
            if (result != null) {
                onResult(result)
            }
        }
    }

    // 文件选择器：磁盘文件
    val diskFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleFileSelected(it, "disk.img") { diskPath = it } }
    }

    // 文件选择器：ISO 文件
    val isoFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleFileSelected(it, "image.iso") { isoPath = it } }
    }

    // 新建磁盘路径（仅在新建模式且未指定路径时自动生成）
    fun ensureCreateDiskPath(): String {
        return if (diskPath.isBlank() || !diskPath.contains("/qemu_disks/")) {
            "\$HOME/storage/shared/qemu_disks/${existingVm?.id ?: java.util.UUID.randomUUID().toString()}.${newDiskFormat}"
        } else {
            diskPath
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 名称
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "虚拟机名称",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = vmName,
                    onValueChange = { vmName = it },
                    label = "请输入虚拟机名称"
                )
            }
        }

        // 提供方式
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        ) {
            Column {
                Row(modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp)) {
                    Text(
                        text = "选择提供方式",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
                RadioButtonPreference(
                    title = "提供现有磁盘",
                    summary = "使用已有的磁盘镜像文件直接启动",
                    selected = mode == "existing_disk",
                    onClick = {
                        mode = "existing_disk"
                        mountIso = isoPath.isNotBlank()
                    }
                )
                RadioButtonPreference(
                    title = "提供安装镜像",
                    summary = "使用 ISO 镜像安装系统，将创建新硬盘",
                    selected = mode == "install_iso",
                    onClick = {
                        mode = "install_iso"
                        mountIso = true
                    }
                )
                RadioButtonPreference(
                    title = "新建空白磁盘",
                    summary = "创建新的空白磁盘镜像，不挂载 ISO",
                    selected = mode == "create_disk",
                    onClick = {
                        mode = "create_disk"
                        mountIso = false
                    }
                )
            }
        }

        // 磁盘镜像
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "磁盘镜像",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))

                if (mode == "existing_disk") {
                    Text(
                        text = if (diskPath.isBlank()) "未选择磁盘文件" else diskPath,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        text = "选择磁盘文件",
                        onClick = { diskFileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                } else {
                    // 新建磁盘：显示大小、格式、路径
                    WindowDropdownPreference(
                        title = "硬盘大小",
                        items = listOf("10 GB", "20 GB", "40 GB", "60 GB", "80 GB"),
                        selectedIndex = listOf(10, 20, 40, 60, 80).indexOf(newDiskSizeGB).coerceAtLeast(0),
                        onSelectedIndexChange = {
                            newDiskSizeGB = listOf(10, 20, 40, 60, 80)[it]
                        }
                    )
                    WindowDropdownPreference(
                        title = "硬盘格式",
                        items = listOf("qcow2", "raw", "vmdk"),
                        selectedIndex = listOf("qcow2", "raw", "vmdk").indexOf(newDiskFormat).coerceAtLeast(0),
                        onSelectedIndexChange = {
                            newDiskFormat = listOf("qcow2", "raw", "vmdk")[it]
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "将创建到：${ensureCreateDiskPath()}",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        // ISO 镜像（install_iso 模式必填；existing_disk / create_disk 可选）
        if (mode == "install_iso" || mountIso) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (mode == "install_iso") "安装镜像 (ISO)" else "ISO 镜像（可选）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (isoPath.isBlank()) "未选择 ISO 文件" else isoPath,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        text = "选择 ISO 文件",
                        onClick = { isoFileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }

        // existing_disk / create_disk 模式下可开关 ISO 挂载
        if (mode != "install_iso") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                SwitchPreference(
                    title = "挂载 ISO 镜像",
                    summary = if (mountIso) "启动时挂载 ISO" else "不挂载 ISO",
                    checked = mountIso,
                    onCheckedChange = {
                        mountIso = it
                        if (!it) isoPath = ""
                    }
                )
            }
        }

        // CPU 核心数 + 内存
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        ) {
            Column {
                Row(modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp)) {
                    Text(
                        text = "CPU 与内存",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
                WindowDropdownPreference(
                    title = "CPU 核心数",
                    items = listOf("1 核", "2 核", "4 核", "8 核"),
                    selectedIndex = listOf(1, 2, 4, 8).indexOf(cpuCores).coerceAtLeast(0),
                    onSelectedIndexChange = { cpuCores = listOf(1, 2, 4, 8)[it] }
                )
                WindowDropdownPreference(
                    title = "内存大小",
                    items = listOf("512 MB", "1024 MB", "2048 MB", "4096 MB"),
                    selectedIndex = listOf(512, 1024, 2048, 4096).indexOf(memoryMB).coerceAtLeast(0),
                    onSelectedIndexChange = { memoryMB = listOf(512, 1024, 2048, 4096)[it] }
                )
            }
        }

        // 设备选项
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        ) {
            Column {
                Row(modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp)) {
                    Text(
                        text = "设备选项",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
                SwitchPreference(
                    title = "配备声音",
                    summary = "使用 HDA 音频设备",
                    checked = hasSound,
                    onCheckedChange = { hasSound = it }
                )
            }
        }

        // 共享目录
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "共享目录",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "使用内部存储在 Termux 中的映射目录（shared）来访问",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = shareDir,
                    onValueChange = { shareDir = it },
                    label = "共享目录路径"
                )
            }
        }

        // 引导顺序
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        ) {
            Column {
                Row(modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp)) {
                    Text(
                        text = "引导顺序",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
                if (mode == "install_iso") {
                    WindowDropdownPreference(
                        title = "第一启动设备",
                        items = listOf("CD-ROM"),
                        selectedIndex = 0,
                        onSelectedIndexChange = {}
                    )
                } else {
                    val bootOptions = listOf("硬盘", "CD-ROM", "无")
                    val bootValues = listOf("c", "d", "")
                    WindowDropdownPreference(
                        title = "第一启动设备",
                        items = bootOptions,
                        selectedIndex = bootValues.indexOf(bootDevice1).coerceAtLeast(0),
                        onSelectedIndexChange = { bootDevice1 = bootValues[it] }
                    )
                }

                val hasIso = mode == "install_iso" || (mode != "install_iso" && mountIso && isoPath.isNotBlank())
                val bootOptions2 = if (hasIso) {
                    listOf("硬盘", "CD-ROM", "无")
                } else {
                    listOf("硬盘", "无")
                }
                val bootValues2 = if (hasIso) {
                    listOf("c", "d", "")
                } else {
                    listOf("c", "")
                }
                WindowDropdownPreference(
                    title = "第二启动设备",
                    items = bootOptions2,
                    selectedIndex = bootValues2.indexOf(bootDevice2).coerceAtLeast(0),
                    onSelectedIndexChange = { bootDevice2 = bootValues2[it] }
                )
            }
        }

        // VNC 端口
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "VNC 端口",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "留空或填写端口号，默认 5900",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = vncPort,
                    onValueChange = { vncPort = it.filter { c -> c.isDigit() } },
                    label = "VNC 端口号"
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 底部按钮
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = "取消",
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = if (existingVm != null) "保存" else "完成",
                onClick = {
                    val port = vncPort.toIntOrNull() ?: 5900
                    val bootList = mutableListOf<String>()
                    if (mode == "install_iso") {
                        bootList.add("d")
                    } else {
                        if (bootDevice1.isNotEmpty()) bootList.add(bootDevice1)
                    }
                    if (bootDevice2.isNotEmpty()) bootList.add(bootDevice2)
                    if (bootList.isEmpty()) bootList.add("c")

                    val actualDiskPath = if (mode == "install_iso" || mode == "create_disk") {
                        ensureCreateDiskPath()
                    } else {
                        diskPath
                    }

                    val actualIsoPath = when {
                        mode == "install_iso" -> isoPath.ifBlank { null }
                        mountIso -> isoPath.ifBlank { null }
                        else -> null
                    }

                    val config = QemuVmConfig(
                        id = existingVm?.id ?: java.util.UUID.randomUUID().toString(),
                        name = vmName.ifBlank { "QEMU VM" },
                        mode = mode,
                        diskPath = actualDiskPath,
                        newDiskSizeGB = newDiskSizeGB,
                        newDiskFormat = newDiskFormat,
                        isoPath = actualIsoPath,
                        cpuCores = cpuCores,
                        memoryMB = memoryMB,
                        hasSound = hasSound,
                        shareDir = shareDir,
                        bootOrder = bootList,
                        vncPort = port
                    )
                    onComplete(config)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    // 复制进度对话框
    if (showCopyProgress) {
        OverlayDialog(
            show = showCopyProgress,
            onDismissRequest = {},
            title = copyProgressText,
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = copyProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "${(copyProgress * 100).toInt()}%",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        )
    }
}

/**
 * 快速解析 URI 到 Termux shared 路径（不复制文件）。
 * 成功返回路径字符串；需要复制时返回 null（由调用方再走 [copyToSharedDir]）。
 */
private fun tryQuickResolvePath(context: Context, uri: Uri): String? {
    val scheme = uri.scheme

    // 1. 通过 ContentResolver DATA 列查询真实文件路径
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val dataIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                if (dataIndex >= 0) {
                    val dataPath = cursor.getString(dataIndex)
                    if (!dataPath.isNullOrBlank()) {
                        val mapped = mapInternalPathToShared(dataPath)
                        if (mapped != null) return mapped
                    }
                }
            }
        }
    } catch (_: Exception) { }

    // 2. file:// scheme
    if ("file".equals(scheme, ignoreCase = true)) {
        val rawPath = uri.path
        if (rawPath != null) {
            return mapInternalPathToShared(rawPath) ?: rawPath
        }
    }

    // 3. content:// scheme：检查常见的 document 路径格式
    if ("content".equals(scheme, ignoreCase = true)) {
        val rawPath = uri.path
        if (rawPath != null) {
            val docPath = android.net.Uri.decode(rawPath)
            val subPath: String? = sequenceOf(
                "primary:",
                "raw:/storage/emulated/0/",
                "raw:/sdcard/",
                "raw:"
            ).map { token ->
                val m = token.toRegex().find(docPath)
                if (m != null) docPath.substring(m.range.last + 1) else null
            }.firstOrNull { !it.isNullOrBlank() }

            if (subPath != null) {
                return normalizeToSharedPath(subPath)
            }
        }
    }

    return null
}

/**
 * 将子路径归一化为 Termux shared 映射路径。
 */
private fun normalizeToSharedPath(subPath: String): String {
    val trimmed = subPath.trim()
    mapInternalPathToShared(trimmed)?.let { return it }
    val relative = trimmed.trimStart('/')
    return "\$HOME/storage/shared/$relative"
}

/**
 * 从 URI 获取安全的文件名。
 */
private fun queryFileName(context: Context, uri: Uri, defaultFileName: String): String {
    var fileName: String? = null
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
    } catch (_: Exception) { }
    if (fileName.isNullOrBlank()) {
        fileName = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: defaultFileName
    }
    return fileName.replace("[^a-zA-Z0-9._\\-]".toRegex(), "_").ifBlank { defaultFileName }
}

/**
 * 从 URI 获取文件大小（字节）。
 */
private fun queryFileSize(context: Context, uri: Uri): Long {
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0) {
                    return cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (_: Exception) { }
    return -1L
}

/**
 * 将 uri 对应的文件流复制到 /sdcard/Download/qemu/ 目录，带进度回调。
 * 返回 Termux shared 映射路径。在 IO 调度器中调用。
 */
private suspend fun copyToSharedDir(
    context: Context,
    uri: Uri,
    defaultFileName: String,
    onProgress: (Float) -> Unit
): String? {
    val fileName = queryFileName(context, uri, defaultFileName)
    val totalSize = queryFileSize(context, uri)

    val sdcard = android.os.Environment.getExternalStorageDirectory().absolutePath
    var targetDir = java.io.File("$sdcard/Download/qemu")
    if (!targetDir.exists() && !targetDir.mkdirs()) {
        val extDir = context.getExternalFilesDir(null)
            ?: java.io.File("${context.filesDir.absolutePath}/shared_qemu_fallback")
        targetDir = java.io.File(extDir, "qemu")
        targetDir.mkdirs()
    }
    val targetFile = java.io.File(targetDir, fileName)

    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(8192 * 4)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (totalSize > 0) {
                        onProgress((copied.toFloat() / totalSize).coerceIn(0f, 1f))
                    }
                }
                output.flush()
            }
        }
        onProgress(1f)
        normalizeToSharedPath(targetFile.absolutePath)
    } catch (e: Exception) {
        null
    }
}

/**
 * 将绝对内部存储路径转换为 Termux 的 shared 映射路径。
 */
private fun mapInternalPathToShared(absPath: String): String? {
    var p = absPath
    if (p.startsWith("/storage/emulated/0/")) {
        p = p.removePrefix("/storage/emulated/0/")
    } else if (p.startsWith("/sdcard/")) {
        p = p.removePrefix("/sdcard/")
    } else {
        return null
    }
    return "\$HOME/storage/shared/$p"
}
