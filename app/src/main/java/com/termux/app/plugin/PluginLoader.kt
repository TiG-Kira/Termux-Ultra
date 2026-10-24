package com.termux.app.plugin

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object PluginLoader {

    private const val PLUGINS_DIR = "plugins"
    private const val TUP_EXTENSION = ".tup"
    private const val ZIP_EXTENSION = ".zip"
    private const val MANIFEST_FILE = "manifest.json"
    private val gson = Gson()

    fun getPluginsDir(context: Context): File {
        val dir = File(context.filesDir, PLUGINS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getPluginDir(context: Context, pluginId: String): File {
        require(pluginId.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]*$"))) { "无效的插件 ID" }
        val dir = PluginArchive.child(getPluginsDir(context), pluginId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun installPlugin(context: Context, sourceFile: File): Result<PluginManifest> {
        return try {
            val ext = sourceFile.extension.lowercase()
            val isValidExt = ext == TUP_EXTENSION.removePrefix(".") || ext == ZIP_EXTENSION.removePrefix(".")
            val isValidZip = try {
                val buf = ByteArray(2)
                sourceFile.inputStream().use { it.read(buf) }
                buf.contentEquals(byteArrayOf(0x50, 0x4B))
            } catch (_: Exception) { false }

            if (!isValidExt && !isValidZip) {
                return Result.failure(IllegalArgumentException("不支持的文件格式，仅支持 .tup 和 .zip"))
            }

            // Stage on the same filesystem as the installed plugins so promotion uses rename.
            val tempDir = java.nio.file.Files.createTempDirectory(getPluginsDir(context).toPath(), ".install-").toFile()

            try {
                PluginArchive.extract(sourceFile, tempDir)

                val manifestFile = File(tempDir, MANIFEST_FILE)
                if (!manifestFile.exists()) {
                    return Result.failure(IllegalArgumentException("插件包中缺少 manifest.json"))
                }
                require(manifestFile.isFile && manifestFile.length() <= 1024 * 1024) {
                    "插件 manifest.json 无效或超过 1 MiB 限制"
                }

                val manifestJson = manifestFile.readText()
                val manifestResult = PluginManifestParser.parse(manifestJson)

                if (manifestResult.isFailure) {
                    return Result.failure(manifestResult.exceptionOrNull() ?: Exception("manifest.json 解析失败"))
                }

                val manifest = manifestResult.getOrThrow()

                checkHostVersion(context, manifest.minHostVersion)
                validatePluginContents(tempDir, manifest)

                val targetDir = getPluginDir(context, manifest.id)
                val previous = File(tempDir.parentFile, tempDir.name + ".previous")
                check(targetDir.renameTo(previous)) { "无法保留旧插件，安装已取消" }
                if (!tempDir.renameTo(targetDir)) {
                    check(previous.renameTo(targetDir)) { "安装失败，旧插件保留在 ${previous.name}" }
                    error("无法写入插件，已恢复旧版本")
                }
                previous.deleteRecursively()

                saveInstallRecord(context, manifest)

                Result.success(manifest)
            } finally {
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun validatePluginContents(pluginDir: File, manifest: PluginManifest) {
        manifest.getAllH5Entries().forEach { (title, entry) ->
            if (entry.isNotBlank()) {
                val entryFile = PluginArchive.child(pluginDir, entry)
                if (!entryFile.isFile) {
                    throw IllegalStateException(
                        "插件 H5 入口文件不存在: $entry (页面: $title)，" +
                        "请确保插件包中包含完整的文件结构"
                    )
                }
            }
        }

        // Compose DSL 页面入口文件校验（pages 子页 + compose 主页）
        manifest.entryPoints?.pages?.forEach { page ->
            val entry = page.effectiveEntry
            if (page.type == "compose" && !entry.isNullOrBlank()) {
                val entryFile = PluginArchive.child(pluginDir, entry)
                if (!entryFile.isFile) {
                    throw IllegalStateException("插件 Compose 页面配置不存在: $entry")
                }
            }
        }
        val home = manifest.entryPoints?.h5Home
        if (home?.enabled == true && home.type == "compose") {
            val entry = home.effectiveEntry
            if (entry.isNotBlank()) {
                val homeFile = PluginArchive.child(pluginDir, entry)
                if (!homeFile.isFile) {
                    throw IllegalStateException("插件 Compose 主页配置不存在: $entry")
                }
            }
        }
    }

    private fun checkHostVersion(context: Context, minVersion: String) {
        try {
            val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.2.0"
            val cleanCurrent = currentVersion.substringBeforeLast(".RB")
            val cleanMin = minVersion.substringBeforeLast(".RB")

            val minParts = cleanMin.split(".")
            val curParts = cleanCurrent.split(".")

            for (i in 0 until minParts.size.coerceAtLeast(curParts.size)) {
                val minPart = minParts.getOrNull(i)?.toIntOrNull() ?: 0
                val curPart = curParts.getOrNull(i)?.toIntOrNull() ?: 0
                if (curPart > minPart) break
                if (curPart < minPart) {
                    throw IllegalStateException(
                        "插件需要 Termux Ultra $minVersion 或更高版本，当前版本为 $currentVersion"
                    )
                }
            }
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
        }
    }

    private fun saveInstallRecord(context: Context, manifest: PluginManifest) {
        val prefs = context.getSharedPreferences("plugin_manager", Context.MODE_PRIVATE)
        val key = "plugin_${manifest.id}"
        val record = InstallRecord(
            id = manifest.id,
            version = manifest.version,
            installedAt = System.currentTimeMillis(),
            state = PluginState.INSTALLED.name
        )
        prefs.edit().putString(key, gson.toJson(record)).apply()
    }

    fun loadInstalledPlugins(context: Context): List<InstalledPlugin> {
        val prefs = context.getSharedPreferences("plugin_manager", Context.MODE_PRIVATE)
        val plugins = mutableListOf<InstalledPlugin>()

        prefs.all.forEach { (key, value) ->
            if (key.startsWith("plugin_") && value is String) {
                try {
                    val type = object : TypeToken<InstallRecord>() {}.type
                    val record = gson.fromJson<InstallRecord>(value, type)
                    val pluginDir = getPluginDir(context, record.id)
                    val manifestFile = File(pluginDir, MANIFEST_FILE)

                    if (manifestFile.exists()) {
                        val manifest = PluginManifestParser.parse(manifestFile.readText()).getOrNull()
                        // manifest 就放在插件目录里，安装后可能被改写：id 与安装目录对不上时
                        // 插件会顶着别人的身份拿权限，这里直接跳过登记。
                        if (manifest != null && PluginSecurity.validatePluginIntegrity(context, record.id)) {
                            val grantedPerms = loadGrantedPermissions(context, record.id)
                            plugins.add(
                                InstalledPlugin(
                                    id = manifest.id,
                                    manifest = manifest,
                                    state = PluginState.valueOf(record.state),
                                    grantedPermissions = grantedPerms,
                                    installPath = pluginDir.absolutePath,
                                    installedAt = record.installedAt,
                                    enabledAt = if (PluginState.valueOf(record.state) == PluginState.ENABLED) record.installedAt else null
                                )
                            )
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        return plugins
    }

    fun loadPluginManifest(context: Context, pluginId: String): PluginManifest? {
        val pluginDir = getPluginDir(context, pluginId)
        val manifestFile = File(pluginDir, MANIFEST_FILE)
        if (!manifestFile.exists()) return null
        return PluginManifestParser.parse(manifestFile.readText()).getOrNull()
    }

    private fun loadGrantedPermissions(context: Context, pluginId: String): Set<PluginPermission> {
        val prefs = context.getSharedPreferences("plugin_manager", Context.MODE_PRIVATE)
        val key = "perms_$pluginId"
        val json = prefs.getString(key, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val strings = gson.fromJson<List<String>>(json, type)
            strings.mapNotNull { s ->
                try { PluginPermission.valueOf(s) } catch (_: Exception) { null }
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun grantPermissions(context: Context, pluginId: String, permissions: Set<PluginPermission>) {
        val prefs = context.getSharedPreferences("plugin_manager", Context.MODE_PRIVATE)
        val key = "perms_$pluginId"
        val names = permissions.map { it.name }
        prefs.edit().putString(key, gson.toJson(names)).apply()
    }

    fun revokeAllPermissions(context: Context, pluginId: String) {
        val prefs = context.getSharedPreferences("plugin_manager", Context.MODE_PRIVATE)
        val key = "perms_$pluginId"
        prefs.edit().remove(key).apply()
    }

    fun setPluginState(context: Context, pluginId: String, state: PluginState) {
        val prefs = context.getSharedPreferences("plugin_manager", Context.MODE_PRIVATE)
        val recordKey = "plugin_$pluginId"
        val json = prefs.getString(recordKey, null) ?: return
        try {
            val type = object : TypeToken<InstallRecord>() {}.type
            val record = gson.fromJson<InstallRecord>(json, type)
            val updatedRecord = record.copy(state = state.name)
            prefs.edit().putString(recordKey, gson.toJson(updatedRecord)).apply()
        } catch (_: Exception) { }
    }

    fun uninstallPlugin(context: Context, pluginId: String) {
        val prefs = context.getSharedPreferences("plugin_manager", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("plugin_$pluginId")
            .remove("perms_$pluginId")
            .remove("config_$pluginId")
            .apply()

        val pluginDir = getPluginDir(context, pluginId)
        if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        }
    }

    fun getPluginConfig(context: Context, pluginId: String): Map<String, Any> {
        val prefs = context.getSharedPreferences("plugin_manager", Context.MODE_PRIVATE)
        val key = "config_$pluginId"
        val json = prefs.getString(key, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun savePluginConfig(context: Context, pluginId: String, config: Map<String, Any>) {
        val prefs = context.getSharedPreferences("plugin_manager", Context.MODE_PRIVATE)
        val key = "config_$pluginId"
        prefs.edit().putString(key, gson.toJson(config)).apply()
    }

    fun getPluginFile(context: Context, pluginId: String, relativePath: String): File? {
        val pluginDir = getPluginDir(context, pluginId)
        return runCatching { PluginArchive.child(pluginDir, relativePath) }.getOrNull()?.takeIf { it.exists() }
    }

    private data class InstallRecord(
        val id: String = "",
        val version: String = "",
        val installedAt: Long = 0L,
        val state: String = "INSTALLED"
    )
}
