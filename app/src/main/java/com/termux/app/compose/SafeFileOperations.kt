package com.termux.app.compose

import java.io.File
import java.io.IOException
import java.nio.file.Files

internal object SafeFileOperations {
    fun transfer(source: File, destination: File, move: Boolean) {
        val src = source.canonicalFile
        val dest = destination.canonicalFile
        require(src != dest && !dest.path.startsWith(src.path + File.separator)) {
            "不能将文件或目录粘贴到自身或子目录"
        }
        check(!Files.exists(destination.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "目标已存在，请重命名后重试: ${destination.name}"
        }
        // Preflight the entire tree before moving/deleting anything. Do not follow link cycles.
        validateTree(source)
        if (move && source.renameTo(destination)) return
        copyTree(source, destination)
        if (move && !source.deleteRecursively()) {
            throw IOException("已复制，但源文件删除失败: ${source.name}")
        }
    }

    private fun validateTree(file: File) {
        require(!Files.isSymbolicLink(file.toPath())) { "暂不支持复制符号链接: ${file.name}" }
        if (file.isDirectory) {
            val children = file.listFiles() ?: throw IOException("无法读取目录: ${file.name}")
            children.forEach { validateTree(it) }
        } else {
            require(file.isFile && file.canRead()) { "无法读取文件: ${file.name}" }
        }
    }

    private fun copyTree(source: File, destination: File) {
        if (source.isDirectory) {
            check(destination.mkdir()) { "无法创建目录: ${destination.name}" }
            val children = source.listFiles() ?: throw IOException("无法读取目录: ${source.name}")
            children.forEach { copyTree(it, File(destination, it.name)) }
        } else {
            source.copyTo(destination, overwrite = false)
        }
    }
}
