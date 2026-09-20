package com.termux.app.compose

import android.content.Context
import android.content.SharedPreferences
import com.termux.app.utils.HyperOSDetector
import android.os.Build

/**
 * 通知配置管理。
 *
 * 存储：
 *  - 通知开关：终端 / Agent / 软件包状态
 *  - 通知方式：普通 / LiveUpdate / 焦点通知
 */
object NotificationPrefs {

    private const val PREFS_NAME = "notification_settings"

    // 通知方式常量
    const val MODE_NORMAL = "normal"
    const val MODE_LIVE_UPDATE = "live_update"
    const val MODE_FOCUS = "focus"

    // 开关键
    private const val KEY_TERMINAL_ENABLED = "notify_terminal_enabled"
    private const val KEY_AGENT_ENABLED = "notify_agent_enabled"
    private const val KEY_PACKAGE_ENABLED = "notify_package_enabled"

    // 通知方式键
    private const val KEY_NOTIFY_MODE = "notify_mode"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ===== 开关 =====

    /** 终端会话通知开关（默认开） */
    @JvmStatic
    fun isTerminalEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TERMINAL_ENABLED, true)

    @JvmStatic
    fun setTerminalEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_TERMINAL_ENABLED, enabled).apply()

    /** Agent 通知开关（默认开） */
    @JvmStatic
    fun isAgentEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AGENT_ENABLED, true)

    @JvmStatic
    fun setAgentEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AGENT_ENABLED, enabled).apply()

    /** 软件包状态通知开关（默认开） */
    @JvmStatic
    fun isPackageEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PACKAGE_ENABLED, true)

    @JvmStatic
    fun setPackageEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_PACKAGE_ENABLED, enabled).apply()

    // ===== 通知方式 =====

    /**
     * 当前通知方式。未配置时根据平台能力给默认值。
     */
    @JvmStatic
    fun getMode(context: Context): String {
        val saved = prefs(context).getString(KEY_NOTIFY_MODE, null)
        if (saved != null) return saved
        // Android 16+ 优先使用 LiveUpdate
        return if (Build.VERSION.SDK_INT >= 36) MODE_LIVE_UPDATE else MODE_NORMAL
    }

    @JvmStatic
    fun setMode(context: Context, mode: String) =
        prefs(context).edit().putString(KEY_NOTIFY_MODE, mode).apply()

    // ===== 能力判断 =====

    /** LiveUpdate（Android 16+）是否可用 */
    @JvmStatic
    fun isLiveUpdateAvailable(): Boolean = Build.VERSION.SDK_INT >= 36

    /** 焦点通知（HyperOS 2+）是否可用 */
    @JvmStatic
    fun isFocusNotificationAvailable(): Boolean = HyperOSDetector.isHyperOS2OrAbove()
}
