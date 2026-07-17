package com.termux.app.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import com.termux.R
import com.termux.shared.shell.TermuxSession

@Composable
fun MainScreen(
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    sessions: List<TermuxSession>,
    onSessionClick: (TermuxSession) -> Unit,
    onNewTerminal: () -> Unit,
    onStopTerminal: (TermuxSession) -> Unit,
    onRenameTerminal: (TermuxSession, String) -> Unit,
    onExecuteScript: (String, String) -> Unit,
    onAboutClick: () -> Unit,
    showVnc: Boolean,
    isWakeLockEnabled: Boolean,
    onToggleWakeLock: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(modifier = Modifier.padding(horizontal = 12.dp)) {
                NavigationBarItem(
                    icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                    label = stringResource(R.string.terminal),
                    selected = selectedTab == 0,
                    onClick = { onTabChange(0) }
                )
                NavigationBarItem(
                    icon = ImageVector.vectorResource(R.drawable.ic_files),
                    label = stringResource(R.string.files),
                    selected = selectedTab == 1,
                    onClick = { onTabChange(1) }
                )
                NavigationBarItem(
                    icon = ImageVector.vectorResource(R.drawable.ic_vnc),
                    label = stringResource(R.string.remote),
                    selected = selectedTab == 2,
                    onClick = { onTabChange(2) }
                )
                NavigationBarItem(
                    icon = ImageVector.vectorResource(R.drawable.ic_resources),
                    label = stringResource(R.string.resources),
                    selected = selectedTab == 3,
                    onClick = { onTabChange(3) }
                )
                NavigationBarItem(
                    icon = ImageVector.vectorResource(R.drawable.ic_settings),
                    label = stringResource(R.string.settings),
                    selected = selectedTab == 4,
                    onClick = { onTabChange(4) }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> TerminalListScreen(
                    sessions = sessions,
                    onSessionClick = onSessionClick,
                    onNewTerminal = onNewTerminal,
                    onStopTerminal = onStopTerminal,
                    onRenameTerminal = onRenameTerminal,
                    isWakeLockEnabled = isWakeLockEnabled,
                    onToggleWakeLock = onToggleWakeLock
                )
                1 -> FileManagerScreen(onOpenFile = onExecuteScript)
                2 -> com.termux.app.remote.RemoteScreen(showVnc = showVnc)
                3 -> ResourcesScreen(onExecuteScript = onExecuteScript)
                4 -> SettingsScreen(onAboutClick = onAboutClick)
            }
        }
    }
}