package com.termux.app.ftp

import android.content.Context
import android.content.SharedPreferences

object FtpServiceManager {
    private var ftpServer: FtpServer? = null
    private var currentPort: Int = 8021
    private var currentUsername: String = "termux"
    private var currentPassword: String = "termux123"
    private var currentRootDir: String = ""

    fun start(context: Context): Boolean {
        if (isRunning()) return true

        val prefs = context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
        currentPort = prefs.getInt("sftp_port", 8021)
        currentUsername = prefs.getString("sftp_username", "termux") ?: "termux"
        currentPassword = prefs.getString("sftp_password", "termux123") ?: "termux123"
        currentRootDir = context.applicationInfo.dataDir

        return try {
            ftpServer = FtpServer(currentPort, currentUsername, currentPassword, currentRootDir)
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

    fun reloadCredentials(context: Context) {
        val prefs = context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE)
        currentPort = prefs.getInt("sftp_port", 8021)
        currentUsername = prefs.getString("sftp_username", "termux") ?: "termux"
        currentPassword = prefs.getString("sftp_password", "termux123") ?: "termux123"
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

    private fun isPortInUse(port: Int): Boolean {
        return try {
            java.net.Socket("127.0.0.1", port).use {
                it.close()
                true
            }
        } catch (e: java.net.ConnectException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}
