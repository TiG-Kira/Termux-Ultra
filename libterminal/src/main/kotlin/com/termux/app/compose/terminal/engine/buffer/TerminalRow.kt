package com.termux.app.compose.terminal.engine.buffer

import com.termux.app.compose.terminal.text.TextStyle
import com.termux.app.compose.terminal.text.WcWidth
import com.termux.app.compose.terminal.text.charCountAtSafe
import com.termux.app.compose.terminal.text.forEachColumn

/**
 * 终端单行数据。
 *
 * 文本存储在 [mText]（char 数组）中，样式存储在 [mStyle]（Long 数组）中，
 * 渲染时可直接按列索引访问，无需拆箱。
 */
internal class TerminalRow(
    /** 本行列数。 */
    private val mColumns: Int,
    style: TextStyle
) {
    /** 存储行内文本的字符数组，可能包含用于填充的尾部空格。 */
    @JvmField
    var mText = CharArray((SPARE_CAPACITY_FACTOR * mColumns).toInt()) { ' ' }

    /** 已使用的字符数（Java char 单位）。 */
    var mSpaceUsed = 0
        private set

    /** 行末是否因输出而自动换行。 */
    @JvmField
    var mLineWrap: Boolean = false

    /** 各列的样式位，交错存储：偶数索引=主样式，奇数索引=扩展特效。以原始 Long 存储以避免装箱。 */
    @JvmField
    val mStyle = LongArray(mColumns * 2)

    /** 本行是否包含宽度 != 1 的字符或代理项对，用于禁用快速路径。 */
    var mHasNonOneWidthOrSurrogateChars: Boolean = false

    /** 本行是否全为空白。 */
    var isBlank = true
        private set

    /** 用指定样式构造空白行（仅含空格）。 */
    init {
        clear(style)
    }

    /**
     * 从源行复制 [sourceX1] 到 [sourceX2)（不含）的内容到本行 [destinationX] 位置。
     *
     * 处理宽字符后半部分的空白填充、组合字符的偏移覆盖，以及行内样式合并。
     * 注意：[sourceX2] 为排他端点。
     */
    fun copyInterval(line: TerminalRow, sourceX1: Int, sourceX2: Int, destinationX: Int) {
        var sourceX1 = sourceX1
        var destinationX = destinationX
        mHasNonOneWidthOrSurrogateChars =
            mHasNonOneWidthOrSurrogateChars or line.mHasNonOneWidthOrSurrogateChars
        val x1 = line.findStartOfColumn(sourceX1)
        val x2 = line.findStartOfColumn(sourceX2)
        var startingFromSecondHalfOfWideChar =
            (sourceX1 > 0 && line.wideDisplayCharacterStartingAt(sourceX1 - 1))
        val sourceChars = if (this == line) line.mText.copyOf(line.mText.size) else line.mText
        val sourceStyle = if (this == line) line.mStyle.copyOf() else line.mStyle
        var latestNonCombiningWidth = 0
        var i = x1
        while (i < x2) {
            val sourceChar = sourceChars[i]
            var codePoint = if (sourceChar.isHighSurrogate()) Character.toCodePoint(
                sourceChar,
                sourceChars[++i]
            ) else sourceChar.code
            if (startingFromSecondHalfOfWideChar) {
                // 宽字符后半部分的复制视同空白填充
                codePoint = ' '.code
                startingFromSecondHalfOfWideChar = false
            }
            val w = WcWidth.width(codePoint)
            if (w > 0) {
                destinationX += latestNonCombiningWidth
                sourceX1 += latestNonCombiningWidth
                latestNonCombiningWidth = w
            }

            // 从安全的快照中读取偶数位（主样式）和奇数位（扩展特效）
            val styleValue = sourceStyle[sourceX1 * 2]
            val extEffect = sourceStyle[sourceX1 * 2 + 1]

            setChar(destinationX, codePoint, TextStyle(styleValue), extEffect)
            i++
        }
    }

    /**
     * 查找指定列号对应的字符数组起始索引。
     *
     * 当行内包含宽字符或代理项对时，列号与字符索引不是一一对应，
     * 需要从头扫描累加显示宽度来定位。无宽字符时直接返回列号（O(1) 快速路径）。
     */
    fun findStartOfColumn(column: Int): Int {
        if (column == mColumns) return mSpaceUsed
        if (!mHasNonOneWidthOrSurrogateChars) return column

        mText.forEachColumn(0, mSpaceUsed) { i, col, _, w, charCount ->
            if (w > 0) {
                val currentColumn = col + w
                if (currentColumn == column) {
                    var newCharIndex = i + charCount
                    while (newCharIndex < mSpaceUsed) {
                        if (WcWidth.width(mText, newCharIndex) <= 0) {
                            newCharIndex += mText.charCountAtSafe(newCharIndex, mSpaceUsed)
                        } else {
                            break
                        }
                    }
                    return newCharIndex
                } else if (currentColumn > column) {
                    return i
                }
            }
            true
        }
        return mSpaceUsed
    }

    private fun wideDisplayCharacterStartingAt(column: Int): Boolean {
        mText.forEachColumn(0, mSpaceUsed) { _, col, _, w, _ ->
            if (w > 0) {
                if (col == column && w == 2) return true
                if (col + w > column) return false
            }
            true
        }
        return false
    }

    fun clear(style: TextStyle, extendedEffect: Long = 0L) {
        if (!isBlank) mText.fill(' ')
        for (i in 0 until mColumns) {
            mStyle[i * 2] = style.value
            mStyle[i * 2 + 1] = extendedEffect
        }
        mSpaceUsed = mColumns
        mHasNonOneWidthOrSurrogateChars = false
        mLineWrap = false
        isBlank = (style.foreColor == TextStyle.COLOR_INDEX_FOREGROUND) &&
                  (style.backColor == TextStyle.COLOR_INDEX_BACKGROUND) &&
                  (style.effect == 0) && (extendedEffect == 0L)
    }

    /**
     * 在指定列写入字符。
     *
     * 处理三种情况：
     * 1. 普通字符：直接写入
     * 2. 组合字符（零宽）：与前一个字符合并，不占新列
     * 3. 宽字符：写入两列，如果目标位置已有字符则清除后续列
     *
     * 如果目标位置被宽字符占据（写入点在其后半部分），会先拆分宽字符。
     */
    fun setChar(columnToSet: Int, codePoint: Int, style: TextStyle, extendedEffect: Long = 0L) {
        var columnToSet = columnToSet
        require(!(columnToSet < 0 || columnToSet >= mColumns)) { "TerminalRow.setChar(): columnToSet=$columnToSet, codePoint=$codePoint, style=${style.value}" }

        if (codePoint != ' '.code && codePoint != 0) {
            isBlank = false
        } else if (style.foreColor != TextStyle.COLOR_INDEX_FOREGROUND ||
                   style.backColor != TextStyle.COLOR_INDEX_BACKGROUND ||
                   style.effect != 0 || extendedEffect != 0L) {
            isBlank = false
        }

        val isAsciiPrintable = codePoint in 0x20..0x7E
        val newCodePointDisplayWidth = if(isAsciiPrintable) 1 else WcWidth.width(codePoint)

        if (!mHasNonOneWidthOrSurrogateChars) {
            // 快速路径：全为 ASCII 单宽字符，直接替换
            if (isAsciiPrintable) {
                mStyle[columnToSet * 2] = style.value
                mStyle[columnToSet * 2 + 1] = extendedEffect
                mText[columnToSet] = codePoint.toChar()
                if (codePoint != 0x20) isBlank = false
                return
            }
            if (codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT || newCodePointDisplayWidth != 1) {
                mHasNonOneWidthOrSurrogateChars = true
            } else {
                mStyle[columnToSet * 2] = style.value
                mStyle[columnToSet * 2 + 1] = extendedEffect
                mText[columnToSet] = codePoint.toChar()
                return
            }
        }

        val newIsCombining = newCodePointDisplayWidth <= 0
        val wasExtraColForWideChar =
            (columnToSet > 0) && wideDisplayCharacterStartingAt(columnToSet - 1)

        if (newIsCombining) {
            // 组合字符：合并到前一列的字符上
            if (wasExtraColForWideChar) columnToSet--
        } else {
            // 普通/宽字符：如果目标在宽字符后半列，先拆分宽字符
            if (wasExtraColForWideChar) setChar(columnToSet - 1, ' '.code, style)
            val overwritingWideCharInNextColumn =
                newCodePointDisplayWidth == 2 && wideDisplayCharacterStartingAt(columnToSet + 1)
            if (overwritingWideCharInNextColumn) setChar(columnToSet + 1, ' '.code, style)
        }

        // 在 columnToSet 调整（组合字符合并）之后写入样式，确保写入正确的列
        mStyle[columnToSet * 2] = style.value
        mStyle[columnToSet * 2 + 1] = extendedEffect

        var text = mText
        val oldStartOfColumnIndex = findStartOfColumn(columnToSet)
        val oldCodePointDisplayWidth = WcWidth.width(text, oldStartOfColumnIndex)

        val oldCharactersUsedForColumn = calculateOldCharactersUsed(columnToSet, oldStartOfColumnIndex, oldCodePointDisplayWidth)

        if (newIsCombining) {
            val combiningCharsCount = WcWidth.zeroWidthCharsCount(
                mText,
                oldStartOfColumnIndex,
                oldStartOfColumnIndex + oldCharactersUsedForColumn
            )
            if (combiningCharsCount >= MAX_COMBINING_CHARACTERS_PER_COLUMN) return
        }

        var newCharactersUsedForColumn = Character.charCount(codePoint)
        if (newIsCombining) {
            newCharactersUsedForColumn += oldCharactersUsedForColumn
        }

        val oldNextColumnIndex = oldStartOfColumnIndex + oldCharactersUsedForColumn
        val newNextColumnIndex = oldStartOfColumnIndex + newCharactersUsedForColumn

        text = shiftTextBuffer(text, oldNextColumnIndex, newNextColumnIndex, newCharactersUsedForColumn - oldCharactersUsedForColumn)
        mSpaceUsed += newCharactersUsedForColumn - oldCharactersUsedForColumn

        Character.toChars(
            codePoint,
            text,
            oldStartOfColumnIndex + (if (newIsCombining) oldCharactersUsedForColumn else 0)
        )

        handleWidthChange(columnToSet, oldCodePointDisplayWidth, newCodePointDisplayWidth, newNextColumnIndex, text, style)
    }

    /**
     * 计算旧字符占用的 char 数量。
     *
     * 宽字符在行尾时可能没有完整的后半列，需用 mSpaceUsed 作为边界。
     */
    private fun calculateOldCharactersUsed(columnToSet: Int, oldStartOfColumnIndex: Int, oldCodePointDisplayWidth: Int): Int {
        return if (columnToSet + oldCodePointDisplayWidth < mColumns) {
            val oldEndOfColumnIndex = findStartOfColumn(columnToSet + oldCodePointDisplayWidth)
            oldEndOfColumnIndex - oldStartOfColumnIndex
        } else {
            mSpaceUsed - oldStartOfColumnIndex
        }
    }

    private fun shiftTextBuffer(text: CharArray, oldNextColumnIndex: Int, newNextColumnIndex: Int, charDifference: Int): CharArray {
        var text = text
        if (charDifference > 0) {
            val oldCharactersAfterColumn = mSpaceUsed - oldNextColumnIndex
            if (mSpaceUsed + charDifference > text.size) {
                val newText = CharArray(text.size + mColumns)
                text.copyInto(destination = newText, endIndex = oldNextColumnIndex)
                text.copyInto(
                    destination = newText,
                    destinationOffset = newNextColumnIndex,
                    startIndex = oldNextColumnIndex,
                    endIndex = oldNextColumnIndex + oldCharactersAfterColumn
                )
                text = newText
                mText = text
            } else {
                text.copyInto(
                    destination = text,
                    destinationOffset = newNextColumnIndex,
                    startIndex = oldNextColumnIndex,
                    endIndex = oldNextColumnIndex + oldCharactersAfterColumn
                )
            }
        } else if (charDifference < 0) {
            text.copyInto(
                destination = text,
                destinationOffset = newNextColumnIndex,
                startIndex = oldNextColumnIndex,
                endIndex = mSpaceUsed
            )
        }
        return text
    }

    /**
     * 当新旧字符宽度不同时，调整后续内容的缓冲区。
     *
     * 宽→窄：在新字符后插入一个空格占位
     * 窄→宽：删除原后半列的字符
     */
    private fun handleWidthChange(columnToSet: Int, oldCodePointDisplayWidth: Int, newCodePointDisplayWidth: Int, newNextColumnIndex: Int, text: CharArray, style: TextStyle) {
        var text = text
        if (oldCodePointDisplayWidth == 2 && newCodePointDisplayWidth == 1) {
            if (mSpaceUsed + 1 > text.size) {
                val newText = CharArray(text.size + mColumns)
                text.copyInto(destination = newText, endIndex = newNextColumnIndex)
                text.copyInto(
                    destination = newText,
                    destinationOffset = newNextColumnIndex + 1,
                    startIndex = newNextColumnIndex,
                    endIndex = mSpaceUsed
                )
                text = newText
                mText = text
            } else {
                text.copyInto(
                    destination = text,
                    destinationOffset = newNextColumnIndex + 1,
                    startIndex = newNextColumnIndex,
                    endIndex = mSpaceUsed
                )
            }
            text[newNextColumnIndex] = ' '
            mStyle[(columnToSet + 1) * 2] = style.value
            mStyle[(columnToSet + 1) * 2 + 1] = 0L
            ++mSpaceUsed
        } else if (oldCodePointDisplayWidth == 1 && newCodePointDisplayWidth == 2) {
            if (columnToSet == mColumns - 1) {
                val oldCharCount = if (text[newNextColumnIndex - 1].isHighSurrogate() && newNextColumnIndex - 2 >= 0 && !text[newNextColumnIndex - 2].isHighSurrogate()) 2 else 1
                if (oldCharCount == 2) {
                    text[newNextColumnIndex - 2] = ' '.code.toChar()
                }
                text[newNextColumnIndex - 1] = ' '.code.toChar()
            } else if (columnToSet == mColumns - 2) {
                mSpaceUsed = newNextColumnIndex
            } else {
                val newNextNextColumnIndex =
                    newNextColumnIndex + (if (mText[newNextColumnIndex].isHighSurrogate()) 2 else 1)
                val nextLen = newNextNextColumnIndex - newNextColumnIndex

                text.copyInto(
                    destination = text,
                    destinationOffset = newNextColumnIndex,
                    startIndex = newNextNextColumnIndex,
                    endIndex = mSpaceUsed
                )
                mSpaceUsed -= nextLen
            }
        }
    }

    inline fun getStyle(column: Int): TextStyle {
        return TextStyle(mStyle[column * 2])
    }

    inline fun getRawStyle(column: Int): Long = mStyle[column * 2]

    inline fun setRawStyle(column: Int, value: Long) { mStyle[column * 2] = value }

    inline fun getExtendedEffect(column: Int): Long = mStyle[column * 2 + 1]

    inline fun setExtendedEffect(column: Int, effect: Long) { mStyle[column * 2 + 1] = effect }

    companion object {
        private const val SPARE_CAPACITY_FACTOR = 1.5f
        private const val MAX_COMBINING_CHARACTERS_PER_COLUMN = 15
    }
}