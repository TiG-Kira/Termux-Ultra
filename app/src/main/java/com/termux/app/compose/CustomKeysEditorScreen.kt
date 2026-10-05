/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.termux.app.compose

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaurav.avnc.ui.vnc.VirtualKey
import com.gaurav.avnc.ui.vnc.VirtualKeyLayoutConfig
import com.gaurav.avnc.util.AppPreferences
import com.termux.R
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme


/**
 * Compose + Miuix 风格的自定义虚拟按键编辑页面。
 *
 * 替换原有的 [com.gaurav.avnc.ui.prefs.VirtualKeysEditor] Fragment，
 * 作为 VNC 设置 → 输入 → 自定义按键 的 Compose 子页面。
 *
 * 按键预览区严格按真实键盘布局渲染：行数取自 [AppPreferences.input.vkRowCount]、
 * 按键顺序取自 [VirtualKeyLayoutConfig.getLayout] 的配置列表，
 * 并以与原生 GridLayout（orientation=vertical）一致的「列优先」方式排布，
 * 使页面展示与用户实际键盘的物理布局保持一致。
 */
@Composable
fun CustomKeysEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 编辑中的按键列表 —— 基于 prefs 中的当前布局（真实配置）
    var keyList by remember { mutableStateOf(VirtualKeyLayoutConfig.getLayout(prefs).toMutableList()) }

    // 当前选中的按键索引（-1 表示无选中）
    var focusedIndex by remember { mutableStateOf(-1) }

    // 当前键盘行数（驱动物理布局的分行，取自真实配置）
    val rowCount = remember { prefs.input.vkRowCount }

    // "添加按键"底部弹层
    var showAddSheet by remember { mutableStateOf(false) }

    // 返回逻辑：关闭弹层 > 取消选中 > 返回上一页
    BackHandler(enabled = true) {
        when {
            showAddSheet -> showAddSheet = false
            focusedIndex >= 0 -> focusedIndex = -1
            else -> onBack()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            // 与 VNC 设置其他子页面一致：把顶栏高度作为可滚动 contentPadding，
            // 让内容从顶栏下方开始，并在滚动时穿过玻璃顶栏的渐变带，避免被遮挡。
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = LocalTopBarClearance.current, bottom = 16.dp)
        ) {
            // ======= 使用说明 =======
            item {
                Text(
                    text = stringResource(R.string.msg_customize_keys_hint),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            // ======= 按键预览区（按真实物理布局渲染） =======
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Card {
                        if (keyList.isEmpty()) {
                            // 无配置 / 配置为空时的空状态
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.msg_no_keys_added),
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        } else {
                            KeyGridView(
                                keys = keyList,
                                rowCount = rowCount,
                                focusedIndex = focusedIndex,
                                onKeyClick = { index ->
                                    focusedIndex = if (focusedIndex == index) -1 else index
                                }
                            )
                        }
                    }
                }
            }

            // ======= 当前选中按键的提示（名称 + 真实键值/功能） =======
            if (focusedIndex >= 0 && focusedIndex < keyList.size) {
                item {
                    Text(
                        text = getKeySummary(keyList[focusedIndex]),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            // ======= 操作按钮行 =======
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ToolIconButton(
                        iconRes = R.drawable.ic_add,
                        contentDescription = stringResource(R.string.pref_customize_virtual_keys),
                        enabled = true,
                        onClick = { showAddSheet = true }
                    )
                    ToolIconButton(
                        iconRes = R.drawable.ic_arrow_up,
                        contentDescription = "上移",
                        enabled = focusedIndex > 0,
                        onClick = {
                            if (focusedIndex > 0) {
                                keyList = keyList.toMutableList().apply {
                                    val i = focusedIndex
                                    val j = i - 1
                                    this[i] = this[j].also { this[j] = this[i] }
                                }
                                focusedIndex -= 1
                            }
                        }
                    )
                    ToolIconButton(
                        iconRes = R.drawable.ic_arrow_down,
                        contentDescription = "下移",
                        enabled = focusedIndex >= 0 && focusedIndex < keyList.size - 1,
                        onClick = {
                            if (focusedIndex >= 0 && focusedIndex < keyList.size - 1) {
                                keyList = keyList.toMutableList().apply {
                                    val i = focusedIndex
                                    val j = i + 1
                                    this[i] = this[j].also { this[j] = this[i] }
                                }
                                focusedIndex += 1
                            }
                        }
                    )
                    ToolIconButton(
                        iconRes = R.drawable.ic_delete,
                        contentDescription = "删除",
                        enabled = focusedIndex >= 0 && keyList.size > 1,
                        onClick = {
                            if (focusedIndex >= 0 && keyList.size > 1) {
                                val newList = keyList.toMutableList()
                                newList.removeAt(focusedIndex)
                                keyList = newList
                                focusedIndex = -1
                            }
                        }
                    )
                }
            }

            // ======= 保存 / 取消 =======
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = onBack,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            VirtualKeyLayoutConfig.setLayout(prefs, keyList)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.msg_saved),
                                    duration = SnackbarDuration.Short
                                )
                            }
                            onBack()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                }
            }

            // ======= 恢复默认 =======
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        text = stringResource(R.string.title_load_defaults),
                        onClick = {
                            keyList = VirtualKeyLayoutConfig.getDefaultLayout(prefs).toMutableList()
                            focusedIndex = -1
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            SnackbarHost(state = snackbarHostState)
        }
    }

    // ======= 添加按键弹层 =======
    OverlayBottomSheet(
        show = showAddSheet,
        onDismissRequest = { showAddSheet = false },
        title = stringResource(R.string.pref_customize_virtual_keys)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 520.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SmallTitle(text = "选择要添加的按键")
                Spacer(modifier = Modifier.height(4.dp))
            }

            item {
                val currentSet = keyList.toSet()
                AvailableKeysGrid(
                    keys = VirtualKey.entries.toList(),
                    disabledKeys = currentSet,
                    onKeyClick = { vk ->
                        val idx = keyList.indexOf(vk)
                        if (idx >= 0) {
                            focusedIndex = idx
                        } else {
                            keyList = keyList.toMutableList().apply { add(vk) }
                            focusedIndex = keyList.lastIndex
                        }
                        showAddSheet = false
                    }
                )
            }
        }
    }
}

// ================= 辅助 Composable =================

/**
 * 按键网格：按真实键盘物理布局渲染。
 *
 * 与原生 [com.gaurav.avnc.ui.vnc.VirtualKeys] 的 GridLayout（orientation=vertical、
 * rowCount=[rowCount]）一致，采用「列优先」排布——同一列的按键自上而下填充，
 * 列满后再换到下一列。索引 i 的按键位于 第 (i % rowCount) 行、第 (i / rowCount) 列，
 * 因此其行列位置、顺序、分组完全由真实配置列表决定。
 */
@Composable
private fun KeyGridView(
    keys: List<VirtualKey>,
    rowCount: Int,
    focusedIndex: Int,
    onKeyClick: (Int) -> Unit
) {
    val rows = if (rowCount < 1) 1 else rowCount
    val columnCount = (keys.size + rows - 1) / rows

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(columnCount) { col ->
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (row in 0 until rows) {
                    val index = col * rows + row
                    if (index < keys.size) {
                        VirtualKeyChip(
                            key = keys[index],
                            isFocused = index == focusedIndex,
                            onClick = { onKeyClick(index) }
                        )
                    }
                }
            }
        }
    }
}

/** 可用按键选择横向列表（添加新按键时的底部弹层） */
@Composable
private fun AvailableKeysGrid(
    keys: List<VirtualKey>,
    disabledKeys: Set<VirtualKey>,
    onKeyClick: (VirtualKey) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(keys) { vk ->
            VirtualKeyChip(
                key = vk,
                isFocused = disabledKeys.contains(vk),
                onClick = { onKeyClick(vk) }
            )
        }
    }
}

/** 单个按键 Chip，模拟原 GridLayout 中的一个按键 View，宽度固定以贴近真实键盘外观 */
@Composable
private fun VirtualKeyChip(
    key: VirtualKey,
    isFocused: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isFocused)
        MiuixTheme.colorScheme.primary.copy(alpha = 0.25f)
    else
        MiuixTheme.colorScheme.background

    val textColor = if (isFocused)
        MiuixTheme.colorScheme.primary
    else
        MiuixTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (key.icon != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(key.icon),
                    contentDescription = key.description ?: key.name,
                    tint = textColor,
                    modifier = Modifier.size(18.dp)
                )
                if (key.label != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = key.label,
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            Text(
                text = getKeyLabel(key),
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 工具栏 Icon 按钮（添加 / 上移 / 下移 / 删除） */
@Composable
private fun ToolIconButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MiuixTheme.colorScheme.background
                else MiuixTheme.colorScheme.background.copy(alpha = 0.4f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (enabled) MiuixTheme.colorScheme.onSurface
            else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 与原 VirtualKeyViewFactory.getLabel() 保持一致 */
private fun getKeyLabel(key: VirtualKey): String = key.label ?: key.name

/**
 * 选中按键的摘要：名称 + 真实键值（KeyEvent 常量名）。
 * 仅展示信息，所有数据均取自真实按键配置。
 */
private fun getKeySummary(key: VirtualKey): String {
    val base = key.description ?: (key.label ?: key.name)
    return if (key.keyCode != null) {
        "$base · ${KeyEvent.keyCodeToString(key.keyCode)}"
    } else {
        base
    }
}
