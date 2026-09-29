package com.termux.app.plugin

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 插件分享与导出。
 *
 * .tup 就是改了后缀的 ZIP，这里把已安装插件目录重新打包回 .tup 供分享，
 * 因为卸载会直接删除磁盘文件，用户想把自己装的插件转给别人时没有别的途径。
 */
object PluginShare {

    /** 分享插件元信息（纯文本） */
    fun shareMeta(context: Context, plugin: InstalledPlugin) {
        val text = buildMetaText(plugin)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, plugin.manifest.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "分享插件信息").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 把插件目录重新打包成 .tup 并分享出去 */
    fun sharePackage(context: Context, plugin: InstalledPlugin): Result<File> {
        return runCatching {
            val file = exportPackage(context, plugin).getOrThrow()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, buildMetaText(plugin))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享插件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            file
        }
    }

    /** 导出插件为 .tup 文件（放到 cacheDir/exports 下） */
    fun exportPackage(context: Context, plugin: InstalledPlugin): Result<File> {
        return runCatching {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safeId = plugin.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(dir, "$safeId-${plugin.manifest.version}.tup")
            zipDirectory(PluginLoader.getPluginDir(context, plugin.id), file)
            file
        }
    }

    fun buildMetaText(plugin: InstalledPlugin): String {
        val m = plugin.manifest
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(plugin.installedAt))
        return buildString {
            appendLine("插件：${m.name}")
            appendLine("版本：${m.version}")
            appendLine("作者：${m.author.ifBlank { "未知" }}")
            appendLine("标识：${m.id}")
            if (m.description.isNotBlank()) {
                appendLine()
                appendLine(m.description)
            }
            val permissions = m.getParsedPermissions()
            if (permissions.isNotEmpty()) {
                appendLine()
                appendLine("权限：${permissions.joinToString("、") { it.name }}")
            }
            val abilities = mutableListOf<String>()
            if (!m.entryPoints?.resourceCards.isNullOrEmpty()) abilities.add("资源卡片")
            if (!m.entryPoints?.agentSkills.isNullOrEmpty()) abilities.add("技能卡片")
            if (m.entryPoints?.h5Home?.enabled == true) abilities.add("H5 主页")
            if (m.systemPrompt != null) abilities.add("System Prompt")
            if (abilities.isNotEmpty()) {
                appendLine("能力：${abilities.joinToString("、")}")
            }
            appendLine("安装时间：$date")
        }
    }

    /** 读取插件声明的图标，读取失败时返回 null 由调用方兜底 */
    fun loadIcon(context: Context, plugin: InstalledPlugin): ImageBitmap? {
        val iconPath = plugin.manifest.icon?.takeIf { it.isNotBlank() } ?: return null
        val file = runCatching { PluginLoader.getPluginFile(context, plugin.id, iconPath) }.getOrNull()
            ?: return null
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
    }

    private fun zipDirectory(sourceDir: File, outFile: File) {
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isDirectory || !file.exists()) return@forEach
                val name = file.relativeTo(sourceDir).path.replace(File.separatorChar, '/')
                zos.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
