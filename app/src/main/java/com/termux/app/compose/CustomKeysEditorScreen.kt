/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.termux.app.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme


/**
 * Compose + Miuix 风格的自定义虚拟按键编辑页面。
 *
 * 替换原有的 [com.gaurav.avnc.ui.prefs.VirtualKeysEditor] Fragment，
 * 作为 VNC 设置 → 输入 → 自定义按键 的 Compose 子页面。
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

    // 编辑中的按键列表 —— 基于 prefs 中的当前布局
    var keyList by remember { mutableStateOf(VirtualKeyLayoutConfig.getLayout(prefs).toMutableList()) }

    // 当前选中的按键索引（-1 表示无选中）
    var focusedIndex by remember { mutableStateOf(-1) }

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
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp)
        ) {
            // ======= 使用说明 =======
            Text(
                text = stringResource(R.string.msg_customize_keys_hint),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )

            // ======= 按键网格区域（横向可滑动） =======
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Card {
                    if (keyList.isEmpty()) {
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
                            focusedIndex = focusedIndex,
                            onKeyClick = { index ->
                                focusedIndex = if (focusedIndex == index) -1 else index
                            }
                        )
                    }
                }
            }

            // ======= 当前选中按键的提示 =======
            if (focusedIndex >= 0 && focusedIndex < keyList.size) {
                Text(
                    text = keyList[focusedIndex].description
                        ?: getKeyLabel(keyList[focusedIndex]),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            // ======= 操作按钮行 =======
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

            // ======= 保存 / 取消 =======
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

            Spacer(modifier = Modifier.height(8.dp))
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

/** 按键横向排列，支持左右滑动浏览 */
@Composable
private fun KeyGridView(
    keys: List<VirtualKey>,
    focusedIndex: Int,
    onKeyClick: (Int) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
    ) {
        items(keys) { vk ->
            val index = keys.indexOf(vk)
            VirtualKeyChip(
                key = vk,
                isFocused = index == focusedIndex,
                onClick = { onKeyClick(index) }
            )
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
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

/** 单个按键 Chip，模拟原 GridLayout 中的一个按键 View */
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
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
