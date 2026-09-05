package com.termux.app.compose.terminal.engine.buffer

import com.termux.app.compose.terminal.text.TextStyle
import com.termux.app.compose.terminal.text.WcWidth
import com.termux.app.compose.terminal.text.withCodePointAt

/**
 * 专门处理终端尺寸发生水平变化时的复杂文本重排（Reflow）操作。
 */
internal object TerminalReflower {

    /**
     * 执行文本重排：将旧布局的行内容重新填充到新宽度的缓冲区中。
     *
     * 遍历旧布局的每一行，处理行尾空格保留（带自定义背景色的空格不会被截断）、
     * 宽字符跨行边界、组合字符偏移、光标位置追踪等边界情况。
     *
     * @return 新的 (cursorColumn, cursorRow) 游标坐标对，未找到时为 (-1, -1)
     */
    fun reflow(
        buffer: TerminalBuffer,
        oldLines: Array<TerminalRow?>,
        oldTotalRows: Int,
        oldScreenRows: Int,
        oldScreenFirstRow: Int,
        oldActiveTranscriptRows: Int,
        oldCursorColumn: Int,
        oldCursorRow: Int,
        currentStyle: TextStyle
    ): CursorCoord {
        var newCursorRow = -1
        var newCursorColumn = -1
        var newCursorPlaced = false

        var currentOutputExternalRow = 0
        var currentOutputExternalColumn = 0

        var skippedBlankLines = 0
        for (externalOldRow in -oldActiveTranscriptRows..<oldScreenRows) {
            // 将外部行号转换为环形缓冲区的内部索引
            var internalOldRow = oldScreenFirstRow + externalOldRow
            internalOldRow = if (internalOldRow < 0) {
                internalOldRow + oldTotalRows
            } else {
                internalOldRow % oldTotalRows
            }

            val oldLine: TerminalRow? = oldLines[internalOldRow]
            val cursorAtThisRow = externalOldRow == oldCursorRow
            // 跳过空行（但光标所在行即使为空也不跳过，除非光标已放置）
            if (oldLine == null || (!(!newCursorPlaced && cursorAtThisRow)) && oldLine.isBlank) {
                skippedBlankLines++
                continue
            } else if (skippedBlankLines > 0) {
                // 输出之前跳过的空行（在输出中补回空白行）
                for (i in 0..<skippedBlankLines) {
                    if (currentOutputExternalRow == buffer.mScreenRows - 1) {
                        buffer.scrollDownOneLine(0, buffer.mScreenRows, currentStyle)
                    } else {
                        currentOutputExternalRow++
                    }
                    currentOutputExternalColumn = 0
                }
                skippedBlankLines = 0
            }

            var lastNonSpaceIndex = 0
            if (oldLine.mLineWrap) {
                lastNonSpaceIndex = oldLine.mSpaceUsed
            } else {
                var col = 0
                var i = 0
                while (i < oldLine.mSpaceUsed) {
                    oldLine.mText.withCodePointAt(i, oldLine.mSpaceUsed) { cp, charCount ->
                        val safeCol = if (col < oldLine.mStyle.size / 2) col else oldLine.mStyle.size / 2 - 1
                        val style = oldLine.getStyle(safeCol)

                        val hasCustomStyle = style.backColor != TextStyle.COLOR_INDEX_BACKGROUND
                            || style.effect != 0
                            || oldLine.getExtendedEffect(safeCol) != 0L
                        if (cp != ' '.code || hasCustomStyle) {
                            lastNonSpaceIndex = i + charCount
                        }

                        val displayWidth = WcWidth.width(cp)
                        if (displayWidth > 0) col += displayWidth
                        i += charCount
                    }
                }

                if (cursorAtThisRow) {
                    var colCursor = 0
                    var cursorIdx = 0
                    while (cursorIdx < oldLine.mSpaceUsed && colCursor <= oldCursorColumn) {
                        oldLine.mText.withCodePointAt(cursorIdx, oldLine.mSpaceUsed) { cp, charCount ->
                            val displayWidth = WcWidth.width(cp)
                            colCursor += if (displayWidth > 0) displayWidth else 0
                            cursorIdx += charCount
                        }
                    }
                    if (cursorIdx > lastNonSpaceIndex) lastNonSpaceIndex = cursorIdx
                }
            }

            var currentOldCol = 0
            var styleAtCol: TextStyle = TextStyle(0)
            var extEffectAtCol: Long = 0L
            var i = 0
            while (i < lastNonSpaceIndex) {
                oldLine.mText.withCodePointAt(i, lastNonSpaceIndex) { codePoint, charCount ->
                    val displayWidth = WcWidth.width(codePoint)
                    if (displayWidth > 0) {
                        val safeCol = if (currentOldCol < oldLine.mStyle.size / 2) currentOldCol else oldLine.mStyle.size / 2 - 1
                        styleAtCol = oldLine.getStyle(safeCol)
                        extEffectAtCol = oldLine.getExtendedEffect(safeCol)
                    }

                    if (currentOutputExternalColumn + displayWidth > buffer.mColumns) {
                        buffer.setLineWrap(currentOutputExternalRow)
                        if (currentOutputExternalRow == buffer.mScreenRows - 1) {
                            if (newCursorPlaced) newCursorRow--
                            buffer.scrollDownOneLine(0, buffer.mScreenRows, currentStyle)
                        } else {
                            currentOutputExternalRow++
                        }
                        currentOutputExternalColumn = 0
                    }

                    val offsetDueToCombiningChar =
                        (if (displayWidth <= 0 && currentOutputExternalColumn > 0) 1 else 0)
                    val outputColumn = currentOutputExternalColumn - offsetDueToCombiningChar
                    buffer.setChar(outputColumn, currentOutputExternalRow, codePoint, styleAtCol, extEffectAtCol)

                    if (displayWidth > 0) {
                        if (oldCursorRow == externalOldRow && oldCursorColumn == currentOldCol) {
                            newCursorColumn = currentOutputExternalColumn
                            newCursorRow = currentOutputExternalRow
                            newCursorPlaced = true
                        }
                        currentOldCol += displayWidth
                        currentOutputExternalColumn += displayWidth
                    }
                    i += charCount
                }
            }
            if (!newCursorPlaced && oldCursorRow == externalOldRow) {
                newCursorColumn = currentOutputExternalColumn
                newCursorRow = currentOutputExternalRow
                newCursorPlaced = true
            }
            if (externalOldRow != (oldScreenRows - 1) && !oldLine.mLineWrap) {
                if (currentOutputExternalRow == buffer.mScreenRows - 1) {
                    if (newCursorPlaced) newCursorRow--
                    buffer.scrollDownOneLine(0, buffer.mScreenRows, currentStyle)
                } else {
                    currentOutputExternalRow++
                }
                currentOutputExternalColumn = 0
            }
        }

        return CursorCoord.pack(newCursorColumn, newCursorRow)
    }
}