package com.termux.app.compose

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R

object TerminalTopBarState {
    var title by mutableStateOf("")
}

private val mainHandler = Handler(Looper.getMainLooper())

@Composable
fun TerminalTopBar(
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.White
            )
        }
        Text(
            text = TerminalTopBarState.title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )
        IconButton(onClick = onNewSession) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.White
            )
        }
        IconButton(onClick = onCloseSession) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.White
            )
        }
    }
}

fun updateTerminalTitle(title: String) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        TerminalTopBarState.title = title
    } else {
        mainHandler.post {
            TerminalTopBarState.title = title
        }
    }
}

fun setTerminalTopBarContent(
    composeView: ComposeView,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: () -> Unit
) {
    composeView.setContent {
        TerminalTopBar(
            onBack = onBack,
            onNewSession = onNewSession,
            onCloseSession = onCloseSession
        )
    }
}
