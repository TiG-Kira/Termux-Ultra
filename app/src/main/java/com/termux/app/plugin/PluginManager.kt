package com.termux.app.plugin

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.shell.TermuxTask
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.logger.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.termux.app.TermuxService
import android.content.ComponentName

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
            val shellPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash"
            val executionCommand = ExecutionCommand(
                System.currentTimeMillis().toInt(),
                shellPath,
                arrayOf("-c", command),
                null,
                null,
                true,
                false
            )
            executionCommand.commandLabel = "Plugin Shell Command"
            executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_OFF

            val termuxTask = TermuxTask.execute(
                context,
                executionCommand,
                null,
                TermuxShellEnvironmentClient(),
                true
            )

            if (termuxTask == null) {
                val errMsg = executionCommand.resultData.errorsList?.firstOrNull()?.message
                    ?: "命令执行失败: 无法启动 TermuxTask"
                Result.failure(Exception(errMsg))
            } else if (executionCommand.isStateFailed()) {
                val errMsg = executionCommand.resultData.errorsList?.firstOrNull()?.message
                    ?: "命令执行失败 (exit=${executionCommand.resultData.exitCode})"
                Result.failure(Exception(errMsg))
            } else {
                val stdout = executionCommand.resultData.stdout?.toString()?.trim() ?: ""
                val stderr = executionCommand.resultData.stderr?.toString()?.trim() ?: ""
                val exitCode = executionCommand.resultData.exitCode ?: -1

                if (exitCode == 0) {
                    Result.success(stdout)
                } else {
                    Result.failure(Exception("命令执行失败 (exit=$exitCode): $stderr"))
                }
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

// ============================================================
    // 插件持久化会话管理
    // ============================================================

    /** 插件持久化会话注册表：sessionId → PluginPersistentSession */
    private val persistentSessionRegistry = ConcurrentHashMap<String, PluginPersistentSession>()

    /**
     * 打开一个插件持久化会话。
     * 返回的 sessionId 用于后续所有操作。
     */
    fun openPersistentSession(
        context: Context,
        pluginId: String,
        sessionName: String = "Plugin $pluginId",
    ): PluginPersistentSession? {
        if (!hasPermission(context, pluginId, PluginPermission.TERMUX_SESSION_ACCESS)) {
            Logger.logError("PluginManager", "Plugin '$pluginId' 没有 TERMUX_SESSION_ACCESS 权限，无法打开持久化会话")
            return null
        }

        val session = PluginPersistentSession.create(context, pluginId, sessionName) ?: run {
            Logger.logError("PluginManager", "PluginPersistentSession.create() 失败 for plugin='$pluginId'")
            return null
        }

        // 注册进本地注册表
        persistentSessionRegistry[session.sessionId] = session

        // 注册到查找表，让 TermuxSessionClient 退出时能清理
        PluginPersistentSessionRegistry.register(session)

        // 注册进 TermuxService 让终端页面也能管理
        registerWithService(context, session)

        // 等待一次 PS1 提示出现（可选，让插件有东西可以立即 readNew）
        Thread.sleep(400)
        session.resetReadCursor()

        return session
    }

    /** 注册到 TermuxService.mTermuxSessions，让终端页面也能管理这个会话。 */
    private fun registerWithService(context: Context, session: PluginPersistentSession) {
        try {
            val intent = Intent(context, TermuxService::class.java)
            context.bindService(intent, object : android.content.ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: android.os.IBinder) {
                    val service = (binder as TermuxService.LocalBinder).service
                    service.registerPluginSession(session.termuxSession)
                    unbindFromService(context, this)
                }
                override fun onServiceDisconnected(name: ComponentName) {}
            }, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage("PluginManager", "registerWithService failed for ${session.sessionId}", e)
        }
    }

    private fun unbindFromService(context: Context, conn: android.content.ServiceConnection) {
        try { context.unbindService(conn) } catch (_: Exception) {}
    }

    /** 根据 sessionId 获取持久化会话（插件自身或同权限可访问）。 */
    fun getPersistentSession(sessionId: String, pluginId: String): PluginPersistentSession? {
        val s = persistentSessionRegistry[sessionId] ?: return null
        if (s.pluginId != pluginId) {
            Logger.logWarn("PluginManager", "Plugin '$pluginId' 尝试访问不属于它的 session '$sessionId'")
            return null
        }
        return s
    }

    /** 列出指定插件拥有的所有持久化会话。 */
    fun listPersistentSessions(pluginId: String): List<PluginPersistentSession> {
        return persistentSessionRegistry.values.filter { it.pluginId == pluginId }
    }

    /** 关闭并移除一个持久化会话。 */
    fun closePersistentSession(sessionId: String, pluginId: String) {
        val session = getPersistentSession(sessionId, pluginId) ?: return
        PluginPersistentSessionRegistry.unregister(session)
        session.close()
        persistentSessionRegistry.remove(sessionId)
    }

    /** 从注册表移除（在 TermuxService.onTermuxSessionExited 中回调使用）。 */
    internal fun unregisterSession(sessionId: String) {
        persistentSessionRegistry.remove(sessionId)
    }

    /** 清理一个已过期的注册表项（会话进程已退出）。 */
    internal fun cleanupDeadSessions() {
        val dead = persistentSessionRegistry.entries.filter { !it.value.isRunning }
        dead.forEach { (_, s) ->
            PluginPersistentSessionRegistry.unregister(s)
            persistentSessionRegistry.remove(s.sessionId)
        }
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
