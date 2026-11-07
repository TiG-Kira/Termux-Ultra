package com.termux.app.rustdesk

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.termux.app.compose.KiTerminalTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import java.nio.ByteBuffer
import java.util.UUID
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
 * 输入约定（对应核心 `session_send_mouse` 的 JSON 契约）：
 *  - 单指点击 = 左键、双指点击 = 右键、单指拖动 = 移动光标；
 *  - 双指拖动 = 滚轮（移动端 wheel 的 x/y 即 delta，`platform/mobile_wheel.rs` 只做反向开关）；
 *  - 文字经软键盘逐字符走 `nativeInputKey`（单字符名 → 核心 Key::_Raw），回车/退格映射
 *    VK_RETURN/VK_BACK；修饰键（Ctrl/Alt/Shift）一次性锁存，随下一个字符下发后清零。
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

        val addError = RustDeskCore.nativeSessionAdd(sessionId, peerId, password, forceRelay, "", "")
        if (!addError.isNullOrEmpty()) {
            android.util.Log.w("RustDeskSession", "session_add 失败：$addError")
            finish()
            return
        }
        if (!RustDeskCore.nativeSessionStart(sessionId, peerId)) {
            finish()
            return
        }

        setContent {
            KiTerminalTheme {
                RustDeskSessionScreen(
                    sessionId = sessionId,
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
private fun RustDeskSessionScreen(sessionId: String, title: String, onClose: () -> Unit) {
    val event by RustDeskCore.events.collectAsState()
    var status by remember { mutableStateOf("正在连接…") }
    // 复用的帧字节缓冲：分辨率变化时才重新分配
    var frameBuf by remember { mutableStateOf(ByteArray(0)) }
    // 每帧换一个 ImageBitmap 包装（不拷贝像素），靠新实例触发重绘
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    // 帧与视图尺寸用 State 承载：手势回调里读 .value，避免闭包捕获旧值
    val frameDims = remember { mutableStateOf(IntSize.Zero) }
    val viewSize = remember { mutableStateOf(IntSize.Zero) }

    // 一次性修饰键锁存与软键盘
    var ctrlLock by remember { mutableStateOf(false) }
    var altLock by remember { mutableStateOf(false) }
    var shiftLock by remember { mutableStateOf(false) }
    var keyboardOn by remember { mutableStateOf(false) }
    var pendingText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(event) {
        val e = event ?: return@LaunchedEffect
        // 全局事件 sessionId 为空；会话事件只收本会话的
        if (e.sessionId.isNotEmpty() && e.sessionId != sessionId) return@LaunchedEffect
        when (e.kind) {
            RustDeskCore.EventKind.EVENT, RustDeskCore.EventKind.GLOBAL -> {
                val name = RustDeskCore.nameOf(e.payload)
                when (name) {
                    "close" -> onClose()
                    "connected" -> status = "已连接"
                    else -> if (name.isNotEmpty()) status = name
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
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frameBuf))
                        frame = bitmap.asImageBitmap()
                        frameDims.value = IntSize(width, height)
                        status = "已连接"
                    }
                }
                // 交还缓冲，核心才会产出下一帧
                RustDeskCore.nativeNextRgba(sessionId, display)
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

    fun sendMouse(json: String) {
        // 没出首帧时不发输入，避免对未知分辨率发送坐标
        if (frameDims.value != IntSize.Zero) RustDeskCore.nativeSendMouse(sessionId, json)
    }

    /** 视图坐标 → 远端帧坐标：等比缩放 + 居中黑边的反变换。 */
    fun toRemote(x: Float, y: Float): IntArray {
        val fd = frameDims.value
        val vs = viewSize.value
        if (fd == IntSize.Zero || vs == IntSize.Zero) return intArrayOf(0, 0)
        val scale = min(vs.width.toFloat() / fd.width, vs.height.toFloat() / fd.height)
        val drawW = fd.width * scale
        val drawH = fd.height * scale
        val offX = (vs.width - drawW) / 2f
        val offY = (vs.height - drawH) / 2f
        val rx = ((x - offX) / scale).toInt().coerceIn(0, fd.width - 1)
        val ry = ((y - offY) / scale).toInt().coerceIn(0, fd.height - 1)
        return intArrayOf(rx, ry)
    }

    fun clickAt(x: Float, y: Float, button: String) {
        val (rx, ry) = toRemote(x, y)
        sendMouse("{\"type\":\"down\",\"buttons\":\"$button\",\"x\":$rx,\"y\":$ry}")
        sendMouse("{\"type\":\"up\",\"buttons\":\"$button\",\"x\":$rx,\"y\":$ry}")
    }

    fun sendKey(name: String, shift: Boolean, ctrl: Boolean, alt: Boolean) {
        if (frameDims.value == IntSize.Zero) return
        RustDeskCore.nativeInputKey(sessionId, name, false, true, alt, ctrl, shift, false)
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
                            var multi = false
                            var moved = false
                            while (true) {
                                val e = awaitPointerEvent()
                                val pressed = e.changes.filter { it.pressed }
                                if (pressed.size >= 2) multi = true
                                if (multi) {
                                    // 双指：位移原样转滚轮 delta（方向待真机校准）
                                    var dx = 0f
                                    var dy = 0f
                                    for (c in e.changes) {
                                        if (c.pressed) {
                                            dx += c.positionChange().x
                                            dy += c.positionChange().y
                                            c.consume()
                                        }
                                    }
                                    if (dx.toInt() != 0 || dy.toInt() != 0) {
                                        sendMouse("{\"type\":\"wheel\",\"buttons\":\"wheel\",\"x\":${dx.toInt()},\"y\":${dy.toInt()}}")
                                    }
                                } else {
                                    val c = pressed.firstOrNull()
                                    if (c != null) {
                                        if (c.positionChange() != Offset.Zero) {
                                            moved = true
                                            val (rx, ry) = toRemote(c.position.x, c.position.y)
                                            sendMouse("{\"type\":\"move\",\"x\":$rx,\"y\":$ry}")
                                        }
                                        c.consume()
                                    }
                                }
                                if (pressed.isEmpty()) break
                            }
                            // 抬手判定：未移动的单指/双指点击
                            if (!moved) {
                                val button = if (multi) "right" else "left"
                                clickAt(first.position.x, first.position.y, button)
                            }
                        }
                    }
            ) {
                val fd = frameDims.value
                if (fd != IntSize.Zero) {
                    val scale = min(size.width.toFloat() / fd.width, size.height.toFloat() / fd.height)
                    val drawW = (fd.width * scale).toInt()
                    val drawH = (fd.height * scale).toInt()
                    val offX = ((size.width - drawW) / 2f).toInt()
                    val offY = ((size.height - drawH) / 2f).toInt()
                    drawImage(
                        image = current,
                        dstOffset = IntOffset(offX, offY),
                        dstSize = IntSize(drawW, drawH),
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
                Text(text = status, color = Color.White.copy(alpha = 0.7f))
                // miuix 的 CircularProgressIndicator 默认 30dp，尺寸必须显式走 size
                CircularProgressIndicator(modifier = Modifier.size(30.dp))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text = title, color = Color.White, modifier = Modifier.weight(1f))
            if (current != null) {
                TextButton(
                    text = if (ctrlLock) "Ctrl*" else "Ctrl",
                    onClick = { ctrlLock = !ctrlLock },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = if (altLock) "Alt*" else "Alt",
                    onClick = { altLock = !altLock },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = if (shiftLock) "Shift*" else "Shift",
                    onClick = { shiftLock = !shiftLock },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = if (keyboardOn) "键盘✓" else "键盘",
                    onClick = { keyboardOn = !keyboardOn },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            TextButton(
                text = "断开",
                onClick = onClose,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
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
    }
}
