package com.termux.app.vnc

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import com.termux.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun VncScreen(
    connections: MutableList<VncConnection>,
    addRequested: Boolean,
    onAddRequestedConsumed: () -> Unit,
    scanRequested: Boolean,
    onScanRequestedConsumed: () -> Unit
) {
    val context = LocalContext.current
    val showAddDialog = remember { mutableStateOf(false) }
    val showEditDialog = remember { mutableStateOf(false) }
    val editingConnection = remember { mutableStateOf<VncConnection?>(null) }

    LaunchedEffect(scanRequested) {
        if (scanRequested) {
            scanTermuxVnc(context, connections)
            onScanRequestedConsumed()
        }
    }

    if (addRequested && !showAddDialog.value) {
        showAddDialog.value = true
        onAddRequestedConsumed()
    }

    if (scanRequested) {
        scanTermuxVnc(context, connections)
        onScanRequestedConsumed()
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                VncConnectionCard(
                    connection = conn,
                    onConnect = { connectToVnc(context, conn) },
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
        VncEditDialog(
            connection = null,
            onSave = { conn ->
                saveConnection(context, conn, connections)
                showAddDialog.value = false
            },
            onDismiss = { showAddDialog.value = false }
        )
    }

    if (showEditDialog.value && editingConnection.value != null) {
        VncEditDialog(
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
fun VncConnectionCard(
    connection: VncConnection,
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
                    text = "${connection.host}:${connection.port}",
                    fontSize = 12.sp
                )
                if (connection.isFromTermux) {
                    Text(
                        text = "来自Termux",
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            }
            if (!connection.isFromTermux) {
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
}

@Composable
fun VncEditDialog(
    connection: VncConnection?,
    onSave: (VncConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val isEdit = connection != null
    val name = remember { mutableStateOf(connection?.name ?: "") }
    val host = remember { mutableStateOf(connection?.host ?: "") }
    val port = remember { mutableStateOf((connection?.port ?: 5900).toString()) }
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
                    value = password.value,
                    onValueChange = { password.value = it },
                    label = { androidx.compose.material3.Text("密码") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val conn = VncConnection(
                    id = connection?.id ?: UUID.randomUUID().toString(),
                    name = name.value,
                    host = host.value,
                    port = port.value.toIntOrNull() ?: 5900,
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

private fun loadConnections(context: Context, connections: MutableList<VncConnection>) {
    val manager = VncConnectionManager(context)
    connections.clear()
    connections.addAll(manager.getConnections())
}

private fun saveConnection(context: Context, connection: VncConnection, connections: MutableList<VncConnection>) {
    val manager = VncConnectionManager(context)
    manager.saveConnection(connection)
    val index = connections.indexOfFirst { it.id == connection.id }
    if (index >= 0) {
        connections[index] = connection
    } else {
        connections.add(connection)
    }
}

private fun deleteConnection(context: Context, connection: VncConnection, connections: MutableList<VncConnection>) {
    val manager = VncConnectionManager(context)
    manager.deleteConnection(connection.id)
    connections.remove(connection)
}

private fun scanTermuxVnc(context: Context, connections: MutableList<VncConnection>) {
    val manager = VncConnectionManager(context)
    manager.deleteNonTermuxConnections()
    connections.removeIf { it.isFromTermux }
    for (port in 5900..5910) {
        try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 100)
            socket.close()
            val sessionName = "Termux VNC $port"
            val existing = connections.find { it.host == "127.0.0.1" && it.port == port }
            if (existing == null) {
                val conn = VncConnection(
                    id = UUID.randomUUID().toString(),
                    name = sessionName,
                    host = "127.0.0.1",
                    port = port,
                    password = "",
                    isFromTermux = true,
                    sessionName = sessionName
                )
                connections.add(conn)
                manager.saveConnection(conn)
            }
        } catch (_: Exception) {
        }
    }
}

private fun connectToVnc(context: Context, connection: VncConnection) {
    val serviceIntent = Intent(context, com.termux.app.TermuxService::class.java)
    context.startService(serviceIntent)

    val profile = com.gaurav.avnc.model.ServerProfile(
        name = connection.name,
        host = connection.host,
        port = connection.port,
        password = connection.password
    )
    val vncIntent = Intent(context, com.gaurav.avnc.ui.vnc.VncActivity::class.java)
    vncIntent.putExtra("com.gaurav.avnc.server_profile", profile)
    context.startActivity(vncIntent)
}
