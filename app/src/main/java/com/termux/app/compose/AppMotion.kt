package com.termux.app.compose

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
}
