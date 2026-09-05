package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.os.Build
import com.termux.shared.termux.TermuxConstants

/**
 * 终端运行核心管理。
 *
 * 两种模式：
 * - JAVA_NDK: 使用现有的 Java + NDK terminal-emulator / terminal-view 模块（默认）
 * - KOTLIN_COMPOSE: 使用新的 Kotlin + Compose libterminal 方案（实验性，插件不可用，需 Android 9+）
 */
object TerminalRuntimeCore {

    const val PREFS_NAME = "app_settings"
    const val KEY_RUNTIME_CORE = "terminal_runtime_core"

    /** Kotlin+Compose 模式所需的最低 SDK 版本（Android 9 Pie）。 */
    const val MIN_SDK_FOR_COMPOSE = 28

    /** 当前设备是否支持切换到 Kotlin+Compose 模式。 */
    val isComposeSupported: Boolean
        get() = Build.VERSION.SDK_INT >= MIN_SDK_FOR_COMPOSE

    enum class Core(val value: String, val displayName: String) {
        JAVA_NDK("java_ndk", "Java + NDK"),
        KOTLIN_COMPOSE("kotlin_compose", "Kotlin + Compose");

        companion object {
            fun fromValue(v: String): Core = entries.firstOrNull { it.value == v } ?: JAVA_NDK
        }
    }

    fun getCurrent(context: Context): Core {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = Core.fromValue(prefs.getString(KEY_RUNTIME_CORE, Core.JAVA_NDK.value) ?: Core.JAVA_NDK.value)
        // SDK < 28 时强制回退到 Java+NDK
        if (stored == Core.KOTLIN_COMPOSE && !isComposeSupported) {
            prefs.edit().putString(KEY_RUNTIME_CORE, Core.JAVA_NDK.value).apply()
            return Core.JAVA_NDK
        }
        return stored
    }

    fun setCurrent(context: Context, core: Core) {
        // SDK < 28 时拒绝切换到 Kotlin+Compose
        if (core == Core.KOTLIN_COMPOSE && !isComposeSupported) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RUNTIME_CORE, core.value)
            .apply()
    }

    /** 是否使用 Compose 核心（Java+NDK=false, Kotlin+Compose=true）。 */
    fun isComposeMode(context: Context): Boolean {
        if (!isComposeSupported) return false
        return getCurrent(context) == Core.KOTLIN_COMPOSE
    }

    /** Java+NDK 模式下可用的插件，Kotlin+Compose 模式下会被禁用。 */
    private val DISABLED_IN_COMPOSE_MODE = listOf(
        IntegratedTools.Tool.TERMUX_API,
        IntegratedTools.Tool.TERMUX_BOOT,
        IntegratedTools.Tool.TERMUX_TASKER,
        IntegratedTools.Tool.TERMUX_WIDGET
    )

    /** 切换核心时自动禁用/启用插件（Styling 除外）。 */
    fun applyPluginState(context: Context, targetCore: Core) {
        val shouldDisable = targetCore == Core.KOTLIN_COMPOSE
        for (tool in DISABLED_IN_COMPOSE_MODE) {
            IntegratedTools.setEnabled(context, tool, !shouldDisable)
            IntegratedTools.applyComponentState(context, tool, !shouldDisable)
        }
    }

    /** 杀掉所有正在运行的会话/任务（切换核心前调用）。 */
    fun killAllSessions(context: Context) {
        try {
            val intent = Intent(context, Class.forName("com.termux.app.TermuxService"))
            intent.action = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_KILL_SESSIONS
            context.startService(intent)
        } catch (_: Exception) {}
    }
}
