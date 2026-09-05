package com.termux.app.compose.terminal.view.textselection

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import com.termux.app.compose.terminal.R
import com.termux.app.compose.terminal.view.TerminalView
import kotlin.math.roundToInt

internal class TextSelectionHandleView(
    private val terminalView: TerminalView,
    private val mCursorController: CursorController,
    private val mInitialOrientation: Int
) : View(terminalView.context) {
    
    private var mHandle: PopupWindow? = null
    private lateinit var mHandleDrawable: Drawable

    var isDragging: Boolean = false
        private set

    val mTempCoords: IntArray = IntArray(2)

    private var mPointX = 0
    private var mPointY = 0
    private var mTouchToWindowOffsetX = 0f
    private var mTouchToWindowOffsetY = 0f
    private var mHotspotX = 0f
    private var mHotspotY = 0f
    private var mTouchOffsetY = 0f
    private var mLastParentX = 0
    private var mLastParentY = 0

    var handleHeight: Int = 0
        private set
    var handleWidth: Int = 0
        private set

    private var mOrientation = 0

    init {
        setOrientation(mInitialOrientation)
    }

    private fun initHandle() {
        mHandle = PopupWindow(
            terminalView.context, null,
            android.R.attr.textSelectHandleWindowStyle
        ).also {
            it.isSplitTouchEnabled = true
            it.isClippingEnabled = false
            it.width = ViewGroup.LayoutParams.WRAP_CONTENT
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
            it.setBackgroundDrawable(null)
            it.animationStyle = 0
            it.windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
            it.enterTransition = null
            it.exitTransition = null
            it.contentView = this
        }
    }

    fun setOrientation(orientation: Int) {
        mOrientation = orientation
        var handleWidth = 0
        when (orientation) {
            LEFT -> {
                mHandleDrawable = getDrawable(context, R.drawable.text_select_handle_left_material)!!
                handleWidth = mHandleDrawable.intrinsicWidth
                mHotspotX = (handleWidth * 3) / 4f
            }
            RIGHT -> {
                mHandleDrawable = getDrawable(context, R.drawable.text_select_handle_right_material)!!
                handleWidth = mHandleDrawable.intrinsicWidth
                mHotspotX = handleWidth / 4f
            }
        }

        this.handleHeight = mHandleDrawable.intrinsicHeight
        this.handleWidth = handleWidth
        mTouchOffsetY = -this.handleHeight * 0.3f
        mHotspotY = 0f
        invalidate()
    }

    fun show() {
        removeFromParent()
        initHandle()
        invalidate()

        val coords = mTempCoords
        terminalView.getLocationInWindow(coords)
        coords[0] += mPointX
        coords[1] += mPointY

        mHandle?.showAtLocation(terminalView, 0, coords[0], coords[1])
    }

    fun hide() {
        this.isDragging = false
        mHandle?.let {
            it.dismiss()
            removeFromParent()
        }
        mHandle = null
        invalidate()
    }

    fun removeFromParent() {
        if (this.parent != null) {
            (this.parent as ViewGroup).removeView(this)
        }
    }

    // [新增] 统一更新布局的方法，剥离计算逻辑，由外部直接控制
    fun updateLayout(x: Int, y: Int, isVisible: Boolean, orientation: Int) {
        val oldHotspotX = mHotspotX
        if (mOrientation != orientation) {
            setOrientation(orientation)
        }
        
        mPointX = (x - (if (this.isShowing) oldHotspotX else mHotspotX)).toInt()
        mPointY = y

        if (isVisible || isDragging) {
            var coords: IntArray? = null

            if (this.isShowing) {
                coords = mTempCoords
                terminalView.getLocationInWindow(coords)
                val x1 = coords[0] + mPointX
                val y1 = coords[1] + mPointY
                mHandle?.update(x1, y1, width, height)
            } else {
                show()
            }

            if (this.isDragging) {
                if (coords == null) {
                    coords = mTempCoords
                    terminalView.getLocationInWindow(coords)
                }
                if (coords[0] != mLastParentX || coords[1] != mLastParentY) {
                    mTouchToWindowOffsetX += (coords[0] - mLastParentX).toFloat()
                    mTouchToWindowOffsetY += (coords[1] - mLastParentY).toFloat()
                    mLastParentX = coords[0]
                    mLastParentY = coords[1]
                }
            }
        } else {
            hide()
        }
    }

    public override fun onDraw(c: Canvas) {
        val width = mHandleDrawable.intrinsicWidth
        val height = mHandleDrawable.intrinsicHeight
        mHandleDrawable.setBounds(0, 0, width, height)
        mHandleDrawable.draw(c)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        terminalView.updateFloatingToolbarVisibility(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val rawX = event.rawX
                val rawY = event.rawY
                mTouchToWindowOffsetX = rawX - mPointX
                mTouchToWindowOffsetY = rawY - mPointY
                val coords = mTempCoords
                terminalView.getLocationInWindow(coords)
                mLastParentX = coords[0]
                mLastParentY = coords[1]
                this.isDragging = true
            }

            MotionEvent.ACTION_MOVE -> {
                val rawX = event.rawX
                val rawY = event.rawY
                val newPosX = rawX - mTouchToWindowOffsetX + mHotspotX
                val newPosY = rawY - mTouchToWindowOffsetY + mHotspotY + mTouchOffsetY

                mCursorController.updatePosition(this, newPosX.roundToInt(), newPosY.roundToInt())
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> this.isDragging = false
        }
        return true
    }

    public override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(mHandleDrawable.intrinsicWidth, mHandleDrawable.intrinsicHeight)
    }

    val isShowing: Boolean get() = mHandle?.isShowing ?: false

    companion object {
        const val LEFT: Int = 0
        const val RIGHT: Int = 2
    }
}