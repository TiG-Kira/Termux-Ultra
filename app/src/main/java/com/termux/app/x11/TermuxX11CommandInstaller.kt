package com.termux.app.x11

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * 确保终端里的 `termux-x11` 命令认得到 Termux Ultra 内置的 X11（宿主包 com.termux）。
 *
 * 背景：用户常按上游文档 `pkg install termux-x11`，但上游脚本写死包名 com.termux.x11 去
 * 检测独立 Termux:X11 App，而本应用已把 X11 内置进 com.termux，故报 "application is not found"。
 * 这里把修正后的 wrapper（assets/termux-x11）落地到 $PREFIX/bin，覆盖 apt 安装的损坏版。
 */
object TermuxX11CommandInstaller {
    private const val MARKER = "TERMUX_ULTRA_X11_WRAPPER"
    private const val ASSET_NAME = "termux-x11"
    private const val TARGET_NAME = "termux-x11"

    /**
     * 把内置 wrapper 写到 $PREFIX/bin/termux-x11。
     * - bootstrap 未解压时等待（首次启动），超时则下次启动再试；
     * - 已是我们版本（含 MARKER）则跳过；
     * - 仅当命令缺失，或确实是上游损坏版（含其特有报错）时才覆盖，避免误删用户自定义脚本。
     */
    @JvmStatic
    fun ensureX11Command(context: Context) {
        val binDir = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH)
        // bootstrap 可能尚未解压（首次启动），最多等 15s
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
        val existing = if (target.exists()) runCatching { target.readText() }.getOrNull() ?: "" else ""

        // 已是我们版本 → 跳过
        if (existing.contains(MARKER)) return
        // 仅当缺失或确为上游损坏版时才覆盖（避免误删用户自定义脚本）
        val isBrokenUpstream = existing.contains("Termux:X11 application is not found")
        if (existing.isNotEmpty() && !isBrokenUpstream) return

        runCatching {
            context.assets.open(ASSET_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, false)
            target.setReadable(true, false)
        }
    }
}
