package com.termux.app.compose

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.termux.R
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「配置集成工具」页（独立 Activity）。
 *
 * 承接设置页「集成工具」组里原先平铺的工具配置入口：每一项对应一个内置工具，
 * 可见性与设置页的开关状态同源——由 [IntegratedTools.isEnabled] 决定，全关则整页只剩空状态。
 *
 * 开关状态不在本页改，所以每次 ON_RESUME 都重读一次：用户可能先进本页、再回设置页关掉某个
 * 工具，回来时该项必须立刻消失，而不是留着失效入口。
 */
@Composable
fun IntegratedToolsConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 本页在 MainScreen 取景层之外，自建一层供玻璃顶栏折射页面内容
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    var apiEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_API)) }
    var bootEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_BOOT)) }
    var stylingEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_STYLING)) }
    var taskerEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_TASKER)) }
    var widgetEnabled by remember { mutableStateOf(IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_WIDGET)) }

    fun refresh() {
        apiEnabled = IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_API)
        bootEnabled = IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_BOOT)
        stylingEnabled = IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_STYLING)
        taskerEnabled = IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_TASKER)
        widgetEnabled = IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_WIDGET)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Termux:API / Termux:Boot 没有各自的配置 Activity，只提供使用说明弹窗
    var showApiHelpDialog by remember { mutableStateOf(false) }
    var showBootHelpDialog by remember { mutableStateOf(false) }

    val hasAnyEnabled = apiEnabled || bootEnabled || stylingEnabled || taskerEnabled || widgetEnabled

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                SnackbarHost(state = snackbarHostState)
            }
        },
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.integrated_tools_config_entry),
                backdrop = glassPage.backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = { onBack() }) {
                        Icon(
                            imageVector = MiuixGlassIcons.ChevronBackward,
                            contentDescription = stringResource(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(glassPage.contentModifier)
                .padding(pagePaddingWithoutTop(padding))
        ) {
            if (!hasAnyEnabled) {
                // 兜底：设置页的入口已随最后一个开关关闭而隐藏，本页只可能由 Activity 直接拉起
                IntegratedToolsEmptyState()
                return@Box
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = standaloneContentPadding(padding, bottom = 16.dp)
            ) {
                item(key = "card_config") {
                    SettingCard {
                        Column {
                            if (apiEnabled) {
                                ToolConfigArrow(
                                    title = stringResource(R.string.termux_api_help),
                                    summary = stringResource(R.string.termux_api_help_summary),
                                    icon = Icons.Rounded.Terminal,
                                    onClick = { showApiHelpDialog = true }
                                )
                            }
                            if (bootEnabled) {
                                ToolConfigArrow(
                                    title = stringResource(R.string.termux_boot_help),
                                    summary = stringResource(R.string.termux_boot_help_summary),
                                    icon = Icons.AutoMirrored.Rounded.Launch,
                                    onClick = { showBootHelpDialog = true }
                                )
                            }
                            if (stylingEnabled) {
                                ToolConfigArrow(
                                    title = stringResource(R.string.termux_styling_config),
                                    summary = stringResource(R.string.termux_styling_config_summary),
                                    icon = Icons.Rounded.Palette,
                                    onClick = { openToolConfigActivity(context, "com.termux.app.activities.TermuxStylingActivity") }
                                )
                            }
                            if (taskerEnabled) {
                                ToolConfigArrow(
                                    title = stringResource(R.string.termux_tasker_config),
                                    summary = stringResource(R.string.termux_tasker_config_summary),
                                    icon = Icons.Rounded.Tune,
                                    onClick = { openToolConfigActivity(context, "com.termux.app.activities.TermuxTaskerActivity") }
                                )
                            }
                            if (widgetEnabled) {
                                ToolConfigArrow(
                                    title = stringResource(R.string.termux_widget_config),
                                    summary = stringResource(R.string.termux_widget_config_summary),
                                    icon = Icons.Rounded.Star,
                                    onClick = { openToolConfigActivity(context, "com.termux.app.activities.TermuxWidgetActivity") }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------- Termux:API usage guide ----------
        OverlayDialog(
            title = stringResource(R.string.termux_api_help),
            show = showApiHelpDialog,
            onDismissRequest = { showApiHelpDialog = false },
            content = {
                Box(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    HelpContentWithCopyableCommands(
                        content = stringResource(R.string.termux_api_help_content),
                        context = context,
                        snackbarHostState = snackbarHostState
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    text = stringResource(R.string.ok),
                    onClick = { showApiHelpDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )

        // ---------- Termux:Boot startup guide ----------
        OverlayDialog(
            title = stringResource(R.string.termux_boot_help),
            show = showBootHelpDialog,
            onDismissRequest = { showBootHelpDialog = false },
            content = {
                Box(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    HelpContentWithCopyableCommands(
                        content = stringResource(R.string.termux_boot_help_content),
                        context = context,
                        snackbarHostState = snackbarHostState
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    text = stringResource(R.string.ok),
                    onClick = { showBootHelpDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }
}

/**
 * 打开「配置集成工具」页。返回键由 Activity 栈自然回到设置页，无需手动回传结果。
 */
fun openIntegratedToolsConfig(context: Context) {
    context.startActivity(Intent(context, com.termux.app.activities.IntegratedToolsConfigActivity::class.java))
}

/** 工具自带配置页的入口。这些 Activity 随工具开关被整体禁用，缺失时静默失败而非崩溃。 */
private fun openToolConfigActivity(context: Context, className: String) {
    val intent = Intent().apply {
        component = ComponentName(context.packageName, className)
    }
    runCatching { context.startActivity(intent) }
}

@Composable
private fun ToolConfigArrow(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    ArrowPreference(
        title = title,
        summary = summary,
        onClick = onClick,
        startAction = { SettingIcon(icon, contentDescription = title) }
    )
}

/** 五个开关全关时的占位：设置页入口此时已隐藏，这里只防 Activity 被直接拉起。 */
@Composable
private fun IntegratedToolsEmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 60.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_extension),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.integrated_tools_config_empty),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun SettingIcon(icon: ImageVector, contentDescription: String? = null) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface
        )
    }
}

/**
 * 解析帮助文本，将命令行渲染为可一键复制的行，其余渲染为普通文本。
 *
 * 判定规则（行首去空格后）：
 *  - 以 `•` 开头 → 命令描述行，其中 ` — ` 后为说明，前面是命令 → 提取命令部分可复制
 *  - 以 `pkg ` / `mkdir ` / `termux-` / `#!/` / `#` / `sshd` / `termux-wake-lock` 开头 → 整行可复制
 *  - 以数字+`.` 开头（如 `1. `）→ 步骤说明行，不可复制
 *  - 其余 → 普通文本
 */
@Composable
private fun HelpContentWithCopyableCommands(
    content: String,
    context: Context,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    fun showSnackbar(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }
    val lines = content.split("\n")
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        lines.forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                return@forEach
            }

            val commandText: String? = when {
                trimmed.startsWith("• ") -> {
                    val afterBullet = trimmed.substring(2).trim()
                    val dashIdx = afterBullet.indexOf(" — ")
                    if (dashIdx > 0) afterBullet.substring(0, dashIdx).trim()
                    else if (afterBullet.startsWith("termux-") || afterBullet.startsWith("pkg ")) afterBullet
                    else null
                }
                trimmed.startsWith("pkg ") ||
                trimmed.startsWith("mkdir ") ||
                trimmed.startsWith("termux-") ||
                trimmed.startsWith("#!/") ||
                trimmed.startsWith("sshd") ||
                trimmed.startsWith("termux-wake-lock") -> trimmed
                rawLine.trimStart().startsWith("#!/") -> rawLine.trimStart()
                rawLine.trimStart().startsWith("termux-wake-lock") -> rawLine.trimStart()
                rawLine.trimStart().startsWith("sshd") -> rawLine.trimStart()
                else -> null
            }

            if (commandText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = rawLine,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        lineHeight = 20.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable {
                                val clip = android.content.ClipData.newPlainText(context.getString(R.string.command), commandText)
                                clipboard.setPrimaryClip(clip)
                                showSnackbar(context.getString(R.string.copied_command, commandText))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = context.getString(R.string.copy),
                            modifier = Modifier.size(16.dp),
                            tint = MiuixTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                Text(
                    text = rawLine,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    lineHeight = 22.sp
                )
            }
        }
    }
}
