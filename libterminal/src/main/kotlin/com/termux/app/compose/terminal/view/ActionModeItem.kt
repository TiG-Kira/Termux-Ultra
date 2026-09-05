package com.termux.app.compose.terminal.view

import android.graphics.drawable.Drawable

/**
 * 浮动工具栏的额外菜单项。
 *
 * @param title 按钮显示文字
 * @param icon 按钮图标 drawable（可为 null，仅显示文字）
 * @param onClick 点击回调，参数为当前选中的文本
 */
data class ActionModeItem(
    val title: String,
    val icon: Drawable? = null,
    val onClick: (selectedText: String) -> Unit
)
