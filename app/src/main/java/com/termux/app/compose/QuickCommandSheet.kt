package com.termux.app.compose

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.ui.state.ToggleableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.TermuxActivity
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

// ================================================================
// nova 模式: OverlayBottomSheet (依赖 Scaffold 提供 MiuixPopupHost)
// ================================================================

/**
 * 快捷指令 OverlayBottomSheet（新星模式）。
 * 必须放在 Scaffold 内部使用。
 */
@Composable
fun QuickCommandSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onExecuteCommand: (QuickCommand) -> Unit
) {
    val context = LocalContext.current
    val store = remember { QuickCommandStore.get(context) }
    var commands by remember { mutableStateOf<List<QuickCommand>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (show) commands = store.getAll()
    }

    OverlayBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = "",
        content = {
            QuickCommandPanelContent(
                commands = commands,
                onCommandClick = { cmd ->
                    onDismiss()
                    onExecuteCommand(cmd)
                },
                onDeleteCommand = { cmd -> commands = store.removeById(cmd.id) },
                onAddClick = { showAddDialog = true }
            )
        }
    )

    if (showAddDialog) {
        AddQuickCommandDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { label, command, auto ->
                val item = QuickCommand(label = label, command = command, autoExecute = auto)
                commands = store.add(item)
                showAddDialog = false
                if (auto) {
                    onDismiss()
                    onExecuteCommand(item)
                }
            }
        )
    }
}

// ================================================================
// 经典模式: WindowDialog (独立窗口，不依赖 Scaffold)
// ================================================================

/**
 * 快捷指令 WindowDialog（经典模式）。
 * 独立窗口，不依赖 Scaffold 的 MiuixPopupHost。
 * 放在 TerminalTopBar（56dp ComposeView）里也能正常全屏弹出。
 *
 * content 里用 LazyColumn → 支持上下滑动。
 */
@Composable
fun QuickCommandWindowSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onExecuteCommand: (QuickCommand) -> Unit
) {
    val context = LocalContext.current
    val store = remember { QuickCommandStore.get(context) }
    var commands by remember { mutableStateOf<List<QuickCommand>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (show) commands = store.getAll()
    }

    WindowDialog(
        show = show,
        title = "快捷指令",
        summary = "",
        onDismissRequest = onDismiss,
        content = {
            QuickCommandPanelContent(
                commands = commands,
                onCommandClick = { cmd ->
                    onDismiss()
                    onExecuteCommand(cmd)
                },
                onDeleteCommand = { cmd -> commands = store.removeById(cmd.id) },
                onAddClick = { showAddDialog = true }
            )
        }
    )

    if (showAddDialog) {
        AddQuickCommandDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { label, command, auto ->
                val item = QuickCommand(label = label, command = command, autoExecute = auto)
                commands = store.add(item)
                showAddDialog = false
                if (auto) {
                    onDismiss()
                    onExecuteCommand(item)
                }
            }
        )
    }
}

// ================================================================
// 共享 UI
// ================================================================

/**
 * OverlayBottomSheet / WindowDialog 共用的核心内容。
 * 包括标题栏 + 可滚动列表 + 空提示。
 */
@Composable
private fun QuickCommandPanelContent(
    commands: List<QuickCommand>,
    onCommandClick: (QuickCommand) -> Unit,
    onDeleteCommand: (QuickCommand) -> Unit,
    onAddClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 520.dp)
    ) {
        // OverlayBottomSheet 自带标题栏，这里对两种容器统一：
        // OverlayBottomSheet 下 title=""，让我们自己的标题栏占主导
        // WindowDialog 用参数 title，不再画自定义标题栏
        Text(
            text = "快捷指令",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (commands.isEmpty()) {
            EmptyCommandsHint()
        } else {
            // LazyColumn 提供上下滑动能力
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(commands, key = { it.id }) { cmd ->
                    QuickCommandItem(
                        command = cmd,
                        onClick = { onCommandClick(cmd) },
                        onDelete = { onDeleteCommand(cmd) }
                    )
                }
            }
        }

        // 底部添加按钮（所有模式统一）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(text = "添加 +", onClick = onAddClick)
        }
    }
}

@Composable
private fun QuickCommandItem(
    command: QuickCommand,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = command.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface
            )
            if (command.command.isNotBlank()) {
                Text(
                    text = command.command,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun EmptyCommandsHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "还没有快捷指令",
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "点击右下角「添加 +」开始",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun AddQuickCommandDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, command: String, autoExecute: Boolean) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var autoExecute by remember { mutableStateOf(true) }

    // 用 miuix WindowDialog（独立窗口），nova / 经典两种模式都能用，
    // 不依赖 Scaffold 的 MiuixPopupHost，避免经典模式下 "添加 +" 无响应。
    WindowDialog(
        show = true,
        title = "添加快捷指令",
        summary = "",
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .heightIn(min = 180.dp)
            ) {
                TextField(
                    value = label,
                    onValueChange = { label = it },
                    label = "名称",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = command,
                    onValueChange = { command = it },
                    label = "命令",
                    useLabelAsPlaceholder = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    maxLines = Int.MAX_VALUE,
                    minLines = 2
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        state = if (autoExecute) ToggleableState.On else ToggleableState.Off,
                        onClick = { autoExecute = !autoExecute },
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "添加后自动执行",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = "添加",
                        onClick = {
                            if (label.isNotBlank() && command.isNotBlank()) {
                                onConfirm(label.trim(), command, autoExecute)
                            }
                        },
                        enabled = label.isNotBlank() && command.isNotBlank()
                    )
                }
            }
        }
    )
}

// ================================================================
// 核心逻辑（纯函数 / 执行入口）
// ================================================================

fun buildQuickCommandText(command: QuickCommand): String {
    return if (command.autoExecute) command.command + "\r" else command.command
}

fun executeQuickCommand(context: Context, command: QuickCommand) {
    // Compose 的 LocalContext.current 可能是 ContextWrapper（如 ComposeView 的 context），
    // 必须向上遍历找到真正的 TermuxActivity，直接 `as?` 会静默失败导致点击无响应。
    val activity = findActivityFromContext(context, TermuxActivity::class.java) ?: return
    val text = buildQuickCommandText(command)

    // Nova（Kotlin+Compose）模式下，Java 侧会话列表为空、mTerminalView 不被使用，
    // activity.currentSession 恒为 null。必须改用 ComposeSessionManager 中的活跃会话，
    // 否则快捷指令在 Nova 模式下会静默失效。
    if (TerminalRuntimeCore.isComposeMode(context)) {
        val composeSession = com.termux.app.compose.terminal.ComposeSessionManager
            .getInstance(context).currentSession
        if (composeSession != null && composeSession.isRunning) {
            composeSession.write(text)
        }
        return
    }

    val session = activity.currentSession ?: return
    if (!session.isRunning) return
    session.write(text)
}
