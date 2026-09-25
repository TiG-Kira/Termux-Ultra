package com.termux.app.compose

import android.content.Context

/**
 * 终端运行核心管理。
 *
 * 阶段 3 起单一引擎（Nova/libterminal）常态化：
 * - 运行核心恒为 KOTLIN_COMPOSE，不再提供 Java+NDK / Kotlin+Compose 切换。
 * - [isComposeMode] 恒为 true，调用方按 Compose 分支执行。
 * - 删除 setCurrent / applyPluginState / killAllSessions：插件（Boot/Tasker/Widget）
 *   由 TermuxService 启动时恢复常开，不再随运行核心状态联动。
 */
object TerminalRuntimeCore {

    enum class Core(val value: String) {
        JAVA_NDK("java_ndk"),
        KOTLIN_COMPOSE("kotlin_compose");

        /** 显示名称：中文 经典/新星，英文 Classic/Nova（跟随应用语言） */
        fun displayName(context: Context): String = when (this) {
            JAVA_NDK -> context.getString(com.termux.R.string.core_name_classic)
            KOTLIN_COMPOSE -> context.getString(com.termux.R.string.core_name_nova)
        }

        companion object {
            fun fromValue(v: String): Core = entries.firstOrNull { it.value == v } ?: JAVA_NDK
        }
    }

    fun getCurrent(context: Context): Core = Core.KOTLIN_COMPOSE

    /** 是否使用 Compose 核心。单一引擎常态化后恒为 true。 */
    @JvmStatic
    fun isComposeMode(context: Context): Boolean = true
}
