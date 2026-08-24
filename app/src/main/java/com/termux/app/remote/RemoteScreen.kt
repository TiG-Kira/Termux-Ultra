package com.termux.app.remote

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.termux.R
import com.termux.app.vnc.VncConnection
import com.termux.app.vnc.VncConnectionManager
import com.termux.app.vnc.connectToVnc
import com.termux.app.ssh.SshConnection
import com.termux.app.ssh.SshConnectionManager
import com.termux.app.ssh.connectToSsh
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SWIPE_THRESHOLD = 100f

@Composable
fun RemoteScreen(
    showVnc: Boolean,
    initialTab: Int = 0,
    onTabChange: (Int) -> Unit = {},
    onGoToFiles: () -> Unit = {},
    onGoToSettings: () -> Unit = {},
    navBarBottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTabIndex by remember { mutableIntStateOf(initialTab) }
    val topBarDragOffset = remember { mutableFloatStateOf(0f) }
    var contentAlphaState by remember { mutableFloatStateOf(1f) }
    var isTopBarSwiiping by remember { mutableStateOf(false) }
    val isScanning = remember { mutableStateOf(false) }
    val vncAddRequested = remember { mutableStateOf(false) }
    val vncScanRequested = remember { mutableStateOf(false) }
    val sshAddRequested = remember { mutableStateOf(false) }
    val darkTheme = isSystemInDarkTheme()
    val topBarIconColor = if (darkTheme) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black

    val vncConnections = remember { mutableStateListOf<VncConnection>() }
    val sshConnections = remember { mutableStateListOf<SshConnection>() }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val vncManager = VncConnectionManager(context)
            vncConnections.addAll(vncManager.getConnections())

            val sshManager = SshConnectionManager(context)
            sshConnections.addAll(sshManager.getConnections())
        }
    }

    LaunchedEffect(initialTab) {
        selectedTabIndex = initialTab
    }

    val topAppBarTitle = if (showVnc) {
        context.getString(R.string.remote)
    } else {
        context.getString(R.string.ssh)
    }

    val scrollBehavior = MiuixScrollBehavior()

    val activeConnections = if (!showVnc || selectedTabIndex == 1) {
        sshConnections
    } else {
        vncConnections
    }
    val activeLabel = if (!showVnc || selectedTabIndex == 1) "SSH" else "VNC"

    val topBarSwipeThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    val screenWidthPx = with(LocalDensity.current) {
        val config = androidx.compose.ui.platform.LocalConfiguration.current
        config.screenWidthDp.dp.toPx()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Box(
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                topBarDragOffset.floatValue = 0f
                                isTopBarSwiiping = true
                                contentAlphaState = 1f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                topBarDragOffset.floatValue += dragAmount.x
                                val progress = if (screenWidthPx > 0f) {
                                    kotlin.math.abs(topBarDragOffset.floatValue) / screenWidthPx
                                } else {
                                    0f
                                }
                                contentAlphaState = (1f - progress).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                val exceeded = kotlin.math.abs(topBarDragOffset.floatValue) >= topBarSwipeThresholdPx
                                val startOffset = topBarDragOffset.floatValue

                                scope.launch {
                                    animate(
                                        initialValue = 0f,
                                        targetValue = 1f,
                                        animationSpec = tween(300)
                                    ) { fraction, _ ->
                                        if (exceeded) {
                                            contentAlphaState = (1f - fraction).coerceIn(0f, 1f)
                                        } else {
                                            val remaining = startOffset * (1f - fraction)
                                            topBarDragOffset.floatValue = remaining
                                            val progress = if (screenWidthPx > 0f) {
                                                kotlin.math.abs(remaining) / screenWidthPx
                                            } else {
                                                0f
                                            }
                                            contentAlphaState = (1f - progress).coerceIn(0f, 1f)
                                        }
                                    }
                                    topBarDragOffset.floatValue = 0f

                                    if (exceeded) {
                                        contentAlphaState = 1f
                                        if (startOffset < 0) {
                                            onGoToSettings()
                                        } else {
                                            onGoToFiles()
                                        }
                                    } else {
                                        contentAlphaState = 1f
                                    }
                                    isTopBarSwiiping = false
                                }
                            },
                            onDragCancel = {
                                val startOffset = topBarDragOffset.floatValue
                                scope.launch {
                                    animate(
                                        initialValue = 0f,
                                        targetValue = 1f,
                                        animationSpec = tween(250)
                                    ) { fraction, _ ->
                                        topBarDragOffset.floatValue = startOffset * (1f - fraction)
                                        val progress = if (screenWidthPx > 0f) {
                                            kotlin.math.abs(topBarDragOffset.floatValue) / screenWidthPx
                                        } else {
                                            0f
                                        }
                                        contentAlphaState = (1f - progress).coerceIn(0f, 1f)
                                    }
                                    topBarDragOffset.floatValue = 0f
                                    contentAlphaState = 1f
                                    isTopBarSwiiping = false
                                }
                            }
                        )
                    }
            ) {
                TopAppBar(
                    title = topAppBarTitle,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        Row {
                            if (showVnc && selectedTabIndex == 0) {
                                IconButton(
                                    onClick = {
                                        vncAddRequested.value = true
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_add),
                                        contentDescription = "添加",
                                        tint = topBarIconColor
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (!isScanning.value) {
                                            vncScanRequested.value = true
                                        }
                                    },
                                    enabled = !isScanning.value
                                ) {
                                    if (isScanning.value) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_refresh),
                                            contentDescription = "扫描",
                                            tint = topBarIconColor
                                        )
                                    }
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        sshAddRequested.value = true
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_add),
                                        contentDescription = "添加",
                                        tint = topBarIconColor
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .graphicsLayer {
                    alpha = contentAlphaState
                }
        ) {
            if (showVnc) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = navBarBottomPadding)
                ) {
                    TabRowWithContour(
                        tabs = listOf("VNC", "SSH"),
                        selectedTabIndex = selectedTabIndex,
                        onTabSelected = {
                            selectedTabIndex = it
                            onTabChange(it)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )

                    val filtered = activeConnections.filter { conn ->
                        when (conn) {
                            is VncConnection -> conn.name.contains(searchQuery, ignoreCase = true) ||
                                conn.host.contains(searchQuery, ignoreCase = true)
                            is SshConnection -> conn.name.contains(searchQuery, ignoreCase = true) ||
                                conn.host.contains(searchQuery, ignoreCase = true) ||
                                conn.username.contains(searchQuery, ignoreCase = true) ||
                                conn.dongleId.contains(searchQuery, ignoreCase = true)
                            else -> false
                        } || searchQuery.isEmpty()
                    }

                    SearchBar(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = if (searchExpanded) 8.dp else 0.dp),
                        inputField = {
                            InputField(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                onSearch = { },
                                expanded = searchExpanded,
                                onExpandedChange = {
                                    searchExpanded = it
                                    if (!it) searchQuery = ""
                                },
                                label = context.getString(R.string.search) + " ($activeLabel)"
                            )
                        },
                        expanded = searchExpanded,
                        onExpandedChange = {
                            searchExpanded = it
                            if (!it) searchQuery = ""
                        },
                        outsideEndAction = {
                            if (searchExpanded) {
                                TextButton(
                                    text = context.getString(R.string.cancel),
                                    onClick = {
                                        searchExpanded = false
                                        searchQuery = ""
                                    }
                                )
                            }
                        }
                    ) {
                        if (searchExpanded) {
                            when {
                                searchQuery.isEmpty() -> {
                                    Spacer(Modifier.size(24.dp))
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = context.getString(R.string.search_hint_input),
                                            color = androidx.compose.ui.graphics.Color.Gray,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                                filtered.isEmpty() -> {
                                    Spacer(Modifier.size(24.dp))
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = context.getString(R.string.search_no_result),
                                            color = androidx.compose.ui.graphics.Color.Gray,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                                else -> {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        filtered.forEach { conn ->
                                            val (cName, cDetail) = when (conn) {
                                                is VncConnection -> conn.name to "${conn.host}:${conn.port}"
                                                is SshConnection -> {
                                                    val tag = when (conn.connectionType) {
                                                        "openpilot" -> "[OpenPilot] "
                                                        "comma" -> "[Comma] "
                                                        "local" -> "[本地] "
                                                        else -> ""
                                                    }
                                                    val detail = when (conn.connectionType) {
                                                        "local" -> "${conn.username}@localhost:${conn.port}"
                                                        "comma" -> {
                                                            if (conn.deviceType == "external") "${conn.username}@${conn.dongleId} (${context.getString(R.string.ssh_method_dongle_id)})"
                                                            else "${conn.username}@${conn.host}:${conn.port}"
                                                        }
                                                        else -> "${conn.username}@${conn.host}:${conn.port}"
                                                    }
                                                    conn.name to "$tag$detail"
                                                }
                                                else -> "" to ""
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        when (conn) {
                                                            is VncConnection -> connectToVnc(context, conn)
                                                            is SshConnection -> connectToSsh(context, conn)
                                                            else -> {}
                                                        }
                                                        searchExpanded = false
                                                        searchQuery = ""
                                                    }
                                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_terminal),
                                                    contentDescription = null,
                                                    tint = MiuixTheme.colorScheme.onSurface,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = cName,
                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                        color = MiuixTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = cDetail,
                                                        fontSize = 12.sp,
                                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (searchExpanded) Modifier.graphicsLayer {
                                    translationY = 1_000_000f
                                } else Modifier
                            )
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { dragOffset = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffset += dragAmount.x
                                    },
                                    onDragEnd = {
                                        if (kotlin.math.abs(dragOffset) >= SWIPE_THRESHOLD) {
                                            if (dragOffset < 0) {
                                                if (selectedTabIndex == 0) {
                                                    selectedTabIndex = 1
                                                    onTabChange(1)
                                                }
                                            } else {
                                                if (selectedTabIndex == 1) {
                                                    selectedTabIndex = 0
                                                    onTabChange(0)
                                                }
                                            }
                                        }
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        dragOffset = 0f
                                    }
                                )
                            }
                    ) {
                        AnimatedContent(
                            targetState = selectedTabIndex,
                            label = "RemoteTabTransition",
                            transitionSpec = {
                                if (dragOffset != 0f) {
                                    androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                                } else {
                                    val direction = if (targetState > initialState) 1 else -1
                                    slideIntoContainer(
                                        if (direction > 0) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right,
                                        animationSpec = tween(200)
                                    ) togetherWith slideOutOfContainer(
                                        if (direction > 0) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right,
                                        animationSpec = tween(200)
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = dragOffset
                                }
                        ) { tabIndex ->
                            when (tabIndex) {
                                0 -> com.termux.app.vnc.VncScreen(
                                    connections = vncConnections,
                                    addRequested = vncAddRequested.value,
                                    onAddRequestedConsumed = { vncAddRequested.value = false },
                                    scanRequested = vncScanRequested.value,
                                    onScanRequestedConsumed = { vncScanRequested.value = false },
                                    onScanStart = { isScanning.value = true },
                                    onScanEnd = { isScanning.value = false },
                                    nestedScrollConnection = scrollBehavior.nestedScrollConnection,
                                    navBarBottomPadding = navBarBottomPadding
                                )
                                1 -> com.termux.app.ssh.SshScreen(
                                    connections = sshConnections,
                                    addRequested = sshAddRequested.value,
                                    onAddRequestedConsumed = { sshAddRequested.value = false },
                                    nestedScrollConnection = scrollBehavior.nestedScrollConnection,
                                    navBarBottomPadding = navBarBottomPadding
                                )
                                else -> Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = navBarBottomPadding)
                ) {
                    val filtered = sshConnections.filter { conn ->
                        conn.name.contains(searchQuery, ignoreCase = true) ||
                            conn.host.contains(searchQuery, ignoreCase = true) ||
                            conn.username.contains(searchQuery, ignoreCase = true) ||
                            conn.dongleId.contains(searchQuery, ignoreCase = true) ||
                            searchQuery.isEmpty()
                    }

                    SearchBar(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = if (searchExpanded) 8.dp else 0.dp),
                        inputField = {
                            InputField(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                onSearch = { },
                                expanded = searchExpanded,
                                onExpandedChange = {
                                    searchExpanded = it
                                    if (!it) searchQuery = ""
                                },
                                label = context.getString(R.string.search) + " (SSH)"
                            )
                        },
                        expanded = searchExpanded,
                        onExpandedChange = {
                            searchExpanded = it
                            if (!it) searchQuery = ""
                        },
                        outsideEndAction = {
                            if (searchExpanded) {
                                TextButton(
                                    text = context.getString(R.string.cancel),
                                    onClick = {
                                        searchExpanded = false
                                        searchQuery = ""
                                    }
                                )
                            }
                        }
                    ) {
                        if (searchExpanded) {
                            when {
                                searchQuery.isEmpty() -> {
                                    Spacer(Modifier.size(24.dp))
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = context.getString(R.string.search_hint_input),
                                            color = androidx.compose.ui.graphics.Color.Gray,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                                filtered.isEmpty() -> {
                                    Spacer(Modifier.size(24.dp))
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = context.getString(R.string.search_no_result),
                                            color = androidx.compose.ui.graphics.Color.Gray,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                                else -> {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        filtered.forEach { conn ->
                                            val (cName, cDetail) = when (conn) {
                                                is SshConnection -> {
                                                    val tag = when (conn.connectionType) {
                                                        "openpilot" -> "[OpenPilot] "
                                                        "comma" -> "[Comma] "
                                                        "local" -> "[本地] "
                                                        else -> ""
                                                    }
                                                    val detail = when (conn.connectionType) {
                                                        "local" -> "${conn.username}@localhost:${conn.port}"
                                                        "comma" -> {
                                                            if (conn.deviceType == "external") "${conn.username}@${conn.dongleId} (${context.getString(R.string.ssh_method_dongle_id)})"
                                                            else "${conn.username}@${conn.host}:${conn.port}"
                                                        }
                                                        else -> "${conn.username}@${conn.host}:${conn.port}"
                                                    }
                                                    conn.name to "$tag$detail"
                                                }
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        connectToSsh(context, conn)
                                                        searchExpanded = false
                                                        searchQuery = ""
                                                    }
                                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_terminal),
                                                    contentDescription = null,
                                                    tint = MiuixTheme.colorScheme.onSurface,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = cName,
                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                        color = MiuixTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = cDetail,
                                                        fontSize = 12.sp,
                                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (searchExpanded) Modifier.graphicsLayer {
                                    translationY = 1_000_000f
                                } else Modifier
                            )
                    ) {
                        com.termux.app.ssh.SshScreen(
                            connections = sshConnections,
                            addRequested = sshAddRequested.value,
                            onAddRequestedConsumed = { sshAddRequested.value = false },
                            nestedScrollConnection = scrollBehavior.nestedScrollConnection
                        )
                    }
                }
            }
        }
    }
}
