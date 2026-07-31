package com.termux.app.ssh

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import com.termux.R
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SshScreen(
    connections: MutableList<SshConnection>,
    addRequested: Boolean,
    onAddRequestedConsumed: () -> Unit,
    nestedScrollConnection: androidx.compose.ui.input.nestedscroll.NestedScrollConnection? = null
) {
    val context = LocalContext.current
    val showAddDialog = remember { mutableStateOf(false) }
    val showEditDialog = remember { mutableStateOf(false) }
    val editingConnection = remember { mutableStateOf<SshConnection?>(null) }

    if (addRequested && !showAddDialog.value) {
        showAddDialog.value = true
        onAddRequestedConsumed()
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
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
            if (connections.isEmpty()) {
                item {
                    EmptySshState()
                }
            } else {
                items(connections) { conn ->
                    SshConnectionCard(
                        connection = conn,
                        onConnect = { connectToSsh(context, conn) },
                        onEdit = {
                            editingConnection.value = conn
                            showEditDialog.value = true
                        },
                        onDelete = {
                            deleteConnection(context, conn, connections)
                        }
                    )
                }
            }
        }

    if (showAddDialog.value) {
        SshEditDialog(
            connection = null,
            onSave = { conn ->
                saveConnection(context, conn, connections)
                showAddDialog.value = false
            },
            onDismiss = { showAddDialog.value = false }
        )
    }

    if (showEditDialog.value && editingConnection.value != null) {
        SshEditDialog(
            connection = editingConnection.value,
            onSave = { conn ->
                saveConnection(context, conn, connections)
                showEditDialog.value = false
            },
            onDismiss = { showEditDialog.value = false }
        )
    }
}

@Composable
fun SshConnectionCard(
    connection: SshConnection,
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
                    painter = painterResource(R.drawable.ic_ssh),
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
                    text = "${connection.username}@${connection.host}:${connection.port}",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 18.sp
                )
            }
            Row {
                IconButton(onClick = { onEdit() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.ssh_action_edit),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { onDelete() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.ssh_action_delete),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySshState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 120.dp),
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
                painter = painterResource(R.drawable.ic_ssh),
                contentDescription = null,
                modifier = Modifier.size(36.dp).alpha(0.6f),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "没有 SSH 连接",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            lineHeight = 22.sp
        )
    }
}

@Composable
fun SshEditDialog(
    connection: SshConnection?,
    onSave: (SshConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val isEdit = connection != null
    val name = remember { mutableStateOf(connection?.name ?: "") }
    val host = remember { mutableStateOf(connection?.host ?: "") }
    val port = remember { mutableStateOf((connection?.port ?: 22).toString()) }
    val username = remember { mutableStateOf(connection?.username ?: "") }
    val password = remember { mutableStateOf(connection?.password ?: "") }
    val showDialog = remember { mutableStateOf(true) }

    OverlayDialog(
        show = showDialog.value,
        onDismissRequest = {
            showDialog.value = false
            onDismiss()
        },
        title = if (isEdit) stringResource(R.string.ssh_edit_connection) else stringResource(R.string.ssh_add_connection),
        content = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = name.value,
                    onValueChange = { name.value = it },
                    label = stringResource(R.string.ssh_field_name)
                )

                TextField(
                    value = host.value,
                    onValueChange = { host.value = it },
                    label = stringResource(R.string.ssh_field_host)
                )

                TextField(
                    value = port.value,
                    onValueChange = { port.value = it },
                    label = stringResource(R.string.ssh_field_port)
                )

                TextField(
                    value = username.value,
                    onValueChange = { username.value = it },
                    label = stringResource(R.string.ssh_field_username)
                )

                TextField(
                    value = password.value,
                    onValueChange = { password.value = it },
                    label = stringResource(R.string.ssh_field_password_optional)
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = {
                            showDialog.value = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.ssh_save),
                        onClick = {
                            val conn = SshConnection(
                                id = connection?.id ?: UUID.randomUUID().toString(),
                                name = name.value,
                                host = host.value,
                                port = port.value.toIntOrNull() ?: 22,
                                username = username.value,
                                password = password.value
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

private fun loadConnections(context: Context, connections: MutableList<SshConnection>) {
    val manager = SshConnectionManager(context)
    connections.clear()
    connections.addAll(manager.getConnections())
}

private fun saveConnection(context: Context, connection: SshConnection, connections: MutableList<SshConnection>) {
    val manager = SshConnectionManager(context)
    manager.saveConnection(connection)
    val index = connections.indexOfFirst { it.id == connection.id }
    if (index >= 0) {
        connections[index] = connection
    } else {
        connections.add(connection)
    }
}

private fun deleteConnection(context: Context, connection: SshConnection, connections: MutableList<SshConnection>) {
    val manager = SshConnectionManager(context)
    manager.deleteConnection(connection.id)
    connections.remove(connection)
}

internal fun connectToSsh(context: Context, connection: SshConnection) {
    val sshCommand = buildSshCommand(connection)

    val executableUri = android.net.Uri.Builder()
        .scheme("com.termux.file")
        .path("/data/data/com.termux/files/usr/bin/bash")
        .build()

    val executeIntent = android.content.Intent(
        "com.termux.service_execute",
        executableUri
    )
    executeIntent.setClass(context, com.termux.app.TermuxService::class.java)
    executeIntent.putExtra("com.termux.execute.arguments", arrayOf("-c", sshCommand))
    executeIntent.putExtra("com.termux.execute.cwd", "/data/data/com.termux/files/home")
    executeIntent.putExtra("com.termux.execute.session_action", "0")

    context.startService(executeIntent)
}

private fun buildSshCommand(connection: SshConnection): String {
    val installCheck = "command -v ssh >/dev/null 2>&1 || pkg install -y openssh; command -v sshpass >/dev/null 2>&1 || pkg install -y sshpass"
    val host = connection.host
    val port = connection.port
    val user = connection.username
    val password = connection.password

    var sshCmd = "ssh -o StrictHostKeyChecking=no"
    if (port != 22) {
        sshCmd += " -p $port"
    }

    if (password.isNotEmpty()) {
        sshCmd = "sshpass -p '${password.replace("'", "'\\''")}' $sshCmd"
    }

    sshCmd += " $user@$host"

    return "$installCheck; $sshCmd"
}
