package com.termux.app.plugin

import android.content.Context
import com.termux.shared.termux.TermuxConstants

object PluginSecurity {

    data class PermissionCheckResult(
        val allowed: Boolean,
        val requiresUserConsent: Boolean = false,
        val reason: String? = null
    )

    fun canExecuteShellCommand(
        context: Context,
        pluginId: String,
        command: String
    ): PermissionCheckResult {
        val plugin = PluginManager.getPluginById(context, pluginId)
            ?: return PermissionCheckResult(false, reason = "插件不存在")

        if (plugin.state != PluginState.ENABLED) {
            return PermissionCheckResult(false, reason = "插件未启用")
        }

        val hasRoot = plugin.grantedPermissions.contains(PluginPermission.ROOT_EXECUTE)
        val hasSession = plugin.grantedPermissions.contains(PluginPermission.TERMUX_SESSION_ACCESS)

        if (!hasRoot && !hasSession) {
            return PermissionCheckResult(false, reason = "插件没有执行命令的权限")
        }

        val riskLevel = assessCommandRisk(command)
        if (riskLevel >= PermissionRiskLevel.HIGH && !hasRoot) {
            return PermissionCheckResult(
                allowed = false,
                requiresUserConsent = true,
                reason = "命令包含高危操作，需要 ROOT 权限或用户确认"
            )
        }

        return PermissionCheckResult(true)
    }

    private fun assessCommandRisk(command: String): PermissionRiskLevel {
        val dangerousPatterns = listOf(
            "rm -rf /", "rm -rf /*", "dd if=", "mkfs", "shutdown", "reboot",
            ":(){ :|:& };:", "> /dev/sda", "chmod -R 777 /", "chown -R",
            "iptables", "systemctl stop", "killall", "su ", "sudo "
        )

        for (pattern in dangerousPatterns) {
            if (command.contains(pattern)) {
                return PermissionRiskLevel.HIGH
            }
        }

        val mediumPatterns = listOf(
            "rm ", "mv ", "fdisk", "parted", "format",
            "chmod", "chown", "kill ", "pkill"
        )

        for (pattern in mediumPatterns) {
            if (command.contains(pattern)) {
                return PermissionRiskLevel.MEDIUM
            }
        }

        return PermissionRiskLevel.LOW
    }

    fun canAccessFileSystem(
        context: Context,
        pluginId: String,
        path: String,
        isWrite: Boolean = false
    ): PermissionCheckResult {
        val plugin = PluginManager.getPluginById(context, pluginId)
            ?: return PermissionCheckResult(false, reason = "插件不存在")

        if (plugin.state != PluginState.ENABLED) {
            return PermissionCheckResult(false, reason = "插件未启用")
        }

        val requiredPerm = if (isWrite) PluginPermission.FILE_SYSTEM_WRITE else PluginPermission.FILE_SYSTEM_READ
        if (!plugin.grantedPermissions.contains(requiredPerm)) {
            return PermissionCheckResult(
                allowed = false,
                requiresUserConsent = true,
                reason = "插件没有文件系统${if (isWrite) "写入" else "读取"}权限"
            )
        }

        val termuxBase = "/data/data/com.termux"
        if (!path.startsWith(termuxBase) && !path.startsWith("/data/local/tmp")) {
            return PermissionCheckResult(
                allowed = false,
                reason = "文件访问路径超出沙盒限制"
            )
        }

        if (path.contains("..")) {
            return PermissionCheckResult(false, reason = "路径逃逸检测")
        }

        return PermissionCheckResult(true)
    }

    fun canAccessInternet(
        context: Context,
        pluginId: String,
        url: String
    ): PermissionCheckResult {
        val plugin = PluginManager.getPluginById(context, pluginId)
            ?: return PermissionCheckResult(false, reason = "插件不存在")

        if (plugin.state != PluginState.ENABLED) {
            return PermissionCheckResult(false, reason = "插件未启用")
        }

        if (!plugin.grantedPermissions.contains(PluginPermission.INTERNET_ACCESS)) {
            return PermissionCheckResult(
                allowed = false,
                requiresUserConsent = true,
                reason = "插件没有网络访问权限"
            )
        }

        return PermissionCheckResult(true)
    }

    fun canModifyAgent(
        context: Context,
        pluginId: String
    ): PermissionCheckResult {
        val plugin = PluginManager.getPluginById(context, pluginId)
            ?: return PermissionCheckResult(false, reason = "插件不存在")

        if (plugin.state != PluginState.ENABLED) {
            return PermissionCheckResult(false, reason = "插件未启用")
        }

        if (!plugin.grantedPermissions.contains(PluginPermission.AGENT_MODIFY)) {
            return PermissionCheckResult(
                allowed = false,
                requiresUserConsent = true,
                reason = "插件没有修改 Agent 的权限"
            )
        }

        return PermissionCheckResult(true)
    }

    fun validatePluginIntegrity(context: Context, pluginId: String): Boolean {
        val pluginDir = PluginLoader.getPluginDir(context, pluginId)
        val manifestFile = java.io.File(pluginDir, "manifest.json")

        if (!manifestFile.exists()) return false

        return try {
            val manifest = PluginManifestParser.parse(manifestFile.readText()).getOrNull()
            manifest != null && manifest.id == pluginId
        } catch (_: Exception) {
            false
        }
    }

    fun getPermissionDisplayName(permission: PluginPermission): String {
        return when (permission) {
            PluginPermission.TERMUX_SESSION_ACCESS -> "终端会话访问"
            PluginPermission.ROOT_EXECUTE -> "ROOT 执行"
            PluginPermission.FILE_SYSTEM_READ -> "文件系统读取"
            PluginPermission.FILE_SYSTEM_WRITE -> "文件系统写入"
            PluginPermission.AGENT_MODIFY -> "修改 Agent"
            PluginPermission.H5_WEBVIEW -> "H5 网页视图"
            PluginPermission.CROSS_APP_BRIDGE -> "跨应用桥接"
            PluginPermission.INTERNET_ACCESS -> "网络访问"
        }
    }

    fun getPermissionDescription(permission: PluginPermission): String {
        return when (permission) {
            PluginPermission.TERMUX_SESSION_ACCESS -> "允许插件在 Termux 终端会话中执行命令并获取输出"
            PluginPermission.ROOT_EXECUTE -> "允许插件使用 ROOT 权限执行命令（高危）"
            PluginPermission.FILE_SYSTEM_READ -> "允许插件读取 Termux 环境中的文件"
            PluginPermission.FILE_SYSTEM_WRITE -> "允许插件写入 Termux 环境中的文件（高危）"
            PluginPermission.AGENT_MODIFY -> "允许插件修改 Termux Agent 的 System Prompt 和技能卡片（高危）"
            PluginPermission.H5_WEBVIEW -> "允许插件在应用内显示 H5 网页界面"
            PluginPermission.CROSS_APP_BRIDGE -> "允许插件通过 Intent/Broadcast 与其他应用交互"
            PluginPermission.INTERNET_ACCESS -> "允许插件发起网络请求"
        }
    }

    fun getRiskLevelDisplayName(riskLevel: PermissionRiskLevel): String {
        return when (riskLevel) {
            PermissionRiskLevel.LOW -> "低"
            PermissionRiskLevel.MEDIUM -> "中"
            PermissionRiskLevel.HIGH -> "高"
        }
    }

    fun checkRootAvailability(): Boolean {
        return try {
            val file = java.io.File("/system/bin/su")
            if (file.exists()) return true
            val file2 = java.io.File("/system/xbin/su")
            if (file2.exists()) return true
            val file3 = java.io.File("/data/adb/magisk")
            if (file3.exists()) return true
            java.io.File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/su").exists()
        } catch (e: Exception) {
            false
        }
    }
}
