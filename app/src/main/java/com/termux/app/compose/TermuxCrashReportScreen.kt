package com.termux.app.compose

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import com.termux.R
import com.termux.shared.termux.TermuxUtils
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun TermuxCrashReportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val density = LocalDensity.current
    val systemNavBarsHeight = with(density) {
        WindowInsets.navigationBars.getBottom(density).toDp()
    }
    val reportText = remember { generateCrashReportText(context) }
    var showShareDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = stringResource(R.string.title_crash_report),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = context.getString(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    TextButton(
                        text = stringResource(R.string.share),
                        onClick = { showShareDialog = true }
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = systemNavBarsHeight + 26.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.crash_report_warning),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            lineHeight = 20.sp
                        )
                    }
                }

                item {
                    SettingCard {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = reportText,
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MiuixTheme.colorScheme.onSurface,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                item {
                    SettingCard {
                        ArrowPreference(
                            title = stringResource(R.string.copy_report),
                            summary = stringResource(R.string.copy_report_summary),
                            onClick = {
                                copyToClipboard(context, reportText)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = context.getString(R.string.report_copied),
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            },
                            startAction = {
                                SettingIcon(R.drawable.ic_error)
                            }
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            OverlayDialog(
                show = showShareDialog,
                onDismissRequest = { showShareDialog = false },
                content = {
                    ShareReportContent(
                        reportText = reportText,
                        onDismiss = { showShareDialog = false }
                    )
                }
            )
        }
    }
}

private fun generateCrashReportText(context: Context): String {
    return buildString {
        appendLine("=== Crash Report ===")
        appendLine()
        appendLine(TermuxUtils.getAppInfoMarkdownString(context, false))
        appendLine()
        appendLine("--- Device Info ---")
        val androidVersion = android.os.Build.VERSION.RELEASE
        val sdkVersion = android.os.Build.VERSION.SDK_INT
        val device = android.os.Build.DEVICE
        val model = android.os.Build.MODEL
        val manufacturer = android.os.Build.MANUFACTURER
        appendLine("Device: $manufacturer $model")
        appendLine("Model: $model")
        appendLine("Android: $androidVersion (SDK $sdkVersion)")
        appendLine("Product: ${android.os.Build.PRODUCT}")
        appendLine("Brand: ${android.os.Build.BRAND}")
        appendLine()
        appendLine("--- Thread Info ---")
        val thread = Thread.currentThread()
        appendLine("Thread: ${thread.name}")
        appendLine("Priority: ${thread.priority}")
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("Crash Report", text)
    clipboard.setPrimaryClip(clip)
}

@Composable
private fun ShareReportContent(
    reportText: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.share_report),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.share_report_summary),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = stringResource(R.string.share),
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.type = "text/plain"
                    intent.putExtra(Intent.EXTRA_TEXT, reportText)
                    intent.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.title_crash_report))
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_report)))
                    onDismiss()
                }
            )
        }
    }
}
