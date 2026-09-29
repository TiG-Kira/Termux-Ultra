package com.termux.app.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import com.termux.R
import com.termux.app.utils.SnackbarHelper
import com.google.android.material.snackbar.Snackbar

/**
 * 随心插件：描述需求 → Agent 生成 → 预览 → 安装。
 *
 * 生成前必须确认真的能用在线模型，本地模型写不出合规 manifest，
 * 所以这里先把校验结果摆给用户看，而不是等生成失败再报错。
 */
@Composable
fun PluginDreamDialog(onDismiss: () -> Unit, onInstalled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val res = context.resources

    var requirement by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var blocked by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<PluginDraft?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var rawText by remember { mutableStateOf("") }
    var cancelled by remember { mutableStateOf(false) }

    // 打开弹窗时先做一次前置校验，把「能不能用在线模型」摆在明面上
    LaunchedEffect(Unit) {
        when (val result = PluginAgentGenerator.resolveConfig(context, res)) {
            is PluginAgentGenerator.ResolveResult.Ready -> {
                note = result.note
                blocked = null
            }
            is PluginAgentGenerator.ResolveResult.Blocked -> {
                blocked = result.message
                note = null
            }
        }
    }

    fun startGenerate() {
        if (requirement.isBlank()) {
            SnackbarHelper.show(context, res.getString(R.string.plugin_dream_empty_input), Snackbar.LENGTH_SHORT)
            return
        }
        when (val result = PluginAgentGenerator.resolveConfig(context, res)) {
            is PluginAgentGenerator.ResolveResult.Blocked -> {
                blocked = result.message
                return
            }
            is PluginAgentGenerator.ResolveResult.Ready -> {
                blocked = null
                note = result.note
                generating = true
                errorText = null
                rawText = ""
                draft = null
                cancelled = false
                scope.launch {
                    PluginAgentGenerator.generate(context, result.config, requirement) { cancelled }
                        .collect { progress ->
                            when (progress) {
                                is PluginGenProgress.Streaming -> progressText = progress.text
                                is PluginGenProgress.Done -> {
                                    generating = false
                                    draft = progress.draft
                                }
                                is PluginGenProgress.Failed -> {
                                    generating = false
                                    errorText = progress.message
                                    rawText = progress.raw
                                }
                                PluginGenProgress.Idle -> Unit
                            }
                        }
                }
            }
        }
    }

    fun installDraft() {
        val current = draft ?: return
        val file = runCatching { PluginAgentGenerator.writeTup(context, current) }.getOrElse {
            SnackbarHelper.show(context, "打包失败: ${it.message}", Snackbar.LENGTH_LONG)
            return
        }
        val result = PluginManager.installPlugin(context, file)
        if (result.isSuccess) {
            val manifest = result.getOrThrow()
            // 生成物没有签名风险提示，直接授权并启用，省一次点击
            PluginManager.enablePlugin(context, manifest.id)
            SnackbarHelper.show(context, "插件「${manifest.name}」已安装并启用", Snackbar.LENGTH_SHORT)
            file.delete()
            onInstalled()
            onDismiss()
        } else {
            SnackbarHelper.show(context, "安装失败: ${result.exceptionOrNull()?.message}", Snackbar.LENGTH_LONG)
        }
    }

    WindowDialog(
        show = true,
        title = stringResource(R.string.plugin_dream),
        summary = stringResource(R.string.plugin_dream_desc),
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (blocked != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            text = blocked!!,
                            modifier = Modifier.padding(12.dp),
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp,
                                color = Color(0xFFB26A00)
                            )
                        )
                    }
                } else if (note != null) {
                    Text(
                        text = note!!,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    )
                }

                TextField(
                    value = requirement,
                    onValueChange = { requirement = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .heightIn(min = 90.dp),
                    label = stringResource(R.string.plugin_dream_input_hint),
                    useLabelAsPlaceholder = true,
                    maxLines = 6,
                    minLines = 4,
                    enabled = !generating
                )

                if (generating) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.plugin_dream_generating),
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                    }
                }

                if (errorText != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.plugin_dream_failed, errorText!!),
                        style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color(0xFFF44336))
                    )
                    if (rawText.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.plugin_dream_parse_failed),
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                        Text(
                            text = rawText.take(600),
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                    }
                }

                if (draft != null) {
                    val current = draft!!
                    Spacer(Modifier.height(12.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = current.manifest.name,
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "v${current.manifest.version} · ${stringResource(R.string.plugin_dream_files, current.fileCount)}",
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                            if (current.manifest.description.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = current.manifest.description,
                                    style = androidx.compose.ui.text.TextStyle(
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            current.files.forEach { file ->
                                Text(
                                    text = "• ${file.path}",
                                    style = androidx.compose.ui.text.TextStyle(
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp)),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                if (draft != null) {
                    Button(
                        onClick = { installDraft() },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                    ) {
                        Text(
                            text = stringResource(R.string.plugin_dream_install),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else if (generating) {
                    Button(
                        onClick = { cancelled = true; generating = false },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(color = Color(0xFFF44336))
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else {
                    Button(
                        onClick = { startGenerate() },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                    ) {
                        Text(
                            text = if (draft == null && errorText != null) {
                                stringResource(R.string.plugin_dream_retry)
                            } else {
                                stringResource(R.string.plugin_dream_generate)
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    )
}
