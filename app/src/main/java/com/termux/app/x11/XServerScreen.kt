package com.termux.app.x11

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.shared.termux.TermuxConstants
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import com.termux.app.compose.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 远程页「XServer」标签：连接本机 Termux 的 X Server（经伴生应用渲染），
 * 或保存并连接远程主机的 X Server。连接动作 = 新建一个导出了 DISPLAY 的终端会话。
 */
@Composable
fun XServerScreen(
    connections: MutableList<XServerConnection>,
    termuxService: com.termux.app.TermuxService?,
    addRequested: Boolean,
    onAddRequestedConsumed: () -> Unit,
    nestedScrollConnection: NestedScrollConnection? = null,
    navBarBottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val showAddDialog = remember { mutableStateOf(false) }
    val showEditDialog = remember { mutableStateOf(false) }
    val editingConnection = remember { mutableStateOf<XServerConnection?>(null) }

    if (addRequested && !showAddDialog.value) {
        showAddDialog.value = true
        onAddRequestedConsumed()
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = navBarBottomPadding + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .let {
                if (nestedScrollConnection != null) {
                    it.nestedScroll(nestedScrollConnection)
                } else {
                    it
                }
            }
    ) {
        item {
            XServerConnectionCard(
                connection = XServerConnection.localTermux(),
                onConnect = { connectToXServer(context, termuxService, XServerConnection.localTermux()) }
            )
        }
        if (connections.isEmpty()) {
            item {
                EmptyXServerState(message = "没有远程 XServer 连接，点右上角添加")
            }
        } else {
            items(connections) { conn ->
                XServerConnectionCard(
                    connection = conn,
                    onConnect = { connectToXServer(context, termuxService, conn) },
                    onEdit = {
                        editingConnection.value = conn
                        showEditDialog.value = true
                    },
                    onDelete = {
                        XServerConnectionManager(context).deleteConnection(conn.id)
                        connections.remove(conn)
                    }
                )
            }
        }
    }

    if (showAddDialog.value) {
        XServerEditDialog(
            connection = null,
            onSave = { conn ->
                XServerConnectionManager(context).saveConnection(conn)
                connections.add(conn)
                showAddDialog.value = false
            },
            onDismiss = { showAddDialog.value = false }
        )
    }

    if (showEditDialog.value && editingConnection.value != null) {
        XServerEditDialog(
            connection = editingConnection.value,
            onSave = { conn ->
                XServerConnectionManager(context).saveConnection(conn)
                val index = connections.indexOfFirst { it.id == conn.id }
                if (index >= 0) connections[index] = conn
                showEditDialog.value = false
            },
            onDismiss = { showEditDialog.value = false }
        )
    }
}

@Composable
private fun XServerConnectionCard(
    connection: XServerConnection,
    onConnect: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onConnect() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_extension),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connection.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "DISPLAY ${connection.displayValue}",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 18.sp
                )
                if (connection.isFromTermux) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "来自 Termux",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            }

            if (!connection.isFromTermux) {
                Row {
                    IconButton(onClick = { onEdit?.invoke() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = "编辑",
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { onDelete?.invoke() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = "删除",
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyXServerState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_extension),
                contentDescription = null,
                modifier = Modifier.size(36.dp).alpha(0.6f),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            lineHeight = 22.sp
        )
    }
}

@Composable
private fun XServerEditDialog(
    connection: XServerConnection?,
    onSave: (XServerConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val isEdit = connection != null
    val name = remember { mutableStateOf(connection?.name ?: "") }
    val host = remember { mutableStateOf(connection?.host ?: "") }
    val display = remember { mutableStateOf((connection?.displayNumber ?: 0).toString()) }
    val showDialog = remember { mutableStateOf(true) }

    OverlayDialog(
        show = showDialog.value,
        onDismissRequest = {
            showDialog.value = false
            onDismiss()
        },
        title = if (isEdit) "编辑连接" else "添加连接",
        content = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = name.value,
                    onValueChange = { name.value = it },
                    label = "名称"
                )

                TextField(
                    value = host.value,
                    onValueChange = { host.value = it },
                    label = "主机地址"
                )

                TextField(
                    value = display.value,
                    onValueChange = { display.value = it },
                    label = "Display 编号（:0 填 0）"
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        text = "取消",
                        onClick = {
                            showDialog.value = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "保存",
                        onClick = {
                            val conn = XServerConnection(
                                id = connection?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name.value,
                                host = host.value,
                                displayNumber = display.value.toIntOrNull() ?: 0
                            )
                            showDialog.value = false
                            onSave(conn)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

/**
 * 连接 XServer：新建一个导出了 DISPLAY 的终端会话并进入控制台。
 *
 * 本机条目在终端进程空间里用 app_process 直启内嵌 X Server（CmdEntryPoint，同上游
 * `termux-x11 :N` 客户端命令），X Server 就绪后会向本应用广播 ACTION_START 并拉起
 * 渲染 Activity；APK 里没有内嵌 X Server 时回退到官方伴生应用。
 */
fun connectToXServer(context: Context, termuxService: com.termux.app.TermuxService?, connection: XServerConnection) {
    if (connection.isFromTermux && runCatching { Class.forName("com.termux.x11.CmdEntryPoint") }.isFailure) {
        // 内嵌 X Server 不在（AAR 未接线/被裁剪），退回伴生应用
        val launched = runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage("com.termux.x11")
            if (intent != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (!launched) {
            Toast.makeText(context, "未检测到 Termux:X11 伴生应用，无法启动本机 X Server", Toast.LENGTH_LONG).show()
        }
    }

    if (termuxService == null) {
        Toast.makeText(context, "核心服务未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
        return
    }

    // 本机：app_process 直启内嵌 X Server（上游同款命令行），失败信息留在会话里可见
    val prefix = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
    val script = if (connection.isFromTermux) {
        "export CLASSPATH=\"$(/system/bin/pm path com.termux | head -n1 | cut -d: -f2)\"; " +
            "exec /system/bin/app_process / --nice-name=termux-x11 com.termux.x11.CmdEntryPoint :${connection.displayNumber}"
    } else {
        // 远程：仅导出 DISPLAY 后 exec 登录 shell，环境变量随 exec 链透传
        "export DISPLAY=${connection.displayValue}; exec $prefix/login"
    }
    val session = termuxService.createTermuxSession(
        null,
        arrayOf("-c", script),
        null,
        null,
        false,
        "X11 ${connection.displayValue}"
    )
    if (session != null) {
        // 先把 X Server 日志所在的 TermuxActivity 起好作为基底窗口
        val intent = Intent(context, com.termux.app.TermuxActivity::class.java)
        intent.putExtra("sessionHandle", session.getTerminalSession().mHandle)
        context.startActivity(intent)
        // 再按「小窗→分屏→兜底直切」渐进激活 X11 渲染页
        if (connection.isFromTermux) {
            launchX11Activity(context)
        }
    }
}

/**
 * 渐进式激活 X11 渲染页：先尝试系统小窗（freeform），失败则分屏，再失败兜底直切 + float 提示。
 *
 * 各 OEM 的「小窗」机制不一（MIUI/OriginOS 等私有方案无法在通用 API 层可靠触发），
 * 这里以 AOSP freeform 为小窗通用方案，分屏用 LAUNCH_ADJACENT，二者均要求渲染 Activity
 * 可缩放（lorie AAR 的 MainActivity 已声明 resizeableActivity="true"）。
 */
private fun launchX11Activity(context: Context) {
    val intent = Intent().setClassName(context, "com.termux.x11.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (tryFreeform(context, intent)) return
    if (trySplitScreen(context, intent)) return
    // 兜底：直接切到前台
    runCatching { context.startActivity(intent) }
    Toast.makeText(context, "X11已启动，已切换到X11为前台", Toast.LENGTH_LONG).show()
}

private fun tryFreeform(context: Context, intent: Intent): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return runCatching {
        val dm = context.resources.displayMetrics
        val w = (dm.widthPixels * 0.7f).toInt().coerceAtLeast(480)
        val h = (dm.heightPixels * 0.82f).toInt().coerceAtLeast(480)
        val x = ((dm.widthPixels - w) / 2f).toInt().coerceAtLeast(0)
        val y = (dm.heightPixels * 0.09f).toInt().coerceAtLeast(0)
        // setLaunchWindowingMode / WINDOWING_MODE_FREEFORM 是 hidden API，运行时反射调用；
        // 设备不支持 freeform（或方法不存在）时反射失败，回落到分屏/兜底。
        val opts = ActivityOptions.makeBasic()
        val wmClass = Class.forName("android.app.WindowConfiguration")
        val freeform = wmClass.getField("WINDOWING_MODE_FREEFORM").getInt(null)
        val setMode = ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
        setMode.invoke(opts, freeform)
        val setBounds = ActivityOptions::class.java.getMethod("setLaunchBounds", Rect::class.java)
        setBounds.invoke(opts, Rect(x, y, x + w, y + h))
        context.startActivity(intent, opts.toBundle())
        true
    }.getOrDefault(false)
}

private fun trySplitScreen(context: Context, intent: Intent): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
    return runCatching {
        val dm = context.resources.displayMetrics
        val opts = ActivityOptions.makeBasic()
            .setLaunchBounds(Rect(0, 0, dm.widthPixels / 2, dm.heightPixels))
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        context.startActivity(intent, opts.toBundle())
        true
    }.getOrDefault(false)
}
