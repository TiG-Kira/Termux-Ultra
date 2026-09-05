package com.termux.app.compose.terminal

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import com.termux.app.compose.terminal.color.TerminalColorScheme
import com.termux.app.compose.terminal.color.TerminalThemes
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

/**
 * Kotlin+Compose 终端设置单例。
 *
 * 所有终端设置项都通过此对象读写：
 * - 写入：同时更新 SharedPreferences 和内部 StateFlow
 * - 读取：Activity/Composable 通过 StateFlow.collectAsState() 订阅
 *
 * 这样 SettingsScreen 修改设置后，正在运行的终端页面会立刻感知并应用，
 * 无需重启 app。
 *
 * Styling 适配：与 Java 版共用 `~/.termux/colors.properties` 与 `~/.termux/font.ttf`，
 * 作为主题/字体的唯一事实来源（[stylingColorScheme]/[stylingTypeface]）。
 * - Java 模式切换主题（Styling 页/termux-reload）→ 写盘 → 广播 reload_style → Compose 刷新；
 * - Compose 模式切换主题（同一 Styling 页）→ 写盘 → 广播 reload_style → Java 刷新；
 * - 两种内核互相切换时，起始主题保持一致（都从磁盘读取）。
 */
object ComposeTerminalSettings {

    private const val PREFS_NAME = "compose_terminal"

    // 默认值
    const val DEFAULT_FONT_SIZE = 14
    const val DEFAULT_CURSOR_BLINK = true
    const val DEFAULT_COLOR_SCHEME = "Default Dark"
    const val DEFAULT_SCROLLBACK_LINES = 5000
    const val DEFAULT_SOFT_KEYBOARD = true
    const val DEFAULT_SHOW_TOOLBAR = true
    const val DEFAULT_KEEP_SCREEN_ON = false

    // StateFlow - 所有终端组件订阅这些值
    private val _fontSize = MutableStateFlow(DEFAULT_FONT_SIZE)
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _cursorBlink = MutableStateFlow(DEFAULT_CURSOR_BLINK)
    val cursorBlink: StateFlow<Boolean> = _cursorBlink.asStateFlow()

    /** 主题名称，对应 TerminalThemes 里的 TerminalTheme.name */
    private val _colorSchemeName = MutableStateFlow(DEFAULT_COLOR_SCHEME)
    val colorSchemeName: StateFlow<String> = _colorSchemeName.asStateFlow()

    /** 解析后的 TerminalColorScheme（内置主题），订阅后直接传给 TerminalView */
    private val _colorScheme = MutableStateFlow<TerminalColorScheme>(
        TerminalThemes.findByName(DEFAULT_COLOR_SCHEME)?.terminalColorScheme
            ?: TerminalColorScheme.dark()
    )
    val colorScheme: StateFlow<TerminalColorScheme> = _colorScheme.asStateFlow()

    /** Styling 磁盘主题（~/.termux/colors.properties）解析结果；null 表示未配置自定义主题。 */
    private val _stylingColorScheme = MutableStateFlow<TerminalColorScheme?>(null)
    val stylingColorScheme: StateFlow<TerminalColorScheme?> = _stylingColorScheme.asStateFlow()

    /** Styling 磁盘字体（~/.termux/font.ttf）；null 表示未配置自定义字体。 */
    private val _stylingTypeface = MutableStateFlow<Typeface?>(null)
    val stylingTypeface: StateFlow<Typeface?> = _stylingTypeface.asStateFlow()

    private val _scrollbackLines = MutableStateFlow(DEFAULT_SCROLLBACK_LINES)
    val scrollbackLines: StateFlow<Int> = _scrollbackLines.asStateFlow()

    private val _softKeyboard = MutableStateFlow(DEFAULT_SOFT_KEYBOARD)
    val softKeyboard: StateFlow<Boolean> = _softKeyboard.asStateFlow()

    private val _showToolbar = MutableStateFlow(DEFAULT_SHOW_TOOLBAR)
    val showToolbar: StateFlow<Boolean> = _showToolbar.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(DEFAULT_KEEP_SCREEN_ON)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private var prefs: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    /** 初始化——在 app 启动/首次进入终端时调用一次。 */
    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        reload()
        reloadFromStylingDisk()
    }

    /** 从 SharedPreferences 重新加载所有值（用于 SettingsScreen 手动刷新等场景）。 */
    fun reload() {
        val p = prefs ?: return
        _fontSize.value = p.getInt("font_size", DEFAULT_FONT_SIZE)
        _cursorBlink.value = p.getBoolean("cursor_blink", DEFAULT_CURSOR_BLINK)
        _colorSchemeName.value = p.getString("color_scheme", DEFAULT_COLOR_SCHEME) ?: DEFAULT_COLOR_SCHEME
        _scrollbackLines.value = p.getInt("scrollback_lines", DEFAULT_SCROLLBACK_LINES)
        _softKeyboard.value = p.getBoolean("soft_keyboard", DEFAULT_SOFT_KEYBOARD)
        _showToolbar.value = p.getBoolean("show_toolbar", DEFAULT_SHOW_TOOLBAR)
        _keepScreenOn.value = p.getBoolean("keep_screen_on", DEFAULT_KEEP_SCREEN_ON)
        // 同步解析 colorScheme
        _colorScheme.value = TerminalThemes.findByName(_colorSchemeName.value)?.terminalColorScheme
            ?: TerminalColorScheme.dark()
    }

    /**
     * 从 `~/.termux/colors.properties` 与 `~/.termux/font.ttf` 重新加载 Styling
     *（Java 与 Compose 共用的一份主题配置）。由 Styling reload_style 广播触发。
     */
    fun reloadFromStylingDisk() {
        val context = appContext ?: return
        _stylingColorScheme.value = loadColorsFromTermux(context)
        _stylingTypeface.value = loadTypefaceFromTermux(context)
    }

    // --- 设置方法（写 SP + 更新 StateFlow） ---

    fun setFontSize(value: Int) {
        _fontSize.value = value
        edit { it.putInt("font_size", value) }
    }

    fun setCursorBlink(value: Boolean) {
        _cursorBlink.value = value
        edit { it.putBoolean("cursor_blink", value) }
    }

    fun setColorScheme(name: String) {
        _colorSchemeName.value = name
        _colorScheme.value = TerminalThemes.findByName(name)?.terminalColorScheme
            ?: TerminalColorScheme.dark()
        edit { it.putString("color_scheme", name) }
    }

    fun setScrollbackLines(value: Int) {
        _scrollbackLines.value = value
        edit { it.putInt("scrollback_lines", value) }
    }

    fun setSoftKeyboard(value: Boolean) {
        _softKeyboard.value = value
        edit { it.putBoolean("soft_keyboard", value) }
    }

    fun setShowToolbar(value: Boolean) {
        _showToolbar.value = value
        edit { it.putBoolean("show_toolbar", value) }
    }

    fun setKeepScreenOn(value: Boolean) {
        _keepScreenOn.value = value
        edit { it.putBoolean("keep_screen_on", value) }
    }

    private inline fun edit(block: (SharedPreferences.Editor) -> Unit) {
        val p = prefs ?: return
        block(p.edit())
    }

    // --- Styling（~/.termux/colors.properties + font.ttf）解析 ---

    private fun loadColorsFromTermux(context: Context): TerminalColorScheme? {
        return try {
            val file = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE
            if (!file.isFile || !file.canRead()) return null

            val props = Properties()
            File(file.path).inputStream().use { props.load(it) }

            // 只有真实写入了颜色键才视为自定义主题（Java 默认文件仅含注释）
            val hasCustom = listOf("foreground", "background", "color0").any { props.containsKey(it) }
            if (!hasCustom) return null

            val default16 = TerminalColorScheme.dark().palette16().toIntArray()
            val palette16 = IntArray(16)
            for (i in 0..15) {
                palette16[i] = props.getProperty("color$i")?.let { parseColorHex(it) } ?: default16[i]
            }
            val foreground = props.getProperty("foreground")?.let { parseColorHex(it) } ?: 0xFFFFFFFF.toInt()
            val background = props.getProperty("background")?.let { parseColorHex(it) } ?: 0xFF000000.toInt()
            val cursor = props.getProperty("cursor")?.let { parseColorHex(it) }
                ?: if (android.graphics.Color.luminance(background) < 0.5f) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

            TerminalColorScheme.custom(foreground, background, cursor, palette16)
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseColorHex(value: String): Int? {
        return try {
            val clean = value.trim().removePrefix("#").removePrefix("0x")
            when (clean.length) {
                6 -> android.graphics.Color.parseColor("#FF$clean")
                8 -> android.graphics.Color.parseColor("#$clean")
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadTypefaceFromTermux(context: Context): Typeface? {
        return try {
            val fontFile = TermuxConstants.TERMUX_FONT_FILE
            if (!fontFile.isFile || fontFile.length() == 0L) return null
            Typeface.createFromFile(File(fontFile.path))
        } catch (_: Throwable) {
            null
        }
    }
}
