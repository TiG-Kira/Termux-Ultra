package com.termux.app.ssh

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("没有项目")
                    }
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
            .clickable { onConnect() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connection.name,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    text = "${connection.username}@${connection.host}:${connection.port}",
                    fontSize = 12.sp
                )
            }
            Row {
                IconButton(onClick = { onEdit() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = "编辑"
                    )
                }
                IconButton(onClick = { onDelete() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = "删除"
                    )
                }
            }
        }
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

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(if (isEdit) "编辑连接" else "添加连接") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.OutlinedTextField(
                    value = name.value,
                    onValueChange = { name.value = it },
                    label = { androidx.compose.material3.Text("名称") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                androidx.compose.material3.OutlinedTextField(
                    value = host.value,
                    onValueChange = { host.value = it },
                    label = { androidx.compose.material3.Text("主机地址") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                androidx.compose.material3.OutlinedTextField(
                    value = port.value,
                    onValueChange = { port.value = it },
                    label = { androidx.compose.material3.Text("端口") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                androidx.compose.material3.OutlinedTextField(
                    value = username.value,
                    onValueChange = { username.value = it },
                    label = { androidx.compose.material3.Text("用户名") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                androidx.compose.material3.OutlinedTextField(
                    value = password.value,
                    onValueChange = { password.value = it },
                    label = { androidx.compose.material3.Text("密码（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val conn = SshConnection(
                    id = connection?.id ?: UUID.randomUUID().toString(),
                    name = name.value,
                    host = host.value,
                    port = port.value.toIntOrNull() ?: 22,
                    username = username.value,
                    password = password.value
                )
                onSave(conn)
            }) {
                androidx.compose.material3.Text("保存")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("取消")
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

private fun connectToSsh(context: Context, connection: SshConnection) {
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
