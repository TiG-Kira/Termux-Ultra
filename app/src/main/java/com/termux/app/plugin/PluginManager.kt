package com.termux.app.plugin

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object PluginManager {

    private val gson = Gson()

    fun getInstalledPlugins(context: Context): List<InstalledPlugin> {
        return PluginLoader.loadInstalledPlugins(context)
    }

    fun getEnabledPlugins(context: Context): List<InstalledPlugin> {
        return PluginLoader.loadInstalledPlugins(context).filter { it.state == PluginState.ENABLED }
    }

    fun installPlugin(context: Context, sourceFile: File): Result<PluginManifest> {
        return PluginLoader.installPlugin(context, sourceFile)
    }

    fun enablePlugin(context: Context, pluginId: String): Boolean {
        val manifest = PluginLoader.loadPluginManifest(context, pluginId) ?: return false
        val permissions = manifest.getParsedPermissions()

        PluginLoader.setPluginState(context, pluginId, PluginState.ENABLED)
        PluginLoader.grantPermissions(context, pluginId, permissions.toSet())
        return true
    }

    fun disablePlugin(context: Context, pluginId: String) {
        PluginLoader.setPluginState(context, pluginId, PluginState.DISABLED)
    }

    fun uninstallPlugin(context: Context, pluginId: String) {
        PluginLoader.uninstallPlugin(context, pluginId)
    }

    fun getPluginPermissions(context: Context, pluginId: String): Set<PluginPermission> {
        return PluginLoader.loadInstalledPlugins(context)
            .find { it.id == pluginId }
            ?.grantedPermissions ?: emptySet()
    }

    fun grantPermission(context: Context, pluginId: String, permission: PluginPermission) {
        val current = getPluginPermissions(context, pluginId)
        PluginLoader.grantPermissions(context, pluginId, current + permission)
    }

    fun revokePermission(context: Context, pluginId: String, permission: PluginPermission) {
        val current = getPluginPermissions(context, pluginId)
        PluginLoader.grantPermissions(context, pluginId, current - permission)
    }

    fun getPluginById(context: Context, pluginId: String): InstalledPlugin? {
        return getInstalledPlugins(context).find { it.id == pluginId }
    }

    fun getPluginSystemPrompt(context: Context): String {
        val enabledPlugins = getEnabledPlugins(context)
        val sb = StringBuilder()

        for (plugin in enabledPlugins) {
            val systemPrompt = plugin.manifest.systemPrompt ?: continue
            val content = systemPrompt.content.trim()
            if (content.isBlank()) continue

            sb.append("\n\n")
            when (systemPrompt.getPromptMode()) {
                PromptModifyMode.APPEND -> {
                    sb.append("## 插件「${plugin.manifest.name}」追加指令\n")
                    sb.append(content)
                }
                PromptModifyMode.MODIFY -> {
                    sb.append("## 插件「${plugin.manifest.name}」修改指令\n")
                    sb.append("以下指令来自插件，可能修改原有行为：\n\n")
                    sb.append(content)
                }
                PromptModifyMode.OVERWRITE -> {
                    sb.append("## ⚠️ 插件「${plugin.manifest.name}」覆盖指令\n")
                    sb.append("插件声明了 OVERWRITE 模式，以下指令将覆盖原有对应部分：\n\n")
                    sb.append(content)
                }
            }
        }

        return sb.toString()
    }

    fun getPluginSkills(context: Context): List<PluginSkill> {
        val skills = mutableListOf<PluginSkill>()
        val enabledPlugins = getEnabledPlugins(context)

        for (plugin in enabledPlugins) {
            val skillRefs = plugin.manifest.entryPoints?.agentSkills ?: continue
            for (ref in skillRefs) {
                skills.add(
                    PluginSkill(
                        id = "${plugin.id}.${ref.id}",
                        name = ref.name,
                        description = ref.description,
                        category = ref.category,
                        handler = ref.handler,
                        requiresClick = ref.requiresClick,
                        hasOutput = ref.hasOutput,
                        riskLevel = try { SkillRiskLevel.valueOf(ref.riskLevel) } catch (_: Exception) { SkillRiskLevel.NONE },
                        cardFormat = ref.cardFormat
                    )
                )
            }
        }

        return skills
    }

    fun getPluginResourceCards(context: Context): List<PluginResourceCard> {
        val cards = mutableListOf<PluginResourceCard>()
        val enabledPlugins = getEnabledPlugins(context)

        for (plugin in enabledPlugins) {
            val cardRefs = plugin.manifest.entryPoints?.resourceCards ?: continue
            for (ref in cardRefs) {
                cards.add(
                    PluginResourceCard(
                        id = "${plugin.id}.${ref.id}",
                        title = ref.title,
                        description = ref.description,
                        icon = ref.icon,
                        action = PluginAction(
                            type = try { ActionType.valueOf(ref.action.type) } catch (_: Exception) { ActionType.CUSTOM },
                            command = ref.action.command,
                            url = ref.action.url
                        )
                    )
                )
            }
        }

        return cards
    }

    fun getPluginH5Homes(context: Context): List<PluginH5HomeInfo> {
        val homes = mutableListOf<PluginH5HomeInfo>()
        val enabledPlugins = getEnabledPlugins(context)

        for (plugin in enabledPlugins) {
            val h5Ref = plugin.manifest.entryPoints?.h5Home ?: continue
            if (!h5Ref.enabled) continue
            homes.add(
                PluginH5HomeInfo(
                    pluginId = plugin.id,
                    pluginName = plugin.manifest.name,
                    entry = h5Ref.entry
                )
            )
        }

        return homes
    }

    fun getPluginConfig(context: Context, pluginId: String): Map<String, Any> {
        return PluginLoader.getPluginConfig(context, pluginId)
    }

    fun savePluginConfig(context: Context, pluginId: String, config: Map<String, Any>) {
        PluginLoader.savePluginConfig(context, pluginId, config)
    }

    fun hasPermission(context: Context, pluginId: String, permission: PluginPermission): Boolean {
        val plugin = getPluginById(context, pluginId) ?: return false
        return plugin.state == PluginState.ENABLED && plugin.grantedPermissions.contains(permission)
    }

    fun executeShellCommand(context: Context, pluginId: String, command: String): Result<String> {
        if (!hasPermission(context, pluginId, PluginPermission.ROOT_EXECUTE) &&
            !hasPermission(context, pluginId, PluginPermission.TERMUX_SESSION_ACCESS)) {
            return Result.failure(SecurityException("插件没有执行命令的权限"))
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("bash", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Result.success(output.trim())
            } else {
                Result.failure(Exception("命令执行失败 (exit=$exitCode): ${error.trim()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun getPluginFileContent(context: Context, pluginId: String, path: String): String? {
        val file = PluginLoader.getPluginFile(context, pluginId, path) ?: return null
        return try {
            file.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun writePluginConfigValue(context: Context, pluginId: String, key: String, value: Any) {
        val config = getPluginConfig(context, pluginId).toMutableMap()
        config[key] = value
        savePluginConfig(context, pluginId, config)
    }
}

data class PluginH5HomeInfo(
    val pluginId: String,
    val pluginName: String,
    val entry: String
)

data class PluginShellResult(
    val success: Boolean,
    val output: String,
    val error: String,
    val exitCode: Int
)
