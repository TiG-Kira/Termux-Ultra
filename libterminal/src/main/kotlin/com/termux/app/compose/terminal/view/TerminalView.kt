package com.termux.app.compose.terminal.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.view.ActionMode
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.termux.app.compose.terminal.color.TerminalColorScheme
import com.termux.app.compose.terminal.engine.TerminalEmulator
import com.termux.app.compose.terminal.engine.TerminalSession
import com.termux.app.compose.terminal.engine.buffer.CursorCoord
import com.termux.app.compose.terminal.view.input.ImeController
import com.termux.app.compose.terminal.view.input.KeyInputProcessor
import com.termux.app.compose.terminal.view.input.TerminalImeConnection
import com.termux.app.compose.terminal.view.input.TerminalTouchHandler
import com.termux.app.compose.terminal.view.textselection.TextSelectionCursorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlin.math.max
import kotlin.math.roundToInt

/** 外部修饰键状态快照，供 [TerminalView.onKeyDown] 消费。 */
@JvmInline
value class ExtraKeysModifierSnapshot(val mask: Int) {
    val ctrl: Boolean get() = (mask and 1) != 0
    val alt: Boolean get() = (mask and 2) != 0
    val shift: Boolean get() = (mask and 4) != 0
    val fn: Boolean get() = (mask and 8) != 0
}

/**
 * 工厂方法，伪装成原本的构造函数。
 * 运行时 0 分配，直接编译为底层的整数位运算。
 */
inline fun ExtraKeysModifierSnapshot(
    ctrl: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
    fn: Boolean = false
): ExtraKeysModifierSnapshot = ExtraKeysModifierSnapshot(
    (if (ctrl) 1 else 0) or
    (if (alt) 2 else 0) or
    (if (shift) 4 else 0) or
    (if (fn) 8 else 0)
)

/**
 * 终端显示与交互的核心 View。
 *
 * 作为中心协调器，整合以下组件：
 * - [TerminalRenderer] 文本渲染
 * - [TerminalTouchHandler] 触摸 / 鼠标 / 滚动
 * - [TerminalCursorBlinker] 光标闪烁动画
 * - [TerminalImeConnection] IME / 软键盘输入
 * - [TextSelectionCursorController] 文本选择
 *
 * 键盘处理（[onKeyDown]、[onKeyUp] 等）因与 View 状态和会话 I/O 紧密耦合，保留在此类中。
 *
 * @param useLightTheme 是否使用浅色主题。可在运行时修改，修改且已绑定会话时立即重建配色。
 *                      浅色主题仅调整默认前景/背景/光标三项底色，OSC 动态改色仍作为覆盖层生效。
 */
class TerminalView(
    context: Context,
    useLightTheme: Boolean = false
) : View(context) {
    companion object {
        /** 虚拟 / 扩展按键键盘的事件来源标识。 */
        const val KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = 2
    }

    /**
     * 是否使用浅色主题基底。
     *
     * 可在运行时修改：若已有绑定会话，会立即重建该会话的渲染配色（主题基底 + OSC 覆盖板）。
     */
    var useLightTheme: Boolean = useLightTheme
        set(value) {
            if (value == field) return
            field = value
            val session = currentSession ?: return
            applyColorScheme(session)
            invalidate()
        }

    /**
     * 自定义终端配色方案。
     *
     * 若设置非 null，[applyColorScheme] 优先使用此值；
     * 否则退回 [useLightTheme] 决定的默认 dark/light 基底。
     * 可运行时修改，已绑定会话会立即重建配色并重绘。
     */
    var customColorScheme: TerminalColorScheme? = null
        set(value) {
            if (value == field) return
            field = value
            val session = currentSession ?: return
            applyColorScheme(session)
            invalidate()
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 会话事件订阅绑定器：管理屏幕刷新与剪贴板事件流的订阅生命周期
    private val sessionBinder = SessionBinder(
        scope = scope,
        onScreenUpdated = { onScreenUpdated() },
        onCopiedText = { copyTextToClipboard(it) }
    )

    internal val touchHandler = TerminalTouchHandler(this)

    // 剪贴板网关：系统剪贴板读写，粘贴时送入模拟器
    private val clipboard = TerminalClipboard(context, { mEmulator }, { cursorBlinker.poke() })

    // IME 控制器：软键盘显隐切换
    private val imeController = ImeController(this)

    // 键盘输入处理器：按键到会话字节流的转换逻辑委托对象
    private val keyProcessor = KeyInputProcessor(
        sessionProvider = { currentSession },
        emulatorProvider = { mEmulator },
        pokeCursor = { cursorBlinker.poke() },
        scrollPages = { pages ->
            val time = SystemClock.uptimeMillis()
            val motionEvent = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
            touchHandler.doScroll(motionEvent, pages)
            motionEvent.recycle()
        },
        isSelectingText = { isSelectingText },
        stopTextSelection = { stopTextSelectionMode() },
        modifierReader = { extraKeysModifierReader?.invoke() ?: ExtraKeysModifierSnapshot() }
    )

    private val cursorBlinker = TerminalBlinker(
        blinkerName = "cursor",
        scope = scope,
        onInvalidate = { invalidate() },
        shouldStart = { it.isCursorEnabled },
        setBlinkingEnabled = { emulator, enabled -> emulator.isCursorBlinkingEnabled = enabled },
        setBlinkState = { emulator, state -> emulator.cursorBlinkState = state }
    )

    private val textBlinker = TerminalBlinker(
        blinkerName = "text",
        scope = scope,
        onInvalidate = { invalidate() },
        setBlinkingEnabled = { emulator, enabled -> emulator.isTextBlinkingEnabled = enabled },
        setBlinkState = { emulator, state -> emulator.textBlinkState = state }
    )

    /** 当前正在显示的终端会话。 */
    var currentSession: TerminalSession? = null
        set(value) {
            if (value == field) return
            stopTextSelectionMode()
            cursorBlinker.stop()
            textBlinker.stop()
            sessionBinder.bind(value)
            topRow = 0
            keyProcessor.reset()
            field = value

            if (value != null) {
                applyColorScheme(value)
                updateSize()
                onScreenUpdated()
                cursorBlinker.start(value.emulator)
                textBlinker.start(value.emulator)
                toggleIme(true)
            } else {
                currentPalette = null
                invalidate()
            }
        }

    /**
     * 依据当前 [useLightTheme] 为指定会话重建主题基底与合成调色板。
     *
     * 主题基底只调整默认前景/背景/光标三项底色；OSC 动态改色作为
     * [TerminalPaletteResolver] 的覆盖板盖在主题之上，仍保持生效。
     */
    private fun applyColorScheme(session: TerminalSession) {
        val scheme = customColorScheme
            ?: if (useLightTheme) TerminalColorScheme.light() else TerminalColorScheme.dark()
        session.emulator.colorScheme = scheme
        currentPalette = TerminalPaletteResolver(scheme, session.emulator.mPalette)
    }

    /**
     * 当前会话渲染用的合成颜色查询对象（主题基底 + OSC 稀疏覆盖板）。
     * 绑定会话时构建，主题基底由 [useLightTheme] 决定。
     */
    private var currentPalette: TerminalPaletteResolver? = null

    internal val mEmulator: TerminalEmulator?
        get() = currentSession?.emulator

    var textSize: Int = 12
        set(value) {
            field = value.coerceIn(4, 100)
            mRenderer = TerminalRenderer(field.dp, typeface)
            updateSize()
        }

    var typeface: Typeface = Typeface.MONOSPACE
        set(value) {
            if (value == field) return
            field = value
            mRenderer = TerminalRenderer(textSize.dp, field)
            updateSize()
            invalidate()
        }

    internal var mRenderer = TerminalRenderer(textSize.dp, typeface)

    /** 浮动工具栏定制器，用于本地化按钮文字或添加额外操作。 */
    var actionModeCustomizer: ActionModeCustomizer? = null

    private val textSelectionCursorController = TextSelectionCursorController(this)

    internal val isSelectingText: Boolean
        get() = textSelectionCursorController.isActive

    private var mDefaultSelectors: IntArray = intArrayOf(-1, -1, -1, -1)

    internal fun startTextSelectionMode(event: MotionEvent) {
        if (!requestFocus()) return
        textSelectionCursorController.show(event)
        invalidate()
    }

    fun stopTextSelectionMode() {
        if (textSelectionCursorController.hide()) invalidate()
    }

    private fun decrementYTextSelectionCursors(decrement: Int) {
        textSelectionCursorController.decrementYTextSelectionCursors(decrement)
    }

    private val textSelectionActionMode: ActionMode?
        get() = textSelectionCursorController.actionMode

    private val mShowFloatingToolbar: Runnable = Runnable {
        this@TerminalView.textSelectionActionMode?.hide(0)
    }

    internal fun updateFloatingToolbarVisibility(event: MotionEvent) {
        if (this.textSelectionActionMode != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> hideFloatingToolbar()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> showFloatingToolbar()
            }
        }
    }

    private fun showFloatingToolbar() {
        textSelectionActionMode?.let {
            val delay = ViewConfiguration.getDoubleTapTimeout()
            postDelayed(mShowFloatingToolbar, delay.toLong())
        }
    }

    private fun hideFloatingToolbar() {
        textSelectionActionMode?.let {
            removeCallbacks(mShowFloatingToolbar)
            it.hide(-1)
        }
    }

    fun toggleAutoScrollDisabled() {
        mEmulator?.toggleAutoScrollDisabled()
    }

    /** 当前显示的顶行索引，范围从 -activeTranscriptRows 到 0。 */
    internal var topRow: Int = 0

    override fun computeVerticalScrollRange() = mEmulator?.screen?.activeRows ?: 1
    override fun computeVerticalScrollExtent() = mEmulator?.mRows ?: 1
    override fun computeVerticalScrollOffset(): Int {
        val emulator = mEmulator ?: return 1
        return emulator.screen.activeRows + this.topRow - emulator.mRows
    }

    private fun onScreenUpdated(skipScrolling: Boolean = false) {
        val emulator = mEmulator ?: return
        var skipScrolling = skipScrolling

        synchronized(emulator) {
            val rowsInHistory = emulator.screen.activeTranscriptRows
            if (this.topRow < -rowsInHistory) this.topRow = -rowsInHistory

            if (this.isSelectingText || emulator.isAutoScrollDisabled) {
                val rowShift = emulator.scrollCounter
                if (-this.topRow + rowShift > rowsInHistory) {
                    if (this.isSelectingText) stopTextSelectionMode()
                    if (emulator.isAutoScrollDisabled) {
                        this.topRow = -rowsInHistory
                        skipScrolling = true
                    }
                } else {
                    skipScrolling = true
                    this.topRow -= rowShift
                    decrementYTextSelectionCursors(rowShift)
                }
            }
        }

        if (!skipScrolling && this.topRow != 0) {
            if (this.topRow < -3) awakenScrollBars()
            this.topRow = 0
        }

        emulator.clearScrollCounter()
        invalidate()
    }

    /** 获取 MotionEvent 位置对应的 (列, 行) 坐标。 */
    internal fun getColumnAndRow(event: MotionEvent, relativeToScroll: Boolean): CursorCoord {
        val renderer = mRenderer
        val column = (event.x / renderer.fontWidth).toInt()
        var row = ((event.y - renderer.mFontLineSpacingAndAscent) / renderer.fontLineSpacing).toInt()
        if (relativeToScroll) row += this.topRow
        return CursorCoord.pack(column, row)
    }

    internal fun getCursorX(x: Float) = (x / mRenderer.fontWidth).toInt()
    internal fun getCursorY(y: Float) = (((y - 40) / mRenderer.fontLineSpacing) + this.topRow).toInt()

    internal fun getPointX(cx: Int): Int {
        val emulator = mEmulator!!
        val clamped = if (cx > emulator.mColumns) emulator.mColumns else cx
        return (clamped * mRenderer.fontWidth).roundToInt()
    }

    internal fun getPointY(cy: Int) = ((cy - this.topRow) * mRenderer.fontLineSpacing).toFloat().roundToInt()

    internal fun copyTextToClipboard() {
        copyTextToClipboard(textSelectionCursorController.selectedText)
    }

    internal fun copyTextToClipboard(text: String) = clipboard.copyText(text)

    fun pasteTextFromClipboard() = clipboard.pasteFromClipboard()

    internal fun awakenScrollbars(): Boolean = awakenScrollBars()

    override fun onTouchEvent(event: MotionEvent) = touchHandler.onTouchEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent) = touchHandler.onGenericMotionEvent(event)

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalImeConnection(this)
    }

    override fun onCheckIsTextEditor() = true

    fun toggleIme(show: Boolean? = null) = imeController.toggleIme(show)

    /**
     * 外部修饰键状态读取器（如来自屏幕扩展按键栏）。
     * 设置后，[onKeyDown] 会同时参考 [KeyEvent] 元状态和此读取器，
     * 使粘性修饰键也作用于物理键盘输入。
     */
    var extraKeysModifierReader: (() -> ExtraKeysModifierSnapshot)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (this.isSelectingText) {
                stopTextSelectionMode()
                return true
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyProcessor.onKeyDown(keyCode, event)) {
            KeyInputProcessor.KeyDownResult.HANDLED -> true
            KeyInputProcessor.KeyDownResult.NOT_HANDLED -> false
            KeyInputProcessor.KeyDownResult.PASS_TO_SUPER -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (mEmulator == null && keyCode != KeyEvent.KEYCODE_BACK) return true
        if (event.isSystem) return super.onKeyUp(keyCode, event)
        return true
    }

    fun inputCodePoint(
        eventSource: Int,
        codePoint: Int,
        controlDownFromEvent: Boolean,
        leftAltDownFromEvent: Boolean
    ) = keyProcessor.inputCodePoint(eventSource, codePoint, controlDownFromEvent, leftAltDownFromEvent)

    internal fun handleKeyCode(keyCode: Int, keyMod: Int): Boolean = keyProcessor.handleKeyCode(keyCode, keyMod)

    override fun isOpaque() = true

    override fun onDraw(canvas: Canvas) {
        val emulator = mEmulator ?: return
        val resolver = currentPalette ?: return
        canvas.drawColor(resolver.background)
        val sel = mDefaultSelectors
        textSelectionCursorController.getSelectors(sel)
        synchronized(emulator) {
            mRenderer.render(emulator, resolver, canvas, this.topRow, sel[0], sel[1], sel[2], sel[3])
        }
        textSelectionCursorController.render()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateSize()
    }

    private fun updateSize() {
        val currentSession = currentSession ?: return
        val emulator = currentSession.emulator
        val viewWidth = width
        val viewHeight = height
        if (viewWidth == 0 || viewHeight == 0) return

        val newColumns = max(4, (viewWidth / mRenderer.fontWidth).toInt())
        val newRows = max(4, (viewHeight - mRenderer.mFontLineSpacingAndAscent) / mRenderer.fontLineSpacing)

        if (newColumns != emulator.mColumns || newRows != emulator.mRows) {
            currentSession.updateSize(newColumns, newRows, mRenderer.fontWidth.toInt(), mRenderer.fontLineSpacing)
            this.topRow = 0
            scrollTo(0, 0)
            invalidate()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        mEmulator?.onWindowFocusChanged(hasWindowFocus)

        // 当窗口失去焦点时，自动关闭文本选择
        if (!hasWindowFocus && isSelectingText) {
            stopTextSelectionMode()
        }
    }

    // 当 View 自身失去焦点时，自动关闭文本选择
    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus && isSelectingText) {
            stopTextSelectionMode()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnTouchModeChangeListener(textSelectionCursorController)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        currentSession = null
        viewTreeObserver.removeOnTouchModeChangeListener(textSelectionCursorController)
        scope.coroutineContext.cancelChildren()
    }

    fun dispose() {
        currentSession = null
        viewTreeObserver.removeOnTouchModeChangeListener(textSelectionCursorController)
        scope.cancel() 
    }

    init {
        isVerticalScrollBarEnabled = true
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt().and(-2)
}
