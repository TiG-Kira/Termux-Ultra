package com.termux.app.compose.terminal.text

/**
 * 64 位文本样式编码。
 *
 * 使用内联值类实现零分配开销，将特效标志、前景色和背景色紧凑编码到一个 Long 中。
 *
 * 主样式（[com.awkoo.libterminal.engine.buffer.TerminalRow.mStyle] 偶数索引）位布局：
 * - bit 0..10：11 个特效标志位（粗体、斜体、下划线、闪烁、反色、隐藏、删除线、保护、暗淡、前景真色、背景真色）
 * - bit 16..39：背景色（索引色占 9 位，真色占 24 位）
 * - bit 40..63：前景色（索引色占 9 位，真色占 24 位）
 *
 * 扩展特效（[com.awkoo.libterminal.engine.buffer.TerminalRow.mStyle] 奇数索引）位布局：
 * - bit 0..2：下划线样式（0=无, 1=单线, 2=双线, 3=波浪, 4=点线, 5=虚线）
 * - bit 3：下划线真色标志（1=24-bit RGB, 0=索引色）
 * - bit 16..39：下划线颜色（索引色占 9 位，真色占 24 位）
 */
@JvmInline
internal value class TextStyle(val value: Long) {

    /** 前景色索引或真色值。索引色范围 0..258，真色高位为 1。 */
    val foreColor: Int
        get() = if ((value and CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND.toLong()) == 0L) {
            ((value ushr 40) and 511L).toInt()
        } else {
            TRUE_COLOR_MASK or ((value ushr 40) and 0x00ffffffL).toInt()
        }

    /** 背景色索引或真色值。索引色范围 0..258，真色高位为 1。 */
    val backColor: Int
        get() = if ((value and CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND.toLong()) == 0L) {
            ((value ushr 16) and 511L).toInt()
        } else {
            -0x1000000 or ((value ushr 16) and 0x00ffffffL).toInt()
        }

    /** 特效标志位。 */
    val effect: Int
        get() = (value and EFFECT_MASK).toInt()

    val isBold: Boolean get() = (value and CHARACTER_ATTRIBUTE_BOLD.toLong()) != 0L
    val isItalic: Boolean get() = (value and CHARACTER_ATTRIBUTE_ITALIC.toLong()) != 0L
    val isUnderline: Boolean get() = (value and CHARACTER_ATTRIBUTE_UNDERLINE.toLong()) != 0L
    val isBlink: Boolean get() = (value and CHARACTER_ATTRIBUTE_BLINK.toLong()) != 0L
    val isInverse: Boolean get() = (value and CHARACTER_ATTRIBUTE_INVERSE.toLong()) != 0L
    val isInvisible: Boolean get() = (value and CHARACTER_ATTRIBUTE_INVISIBLE.toLong()) != 0L
    val isStrikeThrough: Boolean get() = (value and CHARACTER_ATTRIBUTE_STRIKETHROUGH.toLong()) != 0L
    val isProtected: Boolean get() = (value and CHARACTER_ATTRIBUTE_PROTECTED.toLong()) != 0L
    val isDim: Boolean get() = (value and CHARACTER_ATTRIBUTE_DIM.toLong()) != 0L

    companion object {
        const val CHARACTER_ATTRIBUTE_BOLD: Int = 1
        const val CHARACTER_ATTRIBUTE_ITALIC: Int = 1 shl 1
        const val CHARACTER_ATTRIBUTE_UNDERLINE: Int = 1 shl 2
        const val CHARACTER_ATTRIBUTE_BLINK: Int = 1 shl 3
        const val CHARACTER_ATTRIBUTE_INVERSE: Int = 1 shl 4
        const val CHARACTER_ATTRIBUTE_INVISIBLE: Int = 1 shl 5
        const val CHARACTER_ATTRIBUTE_STRIKETHROUGH: Int = 1 shl 6
        const val CHARACTER_ATTRIBUTE_PROTECTED: Int = 1 shl 7
        const val CHARACTER_ATTRIBUTE_DIM: Int = 1 shl 8
        const val CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND: Int = 1 shl 9
        const val CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND: Int = 1 shl 10

        const val EFFECT_MASK: Long = (1 shl 11) - 1

        const val COLOR_INDEX_FOREGROUND: Int = 256
        const val COLOR_INDEX_BACKGROUND: Int = 257
        const val COLOR_INDEX_CURSOR: Int = 258

        const val NUM_INDEXED_COLORS: Int = 259

        /** SGR 4 子参数定义的下划线样式。 */
        const val UNDERLINE_STYLE_NONE: Int = 0
        const val UNDERLINE_STYLE_SINGLE: Int = 1
        const val UNDERLINE_STYLE_DOUBLE: Int = 2
        const val UNDERLINE_STYLE_CURLY: Int = 3
        const val UNDERLINE_STYLE_DOTTED: Int = 4
        const val UNDERLINE_STYLE_DASHED: Int = 5

        /** 扩展特效位布局常量。 */
        private const val EXT_UNDERLINE_STYLE_MASK: Long = 0x7L
        private const val EXT_TRUECOLOR_UNDERLINE: Long = 1L shl 3
        private const val EXT_UNDERLINE_COLOR_SHIFT: Int = 16

        val NORMAL: TextStyle = encode(COLOR_INDEX_FOREGROUND, COLOR_INDEX_BACKGROUND, 0)

        const val TRUE_COLOR_MASK: Int = -0x1000000

        inline val Int.isTrueColor: Boolean
            get() = (this and TRUE_COLOR_MASK) == TRUE_COLOR_MASK

        inline val Int.trueColorValue: Int
            get() = this and 0x00ffffff

        inline fun makeTrueColor(r: Int, g: Int, b: Int): Int =
            TRUE_COLOR_MASK or (r shl 16) or (g shl 8) or b

        /**
         * 编码下划线样式和下划线颜色为扩展特效 Long。
         *
         * @param underlineStyle 下划线样式（[UNDERLINE_STYLE_NONE]..[UNDERLINE_STYLE_DASHED]）
         * @param underlineColor 下划线颜色（索引色 0..258 或真色 0xFFRRGGBB）
         */
        fun encodeExtendedEffect(underlineStyle: Int, underlineColor: Int): Long {
            var result = (underlineStyle.toLong() and EXT_UNDERLINE_STYLE_MASK)
            if (underlineColor.isTrueColor) {
                result = result or EXT_TRUECOLOR_UNDERLINE or (underlineColor.trueColorValue.toLong() shl EXT_UNDERLINE_COLOR_SHIFT)
            } else {
                result = result or ((underlineColor.toLong() and 511L) shl EXT_UNDERLINE_COLOR_SHIFT)
            }
            return result
        }

        /** 从扩展特效 Long 中解码下划线样式。 */
        fun decodeUnderlineStyle(extendedEffect: Long): Int =
            (extendedEffect and EXT_UNDERLINE_STYLE_MASK).toInt()

        /** 从扩展特效 Long 中解码下划线颜色（索引色或真色）。 */
        fun decodeUnderlineColor(extendedEffect: Long): Int =
            if ((extendedEffect and EXT_TRUECOLOR_UNDERLINE) != 0L) {
                -0x1000000 or ((extendedEffect ushr EXT_UNDERLINE_COLOR_SHIFT) and 0x00ffffffL).toInt()
            } else {
                ((extendedEffect ushr EXT_UNDERLINE_COLOR_SHIFT) and 511L).toInt()
            }

        /** 编码前景色、背景色和特效为 [TextStyle]。 */
        fun encode(foreColor: Int, backColor: Int, effect: Int): TextStyle {
            var result = (effect and 511).toLong()
            result = if ((-0x1000000 and foreColor) == -0x1000000) {
                result or (CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND.toLong() or ((foreColor.toLong() and 0x00ffffffL) shl 40))
            } else {
                result or ((foreColor.toLong() and 511L) shl 40)
            }
            result = if ((-0x1000000 and backColor) == -0x1000000) {
                result or (CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND.toLong() or ((backColor.toLong() and 0x00ffffffL) shl 16))
            } else {
                result or ((backColor.toLong() and 511L) shl 16)
            }
            return TextStyle(result)
        }
    }
}