package com.termux.app.compose

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.termux.R

private enum class SheetMode { LIST, WIZARD, EDIT }

/**
 * QEMU on VNC 主弹窗。
 * 无已配置虚拟机时直接进入配置向导；有虚拟机时显示列表。
 */
@Composable
fun QemuOnVncSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onExecuteScript: (String, String) -> Unit
) {
    val context = LocalContext.current
    var vms by remember { mutableStateOf(QemuVmManager.loadVms(context)) }
    var sheetMode by remember { mutableStateOf(if (vms.isEmpty()) SheetMode.WIZARD else SheetMode.LIST) }
    var editingVm by remember { mutableStateOf<QemuVmConfig?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<QemuVmConfig?>(null) }

    fun refreshVms() {
        vms = QemuVmManager.loadVms(context)
    }

    if (show) {
        val title = when (sheetMode) {
            SheetMode.LIST -> "QEMU on VNC"
            SheetMode.WIZARD -> "配置 QEMU 虚拟机"
            SheetMode.EDIT -> "编辑虚拟机"
        }

        OverlayBottomSheet(
            show = show,
            onDismissRequest = {
                onDismiss()
            },
            title = title,
            content = {
                when (sheetMode) {
                    SheetMode.LIST -> VmListContent(
                        vms = vms,
                        onStart = { vm ->
                            onDismiss()
                            onExecuteScript(vm.name, vm.generateScript())
                        },
                        onEdit = { vm ->
                            editingVm = vm
                            sheetMode = SheetMode.EDIT
                        },
                        onDelete = { vm ->
                            showDeleteConfirm = vm
                        },
                        onNewVm = {
                            editingVm = null
                            sheetMode = SheetMode.WIZARD
                        }
                    )
                    SheetMode.WIZARD -> VmWizardContent(
                        existingVm = null,
                        onComplete = { config ->
                            QemuVmManager.saveVm(context, config)
                            refreshVms()
                            onDismiss()
                            onExecuteScript(config.name, config.generateScript())
                        },
                        onCancel = {
                            if (vms.isNotEmpty()) {
                                sheetMode = SheetMode.LIST
                            } else {
                                onDismiss()
                            }
                        }
                    )
                    SheetMode.EDIT -> {
                        val vm = editingVm
                        if (vm != null) {
                            VmWizardContent(
                                existingVm = vm,
                                onComplete = { config ->
                                    QemuVmManager.saveVm(context, config)
                                    refreshVms()
                                    sheetMode = SheetMode.LIST
                                },
                                onCancel = {
                                    sheetMode = SheetMode.LIST
                                }
                            )
                        }
                    }
                }
            }
        )
    }

    // 删除确认对话框
    if (showDeleteConfirm != null) {
        val vm = showDeleteConfirm!!
        OverlayDialog(
            show = true,
            title = "删除虚拟机",
            summary = "确定删除 \"${vm.name}\" 的配置吗？\n注意：磁盘和镜像文件不会被删除，如需彻底删除请手动清除。",
            onDismissRequest = { showDeleteConfirm = null }
        ) {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = "取消",
                    onClick = { showDeleteConfirm = null },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = "删除",
                    onClick = {
                        QemuVmManager.deleteVm(context, vm.id)
                        refreshVms()
                        showDeleteConfirm = null
                        if (vms.isEmpty()) {
                            sheetMode = SheetMode.WIZARD
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

// ==================== VM 列表 ====================

@Composable
private fun VmListContent(
    vms: List<QemuVmConfig>,
    onStart: (QemuVmConfig) -> Unit,
    onEdit: (QemuVmConfig) -> Unit,
    onDelete: (QemuVmConfig) -> Unit,
    onNewVm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        vms.forEach { vm ->
            VmCard(
                vm = vm,
                onStart = { onStart(vm) },
                onEdit = { onEdit(vm) },
                onDelete = { onDelete(vm) }
            )
        }

        Spacer(Modifier.height(4.dp))

        // 新建虚拟机按钮
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onNewVm() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "新建虚拟机",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun VmCard(
    vm: QemuVmConfig,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val cardColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF1A1A1A) else androidx.compose.ui.graphics.Color(0xFFFAFAFA)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = vm.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${vm.cpuCores}核 / ${vm.memoryMB}MB / VNC:${vm.vncPort}",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        text = if (vm.mode == "install_iso") "安装镜像模式" else "现有磁盘模式",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "删除",
                    onClick = onDelete,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "编辑",
                    onClick = onEdit,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "启动",
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

// ==================== 配置向导 ====================

@Composable
private fun VmWizardContent(
    existingVm: QemuVmConfig?,
    onComplete: (QemuVmConfig) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var wizardStep by remember { mutableStateOf(1) }

    // 配置状态
    var vmName by remember { mutableStateOf(existingVm?.name ?: "") }
    var mode by remember { mutableStateOf(existingVm?.mode ?: "existing_disk") }
    var diskPath by remember { mutableStateOf(existingVm?.diskPath ?: "") }
    var newDiskSizeGB by remember { mutableStateOf(existingVm?.newDiskSizeGB ?: 20) }
    var isoPath by remember { mutableStateOf(existingVm?.isoPath ?: "") }
    var cpuCores by remember { mutableStateOf(existingVm?.cpuCores ?: 2) }
    var memoryMB by remember { mutableStateOf(existingVm?.memoryMB ?: 1024) }
    var hasSound by remember { mutableStateOf(existingVm?.hasSound ?: false) }
    var hasCdrom by remember { mutableStateOf(existingVm?.hasCdrom ?: false) }
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

    // install_iso 模式下强制开启 CD-ROM
    if (mode == "install_iso") {
        hasCdrom = true
    }

    // 文件选择器：磁盘文件
    val diskFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { resolveFilePath(context, it) }?.let { diskPath = it }
    }

    // 文件选择器：ISO 文件
    val isoFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { resolveFilePath(context, it) }?.let { isoPath = it }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (wizardStep == 1) {
            // ===== 步骤1: 选择类型 + 名称 =====
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
                            hasCdrom = false
                        }
                    )
                    RadioButtonPreference(
                        title = "提供安装镜像",
                        summary = "使用 ISO 镜像安装系统，将创建新硬盘",
                        selected = mode == "install_iso",
                        onClick = {
                            mode = "install_iso"
                            hasCdrom = true
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                if (existingVm != null) {
                    TextButton(
                        text = "取消",
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(16.dp))
                }
                TextButton(
                    text = "下一步",
                    onClick = {
                        if (vmName.isBlank()) {
                            vmName = if (mode == "install_iso") "新虚拟机" else "Windows VM"
                        }
                        wizardStep = 2
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        } else {
            // ===== 步骤2: 配置参数 =====
            val isInstallMode = mode == "install_iso"
            val isEditMode = existingVm != null

            // 磁盘/ISO 文件
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isInstallMode) "安装镜像 (ISO)" else "磁盘文件",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    if (isInstallMode) {
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
                    } else {
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
                    }
                }
            }

            // 新建硬盘容量（仅 install_iso 模式且非编辑模式）
            if (isInstallMode && !isEditMode) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "新建硬盘容量",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                    WindowDropdownPreference(
                        title = "硬盘大小",
                        items = listOf("10 GB", "20 GB", "40 GB", "60 GB", "80 GB"),
                        selectedIndex = listOf(10, 20, 40, 60, 80).indexOf(newDiskSizeGB).coerceAtLeast(0),
                        onSelectedIndexChange = {
                            newDiskSizeGB = listOf(10, 20, 40, 60, 80)[it]
                        }
                    )
                }
            }

            // 磁盘路径（install_iso 模式且编辑模式下显示，不可更改）
            if (isInstallMode && isEditMode) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "硬盘位置（不可更改）",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = diskPath,
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }

            // CPU 核心数 + 内存
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Column {
                    // 分组标题
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

            // 声音与 CD-ROM
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
                    SwitchPreference(
                        title = "载入 CD-ROM",
                        summary = if (isInstallMode) "安装镜像模式下必须载入" else "载入 ISO 镜像",
                        checked = hasCdrom,
                        onCheckedChange = {
                            if (!isInstallMode) {
                                hasCdrom = it
                            }
                        }
                    )
                }
            }

            // CD-ROM 镜像选择（existing_disk 模式下且开启 CD-ROM）
            if (hasCdrom && !isInstallMode) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
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
                    if (isInstallMode) {
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

                    val bootOptions2 = if (hasCdrom) {
                        listOf("硬盘", "CD-ROM", "无")
                    } else {
                        listOf("硬盘", "无")
                    }
                    val bootValues2 = if (hasCdrom) {
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
                    text = "上一步",
                    onClick = {
                        if (isEditMode) {
                            onCancel()
                        } else {
                            wizardStep = 1
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = if (isEditMode) "保存" else "完成",
                    onClick = {
                        val port = vncPort.toIntOrNull() ?: 5900
                        val bootList = mutableListOf<String>()
                        if (isInstallMode) {
                            bootList.add("d") // CD-ROM first
                        } else {
                            if (bootDevice1.isNotEmpty()) bootList.add(bootDevice1)
                        }
                        if (bootDevice2.isNotEmpty()) bootList.add(bootDevice2)
                        if (bootList.isEmpty()) bootList.add("c")

                        val actualDiskPath = if (isInstallMode && !isEditMode) {
                            "\$HOME/storage/shared/qemu_disks/${existingVm?.id ?: java.util.UUID.randomUUID().toString()}.qcow2"
                        } else {
                            diskPath
                        }

                        val config = QemuVmConfig(
                            id = existingVm?.id ?: java.util.UUID.randomUUID().toString(),
                            name = vmName.ifBlank { "QEMU VM" },
                            mode = mode,
                            diskPath = actualDiskPath,
                            newDiskSizeGB = newDiskSizeGB,
                            isoPath = isoPath.ifBlank { null },
                            cpuCores = cpuCores,
                            memoryMB = memoryMB,
                            hasSound = hasSound,
                            hasCdrom = hasCdrom,
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
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ==================== 文件路径解析 ====================

/**
 * 从 URI 解析文件路径。
 * 尝试获取真实路径，如果路径在内部存储中则转换为 Termux 的 shared 路径。
 * 如果无法获取真实路径，则将文件复制到 shared 目录。
 */
private fun resolveFilePath(context: Context, uri: Uri): String? {
    // 尝试获取文件名
    var fileName: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            fileName = cursor.getString(nameIndex)
        }
    }

    if (fileName.isNullOrBlank()) {
        fileName = uri.lastPathSegment ?: "unknown"
    }

    // 尝试从 URI 获取真实路径
    val path = uri.path
    if (path != null) {
        // file:// URI
        if (path.startsWith("/sdcard/") || path.startsWith("/storage/emulated/0/")) {
            val relativePath = path.substringAfter("/sdcard/").substringAfter("/storage/emulated/0/")
            return "\$HOME/storage/shared/$relativePath"
        }
        if (path.startsWith("/data/data/com.termux/files/home/")) {
            return path
        }
    }

    // 无法直接获取路径，复制文件到 shared 目录
    val targetDir = java.io.File("${context.filesDir.absolutePath}/../../home/storage/shared/qemu")
    if (!targetDir.exists()) targetDir.mkdirs()
    val targetFile = java.io.File(targetDir, fileName)

    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return "\$HOME/storage/shared/qemu/$fileName"
    } catch (e: Exception) {
        return null
    }
}
