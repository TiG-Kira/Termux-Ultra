package com.termux.app.utils

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

/**
 * HyperOS / MIUI 版本检测器。
 *
 * HyperOS 2 (对应 MIUI 15) 及以上才支持"焦点通知"。
 * 通过 [ro.miui.ui.version.code] 系统属性判断。
 * MIUI 15 (code >= 1500) ≈ HyperOS 2.0
 * MIUI 16 (code >= 1600) ≈ HyperOS 3.0
 * ...
 */
object HyperOSDetector {

    private const val TAG = "HyperOSDetector"

    /**
     * 当前 MIUI/HyperOS UI version code（如 1400=MIUI14, 1500=HyperOS2）。
     * 非小米/HyperOS 设备返回 0。
     */
    private val uiVersionCode: Int by lazy {
        tryGetProp("ro.miui.ui.version.code")?.toIntOrNull() ?: 0
    }

    /**
     * 设备是否为小米（manufacturer 包含 xiaomi / redmi / poco / blackshark）。
     */
    private val isXiaomiDevice: Boolean by lazy {
        val mf = Build.MANUFACTURER.lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        mf.contains("xiaomi") || mf.contains("redmi") || mf.contains("poco") ||
            mf.contains("blackshark") || brand.contains("xiaomi") || brand.contains("redmi") ||
            brand.contains("poco") || brand.contains("blackshark")
    }

    /** 是否在 HyperOS 2.0 或以上（支持焦点通知的最低版本）。 */
    fun isHyperOS2OrAbove(): Boolean {
        if (!isXiaomiDevice) return false
        // MIUI 15 / HyperOS 2 及以上 code >= 1500
        return uiVersionCode >= 1500
    }

    /** 是否在 HyperOS 3.0 或以上。 */
    fun isHyperOS3OrAbove(): Boolean {
        if (!isXiaomiDevice) return false
        return uiVersionCode >= 1600
    }

    /** 是否小米设备（有可用的超级岛/流体云环境）。 */
    fun isXiaomi(): Boolean = isXiaomiDevice

    private fun tryGetProp(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            val value = method.invoke(null, key, "") as String
            value.ifBlank { null }
        } catch (_: Throwable) {
            tryGetPropFromFile(key)
        }
    }

    private fun tryGetPropFromFile(key: String): String? {
        return try {
            BufferedReader(FileReader("/system/build.prop")).use { reader ->
                reader.lineSequence().firstOrNull { it.startsWith("$key=") }
                    ?.substringAfter('=')
            }
        } catch (_: Throwable) {
            null
        }
    }
}
