package com.termux.app.compose

import android.content.Context
import android.os.Environment
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BackupManager {

    private const val TERMUX_DATA_DIR = "/data/data/com.termux/files"
    private const val EXCLUDE_DIR = "$TERMUX_DATA_DIR/home/storage/shared"

    fun createBackup(context: Context): String? {
        return try {
            val backupDir = context.getExternalFilesDir("backups")
            backupDir?.mkdirs() ?: return null

            val timestamp = System.currentTimeMillis()
            val backupFileName = "termuxbackup_$timestamp.zip"
            val backupFilePath = File(backupDir, backupFileName).absolutePath

            val fos = FileOutputStream(backupFilePath)
            val zos = ZipOutputStream(fos)

            val termuxDir = File(TERMUX_DATA_DIR)
            if (termuxDir.exists()) {
                addDirectoryToZip(zos, termuxDir, TERMUX_DATA_DIR)
            }

            zos.close()
            fos.close()

            backupFilePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun addDirectoryToZip(zos: ZipOutputStream, dir: File, basePath: String) {
        val files = dir.listFiles() ?: return

        for (file in files) {
            val filePath = file.absolutePath
            if (filePath.startsWith(EXCLUDE_DIR)) {
                continue
            }

            val entryName = filePath.substring(basePath.length).removePrefix("/")
            
            if (file.isDirectory) {
                val entry = ZipEntry("$entryName/")
                entry.time = file.lastModified()
                zos.putNextEntry(entry)
                zos.closeEntry()
                addDirectoryToZip(zos, file, basePath)
            } else {
                val entry = ZipEntry(entryName)
                entry.time = file.lastModified()
                val fileStat = getFileStat(file)
                entry.setExtra(fileStat)
                zos.putNextEntry(entry)

                val fis = FileInputStream(file)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    zos.write(buffer, 0, bytesRead)
                }
                fis.close()
                zos.closeEntry()
            }
        }
    }

    private fun getFileStat(file: File): ByteArray {
        val stat = file.absolutePath.getFileStat()
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(stat)
        dos.flush()
        return baos.toByteArray()
    }

    fun restoreBackup(context: Context, backupPath: String): Boolean {
        return try {
            val zipFile = File(backupPath)
            if (!zipFile.exists()) return false

            val fis = FileInputStream(zipFile)
            val zis = java.util.zip.ZipInputStream(fis)

            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                entry?.let {
                    val entryPath = "$TERMUX_DATA_DIR/${it.name}"
                    val destFile = File(entryPath)

                    if (it.isDirectory) {
                        destFile.mkdirs()
                    } else {
                        destFile.parentFile?.mkdirs()

                        val fos = FileOutputStream(destFile)
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (zis.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                        fos.close()

                        if (it.extra != null && it.extra.size >= 4) {
                            val dis = DataInputStream(ByteArrayInputStream(it.extra))
                            val mode = dis.readInt()
                            destFile.setExecutable((mode and 0x49) != 0)
                            destFile.setReadable((mode and 0x41) != 0)
                            destFile.setWritable((mode and 0x22) != 0)
                        }
                    }
                }
                zis.closeEntry()
            }

            zis.close()
            fis.close()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun String.getFileStat(): Int {
        val file = File(this)
        var mode = 0
        if (file.canRead()) mode = mode or 0x41
        if (file.canWrite()) mode = mode or 0x22
        if (file.canExecute()) mode = mode or 0x49
        return mode
    }

    fun getBackupFiles(context: Context): List<File> {
        val backupDir = context.getExternalFilesDir("backups")
        return backupDir?.listFiles { _, name -> name.startsWith("termuxbackup_") && name.endsWith(".zip") }?.toList() ?: emptyList()
    }
}