package com.termux.app.plugin

enum class PluginState {
    INSTALLED,
    ENABLED,
    DISABLED,
    CORRUPTED,
    NEEDS_PERMISSION
}

enum class PluginPermission {
    TERMUX_SESSION_ACCESS,
    ROOT_EXECUTE,
    FILE_SYSTEM_READ,
    FILE_SYSTEM_WRITE,
    AGENT_MODIFY,
    H5_WEBVIEW,
    CROSS_APP_BRIDGE,
    INTERNET_ACCESS
}

enum class PermissionRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

val permissionRiskMap: Map<PluginPermission, PermissionRiskLevel> = mapOf(
    PluginPermission.INTERNET_ACCESS to PermissionRiskLevel.LOW,
    PluginPermission.H5_WEBVIEW to PermissionRiskLevel.LOW,
    PluginPermission.TERMUX_SESSION_ACCESS to PermissionRiskLevel.MEDIUM,
    PluginPermission.FILE_SYSTEM_READ to PermissionRiskLevel.MEDIUM,
    PluginPermission.CROSS_APP_BRIDGE to PermissionRiskLevel.MEDIUM,
    PluginPermission.FILE_SYSTEM_WRITE to PermissionRiskLevel.HIGH,
    PluginPermission.ROOT_EXECUTE to PermissionRiskLevel.HIGH,
    PluginPermission.AGENT_MODIFY to PermissionRiskLevel.HIGH
)

enum class PromptModifyMode {
    APPEND,
    MODIFY,
    OVERWRITE
}

enum class SkillRiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class PluginEntryPoint(
    val type: EntryPointType,
    val data: Map<String, Any>
)

enum class EntryPointType {
    RESOURCE_CARD,
    SETTING_ITEM,
    AGENT_SKILL,
    H5_HOME,
    PAGE
}

data class PluginResourceCard(
    val id: String,
    val title: String,
    val description: String,
    val icon: String? = null,
    val action: PluginAction
)

data class PluginAction(
    val type: ActionType,
    val command: String? = null,
    val url: String? = null
)

enum class ActionType {
    SHELL_COMMAND,
    OPEN_URL,
    CUSTOM
}

data class PluginSkill(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val handler: String,
    val requiresClick: Boolean = true,
    val hasOutput: Boolean = false,
    val riskLevel: SkillRiskLevel = SkillRiskLevel.NONE,
    val cardFormat: Map<String, Any>? = null
)

data class PluginH5Home(
    val enabled: Boolean,
    val entry: String
)

data class PluginSystemPrompt(
    val mode: PromptModifyMode,
    val content: String,
    val cardFormat: Map<String, Any>? = null
)

data class PluginConfig(
    val pluginId: String,
    val key: String,
    val value: Any
)

data class InstalledPlugin(
    val id: String,
    val manifest: PluginManifest,
    val state: PluginState,
    val grantedPermissions: Set<PluginPermission>,
    val installPath: String,
    val installedAt: Long,
    val enabledAt: Long? = null
) {
    fun isEnabled(): Boolean = state == PluginState.ENABLED
    fun hasPermission(permission: PluginPermission): Boolean =
        grantedPermissions.contains(permission)
}
