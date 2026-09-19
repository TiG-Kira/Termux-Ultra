package com.termux.app.compose

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 快捷指令 BottomSheet。
 *
 * 标题栏（放在 content 顶部，不复用 OverlayBottomSheet 内置 title，
 * 因为需要在左右各放一个图标按钮）：
 *   ┌── [×] 快捷指令         [+] ──┐
 *   │  指令列表                      │
 *   └───────────────────────────────┘
 *
 * - 左侧 [×]：关闭 sheet（不执行任何指令）。
 * - 右侧 [+]：弹出添加对话框。
 * - 列表每项点击 → 关闭 sheet 并把指令交回调用方执行。
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

    // 每次 sheet 打开或关闭时刷新列表（用户可能在别处修改了数据）
    LaunchedEffect(show) {
        if (show) commands = store.getAll()
    }

    OverlayBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        // 用空 title：我们自己在 content 顶部画标题栏
        title = "",
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 520.dp)
            ) {
                // === 自定义标题栏 ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：关闭按钮
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }

                    // 中间标题
                    Text(
                        text = "快捷指令",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 右侧：添加按钮
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }

                // === 列表 ===
                if (commands.isEmpty()) {
                    EmptyCommandsHint()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(commands, key = { it.label }) { cmd ->
                            QuickCommandItem(
                                command = cmd,
                                onClick = {
                                    onDismiss()
                                    onExecuteCommand(cmd)
                                },
                                onDelete = {
                                    commands = store.remove(cmd.label)
                                }
                            )
                        }
                    }
                }
            }
        }
    )

    if (showAddDialog) {
        AddQuickCommandDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { label, command, auto ->
                val item = QuickCommand(label = label, command = command, autoExecute = auto)
                commands = store.add(item)
                showAddDialog = false
            }
        )
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
            text = "点击右上角 + 添加",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

/**
 * 简易的添加对话框。使用 miuix 的 OverlayDialog / AlertDialog 风格
 * 会比较复杂，这里先用 OverlayDialog 包一个 Column + 两个 TextButton。
 *
 * 注意：这里的 onConfirm 不会检查空 label/command——调用方也没理由存空值，
 * 真正的健壮性留给单元测试覆盖 store 层即可。
 */
@Composable
private fun AddQuickCommandDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, command: String, autoExecute: Boolean) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var autoExecute by remember { mutableStateOf(true) }
    val context = LocalContext.current

    OverlayBottomSheet(
        show = true,
        onDismissRequest = onDismiss,
        title = "添加快捷指令",
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .heightIn(min = 180.dp)
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("命令", fontSize = 13.sp) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = autoExecute,
                        onCheckedChange = { autoExecute = it }
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
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (label.isNotBlank() && command.isNotBlank()) {
                                onConfirm(label.trim(), command, autoExecute)
                            }
                        },
                        enabled = label.isNotBlank() && command.isNotBlank()
                    ) { Text("添加") }
                }
            }
        }
    )
}

/**
 * 根据一条 QuickCommand 计算要写入终端的最终文本。
 * 把核心逻辑抽成纯函数方便单元测试；实际写入 session.write()
 * 由调用方负责。
 */
fun buildQuickCommandText(command: QuickCommand): String {
    return if (command.autoExecute) command.command + "\r" else command.command
}

/**
 * 将一条快捷指令送到终端执行：写入命令文本，若 [autoExecute] 为真则追加 `\r`。
 *
 * @param context 用于查找当前会话（Compose 模式下 TermuxActivity 通过 context 可达）。
 * @param command 要执行的快捷指令。
 */
fun executeQuickCommand(context: Context, command: QuickCommand) {
    val activity = context as? TermuxActivity ?: return
    val session = activity.currentSession ?: return
    if (!session.isRunning) return
    session.write(buildQuickCommandText(command))
}
