package com.termux.app.rustdesk

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.app.compose.OverlayDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID

/**
 * RustDesk 标签页：连接记录的增删改与发起会话。
 *
 * 与 [com.termux.app.vnc.VncScreen] 同构——同样的卡片列表、同样的编辑弹窗。
 * 会话由本应用内嵌的 Rust 核心渲染（见 [RustDeskCore]）；仅当该 ABI 没有原生库时，
 * [RustDeskClient] 才把会话退回已安装的官方客户端。
 */
@Composable
fun RustDeskScreen(
    connections: MutableList<RustDeskConnection>,
    addRequested: Boolean,
    onAddRequestedConsumed: () -> Unit,
    nestedScrollConnection: NestedScrollConnection? = null,
    navBarBottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<RustDeskConnection?>(null) }
    var passwordTarget by remember { mutableStateOf<RustDeskConnection?>(null) }
    var showClientMissing by remember { mutableStateOf(false) }
    // 内嵌核心可用时不依赖外部客户端；只有核心与官方客户端都不可用才提示安装
    val needsExternalClient = remember {
        !RustDeskCore.isAvailable() && !RustDeskClient.isInstalled(context)
    }

    if (addRequested && !showAddDialog && editingConnection == null) {
        showAddDialog = true
        onAddRequestedConsumed()
    }

    fun launchConnection(connection: RustDeskConnection, password: String) {
        if (needsExternalClient) {
            showClientMissing = true
            return
        }
        val target = connection.copy(password = password)
        // 与 VNC 一致：密码跟着连接落盘，但受「保存密码」开关约束
        if (password != connection.password && RustDeskSettings.savePassword(context)) {
            saveConnection(context, target, connections)
        }
        if (!RustDeskClient.launch(context, target)) showClientMissing = true
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = navBarBottomPadding + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .let {
                if (nestedScrollConnection != null) it.nestedScroll(nestedScrollConnection) else it
            }
    ) {
        if (needsExternalClient) {
            item { RustDeskClientMissingCard() }
        }

        if (connections.isEmpty()) {
            item { RustDeskEmptyState() }
        } else {
            items(connections) { connection ->
                RustDeskConnectionCard(
                    connection = connection,
                    onConnect = {
                        val ask = RustDeskSettings.askPassword(context)
                        if (ask || connection.password.isEmpty()) {
                            passwordTarget = connection
                        } else {
                            launchConnection(connection, connection.password)
                        }
                    },
                    onEdit = { editingConnection = connection },
                    onDelete = { deleteConnection(context, connection, connections) }
                )
            }
        }
    }

    if (showAddDialog) {
        RustDeskEditDialog(
            connection = null,
            onSave = { connection ->
                saveConnection(context, connection, connections)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    editingConnection?.let { editing ->
        RustDeskEditDialog(
            connection = editing,
            onSave = { connection ->
                saveConnection(context, connection, connections)
                editingConnection = null
            },
            onDismiss = { editingConnection = null }
        )
    }

    passwordTarget?.let { target ->
        RustDeskPasswordDialog(
            connection = target,
            onConfirm = { password ->
                passwordTarget = null
                launchConnection(target, password)
            },
            onDismiss = { passwordTarget = null }
        )
    }

    if (showClientMissing) {
        OverlayDialog(
            title = stringResource(R.string.rustdesk_client_missing_title),
            summary = stringResource(R.string.rustdesk_client_missing_summary),
            show = true,
            onDismissRequest = { showClientMissing = false },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = stringResource(R.string.rustdesk_download),
                        onClick = {
                            RustDeskClient.openDownloadPage(context)
                            showClientMissing = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { showClientMissing = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )
    }
}

@Composable
private fun RustDeskConnectionCard(
    connection: RustDeskConnection,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
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
                    painter = painterResource(R.drawable.ic_rustdesk),
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
                    text = connection.summary,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 18.sp
                )
                if (connection.forceRelay) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.rustdesk_force_relay),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            }

            Row {
                IconButton(onClick = { onEdit() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.action_file_received_edit),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { onDelete() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.delete),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun RustDeskClientMissingCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { RustDeskClient.openDownloadPage(context) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_rustdesk),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.rustdesk_client_missing_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.rustdesk_client_missing_summary),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun RustDeskEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 100.dp),
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
                painter = painterResource(R.drawable.ic_rustdesk),
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .alpha(0.6f),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.rustdesk_empty),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.rustdesk_empty_hint),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun RustDeskEditDialog(
    connection: RustDeskConnection?,
    onSave: (RustDeskConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isEdit = connection != null
    var name by remember { mutableStateOf(connection?.name ?: "") }
    var remoteId by remember { mutableStateOf(connection?.remoteId ?: "") }
    var password by remember { mutableStateOf(connection?.password ?: "") }
    var server by remember {
        mutableStateOf(connection?.server ?: RustDeskSettings.server(context))
    }
    var publicKey by remember {
        mutableStateOf(connection?.key ?: RustDeskSettings.publicKey(context))
    }
    var forceRelay by remember {
        mutableStateOf(connection?.forceRelay ?: RustDeskSettings.forceRelay(context))
    }
    var show by remember { mutableStateOf(true) }

    OverlayDialog(
        show = show,
        onDismissRequest = {
            show = false
            onDismiss()
        },
        title = stringResource(
            if (isEdit) R.string.rustdesk_edit_title else R.string.rustdesk_add_title
        ),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 五个输入框 + 开关在矮屏上会顶到键盘，让内容自己滚而不是被裁掉
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.rustdesk_field_name)
                )
                TextField(
                    value = remoteId,
                    onValueChange = { remoteId = it },
                    label = stringResource(R.string.rustdesk_field_id)
                )
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.rustdesk_field_password),
                    visualTransformation = PasswordVisualTransformation()
                )
                TextField(
                    value = server,
                    onValueChange = { server = it },
                    label = stringResource(R.string.rustdesk_field_server)
                )
                TextField(
                    value = publicKey,
                    onValueChange = { publicKey = it },
                    label = stringResource(R.string.rustdesk_field_key)
                )

                SwitchPreference(
                    title = stringResource(R.string.rustdesk_force_relay),
                    summary = stringResource(R.string.rustdesk_force_relay_desc),
                    checked = forceRelay,
                    onCheckedChange = { forceRelay = it }
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = {
                            show = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.save),
                        onClick = {
                            val id = remoteId.trim()
                            if (id.isEmpty()) return@TextButton
                            show = false
                            onSave(
                                RustDeskConnection(
                                    id = connection?.id ?: UUID.randomUUID().toString(),
                                    name = name.trim().ifEmpty { id },
                                    remoteId = id,
                                    password = password,
                                    server = server.trim(),
                                    key = publicKey.trim(),
                                    forceRelay = forceRelay
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

@Composable
private fun RustDeskPasswordDialog(
    connection: RustDeskConnection,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember(connection.id) { mutableStateOf(connection.password) }
    var show by remember { mutableStateOf(true) }

    OverlayDialog(
        show = show,
        onDismissRequest = {
            show = false
            onDismiss()
        },
        title = stringResource(R.string.rustdesk_password_title),
        summary = stringResource(R.string.rustdesk_password_summary, connection.name),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.rustdesk_field_password),
                    visualTransformation = PasswordVisualTransformation()
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = {
                            show = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.rustdesk_connect),
                        onClick = {
                            show = false
                            onConfirm(password)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

private fun saveConnection(
    context: Context,
    connection: RustDeskConnection,
    connections: MutableList<RustDeskConnection>
) {
    RustDeskConnectionManager(context).saveConnection(connection)
    val index = connections.indexOfFirst { it.id == connection.id }
    if (index >= 0) connections[index] = connection else connections.add(connection)
}

private fun deleteConnection(
    context: Context,
    connection: RustDeskConnection,
    connections: MutableList<RustDeskConnection>
) {
    RustDeskConnectionManager(context).deleteConnection(connection.id)
    connections.removeAll { it.id == connection.id }
}
