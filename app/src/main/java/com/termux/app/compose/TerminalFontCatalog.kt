package com.termux.app.compose

import android.content.Context
import com.termux.app.utils.FontDownloader
import java.io.InputStream

/**
 * 终端字体的统一目录。
 *
 * 字体分两类：内置在 APK 的 assets/fonts 下的少数几款，以及为了控制 APK 体积
 * 移出、需要时才下载的一批。两类在 UI 上同属一个列表，读取时统一走 [open]。
 */
object TerminalFontCatalog {
    // 不内置于 APK 的字体，选中时才下载。文件名需与上游 termux-styling 仓库保持一致，
    // 否则 [FontDownloader] 拼出的远端地址会 404。
    private val DOWNLOADABLE = listOf(
        "Anonymous-Pro.ttf",
        "D2-Coding.ttf",
        "DejaVu-Sans-Mono.ttf",
        "Fantasque-Sans-Mono.ttf",
        "Fira-Code.ttf",
        "Fira-Mono.ttf",
        "Go-Mono.ttf",
        "Hack.ttf",
        "Hermit.ttf",
        "Inconsolata.ttf",
        "Iosevka.ttf",
        "Liberation-Mono.ttf",
        "Meslo.ttf",
        "Monofur.ttf",
        "Monoid.ttf",
        "OpenDyslexic.ttf",
        "Roboto-Mono.ttf",
        "Source-Code-Pro.ttf",
        "Terminus.ttf",
        "Victor-Mono.ttf"
    )

    fun allFonts(context: Context): List<String> =
        (builtinFonts(context) + DOWNLOADABLE).distinct().sorted()

    fun isAvailable(context: Context, fileName: String): Boolean {
        val downloaded = FontDownloader.downloadedFile(context, fileName)
        if (downloaded.exists() && downloaded.length() > 0) return true
        return context.assets.list("fonts")?.contains(fileName) == true
    }

    fun open(context: Context, fileName: String): InputStream? {
        val downloaded = FontDownloader.downloadedFile(context, fileName)
        if (downloaded.exists() && downloaded.length() > 0) {
            return runCatching { downloaded.inputStream() }.getOrNull()
        }
        return runCatching { context.assets.open("fonts/$fileName") }.getOrNull()
    }

    private fun builtinFonts(context: Context): List<String> =
        context.assets.list("fonts")?.filter { it.endsWith(".ttf") } ?: emptyList()
}
