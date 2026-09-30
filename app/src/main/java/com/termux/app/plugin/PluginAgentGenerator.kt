package com.termux.app.plugin

import android.content.Context
import com.termux.R
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.termux.app.compose.AiApiClient
import com.termux.app.compose.AiProviderConfig
import com.termux.app.compose.AiTermuxPrefs
import com.termux.app.compose.OpenAiMessage
import com.termux.app.compose.StreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 生成阶段：供 UI 显示进度 */
sealed class PluginGenProgress {
    data object Idle : PluginGenProgress()
    data class Streaming(val text: String) : PluginGenProgress()
    data class Done(val draft: PluginDraft) : PluginGenProgress()
    data class Failed(val message: String, val raw: String = "") : PluginGenProgress()
}

/** 生成结果：文件树 + 解析出的清单摘要 */
data class PluginDraft(
    val files: List<PluginFile>,
    val manifest: PluginManifest
) {
    val fileCount: Int get() = files.size
}

data class PluginFile(val path: String, val content: String)

/**
 * 随心插件：用大模型把自然语言需求变成 .tup 插件包。
 *
 * 本地小模型写不出合规的 manifest.json，所以在线模型是硬要求：
 * 本地模式下必须开启并配置好备用在线模型，且实际调用的就是备用模型。
 */
object PluginAgentGenerator {

    /** 校验结果：要么给出实际会用的配置，要么给出明确的修复指引 */
    sealed class ResolveResult {
        data class Ready(val config: AiProviderConfig, val note: String?) : ResolveResult()
        data class Blocked(val message: String) : ResolveResult()
    }

    fun resolveConfig(context: Context, res: android.content.res.Resources): ResolveResult {
        val cfg = AiTermuxPrefs.getConfig(context)
        val provider = cfg.providerConfig.provider

        if (provider != "local") {
            if (cfg.providerConfig.apiKey.isBlank()) {
                return ResolveResult.Blocked(res.getString(R.string.plugin_dream_needs_online))
            }
            return ResolveResult.Ready(cfg.providerConfig.copy(maxTokens = 8192), null)
        }

        // 本地模式：必须开启备用在线模型，且参数配齐，生成时实际使用的就是它
        val fb = AiTermuxPrefs.getFallbackOnlineConfig(context)
        val usable = AiTermuxPrefs.isFallbackOnlineEnabled(context) && AiTermuxPrefs.isFallbackOnlineConfigReady(context)
        if (!usable) {
            return ResolveResult.Blocked(res.getString(R.string.plugin_dream_needs_fallback))
        }
        val config = AiProviderConfig(
            provider = "custom",
            apiKey = fb.apiKey,
            apiBaseUrl = fb.baseUrl,
            model = fb.model,
            temperature = fb.temperature,
            maxTokens = 8192
        )
        return ResolveResult.Ready(config, res.getString(R.string.plugin_dream_using_fallback, fb.model))
    }

    /** 流式生成：每收到一段文本就回调一次，便于 UI 显示进度 */
    fun generate(
        context: Context,
        config: AiProviderConfig,
        requirement: String,
        isCancelled: () -> Boolean = { false }
    ): Flow<PluginGenProgress> = flow {
        val messages = listOf(
            OpenAiMessage("system", PluginDevSpec.SYSTEM_PROMPT),
            OpenAiMessage("user", "用户需求：$requirement")
        )
        var full = ""
        // collect 的 lambda 里没法直接结束 flow，用标志位把「已失败」带出来，
        // 否则取消/出错后还会继续往下走解析，多报一个无意义的解析失败
        var failed = false
        try {
            AiApiClient.chatStream(context, config, messages, isCancelled).collect { chunk ->
                when (chunk) {
                    is StreamChunk.Content -> {
                        full += chunk.delta
                        emit(PluginGenProgress.Streaming(full))
                    }
                    is StreamChunk.Cancelled -> {
                        failed = true
                        emit(PluginGenProgress.Failed("已取消", full))
                    }
                    is StreamChunk.Error -> {
                        failed = true
                        emit(PluginGenProgress.Failed(chunk.message, full))
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            emit(PluginGenProgress.Failed(e.message ?: "生成异常", full))
            return@flow
        }
        if (failed) return@flow

        val draft = runCatching { parseDraft(full) }.getOrElse {
            emit(PluginGenProgress.Failed(it.message ?: "解析失败", full))
            return@flow
        }
        emit(PluginGenProgress.Done(draft))
    }

    /** 从模型输出中抠出 JSON 并做本地校验 */
    fun parseDraft(raw: String): PluginDraft {
        val json = extractJsonObject(raw)
            ?: throw IllegalArgumentException("未找到 JSON 输出")
        val filesArray = json.getAsJsonArray("files")
            ?: throw IllegalArgumentException("JSON 缺少 files 字段")

        val files = mutableListOf<PluginFile>()
        for (element in filesArray) {
            val obj = element.asJsonObject
            val path = obj.get("path")?.asString?.trim().orEmpty()
            val content = obj.get("content")?.asString.orEmpty()
            if (path.isBlank()) continue
            val normalized = path.trimStart('/')
            if (normalized.contains("..")) throw IllegalArgumentException("非法文件路径: $path")
            files.add(PluginFile(normalized, content))
        }
        if (files.isEmpty()) throw IllegalArgumentException("files 为空")

        val manifestRaw = files.firstOrNull { it.path == "manifest.json" }?.content
            ?: throw IllegalArgumentException("缺少 manifest.json")

        // parse() 内部已做 id/name/version 与 id 正则校验
        val manifest = PluginManifestParser.parse(manifestRaw)
            .getOrElse { throw IllegalArgumentException("manifest.json 校验失败: ${it.message}") }

        // entry 指向的文件必须都在包里，否则安装时才会失败，提前在这里拦住
        val present = files.map { it.path }.toSet()
        val missing = mutableListOf<String>()
        manifest.entryPoints?.h5Home?.takeIf { it.enabled }?.let { home ->
            val entry = home.effectiveEntry
            if (entry !in present) missing.add(entry)
        }
        manifest.entryPoints?.pages?.forEach { page ->
            val entry = page.effectiveEntry
            if (entry != null && entry !in present) missing.add(entry)
        }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("以下 entry 文件缺失: ${missing.joinToString("、")}")
        }

        return PluginDraft(files, manifest)
    }

    /** 把生成的文件树打包成 .tup（就是 zip） */
    fun writeTup(context: Context, draft: PluginDraft): File {
        val dir = File(context.cacheDir, "dream_plugins").apply { mkdirs() }
        val safeId = draft.manifest.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "$safeId-${draft.manifest.version}.tup")
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            for (f in draft.files) {
                zos.putNextEntry(ZipEntry(f.path))
                zos.write(f.content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return file
    }

    private fun extractJsonObject(raw: String): JsonObject? {
        val trimmed = raw.trim()
        val fence = Regex("""```(?:json)?\s*([\s\S]*?)```""").find(trimmed)
        val candidate = fence?.groupValues?.get(1)?.trim() ?: trimmed
        val direct = runCatching {
            JsonParser.parseString(candidate).asJsonObject.takeIf { it.has("files") }
        }.getOrNull()
        if (direct != null) return direct

        // 模型可能在 JSON 前后加了话：截取最外层花括号再试
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            JsonParser.parseString(candidate.substring(start, end + 1)).asJsonObject
        }.getOrNull()?.takeIf { it.has("files") }
    }
}
