package com.termux.gui

import android.annotation.SuppressLint
import android.content.Context

/**
 * Settings for the app.
 */
class Settings private constructor() {
    
    companion object {
        private const val TIMEOUT_KEY = "timeout"
        private const val TIMEOUT_DEFAULT = 3
        private const val BACKGROUND_KEY = "background"
        private const val BACKGROUND_DEFAULT = false
        private const val LOGLEVEL_KEY = "loglevel"
        private const val LOGLEVEL_DEFAULT = 0
        private const val JAVASCRIPT_KEY = "javascript"
        private const val JAVASCRIPT_DEFAULT = false
        private const val CHANNELS_KEY = "channels"
        val instance = Settings()
    }


    /**
     * Timeout for the service in seconds if no clients are connected.
     */
    var timeout: Int = TIMEOUT_DEFAULT

    /**
     * Whether the service should be immediately stopped in the background if no clients are connected.
     */
    var background: Boolean = BACKGROUND_DEFAULT

    /**
     * The log level for the logger.
     */
    var loglevel: Int = LOGLEVEL_DEFAULT

    /**
     * Whether JavaScript should be enabled in WebViews by default.
     */
    var javascript: Boolean = JAVASCRIPT_DEFAULT

    /**
     * 本插件创建过的通知渠道 ID（GUIService 的 "service" 渠道 + 脚本经协议创建的渠道）。
     *
     * 内置化后与应用同包，宿主自己也建通知渠道（termux_notification_channel 等）——那些不在
     * 此列。「删除所有通知渠道」设置项只清理这里的 ID：误删宿主渠道会抛 SecurityException
     * （前台服务占用中的渠道不允许删除），曾导致设置页闪退。
     */
    val channels: MutableSet<String> = HashSet()


    /**
     * Get the shared preferences for the settings.
     *
     * 本地改动：原实现用 EncryptedSharedPreferences（拖进 tink-android，与宿主已有 tink 冲突），
     * 存的只是超时/日志级别等非敏感项，改为普通 SharedPreferences。
     */
    private fun settingsPreferences(c: Context) =
        c.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    /**
     * Load the settings from the shared preferences.
     */
    fun load(c: Context) {
        val prefs = settingsPreferences(c)
        timeout = prefs.getInt(TIMEOUT_KEY, TIMEOUT_DEFAULT)
        background = prefs.getBoolean(BACKGROUND_KEY, BACKGROUND_DEFAULT)
        loglevel = prefs.getInt(LOGLEVEL_KEY, LOGLEVEL_DEFAULT)
        javascript = prefs.getBoolean(JAVASCRIPT_KEY, JAVASCRIPT_DEFAULT)
        channels.clear()
        prefs.getStringSet(CHANNELS_KEY, emptySet())?.let { channels.addAll(it) }
    }

    /**
     * Save the settings to the shared preferences.
     */
    @SuppressLint("ApplySharedPref")
    fun save(c: Context) {
        val prefs = settingsPreferences(c)
        val e = prefs.edit()
            e.putInt(TIMEOUT_KEY, timeout)
        e.putBoolean(BACKGROUND_KEY, background)
        e.putInt(LOGLEVEL_KEY, loglevel)
        e.putBoolean(JAVASCRIPT_KEY, javascript)
        e.putStringSet(CHANNELS_KEY, channels)
        e.commit()
    }

    /**
     * 记录一个本插件创建的通知渠道 ID，供「删除所有通知渠道」精确清理。重复记录不会重复落盘。
     */
    fun recordChannel(c: Context, id: String) {
        if (channels.add(id)) save(c)
    }
    
    
    
    
}