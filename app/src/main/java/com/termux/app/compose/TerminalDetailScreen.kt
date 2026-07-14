package com.termux.app.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import com.termux.shared.shell.TermuxSession
import com.termux.view.TerminalView

@Composable
fun TerminalDetailScreen(
    session: TermuxSession,
    onBack: () -> Unit,
    onNewTerminal: () -> Unit,
    onStopTerminal: () -> Unit
) {
    val terminalSession = session.getTerminalSession()

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = terminalSession.mSessionName ?: "Terminal",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_back),
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewTerminal) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_new_session),
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onStopTerminal) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_close),
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface
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
                    TerminalView(ctx, null)
                }
            )
        }
    }
}