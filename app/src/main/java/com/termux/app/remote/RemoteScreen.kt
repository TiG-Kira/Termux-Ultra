package com.termux.app.remote

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.termux.R
import com.termux.app.vnc.VncConnection
import com.termux.app.vnc.VncConnectionManager
import com.termux.app.ssh.SshConnection
import com.termux.app.ssh.SshConnectionManager
import androidx.compose.material3.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun RemoteScreen(showVnc: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedTabIndex = remember { mutableIntStateOf(0) }
    val isScanning = remember { mutableStateOf(false) }
    val vncAddRequested = remember { mutableStateOf(false) }
    val vncScanRequested = remember { mutableStateOf(false) }
    val sshAddRequested = remember { mutableStateOf(false) }
    val darkTheme = isSystemInDarkTheme()
    val topBarIconColor = if (darkTheme) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black

    val vncConnections = remember { mutableStateListOf<VncConnection>() }
    val sshConnections = remember { mutableStateListOf<SshConnection>() }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val vncManager = VncConnectionManager(context)
            vncConnections.addAll(vncManager.getConnections())

            val sshManager = SshConnectionManager(context)
            sshConnections.addAll(sshManager.getConnections())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = context.getString(R.string.remote),
                actions = {
                    if (showVnc && selectedTabIndex.value == 0) {
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
                                vncScanRequested.value = true
                                scope.launch(Dispatchers.IO) {
                                    isScanning.value = true
                                    Thread.sleep(1000)
                                    isScanning.value = false
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
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (showVnc) {
                Column(Modifier.fillMaxSize()) {
                    TabRow(
                        selectedTabIndex = selectedTabIndex.value,
                        containerColor = Color.Transparent,
                        contentColor = if (darkTheme) Color.White else Color.Black,
                        indicator = { tabPositions ->
                            TabRowDefaults.Indicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex.value]),
                                color = if (darkTheme) Color.White else Color.Black
                            )
                        }
                    ) {
                        Tab(
                            text = { Text("VNC", color = if (selectedTabIndex.value == 0) (if (darkTheme) Color.White else Color.Black) else (if (darkTheme) Color.Gray else Color.LightGray)) },
                            selected = selectedTabIndex.value == 0,
                            onClick = { selectedTabIndex.value = 0 },
                            unselectedContentColor = if (darkTheme) Color.Gray else Color.LightGray
                        )
                        Tab(
                            text = { Text("SSH", color = if (selectedTabIndex.value == 1) (if (darkTheme) Color.White else Color.Black) else (if (darkTheme) Color.Gray else Color.LightGray)) },
                            selected = selectedTabIndex.value == 1,
                            onClick = { selectedTabIndex.value = 1 },
                            unselectedContentColor = if (darkTheme) Color.Gray else Color.LightGray
                        )
                    }
                    Box(Modifier.fillMaxSize()) {
                        when (selectedTabIndex.value) {
                            0 -> com.termux.app.vnc.VncScreen(
                                connections = vncConnections,
                                addRequested = vncAddRequested.value,
                                onAddRequestedConsumed = { vncAddRequested.value = false },
                                scanRequested = vncScanRequested.value,
                                onScanRequestedConsumed = { vncScanRequested.value = false }
                            )
                            1 -> com.termux.app.ssh.SshScreen(
                                connections = sshConnections,
                                addRequested = sshAddRequested.value,
                                onAddRequestedConsumed = { sshAddRequested.value = false }
                            )
                        }
                    }
                }
            } else {
                com.termux.app.ssh.SshScreen(
                    connections = sshConnections,
                    addRequested = sshAddRequested.value,
                    onAddRequestedConsumed = { sshAddRequested.value = false }
                )
            }
        }
    }
}
