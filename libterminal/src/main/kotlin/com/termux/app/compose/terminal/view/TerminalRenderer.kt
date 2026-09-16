package com.termux.app.compose.terminal.view

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathDashPathEffect
import android.graphics.PathEffect
import android.graphics.PorterDuff
import android.graphics.Typeface
import com.termux.app.compose.terminal.engine.TerminalEmulator
import com.termux.app.compose.terminal.text.TextStyle
import com.termux.app.compose.terminal.text.WcWidth
import com.termux.app.compose.terminal.text.charCountAtSafe
import com.termux.app.compose.terminal.text.withCodePointAt
import com.termux.app.compose.terminal.engine.buffer.TerminalBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * 将 [TerminalEmulator] 渲染到 [Canvas]。
 *
 * 缓存字体度量信息，字体或字号变更时需重新创建。
 */
internal class TerminalRenderer(textSize: Int, typeface: Typeface) {
    private val mTextPaint = Paint()

    @JvmField
    val fontWidth: Float
    @JvmField
    val fontLineSpacing: Int
    private val mFontAscent: Int
    @JvmField
    val mFontLineSpacingAndAscent: Int
    private val asciiMeasures = FloatArray(127)

    // 下划线独立画笔：描边模式，粗细随字号在 init 中设定，避免依赖 mTextPaint 状态被文本绘制覆盖
    private val mUnderlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val mUnderlineThickness: Float
    private val mUnderlineOffset: Float
    private val mDashedEffect: PathEffect
    private val mDottedEffect: PathEffect
    private val mCurlyEffect: PathEffect

    init {
        mTextPaint.textSize = textSize.toFloat()
        mTextPaint.typeface = typeface
        mTextPaint.isAntiAlias = true

        this.fontLineSpacing = ceil(mTextPaint.fontSpacing.toDouble()).toInt()
        mFontAscent = ceil(mTextPaint.ascent().toDouble()).toInt()
        mFontLineSpacingAndAscent = this.fontLineSpacing + mFontAscent
        this.fontWidth = mTextPaint.measureText("X")

        val sb = StringBuilder(" ")
        for (i in asciiMeasures.indices) {
            sb.setCharAt(0, i.toChar())
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1)
        }

        // 下划线几何参数随字号缩放，保证不同字号下视觉比例一致
        mUnderlineThickness = max(1f, textSize / 15f)
        mUnderlineOffset = mUnderlineThickness * 1.5f
        mUnderlinePaint.strokeWidth = mUnderlineThickness

        // 虚线/点线相位以屏幕原点为基准，跨文本运行保持连续
        mDashedEffect = DashPathEffect(floatArrayOf(fontWidth * 0.6f, fontWidth * 0.4f), 0f)
        mDottedEffect = DashPathEffect(floatArrayOf(mUnderlineThickness, mUnderlineThickness * 2f), 0f)

        // 波浪特效以单列宽为周期平铺印章，相邻列波形无缝衔接
        val wavePath = Path().apply {
            val waveLen = fontWidth
            val halfL = waveLen / 2f
            // 振幅，设为字宽的 12%，保证波形明显且优雅
            val amp = waveLen * 0.12f 
            // 丝带的粗细，比普通下划线略粗一点更清晰
            val thickness = mUnderlineThickness * 1.5f 
            
            // 1. 上边缘 (从左到右正向绘制完美正弦波)
            moveTo(0f, 0f)
            cubicTo(halfL * 0.364f, -amp, halfL * 0.636f, -amp, halfL, 0f)
            cubicTo(halfL + halfL * 0.364f, amp, halfL + halfL * 0.636f, amp, waveLen, 0f)
            
            // 2. 右侧边缘闭合，并下移厚度
            lineTo(waveLen, thickness)
            
            // 3. 下边缘 (从右到左反向绘制，控制点需加上 thickness)
            cubicTo(
                halfL + halfL * 0.636f, amp + thickness,
                halfL + halfL * 0.364f, amp + thickness,
                halfL, thickness
            )
            cubicTo(
                halfL * 0.636f, -amp + thickness,
                halfL * 0.364f, -amp + thickness,
                0f, thickness
            )
            
            // 4. 闭合左侧边缘
            close()
        }
        mCurlyEffect = PathDashPathEffect(wavePath, fontWidth, 0f, PathDashPathEffect.Style.TRANSLATE)
    }

    fun render(
        mEmulator: TerminalEmulator, resolver: TerminalPaletteResolver, canvas: Canvas, topRow: Int,
        selectionY1: Int, selectionY2: Int, selectionX1: Int, selectionX2: Int
    ) {
        val reverseVideo = mEmulator.isReverseVideo
        val endRow = topRow + mEmulator.mRows
        val columns = mEmulator.mColumns
        val cursorCol = mEmulator.cursorCol
        val cursorRow = mEmulator.cursorRow
        val cursorVisible = mEmulator.isCursorVisible
        val screen = mEmulator.screen
        val cursorShape = mEmulator.cursorStyle

        if (reverseVideo) canvas.drawColor(
            resolver.foreground,
            PorterDuff.Mode.SRC
        )

        var heightOffset = mFontLineSpacingAndAscent.toFloat()
        for (row in topRow..<endRow) {
            heightOffset += fontLineSpacing.toFloat()
            renderRow(canvas, mEmulator, row, heightOffset, columns, cursorCol, cursorRow, cursorVisible, selectionY1, selectionY2, selectionX1, selectionX2, reverseVideo, resolver, cursorShape, screen)
        }
    }

    /**
     * 渲染单行终端内容。
     *
     * 逐列扫描，将连续相同样式的字符合并为一个文本绘制运行（run），
     * 处理光标高亮、选区反色、字体宽度不匹配等视觉效果，最后批量绘制。
     */
    private fun renderRow(
        canvas: Canvas,
        mEmulator: TerminalEmulator,
        row: Int,
        heightOffset: Float,
        columns: Int,
        cursorCol: Int,
        cursorRow: Int,
        cursorVisible: Boolean,
        selectionY1: Int,
        selectionY2: Int,
        selectionX1: Int,
        selectionX2: Int,
        reverseVideo: Boolean,
        resolver: TerminalPaletteResolver,
        cursorShape: TerminalCursorStyle,
        screen: TerminalBuffer
    ) {
        val cursorX = if (row == cursorRow && cursorVisible) cursorCol else -1
        var selx1 = -1
        var selx2 = -1
        if (row in selectionY1..selectionY2) {
            if (row == selectionY1) selx1 = selectionX1
            selx2 = if (row == selectionY2) selectionX2 else mEmulator.mColumns
        }

        val lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
        val line = lineObject.mText
        val charsUsedInLine = lineObject.mSpaceUsed

        var lastRunRawStyle: Long = 0L
        var lastRunExtEffect: Long = 0L
        var lastRunInsideCursor = false
        var lastRunInsideSelection = false
        var lastRunStartColumn = -1
        var lastRunStartIndex = 0
        var lastRunFontWidthMismatch = false
        var currentCharIndex = 0
        var measuredWidthForRun = 0f

        var column = 0
        while (column < columns) {
            line.withCodePointAt(currentCharIndex, charsUsedInLine) { codePoint, charsForCodePoint ->
                val codePointWcWidth = WcWidth.width(codePoint)
                val insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1))
                val insideSelection = column in selx1..selx2
                val rawStyle = lineObject.getRawStyle(column)
                val extEffect = lineObject.getExtendedEffect(column)

                val measuredCodePointWidth =
                    if (codePoint < asciiMeasures.size) asciiMeasures[codePoint] 
                    else mTextPaint.measureText(line, currentCharIndex, charsForCodePoint)
                    
                val fontWidthMismatch = abs(measuredCodePointWidth / this.fontWidth - codePointWcWidth) > 0.01

                // 直接比较原始 Long，避免 TextStyle 装箱
                // 当样式/扩展特效/光标/选区/字体宽度任一变化时，中断当前文本运行
                if (rawStyle != lastRunRawStyle ||
                    extEffect != lastRunExtEffect ||
                    insideCursor != lastRunInsideCursor ||
                    insideSelection != lastRunInsideSelection ||
                    fontWidthMismatch ||
                    lastRunFontWidthMismatch) {
                    if (column == 0) {
                        // 跳过首列（无可绘制内容），仅记录当前样式
                    } else {
                        drawTextRun(
                            canvas,
                            line,
                            resolver,
                            heightOffset,
                            lastRunStartColumn,
                            column - lastRunStartColumn,
                            lastRunStartIndex,
                            currentCharIndex - lastRunStartIndex,
                            measuredWidthForRun,
                            if (lastRunInsideCursor) resolver.cursor else 0,
                            cursorShape,
                            TextStyle(lastRunRawStyle),
                            lastRunExtEffect,
                            reverseVideo || (lastRunInsideCursor && cursorShape == TerminalCursorStyle.BLOCK) || lastRunInsideSelection,
                            mEmulator
                        )
                    }
                    measuredWidthForRun = 0f
                    lastRunRawStyle = rawStyle
                    lastRunExtEffect = extEffect
                    lastRunInsideCursor = insideCursor
                    lastRunInsideSelection = insideSelection
                    lastRunStartColumn = column
                    lastRunStartIndex = currentCharIndex
                    lastRunFontWidthMismatch = fontWidthMismatch
                }
                measuredWidthForRun += measuredCodePointWidth
                column += codePointWcWidth
                currentCharIndex += charsForCodePoint
                
                // 跳过后续的零宽字符（如组合字符）
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    currentCharIndex += line.charCountAtSafe(currentCharIndex, charsUsedInLine)
                }
            }
        }

        drawTextRun(
            canvas,
            line,
            resolver,
            heightOffset,
            lastRunStartColumn,
            columns - lastRunStartColumn,
            lastRunStartIndex,
            currentCharIndex - lastRunStartIndex,
            measuredWidthForRun,
            if (lastRunInsideCursor) resolver.cursor else 0,
            cursorShape,
            TextStyle(lastRunRawStyle),
            lastRunExtEffect,
            reverseVideo || (lastRunInsideCursor && cursorShape == TerminalCursorStyle.BLOCK) || lastRunInsideSelection,
            mEmulator
        )
    }

    /**
     * 绘制一个文本运行（连续相同样式的字符片段）。
     *
     * 处理背景色绘制、字符宽度与字体宽度不匹配时的缩放、
     * 组合字符偏移、代理项对处理、以及字体宽度缩放阈值判定。
     */
    private fun drawTextRun(
        canvas: Canvas,
        text: CharArray,
        resolver: TerminalPaletteResolver,
        y: Float,
        startColumn: Int,
        runWidthColumns: Int,
        startCharIndex: Int,
        runWidthChars: Int,
        mes: Float,
        cursor: Int,
        cursorStyle: TerminalCursorStyle,
        textStyle: TextStyle,
        extendedEffect: Long,
        reverseVideo: Boolean,
        emulator: TerminalEmulator
    ) {
        var mes = mes
        var foreColor = textStyle.foreColor
        var backColor = textStyle.backColor
        
        val bold = textStyle.isBold
        val blink = textStyle.isBlink
        val underline = textStyle.isUnderline
        val italic = textStyle.isItalic
        val strikeThrough = textStyle.isStrikeThrough
        val dim = textStyle.isDim
        val invisible = textStyle.isInvisible
        // 扩展特效中的下划线样式，NONE 表示无自定义下划线
        val underlineStyle = TextStyle.decodeUnderlineStyle(extendedEffect)

        if ((foreColor and -0x1000000) != -0x1000000) {
            // 粗体使用前 8 色中的亮色（如果适用）
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8
            foreColor = resolver.color(foreColor)
        }

        if ((backColor and -0x1000000) != -0x1000000) {
            backColor = resolver.color(backColor)
        }

        val reverseVideoHere = reverseVideo xor textStyle.isInverse
        if (reverseVideoHere) {
            val tmp = foreColor
            foreColor = backColor
            backColor = tmp
        }

        var left = startColumn * this.fontWidth
        var right = left + runWidthColumns * this.fontWidth

        // 如果字体渲染宽度与列宽不匹配，缩放 Canvas 以适配
        mes /= this.fontWidth
        var savedMatrix = false
        if (abs(mes - runWidthColumns) > 0.01) {
            canvas.save()
            canvas.scale(runWidthColumns / mes, 1f)
            left *= mes / runWidthColumns
            right *= mes / runWidthColumns
            savedMatrix = true
        }

        if (backColor != resolver.background) {
            mTextPaint.color = backColor
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint)
        }

        if (cursor != 0) {
            mTextPaint.color = cursor
            var cursorHeight = (mFontLineSpacingAndAscent - mFontAscent).toFloat()
            if (cursorStyle == TerminalCursorStyle.UNDERLINE) cursorHeight /= 4.0.toFloat()
            else if (cursorStyle == TerminalCursorStyle.BAR) right -= (((right - left) * 3) / 4.0).toFloat()
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint)
        }

        val textIsVisible = !blink || emulator.isTextVisible

        if (!invisible && textIsVisible) {
            if (dim) {
                var red = (0xFF and (foreColor shr 16))
                var green = (0xFF and (foreColor shr 8))
                var blue = (0xFF and foreColor)
                red = red * 2 / 3
                green = green * 2 / 3
                blue = blue * 2 / 3
                foreColor = -0x1000000 + (red shl 16) + (green shl 8) + blue
            }

            mTextPaint.isFakeBoldText = bold
            // 带扩展下划线样式时由独立路径绘制，画笔下划线仅回退用于无扩展样式的数据
            mTextPaint.isUnderlineText = underline && underlineStyle == TextStyle.UNDERLINE_STYLE_NONE
            mTextPaint.textSkewX = if (italic) -0.35f else 0f
            mTextPaint.isStrikeThruText = strikeThrough
            mTextPaint.color = foreColor

            canvas.drawTextRun(
                text,
                startCharIndex,
                runWidthChars,
                startCharIndex,
                runWidthChars,
                left,
                y - mFontLineSpacingAndAscent,
                false,
                mTextPaint
            )
        }

        if (savedMatrix) canvas.restore()

        // 自定义下划线绘制：要求主样式下划线位有效，防止 SGR 24 后残留的扩展样式误绘；
        // 可见性与文字保持一致（隐藏属性、闪烁熄灭阶段不绘制）
        if (underline && underlineStyle != TextStyle.UNDERLINE_STYLE_NONE && !invisible && textIsVisible) {
            // 解析下划线颜色：默认跟随文字前景色（含 dim/reverseVideo 后的最终渲染色）
            val decodedColor = TextStyle.decodeUnderlineColor(extendedEffect)
            mUnderlinePaint.color = if (decodedColor != TextStyle.COLOR_INDEX_FOREGROUND) {
                if ((decodedColor and -0x1000000) == -0x1000000) decodedColor else resolver.color(decodedColor)
            } else {
                foreColor
            }

            // 矩阵已还原，使用绝对坐标绘制，避免字体宽度缩放导致线条粗细变形
            val ulY = y - mFontLineSpacingAndAscent + mUnderlineOffset
            val absoluteLeft = startColumn * this.fontWidth
            val absoluteRight = absoluteLeft + runWidthColumns * this.fontWidth

            canvas.save()
            canvas.clipRect(
                absoluteLeft,
                ulY - mUnderlineThickness * 10f, // 垂直方向彻底放开裁剪，防止波峰被削平
                absoluteRight,
                ulY + mUnderlineThickness * 10f
            )
            when (underlineStyle) {
                TextStyle.UNDERLINE_STYLE_SINGLE -> {
                    mUnderlinePaint.pathEffect = null
                    mUnderlinePaint.strokeCap = Paint.Cap.BUTT
                    canvas.drawLine(absoluteLeft, ulY, absoluteRight, ulY, mUnderlinePaint)
                }
                TextStyle.UNDERLINE_STYLE_DOUBLE -> {
                    mUnderlinePaint.pathEffect = null
                    mUnderlinePaint.strokeCap = Paint.Cap.BUTT
                    val gap = mUnderlineThickness * 1.5f
                    canvas.drawLine(absoluteLeft, ulY - gap / 2, absoluteRight, ulY - gap / 2, mUnderlinePaint)
                    canvas.drawLine(absoluteLeft, ulY + gap / 2, absoluteRight, ulY + gap / 2, mUnderlinePaint)
                }
                else -> {
                    mUnderlinePaint.strokeCap = when (underlineStyle) {
                        TextStyle.UNDERLINE_STYLE_DOTTED -> Paint.Cap.ROUND
                        else -> Paint.Cap.SQUARE
                    }
                    mUnderlinePaint.pathEffect = when (underlineStyle) {
                        TextStyle.UNDERLINE_STYLE_CURLY -> mCurlyEffect
                        TextStyle.UNDERLINE_STYLE_DOTTED -> mDottedEffect
                        else -> mDashedEffect
                    }
                    // 从屏幕原点起笔使特效相位全局对齐，clipRect 裁出当前运行区间
                    canvas.drawLine(0f, ulY, emulator.mColumns * this.fontWidth, ulY, mUnderlinePaint)
                }
            }
            canvas.restore()
        }
    }
}