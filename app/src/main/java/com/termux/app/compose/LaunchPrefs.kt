package com.termux.app.compose

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 启动行为偏好：打开 App 先落在哪个 tab、要不要直接开一个终端控制台。
 *
 * 与 [AppThemePrefs] 同一套路 —— 写入同时更新 SharedPreferences 与 StateFlow，设置页切换后
 * 已订阅的 Composable 下一次重组即可看到新值。
 *
 * 两项互相约束：开了 [autoStartConsole] 就意味着「启动即进控制台」，此时 [launchPage] 没有
 * 意义，读取方一律当成 [LaunchPage.OVERVIEW]。约束只在 [effectiveLaunchPage] 里做一次，
 * 不写回磁盘 —— 用户关掉开关后原先选的页面要能原样回来。
 */
object LaunchPrefs {

    private const val PREFS_NAME = "termux_preferences"
    private const val KEY_LAUNCH_PAGE = "launch_page"
    private const val KEY_AUTO_START_CONSOLE = "auto_start_console"

    /** 冷启动落地页。[AUTO_START_CONSOLE] 开启时只有 [OVERVIEW] 会生效。 */
    enum class LaunchPage {
        OVERVIEW,
        TERMINAL;

        /** 存盘用整型，避免枚举名进磁盘后改不动。 */
        val storedValue: Int get() = ordinal

        companion object {
            fun fromStored(value: Int): LaunchPage =
                entries.getOrElse(value) { OVERVIEW }
        }
    }

    private val _launchPage = MutableStateFlow(LaunchPage.OVERVIEW)
    val launchPage: StateFlow<LaunchPage> = _launchPage.asStateFlow()

    private val _autoStartConsole = MutableStateFlow(false)
    val autoStartConsole: StateFlow<Boolean> = _autoStartConsole.asStateFlow()

    @Volatile
    private var loaded = false

    /** 从磁盘加载；幂等。必须在首次读取 StateFlow 前调用，否则首帧会用默认值。 */
    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        val p = prefs(context)
        _launchPage.value = LaunchPage.fromStored(p.getInt(KEY_LAUNCH_PAGE, LaunchPage.OVERVIEW.storedValue))
        _autoStartConsole.value = p.getBoolean(KEY_AUTO_START_CONSOLE, false)
        loaded = true
    }

    @Synchronized
    fun setLaunchPage(context: Context, page: LaunchPage) {
        _launchPage.value = page
        prefs(context).edit().putInt(KEY_LAUNCH_PAGE, page.storedValue).apply()
    }

    @Synchronized
    fun setAutoStartConsole(context: Context, enabled: Boolean) {
        _autoStartConsole.value = enabled
        prefs(context).edit().putBoolean(KEY_AUTO_START_CONSOLE, enabled).apply()
    }

    /**
     * 受 [autoStartConsole] 约束后的实际落地页。
     *
     * 开了自动控制台就直接进控制台，落地 tab 固定总览；此时磁盘上存的用户选择保持不动，
     * 关掉开关即恢复。
     */
    fun effectiveLaunchPage(): LaunchPage =
        if (_autoStartConsole.value) LaunchPage.OVERVIEW else _launchPage.value

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
