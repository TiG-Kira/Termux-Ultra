package com.termux.app.x11

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * 让 Termux Ultra 内置的 X11 以「自带」方式工作，不再迁就 apt 安装的 termux-x11：
 * - [ensureX11Command]：始终把修正后的 wrapper（assets/termux-x11）落地到 $PREFIX/bin/termux-x11；
 *   上游 apt 版写死包名 com.termux.x11 检测独立 App，在本应用（X11 已内置进 com.termux）下必报
 *   "application is not found"，因此直接以自带版本覆盖，不再判断上游是否「损坏」。
 * - [blockAptX11Nightly]：写入 apt preferences（Pin-Priority: -1）屏蔽 apt 上的 termux-x11* 软件包，
 *   避免用户 `pkg install termux-x11-nightly` 把冲突的旧版命令覆盖回来。
 *
 * 二者在 X11 集成开关打开时（以及应用启动且开关已开时）自动执行，幂等、可重复调用。
 */
object TermuxX11CommandInstaller {
    // wrapper 内嵌版本标记（TERMUX_ULTRA_X11_WRAPPER_V<n>），已安装版本号 < 资产版本号才覆盖，
    // 修复升级 APK 后旧 wrapper 不随版本刷新的问题
    private const val MARKER_PREFIX = "TERMUX_ULTRA_X11_WRAPPER_V"
    private const val WRAPPER_VERSION = 2
    private const val ASSET_NAME = "termux-x11"
    private const val TARGET_NAME = "termux-x11"
    private const val APT_PREF_FILENAME = "termux-x11-block"
    // 屏蔽 apt 上的 termux-x11*（含 termux-x11 与 termux-x11-nightly），不再被 apt 安装/升级覆盖
    private const val APT_PREF_BODY = "Package: termux-x11*\nPin: release *\nPin-Priority: -1\n"

    /**
     * 确保 $PREFIX/bin/termux-x11 是自带版本（X11 开启时调用）。
     * - bootstrap 未解压时等待（首次启动/刚开启），超时则下次启动再试；
     * - 已是自带版本（含 MARKER）则跳过，避免每次启动重复写；
     * - 否则无条件用 assets 里的 wrapper 覆盖（自带策略，不判断上游是否损坏）。
     */
    @JvmStatic
    fun ensureX11Command(context: Context) {
        val binDir = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH)
        // bootstrap 可能尚未解压（首次启动/刚开启），最多等 15s
        var waited = 0
        while (!binDir.isDirectory && waited < 15000) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
            waited += 1000
        }
        if (!binDir.isDirectory) return

        val target = File(binDir, TARGET_NAME)
        // 已是同版本或更新的自带版本 → 跳过
        if (target.exists()) {
            val installedVersion = runCatching { target.readText() }.getOrDefault("")
                .let { Regex("$MARKER_PREFIX(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
            if (installedVersion >= WRAPPER_VERSION) return
        }

        runCatching {
            context.assets.open(ASSET_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, false)
            target.setReadable(true, false)
        }
    }

    /**
     * 屏蔽 apt 上的 termux-x11* 软件包（Pin-Priority: -1），幂等、无网络。
     * 写入 $PREFIX/etc/apt/preferences.d/termux-x11-block。
     */
    @JvmStatic
    fun blockAptX11Nightly(context: Context) {
        val prefDir = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc/apt/preferences.d")
        if (!prefDir.isDirectory) runCatching { prefDir.mkdirs() }
        val pref = File(prefDir, APT_PREF_FILENAME)
        runCatching {
            if (pref.exists() && pref.readText() == APT_PREF_BODY) return
            pref.writeText(APT_PREF_BODY)
        }
    }

    /** X11 开启时的一次性装配：自带命令 + 屏蔽 apt 冲突包。 */
    @JvmStatic
    fun ensureBundledX11(context: Context) {
        ensureX11Command(context)
        blockAptX11Nightly(context)
    }
}
