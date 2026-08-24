# -*- coding: utf-8 -*-
import io

def apply(path, repls):
    with io.open(path, 'r', encoding='utf-8') as f:
        s = f.read()
    for old, new in repls:
        cnt = s.count(old)
        if cnt != 1:
            print("SKIP [%s] count=%d" % (path, cnt))
            continue
        s = s.replace(old, new)
        print("OK   [%s]" % path)
    with io.open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(s)

p = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\activities\AiTermuxActivity.kt'

apply(p, [
    ('''    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
''',
     '''    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    // ---- 本地大模型状态 ----
    var localDownloading by remember { mutableStateOf(false) }
    var localProgress by remember { mutableStateOf(0f) }
    var localProgressMsg by remember { mutableStateOf("") }
    var localRefresh by remember { mutableStateOf(0) }
    var localLlamaReady by remember { mutableStateOf(AiLocalModel.isLlamaCppInstalled()) }

    LaunchedEffect(Unit) { AiLocalModel.init(ctx) }
    LaunchedEffect(localRefresh) { localLlamaReady = AiLocalModel.isLlamaCppInstalled() }
'''),
])

apply(p, [
    ('''    fun updateConfig(newConfig: AiTermuxConfig) {
        config = newConfig.copy(isConfigured = newConfig.providerConfig.apiKey.isNotBlank())
        AiTermuxPrefs.saveConfig(getApplication(), config)
    }''',
     '''    fun updateConfig(newConfig: AiTermuxConfig) {
        val configured = if (newConfig.providerConfig.provider == "local") {
            AiLocalModel.isLocalModelReady()
        } else {
            newConfig.providerConfig.apiKey.isNotBlank()
        }
        config = newConfig.copy(isConfigured = configured)
        AiTermuxPrefs.saveConfig(getApplication(), config)
    }'''),
])

apply(p, [
    ('''@Composable
private fun SectionTitle(text: String) {''',
     '''private fun formatByteCount(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

@Composable
private fun SectionTitle(text: String) {'''),
])
print("PART1 DONE")