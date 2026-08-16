package com.termux.app.compose

import android.content.Context
import android.os.Build

/**
 * API 兼容性中心：集中管理各功能所需的最低 Android API 等级，以及运行时异常触发的屏蔽状态。
 *
 *  - **静态屏蔽**：根据 [Build.VERSION.SDK_INT] 与 [Feature.minApi] 比较，编译期即可判定
 *    的功能不可用（如 Android 11 没有 LiveUpdate 通知 API），此时 UI 按常规逻辑隐藏或降级。
 *  - **运行时屏蔽**：启动时（OOBE / MainActivity）渲染 miuix UI 或执行功能时抛出
 *    VerifyError / NoClassDefFoundError / ExceptionInInitializerError 等异常，经
 *    FallbackHelper 分析异常定位到具体页面或功能后，把对应条目标记为运行时禁用。
 *    下一次渲染时，该功能入口按钮变灰不可点击，终端页显示低版本警告卡片。
 *
 * 运行时状态仅在 **当前进程生命周期内生效**，App 重启后自动重置（不持久化）。
 *
 *  - **用户强制启用**：低版本 Android（8-11）用户确认警告弹窗后，可手动启用被静态屏蔽的
 *    功能。该状态持久化到 SharedPreferences，重启后依然有效。此时终端页的低版本卡片
 *    变为红色并提示风险。
 */
object ApiCompat {

    /** 低版本 Android 阈值：Android 11（API 30）及以下视为低版本（即 Android 8-11；7安装不上） */
    val isLowAndroid: Boolean
        get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R

    /** 当前 SDK 版本号 */
    val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    /** 当前 Android 版本名（如 "7.0"） */
    val androidReleaseName: String
        get() = Build.VERSION.RELEASE ?: "unknown"

    /** 主页面枚举 */
    enum class Page { TERMINAL, FILES, REMOTE, RESOURCES, SETTINGS }

    private const val PREFS_NAME = "termux_api_compat"
    private const val KEY_FORCE_PREFIX = "force_enable_"

    /**
     * 需要特定 API 等级的功能。
     * @param minApi 该功能所需的最低 SDK 版本
     * @param page 所属页面
     * @param requiredVersionLabel 所需 Android 版本的可读名称（用于禁用提示）
     * @param label 功能中文名（用于弹窗显示）
     */
    enum class Feature(
        val minApi: Int,
        val page: Page,
        val requiredVersionLabel: String,
        val label: String
    ) {
        // 终端页
        KEEP_ALIVE_WARNING(31, Page.TERMINAL, "Android 12", "会话保活警告"),
        LIVE_UPDATE_NOTIFICATION(36, Page.TERMINAL, "Android 16", "实时更新通知"),
        // 远程页
        VNC_PICTURE_IN_PICTURE(26, Page.REMOTE, "Android 8.0", "VNC 画中画"),
        VNC_POINTER_CAPTURE(26, Page.REMOTE, "Android 8.0", "VNC 鼠标捕获"),
        VNC_AUDIO(31, Page.REMOTE, "Android 12", "VNC 音频"),
        // 资源页
        QEMU_CONTAINER_MODE(35, Page.RESOURCES, "Android 15", "QEMU 容器模式"),
        QEMU_AUDIO_PULSE(31, Page.RESOURCES, "Android 12", "QEMU PulseAudio 音频"),
        QEMU_VM_MANAGER(31, Page.RESOURCES, "Android 12", "QEMU 虚拟机管理"),
        MOE_ALL_IN_ONE(31, Page.RESOURCES, "Android 12", "MOE 全能脚本"),
        ALPINE_QEMU(31, Page.RESOURCES, "Android 12", "Alpine QEMU"),
        DEBIAN_QEMU(31, Page.RESOURCES, "Android 12", "Debian QEMU"),
        DOCKER_MANAGER(31, Page.RESOURCES, "Android 12", "Docker 容器管理"),
        PULSEAUDIO_PLAYER(31, Page.RESOURCES, "Android 12", "PulseAudio 播放器"),
        // 设置页
        POST_NOTIFICATIONS_RUNTIME(33, Page.SETTINGS, "Android 13", "通知权限"),
        MANAGE_ALL_FILES(30, Page.SETTINGS, "Android 11", "所有文件访问"),
        INTEGRATED_TOOLS(31, Page.SETTINGS, "Android 12", "集成工具（API/Boot/Styling/Tasker/Widget）"),
        MIUIX_DYNAMIC_COLOR(31, Page.SETTINGS, "Android 12", "动态取色主题"),
        GLASS_NAVIGATION_BAR(30, Page.SETTINGS, "Android 11", "玻璃导航栏")
    }

    /** 功能在当前设备是否可用（静态：基于 minApi） */
    fun isAvailable(feature: Feature): Boolean = sdkInt >= feature.minApi

    /** 某页面上被静态屏蔽（不可用）的功能列表 */
    fun disabledFeaturesForPage(page: Page): List<Feature> =
        Feature.values().toList().filter { it.page == page && !isAvailable(it) }

    // ── 用户强制启用（持久化） ──────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户是否已强制启用某个功能（持久化） */
    @Synchronized
    fun isFeatureForceEnabled(context: Context, feature: Feature): Boolean =
        prefs(context).getBoolean(KEY_FORCE_PREFIX + feature.name, false)

    /** 用户确认强制启用某功能（持久化） */
    @Synchronized
    fun setFeatureForceEnabled(context: Context, feature: Feature, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FORCE_PREFIX + feature.name, enabled).apply()
    }

    /** 综合判断：功能是否真正对用户可用（静态可用 OR 用户强制启用） */
    fun isFeatureUsable(context: Context, feature: Feature): Boolean =
        isAvailable(feature) || isFeatureForceEnabled(context, feature)

    /** 是否存在任意被用户强制启用的功能 —— 用于终端卡片升级为红色警告 */
    @Synchronized
    fun hasAnyForceEnabled(context: Context): Boolean {
        val p = prefs(context)
        return Feature.values().any { p.getBoolean(KEY_FORCE_PREFIX + it.name, false) }
    }

    /** 返回所有被强制启用的功能列表（用于卡片展示提示） */
    fun forceEnabledFeatures(context: Context): List<Feature> =
        Feature.values().filter { isFeatureForceEnabled(context, it) }

    /**
     * 一次性关闭所有已强制启用的功能，使其恢复屏蔽状态。
     * 由终端页"已强制启用"红色卡片上的关闭按钮调用。
     */
    @Synchronized
    fun clearAllForceEnabled(context: Context) {
        val editor = prefs(context).edit()
        Feature.values().forEach { editor.remove(KEY_FORCE_PREFIX + it.name) }
        editor.apply()
    }

    // ── 运行时（异常触发）的屏蔽状态 ────────────────────────────────

    @Volatile
    private var sRuntimeDisabledPages: MutableSet<Page> = HashSet()

    @Volatile
    private var sRuntimeDisabledFeatures: MutableSet<Feature> = HashSet()

    /**
     * 把一个页面标记为运行时禁用。一旦标记，[isPageAvailable] 将返回 false，
     * MainScreen 会从底部导航与滑动列表中移除该页面入口。
     */
    @Synchronized
    fun markPageDisabledAtRuntime(page: Page) {
        sRuntimeDisabledPages.add(page)
    }

    /**
     * 把一个功能点标记为运行时禁用。UI 层在渲染该功能入口时检查
     * [isFeatureDisabledAtRuntime]，命中则按钮变灰 + 点击弹 Toast。
     */
    @Synchronized
    fun markFeatureDisabledAtRuntime(feature: Feature) {
        sRuntimeDisabledFeatures.add(feature)
    }

    /** 某页面是否被运行时异常禁用 */
    @Synchronized
    fun isPageDisabledAtRuntime(page: Page): Boolean = sRuntimeDisabledPages.contains(page)

    /** 某功能是否被运行时异常禁用 */
    @Synchronized
    fun isFeatureDisabledAtRuntime(feature: Feature): Boolean = sRuntimeDisabledFeatures.contains(feature)

    /**
     * 当前进程内是否存在任何运行时屏蔽（页面或功能）。
     * 终端页的"低版本警告卡片"仅在此返回 true 时显示，避免 Android 11 等
     * 实际上并无功能缺失的设备被误导。
     */
    @Synchronized
    fun hasAnyRuntimeDisabled(): Boolean =
        sRuntimeDisabledPages.isNotEmpty() || sRuntimeDisabledFeatures.isNotEmpty()

    /**
     * 某页面是否整体可用（有可用功能或核心功能在该 API 下可运行）。
     * 同时综合静态判断与运行时判断：若被运行时标记为禁用，则也视为不可用，
     * 对应的底部导航入口将被隐藏。
     */
    fun isPageAvailable(page: Page): Boolean {
        if (isPageDisabledAtRuntime(page)) return false
        // 各页面核心功能（终端会话、文件管理、VNC/SSH 连接、资源脚本、设置项）
        // 在 API 24 下均可运行，静态维度页面始终可用。
        return true
    }

    // ── miuix UI 渲染失败标记 ────────────────────────────────────────

    /**
     * 是否能加载 miuix UI 组件。默认 true；仅当 FallbackHelper 在实际渲染
     * setContent 期间捕获到 miuix 相关异常并调用 [markMiuixUiFailed] 后才返回 false，
     * 用于 TermuxActivity 等后续页面跳过 miuix 组件初始化，避免重复崩溃。
     */
    @Volatile
    private var sMiuixUiConfirmedFailed: Boolean = false

    fun canLoadMiuixUi(): Boolean = !sMiuixUiConfirmedFailed

    /**
     * 标记 miuix UI 在实际渲染时失败（由 FallbackHelper 在捕获异常后调用）。
     */
    fun markMiuixUiFailed() {
        sMiuixUiConfirmedFailed = true
    }
}
