package com.termux.app.compose.terminal.color

/**
 * 预设终端配色方案。
 *
 * 基于 libterminal 的 TerminalColorScheme 基底，叠加常见主题的 16 色调整。
 * 每个主题提供前景/背景/光标/16 色，可用于 Styling 页面快速选择。
 */
object TerminalThemes {

    /** 深色主题集合。 */
    val DARK_THEMES: List<TerminalTheme> = listOf(
        TerminalTheme(
            name = "Default Dark",
            terminalColorScheme = TerminalColorScheme.dark()
        ),
        TerminalTheme(
            name = "Material Dark",
            terminalColorScheme = TerminalColorScheme.custom(
                foreground = 0xFFE0E0E0.toInt(),
                background = 0xFF121212.toInt(),
                cursor = 0xFFE0E0E0.toInt()
            )
        ),
        TerminalTheme(
            name = "Gruvbox Dark",
            terminalColorScheme = TerminalColorScheme.custom(
                foreground = 0xFFEBDBB2.toInt(),
                background = 0xFF282828.toInt(),
                cursor = 0xFFEBDBB2.toInt(),
                palette16 = intArrayOf(
                    0xFF282828.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(),
                    0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFA89984.toInt(),
                    0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(),
                    0xFF83A598.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFEBDBB2.toInt()
                )
            )
        ),
        TerminalTheme(
            name = "Dracula",
            terminalColorScheme = TerminalColorScheme.custom(
                foreground = 0xFFF8F8F2.toInt(),
                background = 0xFF282A36.toInt(),
                cursor = 0xFFF8F8F2.toInt(),
                palette16 = intArrayOf(
                    0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
                    0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFBFBFBF.toInt(),
                    0xFF4D4D4D.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA5.toInt(),
                    0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt()
                )
            )
        ),
        TerminalTheme(
            name = "Tokyo Night",
            terminalColorScheme = TerminalColorScheme.custom(
                foreground = 0xFFC0CAF5.toInt(),
                background = 0xFF1A1B26.toInt(),
                cursor = 0xFFC0CAF5.toInt(),
                palette16 = intArrayOf(
                    0xFF1A1B26.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
                    0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFA9B1D6.toInt(),
                    0xFF565F89.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
                    0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFC0CAF5.toInt()
                )
            )
        ),
        TerminalTheme(
            name = "Catppuccin Mocha",
            terminalColorScheme = TerminalColorScheme.custom(
                foreground = 0xFFCDD6F4.toInt(),
                background = 0xFF1E1E2E.toInt(),
                cursor = 0xFFCDD6F4.toInt(),
                palette16 = intArrayOf(
                    0xFF45475A.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
                    0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFBAC2DE.toInt(),
                    0xFF585B70.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
                    0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFA6ADC8.toInt()
                )
            )
        )
    )

    /** 浅色主题集合。 */
    val LIGHT_THEMES: List<TerminalTheme> = listOf(
        TerminalTheme(
            name = "Default Light",
            terminalColorScheme = TerminalColorScheme.light()
        ),
        TerminalTheme(
            name = "Material Light",
            terminalColorScheme = TerminalColorScheme.custom(
                foreground = 0xFF121212.toInt(),
                background = 0xFFFFFFFF.toInt(),
                cursor = 0xFF121212.toInt()
            )
        ),
        TerminalTheme(
            name = "Catppuccin Latte",
            terminalColorScheme = TerminalColorScheme.custom(
                foreground = 0xFF4C4F69.toInt(),
                background = 0xFFEFF1F5.toInt(),
                cursor = 0xFF4C4F69.toInt(),
                palette16 = intArrayOf(
                    0xFFCC000000.toInt(), 0xFFD20F39.toInt(), 0xFF40A02B.toInt(), 0xFFDF8E1D.toInt(),
                    0xFF1E66F5.toInt(), 0xFFEA76CB.toInt(), 0xFF179299.toInt(), 0xFFACB0BE.toInt(),
                    0xFF6C6F85.toInt(), 0xFFD20F39.toInt(), 0xFF40A02B.toInt(), 0xFFDF8E1D.toInt(),
                    0xFF1E66F5.toInt(), 0xFFEA76CB.toInt(), 0xFF179299.toInt(), 0xFF4C4F69.toInt()
                )
            )
        )
    )

    /** 所有预设主题。 */
    val ALL: List<TerminalTheme> = DARK_THEMES + LIGHT_THEMES

    /** 按名称查找主题。 */
    fun findByName(name: String): TerminalTheme? =
        ALL.firstOrNull { it.name == name }
}

/** 预设主题数据类。 */
data class TerminalTheme(
    val name: String,
    val terminalColorScheme: TerminalColorScheme
)
