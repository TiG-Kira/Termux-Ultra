// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT
//
// DEC Sixel 图像解码器（Issue #401）。
//
// 输入是一段完整的 sixel DCS 负载（ESC P ... q ... ESC \ 中间的全部字节，
// 含参数头与 'q' 终结符），输出解码后的 ARGB 位图。纯函数、无状态、
// 任何畸形输入一律返回 null，绝不抛异常——调用方在 PTY 读线程上，
// 不能被图像数据搞挂。

package com.termux.app.terminal.sixel

import android.graphics.Bitmap
import android.graphics.Color

object SixelDecoder {

    /** 位图最大边长；超过直接拒绝，防止畸形 raster 属性撑爆内存。 */
    private const val MAX_DIMENSION = 4096

    /** 位图最大总像素（8M px ≈ 32MB ARGB_8888）。 */
    private const val MAX_PIXELS = 8L * 1024 * 1024

    /** 调色板寄存器上限（规范允许任意 n，超过即视为畸形丢弃）。 */
    private const val MAX_PALETTE = 1024

    /**
     * 解码 sixel 负载。
     *
     * @param payload `DCS Pi;Pa;Pb[;Pc] q <sixel data>` 里 ESC P 与 ST 之间的全部字节。
     * @return 解码结果；输入为空、畸形或超限时返回 null。
     */
    fun decode(payload: ByteArray): Bitmap? {
        if (payload.isEmpty()) return null

        // —— 参数头：扫到 'q'，期间收集 raster 属性 P1..P4 ——
        val headerArgs = IntArray(4)
        var headerArgCount = 0
        var argValue = 0
        var hasArg = false
        var index = 0

        fun pushHeaderArg() {
            if (!hasArg) return
            if (headerArgCount < headerArgs.size) headerArgs[headerArgCount] = argValue
            headerArgCount++
            argValue = 0
            hasArg = false
        }

        while (index < payload.size) {
            val b = payload[index].toInt() and 0xFF
            if (b == 'q'.code) break
            when (b) {
                in '0'.code..'9'.code -> {
                    argValue = argValue * 10 + (b - '0'.code)
                    hasArg = true
                }
                else -> pushHeaderArg() // 分号与中间字节都按参数分隔处理
            }
            index++
        }
        pushHeaderArg()
        if (index >= payload.size) return null // 没找到 'q'，不是 sixel
        index++ // 跳过 'q'

        // P3/P4 是 raster 画布宽高，用来预分配；P1/P2（像素纵横比）不据此拉伸，
        // 现代发送方（img2sixel / chafa 等）普遍输出 1:1。
        val rasterW = headerArgs.getOrElse(2) { 0 }.takeIf { it in 1..MAX_DIMENSION } ?: 0
        val rasterH = headerArgs.getOrElse(3) { 0 }.takeIf { it in 1..MAX_DIMENSION } ?: 0

        // —— 画布：按需扩容的像素缓冲，未写入区域为 0（透明） ——
        var pixels = IntArray(0)
        var width = 0
        var height = 0
        var cursorX = 0
        var cursorY = 0

        fun ensureCanvas(minW: Int, minH: Int): Boolean {
            val wantW = maxOf(minW, rasterW)
            val wantH = maxOf(minH, rasterH)
            if (wantW > MAX_DIMENSION || wantH > MAX_DIMENSION) return false
            if (wantW <= width && wantH <= height) return true
            val newW = maxOf(width, wantW).coerceAtLeast(64)
            val newH = maxOf(height, wantH).coerceAtLeast(64)
            if (newW.toLong() * newH > MAX_PIXELS) return false
            val next = IntArray(newW * newH)
            for (row in 0 until height) {
                System.arraycopy(pixels, row * width, next, row * newW, width)
            }
            pixels = next
            width = newW
            height = newH
            return true
        }

        val palette = IntArray(INITIAL_PALETTE_SIZE) { Color.WHITE }
        var paletteSize = INITIAL_PALETTE_SIZE
        var currentColor = palette[0]

        // —— 主体状态机 ——
        var repeatCount = 1
        var collectingRepeat = false
        val args = IntArray(5)
        var argCount = 0

        fun pushArg() {
            if (!hasArg) return
            if (argCount < args.size) args[argCount] = argValue
            argCount++
            argValue = 0
            hasArg = false
        }

        fun resetArgs() {
            argValue = 0
            hasArg = false
            argCount = 0
        }

        while (index < payload.size) {
            val b = payload[index].toInt() and 0xFF
            when (b) {
                in '0'.code..'9'.code -> {
                    argValue = argValue * 10 + (b - '0'.code)
                    hasArg = true
                }
                ';'.code -> pushArg()
                '"'.code -> {
                    // raster attributes："Pa;Pb;Pc;Pd —— 只取宽高做预分配
                    pushArg()
                    if (argCount >= 3) {
                        val w = args[2].takeIf { it in 1..MAX_DIMENSION } ?: 0
                        val h = args.getOrElse(3) { 0 }.takeIf { it in 1..MAX_DIMENSION } ?: 0
                        if (w > width || h > height) {
                            if (!ensureCanvas(w, h)) return null
                        }
                    }
                    resetArgs()
                }
                '#'.code -> {
                    // 颜色：#n（选中寄存器）或 #n;Ps;Px[;Py[;Pz]]（定义寄存器）
                    pushArg()
                    val register = args[0]
                    if (argCount >= 2) {
                        val rgb = when (args[1]) {
                            1 -> if (argCount >= 5) {
                                // HLS：H 0-360，L/S 0-100
                                hslToRgb(
                                    args[2].coerceIn(0, 360),
                                    args[4].coerceIn(0, 100) / 100f,
                                    args[3].coerceIn(0, 100) / 100f,
                                )
                            } else null
                            2 -> if (argCount >= 5) {
                                // RGB：DEC 按 0-100 百分比发送；部分发送方用 0-255，按 >100 归一
                                fun channel(v: Int): Int {
                                    val f = if (v > 100) v / 255f else v / 100f
                                    return (f.coerceIn(0f, 1f) * 255f).toInt()
                                }
                                Color.rgb(channel(args[2]), channel(args[3]), channel(args[4]))
                            } else null
                            else -> null
                        }
                        if (rgb != null && register in 0 until MAX_PALETTE) {
                            if (register >= paletteSize) {
                                val newSize = minOf(maxOf(paletteSize * 2, register + 1), MAX_PALETTE)
                                val grown = palette.copyOf(newSize)
                                java.util.Arrays.fill(grown, paletteSize, newSize, Color.WHITE)
                                paletteSize = newSize
                            }
                            palette[register] = rgb
                        }
                    }
                    currentColor = if (register in 0 until paletteSize) palette[register] else Color.WHITE
                    resetArgs()
                }
                '!'.code -> {
                    // !Pn Ch —— 后续一个 sixel 字节重复 Pn 次
                    collectingRepeat = true
                }
                '$'.code -> cursorX = 0
                '-'.code -> {
                    cursorY += 6
                    if (!ensureCanvas(cursorX.coerceAtLeast(1), cursorY + 6)) return null
                }
                in '?'.code..'~'.code -> {
                    val bits = b - 0x3F
                    val count = if (collectingRepeat) {
                        repeatCount = argValue.coerceAtLeast(1)
                        collectingRepeat = false
                        resetArgs()
                        repeatCount
                    } else {
                        1
                    }
                    if (count > MAX_DIMENSION) return null
                    if (!ensureCanvas(cursorX + count, cursorY + 6)) return null
                    for (i in 0 until count) {
                        val px = cursorX + i
                        for (row in 0..5) {
                            if (bits and (1 shl row) != 0) {
                                pixels[(cursorY + row) * width + px] = currentColor
                            }
                        }
                    }
                    cursorX += count
                    if (!collectingRepeat) repeatCount = 1
                }
                else -> resetArgs() // 空格等其余字节：sixel 语义里忽略
            }
            index++
        }

        if (width == 0 || height == 0) return null

        // —— 裁到实际写入的紧边界 ——
        var usedW = 0
        var usedH = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pixels[y * width + x] != 0) {
                    if (x + 1 > usedW) usedW = x + 1
                    if (y + 1 > usedH) usedH = y + 1
                }
            }
        }
        if (usedW == 0 || usedH == 0) return null

        val bitmap = Bitmap.createBitmap(usedW, usedH, Bitmap.Config.ARGB_8888)
        for (y in 0 until usedH) {
            bitmap.setPixels(pixels, y * width, width, 0, y, usedW, 1)
        }
        return bitmap
    }

    private const val INITIAL_PALETTE_SIZE = 256

    /** HLS（H 0-360 度、S/L 0-1）→ RGB 整数。 */
    private fun hslToRgb(h: Int, s: Float, l: Float): Int {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val hp = (h % 360) / 60f
        val x = c * (1f - Math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return Color.rgb(
            ((r1 + m) * 255f).toInt().coerceIn(0, 255),
            ((g1 + m) * 255f).toInt().coerceIn(0, 255),
            ((b1 + m) * 255f).toInt().coerceIn(0, 255),
        )
    }
}
