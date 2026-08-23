package com.termux.app.ssh

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.termux.R
import com.termux.app.compose.TermuxInternalFilePicker
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TERMUX_HOME = "/data/data/com.termux/files/home"
private const val SSH_KEYS_DIR = "$TERMUX_HOME/.ssh"

@Composable
fun SshScreen(
    connections: MutableList<SshConnection>,
    addRequested: Boolean,
    onAddRequestedConsumed: () -> Unit,
    nestedScrollConnection: androidx.compose.ui.input.nestedscroll.NestedScrollConnection? = null,
    navBarBottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val context = LocalContext.current
    val showTypeDialog = remember { mutableStateOf(false) }
    val showAddDialog = remember { mutableStateOf(false) }
    val showEditDialog = remember { mutableStateOf(false) }
    val editingConnection = remember { mutableStateOf<SshConnection?>(null) }
    val selectedType = remember { mutableStateOf(SshConnection.TYPE_OTHER) }

    if (addRequested && !showTypeDialog.value && !showAddDialog.value) {
        showTypeDialog.value = true
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

    if (showTypeDialog.value) {
        SshConnectionTypeDialog(
            onSelect = { type ->
                selectedType.value = type
                showTypeDialog.value = false
                showAddDialog.value = true
            },
            onDismiss = { showTypeDialog.value = false }
        )
    }

    if (showAddDialog.value) {
        SshConfigDialog(
            connection = null,
            defaultType = selectedType.value,
            onSave = { conn ->
                saveConnection(context, conn, connections)
                showAddDialog.value = false
                selectedType.value = SshConnection.TYPE_OTHER
            },
            onDismiss = {
                showAddDialog.value = false
                selectedType.value = SshConnection.TYPE_OTHER
            }
        )
    }

    if (showEditDialog.value && editingConnection.value != null) {
        SshConfigDialog(
            connection = editingConnection.value,
            defaultType = editingConnection.value!!.connectionType,
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
    val tagColor = when (connection.connectionType) {
        SshConnection.TYPE_OPENPILOT -> Color(0xFF4CAF50)
        SshConnection.TYPE_COMMA -> Color(0xFF2196F3)
        SshConnection.TYPE_LOCAL -> Color(0xFFFF9800)
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val tagText = when (connection.connectionType) {
        SshConnection.TYPE_OPENPILOT -> stringResource(R.string.ssh_tag_openpilot)
        SshConnection.TYPE_COMMA -> stringResource(R.string.ssh_tag_comma)
        SshConnection.TYPE_LOCAL -> stringResource(R.string.ssh_tag_local)
        else -> stringResource(R.string.ssh_tag_other)
    }

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = connection.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                        lineHeight = 22.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(tagColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = tagText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = tagColor
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when (connection.connectionType) {
                        SshConnection.TYPE_LOCAL -> "${connection.username}@localhost:${connection.port}"
                        SshConnection.TYPE_COMMA -> {
                            if (connection.deviceType == SshConnection.DEVICE_EXTERNAL) {
                                "${connection.username}@${connection.dongleId} (${stringResource(R.string.ssh_method_dongle_id)})"
                            } else {
                                "${connection.username}@${connection.host}:${connection.port}"
                            }
                        }
                        else -> "${connection.username}@${connection.host}:${connection.port}"
                    },
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
private fun SshConnectionTypeDialog(
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val showDialog = remember { mutableStateOf(true) }

    OverlayDialog(
        show = showDialog.value,
        onDismissRequest = {
            showDialog.value = false
            onDismiss()
        },
        title = stringResource(R.string.ssh_select_connection_type),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ConnectionTypeOption(
                    title = stringResource(R.string.ssh_type_openpilot_comma),
                    description = stringResource(R.string.ssh_type_openpilot_comma_desc),
                    onClick = { onSelect(SshConnection.TYPE_OPENPILOT) }
                )
                ConnectionTypeOption(
                    title = stringResource(R.string.ssh_type_local),
                    description = stringResource(R.string.ssh_type_local_desc),
                    onClick = { onSelect(SshConnection.TYPE_LOCAL) }
                )
                ConnectionTypeOption(
                    title = stringResource(R.string.ssh_type_other),
                    description = stringResource(R.string.ssh_type_other_desc),
                    onClick = { onSelect(SshConnection.TYPE_OTHER) }
                )
            }
        }
    )
}

@Composable
private fun ConnectionTypeOption(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
fun SshConfigDialog(
    connection: SshConnection?,
    defaultType: String,
    onSave: (SshConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val isEdit = connection != null
    val showDialog = remember { mutableStateOf(true) }
    val connectionType = remember { mutableStateOf(connection?.connectionType ?: defaultType) }

    val name = remember { mutableStateOf(connection?.name ?: "") }
    val host = remember { mutableStateOf(connection?.host ?: "") }
    val port = remember { mutableStateOf((connection?.port ?: 22).toString()) }
    val username = remember { mutableStateOf(connection?.username ?: "") }
    val password = remember { mutableStateOf(connection?.password ?: "") }
    val privateKeyPath = remember { mutableStateOf(connection?.privateKeyPath ?: "") }
    val deviceType = remember { mutableStateOf(connection?.deviceType ?: SshConnection.DEVICE_INTERNAL) }
    val dongleId = remember { mutableStateOf(connection?.dongleId ?: "") }

    val isLocal = connectionType.value == SshConnection.TYPE_LOCAL
    val isOpenPilot = connectionType.value == SshConnection.TYPE_OPENPILOT
    val isComma = connectionType.value == SshConnection.TYPE_COMMA

    val context = LocalContext.current
    val showInternalFilePicker = remember { mutableStateOf(false) }
    val isGeneratingKey = remember { mutableStateOf(false) }
    val keyGenMessage = remember { mutableStateOf("") }

    OverlayDialog(
        show = showDialog.value,
        onDismissRequest = {
            showDialog.value = false
            onDismiss()
        },
        title = if (isEdit) stringResource(R.string.ssh_edit_connection) else stringResource(R.string.ssh_add_connection),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextField(
                    value = name.value,
                    onValueChange = { name.value = it },
                    label = stringResource(R.string.ssh_field_name),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (isLocal) {
                    TextField(
                        value = port.value,
                        onValueChange = { port.value = it },
                        label = stringResource(R.string.ssh_field_port),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    TextField(
                        value = username.value,
                        onValueChange = { username.value = it },
                        label = stringResource(R.string.ssh_field_username),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    TextField(
                        value = password.value,
                        onValueChange = { password.value = it },
                        label = stringResource(R.string.ssh_field_password_optional),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else if (isOpenPilot || isComma) {
                    if (!isEdit) {
                        DeviceTypeSelector(
                            selectedDeviceType = if (isComma) "comma" else "openpilot",
                            onDeviceTypeSelected = { dt ->
                                connectionType.value = if (dt == "comma") {
                                    SshConnection.TYPE_COMMA
                                } else {
                                    SshConnection.TYPE_OPENPILOT
                                }
                            }
                        )
                    }

                    if (isComma) {
                        ConnectionMethodSelector(
                            selectedMethod = deviceType.value,
                            onMethodSelected = { deviceType.value = it }
                        )

                        if (deviceType.value == SshConnection.DEVICE_EXTERNAL) {
                            Text(
                                text = stringResource(R.string.ssh_comma_dongle_warning),
                                fontSize = 12.sp,
                                color = Color(0xFFFF9800)
                            )

                            TextField(
                                value = dongleId.value,
                                onValueChange = { dongleId.value = it },
                                label = stringResource(R.string.ssh_field_dongle_id),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            TextField(
                                value = username.value,
                                onValueChange = { username.value = it },
                                label = stringResource(R.string.ssh_field_username),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            PrivateKeyPicker(
                                context = context,
                                privateKeyPath = privateKeyPath,
                                showFilePicker = showInternalFilePicker,
                                isGenerating = isGeneratingKey,
                                keyGenMessage = keyGenMessage
                            )
                        } else {
                            TextField(
                                value = host.value,
                                onValueChange = { host.value = it },
                                label = stringResource(R.string.ssh_field_device_ip),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            TextField(
                                value = port.value,
                                onValueChange = { port.value = it },
                                label = stringResource(R.string.ssh_field_port),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            TextField(
                                value = username.value,
                                onValueChange = { username.value = it },
                                label = stringResource(R.string.ssh_field_username),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            PrivateKeyPicker(
                                context = context,
                                privateKeyPath = privateKeyPath,
                                showFilePicker = showInternalFilePicker,
                                isGenerating = isGeneratingKey,
                                keyGenMessage = keyGenMessage
                            )
                        }
                    } else {
                        TextField(
                            value = host.value,
                            onValueChange = { host.value = it },
                            label = stringResource(R.string.ssh_field_device_ip),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        TextField(
                            value = port.value,
                            onValueChange = { port.value = it },
                            label = stringResource(R.string.ssh_field_port),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        TextField(
                            value = username.value,
                            onValueChange = { username.value = it },
                            label = stringResource(R.string.ssh_field_username),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        TextField(
                            value = password.value,
                            onValueChange = { password.value = it },
                            label = stringResource(R.string.ssh_field_password_optional),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        PrivateKeyPicker(
                            context = context,
                            privateKeyPath = privateKeyPath,
                            showFilePicker = showInternalFilePicker,
                            isGenerating = isGeneratingKey,
                            keyGenMessage = keyGenMessage
                        )
                    }
                } else {
                    TextField(
                        value = host.value,
                        onValueChange = { host.value = it },
                        label = stringResource(R.string.ssh_field_host),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    TextField(
                        value = port.value,
                        onValueChange = { port.value = it },
                        label = stringResource(R.string.ssh_field_port),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    TextField(
                        value = username.value,
                        onValueChange = { username.value = it },
                        label = stringResource(R.string.ssh_field_username),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    TextField(
                        value = password.value,
                        onValueChange = { password.value = it },
                        label = stringResource(R.string.ssh_field_password_optional),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    PrivateKeyPicker(
                        context = context,
                        privateKeyPath = privateKeyPath,
                        showFilePicker = showInternalFilePicker,
                        isGenerating = isGeneratingKey,
                        keyGenMessage = keyGenMessage
                    )
                }

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
                            val finalHost = when {
                                isLocal -> "localhost"
                                connectionType.value == SshConnection.TYPE_COMMA &&
                                        deviceType.value == SshConnection.DEVICE_EXTERNAL -> ""
                                else -> host.value
                            }

                            val finalPort = when {
                                isLocal -> (port.value.toIntOrNull() ?: 22)
                                connectionType.value == SshConnection.TYPE_OPENPILOT -> (port.value.toIntOrNull() ?: 8022)
                                else -> (port.value.toIntOrNull() ?: 22)
                            }

                            val finalUser = when {
                                username.value.isNotEmpty() -> username.value
                                connectionType.value == SshConnection.TYPE_COMMA -> "comma"
                                connectionType.value == SshConnection.TYPE_OPENPILOT -> "root"
                                isLocal -> "root"
                                else -> ""
                            }

                            val conn = SshConnection(
                                id = connection?.id ?: UUID.randomUUID().toString(),
                                name = name.value,
                                host = finalHost,
                                port = finalPort,
                                username = finalUser,
                                password = password.value,
                                privateKeyPath = privateKeyPath.value,
                                connectionType = connectionType.value,
                                deviceType = if (connectionType.value == SshConnection.TYPE_COMMA) deviceType.value else "",
                                dongleId = if (connectionType.value == SshConnection.TYPE_COMMA &&
                                    deviceType.value == SshConnection.DEVICE_EXTERNAL) dongleId.value else ""
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

    if (showInternalFilePicker.value) {
        TermuxInternalFilePicker(
            show = showInternalFilePicker.value,
            title = "选择 SSH 私钥文件",
            onDismiss = { showInternalFilePicker.value = false },
            onFileSelected = { path ->
                showInternalFilePicker.value = false
                privateKeyPath.value = path
            }
        )
    }
}

@Composable
private fun PrivateKeyPicker(
    context: Context,
    privateKeyPath: androidx.compose.runtime.MutableState<String>,
    showFilePicker: androidx.compose.runtime.MutableState<Boolean>,
    isGenerating: androidx.compose.runtime.MutableState<Boolean>,
    keyGenMessage: androidx.compose.runtime.MutableState<String>
) {
    val scope = rememberCoroutineScope()
    val hasKey = privateKeyPath.value
    val isLoading = isGenerating.value

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.ssh_field_private_key_path),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )

        if (hasKey.isNotEmpty()) {
            Text(
                text = privateKeyPath.value,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        } else {
            Text(
                text = stringResource(R.string.ssh_private_key_path_hint),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = keyGenMessage.value,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showFilePicker.value = true },
                modifier = Modifier.weight(1f),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = stringResource(R.string.ssh_private_key_select_file),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        generateSshKey(
                            context = context,
                            onKeyGenerated = { path ->
                                privateKeyPath.value = path
                            },
                            message = keyGenMessage,
                            isGenerating = isGenerating
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = stringResource(R.string.ssh_private_key_create_key),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private suspend fun generateSshKey(
    context: Context,
    onKeyGenerated: (String) -> Unit,
    message: androidx.compose.runtime.MutableState<String>,
    isGenerating: androidx.compose.runtime.MutableState<Boolean>
) = withContext(Dispatchers.IO) {
    isGenerating.value = true

    try {
        message.value = "正在安装 openssh..."

        val installProcess = ProcessBuilder("pkg", "install", "-y", "openssh")
            .redirectErrorStream(true)
            .start()
        installProcess.waitFor()

        val sshKeygenFile = File("/data/data/com.termux/files/usr/bin/ssh-keygen")
        if (!sshKeygenFile.exists()) {
            message.value = context.getString(R.string.ssh_private_key_create_failed) + ": ssh-keygen 未找到，请手动安装 openssh"
            isGenerating.value = false
            return@withContext
        }

        val sshDir = File(SSH_KEYS_DIR)
        if (!sshDir.exists()) {
            sshDir.mkdirs()
        }

        val timestamp = System.currentTimeMillis()
        val keyPath = "$SSH_KEYS_DIR/id_ed25519_$timestamp"
        val comment = context.getString(R.string.ssh_keygen_comment)

        message.value = context.getString(R.string.ssh_private_key_creating)

        val process = ProcessBuilder(
            "ssh-keygen",
            "-t", "ed25519",
            "-C", comment,
            "-f", keyPath,
            "-N", "",
            "-q"
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode == 0) {
            val privateKeyFile = File(keyPath)
            if (privateKeyFile.exists()) {
                privateKeyFile.setReadable(true, false)
                privateKeyFile.setWritable(false, false)

                message.value = context.getString(R.string.ssh_private_key_created)
                onKeyGenerated(keyPath)
            } else {
                message.value = context.getString(R.string.ssh_private_key_create_failed) + ": key file not found"
            }
        } else {
            message.value = context.getString(R.string.ssh_private_key_create_failed) + ": $output"
        }
    } catch (e: Exception) {
        message.value = context.getString(R.string.ssh_private_key_create_failed) + ": ${e.message}"
    }

    isGenerating.value = false
}

@Composable
private fun DeviceTypeSelector(
    selectedDeviceType: String,
    onDeviceTypeSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.ssh_device_type_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DeviceTypeOption(
                label = stringResource(R.string.ssh_device_openpilot),
                selected = selectedDeviceType == "openpilot",
                onClick = { onDeviceTypeSelected("openpilot") }
            )
            DeviceTypeOption(
                label = stringResource(R.string.ssh_device_comma),
                selected = selectedDeviceType == "comma",
                onClick = { onDeviceTypeSelected("comma") }
            )
        }
    }
}

@Composable
private fun DeviceTypeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MiuixTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ConnectionMethodSelector(
    selectedMethod: String,
    onMethodSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.ssh_connection_method_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DeviceTypeOption(
                label = stringResource(R.string.ssh_method_device_ip),
                selected = selectedMethod == SshConnection.DEVICE_INTERNAL,
                onClick = { onMethodSelected(SshConnection.DEVICE_INTERNAL) }
            )
            DeviceTypeOption(
                label = stringResource(R.string.ssh_method_dongle_id),
                selected = selectedMethod == SshConnection.DEVICE_EXTERNAL,
                onClick = { onMethodSelected(SshConnection.DEVICE_EXTERNAL) }
            )
        }
    }
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

fun connectToSsh(context: Context, connection: SshConnection) {
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

    return when (connection.connectionType) {
        SshConnection.TYPE_LOCAL -> buildLocalSshCommand(connection, installCheck)
        SshConnection.TYPE_OPENPILOT -> buildOpenPilotSshCommand(connection, installCheck)
        SshConnection.TYPE_COMMA -> buildCommaSshCommand(connection, installCheck)
        else -> buildStandardSshCommand(connection, installCheck)
    }
}

private fun buildLocalSshCommand(connection: SshConnection, installCheck: String): String {
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

    sshCmd += " $user@localhost"

    return "$installCheck; $sshCmd"
}

private fun buildOpenPilotSshCommand(connection: SshConnection, installCheck: String): String {
    val host = connection.host
    val port = connection.port
    val user = connection.username
    val password = connection.password
    val keyPath = connection.privateKeyPath

    var sshCmd = "ssh -o StrictHostKeyChecking=no"
    if (port != 22) {
        sshCmd += " -p $port"
    }
    if (keyPath.isNotEmpty()) {
        sshCmd += " -i $keyPath"
    }

    if (password.isNotEmpty()) {
        sshCmd = "sshpass -p '${password.replace("'", "'\\''")}' $sshCmd"
    }

    sshCmd += " $user@$host"

    return "$installCheck; $sshCmd"
}

private fun buildCommaSshCommand(connection: SshConnection, installCheck: String): String {
    val host = connection.host
    val port = connection.port
    val user = connection.username
    val keyPath = connection.privateKeyPath
    val isExternal = connection.deviceType == SshConnection.DEVICE_EXTERNAL

    if (isExternal) {
        val dongleId = connection.dongleId.ifEmpty { connection.host }

        var sshCmd = "ssh -o StrictHostKeyChecking=no"
        if (keyPath.isNotEmpty()) {
            sshCmd += " -i $keyPath"
        }

        val proxyPart = if (keyPath.isNotEmpty()) {
            "ssh -i $keyPath -W %h:%p $user@ssh.comma.ai"
        } else {
            "ssh -W %h:%p $user@ssh.comma.ai"
        }

        sshCmd += " -o ProxyCommand=\"$proxyPart\""

        sshCmd += " $user@$dongleId"

        return "$installCheck; $sshCmd"
    } else {
        var sshCmd = "ssh -o StrictHostKeyChecking=no"
        if (port != 22) {
            sshCmd += " -p $port"
        }
        if (keyPath.isNotEmpty()) {
            sshCmd += " -i $keyPath"
        }

        sshCmd += " $user@$host"

        return "$installCheck; $sshCmd"
    }
}

private fun buildStandardSshCommand(connection: SshConnection, installCheck: String): String {
    val host = connection.host
    val port = connection.port
    val user = connection.username
    val password = connection.password
    val keyPath = connection.privateKeyPath

    var sshCmd = "ssh -o StrictHostKeyChecking=no"
    if (port != 22) {
        sshCmd += " -p $port"
    }
    if (keyPath.isNotEmpty()) {
        sshCmd += " -i $keyPath"
    }

    if (password.isNotEmpty()) {
        sshCmd = "sshpass -p '${password.replace("'", "'\\''")}' $sshCmd"
    }

    sshCmd += " $user@$host"

    return "$installCheck; $sshCmd"
}