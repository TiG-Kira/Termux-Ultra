package com.termux.app.plugin

import java.io.File
import java.util.zip.ZipFile

/** Bounded extraction, including archives whose entry sizes are missing or dishonest. */
internal object PluginArchive {
    fun child(root: File, path: String): File {
        val canonicalRoot = root.canonicalFile
        val child = File(root, path).canonicalFile
        require(child.path.startsWith(canonicalRoot.path + File.separator)) {
            "插件包含越界路径: $path"
        }
        return child
    }

    fun extract(source: File, root: File, maxBytes: Long = 256L * 1024 * 1024, maxEntries: Int = 10000) {
        var total = 0L
        var count = 0
        ZipFile(source).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                require(++count <= maxEntries) { "插件文件数量超过限制 ($maxEntries)" }
                val destination = child(root, entry.name)
                if (entry.isDirectory) {
                    check(destination.isDirectory || destination.mkdirs()) { "无法创建插件目录" }
                } else {
                    check(destination.parentFile.isDirectory || destination.parentFile.mkdirs()) { "无法创建插件目录" }
                    zip.getInputStream(entry).use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                total += size
                                require(total <= maxBytes) { "插件解压大小超过限制 ($maxBytes 字节)" }
                                output.write(buffer, 0, size)
                            }
                        }
                    }
                }
            }
        }
    }
}
