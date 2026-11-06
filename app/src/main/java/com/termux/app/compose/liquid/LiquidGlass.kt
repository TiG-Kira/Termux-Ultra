// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.termux.app.compose.liquid

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * 液态玻璃取景层（Kyant0/AndroidLiquidGlass 的 `com.kyant.backdrop.Backdrop`）。
 *
 * 与 miuix-glass 的 `top.yukonga.miuix.kmp.blur.Backdrop` 是**两套互不兼容的类型**：
 * 前者用 `drawBackdrop + lens(chromaticAberration)` 做强折射色散，后者走 miuix 自己的
 * glass shader。因此主屏要同时维护两条取景链——柔光玻璃+（SOFT）继续用 miuix 那条，
 * 液态玻璃（LIQUID）用这条，内容 Box 同时注册两个取景层。
 *
 * 只在材质开关打开且风格为 LIQUID 时才非空，其余情况一律 null（调用方回落实色）。
 */
val LocalLiquidBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * UI 外观样式「液态玻璃」的总闸门（由 MainScreen 根 provide）。
 *
 * Kiba 用 `MaterialFamily.LIQUID` 这个全局材质枚举决定顶栏形态；Termux Ultra 的
 * UI 外观样式与底栏样式是两个独立偏好，这里只跟外观样式走，底栏三档不受影响。
 * 顶栏骨架（miuix-glass）读它来决定「band 是否换成液态皮肤 / 按钮是否换液态圆钮」，
 * 独立 Activity 不在 MainScreen 树内，默认 false 自动回落原有顶栏。
 */
val LocalLiquidTopBarEnabled = staticCompositionLocalOf { false }

/**
 * 「当前正处在一块液态玻璃顶栏里」。
 *
 * 顶栏动作按钮的材质必须**跟随它所在的那块顶栏**，而不是跟随全局材质开关：
 * 歌曲详情 / 播放页 / 谱面预览这些页面的顶栏是 miuix glass（甚至实色），
 * 如果因为全局选了液态玻璃就把里面的返回按钮换成液态圆钮，会出现
 * 「液态玻璃按钮压在一块 miuix 玻璃/封面/实色顶栏上」的混搭，而且按钮采样的
 * 还是另一条取景层（kyant 那条），观感与性能都不对。
 *
 * 所以由 `KibaTopBar` 的液态分支在自己的内容区 provide true，只有真在液态顶栏里的
 * 按钮才走液态实现。
 *
 * 用 `compositionLocalOf`（动态）而非 `staticCompositionLocalOf`：歌曲详情 / 播放页
 * 会在滚动过程中把它的值从 false 翻成 true（封面消失后按钮改液态），动态版本只让
 * 读取它的那几颗按钮失效，不会把整棵 GlassTopAppBar 子树重组（那会打断玻璃取景重录）。
 */
// 宿主注：Termux Ultra 本轮不按「按钮所在的那块顶栏」逐页切换材质，改用上面的
// LocalLiquidTopBarEnabled 在顶栏骨架内统一分派；此 Local 保留以与 Kiba 上游对齐，
// 当前无调用点，后续增量同步时不必再补差异。
val LocalInLiquidTopBar = compositionLocalOf { false }

/**
 * 胶囊条的液态玻璃效果：通透度提升 → 中幅背景模糊 → 透镜折射。
 *
 * 折射量取 demo 的 **2/3**（demo 是 24dp，这里 16dp / 18dp）：demo 展示的是纯色渐变背景，
 * 而 Kiba 的底栏下面压着高对比的封面与文字，24dp 折射会把封面糊成一大团色斑（AVD 实证：
 * 蓝色封面在选中项位置晕开成蓝雾）。16dp 仍能看出厚玻璃的弯曲感，又不至于吃掉内容。
 */
fun BackdropEffectScope.liquidBarEffects() {
    vibrancy()
    blur(8.dp.toPx())
    lens(16.dp.toPx(), 18.dp.toPx(), depthEffect = true)
}

/**
 * 小控件（播放底栏）的液态玻璃效果：模糊收一点，但保留色散（`chromaticAberration`），
 * 让胶囊边缘透出红蓝分离的厚玻璃色散——这是液态玻璃与柔光玻璃最直观的差别。
 */
fun BackdropEffectScope.liquidPillEffects() {
    vibrancy()
    blur(6.dp.toPx())
    lens(18.dp.toPx(), 20.dp.toPx(), chromaticAberration = true)
}

/** 胶囊条的边缘高光（demo 默认口径：0.5dp 细高光 + 同宽模糊）。 */
val LiquidBarHighlight: Highlight get() = Highlight.Default

/** 胶囊条的投影（demo 默认半径 24dp 的黑色 10%）。 */
val LiquidBarShadow: Shadow get() = Shadow(radius = 24.dp)
