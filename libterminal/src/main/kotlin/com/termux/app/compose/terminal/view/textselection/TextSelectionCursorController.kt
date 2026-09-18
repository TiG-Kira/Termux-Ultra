package com.termux.app.compose.terminal.view.textselection

import android.content.ClipboardManager
import android.graphics.Rect
import android.text.TextUtils
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import com.termux.app.compose.terminal.R
import com.termux.app.compose.terminal.engine.buffer.TerminalBuffer
import com.termux.app.compose.terminal.text.forEachColumn
import com.termux.app.compose.terminal.view.ActionModeItem
import com.termux.app.compose.terminal.view.TerminalView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class TextSelectionCursorController(private val terminalView: TerminalView) : CursorController {
    private val mStartHandle: TextSelectionHandleView =
        TextSelectionHandleView(terminalView, this, TextSelectionHandleView.LEFT)
    private val mEndHandle: TextSelectionHandleView =
        TextSelectionHandleView(terminalView, this, TextSelectionHandleView.RIGHT)
    private var mIsSelectingText = false
    private var mShowStartTime = System.currentTimeMillis()

    private val mHandleHeight: Int = max(mStartHandle.handleHeight, mEndHandle.handleWidth)
    private var mSelX1 = -1
    private var mSelX2 = -1
    private var mSelY1 = -1
    private var mSelY2 = -1

    var actionMode: ActionMode? = null
        private set

    /** 当前 ActionMode 中注册的额外菜单项（与菜单 ID 一一对应）。 */
    private var customItems: List<ActionModeItem> = emptyList()

    override fun show(event: MotionEvent) {
        // 触发选择的瞬间最先冻结屏幕，再执行定位等其它操作，避免冻结前的间隙里后台输出使选区漂移
        terminalView.mEmulator?.let { emulator ->
            synchronized(emulator) { emulator.setInputPaused(true) }
        }

        setInitialTextSelectionPosition(event)

        mShowStartTime = System.currentTimeMillis()
        mIsSelectingText = true

        render()
        setActionModeCallBacks()
    }

    override fun hide(): Boolean {
        if (!isActive) return false

        // 防止在 show 调用后立即 hide（如长按方向键）
        if (System.currentTimeMillis() - mShowStartTime < 300) {
            return false
        }

        mStartHandle.hide()
        mEndHandle.hide()

        actionMode?.finish()
        actionMode = null

        mSelY2 = -1
        mSelX2 = -1
        mSelY1 = -1
        mSelX1 = -1
        mIsSelectingText = false

        // 退出选择模式即解除冻结，按序重放冻结期间的输出并恢复实时
        terminalView.mEmulator?.let { emulator ->
            synchronized(emulator) { emulator.setInputPaused(false) }
        }

        return true
    }

    override fun render() {
        if (!isActive) return

        val left = terminalView.paddingLeft
        val right = terminalView.width - terminalView.paddingRight
        val top = terminalView.paddingTop
        val bottom = terminalView.height - terminalView.paddingBottom

        // 提取统一渲染逻辑，利用 Kotlin 的局部函数减少重复代码
        fun layoutHandle(
            handle: TextSelectionHandleView,
            selX: Int,
            selY: Int,
            isStart: Boolean
        ) {
            // 注意：结束光标 (isStart=false) 需要停在所选字符的末尾，即 selX + 1
            val pointX = terminalView.getPointX(if (isStart) selX else selX + 1)
            val pointY = terminalView.getPointY(selY + 1)

            val isVisible = pointX in left..right && pointY in top..bottom
            val orientation = when {
                pointX - handle.handleWidth < left -> TextSelectionHandleView.RIGHT
                pointX + handle.handleWidth > right -> TextSelectionHandleView.LEFT
                else -> if (isStart) TextSelectionHandleView.LEFT else TextSelectionHandleView.RIGHT
            }
            
            handle.updateLayout(pointX, pointY, isVisible, orientation)
        }

        // 统一调用，消灭冗余代码
        layoutHandle(mStartHandle, mSelX1, mSelY1, isStart = true)
        layoutHandle(mEndHandle, mSelX2, mSelY2, isStart = false)

        actionMode?.invalidate()
    }

    fun setInitialTextSelectionPosition(event: MotionEvent) {
        val emulator = terminalView.mEmulator!!

        synchronized(emulator) {
            val screen = emulator.screen

            val coord = terminalView.getColumnAndRow(event, true)
            mSelX2 = coord.col
            mSelY2 = coord.row
            mSelX1 = coord.col
            mSelY1 = coord.row

            if (" " != screen.getSelectedText(mSelX1, mSelY1, mSelX1, mSelY1)) {
                // 选中的不是空白字符，扩展为单词选择。
                // 单词可能跨折行行续到下一行，且折行处无空格：扩展需跨越行边界。
                // 左扩：
                while (true) {
                    if (mSelX1 > 0) {
                        // 用 isCellBlank 轻量检测，避免逐格构造字符串的性能开销
                        if (!screen.isCellBlank(mSelX1 - 1, mSelY1)) {
                            mSelX1--
                        } else break
                    } else if (mSelY1 - 1 >= -screen.activeTranscriptRows &&
                        screen.getLineWrap(mSelY1 - 1) &&
                        // 上一行折行且行尾格非空白才跨行，避免把空白格兜进选区
                        !screen.isCellBlank(emulator.mColumns - 1, mSelY1 - 1)
                    ) {
                        // 当前行是上一行的折行继续行，跳回上一行行尾继续扩展
                        mSelY1--
                        mSelX1 = emulator.mColumns - 1
                    } else break
                }
                // 右扩：
                while (true) {
                    if (mSelX2 < emulator.mColumns - 1) {
                        if (!screen.isCellBlank(mSelX2 + 1, mSelY2)) {
                            mSelX2++
                        } else break
                    } else if (mSelY2 < emulator.mRows - 1 &&
                        screen.getLineWrap(mSelY2) &&
                        // 下一行折行起始格非空白才跨行，避免把空白格兜进选区
                        !screen.isCellBlank(0, mSelY2 + 1)
                    ) {
                        // 当前行折行到下一行，跳到下一行列 0 继续扩展
                        mSelY2++
                        mSelX2 = 0
                    } else break
                }
            }
        }
    }

    fun setActionModeCallBacks() {
        val callback: ActionMode.Callback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu): Boolean {
                val show = MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT
                val clipboard = terminalView.context.getSystemService(ClipboardManager::class.java)
                val customizer = terminalView.actionModeCustomizer

                val copyLabel = customizer?.copyText() ?: terminalView.context.getString(R.string.copy_text)
                val pasteLabel = customizer?.pasteText() ?: terminalView.context.getString(R.string.paste_text)

                menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, copyLabel)
                    .setShowAsAction(show)
                menu.add(Menu.NONE, ACTION_PASTE, Menu.NONE, pasteLabel)
                    .setEnabled(clipboard.hasPrimaryClip())
                    .setShowAsAction(show)

                customItems = customizer?.createActionModeItems() ?: emptyList()
                customItems.forEachIndexed { index, item ->
                    menu.add(Menu.NONE, ACTION_CUSTOM_BASE + index, Menu.NONE, item.title)
                        .apply { item.icon?.let { setIcon(it) } }
                        .setShowAsAction(show)
                }
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem): Boolean {
                if (!isActive) return true

                when (item.itemId) {
                    ACTION_COPY -> {
                        terminalView.copyTextToClipboard()
                        terminalView.stopTextSelectionMode()
                    }
                    ACTION_PASTE -> {
                        terminalView.stopTextSelectionMode()
                        terminalView.pasteTextFromClipboard()
                    }
                    else -> {
                        val customIndex = item.itemId - ACTION_CUSTOM_BASE
                        if (customIndex in customItems.indices) {
                            val selected = selectedText
                            customItems[customIndex].onClick(selected)
                            terminalView.stopTextSelectionMode()
                        }
                    }
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode?) {
                if (actionMode === mode) {
                    actionMode = null
                    customItems = emptyList()
                    terminalView.stopTextSelectionMode()
                }
            }
        }

        this.actionMode = terminalView.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = callback.onCreateActionMode(mode, menu)
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = callback.onActionItemClicked(mode, item)
            override fun onDestroyActionMode(mode: ActionMode?) = callback.onDestroyActionMode(mode)

            override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: Rect) {
                var x1 = (mSelX1 * terminalView.mRenderer.fontWidth).roundToInt()
                var x2 = ((mSelX2 + 1) * terminalView.mRenderer.fontWidth).roundToInt()
                val top = ((mSelY1 - terminalView.topRow) * terminalView.mRenderer.fontLineSpacing).toFloat().roundToInt()
                var bottom = ((mSelY2 + 1 - terminalView.topRow) * terminalView.mRenderer.fontLineSpacing).toFloat().roundToInt()

                if (x1 > x2) {
                    x1 = x2.also { x2 = x1 }
                }

                val terminalBottom = terminalView.bottom
                bottom += mHandleHeight
                if (bottom > terminalBottom) bottom = terminalBottom

                outRect.set(x1, top, x2, bottom)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    override fun updatePosition(handle: TextSelectionHandleView, x: Int, y: Int) {
        val emulator = terminalView.mEmulator!!
        
        synchronized(emulator) {
            val screen = emulator.screen
            val scrollRows = screen.activeRows - emulator.mRows
            val isStart = handle === mStartHandle

            // 1. 获取光标基础坐标并进行边界约束
            var curX = max(0, terminalView.getCursorX(x.toFloat()))
            var curY = terminalView.getCursorY(y.toFloat())
                .coerceIn(-scrollRows, emulator.mRows - 1)

            // 2. 互斥检查与值更新 (将 mSelX1/mSelY1 和 mSelX2/mSelY2 的交叉修改进行精简)
            if (isStart) {
                if (curY > mSelY2 || (curY == mSelY2 && curX > mSelX2)) {
                    curY = mSelY2
                    curX = mSelX2
                }
                mSelY1 = curY
                mSelX1 = curX
            } else {
                if (curY < mSelY1 || (curY == mSelY1 && curX < mSelX1)) {
                    curY = mSelY1
                    curX = mSelX1
                }
                mSelY2 = curY
                mSelX2 = curX
            }

            // 3. 处理超出屏幕的滚动逻辑
            if (!emulator.isAlternateBufferActive) {
                var topRow = terminalView.topRow
                if (curY <= topRow) {
                    topRow = max(-scrollRows, topRow - 1)
                } else if (curY >= topRow + emulator.mRows) {
                    topRow = min(0, topRow + 1)
                }
                terminalView.topRow = topRow
            }

            // 4. 校准中文字符/宽字符对齐
            if (isStart) {
                mSelX1 = getValidCurX(screen, mSelY1, mSelX1)
            } else {
                mSelX2 = getValidCurX(screen, mSelY2, mSelX2)
            }
        }

        terminalView.invalidate()
    }

    private fun getValidCurX(screen: TerminalBuffer, cy: Int, cx: Int): Int {
        val line = screen.getSelectedText(0, cy, cx, cy)
        if (!TextUtils.isEmpty(line)) {
            line.forEachColumn { _, col, cp, wc, _ ->
                if (cp == 0) return@forEachColumn false
                val cend = col + wc
                if (cx in (col + 1)..<cend) return cend
                if (cend == col) return col
                true
            }
        }
        return cx
    }

    fun decrementYTextSelectionCursors(decrement: Int) {
        mSelY1 -= decrement
        mSelY2 -= decrement
    }

    override fun onTouchEvent(event: MotionEvent) = false

    override fun onTouchModeChanged(isInTouchMode: Boolean) {
        if (!isInTouchMode) terminalView.stopTextSelectionMode()
    }

    override val isActive: Boolean
        get() = mIsSelectingText

    fun getSelectors(sel: IntArray?) {
        if (sel == null || sel.size != 4) return
        sel[0] = mSelY1
        sel[1] = mSelY2
        sel[2] = mSelX1
        sel[3] = mSelX2
    }

    val selectedText: String
        get() {
            val emulator = terminalView.mEmulator ?: return ""
            synchronized(emulator) {
                return emulator.getSelectedText(mSelX1, mSelY1, mSelX2, mSelY2)
            }
        }

    companion object {
        const val ACTION_COPY: Int = 1
        const val ACTION_PASTE: Int = 2
        const val ACTION_CUSTOM_BASE: Int = 10000
    }
}