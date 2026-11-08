package com.termux.app.rustdesk

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 软渲染帧 → Bitmap。
 *
 * 核心在 Android 下用 `VideoHandler` 的 `ImageFormat::ARGB`（libyuv `I420ToARGB`），
 * 产出紧密排列的 `B,G,R,A` 字节流；上游 Flutter 在 Android 也用
 * `PixelFormat.bgra8888` 解同一份数据。
 *
 * 不再整段 `copyPixelsFromBuffer`：那依赖 `ARGB_8888` 的隐式内存字节序，且原样带入
 * alpha 字节，预乘位图遇到非不透明的 A 会整片发暗、偏色。这里显式组装
 * `0xAARRGGBB`，alpha 恒为 255；[swapRb] 供不同服务端/解码路径一键校准红蓝。
 */
internal fun frameToBitmap(
    pixels: ByteArray,
    width: Int,
    height: Int,
    swapRb: Boolean,
): ImageBitmap {
    val size = width * height * 4
    require(pixels.size >= size) { "frame buffer too small: ${pixels.size} < $size" }
    val argb = IntArray(width * height)
    var i = 0
    var j = 0
    while (i < size) {
        val p0 = pixels[i].toInt() and 0xFF
        val g = pixels[i + 1].toInt() and 0xFF
        val p2 = pixels[i + 2].toInt() and 0xFF
        // 默认输入 B,G,R,A；开启交换后按 R,G,B,A 解释
        val r = if (swapRb) p0 else p2
        val b = if (swapRb) p2 else p0
        argb[j] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        i += 4
        j++
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(argb, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}
