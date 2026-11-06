package com.termux.app.rustdesk

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.termux.app.compose.KiTerminalTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import java.nio.ByteBuffer
import java.util.UUID

/**
 * 内嵌 RustDesk 会话页（纯 Compose / miuix，不继承任何上游 Activity）。
 *
 * 与深链兜底方案的区别：建连、认证、帧事件全部经 [RustDeskCore] 的 JNI 桥接到 Rust 核心，
 * 不拉起外部客户端、不经 RustDesk 主页。核心未加载（该 ABI 无 librustdesk.so）时直接退出。
 *
 * 身份边界：本页只把连接记录里的密码透传给核心；RustDesk 账号/OIDC 登录与会话认证的
 * 请求身份（id/uuid/deviceInfo）全部由核心生成，本页不参与、不代发 HTTP。
 *
 * 当前进度：建连、核心事件分发、画面渲染、断开已接通。
 * 帧链路为「核心软渲染 → 持锁拷进复用字节数组 → ARGB_8888 位图 → Canvas 绘制」，
 * 手势/键盘输入（`nativeSendMouse` / `nativeInputKey`）尚未接入。
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
                        status = "已连接"
                    }
                }
                // 交还缓冲，核心才会产出下一帧
                RustDeskCore.nativeNextRgba(sessionId, display)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val current = frame
        if (current != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawImage(
                    image = current,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Low,
                )
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
            TextButton(
                text = "断开",
                onClick = onClose,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
