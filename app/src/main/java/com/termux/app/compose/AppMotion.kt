package com.termux.app.compose

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * 全局动效时长 token。
 *
 * 主页切页、底栏指示器、列表交互等常规 UI 动效统一从这三档取值，替代散落各处的
 * 100~300ms 裸值；欢迎页编排、呼吸渐变等长序列动画保持独立时长，不套用本 token。
 */
object AppMotion {
    /** 短反馈：按压态、指示器淡入淡出等 100~180ms 交互。 */
    const val FAST = 150

    /** 常规过渡：折叠、展开、次级页面淡入等 200~260ms 过渡。 */
    const val NORMAL = 220

    /** 强调过渡：整页切页、底部弹层进出等约 300ms 过渡。 */
    const val SLOW = 300

    /**
     * 整页推屏缓动：快起慢收的三次贝塞尔（与 Kiba 主页 Tab 切换同一条曲线）。
     *
     * 起步就有速度、尾段慢慢咬合，比 material 标准 easing 更利落；
     * 主页 Tab 互切与 OOBE 中间页切换共用，保证整 app 的"推屏"手感一致。
     */
    val Push: Easing = CubicBezierEasing(0.22f, 0.85f, 0.16f, 1f)

    /** 淡入淡出缓动：前段就铺开、尾段收得很轻，避免"啪一下出来"。 */
    val SoftOut: Easing = CubicBezierEasing(0.16f, 0.84f, 0.24f, 1f)
}
