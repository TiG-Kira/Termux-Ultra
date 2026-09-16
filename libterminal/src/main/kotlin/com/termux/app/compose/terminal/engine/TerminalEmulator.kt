package com.termux.app.compose.terminal.engine

import com.termux.app.compose.terminal.view.TerminalCursorStyle
import com.termux.app.compose.terminal.engine.ansi.AnsiEscapeParser
import com.termux.app.compose.terminal.engine.ansi.TerminalActionHandler
import com.termux.app.compose.terminal.engine.buffer.TerminalBuffer
import com.termux.app.compose.terminal.engine.buffer.CursorCoord
import com.termux.app.compose.terminal.color.SparsePalette
import com.termux.app.compose.terminal.color.TerminalColorScheme
import com.termux.app.compose.terminal.text.TextStyle
import com.termux.app.compose.terminal.text.Utf8Decoder
import com.termux.app.compose.terminal.text.WcWidth
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

internal class TerminalEmulator(
    private val writeString: (data: String) -> Unit,
    private val writeByteArray: (data: ByteArray) -> Unit,
    maxTranscriptRows: Int = 5000
) : TerminalActionHandler {

    private var mCursorRow = 0
    private var mCursorCol = 0
    @JvmField
    var mRows = defaultRows
    @JvmField
    var mColumns = defaultColumns
    @JvmField
    var mCellWidthPixels = defaultCellWidthPixels
    @JvmField
    var mCellHeightPixels = defaultCellHeightPixels

    private var mAboutToAutoWrap = false

    var cursorRow: Int
        get() = mCursorRow
        private set(row) {
            mCursorRow = row
            mAboutToAutoWrap = false
        }

    var cursorCol: Int
        get() = mCursorCol
        private set(col) {
            mCursorCol = col
            mAboutToAutoWrap = false
        }

    private fun setCursorPosition(x: Int, y: Int) {
        val newRow = (originTop + y).coerceIn(originTop, originBottom - 1)
        val newCol = (originLeft + x).coerceIn(originLeft, originRight - 1)
        setCursorRowCol(newRow, newCol)
    }

    private fun setCursorColRespectingOriginMode(col: Int) =
        setCursorPosition(col, mCursorRow)

    private fun setCursorRowCol(row: Int, col: Int) {
        mCursorRow = row.coerceIn(0, mRows - 1)
        mCursorCol = col.coerceIn(0, mColumns - 1)
        mAboutToAutoWrap = false
    }

    private val mMainBuffer = TerminalBuffer(mColumns, maxTranscriptRows, mRows)
    private val mAltBuffer = TerminalBuffer(mColumns, mRows, mRows)

    var screen = mMainBuffer
        private set

    val isAlternateBufferActive: Boolean get() = this.screen == mAltBuffer

    private var mTopMargin = 0
    private var mBottomMargin = 0
    private var mLeftMargin = 0
    private var mRightMargin = 0

    private val originTop: Int get() = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) mTopMargin else 0
    private val originLeft: Int get() = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) mLeftMargin else 0
    private val originBottom: Int get() = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) mBottomMargin else mRows
    private val originRight: Int get() = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) mRightMargin else mColumns

    private val mSavedStateMain = SavedScreenState()
    private val mSavedStateAlt = SavedScreenState()

    private var mUseLineDrawingG0 = false
    private var mUseLineDrawingG1 = false
    private var mUseLineDrawingUsesG0 = true

    // DEC 私有模式位状态注册表（当前位 + 已保存位）
    private val decModes = DecModeRegistry()

    // SGR 文字属性状态（前景/背景/特效/下划线）
    private val rendition = RenditionState()

    /** shell 通过 OSC 动态改色的稀疏覆盖板（独立于主题基底，复位只清空本板）。 */
    @JvmField
    val mPalette: SparsePalette = SparsePalette()

    /** 当前主题基底（深/浅），构造默认深色，外观层可在绑定会话时按需替换为浅色基线。 */
    @JvmField
    var colorScheme: TerminalColorScheme = TerminalColorScheme.dark()

    @JvmField
    var cursorStyle: TerminalCursorStyle = TerminalCursorStyle.BAR

    private fun saveCursor() {
        val state = if (isAlternateBufferActive) mSavedStateAlt else mSavedStateMain
        state.mSavedCursorRow = mCursorRow
        state.mSavedCursorCol = mCursorCol
        state.mSavedEffect = rendition.effect
        state.mSavedForeColor = rendition.foreColor
        state.mSavedBackColor = rendition.backColor
        state.mSavedUnderlineStyle = rendition.underlineStyle
        state.mSavedUnderlineColor = rendition.underlineColor
        state.mSavedDecFlags = decModes.current
        state.mUseLineDrawingG0 = mUseLineDrawingG0
        state.mUseLineDrawingG1 = mUseLineDrawingG1
        state.mUseLineDrawingUsesG0 = mUseLineDrawingUsesG0
    }

    private fun restoreCursor() {
        val state = if (isAlternateBufferActive) mSavedStateAlt else mSavedStateMain
        setCursorRowCol(state.mSavedCursorRow, state.mSavedCursorCol)
        rendition.effect = state.mSavedEffect
        rendition.foreColor = state.mSavedForeColor
        rendition.backColor = state.mSavedBackColor
        rendition.underlineStyle = state.mSavedUnderlineStyle
        rendition.underlineColor = state.mSavedUnderlineColor
        decModes.restoreAutowrapAndOrigin(state.mSavedDecFlags)
        mUseLineDrawingG0 = state.mUseLineDrawingG0
        mUseLineDrawingG1 = state.mUseLineDrawingG1
        mUseLineDrawingUsesG0 = state.mUseLineDrawingUsesG0
    }

    private var mInsertMode = false
    private var mTabStop = BooleanArray(mColumns)

    @Volatile @JvmField
    var isCursorBlinkingEnabled = false
    @Volatile @JvmField
    var cursorBlinkState = false

    @Volatile @JvmField
    var isTextBlinkingEnabled = true
    @Volatile @JvmField
    var textBlinkState = false

    private var mLastEmittedCodePoint = -1

    // OSC 处理器：拥有标题状态与剪贴板事件流，依赖稀疏覆盖板、主题基底与写回回调
    private val osc = OscHandler(mPalette, { colorScheme }, writeString)

    // 输入序列编码器：将鼠标/焦点/粘贴事件编码为发往 shell 的转义序列
    private val inputEncoder = InputSequenceEncoder(writeString, writeByteArray)

    // DCS 设备控制串处理器：响应 DA1 查询与终端能力查询
    private val dcsHandler = DeviceControlHandler(writeString)

    private val ansiParser = AnsiEscapeParser(this)

    private val utf8Decoder = Utf8Decoder { ansiParser.processCodePoint(it) }

    init {
        reset()
    }

    fun reset() {
        cursorStyle = TerminalCursorStyle.BAR
        mInsertMode = false
        mLeftMargin = 0
        mTopMargin = 0
        mBottomMargin = mRows
        mRightMargin = mColumns
        mAboutToAutoWrap = false
        mSavedStateAlt.mSavedForeColor = TextStyle.COLOR_INDEX_FOREGROUND
        mSavedStateMain.mSavedForeColor = TextStyle.COLOR_INDEX_FOREGROUND
        rendition.reset()
        mSavedStateAlt.mSavedBackColor = TextStyle.COLOR_INDEX_BACKGROUND
        mSavedStateMain.mSavedBackColor = TextStyle.COLOR_INDEX_BACKGROUND
        mSavedStateAlt.mSavedUnderlineStyle = TextStyle.UNDERLINE_STYLE_NONE
        mSavedStateMain.mSavedUnderlineStyle = TextStyle.UNDERLINE_STYLE_NONE
        mSavedStateAlt.mSavedUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND
        mSavedStateMain.mSavedUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND
        setDefaultTabStops()
        mUseLineDrawingG1 = false
        mUseLineDrawingG0 = false
        mUseLineDrawingUsesG0 = true
        mSavedStateMain.mSavedDecFlags = 0
        mSavedStateMain.mSavedEffect = 0
        mSavedStateMain.mSavedCursorCol = 0
        mSavedStateMain.mSavedCursorRow = 0
        mSavedStateAlt.mSavedDecFlags = 0
        mSavedStateAlt.mSavedEffect = 0
        mSavedStateAlt.mSavedCursorCol = 0
        mSavedStateAlt.mSavedCursorRow = 0
        decModes.resetDefault()
        mSavedStateAlt.mSavedDecFlags = decModes.current
        mSavedStateMain.mSavedDecFlags = decModes.current
        mPalette.resetAll()
        utf8Decoder.reset()
        ansiParser.reset()
    }

    fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        this.mCellWidthPixels = cellWidthPixels
        this.mCellHeightPixels = cellHeightPixels

        val sizeChanged = mColumns != columns || mRows != rows

        if (sizeChanged) {
            if (mRows != rows) {
                mRows = rows
                mTopMargin = 0
                mBottomMargin = mRows
            }
            if (mColumns != columns) {
                val oldColumns = mColumns
                val oldTabStop = mTabStop
                mColumns = columns
                mTabStop = BooleanArray(mColumns)
                setDefaultTabStops()
                oldTabStop.copyInto(mTabStop, endIndex = min(oldColumns, columns))
                mLeftMargin = 0
                mRightMargin = mColumns
            }
        }

        if (sizeChanged || screen.mColumns != mColumns || screen.mScreenRows != mRows) {
            val newTotalRows = if (isAlternateBufferActive) mRows else mMainBuffer.mTotalRows
            val newCursor = screen.resize(
                mColumns,
                mRows,
                newTotalRows,
                CursorCoord.pack(mCursorCol, mCursorRow), // 传入打包的 Long
                // 重排生成的空白行使用默认样式，避免把瞬态 SGR 特效/背景泄漏到新空行
                TextStyle.NORMAL,
                this.isAlternateBufferActive
            )
            mCursorCol = newCursor.col
            mCursorRow = newCursor.row
        }
    }

    private fun setDefaultTabStops() {
        for (i in 0 until mColumns) mTabStop[i] = (i and 7) == 0 && i != 0
    }

    private fun isDecsetInternalBitSet(bit: Int) = decModes.isSet(bit)
    private fun setDecsetinternalBit(internalBit: Int, set: Boolean) = decModes.set(internalBit, set)

    /** 窗口标题状态（门面转发，实际状态由 [osc] 持有）。 */
    val titleState get() = osc.titleState

    /** OSC 52 剪贴板写入事件流（门面转发）。 */
    val copiedText get() = osc.copiedText

    val isTextVisible: Boolean
        get() = if (isTextBlinkingEnabled) textBlinkState else true

    var scrollCounter: Int = 0
        private set
    var isAutoScrollDisabled: Boolean = false
        private set

    // 输入冻结状态：为真时 append 的字节只进缓存队列而不解码，
    // 用于文本选择期间冻结屏幕，解除后按序重放。全部字段仅在 synchronized(emulator) 内访问。
    private var mInputPaused: Boolean = false
    private val mFrozenInputQueue = ArrayDeque<ByteArray>()

    val isReverseVideo: Boolean get() = isDecsetInternalBitSet(DECSET_BIT_REVERSE_VIDEO)
    val isCursorEnabled: Boolean get() = isDecsetInternalBitSet(DECSET_BIT_CURSOR_ENABLED)
    val isCursorVisible: Boolean
        get() {
            if (!isCursorEnabled) return false
            return if (isCursorBlinkingEnabled) cursorBlinkState else true
        }
    val isKeypadApplicationMode: Boolean
        get() = isDecsetInternalBitSet(
            DECSET_BIT_APPLICATION_KEYPAD
        )
    val isCursorKeysApplicationMode: Boolean
        get() = isDecsetInternalBitSet(
            DECSET_BIT_APPLICATION_CURSOR_KEYS
        )
    val isMouseTrackingActive: Boolean
        get() = isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) ||
                isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT) ||
                isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_ANY_EVENT)

    /** 仅 1002 按钮事件模式，用于判定按住按钮时才上报拖拽移动。 */
    val isMouseButtonEventTrackingActive: Boolean
        get() = isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)

    /** 1003 任意事件模式，用于无条件上报移动（含未按住按钮的悬停）。 */
    val isMouseAnyEventTrackingActive: Boolean
        get() = isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_ANY_EVENT)

    fun append(buffer: ByteArray, length: Int) {
        if (mInputPaused) {
            // 文本选择期间冻结屏幕：仅缓存字节，不进入解码，整屏保持不变
            mFrozenInputQueue.add(buffer.copyOf(length))
            return
        }
        utf8Decoder.decode(buffer, length)
    }

    /**
     * 开关输入冻结。
     *
     * frozen 为真时后续 [append] 的原始字节被缓存而不解码（屏幕完全静止）；
     * 为假时按序重放缓存并恢复实时。需在 synchronized(emulator) 内调用。
     */
    fun setInputPaused(frozen: Boolean) {
        if (frozen == mInputPaused) return
        mInputPaused = frozen
        if (!frozen) {
            while (mFrozenInputQueue.isNotEmpty()) {
                val bytes = mFrozenInputQueue.removeFirst()
                utf8Decoder.decode(bytes, bytes.size)
            }
        }
    }

    override fun onCodePoint(codePoint: Int) {
        emitCodePoint(codePoint)
    }

    override fun onBell() {
        // 默认忽略，可被通知拦截
    }

    override fun onBackspace() {
        if (mLeftMargin == mCursorCol) {
            val previousRow = mCursorRow - 1
            if (previousRow >= mTopMargin && screen.getLineWrap(previousRow)) {
                screen.clearLineWrap(previousRow)
                setCursorRowCol(previousRow, mRightMargin - 1)
            }
        } else {
            this.cursorCol = mCursorCol - 1
        }
    }

    override fun onHorizontalTab() {
        this.cursorCol = nextTabStop(1)
    }

    override fun onLinefeed() {
        doLinefeed()
    }

    override fun onCarriageReturn() {
        this.cursorCol = mLeftMargin
    }

    override fun onShiftIn() {
        mUseLineDrawingUsesG0 = true
    }

    override fun onShiftOut() {
        mUseLineDrawingUsesG0 = false
    }

    override fun onEscCommand(state: Int, command: Int) {
        when (state) {
            AnsiEscapeParser.ESC -> handleEscStandard(command)
            AnsiEscapeParser.ESC_POUND -> {
                if (command == '8'.code) screen.blockSet(0, 0, mColumns, mRows, 'E'.code, rendition.style)
            }

            AnsiEscapeParser.ESC_SELECT_LEFT_PAREN -> mUseLineDrawingG0 = (command == '0'.code)
            AnsiEscapeParser.ESC_SELECT_RIGHT_PAREN -> mUseLineDrawingG1 = (command == '0'.code)
            AnsiEscapeParser.ESC_PERCENT -> {} // 字符集选择，当前忽略
        }
    }

    private fun handleEscStandard(b: Int) {
        when (b.toChar()) {
            '6' -> if (mCursorCol > mLeftMargin) {
                this.cursorCol = mCursorCol - 1
            } else {
                val rows = mBottomMargin - mTopMargin
                screen.insertColumns(mLeftMargin, mTopMargin, 1, rows, mRightMargin, rendition.styleWithoutEffect)
            }

            '7' -> saveCursor()
            '8' -> restoreCursor()
            '9' -> if (mCursorCol < mRightMargin - 1) {
                this.cursorCol = mCursorCol + 1
            } else {
                val rows = mBottomMargin - mTopMargin
                screen.deleteColumns(mLeftMargin, mTopMargin, 1, rows, mRightMargin, rendition.styleWithoutEffect)
            }

            'c' -> {
                reset()
                mMainBuffer.clearTranscript()
                blockClear(0, 0, mColumns, mRows)
                setCursorPosition(0, 0)
            }

            'D' -> doLinefeed()
            'E' -> {
                this.cursorCol = originLeft
                doLinefeed()
            }

            'F' -> setCursorRowCol(0, mBottomMargin - 1)
            'H' -> mTabStop[mCursorCol] = true
            'M' -> if (mCursorRow <= mTopMargin) {
                screen.blockCopy(
                    mLeftMargin,
                    mTopMargin,
                    mRightMargin - mLeftMargin,
                    mBottomMargin - (mTopMargin + 1),
                    mLeftMargin,
                    mTopMargin + 1
                )
                blockClear(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin)
            } else {
                mCursorRow--
            }

            'N', '0' -> {} // 忽略
            '=' -> setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, true)
            '>' -> setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, false)
            else -> {}
        }
    }

    override fun onCsiCommand(
        state: Int,
        command: Int,
        args: IntArray,
        argCount: Int,
        subParams: Int
    ) {
        when (state) {
            AnsiEscapeParser.ESC_CSI -> handleCsiStandard(command, args, argCount, subParams)
            AnsiEscapeParser.ESC_CSI_QUESTIONMARK -> handleCsiQuestionMark(command, args, argCount)
            AnsiEscapeParser.ESC_CSI_BIGGERTHAN -> handleCsiBiggerThan(command)
            AnsiEscapeParser.ESC_CSI_DOLLAR -> handleCsiDollar(command, args, argCount)
            AnsiEscapeParser.ESC_CSI_DOUBLE_QUOTE -> handleCsiDoubleQuote(command, args)
            AnsiEscapeParser.ESC_CSI_SINGLE_QUOTE -> handleCsiSingleQuote(command, args)
            AnsiEscapeParser.ESC_CSI_QUESTIONMARK_ARG_DOLLAR -> handleCsiQuestionMarkArgDollar(command, args)
            AnsiEscapeParser.ESC_CSI_ARGS_SPACE -> handleCsiArgsSpace(command, args)
            AnsiEscapeParser.ESC_CSI_ARGS_ASTERIX -> handleCsiArgsAsterix(command, args)
            AnsiEscapeParser.ESC_CSI_EXCLAMATION -> onSoftReset()
        }
    }

    /**
     * 处理标准 CSI（控制序列介绍）命令。
     *
     * 包括光标移动、行擦除、列操作、字符插入/删除、屏幕擦除、
     * Tab 操作、上下滚动、DEC 私有模式设置/重置、模式切换等。
     */
    private fun handleCsiStandard(b: Int, args: IntArray, argCount: Int, subParams: Int) {
        when (b.toChar()) {
            'A' -> {
                this.cursorRow =
                    max(originTop, mCursorRow - AnsiEscapeParser.getArg(args, 0, 1, true))
            }

            'B' -> this.cursorRow =
                min(mRows - 1, mCursorRow + AnsiEscapeParser.getArg(args, 0, 1, true))

            'C', 'a' -> this.cursorCol =
                min(mRightMargin - 1, mCursorCol + AnsiEscapeParser.getArg(args, 0, 1, true))

            'D' -> this.cursorCol =
                max(mLeftMargin, mCursorCol - AnsiEscapeParser.getArg(args, 0, 1, true))

            'E' -> {
                setCursorPosition(
                    0,
                    mCursorRow - originTop + AnsiEscapeParser.getArg(args, 0, 1, true)
                )
            }

            'F' -> {
                setCursorPosition(
                    0,
                    mCursorRow - originTop - AnsiEscapeParser.getArg(args, 0, 1, true)
                )
            }

            'G' -> this.cursorCol =
                AnsiEscapeParser.getArg(args, 0, 1, true).coerceIn(1, mColumns) - 1

            'H', 'f' -> setCursorPosition(
                AnsiEscapeParser.getArg(args, 1, 1, true) - 1,
                AnsiEscapeParser.getArg(args, 0, 1, true) - 1
            )

            'I' -> this.cursorCol = nextTabStop(AnsiEscapeParser.getArg(args, 0, 1, true))
            'J' -> handleCsiJ(args)
            'K' -> handleCsiK(args)
            'L' -> handleCsiL(args)
            'M' -> handleCsiM(args)
            'P' -> handleCsiP(args)
            'S' -> {
                val linesToScroll = AnsiEscapeParser.getArg(args, 0, 1, true)
                for (i in 0 until linesToScroll) scrollDownOneLine()
            }

            'T' -> {
                val linesToScrollArg = AnsiEscapeParser.getArg(args, 0, 1, true)
                val linesBetween = mBottomMargin - mTopMargin
                val linesToScroll = min(linesBetween, linesToScrollArg)
                screen.blockCopy(
                    mLeftMargin,
                    mTopMargin,
                    mRightMargin - mLeftMargin,
                    linesBetween - linesToScroll,
                    mLeftMargin,
                    mTopMargin + linesToScroll
                )
                blockClear(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, linesToScroll)
            }

            'X' -> {
                mAboutToAutoWrap = false
                screen.blockSet(
                    mCursorCol,
                    mCursorRow,
                    min(AnsiEscapeParser.getArg(args, 0, 1, true), mColumns - mCursorCol),
                    1,
                    ' '.code,
                    rendition.eraseFillStyle
                )
            }

            'Z' -> {
                var numberOfTabs = AnsiEscapeParser.getArg(args, 0, 1, true)
                var newCol = mCursorCol
                for (i in (mCursorCol - 1) downTo 0) {
                    if (mTabStop[i]) {
                        if (--numberOfTabs == 0) {
                            newCol = max(i, mLeftMargin)
                            break
                        }
                    }
                }
                mCursorCol = newCol
            }

            '`' -> setCursorColRespectingOriginMode(AnsiEscapeParser.getArg(args, 0, 1, true) - 1)
            'b' -> {
                if (mLastEmittedCodePoint != -1) {
                    val numRepeat = AnsiEscapeParser.getArg(args, 0, 1, true)
                    for (i in 0 until numRepeat) emitCodePoint(mLastEmittedCodePoint)
                }
            }

            'c' -> if (AnsiEscapeParser.getArg(
                    args,
                    0,
                    0,
                    true
                ) == 0
            ) writeString("\u001b[?64;1;2;6;9;15;18;21;22c")

            'd' -> this.cursorRow = AnsiEscapeParser.getArg(args, 0, 1, true).coerceIn(1, mRows) - 1
            'e' -> {
                setCursorPosition(
                    mCursorCol,
                    mCursorRow - originTop + AnsiEscapeParser.getArg(args, 0, 1, true)
                )
            }

            'g' -> when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                0 -> mTabStop[mCursorCol] = false
                3 -> {
                    for (i in 0 until mColumns) mTabStop[i] = false
                }
            }

            'h' -> doSetMode(true, AnsiEscapeParser.getArg(args, 0, 0, true))
            'l' -> doSetMode(false, AnsiEscapeParser.getArg(args, 0, 0, true))
            'm' -> rendition.selectGraphicRendition(args, argCount, subParams)
            'n' -> when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                5 -> writeByteArray(
                    byteArrayOf(
                        27,
                        '['.code.toByte(),
                        '0'.code.toByte(),
                        'n'.code.toByte()
                    )
                )

                6 -> writeString("\u001b[${mCursorRow + 1};${mCursorCol + 1}R")
            }

            'r' -> {
                mTopMargin = (AnsiEscapeParser.getArg(args, 0, 1, true) - 1).coerceIn(0, mRows - 2)
                mBottomMargin = AnsiEscapeParser.getArg(args, 1, mRows, true).coerceIn(mTopMargin + 1, mRows)
                setCursorPosition(0, 0)
            }

            's' -> if (isDecsetInternalBitSet(DECSET_BIT_LEFTRIGHT_MARGIN_MODE)) {
                mLeftMargin = (AnsiEscapeParser.getArg(args, 0, 1, true) - 1).coerceAtMost(mColumns - 2)
                mRightMargin = AnsiEscapeParser.getArg(args, 1, mColumns, true).coerceIn(mLeftMargin + 1, mColumns)
                setCursorPosition(0, 0)
            } else saveCursor()

            't' -> handleCsiT(args)
            'u' -> restoreCursor()
            '@' -> {
                mAboutToAutoWrap = false
                val columnsAfterCursor = mColumns - mCursorCol
                val spacesToInsert =
                    min(AnsiEscapeParser.getArg(args, 0, 1, true), columnsAfterCursor)
                screen.insertColumns(mCursorCol, mCursorRow, spacesToInsert, 1, mColumns, rendition.eraseFillStyle)
            }
        }
    }

    private fun handleCsiJ(args: IntArray) {
        mAboutToAutoWrap = false
        when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
            0 -> {
                blockClear(mCursorCol, mCursorRow, mRightMargin - mCursorCol)
                blockClear(
                    mLeftMargin,
                    mCursorRow + 1,
                    mRightMargin - mLeftMargin,
                    mBottomMargin - (mCursorRow + 1)
                )
            }

            1 -> {
                blockClear(0, mTopMargin, mColumns, mCursorRow - mTopMargin)
                blockClear(0, mCursorRow, mCursorCol + 1)
            }

            2 -> blockClear(0, 0, mColumns, mRows)
            3 -> mMainBuffer.clearTranscript()
        }
    }

    private fun handleCsiK(args: IntArray) {
        mAboutToAutoWrap = false
        when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
            0 -> blockClear(mCursorCol, mCursorRow, mColumns - mCursorCol)
            1 -> blockClear(0, mCursorRow, mCursorCol + 1)
            2 -> blockClear(0, mCursorRow, mColumns)
        }
    }

    private fun handleCsiL(args: IntArray) {
        val linesAfterCursor = (mBottomMargin - mCursorRow).coerceAtLeast(0)
        val linesToInsert = min(AnsiEscapeParser.getArg(args, 0, 1, true), linesAfterCursor)
        screen.insertLines(mCursorRow, linesToInsert, mBottomMargin, rendition.eraseFillStyle)
    }

    private fun handleCsiM(args: IntArray) {
        val linesAfterCursor = (mBottomMargin - mCursorRow).coerceAtLeast(0)
        val linesToDelete = min(AnsiEscapeParser.getArg(args, 0, 1, true), linesAfterCursor)
        screen.deleteLines(mCursorRow, linesToDelete, mBottomMargin, rendition.eraseFillStyle)
    }

    private fun handleCsiP(args: IntArray) {
        mAboutToAutoWrap = false
        val cellsAfterCursor = mColumns - mCursorCol
        val cellsToDelete = min(AnsiEscapeParser.getArg(args, 0, 1, true), cellsAfterCursor)
        screen.deleteColumns(mCursorCol, mCursorRow, cellsToDelete, 1, mColumns, rendition.eraseFillStyle)
    }

    private fun handleCsiT(args: IntArray) {
        when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
            11 -> writeString("\u001b[1t")
            13 -> writeString("\u001b[3;0;0t")
            14 -> writeString("\u001b[4;${mRows * mCellHeightPixels};${mColumns * mCellWidthPixels}t")
            16 -> writeString("\u001b[6;${mRows * mCellHeightPixels};${mColumns * mCellWidthPixels}t")
            18, 19 -> writeString("\u001b[9;${mRows};${mColumns}t") // 报告精确字符尺寸
            20 -> writeString("\u001b]LIconLabel\u001b\\")
            21 -> writeString("\u001b]l\u001b\\")
            22 -> osc.pushTitle()
            23 -> osc.popTitle()
        }
    }

    /**
     * 处理 DEC 私有 CSI 命令（? 前缀）。
     *
     * 包括 DECSET/DECRST（设置/重置终端特性）：
     * 1250/1252 编码、应用光标键、应用小键盘、备用屏幕缓冲区、
     * 光标可见、行缓冲区、自动换行、光标样式等。
     */
    private fun handleCsiQuestionMark(b: Int, args: IntArray, argCount: Int) {
        when (b.toChar()) {
            'J', 'K' -> {
                mAboutToAutoWrap = false
                val fillChar = ' '.code
                var startCol = -1
                var startRow = -1
                var endCol = -1
                var endRow = -1
                val justRow = (b == 'K'.code)
                when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                    0 -> {
                        startCol = mCursorCol
                        startRow = mCursorRow
                        endCol = mColumns
                        endRow = if (justRow) (mCursorRow + 1) else mRows
                    }

                    1 -> {
                        startCol = 0
                        startRow = if (justRow) mCursorRow else 0
                        endCol = mCursorCol + 1
                        endRow = mCursorRow + 1
                    }

                    2 -> {
                        startCol = 0
                        startRow = if (justRow) mCursorRow else 0
                        endCol = mColumns
                        endRow = if (justRow) (mCursorRow + 1) else mRows
                    }
                }
                val style = rendition.eraseFillStyle
                for (row in startRow until endRow) {
                    for (col in startCol until endCol) {
                        if (!screen.getStyleAt(row, col).isProtected) screen.setChar(
                            col,
                            row,
                            fillChar,
                            style
                        )
                    }
                }
            }

            'h', 'l' -> {
                for (i in 0 until argCount) doDecSetOrReset(b == 'h'.code, args[i])
            }

            'n' -> if (AnsiEscapeParser.getArg(
                    args,
                    0,
                    -1,
                    true
                ) == 6
            ) writeString("\u001b[?${mCursorRow + 1};${mCursorCol + 1};1R")

            'r', 's' -> {
                for (i in 0 until argCount) {
                    val externalBit = args[i]
                    val internalBit = decModes.mapExternalToInternal(externalBit)
                    if (internalBit != -1) {
                        if (b == 's'.code) decModes.saveModeBit(internalBit)
                        else doDecSetOrReset(decModes.isSaved(internalBit), externalBit)
                    }
                }
            }
        }
    }

    private fun handleCsiBiggerThan(b: Int) {
        when (b.toChar()) {
            'c' -> writeString("\u001b[>41;320;0c")
        }
    }

    private fun handleCsiDollar(b: Int, args: IntArray, argCount: Int) {
        when (b.toChar()) {
            'v' -> {
                val topSource = (AnsiEscapeParser.getArg(args, 0, 1, true) - 1 + originTop).coerceAtMost(mRows)
                val leftSource = (AnsiEscapeParser.getArg(args, 1, 1, true) - 1 + originLeft).coerceAtMost(mColumns)
                val bottomSource = (AnsiEscapeParser.getArg(args, 2, mRows, true) + originTop).coerceIn(topSource, mRows)
                val rightSource = (AnsiEscapeParser.getArg(args, 3, mColumns, true) + originLeft).coerceIn(leftSource, mColumns)
                val destionationTop =
                    min(AnsiEscapeParser.getArg(args, 5, 1, true) - 1 + originTop, mRows)
                val destinationLeft =
                    min(AnsiEscapeParser.getArg(args, 6, 1, true) - 1 + originLeft, mColumns)
                val heightToCopy = min(mRows - destionationTop, bottomSource - topSource)
                val widthToCopy = min(mColumns - destinationLeft, rightSource - leftSource)
                screen.blockCopy(
                    leftSource,
                    topSource,
                    widthToCopy,
                    heightToCopy,
                    destinationLeft,
                    destionationTop
                )
            }

            '{', 'x', 'z' -> handleCsiDollarErase(b, args)
            'r', 't' -> handleCsiDollarRect(b, args, argCount)
        }
    }

    private fun handleCsiDollarErase(b: Int, args: IntArray) {
        val erase = b != 'x'.code
        val selective = b == '{'.code
        val keepVisualAttributes = erase && selective
        var argIdx = 0
        val fillChar = if (erase) ' '.code else AnsiEscapeParser.getArg(args, argIdx++, -1, true)
        when (fillChar) {
            in 32..126, in 160..255 -> {
                val top = (AnsiEscapeParser.getArg(args, argIdx++, 1, true) + originTop).coerceAtMost(originBottom + 1)
                val left = (AnsiEscapeParser.getArg(args, argIdx++, 1, true) + originLeft).coerceAtMost(originRight + 1)
                val bottom = (AnsiEscapeParser.getArg(args, argIdx++, mRows, true) + originTop).coerceAtMost(originBottom)
                val right = (AnsiEscapeParser.getArg(args, argIdx, mColumns, true) + originLeft).coerceAtMost(originRight)
                val style = rendition.style
                for (row in (top - 1) until bottom) {
                    for (col in (left - 1) until right) {
                        if (!selective || !screen.getStyleAt(row, col).isProtected) {
                            val applyStyle =
                                if (keepVisualAttributes) screen.getStyleAt(row, col)
                                else if (erase) rendition.eraseFillStyle
                                else style
                            val applyExt =
                                if (keepVisualAttributes) screen.allocateFullLineIfNecessary(
                                    screen.externalToInternalRow(row)
                                ).getExtendedEffect(col)
                                else if (erase) 0L
                                else rendition.currentExtendedEffect

                            screen.setChar(col, row, fillChar, applyStyle, applyExt)
                        }
                    }
                }
            }
        }
    }

    private fun handleCsiDollarRect(b: Int, args: IntArray, argCount: Int) {
        val reverse = b == 't'.code
        val top = (AnsiEscapeParser.getArg(args, 0, 1, true) - 1 + originTop).coerceAtMost(originBottom)
        val left = (AnsiEscapeParser.getArg(args, 1, 1, true) - 1 + originLeft).coerceAtMost(originRight)
        val bottom = (AnsiEscapeParser.getArg(args, 2, mRows, true) + 1 + originTop).coerceAtMost(originBottom)
        val right = (AnsiEscapeParser.getArg(args, 3, mColumns, true) + 1 + originLeft).coerceAtMost(originRight)
        for (i in 4 until argCount) {
            val COMBINED_ATTRS =
                TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE or
                        TextStyle.CHARACTER_ATTRIBUTE_BLINK or TextStyle.CHARACTER_ATTRIBUTE_INVERSE
            val (bits, setOrClear) = when (AnsiEscapeParser.getArg(args, i, 0, false)) {
                0 -> COMBINED_ATTRS to reverse
                1 -> TextStyle.CHARACTER_ATTRIBUTE_BOLD to true
                4 -> TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE to true
                5 -> TextStyle.CHARACTER_ATTRIBUTE_BLINK to true
                7 -> TextStyle.CHARACTER_ATTRIBUTE_INVERSE to true
                22 -> TextStyle.CHARACTER_ATTRIBUTE_BOLD to false
                24 -> TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE to false
                25 -> TextStyle.CHARACTER_ATTRIBUTE_BLINK to false
                27 -> TextStyle.CHARACTER_ATTRIBUTE_INVERSE to false
                else -> 0 to true
            }
            if (!(reverse && !setOrClear)) {
                screen.setOrClearEffect(
                    bits,
                    setOrClear,
                    reverse,
                    isDecsetInternalBitSet(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE),
                    originLeft,
                    originRight,
                    top,
                    left,
                    bottom,
                    right
                )
            }
        }
    }

    private fun handleCsiDoubleQuote(b: Int, args: IntArray) {
        if (b == 'q'.code) {
            when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                0, 2 -> rendition.setProtected(false)
                1 -> rendition.setProtected(true)
            }
        }
    }

    private fun handleCsiSingleQuote(b: Int, args: IntArray) {
        val columnsAfterCursor = mRightMargin - mCursorCol
        val columnsToChange = min(AnsiEscapeParser.getArg(args, 1, 1, true), columnsAfterCursor)
        val columnsToMove = columnsAfterCursor - columnsToChange
        when (b) {
            '}'.code -> {
                screen.blockCopy(
                    mCursorCol,
                    0,
                    columnsToMove,
                    mRows,
                    mCursorCol + columnsToChange,
                    0
                )
                blockClear(mCursorCol, 0, columnsToChange, mRows)
            }

            '~'.code -> {
                screen.blockCopy(
                    mCursorCol + columnsToChange,
                    0,
                    columnsToMove,
                    mRows,
                    mCursorCol,
                    0
                )
                blockClear(mCursorCol + columnsToMove, 0, columnsToChange, mRows)
            }
        }
    }

    private fun handleCsiQuestionMarkArgDollar(b: Int, args: IntArray) {
        if (b == 'p'.code) {
            val mode = AnsiEscapeParser.getArg(args, 0, 0, true)
            val value = when (mode) {
                47, 1047, 1049 -> if (isAlternateBufferActive) 1 else 2
                else -> {
                    val internalBit = decModes.mapExternalToInternal(mode)
                    if (internalBit != -1) (if (isDecsetInternalBitSet(internalBit)) 1 else 2) else 0
                }
            }
            writeString("\u001b[?$mode;$value\$y")
        }
    }

    private fun handleCsiArgsSpace(b: Int, args: IntArray) {
        if (b == 'q'.code) {
            when (AnsiEscapeParser.getArg(args, 0, 0, true)) {
                0, 1, 2 -> this.cursorStyle = TerminalCursorStyle.BLOCK
                3, 4 -> this.cursorStyle = TerminalCursorStyle.UNDERLINE
                5, 6 -> this.cursorStyle = TerminalCursorStyle.BAR
            }
        }
    }

    private fun handleCsiArgsAsterix(b: Int, args: IntArray) {
        if (b == 'x'.code) {
            val attrChangeExtent = AnsiEscapeParser.getArg(args, 0, 0, true)
            if (attrChangeExtent in 0..2) {
                setDecsetinternalBit(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE, attrChangeExtent == 2)
            }
        }
    }

    override fun onOscCommand(value: Int, textParameter: String, bellOrStringTerminator: String) {
        osc.onOscCommand(value, textParameter, bellOrStringTerminator)
    }

    /**
     * 处理 DCS 设备控制字符串，委托给 [dcsHandler]。
     */
    override fun onDeviceControl(dcs: String) {
        dcsHandler.handleDeviceControl(
            dcs,
            appCursorKeys = isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS),
            appKeypad = isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD)
        )
    }

    override fun onSoftReset() {
        reset()
    }

    fun sendMouseEvent(mouseButton: Int, column: Int, row: Int, pressed: Boolean) {
        inputEncoder.encodeMouseEvent(
            button = mouseButton,
            column = column,
            row = row,
            pressed = pressed,
            buttonEventTracking = isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT),
            anyEventTracking = isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_ANY_EVENT),
            sgrProtocol = isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_SGR),
            columns = mColumns,
            rows = mRows
        )
    }

    /**
     * 将字符输出到终端屏幕。
     *
     * 处理流程：
     * 1. 线绘字符集映射（G0/G1 → Unicode 盒绘符号）
     * 2. 自动换行判定（行尾 + 宽字符或预备换行 → 换行 + 滚动）
     * 3. 插入模式（右移后续内容）
     * 4. 组合字符处理（零宽字符写入前一列位置）
     * 5. 更新光标位置和预备换行标志
     */
    private fun emitCodePoint(codePoint: Int) {
        mLastEmittedCodePoint = codePoint
        var cp = codePoint
        if (if (mUseLineDrawingUsesG0) mUseLineDrawingG0 else mUseLineDrawingG1) {
            cp = mapSpecialCharacters(cp)
        }

        // 自动换行：行尾 + (预备换行 + 普通字符) 或 宽字符 → 换行
        val autoWrap = isDecsetInternalBitSet(DECSET_BIT_AUTOWRAP)
        val displayWidth = WcWidth.width(cp)
        val cursorInLastColumn = mCursorCol == mRightMargin - 1

        if (autoWrap) {
            if (cursorInLastColumn && ((mAboutToAutoWrap && displayWidth == 1) || displayWidth == 2)) {
                screen.setLineWrap(mCursorRow)
                mCursorCol = mLeftMargin
                if (mCursorRow + 1 < mBottomMargin) mCursorRow++ else scrollDownOneLine()
            }
        } else if (cursorInLastColumn && displayWidth == 2) {
            return
        }

        if (mInsertMode && displayWidth > 0) {
            val destCol = mCursorCol + displayWidth
            if (destCol < mRightMargin) screen.blockCopy(
                mCursorCol,
                mCursorRow,
                mRightMargin - destCol,
                1,
                destCol,
                mCursorRow
            )
        }

        val offsetDueToCombiningChar =
            (if (displayWidth <= 0 && mCursorCol > 0 && !mAboutToAutoWrap) 1 else 0)
        screen.setChar(
            mCursorCol - offsetDueToCombiningChar,
            mCursorRow,
            cp,
            rendition.style,
            rendition.currentExtendedEffect
        )

        if (autoWrap && displayWidth > 0) mAboutToAutoWrap =
            (mCursorCol == mRightMargin - displayWidth)
        mCursorCol = min(mCursorCol + displayWidth, mRightMargin - 1)
    }

    private fun doLinefeed() {
        mAboutToAutoWrap = false
        if (mCursorRow >= mBottomMargin) {
            if (mCursorRow != mRows - 1) this.cursorRow = mCursorRow + 1
        } else {
            if (mCursorRow + 1 == mBottomMargin) scrollDownOneLine() else this.cursorRow =
                mCursorRow + 1
        }
    }

    private fun scrollDownOneLine() {
        this.scrollCounter++
        val currentStyle = rendition.eraseFillStyle
        if (mLeftMargin != 0 || mRightMargin != mColumns) {
            screen.blockCopy(
                mLeftMargin,
                mTopMargin + 1,
                mRightMargin - mLeftMargin,
                mBottomMargin - mTopMargin - 1,
                mLeftMargin,
                mTopMargin
            )
            screen.blockSet(
                mLeftMargin,
                mBottomMargin - 1,
                mRightMargin - mLeftMargin,
                1,
                ' '.code,
                currentStyle
            )
        } else {
            screen.scrollDownOneLine(mTopMargin, mBottomMargin, currentStyle)
        }
    }

    private fun doDecSetOrReset(setting: Boolean, externalBit: Int) {
        val internalBit = decModes.mapExternalToInternal(externalBit)
        if (internalBit != -1) setDecsetinternalBit(internalBit, setting)
        when (externalBit) {
            3 -> {
                mTopMargin = 0
                mLeftMargin = 0
                mBottomMargin = mRows
                mRightMargin = mColumns
                setDecsetinternalBit(DECSET_BIT_LEFTRIGHT_MARGIN_MODE, false)
                blockClear(0, 0, mColumns, mRows)
                setCursorRowCol(0, 0)
            }

            6 -> if (setting) setCursorPosition(0, 0)
            69 -> if (!setting) {
                mLeftMargin = 0
                mRightMargin = mColumns
            }

            1048 -> if (setting) saveCursor() else restoreCursor()
            47, 1047, 1049 -> {
                val newScreen = if (setting) mAltBuffer else mMainBuffer
                if (newScreen != this.screen) {
                    val resized =
                        !(newScreen.mColumns == mColumns && newScreen.mScreenRows == mRows)
                    if (setting) saveCursor()
                    this.screen = newScreen
                    if (!setting) {
                        val col = mSavedStateMain.mSavedCursorCol
                        val row = mSavedStateMain.mSavedCursorRow
                        restoreCursor()
                        if (resized) {
                            mCursorCol = col
                            mCursorRow = row
                        }
                    }
                    if (resized) resize(mColumns, mRows, mCellWidthPixels, mCellHeightPixels)
                    if (externalBit == 1049 && newScreen == mAltBuffer) newScreen.blockSet(
                        0,
                        0,
                        mColumns,
                        mRows,
                        ' '.code,
                        rendition.eraseFillStyle
                    )
                }
            }
        }
    }

    private fun doSetMode(newValue: Boolean, modeBit: Int) {
        when (modeBit) {
            4 -> mInsertMode = newValue
        }
    }

    private fun nextTabStop(numTabs: Int): Int {
        var n = numTabs
        for (i in mCursorCol + 1 until mColumns) if (mTabStop[i] && --n == 0) return min(
            i,
            mRightMargin
        )
        return mRightMargin - 1
    }

    private fun blockClear(sx: Int, sy: Int, w: Int, h: Int = 1) =
        screen.blockSet(sx, sy, w, h, ' '.code, rendition.eraseFillStyle)

    fun clearScrollCounter() {
        this.scrollCounter = 0
    }

    fun toggleAutoScrollDisabled() {
        this.isAutoScrollDisabled = !this.isAutoScrollDisabled
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        inputEncoder.encodeFocusEvent(
            hasFocus,
            isDecsetInternalBitSet(DECSET_BIT_SEND_FOCUS_EVENTS)
        )
    }

    fun getSelectedText(x1: Int, y1: Int, x2: Int, y2: Int): String =
        screen.getSelectedText(x1, y1, x2, y2)

    fun paste(text: String) {
        inputEncoder.encodePastedText(text, isDecsetInternalBitSet(DECSET_BIT_BRACKETED_PASTE_MODE))
    }

    private class SavedScreenState {
        @JvmField
        var mSavedCursorRow: Int = 0
        @JvmField
        var mSavedCursorCol: Int = 0
        @JvmField
        var mSavedEffect: Int = 0
        @JvmField
        var mSavedForeColor: Int = 0
        @JvmField
        var mSavedBackColor: Int = 0
        @JvmField
        var mSavedDecFlags: Int = 0
        @JvmField
        var mSavedUnderlineStyle: Int = TextStyle.UNDERLINE_STYLE_NONE
        @JvmField
        var mSavedUnderlineColor: Int = TextStyle.COLOR_INDEX_FOREGROUND
        @JvmField
        var mUseLineDrawingG0: Boolean = false
        @JvmField
        var mUseLineDrawingG1: Boolean = false
        @JvmField
        var mUseLineDrawingUsesG0: Boolean = true
    }


    companion object {
        private const val defaultRows = 24
        private const val defaultColumns = 80
        private const val defaultCellWidthPixels = 10
        private const val defaultCellHeightPixels = 20

        const val MOUSE_LEFT_BUTTON: Int = 0
        const val MOUSE_LEFT_BUTTON_MOVED: Int = 32
        const val MOUSE_WHEELUP_BUTTON: Int = 64
        const val MOUSE_WHEELDOWN_BUTTON: Int = 65

        private fun mapSpecialCharacters(codePoint: Int): Int = when(codePoint) {
            '_'.code -> '\u0020'.code
            '`'.code -> '\u25c6'.code
            '0'.code -> '\u2588'.code
            'a'.code -> '\u2592'.code
            'b'.code -> '\u2409'.code
            'c'.code -> '\u240c'.code
            'd'.code -> '\r'.code
            'e'.code -> '\u240a'.code
            'f'.code -> '\u00b0'.code
            'g'.code -> '\u00b1'.code
            'h'.code -> '\n'.code
            'i'.code -> '\u240b'.code
            'j'.code -> '\u2518'.code
            'k'.code -> '\u2510'.code
            'l'.code -> '\u250c'.code
            'm'.code -> '\u2514'.code
            'n'.code -> '\u253c'.code
            'o'.code -> '\u23ba'.code
            'p'.code -> '\u23bb'.code
            'q'.code -> '\u2500'.code
            'r'.code -> '\u23bc'.code
            's'.code -> '\u23bd'.code
            't'.code -> '\u251c'.code
            'u'.code -> '\u2524'.code
            'v'.code -> '\u2534'.code
            'w'.code -> '\u252c'.code
            'x'.code -> '\u2502'.code
            'y'.code -> '\u2264'.code
            'z'.code -> '\u2265'.code
            '{'.code -> '\u03c0'.code
            '|'.code -> '\u2260'.code
            '}'.code -> '\u00a3'.code
            '~'.code -> '\u00b7'.code
            else -> codePoint
        }
    }
}