package com.termux.app.rustdesk

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/**
 * 会话输入契约与视图变换。
 *
 * 核心 `session_send_mouse` 把消息反序列化成 `HashMap<String, String>`：所有值必须是 JSON
 * 字符串，坐标写数字会让整条消息解析失败并被静默丢弃（上游 `input_model.dart` 同样是
 * `'$x'` / `'$y'`）。修饰键只要键存在就生效，值任意。
 */
internal fun mouseMessage(
    type: String? = null,
    buttons: String? = null,
    x: Int = 0,
    y: Int = 0,
    alt: Boolean = false,
    ctrl: Boolean = false,
    shift: Boolean = false,
    command: Boolean = false,
): String = buildList {
    if (type != null) add("\"type\":\"$type\"")
    if (buttons != null) add("\"buttons\":\"$buttons\"")
    add("\"x\":\"$x\"")
    add("\"y\":\"$y\"")
    if (alt) add("\"alt\":\"true\"")
    if (ctrl) add("\"ctrl\":\"true\"")
    if (shift) add("\"shift\":\"true\"")
    if (command) add("\"command\":\"true\"")
}.joinToString(prefix = "{", postfix = "}", separator = ",")

/** 帧在视图中的落点：fit 基准缩放 × 用户缩放，再叠加平移。 */
internal data class FrameLayout(
    val scale: Float,
    val offset: IntOffset,
    val size: IntSize,
) {
    /** 视图坐标 → 远端帧坐标；越界钳到帧内，避免把点击丢到无效位置。 */
    fun toRemote(x: Float, y: Float, frame: IntSize): Pair<Int, Int> = Pair(
        ((x - offset.x) / scale).toInt().coerceIn(0, (frame.width - 1).coerceAtLeast(0)),
        ((y - offset.y) / scale).toInt().coerceIn(0, (frame.height - 1).coerceAtLeast(0)),
    )
}

internal fun frameLayout(frame: IntSize, view: IntSize, zoom: Float, pan: Offset): FrameLayout {
    if (frame == IntSize.Zero || view == IntSize.Zero) {
        return FrameLayout(1f, IntOffset.Zero, IntSize.Zero)
    }
    val fit = min(view.width.toFloat() / frame.width, view.height.toFloat() / frame.height)
    val scale = fit * zoom
    val drawW = frame.width * scale
    val drawH = frame.height * scale
    return FrameLayout(
        scale = scale,
        offset = IntOffset(
            ((view.width - drawW) / 2f + pan.x).toInt(),
            ((view.height - drawH) / 2f + pan.y).toInt(),
        ),
        size = IntSize(drawW.toInt().coerceAtLeast(1), drawH.toInt().coerceAtLeast(1)),
    )
}

/** 画面放大后允许平移，但不让远端画面边缘漂进视口内侧造成大片黑边。 */
internal fun clampPan(frame: IntSize, view: IntSize, zoom: Float, pan: Offset): Offset {
    if (frame == IntSize.Zero || view == IntSize.Zero) return Offset.Zero
    val fit = min(view.width.toFloat() / frame.width, view.height.toFloat() / frame.height)
    val scale = fit * zoom
    val maxX = ((frame.width * scale - view.width) / 2f).coerceAtLeast(0f)
    val maxY = ((frame.height * scale - view.height) / 2f).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

/** 工具栏一键发送的按键；键名必须命中核心 `client.rs::KEY_MAP`，单字符走 Key::_Raw。 */
internal data class QuickKey(
    val label: String,
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val command: Boolean = false,
)

internal val QUICK_KEYS = listOf(
    QuickKey("Esc", "VK_ESCAPE"),
    QuickKey("Tab", "VK_TAB"),
    QuickKey("↵", "VK_RETURN"),
    QuickKey("⌫", "VK_BACK"),
    QuickKey("Del", "VK_DELETE"),
    QuickKey("Ins", "VK_INSERT"),
    QuickKey("Home", "VK_HOME"),
    QuickKey("End", "VK_END"),
    QuickKey("PgUp", "VK_PRIOR"),
    QuickKey("PgDn", "VK_NEXT"),
    QuickKey("←", "VK_LEFT"),
    QuickKey("↑", "VK_UP"),
    QuickKey("↓", "VK_DOWN"),
    QuickKey("→", "VK_RIGHT"),
    QuickKey("Ctrl+C", "VK_C", ctrl = true),
    QuickKey("Ctrl+V", "VK_V", ctrl = true),
    QuickKey("Ctrl+X", "VK_X", ctrl = true),
    QuickKey("Ctrl+A", "VK_A", ctrl = true),
    QuickKey("Ctrl+S", "VK_S", ctrl = true),
    QuickKey("Ctrl+Z", "VK_Z", ctrl = true),
    QuickKey("Alt+Tab", "VK_TAB", alt = true),
    QuickKey("Alt+F4", "VK_F4", alt = true),
    QuickKey("Ctrl+Alt+Del", "VK_DELETE", ctrl = true, alt = true),
    QuickKey("Win+D", "VK_D", command = true),
    QuickKey("Win+E", "VK_E", command = true),
    QuickKey("F1", "VK_F1"),
    QuickKey("F5", "VK_F5"),
    QuickKey("F11", "VK_F11"),
)
