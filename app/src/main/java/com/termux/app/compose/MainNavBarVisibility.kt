package com.termux.app.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.glass.GlassMotion

/**
 * 主页底部导航栏的显隐登记。
 *
 * 主页五个页面里任何一层模态内容（弹窗 / 底弹）抬起时，底栏都该下沉让位。这些模态散落在各
 * 个页面自己的组合里，彼此不知道对方存在，因此在这里统一登记：谁抬起谁登记，全部落下才恢复。
 *
 * 登记用 token 而不是自增计数：某个弹层所在的组合被直接销毁时，token 随 dispose 一起摘掉，
 * 不会像计数那样泄漏成"底栏永久消失"。
 */
object MainNavBarVisibility {

    private val holders = mutableStateListOf<Any>()

    /** 当前是否有任一模态层要求底栏隐藏。 */
    val hidden: Boolean get() = holders.isNotEmpty()

    fun acquire(): Any {
        val token = Any()
        holders.add(token)
        return token
    }

    fun release(token: Any) {
        holders.remove(token)
    }
}

/**
 * [hiding] 为 true 期间把底栏登记为隐藏。
 *
 * 由 MainNavBarDialogs.kt 里那几个同名弹层转发函数调用；弹层关闭退出组合时自动注销。
 */
@Composable
fun MainNavBarHideEffect(hiding: Boolean) {
    DisposableEffect(hiding) {
        if (!hiding) {
            onDispose {}
        } else {
            val token = MainNavBarVisibility.acquire()
            onDispose { MainNavBarVisibility.release(token) }
        }
    }
}

/**
 * 底栏的进出场：隐藏时下沉出屏，恢复时上浮回位。
 *
 * 位移与玻璃底栏自带的 visible 动画同源——同一条 [GlassMotion.navShowHide] 弹簧、同一个
 * [GlassMotion.NAV_SHOW_DELAY_MS] 回弹延时，两者叠加时节奏才不会错位。位移到位之前透明度
 * 已经跑完，是为了让浮动胶囊在离开画面前先虚掉，而不是被"抽"走。
 *
 * @param visible 底栏是否应该停在原位。
 * @param travel 完全隐藏时向下走的行程，通常取底栏总高加一点余量。
 * @param fade 这里是否同时淡出。玻璃底栏自己就会 shrink 并淡出（见它的 visible 参数），
 *   那一分支传 false，避免两层透明度相乘让条子过早消失。
 */
@Composable
fun Modifier.mainNavBarSlide(visible: Boolean, travel: Dp, fade: Boolean = true): Modifier {
    val density = LocalDensity.current
    val travelPx = with(density) { travel.toPx() }
    val progress = remember { Animatable(if (visible) 0f else 1f) }
    LaunchedEffect(visible, travelPx) {
        if (visible) {
            delay(GlassMotion.NAV_SHOW_DELAY_MS)
            progress.animateTo(0f, GlassMotion.navShowHide<Float>())
        } else {
            progress.animateTo(1f, GlassMotion.navShowHide<Float>())
        }
    }
    return this.graphicsLayer {
        translationY = travelPx * progress.value
        if (fade) alpha = 1f - progress.value
    }
}
