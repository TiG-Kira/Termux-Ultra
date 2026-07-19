package com.termux.app.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import com.termux.shared.shell.TermuxSession
import com.termux.shared.view.KeyboardUtils
import com.termux.view.TerminalView

@Composable
fun TerminalDetailScreen(
    session: TermuxSession,
    onBack: () -> Unit,
    onNewTerminal: () -> Unit,
    onStopTerminal: () -> Unit
) {
    val terminalSession = session.getTerminalSession()
    val context = LocalContext.current
    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = terminalSession.mSessionName ?: "Terminal",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_back),
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val view = terminalViewRef.value
                        if (view != null) {
                            KeyboardUtils.showSoftKeyboard(context, view)
                        }
                    }) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_keyboard),
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = onNewTerminal) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_new_session),
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = onStopTerminal) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_close),
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize()
        ) {
            androidx.compose.ui.viewinterop.AndroidView<TerminalView>(
                factory = { ctx ->
                    TerminalView(ctx, null).also { terminalViewRef.value = it }
                }
            )
        }
    }
}
