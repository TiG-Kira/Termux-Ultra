package com.termux.app.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 横屏/平板布局适配工具（终端控制台 + 主页共用）。
 *
 * 竖屏（手机）的所有 UI 逻辑保持不变：以下工具在竖屏下均返回
 * "什么都不做"的默认值，只有横屏/平板才会启用新布局行为。
 */

/** 是否处于横屏方向。 */
@Composable
fun isLandscapeOrientation(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
}

/** 是否为平板级设备（最小屏宽 >= 600dp，与 Material 断点一致）。 */
@Composable
fun isTabletDevice(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.smallestScreenWidthDp >= 600
}

/** 双分屏/侧边导航栏等宽屏布局的启用条件：横屏或平板。 */
@Composable
fun isWideScreenLayout(): Boolean = isLandscapeOrientation() || isTabletDevice()

/**
 * 刘海/挖孔智能避让的 insets（横屏时主要作用于水平方向）。
 *
 * displayCutout insets 由系统按刘海/挖孔的 boundingRect 精确给出：
 * - 刘海屏：cutout 矩形横跨较宽区域 → 水平 inset 覆盖整条刘海带，内容整段避开；
 * - 挖孔屏：cutout 矩形只有孔位大小 → 水平 inset 仅让出挖孔所在位置，其余内容可正常铺展。
 *
 * 竖屏时返回全 0（竖屏的 cutout 位于顶部，由既有 statusBarsPadding 链路处理，
 * 不在此叠加，避免改变竖屏 UI 行为）。
 */
@Composable
fun rememberLandscapeCutoutInsets(): PaddingValues {
    if (!isLandscapeOrientation()) return PaddingValues(0.dp)
    return WindowInsets.displayCutout.asPaddingValues()
}

/** 主页内容区的横屏最大宽度：超过后列表/网格居中收窄，避免行宽被拉到不可读。 */
val LandscapePageMaxWidth: Dp = 720.dp

/**
 * 横屏/平板时把内容收窄到 [maxWidth] 并水平居中（列表页的可读性行宽）；
 * 竖屏原样返回，链式行为完全不变。
 *
 * 注意：必须放在 `.fillMaxSize()` 之前——fillMaxSize 在外层会把宽度撑满，
 * 内层的 widthIn 就没有收窄余地了。
 */
@Composable
fun Modifier.landscapeCenteredMaxWidth(maxWidth: Dp = LandscapePageMaxWidth): Modifier =
    if (!isWideScreenLayout()) this
    else this
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = maxWidth)
