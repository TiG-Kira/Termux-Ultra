package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.termux.R
import com.termux.app.TermuxService
import com.termux.shared.shell.TermuxSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ============================================================
// Data Models
// ============================================================

enum class OverviewCardType {
    TIPS_AGENT,
    SESSIONS,
    CPU_MONITOR,
    GPU_MONITOR,
    PROCESS_LIST,
    STOP_ALL,
    RESOURCE_ACTION
}

enum class CardSize {
    SMALL,
    WIDE
}

data class OverviewCardConfig(
    val id: String,
    val type: OverviewCardType,
    var isVisible: Boolean = true,
    var size: CardSize = CardSize.SMALL,
    var position: Int = 0,
    var resourceActionId: String? = null  // For RESOURCE_ACTION cards
)

// Resource action types
enum class ResourceActionCategory {
    UTILITY_CENTER,  // 实用功能中心
    THIRD_PARTY_CENTER,  // 第三方资源中心
    SYSTEM_FUNCTION  // 系统功能
}

data class ResourceAction(
    val id: String,
    val name: String,
    val description: String = "",
    val category: ResourceActionCategory,
    val script: String? = null,  // Shell script to execute
    val url: String? = null,  // URL for reference
    val iconRes: Int = R.drawable.ic_terminal,
    val type: String = "default",
    val needsContainerCheck: Boolean = false,
    val copyToClipboard: Boolean = false
)

// Available resource actions list
object ResourceActions {
    fun getUtilityCenterActions(): List<ResourceAction> = listOf(
        ResourceAction(
            id = "qemu_vnc",
            name = "QEMU with VNC",
            description = "在 Termux 中通过 VNC 运行虚拟机",
            category = ResourceActionCategory.UTILITY_CENTER,
            iconRes = R.drawable.ic_server,
            type = "qemu_on_vnc"
        ),
        ResourceAction(
            id = "debian_qemu",
            name = "Debian QEMU",
            description = "在 QEMU 中安装 Debian Linux",
            category = ResourceActionCategory.UTILITY_CENTER,
            script = "debian_qemu",
            iconRes = R.drawable.ic_server,
            type = "qemu_termux"
        ),
        ResourceAction(
            id = "ubuntu_container",
            name = "Ubuntu 容器",
            description = "安装 Ubuntu Linux 容器（PRoot）",
            category = ResourceActionCategory.UTILITY_CENTER,
            script = "install_debian_container",
            iconRes = R.drawable.ic_ubuntu,
            type = "install_debian_container"
        ),
        ResourceAction(
            id = "tmux",
            name = "tmux",
            description = "后台执行任务，防止终端关闭导致进程结束",
            category = ResourceActionCategory.UTILITY_CENTER,
            script = "pkg install tmux -y",
            iconRes = R.drawable.ic_terminal
        ),
        ResourceAction(
            id = "qemu_install",
            name = "QEMU 安装",
            description = "在 Linux 容器内安装 QEMU 虚拟机套件",
            category = ResourceActionCategory.UTILITY_CENTER,
            script = "install_qemu",
            iconRes = R.drawable.ic_server,
            type = "install_qemu_in_container"
        )
    )
    
    fun getThirdPartyActions(context: Context): List<ResourceAction> {
        val prefs = context.getSharedPreferences("third_party_resources", Context.MODE_PRIVATE)
        val json = prefs.getString("resources_list", null)
        if (json != null) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<com.termux.app.activities.ThirdPartyResource>>() {}.type
                val resources = com.google.gson.Gson().fromJson<List<com.termux.app.activities.ThirdPartyResource>>(json, type) ?: emptyList()
                return resources.map { r ->
                    ResourceAction(
                        id = "tp_${r.id}",
                        name = r.name,
                        description = r.description,
                        category = ResourceActionCategory.THIRD_PARTY_CENTER,
                        script = r.script,
                        url = r.url,
                        iconRes = R.drawable.ic_code,
                        type = r.type,
                        needsContainerCheck = r.needsContainerCheck,
                        copyToClipboard = r.copyToClipboard
                    )
                }
            } catch (_: Exception) {}
        }
        return emptyList()
    }
    
    fun getAllActions(context: Context): List<ResourceAction> {
        return getUtilityCenterActions() + getThirdPartyActions(context)
    }
    
    fun getActionById(context: Context, id: String): ResourceAction? {
        return getAllActions(context).find { it.id == id }
    }
}

data class ProcessInfo(
    val pid: Int,
    val name: String,
    val cpuPercent: Float,
    val memPercent: Float,
    val isFrozen: Boolean = false
)

// ============================================================
// Card Configuration Manager
// ============================================================

class OverviewCardManager(context: Context) {
    private val prefs = context.getSharedPreferences("overview_cards", Context.MODE_PRIVATE)
    
    companion object {
        private var instance: OverviewCardManager? = null
        
        fun getInstance(context: Context): OverviewCardManager {
            if (instance == null) {
                instance = OverviewCardManager(context.applicationContext)
            }
            return instance!!
        }
    }
    
    fun getCards(): List<OverviewCardConfig> {
        val cardOrder = prefs.getString("card_order", null)
        if (cardOrder != null) {
            return cardOrder.split(",").mapIndexed { index, id ->
                val type = OverviewCardType.valueOf(
                    prefs.getString("${id}_type", OverviewCardType.TIPS_AGENT.name) ?: OverviewCardType.TIPS_AGENT.name
                )
                OverviewCardConfig(
                    id = id,
                    type = type,
                    isVisible = prefs.getBoolean("${id}_visible", true),
                    size = CardSize.valueOf(
                        prefs.getString("${id}_size", CardSize.SMALL.name) ?: CardSize.SMALL.name
                    ),
                    position = index,
                    resourceActionId = prefs.getString("${id}_resource_action_id", null)
                )
            }
        }
        return getDefaultCards()
    }
    
    fun getDefaultCards(): List<OverviewCardConfig> {
        return listOf(
            OverviewCardConfig("tips_agent", OverviewCardType.TIPS_AGENT, isVisible = true, size = CardSize.WIDE, position = 0),
            OverviewCardConfig("sessions", OverviewCardType.SESSIONS, isVisible = true, size = CardSize.WIDE, position = 1),
            OverviewCardConfig("cpu", OverviewCardType.CPU_MONITOR, isVisible = true, size = CardSize.SMALL, position = 2),
            OverviewCardConfig("gpu", OverviewCardType.GPU_MONITOR, isVisible = true, size = CardSize.SMALL, position = 3),
            OverviewCardConfig("processes", OverviewCardType.PROCESS_LIST, isVisible = true, size = CardSize.WIDE, position = 4),
            OverviewCardConfig("stop_all", OverviewCardType.STOP_ALL, isVisible = true, size = CardSize.SMALL, position = 5)
        )
    }
    
    fun saveCards(cards: List<OverviewCardConfig>) {
        val editor = prefs.edit()
        val order = cards.joinToString(",") { it.id }
        editor.putString("card_order", order)
        cards.forEach { card ->
            editor.putString("${card.id}_type", card.type.name)
            editor.putBoolean("${card.id}_visible", card.isVisible)
            editor.putString("${card.id}_size", card.size.name)
            if (card.resourceActionId != null) {
                editor.putString("${card.id}_resource_action_id", card.resourceActionId)
            } else {
                editor.remove("${card.id}_resource_action_id")
            }
        }
        editor.apply()
    }
    
    fun updateCard(card: OverviewCardConfig) {
        val cards = getCards().toMutableList()
        val index = cards.indexOfFirst { it.id == card.id }
        if (index >= 0) {
            cards[index] = card
            saveCards(cards)
        }
    }
    
    fun moveCard(fromIndex: Int, toIndex: Int) {
        val cards = getCards().toMutableList()
        if (fromIndex in cards.indices && toIndex in cards.indices) {
            val card = cards.removeAt(fromIndex)
            cards.add(toIndex, card)
            saveCards(cards)
        }
    }
}

// ============================================================
// Overview Screen
// ============================================================

@Composable
fun OverviewScreen(
    sessions: List<TermuxSession>,
    onSessionClick: (TermuxSession) -> Unit,
    onNewTerminal: () -> Unit,
    onStopAllSessions: () -> Unit,
    isWakeLockEnabled: Boolean,
    onToggleWakeLock: () -> Unit,
    onExecuteScript: (String, String) -> Unit = { _, _ -> },
    onRefresh: () -> Unit = {},
    onEditModeChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cardManager = remember { OverviewCardManager.getInstance(context) }
    var isEditMode by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf(cardManager.getCards()) }
    var showCardSettings by remember { mutableStateOf(false) }
    var showAddCardDialog by remember { mutableStateOf(false) }
    var selectedCardId by remember { mutableStateOf<String?>(null) }
    
    // Notify edit mode changes
    LaunchedEffect(isEditMode) {
        onEditModeChanged(isEditMode)
    }
    
    // CPU/GPU monitoring
    var cpuUsage by remember { mutableFloatStateOf(0f) }
    var cpuTemperature by remember { mutableFloatStateOf(0f) }
    var gpuUsage by remember { mutableFloatStateOf(0f) }
    var cpuHistory by remember { mutableStateOf<List<Float>>(emptyList()) }
    var gpuHistory by remember { mutableStateOf<List<Float>>(emptyList()) }
    
    // Process list
    var processList by remember { mutableStateOf<List<ProcessInfo>>(emptyList()) }
    
    // Session counts
    val runningSessions = sessions.filter { it.getTerminalSession().isRunning }
    val stoppedSessions = sessions.filter { !it.getTerminalSession().isRunning }
    
    // Load cards
    LaunchedEffect(Unit) {
        cards = cardManager.getCards()
    }
    
    // Save cards on change
    LaunchedEffect(cards) {
        cardManager.saveCards(cards)
    }
    
    // CPU/GPU monitoring loop
    LaunchedEffect(Unit) {
        // First call to initialize baseline
        readCpuUsage()
        delay(500)
        while (true) {
            cpuUsage = readCpuUsage()
            cpuTemperature = readCpuTemperature()
            
            // Try GraphicsStatsManager first (most reliable for Android N+)
            var newGpuUsage = readGpuUsageFromStats(context)
            
            // Fallback to sysfs paths if GraphicsStatsManager fails
            if (newGpuUsage < 0f) {
                newGpuUsage = readGpuUsage()
            }
            
            // Update GPU usage only if available, keep last known value otherwise
            gpuUsage = newGpuUsage
            
            // Update history for charts
            MonitorHistory.addCpu(cpuUsage)
            MonitorHistory.addGpu(gpuUsage)
            cpuHistory = MonitorHistory.getCpuHistory()
            gpuHistory = MonitorHistory.getGpuHistory()
            
            delay(1000)
        }
    }
    
    // Process list monitoring
    LaunchedEffect(Unit) {
        while (true) {
            processList = readProcessList()
            delay(2000)
        }
    }
    
    val scrollBehavior = MiuixScrollBehavior()
    
    val filteredCards = cards.filter { it.isVisible }.sortedBy { it.position }
    
    // Card settings dialog
    if (showCardSettings && selectedCardId != null) {
        val card = cards.find { it.id == selectedCardId!! }
        if (card != null) {
            val sortedCards = cards.sortedBy { it.position }
            val currentIndex = sortedCards.indexOfFirst { it.id == card.id }
            
            OverlayDialog(
                show = showCardSettings,
                onDismissRequest = { showCardSettings = false },
                title = stringResource(R.string.overview_card_settings),
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        // Card name
                        Text(
                            text = getCardTypeName(card.type),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        // Toggle visibility
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.overview_card_enabled),
                                fontSize = 15.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Switch(
                                checked = card.isVisible,
                                onCheckedChange = { enabled ->
                                    cards = cards.map { 
                                        if (it.id == card.id) it.copy(isVisible = enabled) else it 
                                    }
                                }
                            )
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        // Position adjustment
                        Text(
                            text = stringResource(R.string.overview_card_position),
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                text = "↑ ${stringResource(R.string.overview_move_up)}",
                                onClick = {
                                    if (currentIndex > 0) {
                                        val newIndex = currentIndex - 1
                                        val updatedList = sortedCards.toMutableList()
                                        val movedCard = updatedList.removeAt(currentIndex)
                                        updatedList.add(newIndex, movedCard)
                                        cards = updatedList.mapIndexed { index, c ->
                                            c.copy(position = index)
                                        }
                                    }
                                },
                                enabled = currentIndex > 0
                            )
                            TextButton(
                                text = "↓ ${stringResource(R.string.overview_move_down)}",
                                onClick = {
                                    if (currentIndex < sortedCards.size - 1) {
                                        val newIndex = currentIndex + 1
                                        val updatedList = sortedCards.toMutableList()
                                        val movedCard = updatedList.removeAt(currentIndex)
                                        updatedList.add(newIndex, movedCard)
                                        cards = updatedList.mapIndexed { index, c ->
                                            c.copy(position = index)
                                        }
                                    }
                                },
                                enabled = currentIndex < sortedCards.size - 1
                            )
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        // Size selection (not for STOP_ALL)
                        if (card.type != OverviewCardType.STOP_ALL) {
                            val cardLayoutMode = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                                .getInt("KEY_CARD_LAYOUT_MODE", 0)
                            val isVerticalMode = cardLayoutMode == 0
                            val disableSmallForTipsAgent = card.type == OverviewCardType.TIPS_AGENT && isVerticalMode
                            
                            Text(
                                text = stringResource(R.string.overview_card_size),
                                fontSize = 15.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    text = stringResource(R.string.overview_card_size_small),
                                    onClick = {
                                        cards = cards.map {
                                            if (it.id == card.id) it.copy(size = CardSize.SMALL) else it
                                        }
                                    },
                                    enabled = !disableSmallForTipsAgent,
                                    colors = if (card.size == CardSize.SMALL) {
                                        top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary()
                                    } else {
                                        top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors()
                                    }
                                )
                                TextButton(
                                    text = stringResource(R.string.overview_card_size_wide),
                                    onClick = {
                                        cards = cards.map {
                                            if (it.id == card.id) it.copy(size = CardSize.WIDE) else it
                                        }
                                    },
                                    colors = if (card.size == CardSize.WIDE) {
                                        top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary()
                                    } else {
                                        top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors()
                                    }
                                )
                            }
                            
                            if (disableSmallForTipsAgent) {
                                Text(
                                    text = stringResource(R.string.overview_tips_agent_vertical_hint),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        
                        // Delete card (except TIPS_AGENT which can't be deleted)
                        if (card.type != OverviewCardType.TIPS_AGENT) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            TextButton(
                                text = stringResource(R.string.overview_delete_card),
                                onClick = {
                                    cards = cards.filter { it.id != card.id }
                                    showCardSettings = false
                                },
                                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors()
                            )
                        }
                    }
                }
            )
        }
    }
    
    // Add card dialog
    if (showAddCardDialog) {
        val availableTypes = OverviewCardType.values().filter { type ->
            type != OverviewCardType.STOP_ALL // STOP_ALL can't be added manually
        }
        // RESOURCE_ACTION can be added multiple times
        val existingTypes = cards.filter { it.type != OverviewCardType.RESOURCE_ACTION }
            .map { it.type }.toSet()
        
        OverlayDialog(
            show = showAddCardDialog,
            onDismissRequest = { showAddCardDialog = false },
            title = stringResource(R.string.overview_add_card),
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    availableTypes.forEach { type ->
                        // RESOURCE_ACTION type can always be added
                        val isAlreadyAdded = type != OverviewCardType.RESOURCE_ACTION && existingTypes.contains(type)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isAlreadyAdded) {
                                    if (!isAlreadyAdded) {
                                        val newId = "${type.name.lowercase()}_${System.currentTimeMillis()}"
                                        val maxPosition = cards.maxOfOrNull { it.position } ?: 0
                                        val newCard = OverviewCardConfig(
                                            id = newId,
                                            type = type,
                                            isVisible = true,
                                            size = if (type == OverviewCardType.SESSIONS || 
                                                     type == OverviewCardType.PROCESS_LIST ||
                                                     type == OverviewCardType.TIPS_AGENT) CardSize.WIDE 
                                                   else CardSize.SMALL,
                                            position = maxPosition + 1
                                        )
                                        cards = cards + newCard
                                        showAddCardDialog = false
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = getCardIcon(type),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (isAlreadyAdded) 
                                        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f) 
                                    else 
                                        MiuixTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = getCardTypeName(type),
                                    fontSize = 16.sp,
                                    color = if (isAlreadyAdded) 
                                        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f) 
                                    else 
                                        MiuixTheme.colorScheme.onSurface
                                )
                            }
                            if (isAlreadyAdded) {
                                Text(
                                    text = stringResource(R.string.overview_already_added),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            } else if (type == OverviewCardType.RESOURCE_ACTION) {
                                Text(
                                    text = stringResource(R.string.overview_can_add_multiple),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.primary
                                )
                            }
                        }
                        if (type != availableTypes.last()) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        )
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.overview_title),
                scrollBehavior = scrollBehavior,
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            showAddCardDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = {
                            isEditMode = !isEditMode
                        }) {
                            Icon(
                                imageVector = if (isEditMode) Icons.Rounded.Check else Icons.Rounded.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        // Calculate waterfall layout: reorder cards for optimal placement
        val orderedCards = remember(filteredCards) {
            calculateWaterfallOrder(filteredCards)
        }
        
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 92.dp, start = 16.dp, end = 16.dp)
        ) {
            items(
                items = orderedCards,
                span = { card ->
                    if (card.size == CardSize.WIDE) GridItemSpan(2) else GridItemSpan(1)
                }
            ) { card ->
                CardItem(
                    card = card,
                    context = context,
                    isEditMode = isEditMode,
                    cpuUsage = cpuUsage,
                    cpuTemperature = cpuTemperature,
                    gpuUsage = gpuUsage,
                    cpuHistory = cpuHistory,
                    gpuHistory = gpuHistory,
                    processList = processList,
                    runningSessions = runningSessions,
                    stoppedSessions = stoppedSessions,
                    sessions = sessions,
                    onSessionClick = onSessionClick,
                    onStopAllSessions = onStopAllSessions,
                    onNewTerminal = onNewTerminal,
                    onExecuteScript = onExecuteScript,
                    selectedCardId = selectedCardId,
                    onCardSelected = { selectedCardId = it },
                    onShowCardSettings = { showCardSettings = true }
                )
            }
        }
    }
}

// ============================================================
// Tips & Agent Card (Migrated from Terminal List)
// ============================================================

@Composable
private fun TipsAgentCard(
    card: OverviewCardConfig,
    isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val aiTermuxEnabled = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .getBoolean("ai_termux_enabled", true)
    val cardLayoutMode = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .getInt("KEY_CARD_LAYOUT_MODE", 0)
    val useHorizontalLayout = cardLayoutMode == 1
    var showWelcomeCard by remember { mutableStateOf(false) }
    var showKeepAliveWarning by remember { mutableStateOf(false) }
    var showLowCard by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("terminal_welcome_shown", false)) {
            showWelcomeCard = true
        }
        if (ApiCompat.isAvailable(ApiCompat.Feature.KEEP_ALIVE_WARNING)) {
            if (!prefs.getBoolean("keep_alive_warning_dismissed", false)) {
                showKeepAliveWarning = true
            }
        }
        showLowCard = ApiCompat.hasAnyRuntimeDisabled() ||
            (ApiCompat.isLowAndroid && (ApiCompat.hasAnyForceEnabled(context) || true))
    }
    
    Column(modifier = Modifier.fillMaxWidth()) {
        // Title bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.overview_card_tips),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (isEditMode) {
                IconButton(onClick = onEditClick) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
        
        if (useHorizontalLayout) {
            HorizontalTipsContent(
                aiTermuxEnabled = aiTermuxEnabled,
                showWelcomeCard = showWelcomeCard,
                showKeepAliveWarning = showKeepAliveWarning,
                showLowCard = showLowCard,
                onWelcomeClose = {
                    showWelcomeCard = false
                    context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("terminal_welcome_shown", true).apply()
                },
                onKeepAliveClose = {
                    showKeepAliveWarning = false
                    context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("keep_alive_warning_dismissed", true).apply()
                }
            )
        } else {
            VerticalTipsContent(
                aiTermuxEnabled = aiTermuxEnabled,
                showWelcomeCard = showWelcomeCard,
                showKeepAliveWarning = showKeepAliveWarning,
                showLowCard = showLowCard,
                onWelcomeClose = {
                    showWelcomeCard = false
                    context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("terminal_welcome_shown", true).apply()
                },
                onKeepAliveClose = {
                    showKeepAliveWarning = false
                    context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("keep_alive_warning_dismissed", true).apply()
                }
            )
        }
    }
}

@Composable
private fun HorizontalTipsContent(
    aiTermuxEnabled: Boolean,
    showWelcomeCard: Boolean,
    showKeepAliveWarning: Boolean,
    showLowCard: Boolean,
    onWelcomeClose: () -> Unit,
    onKeepAliveClose: () -> Unit
) {
    val context = LocalContext.current
    
    LazyRow(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (aiTermuxEnabled) {
            item {
                AiTermuxEntryCard(horizontalMode = true)
            }
        }
        if (showWelcomeCard) {
            item {
                WelcomeCard(
                    text = stringResource(R.string.terminal_welcome_message),
                    onClose = onWelcomeClose,
                    horizontalMode = true
                )
            }
        }
        if (showKeepAliveWarning) {
            item {
                KeepAliveWarningCard(
                    onClose = onKeepAliveClose,
                    horizontalMode = true
                )
            }
        }
        if (showLowCard) {
            item {
                LowAndroidWarningCard(horizontalMode = true)
            }
        } else {
            item {
                ServiceStatusCard(
                    status = ServiceStatus.NORMAL,
                    killedSessionName = null,
                    horizontalMode = true
                )
            }
        }
    }
}

@Composable
private fun VerticalTipsContent(
    aiTermuxEnabled: Boolean,
    showWelcomeCard: Boolean,
    showKeepAliveWarning: Boolean,
    showLowCard: Boolean,
    onWelcomeClose: () -> Unit,
    onKeepAliveClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (aiTermuxEnabled) {
            AiTermuxEntryCard(horizontalMode = false)
        }
        if (showWelcomeCard) {
            WelcomeCard(
                text = stringResource(R.string.terminal_welcome_message),
                onClose = onWelcomeClose,
                horizontalMode = false
            )
        }
        if (showKeepAliveWarning) {
            KeepAliveWarningCard(
                onClose = onKeepAliveClose,
                horizontalMode = false
            )
        }
        if (showLowCard) {
            LowAndroidWarningCard(horizontalMode = false)
        } else {
            ServiceStatusCard(
                status = ServiceStatus.NORMAL,
                killedSessionName = null,
                horizontalMode = false
            )
        }
    }
}

// ============================================================
// Sessions Card
// ============================================================

@Composable
private fun SessionsCard(
    card: OverviewCardConfig,
    runningCount: Int,
    stoppedCount: Int,
    sessions: List<TermuxSession>,
    onSessionClick: (TermuxSession) -> Unit,
    isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.overview_card_sessions),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isEditMode) {
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Running sessions
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isDark) Color(0xFF1B3A1F) else Color(0xFFE8F5E9)
                        )
                        .clickable(enabled = !isEditMode && sessions.isNotEmpty()) {
                            val running = sessions.filter { it.getTerminalSession().isRunning }
                            if (running.isNotEmpty()) {
                                onSessionClick(running.first())
                            }
                        }
                        .padding(10.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFF4CAF50)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.overview_running),
                                fontSize = 11.sp,
                                color = Color(0xFF4CAF50)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = runningCount.toString(),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
                
                // Stopped sessions
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isDark) Color(0xFF3B1414) else Color(0xFFFFEBEE)
                        )
                        .padding(10.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFFE57373)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.overview_stopped),
                                fontSize = 11.sp,
                                color = Color(0xFFE57373)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stoppedCount.toString(),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE57373)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// CPU Monitor Card
// ============================================================

@Composable
private fun CpuMonitorCard(
    card: OverviewCardConfig,
    usage: Float,
    temperature: Float,
    history: List<Float>,
    isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val usageColor = getUsageColor(usage)
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.overview_card_cpu),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isEditMode) {
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            // Usage value and chart
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "${usage.toInt()}%",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = usageColor
                    )
                }
                Column(
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier.weight(2f)
                ) {
                    UsageChart(
                        data = history,
                        color = usageColor
                    )
                }
            }
            
            if (temperature > 0f) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.overview_cpu_temp, temperature),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

// ============================================================
// GPU Monitor Card
// ============================================================

@Composable
private fun GpuMonitorCard(
    card: OverviewCardConfig,
    usage: Float,
    history: List<Float>,
    isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val isGpuAvailable = usage >= 0f
    val hasHistoricalData = MonitorHistory.hasGpuHistory()
    val peakUsage = MonitorHistory.getGpuPeak()
    val validHistory = MonitorHistory.getValidGpuHistory()
    val usageColor = if (isGpuAvailable) getUsageColor(usage) 
                      else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Monitor,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isGpuAvailable) MiuixTheme.colorScheme.primary 
                           else MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.overview_card_gpu),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isEditMode) {
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            // Usage value and chart
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isGpuAvailable) {
                        Text(
                            text = "${usage.toInt()}%",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = usageColor
                        )
                    } else if (hasHistoricalData) {
                        Text(
                            text = "${peakUsage.toInt()}%",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.overview_gpu_peak),
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                        )
                    } else {
                        Text(
                            text = "N/A",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier.weight(2f)
                ) {
                    if (isGpuAvailable) {
                        UsageChart(
                            data = history,
                            color = usageColor
                        )
                    } else if (hasHistoricalData) {
                        UsageChart(
                            data = validHistory,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.overview_no_gpu_data),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// Usage Chart Component
// ============================================================

@Composable
private fun UsageChart(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        if (data.isEmpty()) {
            drawRect(
                color = color.copy(alpha = 0.1f),
                size = size
            )
            return@Canvas
        }
        
        val stepX = size.width / (MAX_CHART_POINTS - 1).coerceAtLeast(1)
        val maxY = 100f
        val barWidth = size.height / maxY
        
        // Draw gradient background fill
        val path = Path()
        val fillPath = Path()
        
        val dataToDraw = if (data.size < MAX_CHART_POINTS) {
            List(MAX_CHART_POINTS - data.size) { 0f } + data
        } else {
            data.takeLast(MAX_CHART_POINTS)
        }
        
        for ((index, value) in dataToDraw.withIndex()) {
            val x = index * stepX
            val y = size.height - (value.coerceIn(0f, 100f) * barWidth)
            
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        
        // Draw gradient fill
        if (dataToDraw.isNotEmpty()) {
            val lastX = (dataToDraw.size - 1) * stepX
            fillPath.lineTo(lastX, size.height)
            fillPath.close()
            
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = 0.3f),
                        color.copy(alpha = 0.05f)
                    )
                )
            )
        }
        
        // Draw line
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2f),
            alpha = 0.9f
        )
        
        // Draw last point highlight
        if (dataToDraw.isNotEmpty()) {
            val lastIndex = dataToDraw.size - 1
            val lastValue = dataToDraw[lastIndex]
            val lastX = lastIndex * stepX
            val lastY = size.height - (lastValue.coerceIn(0f, 100f) * barWidth)
            
            drawCircle(
                color = color,
                radius = 3f,
                center = Offset(lastX, lastY)
            )
        }
    }
}

private const val MAX_CHART_POINTS = 30

// ============================================================
// Process List Card
// ============================================================

@Composable
private fun ProcessListCard(
    card: OverviewCardConfig,
    processes: List<ProcessInfo>,
    isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val frozenCount = processes.count { it.isFrozen }
    val activeProcesses = processes.filter { !it.isFrozen }
    val frozenProcesses = processes.filter { it.isFrozen }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.overview_card_processes),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (frozenCount > 0) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = MiuixTheme.colorScheme.error.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.overview_frozen_count, frozenCount),
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (isEditMode) {
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (processes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.overview_no_processes),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.overview_process_name),
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.overview_process_cpu),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    
                    // Active processes (top 5)
                    activeProcesses.take(5).forEach { process ->
                        ProcessItemRow(process)
                    }
                    
                    // Frozen processes (show up to 3)
                    if (frozenProcesses.isNotEmpty()) {
                        if (activeProcesses.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        
                        // Frozen section header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MiuixTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.overview_frozen_processes),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.error
                            )
                        }
                        
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MiuixTheme.colorScheme.error.copy(alpha = 0.3f)
                        )
                        
                        // Frozen process list
                        frozenProcesses.take(3).forEach { process ->
                            ProcessItemRow(process)
                        }
                        
                        // Show more indicator
                        if (frozenProcesses.size > 3) {
                            Text(
                                text = stringResource(R.string.overview_frozen_more, frozenProcesses.size - 3),
                                fontSize = 10.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessItemRow(process: ProcessInfo) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = process.name,
                fontSize = 12.sp,
                color = if (process.isFrozen) 
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                else 
                    MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (process.isFrozen) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(
                            color = MiuixTheme.colorScheme.error.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(3.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = stringResource(R.string.overview_frozen_tag),
                        fontSize = 9.sp,
                        color = MiuixTheme.colorScheme.error
                    )
                }
            }
        }
        Text(
            text = if (process.isFrozen) "—" else "${process.cpuPercent.toInt()}%",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (process.isFrozen) 
                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
            else 
                getUsageColor(process.cpuPercent)
        )
    }
}

// ============================================================
// Stop All Card
// ============================================================

@Composable
private fun StopAllCard(
    card: OverviewCardConfig,
    sessionCount: Int,
    isEditMode: Boolean,
    onStopAll: () -> Unit,
    onEditClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    var showConfirmDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isEditMode && sessionCount > 0) {
                showConfirmDialog = true
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color(0xFFE57373)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.overview_card_stop_all),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isEditMode) {
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        if (sessionCount > 0) Color(0xFFFFEBEE) else Color(0xFFE0E0E0)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (sessionCount > 0) Color(0xFFE57373) else Color(0xFFBDBDBD)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = if (sessionCount > 0) "$sessionCount ${stringResource(R.string.overview_running)}" 
                    else stringResource(R.string.overview_no_sessions),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
    
    // Confirm dialog
    if (showConfirmDialog) {
        OverlayDialog(
            show = showConfirmDialog,
            onDismissRequest = { showConfirmDialog = false },
            title = stringResource(R.string.overview_card_stop_all),
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.overview_stop_all_confirm),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showConfirmDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = stringResource(R.string.ok),
                            onClick = {
                                showConfirmDialog = false
                                onStopAll()
                            },
                            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        )
    }
}

// ============================================================
// Helper Functions
// ============================================================

fun getUsageColor(usage: Float): Color {
    return when {
        usage < 50f -> Color(0xFF4CAF50)
        usage < 80f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
}

fun getCardTypeName(type: OverviewCardType): String {
    return when (type) {
        OverviewCardType.TIPS_AGENT -> "Tips & Agent"
        OverviewCardType.SESSIONS -> "Sessions"
        OverviewCardType.CPU_MONITOR -> "CPU Monitor"
        OverviewCardType.GPU_MONITOR -> "GPU Monitor"
        OverviewCardType.PROCESS_LIST -> "Process List"
        OverviewCardType.STOP_ALL -> "Stop All"
        OverviewCardType.RESOURCE_ACTION -> "Resource Action"
    }
}

fun getCardIcon(type: OverviewCardType): ImageVector {
    return when (type) {
        OverviewCardType.TIPS_AGENT -> Icons.Rounded.Info
        OverviewCardType.SESSIONS -> Icons.Rounded.Memory
        OverviewCardType.CPU_MONITOR -> Icons.Rounded.Monitor
        OverviewCardType.GPU_MONITOR -> Icons.Rounded.Speed
        OverviewCardType.PROCESS_LIST -> Icons.Rounded.List
        OverviewCardType.STOP_ALL -> Icons.Rounded.Stop
        OverviewCardType.RESOURCE_ACTION -> Icons.Rounded.PlayArrow
    }
}

// ============================================================
// System Stats Readers
// ============================================================

private data class CpuStats(
    val idle: Long,
    val total: Long
)

private object CpuMonitor {
    private var lastStats: CpuStats? = null
    private var initialized = false
    
    @Synchronized
    fun getCpuUsage(): Float {
        val current = readProcStat() ?: return 0f
        
        if (!initialized) {
            lastStats = current
            initialized = true
            return 0f // First reading, need second sample
        }
        
        val last = lastStats ?: return 0f
        
        // Check if stats changed
        if (current.total == last.total) {
            return 0f // No change
        }
        
        val totalDelta = current.total - last.total
        val idleDelta = current.idle - last.idle
        
        lastStats = current
        
        return if (totalDelta > 0) {
            val usage = ((totalDelta - idleDelta).toFloat() / totalDelta) * 100
            usage.coerceIn(0f, 100f)
        } else {
            0f
        }
    }
    
    @Synchronized
    fun reset() {
        lastStats = null
        initialized = false
    }
}

private fun readProcStat(): CpuStats? {
    return try {
        val statFile = java.io.File("/proc/stat")
        if (statFile.canRead()) {
            var result: CpuStats? = null
            statFile.forEachLine { line ->
                if (result == null && line.startsWith("cpu ")) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 5) {
                        val idle = parts[4].toLongOrNull() ?: 0L
                        val total = parts.drop(1).sumOf { it.toLongOrNull() ?: 0L }
                        result = CpuStats(idle, total)
                    }
                }
            }
            result
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

fun readCpuUsage(): Float {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("ps", "-A", "-o", "PID,NAME,%CPU"))
        val reader = process.inputStream.bufferedReader()
        val lines = reader.readLines()
        reader.close()
        process.waitFor()
        
        var totalCpu = 0f
        for (line in lines.drop(1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split("\\s+".toRegex())
            if (parts.size >= 3) {
                val name = parts[1]
                val cpuStr = parts[2]
                // Filter Termux-related processes
                if (name.contains("termux", ignoreCase = true) || 
                    name.contains("bash", ignoreCase = true) ||
                    name.contains("ps", ignoreCase = true)) {
                    val cpu = cpuStr.toFloatOrNull() ?: 0f
                    totalCpu += cpu
                }
            }
        }
        totalCpu.coerceIn(0f, 100f)
    } catch (e: Exception) {
        0f
    }
}

// GPU detection using GraphicsStatsManager (API 24+) via reflection
fun readGpuUsageFromStats(context: Context): Float {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val gpuStats = getGpuStatsFromManager(context)
            if (gpuStats != null) {
                return gpuStats
            }
        }
        -1f
    } catch (e: Exception) {
        -1f
    }
}

private fun getGpuStatsFromManager(context: Context): Float? {
    return try {
        // Use reflection to access GraphicsStatsManager
        val service = context.getSystemService("graphicsstats")
        if (service == null) return null
        
        val myPid = Process.myPid()
        
        // Try to get frame stats using reflection
        try {
            val getFrameStatsMethod = service.javaClass.getMethod("getFrameStats", Int::class.java, Class.forName("android.graphics.FrameInfo"))
            val frameStats = getFrameStatsMethod.invoke(service, myPid, null) as? List<*>
            
            if (frameStats != null && frameStats.isNotEmpty()) {
                val recentStats = frameStats.lastOrNull()
                if (recentStats != null) {
                    val totalFramesField = recentStats.javaClass.getDeclaredField("totalFrameCount")
                    val jankyFramesField = recentStats.javaClass.getDeclaredField("jankyFrameCount")
                    
                    totalFramesField.isAccessible = true
                    jankyFramesField.isAccessible = true
                    
                    val totalFrames = totalFramesField.getLong(recentStats)
                    val jankyFrames = jankyFramesField.getLong(recentStats)
                    
                    if (totalFrames > 0) {
                        val jankRatio = jankyFrames.toFloat() / totalFrames
                        return (jankRatio * 200f).coerceIn(0f, 100f)
                    }
                }
            }
        } catch (_: Exception) {
        }
        
        // Try alternate: get drop frames
        try {
            val getDropFramesMethod = service.javaClass.getMethod("getDropFrames", Int::class.java)
            val droppedFrames = getDropFramesMethod.invoke(service, myPid) as? Int
            if (droppedFrames != null && droppedFrames > 0) {
                return (droppedFrames.toFloat() * 10f).coerceIn(0f, 100f)
            }
        } catch (_: Exception) {
        }
        
        // If we can access the service, GPU is available
        return 5f
    } catch (e: Exception) {
        null
    }
}

fun readGpuUsage(): Float {
    return try {
        // Try multiple GPU detection methods
        val gpuPaths = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",  // Qualcomm Adreno
            "/sys/class/kgsl/kgsl-3d0/gpu_busy",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/devices/platform/kgsl-3d0.0/gpu/gpu_busy_percentage",
            "/sys/class/mali/utilization",                     // ARM Mali
            "/sys/devices/platform/soc/soc:gpu/utilization",
            "/sys/class/devfreq/gpufreq/cur_load",              // MediaTek
            "/sys/class/devfreq/mtk-dvfsrc-devfreq/gpufreq/cur_load",
            "/sys/kernel/gpu/gpu_busy",                          // Generic
            "/sys/class/gpu/gpu0/load",
            "/sys/kernel/debug/mali0/utilization",
            "/proc/mali/utilization",
            "/sys/devices/soc/gpu/gpu_busy"
        )
        
        for (path in gpuPaths) {
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) {
                val content = file.readText().trim()
                val value = content.toFloatOrNull()
                if (value != null) {
                    return value.coerceIn(0f, 100f)
                }
            }
        }
        
        // Alternative: try to compute from various paths
        val alternativePaths = listOf(
            "/sys/class/mali/mali0/utilization",
            "/sys/devices/mali0/utilization",
            "/sys/kernel/debug/mali0/utilization",
            "/sys/devices/platform/soc/fd000000.gpu/utilization"
        )
        
        for (path in alternativePaths) {
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) {
                val content = file.readText().trim()
                val value = content.toFloatOrNull()
                if (value != null) {
                    return value.coerceIn(0f, 100f)
                }
            }
        }
        
        // Try checking if GPU device exists (even if we can't read usage)
        val gpuDevicePaths = listOf(
            "/dev/kgsl-3d0",
            "/dev/mali0",
            "/dev/mali",
            "/dev/gpu"
        )
        
        for (path in gpuDevicePaths) {
            val file = java.io.File(path)
            if (file.exists()) {
                // GPU device exists, return 0% as baseline
                return 0f
            }
        }
        
        // Fallback: compute from frame rendering using dumpsys
        try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "gfxinfo", "termux"))
            val reader = process.inputStream.bufferedReader()
            val lines = reader.readLines()
            reader.close()
            process.waitFor()
            
            var jankyFrames = 0
            var totalFrames = 0
            for (line in lines) {
                if (line.contains("jankyFrames")) {
                    jankyFrames += line.split(":")[1].trim().toIntOrNull() ?: 0
                }
                if (line.contains("frameTimeline")) {
                    totalFrames++
                }
            }
            
            if (totalFrames > 0) {
                return (jankyFrames.toFloat() / totalFrames * 100f).coerceIn(0f, 100f)
            }
        } catch (_: Exception) {
        }
        
        // Try dumpsys SurfaceFlinger
        try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "SurfaceFlinger", "--list"))
            val reader = process.inputStream.bufferedReader()
            val output = reader.readText()
            reader.close()
            process.waitFor()
            
            if (output.contains("Termux") || output.contains("termux")) {
                return 0f  // GPU is being used by Termux
            }
        } catch (_: Exception) {
        }
        
        // GPU truly unavailable
        -1f
    } catch (e: Exception) {
        -1f
    }
}

// Monitor history tracker for charts
object MonitorHistory {
    private const val MAX_HISTORY = 30
    private val cpuHistory = mutableListOf<Float>()
    private val gpuHistory = mutableListOf<Float>()
    private var gpuPeak = 0f
    
    @Synchronized
    fun addCpu(value: Float) {
        cpuHistory.add(value)
        if (cpuHistory.size > MAX_HISTORY) cpuHistory.removeAt(0)
    }
    
    @Synchronized
    fun addGpu(value: Float) {
        if (value >= 0f) {
            if (value > gpuPeak) gpuPeak = value
        }
        gpuHistory.add(value)
        if (gpuHistory.size > MAX_HISTORY) gpuHistory.removeAt(0)
    }
    
    @Synchronized
    fun getCpuHistory(): List<Float> = cpuHistory.toList()
    
    @Synchronized
    fun getGpuHistory(): List<Float> = gpuHistory.toList()
    
    @Synchronized
    fun getGpuPeak(): Float = gpuPeak
    
    @Synchronized
    fun hasGpuHistory(): Boolean = gpuHistory.any { it >= 0f }
    
    @Synchronized
    fun getValidGpuHistory(): List<Float> = gpuHistory.map { if (it >= 0f) it else 0f }
    
    @Synchronized
    fun reset() {
        cpuHistory.clear()
        gpuHistory.clear()
        gpuPeak = 0f
    }
}

fun readCpuTemperature(): Float {
    return try {
        val tempPaths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/hwmon/hwmon0/temp1_input"
        )
        
        for (path in tempPaths) {
            val file = java.io.File(path)
            if (file.exists()) {
                val tempStr = file.readText().trim()
                val temp = tempStr.toFloatOrNull()
                if (temp != null) {
                    return if (temp > 100) temp / 1000f else temp
                }
            }
        }
        0f
    } catch (_: Exception) {
        0f
    }
}

fun readProcessList(): List<ProcessInfo> {
    val processes = mutableListOf<ProcessInfo>()
    val frozenProcesses = mutableListOf<ProcessInfo>()
    
    try {
        // Get process list with state information
        val process = Runtime.getRuntime().exec(arrayOf("ps", "-A", "-o", "PID,STATE,NAME,%CPU,%MEM"))
        val reader = process.inputStream.bufferedReader()
        val lines = reader.readLines()
        reader.close()
        process.waitFor()
        
        // Skip header line
        if (lines.size > 1) {
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 5) {
                    val pid = parts[0].toIntOrNull() ?: continue
                    val state = parts[1]
                    val name = parts[2]
                    val cpu = parts[3].toFloatOrNull() ?: 0f
                    val mem = parts[4].toFloatOrNull() ?: 0f
                    
                    // Check if process is frozen (state 'T' or 't' means stopped/frozen)
                    val isFrozen = state.startsWith("T") || state.startsWith("t")
                    
                    // Also check cgroup freezer state
                    val freezerFrozen = checkFreezerState(pid)
                    
                    if (cpu > 0.1f || isFrozen || freezerFrozen) {
                        val processInfo = ProcessInfo(
                            pid = pid,
                            name = name,
                            cpuPercent = if (isFrozen || freezerFrozen) 0f else cpu,
                            memPercent = mem,
                            isFrozen = isFrozen || freezerFrozen
                        )
                        
                        if (processInfo.isFrozen) {
                            frozenProcesses.add(processInfo)
                        } else {
                            processes.add(processInfo)
                        }
                    }
                }
            }
        }
    } catch (_: Exception) {}
    
    // Merge: active processes first, then frozen processes
    // Sort active by CPU usage, frozen by name
    val activeProcesses = processes.sortedByDescending { it.cpuPercent }.take(10)
    val sortedFrozen = frozenProcesses.sortedBy { it.name }
    
    // Combine and return top processes
    return (activeProcesses + sortedFrozen).take(15)
}

private fun checkFreezerState(pid: Int): Boolean {
    return try {
        // Check cgroup v2 freezer state
        val freezerFile = java.io.File("/proc/$pid/freezer_state")
        if (freezerFile.exists() && freezerFile.canRead()) {
            val state = freezerFile.readText().trim()
            if (state == "FROZEN" || state == "ON") {
                return true
            }
        }
        
        // Check cgroup v1
        val cgroupFile = java.io.File("/proc/$pid/cgroup")
        if (cgroupFile.exists() && cgroupFile.canRead()) {
            val content = cgroupFile.readText()
            // If the process is in a frozen cgroup
            if (content.contains("freezer") || content.contains("frozen")) {
                // Try to check the freezer state
                val pathParts = content.trim().split(":")
                if (pathParts.size >= 3) {
                    val freezerPath = "/sys/fs/cgroup/freezer/${pathParts[2].trim()}"
                    val freezerStateFile = java.io.File("$freezerPath/freezer.state")
                    if (freezerStateFile.exists() && freezerStateFile.canRead()) {
                        val state = freezerStateFile.readText().trim()
                        if (state == "FROZEN") {
                            return true
                        }
                    }
                }
            }
        }
        
        // Check /proc/pid/status for stopped state
        val statusFile = java.io.File("/proc/$pid/status")
        if (statusFile.exists() && statusFile.canRead()) {
            val status = statusFile.readText()
            // Look for State line with 'T' character
            val stateLine = status.lines().find { it.startsWith("State:") }
            if (stateLine != null) {
                val stateChar = stateLine.trim().split("\\s+".toRegex()).getOrNull(1)
                if (stateChar == "T" || stateChar == "t") {
                    return true
                }
            }
        }
        
        false
    } catch (_: Exception) {
        false
    }
}

// ============================================================
// Resource Action Card
// ============================================================

@Composable
private fun ResourceActionCard(
    card: OverviewCardConfig,
    context: Context,
    isEditMode: Boolean,
    onActionSelected: (String) -> Unit,
    onLaunchAction: (ResourceAction) -> Unit,
    onEditClick: () -> Unit
) {
    val action = card.resourceActionId?.let { ResourceActions.getActionById(context, it) }
    var showSelectDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (action != null) {
                    if (!isEditMode) {
                        onLaunchAction(action)
                    }
                } else {
                    // No action selected, open selection dialog
                    showSelectDialog = true
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (action != null) action.name else stringResource(R.string.overview_resource_action),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Edit button - always visible for changing selection
                IconButton(onClick = {
                    if (action != null) {
                        // Open selection dialog to change action
                        showSelectDialog = true
                    } else {
                        // Open selection dialog to add action
                        showSelectDialog = true
                    }
                }) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (action != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = action.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        if (action.description.isNotEmpty()) {
                            Text(
                                text = action.description,
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val categoryText = when (action.category) {
                            ResourceActionCategory.UTILITY_CENTER -> stringResource(R.string.overview_utility_center)
                            ResourceActionCategory.THIRD_PARTY_CENTER -> stringResource(R.string.overview_third_party_center)
                            ResourceActionCategory.SYSTEM_FUNCTION -> stringResource(R.string.overview_system_function)
                        }
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = categoryText,
                                fontSize = 10.sp,
                                color = MiuixTheme.colorScheme.primary
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MiuixTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSelectDialog = true }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.overview_select_action),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
        }
    }
    
    ResourceActionSelectionDialog(
        context = context,
        show = showSelectDialog,
        currentActionId = card.resourceActionId,
        onActionSelected = { actionId ->
            onActionSelected(actionId)
            showSelectDialog = false
        },
        onDismiss = { showSelectDialog = false }
    )
}

// ============================================================
// Resource Action Selection Dialog
// ============================================================

@Composable
private fun ResourceActionSelectionDialog(
    context: Context,
    show: Boolean,
    currentActionId: String?,
    onActionSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val utilityActions = remember { ResourceActions.getUtilityCenterActions() }
    val thirdPartyActions = remember { ResourceActions.getThirdPartyActions(context) }
    var selectedTab by remember { mutableStateOf(0) }
    
    OverlayDialog(
        show = show,
        title = stringResource(R.string.overview_select_action_title),
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        stringResource(R.string.overview_utility_center),
                        stringResource(R.string.overview_third_party_center)
                    ).forEachIndexed { index, title ->
                        Box(
                            modifier = Modifier
                                .clickable { selectedTab = index }
                                .background(
                                    color = if (selectedTab == index) 
                                        MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) 
                                    else 
                                        MiuixTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                fontSize = 13.sp,
                                color = if (selectedTab == index) 
                                    MiuixTheme.colorScheme.primary 
                                else 
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                val actions = if (selectedTab == 0) utilityActions else thirdPartyActions
                
                if (actions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.overview_no_actions),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(actions) { action ->
                            val isSelected = action.id == currentActionId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onActionSelected(action.id) }
                                    .background(
                                        color = if (isSelected) 
                                            MiuixTheme.colorScheme.primary.copy(alpha = 0.1f) 
                                        else 
                                            MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = action.iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MiuixTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = action.name,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                    if (action.description.isNotEmpty()) {
                                        Text(
                                            text = action.description,
                                            fontSize = 11.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

// ============================================================
// Resource Action Launcher
// ============================================================

fun launchResourceAction(
    context: Context,
    action: ResourceAction,
    onExecuteScript: (String, String) -> Unit
) {
    when (action.type) {
        "qemu_on_vnc" -> {
            val intent = Intent(context, com.termux.app.activities.QemuVmActivity::class.java)
            context.startActivity(intent)
        }
        else -> {
            val script = action.script
            if (script != null) {
                // Use onExecuteScript to create a new terminal and execute the script
                onExecuteScript(action.name, script)
            } else {
                action.url?.let { url ->
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(browserIntent)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}

// ============================================================
// Card Item - Unified card renderer
// ============================================================

@Composable
private fun CardItem(
    card: OverviewCardConfig,
    context: Context,
    isEditMode: Boolean,
    cpuUsage: Float,
    cpuTemperature: Float,
    gpuUsage: Float,
    cpuHistory: List<Float>,
    gpuHistory: List<Float>,
    processList: List<ProcessInfo>,
    runningSessions: List<TermuxSession>,
    stoppedSessions: List<TermuxSession>,
    sessions: List<TermuxSession>,
    onSessionClick: (TermuxSession) -> Unit,
    onStopAllSessions: () -> Unit,
    onNewTerminal: () -> Unit,
    onExecuteScript: (String, String) -> Unit,
    selectedCardId: String?,
    onCardSelected: (String) -> Unit,
    onShowCardSettings: () -> Unit
) {
    when (card.type) {
        OverviewCardType.TIPS_AGENT -> {
            TipsAgentCard(
                card = card,
                isEditMode = isEditMode,
                onEditClick = {
                    onCardSelected(card.id)
                    onShowCardSettings()
                }
            )
        }
        OverviewCardType.SESSIONS -> {
            SessionsCard(
                card = card,
                runningCount = runningSessions.size,
                stoppedCount = stoppedSessions.size,
                sessions = sessions,
                onSessionClick = onSessionClick,
                onEditClick = {
                    onCardSelected(card.id)
                    onShowCardSettings()
                },
                isEditMode = isEditMode
            )
        }
        OverviewCardType.CPU_MONITOR -> {
            CpuMonitorCard(
                card = card,
                usage = cpuUsage,
                temperature = cpuTemperature,
                history = cpuHistory,
                isEditMode = isEditMode,
                onEditClick = {
                    onCardSelected(card.id)
                    onShowCardSettings()
                }
            )
        }
        OverviewCardType.GPU_MONITOR -> {
            GpuMonitorCard(
                card = card,
                usage = gpuUsage,
                history = gpuHistory,
                isEditMode = isEditMode,
                onEditClick = {
                    onCardSelected(card.id)
                    onShowCardSettings()
                }
            )
        }
        OverviewCardType.PROCESS_LIST -> {
            ProcessListCard(
                card = card,
                processes = processList,
                isEditMode = isEditMode,
                onEditClick = {
                    onCardSelected(card.id)
                    onShowCardSettings()
                }
            )
        }
        OverviewCardType.STOP_ALL -> {
            StopAllCard(
                card = card,
                sessionCount = sessions.size,
                isEditMode = isEditMode,
                onStopAll = onStopAllSessions,
                onEditClick = {
                    onCardSelected(card.id)
                    onShowCardSettings()
                }
            )
        }
        OverviewCardType.RESOURCE_ACTION -> {
            ResourceActionCard(
                card = card,
                context = context,
                isEditMode = isEditMode,
                onActionSelected = { actionId: String ->
                    val cardManager = OverviewCardManager.getInstance(context)
                    val updatedCard = card.copy(resourceActionId = actionId)
                    cardManager.updateCard(updatedCard)
                },
                onLaunchAction = { action: ResourceAction ->
                    launchResourceAction(context, action, onExecuteScript)
                },
                onEditClick = {
                    onCardSelected(card.id)
                    onShowCardSettings()
                }
            )
        }
    }
}

// ============================================================
// Waterfall Layout Calculator
// ============================================================

/**
 * Calculate the optimal order for cards in a waterfall layout.
 * Small cards are placed in columns trying to keep heights balanced.
 * Wide cards are inserted at positions where both columns have similar heights.
 */
private fun calculateWaterfallOrder(cards: List<OverviewCardConfig>): List<OverviewCardConfig> {
    if (cards.isEmpty()) return cards
    
    // Estimate card heights (in arbitrary units, just for comparison)
    fun estimateHeight(card: OverviewCardConfig): Int {
        return when (card.type) {
            OverviewCardType.CPU_MONITOR -> 80
            OverviewCardType.GPU_MONITOR -> 80
            OverviewCardType.SESSIONS -> 90
            OverviewCardType.PROCESS_LIST -> 120
            OverviewCardType.TIPS_AGENT -> 70
            OverviewCardType.RESOURCE_ACTION -> 100
            OverviewCardType.STOP_ALL -> 80
            else -> 80
        }
    }
    
    // Separate cards by size
    val smallCards = cards.filter { it.size == CardSize.SMALL }
    val wideCards = cards.filter { it.size == CardSize.WIDE }
    
    // Build the layout column by column
    val result = mutableListOf<OverviewCardConfig>()
    var leftHeight = 0
    var rightHeight = 0
    var smallIndex = 0
    var wideIndex = 0
    
    // Interleave: place small cards in the shorter column,
    // and insert wide cards when heights are balanced
    while (smallIndex < smallCards.size || wideIndex < wideCards.size) {
        // Determine if we should place a wide card or a small card
        val canPlaceWide = wideIndex < wideCards.size
        val canPlaceSmall = smallIndex < smallCards.size
        
        if (!canPlaceSmall && canPlaceWide) {
            // Only wide cards left
            result.add(wideCards[wideIndex])
            val h = estimateHeight(wideCards[wideIndex])
            leftHeight += h
            rightHeight += h
            wideIndex++
        } else if (!canPlaceWide && canPlaceSmall) {
            // Only small cards left
            val card = smallCards[smallIndex]
            result.add(card)
            val h = estimateHeight(card)
            if (leftHeight <= rightHeight) {
                leftHeight += h
            } else {
                rightHeight += h
            }
            smallIndex++
        } else if (canPlaceWide && canPlaceSmall) {
            // Decide whether to place wide or small card
            // Place wide card when heights are close (difference < threshold)
            val heightDiff = kotlin.math.abs(leftHeight - rightHeight)
            val wideCard = wideCards[wideIndex]
            val wideHeight = estimateHeight(wideCard)
            
            // Place wide card if it helps balance or if heights are already close
            // and the wide card won't create too much imbalance
            val wouldBalance = (leftHeight <= rightHeight && leftHeight + wideHeight <= rightHeight) ||
                              (rightHeight < leftHeight && rightHeight + wideHeight <= leftHeight)
            
            if (heightDiff < 40 || wouldBalance) {
                // Place wide card
                result.add(wideCard)
                leftHeight += wideHeight
                rightHeight += wideHeight
                wideIndex++
            } else {
                // Place small card in the shorter column
                val card = smallCards[smallIndex]
                result.add(card)
                val h = estimateHeight(card)
                if (leftHeight <= rightHeight) {
                    leftHeight += h
                } else {
                    rightHeight += h
                }
                smallIndex++
            }
        }
    }
    
    return result
}
