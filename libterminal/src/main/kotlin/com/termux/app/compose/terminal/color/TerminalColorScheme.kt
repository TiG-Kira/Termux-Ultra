package com.termux.app.compose.terminal.color

import com.termux.app.compose.terminal.text.TextStyle

/**
 * 终端主题基底色板。
 *
 * 决定终端默认前景色、背景色与光标色的基底值（以及 256 色板其余槽位的默认值）。
 * 是可实例化的纯值类，每个终端实例持有一份（[com.awkoo.libterminal.engine.TerminalEmulator.colorScheme]），
 * 不再使用全局单例，因此不同的 view/session 可拥有彼此独立的主题。
 *
 * shell 通过 OSC 序列动态改色时，写入的是 [SparsePalette] 覆盖板，而不是本基底；
 * 因此主题基底保持只读不变，复位只需清空覆盖板。
 *
 * 值语义：基于内容判等（[color] 各槽位一致即相等），可在不同 view 间安全复用，
 * 且 Compose 重组中重建等值实例也不会被误判为"已变更"。
 *
 * @see SparsePalette
 * @see TerminalPaletteResolver
 */
class TerminalColorScheme private constructor(
    private val colors: IntArray
) {
    /** 取指定槽位的基底色值（0..255 为 256 色板，256=前景，257=背景，258=光标）。 */
    fun color(index: Int): Int = colors[index]

    /** 默认前景色基底。 */
    val foreground: Int get() = colors[TextStyle.COLOR_INDEX_FOREGROUND]

    /** 默认背景色基底。 */
    val background: Int get() = colors[TextStyle.COLOR_INDEX_BACKGROUND]

    /** 默认光标色基底。 */
    val cursor: Int get() = colors[TextStyle.COLOR_INDEX_CURSOR]

    override fun equals(other: Any?): Boolean =
        other is TerminalColorScheme && colors.contentEquals(other.colors)

    override fun hashCode(): Int = colors.contentHashCode()

    companion object {
        /** 槽位索引：默认前景色。 */
        const val INDEX_FOREGROUND: Int = TextStyle.COLOR_INDEX_FOREGROUND

        /** 槽位索引：默认背景色。 */
        const val INDEX_BACKGROUND: Int = TextStyle.COLOR_INDEX_BACKGROUND

        /** 槽位索引：默认光标色。 */
        const val INDEX_CURSOR: Int = TextStyle.COLOR_INDEX_CURSOR

        /** 槽位总数：256 色板 (0..255) + 前景 + 背景 + 光标。 */
        const val COLOR_COUNT: Int = TextStyle.NUM_INDEXED_COLORS

        /** 深色主题基线：白字黑底，光标白。 */
        fun dark(): TerminalColorScheme = TerminalColorScheme(buildDarkPalette())

        /** 浅色主题基线：黑字浅底，光标深灰。 */
        fun light(): TerminalColorScheme = TerminalColorScheme(buildLightPalette())

        /**
         * 以完整 259 槽位色板构建主题。
         *
         * @param colors 长度必须为 [COLOR_COUNT] 的数组，槽位含义见 [color]；
         *               传入数组会被拷贝，之后对原数组的修改不影响本主题。
         */
        fun custom(colors: IntArray): TerminalColorScheme {
            require(colors.size == COLOR_COUNT) {
                "colors must contain exactly $COLOR_COUNT entries, but got ${colors.size}"
            }
            return TerminalColorScheme(colors.copyOf())
        }

        /**
         * 基于标准 Xterm 256 色板构建主题，仅覆盖默认前景/背景/光标与前 16 种基础 ANSI 颜色。
         *
         * 如需改写 256 色板其余槽位，请使用 [custom] 全量入口。
         *
         * @param foreground 默认前景色（0xFFRRGGBB）
         * @param background 默认背景色（0xFFRRGGBB）
         * @param cursor 光标颜色（0xFFRRGGBB）
         * @param ansi16Colors 前 16 种基础 ANSI 颜色（0..7 为标准色，8..15 为高亮色）。
         *                      缺省时使用 Xterm 标准色。
         */
        fun custom(
            foreground: Int,
            background: Int,
            cursor: Int,
            ansi16Colors: IntArray? = null
        ): TerminalColorScheme {
            require(ansi16Colors === null || ansi16Colors.size == 16) {
                "ansi16Colors must contain exactly 16 entries, but got ${ansi16Colors?.size}"
            }
            val palette = buildBasePalette()
            palette[TextStyle.COLOR_INDEX_FOREGROUND] = foreground
            palette[TextStyle.COLOR_INDEX_BACKGROUND] = background
            palette[TextStyle.COLOR_INDEX_CURSOR] = cursor
            ansi16Colors?.copyInto(palette, destinationOffset = 0, startIndex = 0, endIndex = 16)
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