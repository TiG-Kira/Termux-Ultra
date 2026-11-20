package com.termux.app.terminal.shell

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.awkoo.libterminal.color.TerminalColorScheme
import com.awkoo.libterminal.engine.TerminalSession
import com.awkoo.libterminal.engine.TerminalCursorStyle
import com.awkoo.libterminal.view.ExtraKeysModifierSnapshot
import com.awkoo.libterminal.view.TerminalView as LibTerminalView
import com.termux.R
import com.termux.app.terminal.sixel.SixelImageBus

/**
 * 终端渲染屏幕。
 *
 * 使用 AndroidView 包装 libterminal 的 TerminalView，
 * 绑定 TerminalSession，实现终端显示与交互。
 * 顶部还承载 sixel 图像浮层（见 [SixelImageBus]）：有新图时压在终端上，
 * 点按关闭。
 */
@Composable
fun ComposeTerminalScreen(
    session: TerminalSession?,
    modifier: Modifier = Modifier,
    terminalViewRef: MutableState<LibTerminalView?>,
    useLightTheme: Boolean = false,
    textSize: Int = 14,
    cursorBlink: Boolean = true,
    cursorStyle: TerminalCursorStyle = TerminalCursorStyle.BAR,
    textBlinking: Boolean = true,
    colorScheme: TerminalColorScheme? = null,
    typeface: android.graphics.Typeface? = null,
    // 工具栏锁定的 CTRL/ALT 只有通过这个读针才能作用于输入法输入：libterminal 在
    // InputConnection 的 inputCodePoint 中读取它，把 ctrl/alt 并入本次输入的修饰态。
    // 刻意不给默认值——漏传就等于静默失去该能力，让调用方必须显式表态。
    extraKeysModifierReader: () -> ExtraKeysModifierSnapshot
) {
    var terminalView by remember { mutableStateOf<LibTerminalView?>(null) }
    var lastSessionId by remember { mutableStateOf<Int?>(null) }

    // sixel 预览（Issue #401）：扫描器在 PTY 读线程上解码投递，这里只消费。
    val sixelImage by SixelImageBus.imageFor(session?.id).collectAsState()

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        val resolvedScheme = colorScheme
            ?: if (useLightTheme) TerminalColorScheme.light() else TerminalColorScheme.dark()

        AndroidView(
            factory = { ctx ->
                KeyLoggingContainer(ctx).apply {
                    val tv = LibTerminalView(ctx).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        defaultFocusHighlightEnabled = false
                        this.textSize = textSize
                        this.typeface = typeface ?: android.graphics.Typeface.MONOSPACE
                        this.colorScheme = resolvedScheme
                        this.cursorBlinking = cursorBlink
                        this.cursorStyle = cursorStyle
                        this.textBlinking = textBlinking
                        this.extraKeysModifierReader = extraKeysModifierReader
                    }
                    addView(
                        tv,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                    terminalView = tv
                    terminalViewRef.value = tv
                }
            },
            update = { container ->
                val tv = container.getChildAt(0) as LibTerminalView
                tv.textSize = textSize
                tv.typeface = typeface ?: android.graphics.Typeface.MONOSPACE
                tv.colorScheme = resolvedScheme
                tv.cursorBlinking = cursorBlink
                tv.cursorStyle = cursorStyle
                tv.textBlinking = textBlinking
                tv.extraKeysModifierReader = extraKeysModifierReader
                if (session != null && lastSessionId != session.id) {
                    tv.currentSession = session
                    lastSessionId = session.id
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // sixel 图像浮层：压在终端视图之上，点按关闭；只挡图像自身的点击区域。
        sixelImage?.let { image ->
            Image(
                bitmap = image.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.sixel_image_preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(resolvedScheme.background))
                    .border(
                        1.dp,
                        Color(resolvedScheme.foreground).copy(alpha = 0.3f),
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { SixelImageBus.dismiss(image.sessionId) }
                    .padding(6.dp),
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            terminalView?.dispose()
            terminalView = null
            terminalViewRef.value = null
        }
    }
}

/**
 * 硬件按键日志容器：libterminal 的 TerminalView 是 final 类且无输入回调接口，
 * 只能在外层容器 dispatchKeyEvent 处截获流向终端的硬件按键事件。
 * IME 软输入走 InputConnection，不经过 View 按键分发，无法在此记录。
 */
private class KeyLoggingContainer(context: Context) : FrameLayout(context) {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ComposeTerminalSettings.keyLogging.value) {
            Log.d(
                "TerminalKeyLogging",
                "action=${event.action} keyCode=${event.keyCode} " +
                    "repeat=${event.repeatCount} meta=0x${event.metaState.toString(16)}"
            )
        }
        val remapped = remapUnsupportedKeyCodes(event)
        return super.dispatchKeyEvent(remapped)
    }

    /**
     * 把部分键盘/输入法发出的 keyCode 归一到 libterminal 认识的编码。
     *
     * 引擎 KeySequenceEncoder.getCode 的分支表里有 KEYCODE_MOVE_HOME(122) /
     * KEYCODE_MOVE_END(123)，但没有 KEYCODE_HOME(3)：Home 键在 Android 上属于系统键，
     * 通常被系统截走不派发给应用，但部分软键盘与外接键盘仍会发 3，此时
     * getCode 返回 null、getUnicodeChar 也返回 0，按键被静默丢弃。这里在到达引擎前
     * 改写成 MOVE_HOME，与经典 KeyHandler 对两者不做区分的行为保持一致。
     */
    private fun remapUnsupportedKeyCodes(event: KeyEvent): KeyEvent {
        val replacement = when (event.keyCode) {
            KeyEvent.KEYCODE_HOME -> KeyEvent.KEYCODE_MOVE_HOME
            else -> return event
        }
        // 8 参数构造器末位是 flags，传 0：带 FLAG_IS_SYSTEM 的话引擎的
        // KeyInputProcessor 会直接 PASS_TO_SUPER，按键根本不会被编码。
        return KeyEvent(
            event.downTime, event.eventTime, event.action, replacement,
            event.repeatCount, event.metaState, event.deviceId, 0
        )
    }
}
