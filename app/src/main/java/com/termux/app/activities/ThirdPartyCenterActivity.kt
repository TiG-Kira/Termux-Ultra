package com.termux.app.activities

import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.termux.R
import com.termux.app.compose.*
import com.termux.app.compose.TerminalSession
import com.termux.app.compose.getRunningSessions
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Third-Party Center: User-added/deletable/editable resource scripts maintained by third-party developers.
 * Comes with preset third-party resources on first launch.
 */
class ThirdPartyCenterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            com.termux.app.compose.KiTerminalTheme {
                val context = this@ThirdPartyCenterActivity
                val scrollBehavior = MiuixScrollBehavior()

                val prefs = remember { context.getSharedPreferences(THIRD_PARTY_PREFS, Context.MODE_PRIVATE) }
                var resources by remember { mutableStateOf(loadResources(prefs, context)) }
                var showAddDialog by remember { mutableStateOf(false) }
                var editingItem by remember { mutableStateOf<ThirdPartyResource?>(null) }
                var showDeleteConfirm by remember { mutableStateOf<ThirdPartyResource?>(null) }
                // Form state keyed to editingItem so it resets when switching items
                var editName by remember(editingItem) { mutableStateOf(editingItem?.name ?: "") }
                var editDesc by remember(editingItem) { mutableStateOf(editingItem?.description ?: "") }
                var editScript by remember(editingItem) { mutableStateOf(editingItem?.script ?: "") }
                var editUrl by remember(editingItem) { mutableStateOf(editingItem?.url ?: "") }
                var editNeedContainer by remember(editingItem) { mutableStateOf(editingItem?.needsContainerCheck ?: false) }
                var editCopyClip by remember(editingItem) { mutableStateOf(editingItem?.copyToClipboard ?: false) }

                // Session management
                var expandedCard by remember { mutableStateOf<String?>(null) }
                var sessions by remember { mutableStateOf<List<TerminalSession>>(emptyList()) }
                var termuxService by remember { mutableStateOf<com.termux.app.TermuxService?>(null) }

                fun refreshSessions() {
                    sessions = getRunningSessions(context, termuxService)
                }

                val serviceConnection = remember {
                    object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            val binder = service as com.termux.app.TermuxService.LocalBinder
                            termuxService = binder.service
                            refreshSessions()
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {
                            termuxService = null
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    val intent = Intent(context, com.termux.app.TermuxService::class.java)
                    context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                }

                LaunchedEffect(termuxService) {
                    while (true) {
                        kotlinx.coroutines.delay(3000)
                        refreshSessions()
                    }
                }

                DisposableEffect(Unit) {
                    onDispose {
                        try { context.unbindService(serviceConnection) } catch (_: Exception) {}
                    }
                }

                fun save() {
                    prefs.edit().putString(KEY_RESOURCES, Gson().toJson(resources)).apply()
                }

                fun addOrUpdate(r: ThirdPartyResource) {
                    val idx = resources.indexOfFirst { it.id == r.id }
                    resources = if (idx >= 0) resources.mapIndexed { i, item -> if (i == idx) r else item } else resources + r
                    save()
                }

                fun remove(id: String) {
                    resources = resources.filter { it.id != id }
                    save()
                }

                val onExecuteScript: (String, String) -> Unit = { scriptName, command ->
                    val sessionName = scriptName
                    val newSession = termuxService?.createTermuxSession(
                        null,
                        arrayOf("-c", command),
                        null,
                        null,
                        false,
                        sessionName
                    )
                    refreshSessions()
                    if (newSession != null) {
                        val intent = Intent(context, com.termux.app.TermuxActivity::class.java)
                        intent.putExtra("sessionHandle", newSession.getTerminalSession().mHandle)
                        startActivity(intent)
                    }
                }

                fun onExecuteInRunningSession(sessionId: String, command: String) {
                    try {
                        val allSessions = termuxService?.getTermuxSessions() ?: return
                        val targetSession = allSessions.find {
                            val ts = it.getTerminalSession()
                            ts.mHandle == sessionId || ts.mSessionName == sessionId
                        }
                        targetSession?.let { tsItem ->
                            val terminalSession = tsItem.getTerminalSession()
                            // Write command to the session PTY first, then switch to it
                            terminalSession.write(command + "\n")
                            val intent = Intent(context, com.termux.app.TermuxActivity::class.java)
                            intent.putExtra("sessionHandle", terminalSession.mHandle)
                            startActivity(intent)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }

                fun checkContainerAndExecute(r: ThirdPartyResource, execute: () -> Unit) {
                    if (r.needsContainerCheck) {
                        val containerDir = "/data/data/com.termux/files/home/debian-container"
                        val runScript = java.io.File("$containerDir/run.sh")
                        val rootfsBash = java.io.File("$containerDir/rootfs/bin/bash")
                        if (!runScript.exists() || !rootfsBash.exists()) {
                            Toast.makeText(context, context.getString(R.string.need_container_first), Toast.LENGTH_LONG).show()
                            return
                        }
                    }
                    execute()
                }

                fun executeResource(r: ThirdPartyResource) {
                    if (r.copyToClipboard) {
                        checkContainerAndExecute(r) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(r.name, r.script)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, context.getString(R.string.copy_to_clipboard_toast), Toast.LENGTH_SHORT).show()
                        }
                        return
                    }

                    expandedCard = if (expandedCard == r.id) null else r.id
                    refreshSessions()
                }

                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        TopAppBar(
                            title = stringResource(R.string.third_party_center),
                            scrollBehavior = scrollBehavior,
                            navigationIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .clickable { finish() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Back,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                            },
                            actions = {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .clickable {
                                            Log.d("ThirdPartyCenter", "Add button clicked")
                                            editingItem = null
                                            editName = ""
                                            editDesc = ""
                                            editScript = ""
                                            editUrl = ""
                                            editNeedContainer = false
                                            editCopyClip = false
                                            showAddDialog = true
                                            Log.d("ThirdPartyCenter", "showAddDialog set to true: $showAddDialog")
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_add),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        )
                    }
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MiuixTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = stringResource(R.string.third_party_maintained),
                                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                    )
                                    Text(
                                        text = stringResource(R.string.third_party_maintained_desc),
                                        style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }

                        if (resources.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MiuixTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(32.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = stringResource(R.string.no_third_party_resources),
                                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                        )
                                        Text(
                                            text = stringResource(R.string.add_custom_script),
                                            style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        items(resources) { r ->
                            val isExpanded = expandedCard == r.id
                            ThirdPartyResourceCard(
                                item = r,
                                isExpanded = isExpanded,
                                hasRunningSessions = sessions.isNotEmpty(),
                                sessions = sessions,
                                onExecute = { executeResource(r) },
                                onToggleExpand = { expandedCard = if (expandedCard == r.id) null else r.id },
                                onExecuteInNewSession = { command ->
                                    checkContainerAndExecute(r) {
                                        onExecuteScript(r.name, command)
                                        expandedCard = null
                                        refreshSessions()
                                    }
                                },
                                onExecuteInTmux = { command ->
                                    checkContainerAndExecute(r) {
                                        val tmuxName = r.name.replace(".", "_").replace(" ", "_")
                                        val tmuxCommand = "tmux new -s $tmuxName -d && tmux send-keys -t $tmuxName '$command' C-m && tmux attach -t $tmuxName"
                                        onExecuteScript(r.name, tmuxCommand)
                                        expandedCard = null
                                        refreshSessions()
                                    }
                                },
                                onExecuteInRunningSession = { sessionId, command ->
                                    checkContainerAndExecute(r) {
                                        onExecuteInRunningSession(sessionId, command)
                                        expandedCard = null
                                        refreshSessions()
                                    }
                                },
                                onEdit = {
                                    editingItem = r
                                    editName = r.name
                                    editDesc = r.description
                                    editScript = r.script
                                    editUrl = r.url
                                    editNeedContainer = r.needsContainerCheck
                                    editCopyClip = r.copyToClipboard
                                    showAddDialog = true
                                },
                                onDelete = { showDeleteConfirm = r }
                            )
                        }
                    }

                    // Add/Edit Dialog — MUST be inside Scaffold so MiuixPopupHost renders it
                    OverlayDialog(
                        show = showAddDialog,
                        onDismissRequest = {
                            showAddDialog = false
                            editingItem = null
                        },
                        title = if (editingItem?.name?.isBlank() != false) stringResource(R.string.add_third_party_resource) else stringResource(R.string.edit_third_party_resource)
                    ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.resource_name),
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                        )
                        TextField(
                            value = editName,
                            onValueChange = { editName = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.resource_description_optional),
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                        )
                        TextField(
                            value = editDesc,
                            onValueChange = { editDesc = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.resource_script),
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                        )
                        TextField(
                            value = editScript,
                            onValueChange = { editScript = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.resource_reference_link),
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                        )
                        TextField(
                            value = editUrl,
                            onValueChange = { editUrl = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.advanced_options),
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.option_need_container),
                                style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface),
                                modifier = Modifier.weight(1f)
                            )
                            Switch(checked = editNeedContainer, onCheckedChange = { editNeedContainer = it })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.option_copy_to_clipboard),
                                style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface),
                                modifier = Modifier.weight(1f)
                            )
                            Switch(checked = editCopyClip, onCheckedChange = { editCopyClip = it })
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                text = stringResource(R.string.cancel),
                                onClick = {
                                    showAddDialog = false
                                    editingItem = null
                                }
                            )
                            Spacer(Modifier.width(12.dp))
                            TextButton(
                                text = stringResource(R.string.save),
                                onClick = {
                                    if (editName.isBlank()) return@TextButton
                                    val saved = ThirdPartyResource(
                                        id = editingItem?.id?.ifBlank { java.util.UUID.randomUUID().toString() } ?: java.util.UUID.randomUUID().toString(),
                                        name = editName,
                                        description = editDesc,
                                        script = editScript,
                                        url = editUrl,
                                        needsContainerCheck = editNeedContainer,
                                        copyToClipboard = editCopyClip,
                                        needsLinuxContainer = editingItem?.needsLinuxContainer ?: false,
                                        type = editingItem?.type ?: "default"
                                    )
                                    addOrUpdate(saved)
                                    showAddDialog = false
                                    editingItem = null
                                },
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                    }
                }

                    // Delete confirm — inside Scaffold
                    OverlayDialog(
                        show = showDeleteConfirm != null,
                        onDismissRequest = { showDeleteConfirm = null },
                        title = stringResource(R.string.delete_resource),
                        summary = showDeleteConfirm?.name?.let { stringResource(R.string.delete_resource_confirm, it) } ?: ""
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                text = stringResource(R.string.cancel),
                                onClick = { showDeleteConfirm = null }
                            )
                            Spacer(Modifier.width(12.dp))
                            TextButton(
                                text = stringResource(R.string.delete),
                                onClick = {
                                    showDeleteConfirm?.let { remove(it.id) }
                                    showDeleteConfirm = null
                                },
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val THIRD_PARTY_PREFS = "third_party_resources"
        private const val KEY_RESOURCES = "resources_list"

        fun loadResources(prefs: SharedPreferences, context: Context): List<ThirdPartyResource> {
            val json = prefs.getString(KEY_RESOURCES, null)
            if (json != null) {
                return try {
                    val type = object : TypeToken<List<ThirdPartyResource>>() {}.type
                    Gson().fromJson<List<ThirdPartyResource>>(json, type) ?: emptyList()
                } catch (_: Exception) { emptyList() }
            }
            // First launch: populate presets
            val presets = createPresets(context)
            prefs.edit().putString(KEY_RESOURCES, Gson().toJson(presets)).apply()
            return presets
        }

        private fun createPresets(context: Context): List<ThirdPartyResource> {
            return listOf(
                ThirdPartyResource(
                    id = "preset_moe",
                    name = context.getString(R.string.resource_moe),
                    description = context.getString(R.string.resource_moe_desc),
                    script = "https://gitee.com/mo2/linux/raw/2/2.awk",
                    url = "https://github.trss.me/Install/TMOE.html"
                ),
                ThirdPartyResource(
                    id = "preset_lightpanel",
                    name = context.getString(R.string.resource_lightpanel),
                    description = context.getString(R.string.resource_lightpanel_desc),
                    script = "install_lightpanel",
                    url = "https://github.com/MyUI0/lightpanel",
                    needsContainerCheck = true,
                    needsLinuxContainer = true,
                    type = "install_lightpanel"
                ),
                ThirdPartyResource(
                    id = "preset_minecraft",
                    name = context.getString(R.string.resource_minecraft_server),
                    description = context.getString(R.string.resource_minecraft_server_desc),
                    script = "curl -sSL https://raw.githubusercontent.com/TheRemote/MinecraftBedrockServer/master/SetupMinecraft.sh | bash",
                    url = "https://github.com/TheRemote/MinecraftBedrockServer",
                    needsContainerCheck = true,
                    copyToClipboard = true
                ),
                ThirdPartyResource(
                    id = "preset_lamp",
                    name = context.getString(R.string.resource_linux_server),
                    description = context.getString(R.string.resource_linux_server_desc),
                    script = "curl -sSL https://raw.githubusercontent.com/teddysun/lamp/master/lamp.sh | bash",
                    url = "https://github.com/teddysun/lamp",
                    needsContainerCheck = true,
                    copyToClipboard = true
                ),
                ThirdPartyResource(
                    id = "preset_nginx",
                    name = context.getString(R.string.resource_web_server),
                    description = context.getString(R.string.resource_web_server_desc),
                    script = "curl -sSL https://raw.githubusercontent.com/angristan/nginx-autoinstall/master/nginx-autoinstall.sh | bash",
                    url = "https://nginx.org/",
                    needsContainerCheck = true,
                    copyToClipboard = true
                ),
                ThirdPartyResource(
                    id = "preset_nodejs",
                    name = context.getString(R.string.resource_node_js),
                    description = context.getString(R.string.resource_node_js_desc),
                    script = "https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh",
                    url = "https://nodejs.org/"
                ),
                ThirdPartyResource(
                    id = "preset_python",
                    name = context.getString(R.string.resource_python_env),
                    description = context.getString(R.string.resource_python_env_desc),
                    script = "pkg install python -y",
                    url = "https://www.python.org/",
                    type = "python_pkg"
                )
            )
        }
    }
}

/** Resolve a ThirdPartyResource into an executable shell command */
private fun resolveThirdPartyCommand(r: ThirdPartyResource, context: Context): String {
    // Special type: install_lightpanel (asset-based, needs Linux container)
    if (r.type == "install_lightpanel") {
        val item = ResourceItem(
            title = r.name,
            description = r.description,
            url = r.url,
            scriptUrl = "install_lightpanel",
            iconRes = R.drawable.ic_terminal,
            needsLinuxContainer = true
        )
        return resolveCommand(item, context)
    }

    // URL-based scripts: use resolveCommand (handles .awk, .py, .sh download & execute)
    if (r.script.startsWith("http")) {
        val item = ResourceItem(
            title = r.name,
            description = r.description,
            url = r.url,
            scriptUrl = r.script,
            iconRes = R.drawable.ic_terminal
        )
        return resolveCommand(item, context)
    }

    // Direct commands (pkg install, curl pipe, etc.)
    return r.script
}

/** Third-party resource data class */
data class ThirdPartyResource(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val script: String,
    val url: String,
    val needsContainerCheck: Boolean = false,
    val copyToClipboard: Boolean = false,
    val needsLinuxContainer: Boolean = false,
    val type: String = "default"
)

@Composable
private fun ThirdPartyResourceCard(
    item: ThirdPartyResource,
    isExpanded: Boolean,
    hasRunningSessions: Boolean,
    sessions: List<TerminalSession>,
    onExecute: () -> Unit,
    onToggleExpand: () -> Unit,
    onExecuteInNewSession: (String) -> Unit,
    onExecuteInTmux: (String) -> Unit,
    onExecuteInRunningSession: (String, String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val cardBackgroundColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFFAFAFA)
    val onSurfaceColor = MiuixTheme.colorScheme.onSurface
    val primaryColor = MiuixTheme.colorScheme.primary
    val surfaceVariantColor = MiuixTheme.colorScheme.surfaceVariant
    val dividerColor = onSurfaceColor.copy(alpha = 0.15f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardBackgroundColor)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                        .background(primaryColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_terminal),
                        contentDescription = item.name,
                        modifier = Modifier.size(24.dp),
                        tint = Color.White
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = item.name,
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor)
                    )
                    if (item.description.isNotBlank()) {
                        Text(
                            text = item.description,
                            style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        )
                    }
                }
            }

            item.script.takeIf { it.isNotBlank() }?.let { script ->
                androidx.compose.material3.HorizontalDivider(color = dividerColor)
                Text(
                    text = stringResource(R.string.resource_script_label, script),
                    style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (item.url.isNotBlank() || item.script.isNotBlank()) {
                androidx.compose.material3.HorizontalDivider(color = dividerColor)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val grayButtonColors = ButtonDefaults.buttonColors(
                        color = if (isDark) Color(0xFF424242) else Color(0xFFE0E0E0),
                        contentColor = onSurfaceColor
                    )

                    // Info button (icon on top, text below, centered)
                    if (item.url.isNotBlank()) {
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(item.url))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.padding(end = 8.dp),
                            colors = grayButtonColors
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_info),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = onSurfaceColor
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.view_reference),
                                    color = onSurfaceColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    // Edit button (icon on top, text below, centered)
                    Button(
                        onClick = onEdit,
                        modifier = Modifier.padding(end = 8.dp),
                        colors = grayButtonColors
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_edit),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = onSurfaceColor
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.edit),
                                color = onSurfaceColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }

                    // Delete button (icon on top, text below, centered)
                    Button(
                        onClick = onDelete,
                        modifier = Modifier.padding(end = 8.dp),
                        colors = grayButtonColors
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = onSurfaceColor
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.delete),
                                color = onSurfaceColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }

                    // Execute/Copy button (icon on top, text below, centered)
                    if (item.copyToClipboard) {
                        Button(
                            onClick = onExecute,
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_copy),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color.White
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.copy_resource),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    } else {
                        val buttonText = if (isExpanded) stringResource(R.string.collapse) else stringResource(R.string.execute)
                        Button(
                            onClick = onToggleExpand,
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    painter = painterResource(if (isExpanded) R.drawable.ic_collapse else R.drawable.ic_play),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color.White
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = buttonText,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // Expanded execution options
            if (isExpanded && !item.copyToClipboard) {
                val baseCommand = resolveThirdPartyCommand(item, context)

                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .background(surfaceVariantColor)
                        .clip(RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.select_execution_method),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor
                        ),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Button(
                        onClick = { onExecuteInNewSession(baseCommand) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_terminal),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.White
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.execute_in_new_session),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = { onExecuteInTmux(baseCommand) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_terminal),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = onSurfaceColor
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.execute_in_tmux),
                            color = onSurfaceColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (hasRunningSessions) {
                        Text(
                            text = stringResource(R.string.execute_in_running_session),
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            ),
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            sessions.forEach { session ->
                                Button(
                                    onClick = { onExecuteInRunningSession(session.id, baseCommand) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_terminal),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = onSurfaceColor
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = context.getString(R.string.copy_to_session, session.name),
                                        color = onSurfaceColor,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
