package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 脚本检测对话框。
 * 当检测到脚本文件中包含危险命令时显示，
 * 允许用户查看、编辑或继续执行脚本。
 *
 * @param results 检测到的危险命令列表
 * @param scriptFilePath 脚本文件路径（用于查看/编辑操作）
 * @param onViewScript 点击"查看脚本"回调
 * @param onEditScript 点击"编辑脚本"回调
 * @param onContinue 点击"继续执行"回调
 * @param onCancel 点击"取消"回调
 */
@Composable
fun ScriptDetectionDialog(
    results: List<RiskCommandDetector.ScriptDetectionResult>,
    scriptFilePath: String,
    onViewScript: () -> Unit = {},
    onEditScript: () -> Unit = {},
    onContinue: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    var showDialog by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    OverlayDialog(
        show = showDialog,
        onDismissRequest = {
            showDialog = false
            onCancel()
        },
        title = "脚本安全检测",
        summary = buildString {
            append("检测到 ${results.size} 条危险命令")
            if (results.isNotEmpty()) {
                append("，建议检查后再执行。")
            }
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 危险命令列表
                results.forEach { result ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(
                                if (isDark) Color(0xFF3D1414) else Color(0xFFFFEBEE)
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (isDark) Color(0xFFB88600).copy(alpha = 0.2f)
                                        else Color(0xFFFFA000).copy(alpha = 0.2f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isDark) Color(0xFFFFB300) else Color(0xFFFF8F00)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "第 ${result.lineNumber} 行",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = result.detection.riskType?.displayName ?: "未知风险",
                                        fontSize = 11.sp,
                                        color = Color(0xFFD32F2F),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = result.lineContent,
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 18.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = result.detection.description,
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 操作按钮区
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        text = "查看脚本",
                        onClick = {
                            showDialog = false
                            onViewScript()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "编辑脚本",
                        onClick = {
                            showDialog = false
                            onEditScript()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        text = "取消",
                        onClick = {
                            showDialog = false
                            onCancel()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            showDialog = false
                            onContinue()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = Color(0xFFD32F2F)
                        )
                    ) {
                        Text(
                            text = "继续执行",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    )
}