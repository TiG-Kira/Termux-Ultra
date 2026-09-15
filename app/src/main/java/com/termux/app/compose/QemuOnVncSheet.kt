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
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
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
    // 音频模式：关闭 / VNC RFB / PulseAudio跟随页面 / PulseAudio持续播放
    var audioMode by remember {
        mutableStateOf(
            // 优先使用已保存的 audioMode；若为空则按旧的 hasSound 回填
            when {
                existingVm?.audioMode != null && existingVm.audioMode.isNotBlank() -> existingVm.audioMode
                existingVm?.hasSound == true -> AudioMode.VNC_RFB
                else -> AudioMode.DISABLED
            }
        )
    }
    // 向后兼容：用 audioMode 派生 hasSound
    val hasSound = audioMode != AudioMode.DISABLED
    var shareDir by remember {
        mutableStateOf(existingVm?.shareDir ?: "\$HOME/storage/shared/Termux/Sharing")
    }
    var bootDevice1 by remember {
        mutableStateOf(existingVm?.bootOrder?.getOrElse(0) { "c" } ?: "c")
    }
    var bootDevice2 by remember {
        mutableStateOf(existingVm?.bootOrder?.getOrElse(1) { "" } ?: "")
    }
    var vncPort by remember { mutableStateOf((existingVm?.vncPort ?: 5900).toString()) }
    var diskInterface by remember { mutableStateOf(existingVm?.diskInterface ?: "ide") }
    var machineType by remember { mutableStateOf(existingVm?.machineType ?: "q35") }
    var cpuModelOverride by remember { mutableStateOf(existingVm?.cpuModelOverride ?: "") }
    // Tab: 0=手动配置, 1=Agent 自动配置
    var selectedTab by remember { mutableStateOf(0) }

    // ── Agent 自动配置相关 state ──
    var agentPrompt by remember { mutableStateOf("") }
    var agentLoading by remember { mutableStateOf(false) }
    var agentError by remember { mutableStateOf<String?>(null) }
    var agentSuggestion by remember { mutableStateOf<AgentVmSuggestion?>(null) }
    // ISO 识别结果提示
    var detectedSystem by remember { mutableStateOf<IsoSystemInfo?>(null) }
    // 标记名称是否由 ISO 识别自动填充（用户手动修改后不再自动覆盖）
    var nameAutoFilled by remember { mutableStateOf(false) }

    // 复制进度状态
    var showCopyProgress by remember { mutableStateOf(false) }
    var copyProgress by remember { mutableFloatStateOf(0f) }
    var copyProgressText by remember { mutableStateOf("正在复制到虚拟机目录...") }

    // 文件来源选择："disk"=选择磁盘文件；"iso"=选择ISO文件；null=不显示
    var fileSourceTarget by remember { mutableStateOf<String?>(null) }
    // Termux 内部文件选择器
    var showInternalDiskPicker by remember { mutableStateOf(false) }
    var showInternalIsoPicker by remember { mutableStateOf(false) }

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
        uri?.let {
            handleFileSelected(it, "image.iso") { path ->
                isoPath = path
                // 智能识别 ISO 包含的操作系统
                val detected = detectIsoSystem(path)
                detectedSystem = detected
                if (detected != null) {
                    // 如果用户未填写名称或名称由上次识别自动填充，则使用系统名
                    if (vmName.isBlank() || nameAutoFilled) {
                        vmName = detected.systemName
                        nameAutoFilled = true
                    }
                    // 应用推荐配置（用户仍可更改）
                    machineType = detected.recommendedMachineType
                    diskInterface = detected.recommendedDiskInterface
                    cpuCores = detected.recommendedCpuCores
                    memoryMB = detected.recommendedMemoryMB
                }
            }
        }
    }

    // 新建磁盘路径（仅在新建模式且未指定路径时自动生成）
    fun ensureCreateDiskPath(): String {
        return if (diskPath.isBlank() || (!diskPath.contains("/virtual_disks/") && !diskPath.contains("/qemu_disks/"))) {
            "\$HOME/virtual_disks/${existingVm?.id ?: java.util.UUID.randomUUID().toString()}.${newDiskFormat}"
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
        // 名称 Card —— 公共，始终显示
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
                    onValueChange = {
                        vmName = it
                        nameAutoFilled = false
                    },
                    label = "请输入虚拟机名称"
                )
            }
        }

        // Tab 切换 —— Agent 自动配置 / 手动配置
        TabRowWithContour(
            tabs = listOf("手动配置", "Agent 自动配置"),
            selectedTabIndex = selectedTab,
            onTabSelected = { selectedTab = it },
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        // ── Tab 内容区 ──
        if (selectedTab == 1) {
            // ============ Agent 自动配置 Tab ============
            AgentVmConfigTab(
                context = context,
                coroutineScope = coroutineScope,
                agentPrompt = agentPrompt,
                onAgentPromptChange = { agentPrompt = it },
                agentLoading = agentLoading,
                agentError = agentError,
                agentSuggestion = agentSuggestion,
                onApplySuggestion = { s ->
                    // Agent 建议：只覆盖硬件配置（机型/CPU/内存/硬盘大小建议）
                    machineType = s.machineType
                    cpuModelOverride = s.cpuModelOverride
                    cpuCores = s.cpuCores
                    memoryMB = s.memoryMB
                    if (s.recommendedDiskSizeGB != null) {
                        newDiskSizeGB = s.recommendedDiskSizeGB
                    }
                    // 自动切换回手动 Tab，让用户调整声音/ISO/磁盘等个性设置
                    selectedTab = 0
                    agentSuggestion = null
                    agentError = null
                },
                onGenerate = {
                    val cfg = AiTermuxPrefs.getConfig(context)
                    if (!cfg.isConfigured) {
                        agentError = "Agent 未配置，请先在「设置 → AI Agent」中配置 API Key 和模型"
                        return@AgentVmConfigTab
                    }
                    agentLoading = true
                    agentError = null
                    agentSuggestion = null
                    coroutineScope.launch {
                        try {
                            val result = AiApiClient.chat(
                                context, cfg.providerConfig,
                                listOf(
                                    OpenAiMessage("system", QEMU_AGENT_SYSTEM_PROMPT),
                                    OpenAiMessage("user", agentPrompt)
                                )
                            )
                            agentLoading = false
                            if (result.error != null) {
                                agentError = "Agent 调用失败: ${result.error?.message}"
                            } else {
                                val text = result.choices.firstOrNull()?.message?.content ?: ""
                                val parsed = parseAgentVmSuggestion(text)
                                if (parsed != null) {
                                    agentSuggestion = parsed
                                } else {
                                    agentError = "Agent 返回内容无法解析，原始回复: ${text.take(200)}"
                                }
                            }
                        } catch (e: Exception) {
                            agentLoading = false
                            agentError = "Agent 调用异常: ${e.message}"
                        }
                    }
                }
            )
        } else {
            // ============ 手动配置 Tab ============
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
                            onClick = { fileSourceTarget = "disk" },
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
                            onClick = { fileSourceTarget = "iso" },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                        // 显示 ISO 识别结果
                        if (detectedSystem != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "识别到系统: ${detectedSystem!!.systemName}\n已自动应用推荐配置（可修改）",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.primary
                            )
                        }
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
                            if (!it) {
                                isoPath = ""
                                detectedSystem = null
                            }
                        }
                    )
                }
            }

            // CPU 核心数 + CPU 类型 + 内存
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
                    // CPU 类型：用户可选，支持自定义（留空则用 QEMU 默认）
                    val cpuModels = listOf("不指定（QEMU 自动）", "host", "max", "qemu64", "qemu64-v2", "epyc", "epyc-v2", "epyc-v3", "x86_64-v2", "x86_64-v3", "x86_64-v4", "kvm64")
                    val cpuModelLabels = listOf("不指定（QEMU 自动）", "host (宿主机直通)", "max (最大特性)", "qemu64 (基础 x86_64)", "qemu64-v2 (基础+)", "EPYC (AMD)", "EPYC-v2 (AMD Zen2)", "EPYC-v3 (AMD Zen3)", "x86_64-v2 (Haswell)", "x86_64-v3 (Broadwell)", "x86_64-v4 (Skylake)", "kvm64 (KVM 默认)")
                    val currentCpuModelIndex = if (cpuModelOverride.isBlank()) 0 else cpuModels.indexOf(cpuModelOverride).coerceAtLeast(0)
                    WindowDropdownPreference(
                        title = "CPU 类型",
                        items = cpuModelLabels,
                        selectedIndex = currentCpuModelIndex,
                        onSelectedIndexChange = { idx ->
                            cpuModelOverride = if (idx == 0) "" else cpuModels[idx]
                        }
                    )
                    WindowDropdownPreference(
                        title = "内存大小",
                        items = listOf("512 MB", "1024 MB", "2048 MB", "4096 MB", "8192 MB"),
                        selectedIndex = listOf(512, 1024, 2048, 4096, 8192).indexOf(memoryMB).coerceAtLeast(0),
                        onSelectedIndexChange = { memoryMB = listOf(512, 1024, 2048, 4096, 8192)[it] }
                    )
                }
            }

            // 虚拟PC类型与硬盘接口
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Column {
                    Row(modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp)) {
                        Text(
                            text = "机型与硬盘接口",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                    WindowDropdownPreference(
                        title = "虚拟PC类型",
                        items = listOf("Q35 (现代PC)", "PC (i440fx 传统PC)", "ISA PC (老式PC)"),
                        selectedIndex = listOf("q35", "pc", "isapc").indexOf(machineType).coerceAtLeast(0),
                        onSelectedIndexChange = {
                            machineType = listOf("q35", "pc", "isapc")[it]
                        }
                    )
                    WindowDropdownPreference(
                        title = "硬盘连接方式",
                        items = listOf("IDE (兼容性最好)", "VirtIO (高性能)", "SATA (AHCI)", "SCSI (virtio-scsi)"),
                        selectedIndex = listOf("ide", "virtio", "sata", "scsi").indexOf(diskInterface).coerceAtLeast(0),
                        onSelectedIndexChange = {
                            diskInterface = listOf("ide", "virtio", "sata", "scsi")[it]
                        }
                    )
                }
            }

            // 音频输出模式 —— 只在手动 Tab 里，Agent 不碰
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Column {
                    Row(modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp)) {
                        Text(
                            text = "音频输出模式",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                    RadioButtonPreference(
                        title = "关闭",
                        summary = "虚拟机无声音输出",
                        selected = audioMode == AudioMode.DISABLED,
                        onClick = { audioMode = AudioMode.DISABLED }
                    )
                    RadioButtonPreference(
                        title = "VNC RFB 扩展（推荐）",
                        summary = if (shouldUseQemuInContainer()) {
                            "通过 VNC 连接直接传递音频；容器内 QEMU 不支持时自动回退到 PulseAudio"
                        } else {
                            "通过 VNC 连接直接传递虚拟机声音，客户端无需额外配置"
                        },
                        selected = audioMode == AudioMode.VNC_RFB,
                        onClick = { audioMode = AudioMode.VNC_RFB }
                    )
                    RadioButtonPreference(
                        title = "PulseAudio - 跟随 VNC 页面",
                        summary = "进入 VNC 页面时开始播放声音，退出页面时停止播放；使用容器/原生 PulseAudio 服务",
                        selected = audioMode == AudioMode.PA_FOLLOW_SCREEN,
                        onClick = { audioMode = AudioMode.PA_FOLLOW_SCREEN }
                    )
                    RadioButtonPreference(
                        title = "PulseAudio - 持续播放",
                        summary = "虚拟机声音持续播放（即使关闭 VNC 页面），可用任意 PulseAudio 客户端收听",
                        selected = audioMode == AudioMode.PA_PERSIST,
                        onClick = { audioMode = AudioMode.PA_PERSIST }
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
        }

        Spacer(Modifier.height(8.dp))

        // 底部按钮 —— 公共，始终显示
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
                        hasSound = hasSound,          // 旧字段（向后兼容）
                        audioMode = audioMode,        // 新字段：用户选择的音频模式
                        shareDir = shareDir,
                        bootOrder = bootList,
                        vncPort = port,
                        diskInterface = diskInterface,
                        machineType = machineType,
                        cpuModelOverride = cpuModelOverride.ifBlank { null }
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

    // 文件来源选择对话框：Termux 内部 vs 外部系统选择器
    if (fileSourceTarget != null) {
        OverlayDialog(
            show = true,
            title = if (fileSourceTarget == "disk") "选择磁盘文件方式" else "选择 ISO 文件方式",
            summary = "请选择文件来源：\n\n" +
                "• Termux 环境内：浏览 /data/data/com.termux 下的文件（如 \$HOME/virtual_disks/）\n" +
                "• 外部存储：使用系统文件选择器选择 Termux 之外的文件（自动复制到内部）",
            onDismissRequest = { fileSourceTarget = null },
            content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "Termux 环境内",
                    onClick = {
                        val target = fileSourceTarget
                        fileSourceTarget = null
                        when (target) {
                            "disk" -> showInternalDiskPicker = true
                            "iso" -> showInternalIsoPicker = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                TextButton(
                    text = "外部存储（系统选择器）",
                    onClick = {
                        val target = fileSourceTarget
                        fileSourceTarget = null
                        when (target) {
                            "disk" -> diskFileLauncher.launch(arrayOf("*/*"))
                            "iso" -> isoFileLauncher.launch(arrayOf("*/*"))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            }
        )
    }

    // 磁盘文件：Termux 内部选择器
    TermuxInternalFilePicker(
        show = showInternalDiskPicker,
        title = "选择磁盘文件",
        fileExtensions = listOf("qcow2", "img", "raw", "vmdk", "vdi", "vpc", "qcow", "qed"),
        onDismiss = { showInternalDiskPicker = false },
        onFileSelected = { path ->
            diskPath = path
            showInternalDiskPicker = false
        }
    )

    // ISO 文件：Termux 内部选择器
    TermuxInternalFilePicker(
        show = showInternalIsoPicker,
        title = "选择 ISO 文件",
        fileExtensions = listOf("iso"),
        onDismiss = { showInternalIsoPicker = false },
        onFileSelected = { path ->
            isoPath = path
            showInternalIsoPicker = false
            // 智能识别 ISO
            val detected = detectIsoSystem(path)
            detectedSystem = detected
            if (detected != null) {
                if (vmName.isBlank() || nameAutoFilled) {
                    vmName = detected.systemName
                    nameAutoFilled = true
                }
                machineType = detected.recommendedMachineType
                diskInterface = detected.recommendedDiskInterface
                cpuCores = detected.recommendedCpuCores
                memoryMB = detected.recommendedMemoryMB
            }
        }
    )
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


// ============================================================================
// Agent 自动配置辅助：数据类、解析器、Composable UI
// ============================================================================

/** Agent 建议的虚拟机硬件配置（只包含硬件，不含声音/ISO/磁盘选择） */
data class AgentVmSuggestion(
    val machineType: String,             // "q35" / "pc" / "isapc"
    val cpuModelOverride: String?,       // "host" / "max" / "qemu64" / null (= 不指定)
    val cpuCores: Int,                   // 1/2/4/8
    val memoryMB: Int,                   // 512/1024/2048/4096/8192
    val diskInterface: String? = null,   // "ide"/"virtio"/"sata"/"scsi" (可选)
    val recommendedDiskSizeGB: Int? = null,  // 建议硬盘大小 (可选)
    val explanation: String = ""         // Agent 解释文字
)

/** 给 Agent 的 system prompt，要求返回严格的 JSON */
private val QEMU_AGENT_SYSTEM_PROMPT = """
你是 QEMU 虚拟机硬件配置专家。用户会用一句话描述他们的使用需求，你需要为他们推荐最优的 QEMU 虚拟机硬件配置。

**重要规则**：
1. 只推荐**硬件配置**：机器类型、CPU 型号、CPU 核心数、内存大小、硬盘接口、建议硬盘大小
2. **绝对不要**推荐声音设置、ISO 选择、磁盘文件选择、共享目录、引导顺序等个性/手动项
3. 根据用户需求的轻/中/重程度合理分配资源，不要过度配置

**机器类型**（machineType）：
- q35: 现代 PC，推荐首选，兼容好
- pc (i440fx): 传统 PC，某些老系统需要
- isapc: 超级老式 ISA PC，极少使用

**CPU 类型**（cpuModelOverride，可留空 = 不指定用 QEMU 默认）：
- host: 直通宿主机 CPU 特性，性能最好但兼容性一般
- max: 最大 CPU 特性集，兼容性好但性能略低于 host
- qemu64 / qemu64-v2: 基础 x86_64，兼容性最好
- epyc / epyc-v2 / epyc-v3: AMD EPYC
- x86_64-v2 / v3 / v4: Intel 各代架构模拟
- kvm64: KVM 默认类型

**硬盘接口**（diskInterface）：
- ide: 兼容性最好，推荐首选
- virtio: 高性能，部分老系统无驱动
- sata: AHCI，较新
- scsi (virtio-scsi): 高性能

**资源限制**：
- CPU 核心数 1-8，内存 512-8192MB
- 建议硬盘大小 10-80GB

**返回格式**：严格的 JSON，不要有 markdown 代码块标记，不要有解释性文字包裹 JSON。

{
  "machineType": "q35",
  "cpuModelOverride": null,
  "cpuCores": 4,
  "memoryMB": 2048,
  "diskInterface": "ide",
  "recommendedDiskSizeGB": 40,
  "explanation": "简明扼要的推荐理由（1-2 句）"
}

仅输出 JSON，不要其他内容。
"""

/** 从 Agent 回复文本中解析 AgentVmSuggestion（容错：处理 markdown 代码块、前后文本） */
private fun parseAgentVmSuggestion(raw: String): AgentVmSuggestion? {
    // 1. 剥除 markdown 代码块
    val cleaned = raw
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    // 2. 找第一个 { 到最后一个 } 的 JSON
    val start = cleaned.indexOf(\"{\")
    val end = cleaned.lastIndexOf(\"}\")
    if (start < 0 || end <= start) return null
    val jsonStr = cleaned.substring(start, end + 1)
    return try {
        val gson = com.google.gson.Gson()
        gson.fromJson(jsonStr, AgentVmSuggestion::class.java)
    } catch (_: Exception) {
        null
    }
}

/** Agent 自动配置 Tab 的 UI 组件 */
@Composable
private fun AgentVmConfigTab(
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    agentPrompt: String,
    onAgentPromptChange: (String) -> Unit,
    agentLoading: Boolean,
    agentError: String?,
    agentSuggestion: AgentVmSuggestion?,
    onApplySuggestion: (AgentVmSuggestion) -> Unit,
    onGenerate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 一句话描述输入
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "描述你的虚拟机需求",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Agent 会根据你的描述自动推荐合适的硬件配置",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = agentPrompt,
                    onValueChange = onAgentPromptChange,
                    label = "例如: Windows 11 虚拟机，用于轻度办公",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = if (agentLoading) "正在分析..." else "让 Agent 帮我配置",
                    onClick = onGenerate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !agentLoading && agentPrompt.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }

        // Agent 错误提示
        if (agentError != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚠️ $agentError",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.error
                    )
                }
            }
        }

        // Agent 加载中
        if (agentLoading) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Agent 正在分析需求...",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        // Agent 建议结果展示
        if (agentSuggestion != null && !agentLoading) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Agent 推荐的硬件配置",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    // 建议详情列表
                    listOfNotNull(
                        "机器类型: ${agentSuggestion.machineType}",
                        agentSuggestion.cpuModelOverride?.let { "CPU 类型: $it" } ?: "CPU 类型: QEMU 自动",
                        "CPU 核心数: ${agentSuggestion.cpuCores} 核",
                        "内存大小: ${agentSuggestion.memoryMB} MB",
                        agentSuggestion.diskInterface?.let { "硬盘接口: $it" },
                        agentSuggestion.recommendedDiskSizeGB?.let { "建议硬盘: $it GB" }
                    ).forEach { line ->
                        Text(
                            text = "• $line",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                    if (agentSuggestion.explanation.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = agentSuggestion.explanation,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = "应用建议",
                            onClick = { onApplySuggestion(agentSuggestion) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "应用后会自动切换回「手动配置」Tab，方便你调整声音/ISO/磁盘等个性设置",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        // 使用说明
        if (agentSuggestion == null && agentError == null && !agentLoading) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "使用说明",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "• 在上方用一句话描述你的虚拟机用途",
                        "• Agent 会推荐合适的机器类型 / CPU / 内存等硬件",
                        "• 点击「应用建议」后切换到手动 Tab 调整声音/ISO/磁盘",
                        "• Agent 不会替你决定声音、磁盘文件、ISO 镜像"
                    ).forEach { line ->
                        Text(
                            text = line,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}

