package com.termux.app.compose.terminal.color

import com.termux.app.compose.terminal.text.TextStyle

/**
 * 终端主题基底色板。
 *
 * 决定终端默认前景色、背景色与光标色的基底值（以及 256 色板其余槽位的默认值）。
 * 是可实例化的纯数据类，每个终端实例持有一份（[com.awkoo.libterminal.engine.TerminalEmulator.colourScheme]），
 * 不再使用全局单例，因此不同的 view/session 可拥有彼此独立的主题。
 *
 * shell 通过 OSC 序列动态改色时，写入的是 [SparsePalette] 覆盖板，而不是本基底；
 * 因此主题基底保持只读不变，复位只需清空覆盖板。
 *
 * @see SparsePalette
 * @see TerminalPaletteResolver
 */
class TerminalColorScheme private constructor(
    private val colors: IntArray
) {
    /** 取指定槽位的基底色值。 */
    fun color(index: Int): Int = colors[index]

    /** 默认前景色基底。 */
    val foreground: Int get() = colors[TextStyle.COLOR_INDEX_FOREGROUND]

    /** 默认背景色基底。 */
    val background: Int get() = colors[TextStyle.COLOR_INDEX_BACKGROUND]

    /** 默认光标色基底。 */
    val cursor: Int get() = colors[TextStyle.COLOR_INDEX_CURSOR]

    /** 完整 259 色数组副本。 */
    fun toIntArray(): IntArray = colors.copyOf()

    /** 前 16 色（标准色 + 高亮色），返回列表。 */
    fun palette16(): List<Int> = colors.copyOfRange(0, 16).toList()

    companion object {
        /** 深色主题基线：白字黑底，光标白。 */
        fun dark(): TerminalColorScheme = TerminalColorScheme(buildDarkPalette())

        /** 浅色主题基线：黑字浅底，光标深灰。 */
        fun light(): TerminalColorScheme = TerminalColorScheme(buildLightPalette())

        /**
         * 从自定义颜色构建主题基底。
         *
         * @param foreground 前景色（0xAARRGGBB）
         * @param background 背景色（0xAARRGGBB）
         * @param cursor 光标色（0xAARRGGBB）
         * @param palette16 可选的 16 色覆盖；若为 null 则使用标准 Xterm 16 色
         */
        fun custom(
            foreground: Int,
            background: Int,
            cursor: Int,
            palette16: IntArray? = null
        ): TerminalColorScheme {
            val palette = buildBasePalette()
            if (palette16 != null) {
                val copyCount = minOf(palette16.size, 16)
                System.arraycopy(palette16, 0, palette, 0, copyCount)
            }
            palette[TextStyle.COLOR_INDEX_FOREGROUND] = foreground
            palette[TextStyle.COLOR_INDEX_BACKGROUND] = background
            palette[TextStyle.COLOR_INDEX_CURSOR] = cursor
            return TerminalColorScheme(palette)
        }

        private fun buildBasePalette(): IntArray {
            // Xterm 256 色调色板，包含 256 色与特殊扩展色的默认值，
            // 在类加载时通过算法动态生成一次，避免 200 多行的硬编码
            val palette = IntArray(TextStyle.NUM_INDEXED_COLORS).apply {
                // 1. 前 16 色：标准基础色 (注意：蓝色通道被专门提亮过)
                val baseColors = intArrayOf(
                    0xFF000000.toInt(), // 0: 黑
                    0xFFCD0000.toInt(), // 1: 暗红
                    0xFF00CD00.toInt(), // 2: 暗绿
                    0xFFCDCD00.toInt(), // 3: 暗黄
                    0xFF6495ED.toInt(), // 4: 暗蓝 (矢车菊蓝)
                    0xFFCD00CD.toInt(), // 5: 暗品红
                    0xFF00CDCD.toInt(), // 6: 暗青
                    0xFFE5E5E5.toInt(), // 7: 暗白
                    // 后 8 个为高亮色：
                    0xFF7F7F7F.toInt(), // 8: 中灰
                    0xFFFF0000.toInt(), // 9: 亮红
                    0xFF00FF00.toInt(), // 10: 亮绿
                    0xFFFFFF00.toInt(), // 11: 亮黄
                    0xFF5C5CFF.toInt(), // 12: 亮蓝
                    0xFFFF00FF.toInt(), // 13: 亮品红
                    0xFF00FFFF.toInt(), // 14: 亮青
                    0xFFFFFFFF.toInt()  // 15: 亮白
                )
                baseColors.copyInto(this)

                // 2. 216 色立方体 (索引 16 ~ 231)
                // Xterm 标准的 6 个梯度：0x00, 0x5F, 0x87, 0xAF, 0xD7, 0xFF
                val cubeSteps = intArrayOf(0x00, 0x5F, 0x87, 0xAF, 0xD7, 0xFF)
                for (r in 0..5) {
                    for (g in 0..5) {
                        for (b in 0..5) {
                            val index = 16 + (r * 36) + (g * 6) + b
                            this[index] = 0xFF000000.toInt() or
                                    (cubeSteps[r] shl 16) or
                                    (cubeSteps[g] shl 8) or
                                    cubeSteps[b]
                        }
                    }
                }

                // 3. 24 级灰度渐变 (索引 232 ~ 255)
                // 灰阶标准值：从 8 开始，每个步长为 10
                for (i in 0..23) {
                    val gray = 8 + i * 10
                    this[232 + i] = 0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray
                }
            }
            return palette
        }

        private fun buildDarkPalette(): IntArray {
            val palette = buildBasePalette()
            palette[TextStyle.COLOR_INDEX_FOREGROUND] = 0xFFFFFFFF.toInt() // 默认前景色：白
            palette[TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF000000.toInt() // 默认背景色：黑
            palette[TextStyle.COLOR_INDEX_CURSOR] = 0xFFFFFFFF.toInt()    // 默认光标色：白
            return palette
        }

        private fun buildLightPalette(): IntArray {
            val palette = buildBasePalette()
            // 浅色主题仅调整默认前景/背景/光标三项，256 色板保持不变
            palette[TextStyle.COLOR_INDEX_FOREGROUND] = 0xFF000000.toInt() // 默认前景色：黑
            palette[TextStyle.COLOR_INDEX_BACKGROUND] = 0xFFFFFFFF.toInt() // 默认背景色：白
            palette[TextStyle.COLOR_INDEX_CURSOR] = 0xFF000000.toInt()     // 默认光标色：黑
            return palette
        }
    }
}
