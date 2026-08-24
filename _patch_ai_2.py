# -*- coding: utf-8 -*-
import io

path = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\activities\AiTermuxActivity.kt'
s = io.open(path, 'r', encoding='utf-8').read()

anchor = '''                    ProviderChip("OpenAI 兼容", "custom", provider, isDark) { provider = it }
                    ProviderChip("直接 OpenAI", "openai", provider, isDark) { provider = it }
                }
            }

            item { SectionTitle("2. API Key（必填）") }'''

block = '''                    ProviderChip("OpenAI 兼容", "custom", provider, isDark) { provider = it }
                    ProviderChip("直接 OpenAI", "openai", provider, isDark) { provider = it }
                    ProviderChip("本地大模型", "local", provider, isDark) { provider = it }
                }
            }

            if (provider == "local") {
                item { SectionTitle("本地大模型（离线 · 设备端运行）") }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDark) Color(0xFF3A2A2A) else Color(0xFFFFF3E0)
                        )
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                text = "⚠️ 资源占用提示",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "本地大模型在设备上通过 llama.cpp 运行，推理时会明显占用 CPU/内存（约 ${LOCAL_MODELS.firstOrNull()?.recommendedRamMB ?: 2}MB 及以上）、发热和耗电。响应速度与生成质量均低于云端大模型，请根据设备性能谨慎选择。",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = TextStyle(lineHeight = 18.sp)
                            )
                        }
                    }
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            LOCAL_MODELS.forEach { model ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (AiLocalModel.isModelInstalled(model)) Color(0xFF16A34A).copy(alpha = 0.15f)
                                                else MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (AiLocalModel.isModelInstalled(model)) "✓" else "↓",
                                            color = if (AiLocalModel.isModelInstalled(model)) Color(0xFF16A34A) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(model.displayName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                                        Text(model.description, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                if (localDownloading) {
                                    if (localProgress > 0f) {
                                        LinearProgressIndicator(progress = localProgress, modifier = Modifier.fillMaxWidth().height(6.dp))
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(localProgressMsg.ifBlank { "正在下载…" }, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                } else if (AiLocalModel.isModelInstalled(model)) {
                                    Text(
                                        "✅ 已安装 · 占用 ${formatByteCount(AiLocalModel.modelFile(model).length())} · 需要 ${model.recommendedRamMB}MB+ 内存",
                                        fontSize = 11.sp,
                                        color = Color(0xFF16A34A)
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            val newCfg = AiTermuxConfig(
                                                providerConfig = AiProviderConfig(
                                                    provider = "local",
                                                    apiKey = "",
                                                    apiBaseUrl = "",
                                                    model = model.displayName,
                                                    temperature = temperature,
                                                    localModelId = model.id
                                                ),
                                                customSystemPrompt = customPrompt,
                                                isConfigured = true
                                            )
                                            vm.updateConfig(newCfg)
                                            SnackbarHelper.show(ctx, "已切换到本地大模型：${model.displayName}", Snackbar.LENGTH_SHORT, null)
                                        },
                                        enabled = localLlamaReady,
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                                    ) {
                                        Text(if (localLlamaReady) "使用本地大模型" else "请先安装 llama.cpp", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                } else {
                                    Text(
                                        "未安装 · 大小约 ${formatByteCount(model.sizeBytes)}",
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            localDownloading = true
                                            localProgress = 0f
                                            localProgressMsg = "准备下载…"
                                            vm.viewModelScope.launch {
                                                val ok = AiLocalModel.downloadModel(model) { p, msg ->
                                                    localProgress = p
                                                    localProgressMsg = msg
                                                }
                                                localDownloading = false
                                                if (ok) {
                                                    localProgressMsg = "✅ 下载完成并已自动配置"
                                                    localProgress = 1f
                                                    testResult = null
                                                } else {
                                                    localProgressMsg = "❌ 下载失败"
                                                }
                                                localRefresh++
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                                    ) {
                                        Text("下载并自动配置（约 ${formatByteCount(model.sizeBytes)}）", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                            if (!localLlamaReady && !localDownloading) {
                                Text("首次使用需联网安装 llama.cpp 运行时，时长取决于网络。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            }

            item { SectionTitle("2. API Key（必填）") }'''

c = s.count(anchor)
print("anchor count =", c)
if c == 1:
    s = s.replace(anchor, block)
    io.open(path, 'w', encoding='utf-8', newline='').write(s)
    print("APPLIED")
else:
    print("NOT APPLIED")