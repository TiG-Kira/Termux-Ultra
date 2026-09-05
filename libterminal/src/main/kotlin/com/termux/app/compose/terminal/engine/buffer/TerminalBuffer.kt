package com.termux.app.compose.terminal.engine.buffer

import com.termux.app.compose.terminal.text.TextStyle
import kotlin.math.max
import kotlin.math.min

/**
 * 终端行缓冲区（环形数组）。
 *
 * 管理屏幕可见行和滚动历史，支持文本选择、行列操作、块复制/填充和上下滚动。
 */
internal class TerminalBuffer(
    @JvmField var mColumns: Int,
    @JvmField var mTotalRows: Int,
    @JvmField var mScreenRows: Int
) {
    @JvmField
    var mLines: Array<TerminalRow?> = arrayOfNulls(mTotalRows)

    var activeTranscriptRows: Int = 0
        private set

    private var mScreenFirstRow = 0

    init {
        blockSet(0, 0, mColumns, mScreenRows, ' '.code, TextStyle.NORMAL)
    }

    fun getSelectedText(selX1: Int, selY1: Int, selX2: Int, selY2: Int): String {
        return getSelectedText(selX1, selY1, selX2, selY2, true)
    }

    fun getSelectedText(
        selX1: Int,
        selY1: Int,
        selX2: Int,
        selY2: Int,
        joinBackLines: Boolean
    ): String {
        return getSelectedText(selX1, selY1, selX2, selY2, joinBackLines, false)
    }

    /**
     * 提取指定矩形区域的选中文本。
     *
     * @param joinBackLines 是否将自动换行的续行合并为一行（续行以非换行结束时拼接）
     * @param joinFullLines 是否按整行提取（忽略列范围）
     */
    fun getSelectedText(
        selX1: Int,
        selY1: Int,
        selX2: Int,
        selY2: Int,
        joinBackLines: Boolean,
        joinFullLines: Boolean
    ): String {
        var selY1 = selY1
        var selY2 = selY2

        val estimatedCapacity = (selY2 - selY1 + 1) * (mColumns + 1)
        val builder = StringBuilder(estimatedCapacity)

        val columns = mColumns

        if (selY1 < -this.activeTranscriptRows) selY1 = -this.activeTranscriptRows
        if (selY2 >= mScreenRows) selY2 = mScreenRows - 1

        for (row in selY1..selY2) {
            val x1 = if (row == selY1) selX1 else 0
            var x2: Int
            if (row == selY2) {
                x2 = selX2 + 1
                if (x2 > columns) x2 = columns
            } else {
                x2 = columns
            }
            val lineObject = mLines[externalToInternalRow(row)]
            if (lineObject == null) {
                if ((!joinBackLines) && row < selY2 && row < mScreenRows - 1) {
                    builder.append('\n')
                }
                continue
            }
            val x1Index = lineObject.findStartOfColumn(x1)
            var x2Index =
                if (x2 < mColumns) lineObject.findStartOfColumn(x2) else lineObject.mSpaceUsed
            if (x2Index == x1Index) {
                x2Index = lineObject.findStartOfColumn(x2 + 1)
            }
            val line = lineObject.mText
            var lastPrintingCharIndex = -1
            var i: Int
            val rowLineWrap = getLineWrap(row)
            if (rowLineWrap && x2 == columns) {
                lastPrintingCharIndex = x2Index - 1
            } else {
                i = x1Index
                while (i < x2Index) {
                    val c = line[i]
                    if (c != ' ') lastPrintingCharIndex = i
                    ++i
                }
            }

            val len = lastPrintingCharIndex - x1Index + 1
            if (lastPrintingCharIndex != -1 && len > 0) builder.appendRange(line, x1Index,
                x1Index + len
            )

            val lineFillsWidth = lastPrintingCharIndex == x2Index - 1
            if (
                (!joinBackLines || !rowLineWrap) &&
                (!joinFullLines || !lineFillsWidth) &&
                row < selY2 &&
                row < mScreenRows - 1
            ) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }

    /**
     * 判断外部行的指定列是否是空白格（空格或越界空）。
     *
     * 与 [getSelectedText] 单格选择"是否为空"的语义保持一致，
     * 但在 [TextSelectionCursorController] 的单词扩展中避免每次构建字符串的开销，
     * 直接扫描 char 数组判断。
     */
    fun isCellBlank(column: Int, row: Int): Boolean {
        if (column < 0 || column >= mColumns) return true
        val lineObject = mLines[externalToInternalRow(row)] ?: return true
        var x1 = lineObject.findStartOfColumn(column)
        var x2 = lineObject.findStartOfColumn(column + 1)
        if (x2 == x1) {
            x2 = lineObject.findStartOfColumn(column + 2)
        }
        x2 = min(x2, lineObject.mSpaceUsed)
        val line = lineObject.mText
        for (i in x1 until x2) {
            if (line[i] != ' ') return false
        }
        return true
    }

    val activeRows: Int
        get() = this.activeTranscriptRows + mScreenRows

    fun externalToInternalRow(externalRow: Int): Int {
        require(!(externalRow < -this.activeTranscriptRows || externalRow > mScreenRows)) {
            "extRow=" + externalRow + ", mScreenRows=" + mScreenRows + ", mActiveTranscriptRows=" + this.activeTranscriptRows
        }
        return (mScreenFirstRow + externalRow).mod(mTotalRows)
    }

    fun setLineWrap(row: Int) {
        allocateFullLineIfNecessary(externalToInternalRow(row)).mLineWrap = true
    }

    fun getLineWrap(row: Int): Boolean {
        return mLines[externalToInternalRow(row)]?.mLineWrap ?: false
    }

    fun clearLineWrap(row: Int) {
        mLines[externalToInternalRow(row)]?.mLineWrap = false
    }

    /**
     * 调整缓冲区尺寸。
     *
     * 仅高度变化时执行简单垂直重排；宽度变化时委托给 [TerminalReflower] 执行文本重排。
     */
    fun resize(
        newColumns: Int,
        newRows: Int,
        newTotalRows: Int,
        cursor: CursorCoord,
        currentStyle: TextStyle,
        altScreen: Boolean
    ): CursorCoord {
        var newCursor = if (newColumns == mColumns && newRows <= mTotalRows) {
            handleSimpleVerticalResize(newRows, newTotalRows, cursor, currentStyle, altScreen)
        } else {
            handleHorizontalResize(newColumns, newRows, newTotalRows, cursor, currentStyle)
        }

        // 统一处理越界光标防护
        var cX = newCursor.col
        var cY = newCursor.row
        if (cX < 0 || cY < 0) {
            cY = 0
            cX = 0
        }
        return CursorCoord.pack(cX, cY)
    }

    /**
     * 简单垂直重排：仅行数变化时调整屏幕首行和滚动历史。
     */
    private fun handleSimpleVerticalResize(
        newRows: Int,
        newTotalRows: Int,
        cursor: CursorCoord,
        currentStyle: TextStyle,
        altScreen: Boolean
    ): CursorCoord {
        var cursorRow = cursor.row
        var shiftDownOfTopRow = mScreenRows - newRows
        if (shiftDownOfTopRow in 1..<mScreenRows) {
            for (i in mScreenRows - 1 downTo 1) {
                if (cursorRow >= i) break
                val r = externalToInternalRow(i)
                if (mLines[r]?.isBlank ?: true) {
                    if (--shiftDownOfTopRow == 0) break
                }
            }
        } else if (shiftDownOfTopRow < 0) {
            val actualShift = max(shiftDownOfTopRow, -this.activeTranscriptRows)
            if (shiftDownOfTopRow != actualShift) {
                for (i in 0..<actualShift - shiftDownOfTopRow)
                    allocateFullLineIfNecessary((mScreenFirstRow + mScreenRows + i) % mTotalRows)
                        .clear(currentStyle)
                shiftDownOfTopRow = actualShift
            }
        }

        mScreenFirstRow += shiftDownOfTopRow
        mScreenFirstRow = if (mScreenFirstRow < 0) {
            mScreenFirstRow + mTotalRows
        } else {
            mScreenFirstRow % mTotalRows
        }
        mTotalRows = newTotalRows
        this.activeTranscriptRows =
            if (altScreen) 0 else max(0, this.activeTranscriptRows + shiftDownOfTopRow)
        cursorRow -= shiftDownOfTopRow
        mScreenRows = newRows
        return CursorCoord.pack(cursor.col, cursorRow)
    }

    private fun handleHorizontalResize(
        newColumns: Int,
        newRows: Int,
        newTotalRows: Int,
        cursor: CursorCoord,
        currentStyle: TextStyle
    ): CursorCoord {
        // 保存旧状态
        val oldLines = mLines
        val oldActiveTranscriptRows = this.activeTranscriptRows
        val oldScreenFirstRow = mScreenFirstRow
        val oldScreenRows = mScreenRows
        val oldTotalRows = mTotalRows

        // 重新分配新的空缓冲区
        mLines = arrayOfNulls(newTotalRows)
        mTotalRows = newTotalRows
        mScreenRows = newRows
        mScreenFirstRow = 0
        this.activeTranscriptRows = 0
        mColumns = newColumns

        // 执行委托排版（Reflow）操作
        return TerminalReflower.reflow(
            buffer = this,
            oldLines = oldLines,
            oldTotalRows = oldTotalRows,
            oldScreenRows = oldScreenRows,
            oldScreenFirstRow = oldScreenFirstRow,
            oldActiveTranscriptRows = oldActiveTranscriptRows,
            oldCursorColumn = cursor.col,
            oldCursorRow = cursor.row,
            currentStyle = currentStyle
        )
    }

    private fun blockCopyLinesDown(srcInternal: Int, len: Int) {
        if (len == 0) return
        val totalRows = mTotalRows

        val start = len - 1
        val lineToBeOverWritten = mLines[(srcInternal + start + 1) % totalRows]
        for (i in start downTo 0)
            mLines[(srcInternal + i + 1) % totalRows] = mLines[(srcInternal + i) % totalRows]
        mLines[(srcInternal) % totalRows] = lineToBeOverWritten
    }

    fun scrollDownOneLine(topMargin: Int, bottomMargin: Int, style: TextStyle) {
        require(!(topMargin > bottomMargin - 1 || topMargin < 0 || bottomMargin > mScreenRows)) {
            "topMargin=$topMargin, bottomMargin=$bottomMargin, mScreenRows=$mScreenRows"
        }

        blockCopyLinesDown(mScreenFirstRow, topMargin)
        blockCopyLinesDown(externalToInternalRow(bottomMargin), mScreenRows - bottomMargin)

        mScreenFirstRow = (mScreenFirstRow + 1) % mTotalRows
        if (this.activeTranscriptRows < mTotalRows - mScreenRows) this.activeTranscriptRows++

        val blankRow = externalToInternalRow(bottomMargin - 1)
        mLines[blankRow]?.clear(style) ?: { mLines[blankRow] = TerminalRow(mColumns, style) }()
    }

    fun blockCopy(sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) {
        if (w == 0) return
        val copyingUp = sy > dy
        for (y in 0..<h) {
            val y2 = if (copyingUp) y else (h - (y + 1))

            val srcInternal = externalToInternalRow(sy + y2)
            val sourceRow = mLines[srcInternal]

            if (sourceRow == null || sourceRow.isBlank) {
                blockSet(dx, dy + y2, w, 1, ' '.code, TextStyle.NORMAL)
            } else {
                val destInternal = externalToInternalRow(dy + y2)
                allocateFullLineIfNecessary(destInternal)
                    .copyInterval(sourceRow, sx, sx + w, dx)
            }
        }
    }

    fun blockSet(sx: Int, sy: Int, w: Int, h: Int, value: Int, style: TextStyle, extendedEffect: Long = 0L) {
        require(!(sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows)) {
            "Illegal arguments! blockSet($sx, $sy, $w, $h, $value, $mColumns, $mScreenRows)"
        }
        for (y in 0..<h) for (x in 0..<w) setChar(sx + x, sy + y, value, style, extendedEffect)
    }

    fun allocateFullLineIfNecessary(row: Int): TerminalRow {
        return mLines[row] ?: TerminalRow(mColumns, TextStyle.NORMAL).also { mLines[row] = it }
    }

    fun setChar(column: Int, row: Int, codePoint: Int, style: TextStyle, extendedEffect: Long = 0L) {
        require(!(row !in 0..<mScreenRows || column < 0 || column >= mColumns)) {
            "TerminalBuffer.setChar(): row=$row, column=$column, mScreenRows=$mScreenRows, mColumns=$mColumns"
        }
        val row = externalToInternalRow(row)
        allocateFullLineIfNecessary(row).setChar(column, codePoint, style, extendedEffect)
    }

    fun getStyleAt(externalRow: Int, column: Int): TextStyle {
        return allocateFullLineIfNecessary(externalToInternalRow(externalRow)).getStyle(column)
    }

    fun setOrClearEffect(
        bits: Int,
        setOrClear: Boolean,
        reverse: Boolean,
        rectangular: Boolean,
        leftMargin: Int,
        rightMargin: Int,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int
    ) {
        // 仅操作主样式槽（偶数索引）的 effect 位
        // 扩展特效槽（奇数索引）由 setChar 写入时清零
        val effectMask = TextStyle.EFFECT_MASK
        for (y in top..<bottom) {
            val line = allocateFullLineIfNecessary(externalToInternalRow(y))
            val startOfLine = if (rectangular || y == top) left else leftMargin
            val endOfLine = if (rectangular || y + 1 == bottom) right else rightMargin
            for (x in startOfLine..<endOfLine) {
                val raw = line.getRawStyle(x)
                val effect = (raw and effectMask).toInt()
                val newEffect = when {
                    reverse -> (effect and bits.inv()) or (bits and effect.inv())
                    setOrClear -> effect or bits
                    else -> effect and bits.inv()
                }
                line.setRawStyle(x, (raw and effectMask.inv()) or (newEffect.toLong() and effectMask))
            }
        }
    }

    fun clearTranscript() {
        this.activeTranscriptRows = 0
    }

    /** 在指定行插入空行，并将后续行向下移动 */
    fun insertLines(y: Int, count: Int, bottomMargin: Int, style: TextStyle) {
        if (count <= 0) return
        val linesToMove = bottomMargin - y - count
        if (linesToMove > 0) blockCopy(0, y, mColumns, linesToMove, 0, y + count)
        blockSet(0, y, mColumns, count, ' '.code, style)
    }

    /** 删除指定行，并将后续行向上移动填补 */
    fun deleteLines(y: Int, count: Int, bottomMargin: Int, style: TextStyle) {
        if (count <= 0) return
        val linesToMove = bottomMargin - y - count
        if (linesToMove > 0) blockCopy(0, y + count, mColumns, linesToMove, 0, y)
        blockSet(0, y + linesToMove, mColumns, count, ' '.code, style)
    }

    /** 在指定列插入空白字符，并将后续字符向右平移 */
    fun insertColumns(x: Int, y: Int, count: Int, rows: Int, rightMargin: Int, style: TextStyle) {
        if (count <= 0) return
        val charsToMove = rightMargin - x - count
        if (charsToMove > 0) blockCopy(x, y, charsToMove, rows, x + count, y)
        blockSet(x, y, count, rows, ' '.code, style)
    }

    /** 删除指定列的字符，并将后续字符向左平移填补 */
    fun deleteColumns(x: Int, y: Int, count: Int, rows: Int, rightMargin: Int, style: TextStyle) {
        if (count <= 0) return
        val charsToMove = rightMargin - x - count
        if (charsToMove > 0) blockCopy(x + count, y, charsToMove, rows, x, y)
        blockSet(x + charsToMove, y, count, rows, ' '.code, style)
    }
}