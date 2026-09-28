package com.termux.app.compose

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局外观偏好：Material You 动态取色开关。
 *
 * 与 [com.termux.app.terminal.shell.ComposeTerminalSettings] 同一套路 —— 写入同时更新
 * SharedPreferences 与 StateFlow，Composable 侧订阅 StateFlow，因此在设置页切换后
 * 已打开的页面下一次重组即可应用新配色，不必重启 App。
 *
 * 是否真的启用动态取色还要过 [ApiCompat.Feature.MIUIX_DYNAMIC_COLOR] 门禁（Android 12），
 * 由 KiTerminalTheme 统一判定，这里只负责存「用户想不想开」。
 */
object AppThemePrefs {

    private const val PREFS_NAME = "termux_preferences"
    private const val KEY_MATERIAL_YOU = "material_you_enabled"

    private val _materialYouEnabled = MutableStateFlow(false)
    val materialYouEnabled: StateFlow<Boolean> = _materialYouEnabled.asStateFlow()

    @Volatile
    private var loaded = false

    /** 从磁盘加载；幂等，可重复调用。必须在首次读取 StateFlow 前调用，否则首帧会用默认值。 */
    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        _materialYouEnabled.value = prefs(context).getBoolean(KEY_MATERIAL_YOU, false)
        loaded = true
    }

    @Synchronized
    fun setMaterialYouEnabled(context: Context, enabled: Boolean) {
        _materialYouEnabled.value = enabled
        prefs(context).edit().putBoolean(KEY_MATERIAL_YOU, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
