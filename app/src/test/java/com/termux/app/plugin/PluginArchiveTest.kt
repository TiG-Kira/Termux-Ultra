package com.termux.app.plugin

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, String>): File {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test fun extractsNormalFiles() {
        val root = temporary.newFolder()
        PluginArchive.extract(archive("web/index.html" to "hello"), root)
        assertEquals("hello", File(root, "web/index.html").readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversal() {
        PluginArchive.extract(archive("../escaped" to "bad"), temporary.newFolder())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSiblingWithSamePrefix() {
        val root = File(temporary.root, "plugin").apply { mkdir() }
        PluginArchive.child(root, "../plugin-evil/secret")
    }

    @Test(expected = IllegalArgumentException::class)
    fun limitsActualExpandedBytes() {
        PluginArchive.extract(archive("large" to "x".repeat(10000)), temporary.newFolder(), maxBytes = 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun limitsEntryCount() {
        PluginArchive.extract(archive("one" to "1", "two" to "2"), temporary.newFolder(), maxEntries = 1)
    }
}
