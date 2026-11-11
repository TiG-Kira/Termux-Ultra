package com.termux.app.compose

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** 把 assets/msdos_start.sh 落到 Termux 内部，并写好启动参数文件。 */
object MsDosLauncher {
    private const val ASSET = "msdos_start.sh"
    private const val SCRIPT = "$TERMUX_HOME_ABS/msdos/msdos_start.sh"
    private const val RUN_SCRIPT = "$TERMUX_HOME_ABS/debian-container/run.sh"

    private val ALLOWED_MOUNT_ROOTS = listOf(
        "$TERMUX_HOME_ABS/",
        "/sdcard/",
        "/storage/emulated/0/"
    )

    enum class Mode(val cli: String) {
        CONSOLE("console"),
        VNC("vnc")
    }

    /** Android 15+ 原生 QEMU 被 linker namespace 卡死，容器缺失时必须显式提示。 */
    fun isContainerMissing(): Boolean =
        shouldUseQemuInContainer() && !File(RUN_SCRIPT).exists()

    /** 返回可直接交给 createTermuxSession 的 shell 命令；失败返回 null。 */
    fun buildCommand(
        context: Context,
        mode: Mode,
        vncPort: Int = MsDosPrefs.config.value.vncPort
    ): String? {
        val dir = File(MsDosAssetDownloader.TERMUX_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null

        try {
            context.assets.open(ASSET).use { input ->
                FileOutputStream(File(dir, ASSET)).use { out -> input.copyTo(out) }
            }
            File(dir, ASSET).setExecutable(true, false)
        } catch (e: Exception) {
            return null
        }

        val cfg = MsDosPrefs.config.value
        val requested = if (cfg.mountEnabled && cfg.mountDir.isNotBlank()) cfg.mountDir else "-"
        val mount = if (requested == "-" || isValidMountDir(requested)) requested else "-"

        // 纯 KEY=VALUE，脚本逐行读取而不 source，避免把用户路径当 shell 代码执行。
        File(dir, "msdos_args").writeText(
            "MODE=${mode.cli}\nVNC_PORT=$vncPort\nMOUNT_DIR=$mount\n"
        )

        // Android 15+ 的 linker namespace 限制让原生 QEMU 起不来，复用已有 proot 容器。
        return if (shouldUseQemuInContainer()) {
            "$RUN_SCRIPT /root/shared/msdos/$ASSET"
        } else {
            "bash $SCRIPT"
        }
    }

    private fun isValidMountDir(path: String): Boolean {
        if (!path.startsWith("/") || ".." in path ||
            path.any { it == '\n' || it == '\r' }) return false
        val allowed = ALLOWED_MOUNT_ROOTS.any { root ->
            path == root.removeSuffix("/") || path.startsWith(root)
        }
        return allowed && File(path).isDirectory
    }
}
