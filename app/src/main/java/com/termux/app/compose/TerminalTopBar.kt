package com.termux.app.compose

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R

object TerminalTopBarState {
    var title by mutableStateOf("")
}

@Composable
fun TerminalTopBar(
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: () -> Unit
) {
    SmallTopAppBar(
        title = TerminalTopBarState.title,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        actions = {
            IconButton(onClick = onNewSession) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = onCloseSession) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    )
}

fun updateTerminalTitle(title: String) {
    TerminalTopBarState.title = title
}

fun setTerminalTopBarContent(
    composeView: ComposeView,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: () -> Unit
) {
    composeView.setContent {
        MiuixTheme {
            TerminalTopBar(
                onBack = onBack,
                onNewSession = onNewSession,
                onCloseSession = onCloseSession
            )
        }
    }
}
