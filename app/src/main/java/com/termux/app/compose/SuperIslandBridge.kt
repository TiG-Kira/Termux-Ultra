package com.termux.app.compose

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import com.lab.island.sdk.island.IslandClient
import com.lab.island.sdk.island.IslandDraftBuilder
import com.lab.island.sdk.island.IslandDuration
import com.lab.island.sdk.island.IslandScene
import com.lab.island.sdk.island.PublishKind
import com.lab.island.sdk.island.PublishOutcome

/**
 * 小米 HyperOS 超级岛通知桥接（供 TermuxService 前台通知使用）。
 *
 * 流程：HyperOS 检测 → 尝试上岛（IslandClient.publish / updateActive）→
 * 上岛成功（PublishKind.SUPER_ISLAND）→ 回调 shown=true，岛接管展示；
 * 上岛失败（通知退化为普通通知 / 出错）→ 桥内先取消 SDK 发出的普通通知（避免与前台通知重复），
 * 再回调 shown=false，由 TermuxService 回退到现有 LiveUpdate 通知逻辑；
 * LiveUpdate 不可用（SDK < 36）时由 buildNotification 的现有退回逻辑降级为普通通知。
 */
object SuperIslandBridge {

    /**
     * [OS4 标记] 当前集成的超级岛 SDK 版本（与 app/build.gradle 的 superIslandSdkVersion 一致）。
     *
     * OS4 禁入原因：HyperOS 4 上 XMSF 认证可"成功"（SDK 回调 SUPER_ISLAND）但岛不渲染，
     * 通知以普通形式残留，故 publishOrUpdate 中对 hyperOsMajorVersion() >= 4 直接禁入。
     *
     * 恢复条件（满足其一）：
     * 1. Xiaomi-SuperIsland-Playground 发布支持 HyperOS 4 的新版本；
     * 2. SDK 或项目更换了不依赖 XMSF 认证的上岛方案。
     * 构建时 build.gradle 的 checkSuperIslandSdkVersion 任务会自动检测上游新版本并提示。
     *
     * 恢复步骤：
     * 1. 更新 build.gradle 的 superIslandSdkVersion 为新版本并验证上岛；
     * 2. 同步更新本常量；
     * 3. 删除 publishOrUpdate 中 hyperOsMajorVersion() >= 4 的禁入判断；
     * 4. 删除本标记注释。
     */
    const val SDK_VERSION_MARKER = "v1"

    /** Java 侧友好的上岛结果回调（SAM 接口，Java lambda 可直接使用） */
    fun interface IslandShownCallback {
        fun onShown(shown: Boolean)
    }

    /** HyperOS 焦点通知白名单（Settings.Secure，JSON 数组，仅系统可写） */
    private const val FOCUS_NOTIFICATION_WHITELIST_KEY = "focus_notification_white_list"

    /** 超级岛 SDK 固定通知 id（IslandController companion NOTIFICATION_ID = 51_000，SDK v1） */
    private const val SDK_NOTIFICATION_ID = 51_000

    /** 是否为小米 HyperOS 设备（仅 API 31+ 尝试，与超级岛 SDK minSdk 一致）。 */
    @JvmStatic
    fun isHyperOs(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            if (getSystemProperty("ro.mi.os.version.name")
                ?.contains("HyperOS", ignoreCase = true) == true
            ) {
                true
            } else {
                // MIUI 旧属性兜底（部分 HyperOS 设备仅保留此属性）
                !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 解析 HyperOS 主版本号（如 "HyperOS 4.0.1.x" -> 4）；非 HyperOS 或无法解析时返回 -1。
     */
    private fun hyperOsMajorVersion(): Int = try {
        val name = getSystemProperty("ro.mi.os.version.name") ?: return -1
        Regex("HyperOS\\s*(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    } catch (_: Throwable) {
        -1
    }

    private fun getSystemProperty(key: String): String? = try {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /**
     * 超级岛是否兼容当前设备（HyperOS 且主版本 < 4）。
     * OS4 上 SDK 完全不使用（连初始化都不做），仅走默认 LiveUpdate：
     * 实时通知在 OS4 由系统自动以实时通知样式上浮，无需 SDK。
     */
    @JvmStatic
    fun isIslandCompatible(): Boolean = isHyperOs() && hyperOsMajorVersion() < 4

    /**
     * 系统级清理 SDK 残留通知（不初始化 SDK，任何 OS 版本可安全调用）。
     */
    @JvmStatic
    fun cancelResidue(context: Context) {
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(SDK_NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    /**
     * 发布/更新超级岛通知。
     *
     * @param title   通知标题
     * @param text    通知正文（与前台通知一致）
     * @param pillText 药丸短文本（与 LiveUpdate shortCriticalText 一致）
     * @param shown   true=成功上岛；false=失败（内部已取消 SDK 的普通通知，调用方走现有通知逻辑）
     */
    @JvmStatic
    fun publishOrUpdate(
        context: Context,
        title: String,
        text: String,
        pillText: String,
        shown: IslandShownCallback
    ) {
        val client = try {
            IslandClient.get(context)
        } catch (_: Throwable) {
            shown.onShown(false)
            return
        }
        // [OS4 标记] HyperOS 4 的超级岛协议暂不兼容（XMSF 认证可"成功"但岛不渲染，
        // 通知会以普通形式残留）：完全不走 SDK，仅清理可能的历史残留通知。
        // 恢复方法见 SDK_VERSION_MARKER 注释。
        if (!isIslandCompatible()) {
            cancelResidue(context)
            shown.onShown(false)
            return
        }
        try {
            val draft = IslandDraftBuilder()
                .scene(IslandScene.GENERAL)
                .title(title)
                .subtitle(text)
                .source("Termux Ultra")
                .trailingText(pillText)
                .digitText("")
                .duration(IslandDuration.UNTIL_CANCELLED)
                .targetPackageName(context.packageName)
                .build()
            val callback = { outcome: PublishOutcome ->
                val ok = outcome.kind == PublishKind.SUPER_ISLAND
                if (!ok) {
                    // 上岛失败：SDK 已把通知发成普通通知，取消它避免与前台通知重复
                    cancelQuietly(client)
                }
                shown.onShown(ok)
            }
            if (client.activeIslands.value.isNotEmpty()) {
                // 已有活跃岛：实时更新（SDK 内部自动合并高频更新）
                client.updateActive(draft) { outcome -> callback(outcome) }
            } else {
                // 首次上岛
                client.publish(draft) { outcome -> callback(outcome) }
            }
        } catch (_: Throwable) {
            cancelQuietly(client)
            shown.onShown(false)
        }
    }

    /**
     * 是否已获得 HyperOS 焦点通知权限。
     * 读取系统焦点通知白名单（JSON 数组），兼容不同字段命名；
     * 白名单不可读或解析失败时退化为包名子串判定。
     */
    @JvmStatic
    fun isFocusNotificationGranted(context: Context): Boolean {
        if (!isHyperOs()) return true
        return try {
            val raw = Settings.Secure.getString(
                context.contentResolver, FOCUS_NOTIFICATION_WHITELIST_KEY
            )
            if (raw.isNullOrEmpty()) {
                false
            } else {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val pkg = obj.optString("package_name", obj.optString("pkg"))
                    if (pkg == context.packageName) {
                        return obj.optBoolean("is_open", obj.optBoolean("open", false))
                    }
                }
                false
            }
        } catch (_: Throwable) {
            try {
                val raw = Settings.Secure.getString(
                    context.contentResolver, FOCUS_NOTIFICATION_WHITELIST_KEY
                )
                !raw.isNullOrEmpty() && raw.contains(context.packageName)
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * 跳转系统应用通知设置页（HyperOS 的应用通知详情页内含「焦点通知」开关）。
     */
    @JvmStatic
    fun openFocusNotificationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Throwable) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
            }
        }
    }

    /** 取消超级岛及其通知（OS4 上不初始化 SDK，仅系统级清理）。 */
    @JvmStatic
    fun cancel(context: Context) {
        cancelResidue(context)
        if (isIslandCompatible()) {
            try {
                IslandClient.get(context).cancel(-1)
            } catch (_: Throwable) {
            }
        }
    }

    private fun cancelQuietly(client: IslandClient) {
        try {
            // 传入任意非 SDK 固定通知 id 的值，SDK 会同时清理其固定 id 通知并重置岛状态
            client.cancel(-1)
        } catch (_: Throwable) {
        }
    }
}
