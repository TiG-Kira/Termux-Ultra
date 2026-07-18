package com.termux.app.compose

import android.content.Context
import android.os.Environment
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BackupManager {

    private const val TERMUX_DATA_DIR = "/data/data/com.termux/files"
    private const val EXCLUDE_DIR = "$TERMUX_DATA_DIR/home/storage/shared"
    
    @Volatile
    private var isBackupCancelled = false
    
    @Volatile
    private var isRestoreCancelled = false

    fun createBackup(context: Context, onProgress: ((Int, Int, String) -> Unit)? = null): String? {
        isBackupCancelled = false
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
                val totalFiles = countFiles(termuxDir)
                var processedFiles = 0
                addDirectoryToZip(zos, termuxDir, TERMUX_DATA_DIR) {
                    if (isBackupCancelled) {
                        throw InterruptedException("Backup cancelled")
                    }
                    processedFiles++
                    onProgress?.invoke(processedFiles, totalFiles, "正在备份: ${it.name}")
                }
            }

            zos.close()
            fos.close()

            backupFilePath
        } catch (e: InterruptedException) {
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun cancelBackup() {
        isBackupCancelled = true
    }

    fun isBackupRunning(): Boolean {
        return !isBackupCancelled
    }

    private fun addDirectoryToZip(zos: ZipOutputStream, dir: File, basePath: String, onFileProcessed: ((File) -> Unit)? = null) {
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
                addDirectoryToZip(zos, file, basePath, onFileProcessed)
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
                onFileProcessed?.invoke(file)
            }
        }
    }

    private fun countFiles(dir: File): Int {
        var count = 0
        val files = dir.listFiles() ?: return 0
        for (file in files) {
            val filePath = file.absolutePath
            if (filePath.startsWith(EXCLUDE_DIR)) {
                continue
            }
            if (file.isDirectory) {
                count += countFiles(file)
            } else {
                count++
            }
        }
        return count
    }

    private fun getFileStat(file: File): ByteArray {
        val stat = file.absolutePath.getFileStat()
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(stat)
        dos.flush()
        return baos.toByteArray()
    }

    fun restoreBackup(context: Context, backupPath: String, onProgress: ((Int, Int, String) -> Unit)? = null): Boolean {
        isRestoreCancelled = false
        return try {
            val zipFile = File(backupPath)
            if (!zipFile.exists()) return false

            val fis = FileInputStream(zipFile)
            val zis = java.util.zip.ZipInputStream(fis)

            var entry: ZipEntry?
            
            val totalEntries = countZipEntries(zipFile)
            var processedEntries = 0
            
            while (zis.nextEntry.also { entry = it } != null) {
                if (isRestoreCancelled) {
                    throw InterruptedException("Restore cancelled")
                }
                
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
                
                processedEntries++
                onProgress?.invoke(processedEntries, totalEntries, "正在恢复: ${entry?.name}")
                zis.closeEntry()
            }

            zis.close()
            fis.close()

            true
        } catch (e: InterruptedException) {
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun cancelRestore() {
        isRestoreCancelled = true
    }

    fun isRestoreRunning(): Boolean {
        return !isRestoreCancelled
    }
    
    private fun countZipEntries(zipFile: File): Int {
        var count = 0
        try {
            val fis = FileInputStream(zipFile)
            val zis = java.util.zip.ZipInputStream(fis)
            while (zis.nextEntry != null) {
                count++
                zis.closeEntry()
            }
            zis.close()
            fis.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return count
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