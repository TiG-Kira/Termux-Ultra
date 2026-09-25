package com.termux.app.compose

import android.content.Context

/**
 * 终端运行核心管理。
 *
 * libterminal 常态化接管会话管理：
 * - 运行核心固定（Compose 会话管理）。
 * - [isComposeMode] 恒为 true，调用方无需分支判断。
 * - 不再提供运行核心切换；插件（Boot/Tasker/Widget）由 TermuxService 启动时恢复常开。
 */
object TerminalRuntimeCore {

    enum class Core(val value: String) {
        /* value 为存档偏好兼容值，不再切换；恒为 KOTLIN_COMPOSE。 */
        JAVA_NDK("java_ndk"),
        KOTLIN_COMPOSE("kotlin_compose");

        /** 显示名称（当前实现下即为 LibTerminal）。 */
        fun displayName(context: Context): String = when (this) {
            JAVA_NDK -> context.getString(com.termux.R.string.core_name_classic)
            KOTLIN_COMPOSE -> context.getString(com.termux.R.string.core_name_libterminal)
        }

        companion object {
            fun fromValue(v: String): Core = entries.firstOrNull { it.value == v } ?: JAVA_NDK
        }
    }

    fun getCurrent(context: Context): Core = Core.KOTLIN_COMPOSE

    /** 是否走 Compose 会话管理。恒为 true。 */
    @JvmStatic
    fun isComposeMode(context: Context): Boolean = true
}