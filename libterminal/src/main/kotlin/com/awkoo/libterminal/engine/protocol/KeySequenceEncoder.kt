package com.awkoo.libterminal.engine.protocol

import android.view.KeyEvent

/**
 * 键盘能力编码器：将 terminfo/termcap 能力名或 Android KeyCode 编码为发往 shell 的转义序列。
 *
 * 供 XTGETTCAP（[DeviceControlHandler]）与视图层按键输入（[com.awkoo.libterminal.view.input.KeyInputProcessor]）使用。
 * 纯字符串生成逻辑，仅依赖 KeyEvent 的编译期 int 常量，不依赖 Android 视图层。
 */
internal object KeySequenceEncoder {
    const val KEYMOD_ALT: Int = -0x80000000
    const val KEYMOD_CTRL: Int = 0x40000000
    const val KEYMOD_SHIFT: Int = 0x20000000
    const val KEYMOD_NUM_LOCK: Int = 0x10000000

    // terminfo/termcap 键名到 Android KeyCode 的映射
    private val TERMCAP_TO_KEYCODE = mapOf(
        // terminfo: http://pubs.opengroup.org/onlinepubs/7990989799/xcurses/terminfo.html
        // termcap: http://man7.org/linux/man-pages/man5/termcap.5.html
        "%i" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_DPAD_RIGHT),
        "#2" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_MOVE_HOME),
        "#4" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_DPAD_LEFT),
        "*7" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_MOVE_END),
        "k1" to KeyEvent.KEYCODE_F1,
        "k2" to KeyEvent.KEYCODE_F2,
        "k3" to KeyEvent.KEYCODE_F3,
        "k4" to KeyEvent.KEYCODE_F4,
        "k5" to KeyEvent.KEYCODE_F5,
        "k6" to KeyEvent.KEYCODE_F6,
        "k7" to KeyEvent.KEYCODE_F7,
        "k8" to KeyEvent.KEYCODE_F8,
        "k9" to KeyEvent.KEYCODE_F9,
        "k;" to KeyEvent.KEYCODE_F10,
        "F1" to KeyEvent.KEYCODE_F11,
        "F2" to KeyEvent.KEYCODE_F12,
        "F3" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_F1),
        "F4" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_F2),
        "F5" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_F3),
        "F6" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_F4),
        "F7" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_F5),
        "F8" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_F6),
        "F9" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_F7),
        "FA" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_F8),
        "FB" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_F9),
        "FC" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_F10),
        "FD" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_F11),
        "FE" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_F12),

        "kb" to KeyEvent.KEYCODE_DEL, // 退格键

        "kd" to KeyEvent.KEYCODE_DPAD_DOWN, // terminfo=kcud1，下方向键
        "kh" to KeyEvent.KEYCODE_MOVE_HOME,
        "kl" to KeyEvent.KEYCODE_DPAD_LEFT,
        "kr" to KeyEvent.KEYCODE_DPAD_RIGHT,

        // K1=小键盘左上角：
        // t_K1 <kHome> 小键盘 Home
        // t_K3 <kPageUp> 小键盘 PageUp
        // t_K4 <kEnd> 小键盘 End
        // t_K5 <kPageDown> 小键盘 PageDown
        "K1" to KeyEvent.KEYCODE_MOVE_HOME,
        "K3" to KeyEvent.KEYCODE_PAGE_UP,
        "K4" to KeyEvent.KEYCODE_MOVE_END,
        "K5" to KeyEvent.KEYCODE_PAGE_DOWN,

        "ku" to KeyEvent.KEYCODE_DPAD_UP,

        "kB" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_TAB), // termcap=kB, terminfo=kcbt：反向制表
        "kD" to KeyEvent.KEYCODE_FORWARD_DEL, // terminfo=kdch1，删除字符键
        "kDN" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_DPAD_DOWN), // 非标准：Shift+下
        "kF" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_DPAD_DOWN), // terminfo=kind，向下滚动键
        "kI" to KeyEvent.KEYCODE_INSERT,
        "kP" to KeyEvent.KEYCODE_PAGE_UP,
        "kN" to KeyEvent.KEYCODE_PAGE_DOWN,
        "kR" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_DPAD_UP), // terminfo=kri，向上滚动键
        "kUP" to (KEYMOD_SHIFT or KeyEvent.KEYCODE_DPAD_UP), // 非标准：Shift+上

        "@7" to KeyEvent.KEYCODE_MOVE_END,
        "@8" to KeyEvent.KEYCODE_NUMPAD_ENTER
    )

    @JvmStatic
    fun getCodeFromTermcap(
        termcap: String,
        cursorKeysApplication: Boolean,
        keypadApplication: Boolean
    ): String? {
        val keyCodeAndMod = TERMCAP_TO_KEYCODE[termcap] ?: return null
        val KEY_MOD_MASK = KEYMOD_ALT or KEYMOD_CTRL or KEYMOD_SHIFT or KEYMOD_NUM_LOCK
        val keyCode = keyCodeAndMod and KEY_MOD_MASK.inv()
        val keyMod = keyCodeAndMod and KEY_MOD_MASK

        return getCode(keyCode, keyMod, cursorKeysApplication, keypadApplication)
    }

    @JvmStatic
    fun getCode(
        keyCode: Int,
        keyMode: Int,
        cursorApp: Boolean,
        keypadApplication: Boolean
    ): String? {
        val numLockOn = (keyMode and KEYMOD_NUM_LOCK) != 0
        val keyMode = keyMode and KEYMOD_NUM_LOCK.inv()

        fun buildCursor(cursorC: Char, appModeC: Char): String {
            return if (keyMode == 0) {
                if (cursorApp) "\u001bO$appModeC" else "\u001b[$cursorC"
            } else {
                transformForModifiers("\u001b[1", keyMode, cursorC)
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> "\u000d"

            KeyEvent.KEYCODE_DPAD_UP -> buildCursor('A', 'A')
            KeyEvent.KEYCODE_DPAD_DOWN -> buildCursor('B', 'B')
            KeyEvent.KEYCODE_DPAD_RIGHT -> buildCursor('C', 'C')
            KeyEvent.KEYCODE_DPAD_LEFT -> buildCursor('D', 'D')
            KeyEvent.KEYCODE_MOVE_HOME -> buildCursor('H', 'H')
            KeyEvent.KEYCODE_MOVE_END -> buildCursor('F', 'F')

            KeyEvent.KEYCODE_F1 -> if (keyMode == 0) "\u001bOP" else transformForModifiers("\u001b[1", keyMode, 'P')
            KeyEvent.KEYCODE_F2 -> if (keyMode == 0) "\u001bOQ" else transformForModifiers("\u001b[1", keyMode, 'Q')
            KeyEvent.KEYCODE_F3 -> if (keyMode == 0) "\u001bOR" else transformForModifiers("\u001b[1", keyMode, 'R')
            KeyEvent.KEYCODE_F4 -> if (keyMode == 0) "\u001bOS" else transformForModifiers("\u001b[1", keyMode, 'S')

            KeyEvent.KEYCODE_F5 -> transformForModifiers("\u001b[15", keyMode, '~')
            KeyEvent.KEYCODE_F6 -> transformForModifiers("\u001b[17", keyMode, '~')
            KeyEvent.KEYCODE_F7 -> transformForModifiers("\u001b[18", keyMode, '~')
            KeyEvent.KEYCODE_F8 -> transformForModifiers("\u001b[19", keyMode, '~')
            KeyEvent.KEYCODE_F9 -> transformForModifiers("\u001b[20", keyMode, '~')
            KeyEvent.KEYCODE_F10 -> transformForModifiers("\u001b[21", keyMode, '~')
            KeyEvent.KEYCODE_F11 -> transformForModifiers("\u001b[23", keyMode, '~')
            KeyEvent.KEYCODE_F12 -> transformForModifiers("\u001b[24", keyMode, '~')

            KeyEvent.KEYCODE_SYSRQ -> "\u001b[32~" // 系统请求 / 打印
            KeyEvent.KEYCODE_BREAK -> "\u001b[34~" // 暂停 / 中断

            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> "\u001b"

            KeyEvent.KEYCODE_INSERT -> transformForModifiers("\u001b[2", keyMode, '~')
            KeyEvent.KEYCODE_FORWARD_DEL -> transformForModifiers("\u001b[3", keyMode, '~')
            KeyEvent.KEYCODE_PAGE_UP -> transformForModifiers("\u001b[5", keyMode, '~')
            KeyEvent.KEYCODE_PAGE_DOWN -> transformForModifiers("\u001b[6", keyMode, '~')

            KeyEvent.KEYCODE_DEL -> {
                // 与 xterm / gnome-terminal 行为一致：
                (if ((keyMode and KEYMOD_ALT) == 0) "" else "\u001b") +
                (if ((keyMode and KEYMOD_CTRL) == 0) "\u007F" else "\u0008")
            }

            KeyEvent.KEYCODE_NUM_LOCK -> if (keypadApplication) "\u001bOP" else null

            // 未按 Ctrl 时返回 null，走普通输入处理流程（可能写入组合重音符）：
            KeyEvent.KEYCODE_SPACE -> if ((keyMode and KEYMOD_CTRL) == 0) null else "\u0000"

            // Shift+Tab 发送反向制表：
            KeyEvent.KEYCODE_TAB -> if ((keyMode and KEYMOD_SHIFT) == 0) "\u0009" else "\u001b[Z"

            KeyEvent.KEYCODE_ENTER -> if ((keyMode and KEYMOD_ALT) == 0) "\r" else "\u001b\r"

            else -> handleNumpadKey(keyCode, keyMode, numLockOn, keypadApplication, ::buildCursor)
        }
    }

    private fun handleNumpadKey(
        keyCode: Int,
        keyMode: Int,
        numLockOn: Boolean,
        keypadApplication: Boolean,
        buildCursor: (Char, Char) -> String
    ): String? {
        fun buildNumpad(numLockKey: String, baseModCode: Char, alternativeC: Char): String {
            return if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001bO", keyMode, alternativeC) else numLockKey
            } else {
                buildCursor(baseModCode, baseModCode)
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_NUMPAD_ENTER -> if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'M') else "\n"
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'j') else "*"
            KeyEvent.KEYCODE_NUMPAD_ADD -> if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'k') else "+"

            KeyEvent.KEYCODE_NUMPAD_COMMA -> ","
            KeyEvent.KEYCODE_NUMPAD_DOT -> if (numLockOn) {
                if (keypadApplication) "\u001bOn" else "."
            } else {
                // 删除
                transformForModifiers("\u001b[3", keyMode, '~')
            }

            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'm') else "-"

            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'o') else "/"

            KeyEvent.KEYCODE_NUMPAD_0 -> if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'p') else "0"
            } else {
                // 插入
                transformForModifiers("\u001b[2", keyMode, '~')
            }

            KeyEvent.KEYCODE_NUMPAD_1 -> buildNumpad("1", 'F', 'q')
            KeyEvent.KEYCODE_NUMPAD_2 -> buildNumpad("2", 'B', 'r')

            KeyEvent.KEYCODE_NUMPAD_3 -> if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001bO", keyMode, 's') else "3"
            } else {
                // 向下翻页
                "\u001b[6~"
            }

            KeyEvent.KEYCODE_NUMPAD_4 -> buildNumpad("4", 'D', 't')

            KeyEvent.KEYCODE_NUMPAD_5 -> if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'u') else "5"

            KeyEvent.KEYCODE_NUMPAD_6 -> buildNumpad("6", 'C', 'v')
            KeyEvent.KEYCODE_NUMPAD_7 -> buildNumpad("7", 'H', 'w')
            KeyEvent.KEYCODE_NUMPAD_8 -> buildNumpad("8", 'A', 'x')

            KeyEvent.KEYCODE_NUMPAD_9 -> if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'y') else "9"
            } else {
                // 向上翻页
                "\u001b[5~"
            }

            KeyEvent.KEYCODE_NUMPAD_EQUALS -> if (keypadApplication) transformForModifiers("\u001bO", keyMode, 'X') else "="

            else -> null
        }
    }

    private fun transformForModifiers(start: String, keymod: Int, lastChar: Char): String {
        // 根据按键标准推导算数： base: 1,  Shift=+1,  Alt=+2,  Ctrl=+4
        var modifier = 1
        if ((keymod and KEYMOD_SHIFT) != 0) modifier += 1
        if ((keymod and KEYMOD_ALT) != 0) modifier += 2
        if ((keymod and KEYMOD_CTRL) != 0) modifier += 4

        // ==1 代表没有任何需要转义的修饰位命中
        return if (modifier == 1) {
            start + lastChar
        } else {
            "$start;$modifier$lastChar"
        }
    }
}