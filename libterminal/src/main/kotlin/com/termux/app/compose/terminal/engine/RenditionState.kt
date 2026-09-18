package com.termux.app.compose.terminal.engine

import com.termux.app.compose.terminal.engine.ansi.AnsiEscapeParser
import com.termux.app.compose.terminal.text.TextStyle

/**
 * SGR 文字属性状态（渲染状态）。
 *
 * 集中持有当前前景/背景色、文本特效位、下划线样式与颜色，并提供派生样式
 * （普通样式、擦除填充样式、扩展效果）、SGR 解析 [selectGraphicRendition]、
 * 受保护位切换与复位。纯状态对象，由 [TerminalEmulator] 持有并驱动。
 */
internal class RenditionState {

    var foreColor: Int = TextStyle.COLOR_INDEX_FOREGROUND
    var backColor: Int = TextStyle.COLOR_INDEX_BACKGROUND
    var effect: Int = 0
    var underlineColor: Int = TextStyle.COLOR_INDEX_FOREGROUND
    var underlineStyle: Int = TextStyle.UNDERLINE_STYLE_NONE

    /** 当前完整样式（前景/背景/特效）。 */
    val style: TextStyle get() = TextStyle.encode(foreColor, backColor, effect)

    /** 无特效变体：仅前景/背景，用于字符插入/删除等场景。 */
    val styleWithoutEffect: TextStyle get() = TextStyle.encode(foreColor, backColor, 0)

    /** 擦除填充样式：仅保留当前背景色（BCE），前景色复位为默认，清空所有文本特效。 */
    val eraseFillStyle: TextStyle
        get() = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, backColor, 0)

    /** 扩展效果（下划线样式与颜色）。 */
    val currentExtendedEffect: Long
        get() = TextStyle.encodeExtendedEffect(underlineStyle, underlineColor)

    /** 复位为默认外观。 */
    fun reset() {
        foreColor = TextStyle.COLOR_INDEX_FOREGROUND
        backColor = TextStyle.COLOR_INDEX_BACKGROUND
        effect = 0
        underlineStyle = TextStyle.UNDERLINE_STYLE_NONE
        underlineColor = TextStyle.COLOR_INDEX_FOREGROUND
    }

    /** 设置/清除受保护位（DEC 双引号命令）。 */
    fun setProtected(protected: Boolean) {
        effect = if (protected) {
            effect or TextStyle.CHARACTER_ATTRIBUTE_PROTECTED
        } else {
            effect and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED.inv()
        }
    }

    /**
     * 处理 SGR（选择图形渲染）转义序列。
     *
     * 解析参数数组，设置前景色、背景色、效果位（粗体、下划线、闪烁等）。
     * 支持 256 色和 24-bit RGB 真彩色、子参数位集跳过等 xterm 扩展。
     */
    fun selectGraphicRendition(args: IntArray, argCount: Int, argsSubParamsBitSet: Int) {
        var i = 0
        while (i < argCount) {
            if ((argsSubParamsBitSet and (1 shl i)) != 0) {
                i++
                continue
            }
            var code = AnsiEscapeParser.getArg(args, i, 0, false)
            if (code < 0) {
                if (i > 0) {
                    i++
                    continue
                } else code = 0
            }
            when (code) {
                0 -> {
                    foreColor = TextStyle.COLOR_INDEX_FOREGROUND
                    backColor = TextStyle.COLOR_INDEX_BACKGROUND
                    effect = 0
                    underlineStyle = TextStyle.UNDERLINE_STYLE_NONE
                    underlineColor = TextStyle.COLOR_INDEX_FOREGROUND
                }

                4 -> {
                    if (i + 1 < argCount && ((argsSubParamsBitSet and (1 shl (i + 1))) != 0)) {
                        i++
                        val style = args[i]
                        if (style == 0) {
                            effect = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE.inv()
                            underlineStyle = TextStyle.UNDERLINE_STYLE_NONE
                        } else {
                            effect = effect or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                            underlineStyle =
                                if (style in TextStyle.UNDERLINE_STYLE_NONE..TextStyle.UNDERLINE_STYLE_DASHED) style
                                else TextStyle.UNDERLINE_STYLE_SINGLE
                        }
                    } else {
                        effect = effect or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                        underlineStyle = TextStyle.UNDERLINE_STYLE_SINGLE
                    }
                }
                // SGR 24：关闭下划线时同步重置下划线样式，避免残留样式在后续开启下划线时误生效
                24 -> {
                    effect = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE.inv()
                    underlineStyle = TextStyle.UNDERLINE_STYLE_NONE
                }

                in sgrEffectMap -> {
                    val attr = sgrEffectMap[code]!!
                    effect = if (code < 20) effect or attr else effect and attr.inv()
                }

                // 38/48/58: 前景色/背景色/下划线色的扩展格式（256色和24-bit RGB）
                38, 48, 58 -> if (i + 2 < argCount) {
                    when (args[i + 1]) {
                        2 -> {
                            if (i + 4 < argCount) {
                                val r = AnsiEscapeParser.getArg(args, i + 2, 0, false)
                                val g = AnsiEscapeParser.getArg(args, i + 3, 0, false)
                                val b = AnsiEscapeParser.getArg(args, i + 4, 0, false)
                                if (r in 0..255 && g in 0..255 && b in 0..255) {
                                    val argb = TextStyle.makeTrueColor(r, g, b)
                                    when (code) {
                                        38 -> foreColor = argb
                                        48 -> backColor = argb
                                        58 -> underlineColor = argb
                                    }
                                }
                                i += 4
                            } else {
                                i += 2
                            }
                        }

                        5 -> {
                            val color = AnsiEscapeParser.getArg(args, i + 2, 0, false)
                            i += 2
                            if (color in 0 until TextStyle.NUM_INDEXED_COLORS) {
                                when (code) {
                                    38 -> foreColor = color
                                    48 -> backColor = color
                                    58 -> underlineColor = color
                                }
                            }
                        }
                    }
                } else {
                    // 参数不足，跳过当前 SGR 代码
                    i++
                }

                39 -> foreColor = TextStyle.COLOR_INDEX_FOREGROUND
                49 -> backColor = TextStyle.COLOR_INDEX_BACKGROUND
                59 -> underlineColor = TextStyle.COLOR_INDEX_FOREGROUND
                in 30..37 -> foreColor = code - 30
                in 40..47 -> backColor = code - 40
                in 90..97 -> foreColor = code - 90 + 8
                in 100..107 -> backColor = code - 100 + 8
            }
            i++
        }
    }

    companion object {
        private val sgrEffectMap = mapOf(
            1 to TextStyle.CHARACTER_ATTRIBUTE_BOLD,
            2 to TextStyle.CHARACTER_ATTRIBUTE_DIM,
            3 to TextStyle.CHARACTER_ATTRIBUTE_ITALIC,
            5 to TextStyle.CHARACTER_ATTRIBUTE_BLINK,
            7 to TextStyle.CHARACTER_ATTRIBUTE_INVERSE,
            8 to TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE,
            9 to TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH,
            22 to (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_DIM),
            23 to TextStyle.CHARACTER_ATTRIBUTE_ITALIC,
            25 to TextStyle.CHARACTER_ATTRIBUTE_BLINK,
            27 to TextStyle.CHARACTER_ATTRIBUTE_INVERSE,
            28 to TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE,
            29 to TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH,
        )
    }
}
