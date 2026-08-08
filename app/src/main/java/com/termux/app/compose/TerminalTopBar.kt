package com.termux.app.compose

import androidx.compose.ui.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

object TerminalTopBarState {
    var title by mutableStateOf("")
    var isKeyboardVisible by mutableStateOf(false)
    var iconColor by mutableStateOf(Color.White)
}

private val mainHandler = Handler(Looper.getMainLooper())

@Composable
fun TerminalTopBar(
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onLongPressNewSession: () -> Unit
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
                imageVector = MiuixIcons.Back,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = TerminalTopBarState.iconColor
            )
        }
        Text(
            text = TerminalTopBarState.title,
            color = TerminalTopBarState.iconColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )
        if (!TerminalTopBarState.isKeyboardVisible) {
            IconButton(onClick = onToggleKeyboard) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = TerminalTopBarState.iconColor
                )
            }
        }
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onNewSession,
                    onLongClick = onLongPressNewSession
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = TerminalTopBarState.iconColor
            )
        }
        IconButton(onClick = onCloseSession) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = TerminalTopBarState.iconColor
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

fun updateKeyboardVisibility(isVisible: Boolean) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        TerminalTopBarState.isKeyboardVisible = isVisible
    } else {
        mainHandler.post {
            TerminalTopBarState.isKeyboardVisible = isVisible
        }
    }
}

fun updateIconColorForBackground(backgroundColor: Int) {
    val isDark = isColorDark(backgroundColor)
    val color = if (isDark) Color.White else Color.Black
    if (Looper.myLooper() == Looper.getMainLooper()) {
        TerminalTopBarState.iconColor = color
    } else {
        mainHandler.post {
            TerminalTopBarState.iconColor = color
        }
    }
}

fun isColorDark(color: Int): Boolean {
    val r = android.graphics.Color.red(color)
    val g = android.graphics.Color.green(color)
    val b = android.graphics.Color.blue(color)
    val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return luminance < 0.5
}

fun setTerminalTopBarContent(
    composeView: ComposeView,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onLongPressNewSession: () -> Unit
) {
    // miuix UI 库不可用时跳过设置，避免 TermuxActivity 崩溃
    if (!ApiCompat.canLoadMiuixUi()) return
    composeView.setContent {
        TerminalTopBar(
            onBack = onBack,
            onNewSession = onNewSession,
            onCloseSession = onCloseSession,
            onToggleKeyboard = onToggleKeyboard,
            onLongPressNewSession = onLongPressNewSession
        )
    }
}
