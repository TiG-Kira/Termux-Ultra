package com.termux.app.plugin

import com.google.gson.Gson

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val minHostVersion: String = "2.0.0",
    val description: String = "",
    val author: String = "",
    val icon: String? = null,
    val permissions: List<String> = emptyList(),
    val entryPoints: PluginEntryPoints? = null,
    val systemPrompt: PluginSystemPromptRef? = null
) {
    fun getParsedPermissions(): List<PluginPermission> {
        return permissions.mapNotNull { perm ->
            try {
                PluginPermission.valueOf(perm.uppercase())
            } catch (_: Exception) {
                null
            }
        }
    }

    fun getAllH5Entries(): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        entryPoints?.h5Home?.let { h5 ->
            if (h5.enabled && h5.entry.isNotBlank()) {
                entries.add((h5.title ?: name) to h5.entry)
            }
        }
        entryPoints?.pages?.forEach { page ->
            if (page.type == "h5" && !page.entry.isNullOrBlank()) {
                entries.add(page.title to page.entry.orEmpty())
            }
        }
        return entries
    }
}

data class PluginEntryPoints(
    val resourceCards: List<PluginResourceCardRef>? = null,
    val settingItems: List<PluginSettingItemRef>? = null,
    val agentSkills: List<PluginSkillRef>? = null,
    val h5Home: PluginH5HomeRef? = null,
    val pages: List<PluginPageRef>? = null
)

data class PluginResourceCardRef(
    val id: String,
    val title: String,
    val description: String,
    val icon: String? = null,
    val action: PluginActionRef
)

data class PluginActionRef(
    val type: String,
    val command: String? = null,
    val url: String? = null
)

data class PluginSettingItemRef(
    val id: String,
    val label: String,
    val type: String = "switch",
    val defaultValue: Any? = null,
    val options: List<String>? = null
)

data class PluginSkillRef(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val handler: String,
    val requiresClick: Boolean = true,
    val hasOutput: Boolean = false,
    val riskLevel: String = "NONE",
    val cardFormat: Map<String, Any>? = null
)

data class PluginH5HomeRef(
    val enabled: Boolean = false,
    val entry: String = "web/index.html",
    val title: String? = null
)

data class PluginPageRef(
    val id: String,
    val title: String,
    val icon: String? = null,
    val type: String = "h5",
    val entry: String? = null
)

data class PluginSystemPromptRef(
    val mode: String = "APPEND",
    val content: String,
    val cardFormat: Map<String, Any>? = null
) {
    fun getPromptMode(): PromptModifyMode {
        return try {
            PromptModifyMode.valueOf(mode.uppercase())
        } catch (_: Exception) {
            PromptModifyMode.APPEND
        }
    }
}

object PluginManifestParser {
    private val gson = Gson()

    fun parse(json: String): Result<PluginManifest> {
        return try {
            val manifest = gson.fromJson(json, PluginManifest::class.java)
            validate(manifest)
            Result.success(manifest)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun validate(manifest: PluginManifest) {
        require(manifest.id.isNotBlank()) { "插件 ID 不能为空" }
        require(manifest.name.isNotBlank()) { "插件名称不能为空" }
        require(manifest.version.isNotBlank()) { "插件版本不能为空" }
        require(manifest.id.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]*$"))) {
            "插件 ID 格式不正确，应使用反向域名格式（如 com.example.plugin）"
        }
    }

    fun fromManifest(manifest: PluginManifest): PluginManifest {
        return manifest
    }
}