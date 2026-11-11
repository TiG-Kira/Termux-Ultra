package com.termux.app.compose

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** MS-DOS 模拟器偏好。SharedPreferences 持久化，MutableStateFlow 驱动 Compose。 */
object MsDosPrefs {
    private const val PREFS = "msdos_prefs"
    private const val KEY_MOUNT_ENABLED = "mount_enabled"
    private const val KEY_MOUNT_DIR = "mount_dir"
    private const val KEY_VNC_PORT = "vnc_port"

    const val DEFAULT_PORT = 5900
    val PORT_OPTIONS = listOf(5900, 5901, 5902, 5903)

    data class Config(
        val mountEnabled: Boolean = false,
        val mountDir: String = "",
        val vncPort: Int = DEFAULT_PORT
    )

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _config.value = Config(
            mountEnabled = p.getBoolean(KEY_MOUNT_ENABLED, false),
            mountDir = p.getString(KEY_MOUNT_DIR, "") ?: "",
            vncPort = p.getInt(KEY_VNC_PORT, DEFAULT_PORT)
                .takeIf { it in 5900..5999 } ?: DEFAULT_PORT
        )
    }

    fun update(context: Context, transform: (Config) -> Config) {
        val next = transform(_config.value)
        _config.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MOUNT_ENABLED, next.mountEnabled)
            .putString(KEY_MOUNT_DIR, next.mountDir)
            .putInt(KEY_VNC_PORT, next.vncPort)
            .apply()
    }
}
