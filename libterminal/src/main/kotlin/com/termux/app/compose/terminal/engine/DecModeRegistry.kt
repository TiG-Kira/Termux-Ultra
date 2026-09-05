package com.termux.app.compose.terminal.engine

// DEC 私有模式内部位号（与外部 DECSET 位号不同的内部编码）
internal const val DECSET_BIT_APPLICATION_CURSOR_KEYS = 1
internal const val DECSET_BIT_REVERSE_VIDEO = 1 shl 1
internal const val DECSET_BIT_ORIGIN_MODE = 1 shl 2
internal const val DECSET_BIT_AUTOWRAP = 1 shl 3
internal const val DECSET_BIT_CURSOR_ENABLED = 1 shl 4
internal const val DECSET_BIT_APPLICATION_KEYPAD = 1 shl 5
internal const val DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE = 1 shl 6
internal const val DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT = 1 shl 7
internal const val DECSET_BIT_MOUSE_TRACKING_ANY_EVENT = 1 shl 13
internal const val DECSET_BIT_SEND_FOCUS_EVENTS = 1 shl 8
internal const val DECSET_BIT_MOUSE_PROTOCOL_SGR = 1 shl 9
internal const val DECSET_BIT_BRACKETED_PASTE_MODE = 1 shl 10
internal const val DECSET_BIT_LEFTRIGHT_MARGIN_MODE = 1 shl 11
internal const val DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE = 1 shl 12

/**
 * DEC 私有模式位状态注册表。
 *
 * 集中持有当前/已保存的模式位集合（DECSET/DECRST），并封装位操作、
 * 鼠标追踪模式互斥、外部 DEC 位号到内部位的映射、以及 CSI ? r/? s 的
 * 任意模式保存/恢复。纯状态对象，由 [TerminalEmulator] 持有并驱动。
 */
internal class DecModeRegistry {

    private var currentFlags = 0
    private var savedFlags = 0

    /** 查询某个内部位是否已设置。 */
    fun isSet(bit: Int): Boolean = (currentFlags and bit) != 0

    /**
     * 设置或清除某个内部位。
     *
     * 鼠标追踪模式（1000/1002/1003）互斥：开启任一模式时清除其余两个。
     */
    fun set(bit: Int, value: Boolean) {
        if (value) {
            when (bit) {
                DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE,
                DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT,
                DECSET_BIT_MOUSE_TRACKING_ANY_EVENT -> {
                    if (bit != DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) {
                        set(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE, false)
                    }
                    if (bit != DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT) {
                        set(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT, false)
                    }
                    if (bit != DECSET_BIT_MOUSE_TRACKING_ANY_EVENT) {
                        set(DECSET_BIT_MOUSE_TRACKING_ANY_EVENT, false)
                    }
                }
            }
        }
        currentFlags = if (value) currentFlags or bit else currentFlags and bit.inv()
    }

    /** 当前全部模式位快照。 */
    val current: Int get() = currentFlags

    /** 清除并复位为默认状态：仅保留自动换行与光标可见。 */
    fun resetDefault() {
        currentFlags = 0
        savedFlags = 0
        set(DECSET_BIT_AUTOWRAP, true)
        set(DECSET_BIT_CURSOR_ENABLED, true)
    }

    /** 仅恢复自动换行与原点模式两个位（光标恢复专用）。 */
    fun restoreAutowrapAndOrigin(saved: Int) {
        val mask = DECSET_BIT_AUTOWRAP or DECSET_BIT_ORIGIN_MODE
        currentFlags = (currentFlags and mask.inv()) or (saved and mask)
    }

    /** CSI ? s：保存某个内部位。 */
    fun saveModeBit(bit: Int) {
        savedFlags = savedFlags or bit
    }

    /** CSI ? r：查询某个内部位是否被保存。 */
    fun isSaved(bit: Int): Boolean = (savedFlags and bit) != 0

    /** 将外部 DEC 位号映射为内部位，未知位号返回 -1。 */
    fun mapExternalToInternal(decsetBit: Int): Int = when (decsetBit) {
        1 -> DECSET_BIT_APPLICATION_CURSOR_KEYS
        5 -> DECSET_BIT_REVERSE_VIDEO
        6 -> DECSET_BIT_ORIGIN_MODE
        7 -> DECSET_BIT_AUTOWRAP
        25 -> DECSET_BIT_CURSOR_ENABLED
        66 -> DECSET_BIT_APPLICATION_KEYPAD
        69 -> DECSET_BIT_LEFTRIGHT_MARGIN_MODE
        1000 -> DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE
        1002 -> DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT
        1003 -> DECSET_BIT_MOUSE_TRACKING_ANY_EVENT
        1004 -> DECSET_BIT_SEND_FOCUS_EVENTS
        1006 -> DECSET_BIT_MOUSE_PROTOCOL_SGR
        2004 -> DECSET_BIT_BRACKETED_PASTE_MODE
        else -> -1
    }
}
