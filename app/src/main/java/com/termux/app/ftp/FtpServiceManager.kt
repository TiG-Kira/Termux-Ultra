package com.termux.app.ftp

import android.content.Context

object FtpServiceManager {
    private var ftpServer: FtpServer? = null
    private var currentPort: Int = 8021
    private var currentUsername: String = "termux"
    private var currentPassword: String = ""
    private var currentRootDir: String = ""
    /** 监听地址，默认回环，避免默认暴露到局域网 / 公网。 */
    private var currentBindAddress: String = "127.0.0.1"

    /**
     * 口令强度门槛：长度 >= 8 且至少满足 4 类字符（小写 / 大写 / 数字 / 特殊）中的 3 类，
     * 且不能是历史弱默认值。不满足则拒绝启动 FTP，强制用户先设置强口令。
     */
    fun isPasswordStrong(password: String): Boolean {
        if (password.isEmpty()) return false
        if (password.length < 8) return false
        if (password == "termux123") return false
        var lower = false
        var upper = false
        var digit = false
        var special = false
        for (c in password) {
            when {
                c.isLowerCase() -> lower = true
                c.isUpperCase() -> upper = true
                c.isDigit() -> digit = true
                else -> special = true
            }
        }
        val categories = listOf(lower, upper, digit, special).count { it }
        return categories >= 3
    }

    fun start(context: Context): Boolean {
        if (isRunning()) return true

        val prefs = context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
        currentPort = prefs.getInt("sftp_port", 8021)
        currentUsername = prefs.getString("sftp_username", "termux") ?: "termux"
        currentPassword = FtpCredentialStore.getPassword(context)
        currentBindAddress = prefs.getString("sftp_bind_address", "127.0.0.1") ?: "127.0.0.1"
        // 根目录收窄到应用 files 区（优先 $HOME=.../files/home），不再暴露整个应用私有数据目录。
        val home = java.io.File(context.filesDir, "home")
        currentRootDir = if (home.exists() && home.isDirectory) home.absolutePath else context.filesDir.absolutePath

        // 弱口令 / 空口令一律拒绝启动，强制用户在 FTP 信息页设置强口令。
        if (!isPasswordStrong(currentPassword)) {
            return false
        }

        return try {
            ftpServer = FtpServer(currentPort, currentUsername, currentPassword, currentRootDir, currentBindAddress)
            ftpServer?.start()
            Thread.sleep(300)
            val running = isRunning()
            if (running) {
                val appPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                appPrefs.edit().putBoolean("ftp_enabled", true).apply()
            }
            running
        } catch (e: Exception) {
            e.printStackTrace()
            ftpServer = null
            false
        }
    }

    fun stop(context: Context) {
        try {
            ftpServer?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        ftpServer = null
        val appPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        appPrefs.edit().putBoolean("ftp_enabled", false).apply()
    }

    fun isRunning(): Boolean {
        return ftpServer?.isRunning() == true
    }

    fun getPort(): Int = currentPort

    fun getUsername(): String = currentUsername

    fun getPassword(): String = currentPassword

    fun getRootDir(): String = currentRootDir

    fun getBindAddress(): String = currentBindAddress

    fun reloadCredentials(context: Context) {
        val prefs = context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
        currentPort = prefs.getInt("sftp_port", 8021)
        currentUsername = prefs.getString("sftp_username", "termux") ?: "termux"
        currentPassword = FtpCredentialStore.getPassword(context)
        currentBindAddress = prefs.getString("sftp_bind_address", "127.0.0.1") ?: "127.0.0.1"
    }

    fun restartWithNewConfig(context: Context): Boolean {
        val wasRunning = isRunning()
        if (wasRunning) {
            stop(context)
            Thread.sleep(300)
        }
        reloadCredentials(context)
        return if (wasRunning) {
            start(context)
        } else {
            true
        }
    }

}
