package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主页横屏侧边导航栏。
 *
 * 竖屏时主页沿用底部导航栏（浮动玻璃 / 经典两样式），横屏时切换为本组件：
 * 导航栏锁边停靠在屏幕一侧、图标与文字纵向排列，底部不再被导航栏占用，
 * 五个页面的内容区因此可以在横屏下使用完整纵向空间。
 */
@Composable
fun MainScreenNavRail(
    selectedTab: Int,
    availableTabs: List<Int>,
    onTabChange: (Int) -> Unit,
    bottomInset: Dp
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(76.dp)
            .background(MiuixTheme.colorScheme.surface)
            // 横屏下状态栏仍在顶部（主页不做沉浸式），图标避开状态栏与手势条
            .statusBarsPadding()
            .padding(bottom = bottomInset)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        availableTabs.forEach { tab ->
            val icon: ImageVector = when (tab) {
                0 -> Icons.Rounded.Dashboard
                1 -> ImageVector.vectorResource(R.drawable.ic_nav_terminal)
                2 -> Icons.Rounded.Folder
                3 -> Icons.Rounded.DesktopWindows
                else -> Icons.Rounded.Settings
            }
            val labelRes = when (tab) {
                0 -> R.string.overview
                1 -> R.string.terminal
                2 -> R.string.files
                3 -> R.string.remote
                else -> R.string.settings
            }
            val selected = selectedTab == tab
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onTabChange(tab) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else Color.Transparent
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = stringResource(labelRes),
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = if (selected) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}
