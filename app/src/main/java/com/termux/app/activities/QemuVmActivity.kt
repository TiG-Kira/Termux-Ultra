package com.termux.app.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.termux.R
import com.termux.app.TermuxService
import com.termux.app.compose.*
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * QEMU 虚拟机管理页面。
 * 展示虚拟机列表/空状态，支持新建、编辑、删除、启动虚拟机。
 */
class QemuVmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            KiTerminalTheme {
                QemuVmScreen(
                    onBack = { finish() },
                    onExecuteScript = { name, command ->
                        startTermuxSession(name, command)
                    }
                )
            }
        }
    }

    private var termuxService: TermuxService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TermuxService.LocalBinder
            termuxService = binder.service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            termuxService = null
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, TermuxService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        try { unbindService(serviceConnection) } catch (_: Exception) { }
    }

    private fun startTermuxSession(name: String, command: String) {
        val service = termuxService ?: return
        val newSession = service.createTermuxSession(
            null,
            arrayOf("-c", command),
            null,
            null,
            false,
            name
        )
        newSession?.let {
            val intent = Intent(this, com.termux.app.TermuxActivity::class.java)
            intent.putExtra("sessionHandle", it.getTerminalSession().mHandle)
            startActivity(intent)
        }
    }
}

/**
 * 检查当前运行中的 QEMU 虚拟机数量。
 * 同时覆盖 Termux 原生 / proot 容器两种环境。
 *
 * 实现：直接调用 [com.termux.app.compose.ProcessDetector.countRunningQemu]，
 * 与 TermuxService LiveUpdate 通知中的检测逻辑完全一致，保证虚拟机页面卡片上的
 * "运行中"数量与通知药丸文字同步。
 */
private suspend fun countRunningQemuVms(context: Context): Int {
    return com.termux.app.compose.ProcessDetector.countRunningQemu(context)
}

@Composable
private fun QemuVmScreen(
    onBack: () -> Unit,
    onExecuteScript: (String, String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var vms by remember { mutableStateOf(QemuVmManager.loadVms(context)) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var editingVm by remember { mutableStateOf<QemuVmConfig?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<QemuVmConfig?>(null) }
    var runningVmCount by remember { mutableStateOf(0) }
    val scrollBehavior = MiuixScrollBehavior()

    // 路径迁移状态
    var showMigrationDialog by remember { mutableStateOf(false) }
    var isMigrating by remember { mutableStateOf(false) }
    var migrationResult by remember { mutableStateOf<QemuVmManager.MigrationResult?>(null) }
    var migrationPromptShown by remember { mutableStateOf(false) }

    fun refreshVms() {
        vms = QemuVmManager.loadVms(context)
    }

    LaunchedEffect(Unit) {
        while (true) {
            runningVmCount = countRunningQemuVms(context)
            delay(3000)
        }
    }

    // 页面加载时检测是否需要路径迁移（仅提示一次）
    LaunchedEffect(vms) {
        if (!migrationPromptShown && !QemuVmManager.isMigrationDone(context) && QemuVmManager.needsMigration(vms)) {
            showMigrationDialog = true
            migrationPromptShown = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = "虚拟机",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (vms.isEmpty()) {
                VmEmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    onCreate = {
                        editingVm = null
                        showCreateSheet = true
                    }
                )
            } else {
                VmListScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    vms = vms,
                    runningVmCount = runningVmCount,
                    onStart = { vm ->
                        onExecuteScript(vm.name, vm.generateScript())
                    },
                    onEdit = { vm ->
                        editingVm = vm
                        showCreateSheet = true
                    },
                    onDelete = { vm ->
                        showDeleteConfirm = vm
                    }
                )
            }

            // 手动叠加 FAB，完全控制位置
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 72.dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary)
                    .clickable {
                        editingVm = null
                        showCreateSheet = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = "新建虚拟机",
                    modifier = Modifier.size(24.dp),
                    tint = Color.White
                )
            }
        }

        // Overlay 弹窗必须在 Scaffold 内部，以访问 LocalDialogStates 由 MiuixPopupHost 渲染
        if (showCreateSheet) {
            QemuOnVncSheet(
                show = true,
                existingVm = editingVm,
                onDismiss = {
                    showCreateSheet = false
                    editingVm = null
                    refreshVms()
                },
                onExecuteScript = { name, command ->
                    showCreateSheet = false
                    editingVm = null
                    refreshVms()
                    onExecuteScript(name, command)
                }
            )
        }

        if (showDeleteConfirm != null) {
            OverlayDialog(
                show = true,
                title = "删除虚拟机",
                summary = showDeleteConfirm?.let {
                    "确定删除 \"${it.name}\" 的配置吗？\n注意：磁盘和镜像文件不会被删除，如需彻底删除请手动清除。"
                } ?: "",
                onDismissRequest = { showDeleteConfirm = null },
                content = {
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
                            showDeleteConfirm?.let { vm ->
                                QemuVmManager.deleteVm(context, vm.id)
                                refreshVms()
                                showDeleteConfirm = null
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
                }
            )
        }

        // 路径迁移对话框
        if (showMigrationDialog) {
            OverlayDialog(
                show = true,
                title = "路径迁移",
                summary = "检测到现有虚拟机使用旧版默认路径。\n\n" +
                    "新版本已更新默认位置：\n" +
                    "• 硬盘：\$HOME/virtual_disks/\n" +
                    "• 共享目录：\$HOME/storage/shared/Termux/Sharing/\n\n" +
                    "是否一键迁移硬盘文件和共享目录到新位置？",
                onDismissRequest = {
                    if (!isMigrating) showMigrationDialog = false
                },
                content = {
                if (isMigrating) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "正在迁移，请稍候...",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            text = "稍后",
                            onClick = { showMigrationDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(16.dp))
                        TextButton(
                            text = "一键迁移",
                            onClick = {
                                isMigrating = true
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        QemuVmManager.migratePaths(context)
                                    }
                                    isMigrating = false
                                    migrationResult = result
                                    showMigrationDialog = false
                                    refreshVms()
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

        // 迁移结果对话框
        if (migrationResult != null) {
            OverlayDialog(
                show = true,
                title = "迁移完成",
                summary = migrationResult!!.let { result ->
                    buildString {
                        append("已迁移 ${result.migratedVmCount} 个虚拟机配置。\n")
                        append("成功移动 ${result.movedDiskCount} 个硬盘文件。\n")
                        append("共享目录${if (result.shareDirMoved) "已" else "未"}迁移。")
                        if (result.errors.isNotEmpty()) {
                            append("\n\n错误信息：\n")
                            result.errors.forEach { append("• $it\n") }
                        }
                    }
                },
                onDismissRequest = { migrationResult = null },
                content = {
                Row(horizontalArrangement = Arrangement.Center) {
                    TextButton(
                        text = "确定",
                        onClick = { migrationResult = null },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
                }
            )
        }
    }
}

@Composable
private fun VmEmptyState(
    modifier: Modifier = Modifier,
    onCreate: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_computer),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MiuixTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "暂无虚拟机",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "点击新建按钮创建第一台虚拟机，开始运行你的 QEMU 环境。",
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 48.dp)
        )
        Spacer(Modifier.height(24.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable { onCreate() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
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
    }
}

@Composable
private fun VmListScreen(
    modifier: Modifier = Modifier,
    vms: List<QemuVmConfig>,
    runningVmCount: Int = 0,
    onStart: (QemuVmConfig) -> Unit,
    onEdit: (QemuVmConfig) -> Unit,
    onDelete: (QemuVmConfig) -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 统计卡片
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "虚拟机总数",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        text = vms.size.toString(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "运行中",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        text = runningVmCount.toString(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = "全部虚拟机",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp)
        )

        vms.forEach { vm ->
            VmCard(
                vm = vm,
                onStart = { onStart(vm) },
                onEdit = { onEdit(vm) },
                onDelete = { onDelete(vm) }
            )
        }

        Spacer(Modifier.height(72.dp))
    }
}

@Composable
private fun VmCard(
    vm: QemuVmConfig,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFFAFAFA)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MiuixTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(
                                if (vm.mode == "install_iso") R.drawable.ic_computer else R.drawable.ic_server
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MiuixTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = vm.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${vm.cpuCores}核 / ${vm.memoryMB}MB / ${(vm.machineType ?: "q35").uppercase()} / ${(vm.diskInterface ?: "ide").uppercase()} / VNC:${vm.vncPort}",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
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
