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
        e.commit()
    }
    
    
    
    
}