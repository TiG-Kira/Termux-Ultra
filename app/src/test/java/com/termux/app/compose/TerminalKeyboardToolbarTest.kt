package com.termux.app.compose

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端小键盘工具栏布局与滚动判定单元测试。
 * 验证在各种屏幕尺寸、显示缩放及自定义列数下，按键等分宽度与横向滚动的计算正确性，
 * 彻底防止小屏设备或多列键盘按键被截断且无法滚动的缺陷（Issue #ISSUE-001）。
 */
class TerminalKeyboardToolbarTest {

    @Test
    fun testStandardPhoneWidth_12Columns_needsScroll() {
        // 360dp 手机默认 12 列按键：
        // available = 360 - 12 = 348dp
        // gap = 3 * 11 = 33dp
        // fitWidth = (348 - 33) / 12 = 315 / 12 = 26.25dp < 28dp
        // 必须开启横向滚动，避免最右侧按键（BKSP/END/PGDN）被屏幕截断
        val result = TerminalKeyboardLayoutCalculator.calculate(360.dp, 12)
        assertTrue("On 360dp screen with 12 keys, needsScroll must be true", result.needsScroll)
        assertEquals(28.dp, result.keyWidth)
        val expectedContentWidth = 28.dp * 12 + 3.dp * 11 + 6.dp * 2 // 336 + 33 + 12 = 381dp
        assertEquals(expectedContentWidth, result.contentWidth)
    }

    @Test
    fun testWidePhoneWidth_12Columns_fitsWithoutScroll() {
        // 411dp 手机（如 Pixel 系列）12 列按键：
        // available = 411 - 12 = 399dp
        // gap = 33dp
        // fitWidth = (399 - 33) / 12 = 366 / 12 = 30.5dp >= 28dp
        // 宽度充足，等分铺满，无需滚动
        val result = TerminalKeyboardLayoutCalculator.calculate(411.dp, 12)
        assertFalse("On 411dp screen with 12 keys, needsScroll must be false", result.needsScroll)
        assertEquals(30.5.dp, result.keyWidth)
        assertEquals(411.dp, result.contentWidth)
    }

    @Test
    fun testSmallPhone_needsScroll() {
        // 320dp 紧凑型手机或开启大显示缩放：
        val result = TerminalKeyboardLayoutCalculator.calculate(320.dp, 12)
        assertTrue(result.needsScroll)
        assertEquals(28.dp, result.keyWidth)
        assertEquals(381.dp, result.contentWidth)
    }

    @Test
    fun testFewColumns_fitsEvenly() {
        // 360dp 屏幕上自定义 6 列按键：
        // available = 348dp, gap = 15dp, fitWidth = 333 / 6 = 55.5dp
        val result = TerminalKeyboardLayoutCalculator.calculate(360.dp, 6)
        assertFalse(result.needsScroll)
        assertEquals(55.5.dp, result.keyWidth)
        assertEquals(360.dp, result.contentWidth)
    }

    @Test
    fun testZeroColumns_gracefulHandling() {
        val result = TerminalKeyboardLayoutCalculator.calculate(360.dp, 0)
        assertFalse(result.needsScroll)
        assertEquals(360.dp, result.contentWidth)
    }
}
