package com.termux.app.rustdesk

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.compose.KiTerminalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/**
 * 内嵌 RustDesk 会话页（纯 Compose / miuix，不继承任何上游 Activity）。
 *
 * 与深链兜底方案的区别：建连、认证、帧事件全部经 [RustDeskCore] 的 JNI 桥接到 Rust 核心，
 * 不拉起外部客户端、不经 RustDesk 主页。核心未加载（该 ABI 无 librustdesk.so）时直接退出。
 *
 * 身份边界：本页只把连接记录里的密码透传给核心；RustDesk 账号/OIDC 登录与会话认证的
 * 请求身份（id/uuid/deviceInfo）全部由核心生成，本页不参与、不代发 HTTP。
 *
 * 输入约定（对应核心 `session_send_mouse` 的 JSON 契约，所有值必须是字符串）：
 *  - 单指点击 = 左键、长按拖动 = 左键拖放、双指点击 = 右键；
 *  - 单指轻扫 = 移动光标、双指捏合 = 缩放、放大后双指拖动 = 平移画面、未放大时 = 滚轮；
 *  - 文字经软键盘逐字符走 `nativeInputKey`（单字符名 → 核心 Key::_Raw），功能键走 KEY_MAP；
 *  - 修饰键（Ctrl/Alt/Shift）一次性锁存，随下一个字符下发后清零。
 */
class RustDeskSessionActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_NAME = "rd_name"
        private const val EXTRA_REMOTE_ID = "rd_remote_id"
        private const val EXTRA_PASSWORD = "rd_password"
        private const val EXTRA_SERVER = "rd_server"
        private const val EXTRA_KEY = "rd_key"
        private const val EXTRA_FORCE_RELAY = "rd_force_relay"

        /** 由 [RustDeskClient] 调用；密码只在 Intent 里短暂传递，不落日志。 */
        fun intent(context: Context, c: RustDeskConnection): Intent =
            Intent(context, RustDeskSessionActivity::class.java).apply {
                putExtra(EXTRA_NAME, c.name)
                putExtra(EXTRA_REMOTE_ID, c.remoteId)
                putExtra(EXTRA_PASSWORD, c.password)
                putExtra(EXTRA_SERVER, c.server)
                putExtra(EXTRA_KEY, c.key)
                putExtra(EXTRA_FORCE_RELAY, c.forceRelay)
            }
    }

    private var sessionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!RustDeskCore.isAvailable()) {
            // 该 ABI 没有原生库，不进会话页去踩 UnsatisfiedLinkError
            finish()
            return
        }

        val remoteId = intent.getStringExtra(EXTRA_REMOTE_ID).orEmpty()
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        val server = intent.getStringExtra(EXTRA_SERVER).orEmpty()
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val forceRelay = intent.getBooleanExtra(EXTRA_FORCE_RELAY, false)

        RustDeskCore.attach(this)
        // 自建服务器 / 公钥配置只做本地选项透传，实际连接与身份由核心处理
        if (server.isNotEmpty()) RustDeskCore.nativeSetLocalOption("custom-rendezvous-server", server)
        if (key.isNotEmpty()) RustDeskCore.nativeSetLocalOption("key", key)

        sessionId = UUID.randomUUID().toString()
        val peerId = buildString {
            append(remoteId.trim())
            if (forceRelay) append("/r")
            if (server.isNotEmpty()) append("@").append(server.trim())
        }

        setContent {
            KiTerminalTheme {
                RustDeskSessionScreen(
                    sessionId = sessionId,
                    peerId = peerId,
                    password = password,
                    forceRelay = forceRelay,
                    title = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { remoteId },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        if (sessionId.isNotEmpty()) {
            runCatching { RustDeskCore.nativeSessionClose(sessionId) }
        }
        super.onDestroy()
    }
}

@Composable
private fun RustDeskSessionScreen(
    sessionId: String,
    peerId: String,
    password: String,
    forceRelay: Boolean,
    title: String,
    onClose: () -> Unit,
) {
    val connectedText = stringResource(R.string.rustdesk_session_connected)
    val connectingText = stringResource(R.string.rustdesk_session_connecting)
    var status by remember { mutableStateOf("") }
    var frameBuf by remember { mutableStateOf(ByteArray(0)) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    // 帧与视图信息用 State 容器承载：手势回调长生命周期，直接捕获值会读到旧状态
    val frameDims = remember { mutableStateOf(IntSize.Zero) }
    val viewSize = remember { mutableStateOf(IntSize.Zero) }
    val zoomState = remember { mutableStateOf(1f) }
    val panState = remember { mutableStateOf(Offset.Zero) }
    val rightMode = remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<SessionDialog?>(null) }
    var deferredDialog by remember { mutableStateOf<SessionDialog?>(null) }

    var ctrlLock by remember { mutableStateOf(false) }
    var altLock by remember { mutableStateOf(false) }
    var shiftLock by remember { mutableStateOf(false) }
    var keyboardOn by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    var pendingText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val gestureScope = rememberCoroutineScope()

    // onSubscription 保证订阅建立后才建连，建连期间的核心事件不会再丢
    LaunchedEffect(Unit) {
        RustDeskCore.events.onSubscription {
            launch {
                val addError = withContext(Dispatchers.IO) {
                    RustDeskCore.nativeSessionAdd(sessionId, peerId, password, forceRelay, "", "")
                }
                if (!addError.isNullOrEmpty()) {
                    dialog = SessionDialog.Message(
                        RustDeskCore.EventPayload(type = "error", text = addError)
                    )
                    return@launch
                }
                val started = withContext(Dispatchers.IO) {
                    RustDeskCore.nativeSessionStart(sessionId, peerId)
                }
                if (!started) {
                    dialog = SessionDialog.Message(
                        RustDeskCore.EventPayload(type = "error", text = "session_start failed")
                    )
                }
            }
        }.collect { e ->
            // 全局事件 sessionId 为空；会话事件只收本会话的
            if (e.sessionId.isNotEmpty() && e.sessionId != sessionId) return@collect
            when (e.kind) {
                RustDeskCore.EventKind.EVENT, RustDeskCore.EventKind.GLOBAL -> {
                    val p = RustDeskCore.parseEvent(e.payload)
                    when (p?.name ?: e.payload) {
                        "close" -> onClose()
                        "connected" -> status = connectedText
                        "msgbox" -> if (p != null) {
                            deferredDialog = null
                            dialog = dialogFor(p)
                            status = p.title.ifBlank { p.text.ifBlank { p.type } }
                        }
                        "cancel_msgbox" -> if (dialog is SessionDialog.Message) dialog = null
                        "toast" -> status = p?.text.orEmpty()
                        else -> status = p?.title?.takeIf { it.isNotEmpty() }
                            ?: p?.text?.takeIf { it.isNotEmpty() }
                            ?: e.payload.take(100)
                    }
                }
                RustDeskCore.EventKind.RGBA -> {
                    val display = e.payload.toIntOrNull() ?: 0
                    val dims = RustDeskCore.nativeGetRgbaDims(sessionId, display)
                    val width = (dims ushr 32).toInt()
                    val height = (dims and 0xFFFF_FFFFL).toInt()
                    val need = width * height * 4
                    if (need > 0) {
                        if (frameBuf.size != need) frameBuf = ByteArray(need)
                        // 软渲染输出紧密排列的 RGBA，与 ARGB_8888 的内存字节序一致
                        if (RustDeskCore.nativeCopyRgba(sessionId, display, frameBuf) == need) {
                            // 整屏分配与拷贝移出主线程，高帧率下才不会堵塞绘制
                            val pixels = frameBuf
                            val image = withContext(Dispatchers.Default) {
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                                bitmap.asImageBitmap()
                            }
                            frame = image
                            frameDims.value = IntSize(width, height)
                            status = connectedText
                        }
                    }
                    // 交还缓冲，核心才会产出下一帧
                    RustDeskCore.nativeNextRgba(sessionId, display)
                }
            }
        }
    }

    LaunchedEffect(keyboardOn) {
        if (keyboardOn) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    fun layout(): FrameLayout =
        frameLayout(frameDims.value, viewSize.value, zoomState.value, panState.value)

    fun sendMouse(msg: String) {
        // 没出首帧时不发输入，避免对未知分辨率发送坐标
        if (frameDims.value != IntSize.Zero) RustDeskCore.nativeSendMouse(sessionId, msg)
    }

    fun remoteAt(x: Float, y: Float): Pair<Int, Int> =
        layout().toRemote(x, y, frameDims.value)

    fun mouseAt(type: String?, buttons: String?, x: Float, y: Float) {
        val (rx, ry) = remoteAt(x, y)
        sendMouse(mouseMessage(type = type, buttons = buttons, x = rx, y = ry))
    }

    fun tap(x: Float, y: Float, button: String) {
        mouseAt("down", button, x, y)
        mouseAt("up", button, x, y)
    }

    fun setZoom(next: Float) {
        zoomState.value = next.coerceIn(0.5f, 5f)
        panState.value = clampPan(frameDims.value, viewSize.value, zoomState.value, panState.value)
    }

    fun originalSize() {
        val fd = frameDims.value
        val vs = viewSize.value
        if (fd == IntSize.Zero || vs == IntSize.Zero) return
        val fit = min(vs.width.toFloat() / fd.width, vs.height.toFloat() / fd.height)
        if (fit > 0f) setZoom(1f / fit)
    }

    fun sendKey(name: String, shift: Boolean, ctrl: Boolean, alt: Boolean, command: Boolean = false) {
        if (frameDims.value == IntSize.Zero) return
        RustDeskCore.nativeInputKey(sessionId, name, false, true, alt, ctrl, shift, command)
    }

    /** 一次性修饰键：取出当前锁存值并清零。Triple(shift, ctrl, alt)。 */
    fun consumeModifiers(): Triple<Boolean, Boolean, Boolean> {
        val m = Triple(shiftLock, ctrlLock, altLock)
        shiftLock = false
        ctrlLock = false
        altLock = false
        return m
    }

    fun sendChar(ch: Char) {
        val (shift0, ctrl0, alt0) = consumeModifiers()
        when (ch) {
            '\n', '\r' -> sendKey("VK_RETURN", shift0, ctrl0, alt0)
            '\b' -> sendKey("VK_BACK", shift0, ctrl0, alt0)
            else -> {
                // 大写字母借 shift 修饰下发，字符名一律用小写形式
                val upper = ch.isUpperCase()
                sendKey(ch.lowercaseChar().toString(), shift0 || upper, ctrl0, alt0)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val current = frame
        if (current != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize.value = it }
                    .pointerInput(sessionId) {
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            val downPos = first.position
                            var lastPos = downPos
                            var moved = false
                            var multi = false
                            var pinchOrPan = false
                            var dragging = false
                            var dragDistance = 0f
                            var prevCentroid = downPos
                            var prevSpread = 0f
                            val wheel = WheelAccumulator()

                            // 长按进入拖放：按下左键，随后的移动就是拖动而不是单纯移动光标
                            val longPressJob: Job = gestureScope.launch {
                                delay(500)
                                if (!multi && !moved) {
                                    dragging = true
                                    val (rx, ry) = remoteAt(downPos.x, downPos.y)
                                    sendMouse(mouseMessage(type = "down", buttons = "left", x = rx, y = ry))
                                }
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    if (!multi) {
                                        longPressJob.cancel()
                                        multi = true
                                    }
                                    val p1 = pressed[0].position
                                    val p2 = pressed[1].position
                                    val centroid = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                                    val spread = (p1 - p2).getDistance()
                                    if (prevSpread > 0f) {
                                        val scaleChange = spread / prevSpread
                                        if (abs(scaleChange - 1f) > 0.02f) {
                                            setZoom(zoomState.value * scaleChange)
                                            pinchOrPan = true
                                        }
                                        val delta = centroid - prevCentroid
                                        if (delta.getDistance() > 3f) {
                                            pinchOrPan = true
                                            if (zoomState.value > 1.01f) {
                                                panState.value = clampPan(
                                                    frameDims.value, viewSize.value, zoomState.value,
                                                    panState.value + delta
                                                )
                                            } else {
                                                // 未放大时双指滑动当滚轮；服务端会乘 WHEEL_DELTA，
                                                // 所以按格数下发，不能直接把像素位移当 x/y
                                                val (dx, dy) = wheel.add(delta)
                                                if (dx != 0 || dy != 0) {
                                                    sendMouse(mouseMessage(type = "wheel", x = dx, y = dy))
                                                }
                                            }
                                        }
                                    }
                                    prevCentroid = centroid
                                    prevSpread = spread
                                    pressed.forEach { it.consume() }
                                    continue
                                }

                                val c = pressed.firstOrNull() ?: break
                                val change = c.positionChange()
                                dragDistance += change.getDistance()
                                if (dragDistance > 8f) moved = true
                                if (change != Offset.Zero) {
                                    lastPos = c.position
                                    val (rx, ry) = remoteAt(c.position.x, c.position.y)
                                    sendMouse(mouseMessage(x = rx, y = ry))
                                }
                                c.consume()
                            }
                            longPressJob.cancel()

                            when {
                                dragging -> {
                                    val (rx, ry) = remoteAt(lastPos.x, lastPos.y)
                                    sendMouse(mouseMessage(type = "up", buttons = "left", x = rx, y = ry))
                                }
                                multi -> {
                                    // 双指轻点 = 右键；真正捏合/滑动过的不再补发右键
                                    if (!pinchOrPan) tap(downPos.x, downPos.y, "right")
                                }
                                !moved -> {
                                    val button = if (rightMode.value) "right" else "left"
                                    rightMode.value = false
                                    tap(downPos.x, downPos.y, button)
                                }
                            }
                        }
                    }
            ) {
                val fd = frameDims.value
                if (fd != IntSize.Zero) {
                    val l = frameLayout(
                        fd,
                        IntSize(size.width.toInt(), size.height.toInt()),
                        zoomState.value,
                        panState.value
                    )
                    drawImage(
                        image = current,
                        dstOffset = l.offset,
                        dstSize = l.size,
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            ) {
                Text(text = title, color = Color.White)
                Text(
                    text = status.ifBlank { connectingText },
                    color = Color.White.copy(alpha = 0.7f)
                )
                // miuix 的 CircularProgressIndicator 默认 30dp，尺寸必须显式走 size
                CircularProgressIndicator(modifier = Modifier.size(30.dp))
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable {}
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        R.string.rustdesk_session_zoom_label,
                        (zoomState.value * 100).toInt()
                    ),
                    color = Color.White.copy(alpha = 0.75f)
                )
                if (deferredDialog != null) {
                    TextButton(
                        text = stringResource(R.string.rustdesk_session_resume_credential),
                        onClick = {
                            dialog = deferredDialog
                            deferredDialog = null
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                TextButton(
                    text = stringResource(R.string.rustdesk_session_disconnect),
                    onClick = onClose,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                TextButton(
                    text = "−",
                    onClick = { setZoom(zoomState.value / 1.25f) },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = "+",
                    onClick = { setZoom(zoomState.value * 1.25f) },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(R.string.rustdesk_session_zoom_fit),
                    onClick = {
                        zoomState.value = 1f
                        panState.value = Offset.Zero
                    }
                )
                TextButton(
                    text = stringResource(R.string.rustdesk_session_zoom_original),
                    onClick = { originalSize() }
                )
                TextButton(
                    text = if (rightMode.value) {
                        stringResource(R.string.rustdesk_session_right_on)
                    } else {
                        stringResource(R.string.rustdesk_session_right)
                    },
                    onClick = { rightMode.value = !rightMode.value }
                )
                TextButton(
                    text = if (ctrlLock) "Ctrl*" else "Ctrl",
                    onClick = { ctrlLock = !ctrlLock }
                )
                TextButton(
                    text = if (altLock) "Alt*" else "Alt",
                    onClick = { altLock = !altLock }
                )
                TextButton(
                    text = if (shiftLock) "Shift*" else "Shift",
                    onClick = { shiftLock = !shiftLock }
                )
                TextButton(
                    text = if (keyboardOn) {
                        stringResource(R.string.rustdesk_session_keyboard_on)
                    } else {
                        stringResource(R.string.rustdesk_session_keyboard)
                    },
                    onClick = { keyboardOn = !keyboardOn }
                )
                TextButton(
                    text = stringResource(R.string.rustdesk_session_more_keys),
                    onClick = { showKeys = !showKeys }
                )
            }
        }

        if (showKeys) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)
            ) {
                QUICK_KEYS.forEach { key ->
                    TextButton(
                        text = key.label,
                        onClick = { sendKey(key.key, key.shift, key.ctrl, key.alt, key.command) }
                    )
                }
            }
        }

        if (keyboardOn) {
            // 1dp 隐形输入框：只为拉起软键盘并把字符逐个转发给远端
            BasicTextField(
                value = pendingText,
                onValueChange = { new ->
                    if (new.length > pendingText.length) {
                        new.substring(pendingText.length).forEach(::sendChar)
                    } else if (new.length < pendingText.length) {
                        val (_, ctrl0, alt0) = consumeModifiers()
                        repeat(pendingText.length - new.length) {
                            sendKey("VK_BACK", false, ctrl0, alt0)
                        }
                    }
                    pendingText = new
                },
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(focusRequester)
                    .focusable(),
            )
        }

        RustDeskSessionDialogHost(
            dialog = dialog,
            sessionId = sessionId,
            onDismiss = {
                if (dialog?.isCredential == true) deferredDialog = null
                dialog = null
            },
            onDefer = {
                deferredDialog = dialog
                dialog = null
            }
        )
    }
}
