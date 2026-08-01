package com.termux.app.compose

import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import com.termux.app.settings.properties.TermuxAppSharedProperties
import com.termux.shared.shell.TermuxSession
import com.termux.shared.terminal.TermuxTerminalViewClientBase
import com.termux.shared.terminal.io.TerminalExtraKeys
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView
import com.termux.shared.view.KeyboardUtils
import com.termux.terminal.TerminalSession
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
    val properties = remember { TermuxAppSharedProperties(context) }

    LaunchedEffect(Unit) {
        properties.loadTermuxPropertiesFromDisk()
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = terminalSession.mSessionName ?: stringResource(R.string.terminal),
                navigationIcon = {
                    Row(modifier = Modifier.padding(start = 16.dp)) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                },
                actions = {
                    Row(modifier = Modifier.padding(end = 16.dp)) {
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
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView<TerminalView>(
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        terminalViewRef.value = this
                        isFocusableInTouchMode = true
                        setTerminalViewClient(TerminalDetailViewClient())
                        attachSession(terminalSession)
                        requestFocus()
                    }
                },
                update = { view ->
                    view.attachSession(terminalSession)
                    view.requestFocus()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            AndroidView<ExtraKeysView>(
                factory = { ctx ->
                    val terminalView = terminalViewRef.value
                        ?: throw IllegalStateException("TerminalView must be created before ExtraKeysView")

                    ExtraKeysView(ctx, null).apply {
                        setExtraKeysViewClient(TerminalDetailExtraKeys(context, terminalView, terminalSession))
                        setButtonTextAllCaps(properties.shouldExtraKeysTextBeAllCaps())
                        reload(properties.getExtraKeysInfo())
                    }
                },
                update = { view ->
                    view.setButtonTextAllCaps(properties.shouldExtraKeysTextBeAllCaps())
                    view.reload(properties.getExtraKeysInfo())
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private class TerminalDetailViewClient : TermuxTerminalViewClientBase() {
    override fun onScale(scale: Float): Float {
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) {
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        return false
    }

    override fun onLongPress(event: MotionEvent): Boolean {
        return false
    }

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        return false
    }
}

private class TerminalDetailExtraKeys(
    private val context: Context,
    terminalView: TerminalView,
    private val terminalSession: TerminalSession
) : TerminalExtraKeys(terminalView) {

    override fun onTerminalExtraKeyButtonClick(
        view: View,
        key: String,
        ctrlDown: Boolean,
        altDown: Boolean,
        shiftDown: Boolean,
        fnDown: Boolean
    ) {
        when (key) {
            "KEYBOARD" -> {
                KeyboardUtils.toggleSoftKeyboard(context)
            }
            "PASTE" -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = clipboard?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    if (text.isNotEmpty()) {
                        terminalSession.write(text)
                    }
                }
            }
            else -> {
                super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown)
            }
        }
    }
}
