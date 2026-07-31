package com.termux.app.compose

import android.content.Context
import android.os.Environment
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.termux.TermuxConstants
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

/**
 * Backup & restore manager backed by Termux's own `termux-backup` / `termux-restore` commands.
 *
 * The commands run in a background shell process (using the Termux environment), so they never
 * appear in the terminal page or any foreground terminal session. Output is streamed line by line
 * to report progress, and the process exits immediately on completion, returning the result via
 * the exit code.
 *
 * Reference: https://wiki.termux.com/wiki/Backing_up_Termux
 */
object BackupManager {

    private const val TERMUX_SHELL = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh"
    private const val TERMUX_HOME = TermuxConstants.TERMUX_HOME_DIR_PATH

    @Volatile
    private var isBackupCancelled = false

    @Volatile
    private var isRestoreCancelled = false

    @Volatile
    private var backupProcess: Process? = null

    @Volatile
    private var restoreProcess: Process? = null

    private val envClient = TermuxShellEnvironmentClient()

    /** Build the Termux shell environment array for [Runtime.exec]. */
    private fun buildEnv(context: Context): Array<String> {
        return envClient.buildEnvironment(context, false, TERMUX_HOME)
    }

    /**
     * Create a backup by running `termux-backup <path>`.
     *
     * @return the absolute path of the created backup file on success, or `null` on failure/cancel.
     */
    fun createBackup(context: Context, onProgress: ((Int, Int, String) -> Unit)? = null): String? {
        isBackupCancelled = false
        return try {
            val backupDir = File(Environment.getExternalStorageDirectory(), "TermuxBackup")
            backupDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val backupFile = File(backupDir, "termuxbackup_$timestamp.tar.xz")
            val backupPath = backupFile.absolutePath

            val env = buildEnv(context)
            // Escape the path for the inner shell.
            val command = "termux-backup \"$backupPath\""
            val process = Runtime.getRuntime().exec(arrayOf(TERMUX_SHELL, "-c", command), env, File(TERMUX_HOME))
            backupProcess = process

            val currentSizeMB = AtomicInteger(0)
            onProgress?.invoke(0, -1, "")

            // Stream stdout/stderr lines as progress messages.
            val readerThread = Thread {
                try {
                    val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                    val stderr = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))
                    var line: String?
                    while (stdout.readLine().also { line = it } != null) {
                        if (isBackupCancelled) return@Thread
                        line?.takeIf { it.isNotBlank() }?.let { onProgress?.invoke(currentSizeMB.get(), -1, it) }
                    }
                    while (stderr.readLine().also { line = it } != null) {
                        if (isBackupCancelled) return@Thread
                        line?.takeIf { it.isNotBlank() }?.let { onProgress?.invoke(currentSizeMB.get(), -1, it) }
                    }
                } catch (_: Exception) {
                    // ignore
                }
            }

            // Monitor the growing backup file size as a coarse progress indicator.
            val monitorThread = Thread {
                try {
                    while (process.isAlive) {
                        if (isBackupCancelled) return@Thread
                        val sizeMB = (backupFile.length() / (1024L * 1024L)).toInt()
                        currentSizeMB.set(sizeMB)
                        onProgress?.invoke(sizeMB, -1, "")
                        Thread.sleep(1000)
                    }
                } catch (_: Exception) {
                    // ignore
                }
            }

            readerThread.start()
            monitorThread.start()

            val exitCode = process.waitFor()
            readerThread.join(2000)
            monitorThread.join(2000)
            backupProcess = null

            if (isBackupCancelled) {
                backupFile.delete()
                return null
            }
            if (exitCode != 0) {
                return null
            }
            backupPath
        } catch (e: Exception) {
            e.printStackTrace()
            backupProcess = null
            null
        }
    }

    fun cancelBackup() {
        isBackupCancelled = true
        backupProcess?.destroy()
        backupProcess = null
    }

    fun isBackupRunning(): Boolean {
        return !isBackupCancelled
    }

    /**
     * Restore from [backupPath] by running `termux-restore <path>`.
     *
     * @return `true` on success, `false` on failure/cancel.
     */
    fun restoreBackup(context: Context, backupPath: String, onProgress: ((Int, Int, String) -> Unit)? = null): Boolean {
        isRestoreCancelled = false
        return try {
            val zipFile = File(backupPath)
            if (!zipFile.exists()) {
                onProgress?.invoke(0, 1, "")
                return false
            }

            val env = buildEnv(context)
            val command = "termux-restore \"$backupPath\""
            val process = Runtime.getRuntime().exec(arrayOf(TERMUX_SHELL, "-c", command), env, File(TERMUX_HOME))
            restoreProcess = process

            val totalMB = AtomicInteger((zipFile.length() / (1024L * 1024L)).toInt())
            onProgress?.invoke(0, -1, "")

            // Stream stdout/stderr lines as progress messages.
            val readerThread = Thread {
                try {
                    val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                    val stderr = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))
                    var line: String?
                    while (stdout.readLine().also { line = it } != null) {
                        if (isRestoreCancelled) return@Thread
                        line?.takeIf { it.isNotBlank() }?.let { onProgress?.invoke(totalMB.get(), -1, it) }
                    }
                    while (stderr.readLine().also { line = it } != null) {
                        if (isRestoreCancelled) return@Thread
                        line?.takeIf { it.isNotBlank() }?.let { onProgress?.invoke(totalMB.get(), -1, it) }
                    }
                } catch (_: Exception) {
                    // ignore
                }
            }

            // Heartbeat so the notification keeps showing the restore is ongoing.
            val monitorThread = Thread {
                try {
                    while (process.isAlive) {
                        if (isRestoreCancelled) return@Thread
                        onProgress?.invoke(totalMB.get(), -1, "")
                        Thread.sleep(1000)
                    }
                } catch (_: Exception) {
                    // ignore
                }
            }

            readerThread.start()
            monitorThread.start()

            val exitCode = process.waitFor()
            readerThread.join(2000)
            monitorThread.join(2000)
            restoreProcess = null

            if (isRestoreCancelled) {
                return false
            }
            exitCode == 0
        } catch (e: Exception) {
            e.printStackTrace()
            restoreProcess = null
            false
        }
    }

    fun cancelRestore() {
        isRestoreCancelled = true
        restoreProcess?.destroy()
        restoreProcess = null
    }

    fun isRestoreRunning(): Boolean {
        return !isRestoreCancelled
    }

    fun getBackupFiles(context: Context): List<File> {
        val backupDir = File(Environment.getExternalStorageDirectory(), "TermuxBackup")
        return backupDir.listFiles { _, name ->
            name.startsWith("termuxbackup_") && (name.endsWith(".zip") || name.endsWith(".tar.xz") || name.endsWith(".tar.gz") || name.endsWith(".tar"))
        }?.toList() ?: emptyList()
    }
}
