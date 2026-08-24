# -*- coding: utf-8 -*-
import io

def apply(path, repls):
    with io.open(path, 'r', encoding='utf-8') as f:
        s = f.read()
    for old, new in repls:
        cnt = s.count(old)
        if cnt != 1:
            print("SKIP %s :: pattern count=%d :: %r" % (path, cnt, old[:60]))
            continue
        s = s.replace(old, new)
        print("OK   %s :: %r" % (path, old[:50]))
    with io.open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(s)

base = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app'

# ---------------- E1: AiTermuxModels.kt ----------------
apply(base + r'\compose\AiTermuxModels.kt', [
    ('''data class AiProviderConfig(
    val provider: String = "custom",          // "openai", "custom"
    val apiKey: String = "",
    val apiBaseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val temperature: Float = 0.7f
)''',
     '''data class AiProviderConfig(
    val provider: String = "custom",          // "openai", "custom", "local"
    val apiKey: String = "",
    val apiBaseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val temperature: Float = 0.7f,
    val localModelId: String = ""             // 本地大模型标识（provider == "local" 时使用）
)'''),
])

# ---------------- E2: AiLocalModel.kt (Qwen2.5-1.5B) ----------------
apply(base + r'\compose\AiLocalModel.kt', [
    ('''    LocalModelEntry(
        id = "qwen2.5-0.5b-q4km",
        displayName = "Qwen2.5-0.5B-Instruct",
        description = "面向 Android 设备的轻量对话模型（Q4_K_M 量化，约 450MB）",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        sizeBytes = 443_000_000L,
        maxTokens = 512,
        recommendedRamMB = 1024
    )''',
     '''    LocalModelEntry(
        id = "qwen2.5-1.5b-q4km",
        displayName = "Qwen2.5-1.5B-Instruct",
        description = "面向 Android 设备的对话模型（Q4_K_M 量化，约 950MB）",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sizeBytes = 990_000_000L,
        maxTokens = 512,
        recommendedRamMB = 2048
    )'''),
])

# ---------------- E3: AiTermuxEngine.kt (local routing) ----------------
apply(base + r'\compose\AiTermuxEngine.kt', [
    (''': ChatCompletionResponse = withContext(Dispatchers.IO) {
        try {''',
     ''': ChatCompletionResponse = withContext(Dispatchers.IO) {
        // 本地大模型：走设备端 llama.cpp 推理
        if (config.provider == "local") {
            return@withContext AiLocalModel.completeLocal(config, messages)
        }
        try {'''),
    (''': ChatCompletionResponse = withContext(Dispatchers.IO) {
        try {''',
     ''': ChatCompletionResponse = withContext(Dispatchers.IO) {
        // 本地大模型：走设备端 llama.cpp 推理
        if (config.provider == "local") {
            return@withContext AiLocalModel.completeLocal(config, messages)
        }
        try {'''),
    ('''    ): Flow<StreamChunk> {
        return flow {''',
     '''    ): Flow<StreamChunk> {
        // 本地大模型：走设备端 llama.cpp 流式推理
        if (config.provider == "local") {
            return AiLocalModel.chatStreamLocal(config, messages, isCancelled)
        }
        return flow {'''),
])

print("DONE")