package com.termux.app.compose.terminal.view

/**
 * 浮动工具栏（ActionMode）的定制基类。
 *
 * 用户可继承此类，override 所需方法来自定义：
 * - 按钮文字本地化
 * - 添加额外操作按钮（如分享）
 *
 * 使用示例：
 * ```kotlin
 * terminalView.actionModeCustomizer = object : ActionModeCustomizer() {
 *     override fun copyText() = "复制"
 *     override fun pasteText() = "粘贴"
 *     override fun createActionModeItems() = listOf(
 *         ActionModeItem("分享", shareDrawable) { text -> shareText(text) }
 *     )
 * }
 * ```
 */
open class ActionModeCustomizer {
    /** 复制按钮的文字，默认 "Copy"。 */
    open fun copyText(): String = "Copy"

    /** 粘贴按钮的文字，默认 "Paste"。 */
    open fun pasteText(): String = "Paste"

    /**
     * 提供浮动工具栏的额外菜单项。
     * 默认返回空列表，子类可 override 以添加自定义操作。
     */
    open fun createActionModeItems(): List<ActionModeItem> = emptyList()
}
