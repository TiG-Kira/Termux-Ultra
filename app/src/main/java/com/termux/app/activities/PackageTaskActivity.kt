package com.termux.app.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.R
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.LiveUpdateState
import com.termux.app.compose.NavigationHelper
import com.termux.app.compose.PkgRepo
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 软件包管理任务执行页：安装、卸载、升级、刷新四类操作共用，视觉对齐 KernelSU 安装过程页（miuix 风格）。
 *
 * 交互规则：
 * - 任务跑在 LiveUpdateState.pkgScope（独立于页面生命周期），返回键随时退出，任务在后台继续。
 * - 从软件包管理页顶栏任务按钮可重新进入本页，页面从 LiveUpdateState 恢复当前任务状态。
 * - 完成后标题切换为「安装成功 / 安装失败」（按操作类型映射文案），右下角按钮变对勾，点击退出。
 */
class PackageTaskActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // 新任务模式：Intent 携带操作类型与包名，进页面即启动；否则为恢复模式，只观察现有任务
        val opName = intent.getStringExtra(EXTRA_OPERATION)
        if (savedInstanceState == null && opName != null && LiveUpdateState.getPkgStateSnapshot() == null) {
            PkgTaskRunner.run(
                applicationContext,
                LiveUpdateState.PkgOperation.valueOf(opName),
                intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
            )
        }

        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                KiTerminalTheme {
                    PackageTaskScreen(onClose = { finish() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPERATION = "extra_pkg_operation"
        const val EXTRA_PACKAGE_NAME = "extra_pkg_name"
        const val EXTRA_SUCCESS = "extra_pkg_success"

        /** 启动新任务的 Intent：操作类型 + 包信息（升级/刷新时包名为空） */
        fun newTaskIntent(context: Context, operation: LiveUpdateState.PkgOperation, packageName: String): Intent =
            Intent(context, PackageTaskActivity::class.java)
                .putExtra(EXTRA_OPERATION, operation.name)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)

        /** 恢复现有任务的 Intent：不带参数，页面直接观察 LiveUpdateState */
        fun restoreIntent(context: Context): Intent =
            Intent(context, PackageTaskActivity::class.java)
    }
}

/**
 * 包任务执行器：四类操作统一入口，跑在 LiveUpdateState.pkgScope，日志流式写入
 * LiveUpdateState.pkgLog 供任务页实时展示。
 */
object PkgTaskRunner {
    fun run(context: Context, operation: LiveUpdateState.PkgOperation, packageName: String) {
        LiveUpdateState.startPkg(operation, packageName)
        LiveUpdateState.pkgScope.launch {
            val result = when (operation) {
                LiveUpdateState.PkgOperation.INSTALL ->
                    PkgRepo.install(context, packageName, onOutput = { LiveUpdateState.appendPkgLog(it) }, stdin = LiveUpdateState.pkgStdin)
                LiveUpdateState.PkgOperation.UNINSTALL ->
                    PkgRepo.uninstall(context, packageName, onOutput = { LiveUpdateState.appendPkgLog(it) }, stdin = LiveUpdateState.pkgStdin)
                LiveUpdateState.PkgOperation.UPDATE ->
                    PkgRepo.update(context, onOutput = { LiveUpdateState.appendPkgLog(it) }, stdin = LiveUpdateState.pkgStdin)
                LiveUpdateState.PkgOperation.UPGRADE ->
                    PkgRepo.upgradeAll(context, onOutput = { LiveUpdateState.appendPkgLog(it) }, stdin = LiveUpdateState.pkgStdin)
            }
            LiveUpdateState.finishPkg(result.first)
        }
    }
}

@Composable
private fun PackageTaskScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val state by LiveUpdateState.pkgState.collectAsState()
    val log by LiveUpdateState.pkgLog.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val snap = state

    // 没有可恢复的任务（状态已被消费或误启动）→ 直接退出
    LaunchedEffect(snap) {
        if (snap == null) onClose()
    }

    // 完成即写入 result：即使用户用返回键离开，调用方也能拿到结果刷新列表
    LaunchedEffect(snap?.finished) {
        val activity = context as? Activity
        if (activity != null && snap != null && snap.finished) {
            activity.setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(PackageTaskActivity.EXTRA_OPERATION, snap.operation.name)
                    .putExtra(PackageTaskActivity.EXTRA_PACKAGE_NAME, snap.packageName)
                    .putExtra(PackageTaskActivity.EXTRA_SUCCESS, snap.success)
            )
        }
    }

    var lockDialogDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(snap?.finished == false) {
        if (snap != null && !snap.finished) lockDialogDismissed = false
    }

    // ===== 交互式输入（apt/dpkg 提示需要用户输入时）=====
    var inputPrompt by remember { mutableStateOf<String?>(null) }
    var userInput by remember { mutableStateOf("") }
    var inputOpen by remember { mutableStateOf(false) }
    var lastConsumedLogLen by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 监听流式日志：检测到需要用户输入的提示（keep/replace、conffile、[Y/n]、*** 等）才弹出输入卡
    LaunchedEffect(log) {
        val newLen = log.length
        if (newLen > lastConsumedLogLen) {
            val chunk = log.substring(lastConsumedLogLen)
            val prompt = detectPkgPrompt(chunk)
            if (prompt != null) {
                inputPrompt = prompt
                userInput = ""
                inputOpen = true
            }
        }
        lastConsumedLogLen = newLen
    }

    // 任务结束后强制收起输入卡
    LaunchedEffect(snap?.finished) {
        if (snap != null && snap.finished) inputOpen = false
    }

    // 输入卡出现时自动聚焦输入框，弹出软键盘
    LaunchedEffect(inputOpen) {
        if (inputOpen) focusRequester.requestFocus()
    }

    fun submitPkgInput() {
        LiveUpdateState.pkgStdin.trySend(userInput)
        userInput = ""
        inputOpen = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            SmallTopAppBar(
                title = taskTitle(snap),
                subtitle = taskSubtitle(snap),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = MiuixGlassIcons.ChevronBackward,
                            contentDescription = stringResource(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(scrollState)
            ) {
                LaunchedEffect(log) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
                Text(
                    text = log.ifBlank { "正在准备任务 ..." },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Spacer(
                    Modifier.height(
                        96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    )
                )
            }
            // 右下角任务按钮：运行中转圈（点击无效），完成变对勾，点击退出回到软件包管理
            val finished = snap?.finished == true
            GlassIconButton(
                onClick = {
                    if (finished) {
                        LiveUpdateState.clearPkg()
                        onClose()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 20.dp,
                        bottom = 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    )
            ) {
                if (finished) {
                    Icon(
                        imageVector = MiuixGlassIcons.Ok,
                        contentDescription = "完成",
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        size = 22.dp,
                        strokeWidth = 2.5.dp
                    )
                }
            }
            // 交互式输入卡：仅当检测到 apt/dpkg 需要用户输入（保留/替换、conffile、[Y/n] 等）时弹出；
            // 不需要时（无提示或任务结束）保持隐藏，显示/隐藏均使用优雅的非线性弹簧+淡入淡出动画
            AnimatedVisibility(
                visible = inputOpen,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 92.dp + navBarPadding),
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(animationSpec = tween(200)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = tween(160))
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "需要你的输入才能继续：",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = inputPrompt ?: "",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onSurface,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        TextField(
                            value = userInput,
                            onValueChange = { userInput = it },
                            label = "输入应答后回车发送（如 Y / N / 回车）",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submitPkgInput() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                text = "发送",
                                onClick = { submitPkgInput() }
                            )
                        }
                    }
                }
            }
        }
    }

    // apt 锁冲突（自 PackageDetailScreen 迁移）：任务失败且日志命中锁错误时给出解除入口
    if (snap != null && snap.finished && !snap.success && !lockDialogDismissed && isLockError(log)) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.pkg_manager_busy),
            summary = "检测到 Termux 的 apt/dpkg 正在被其他进程占用。\n\n强行解除可能导致正在进行的任务中断、数据库状态不一致。\n\n建议先关闭其他正在运行的 Termux 会话，然后再试。",
            onDismissRequest = { lockDialogDismissed = true },
            content = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { lockDialogDismissed = true },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "强行解除",
                        onClick = {
                            lockDialogDismissed = true
                            val op = snap.operation
                            val name = snap.packageName
                            scope.launch {
                                PkgRepo.forceRemoveLocks(appContext)
                                Toast.makeText(appContext, "锁已解除，正在继续...", Toast.LENGTH_SHORT).show()
                                PkgTaskRunner.run(appContext, op, name)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        )
    }
}

private fun taskTitle(snap: LiveUpdateState.PkgState?): String {
    if (snap == null) return "软件包任务"
    return when {
        !snap.finished -> when (snap.operation) {
            LiveUpdateState.PkgOperation.INSTALL -> "正在安装 ${snap.packageName}"
            LiveUpdateState.PkgOperation.UNINSTALL -> "正在卸载 ${snap.packageName}"
            LiveUpdateState.PkgOperation.UPDATE -> "正在刷新软件源"
            LiveUpdateState.PkgOperation.UPGRADE -> "正在升级所有包"
        }
        else -> when (snap.operation) {
            LiveUpdateState.PkgOperation.INSTALL -> if (snap.success) "安装成功" else "安装失败"
            LiveUpdateState.PkgOperation.UNINSTALL -> if (snap.success) "卸载成功" else "卸载失败"
            LiveUpdateState.PkgOperation.UPDATE -> if (snap.success) "刷新成功" else "刷新失败"
            LiveUpdateState.PkgOperation.UPGRADE -> if (snap.success) "升级成功" else "升级失败"
        }
    }
}

private fun taskSubtitle(snap: LiveUpdateState.PkgState?): String {
    if (snap == null) return ""
    return when {
        !snap.finished -> "任务可在后台继续执行，返回不会中断"
        snap.success -> "任务已完成，点击右下角按钮返回"
        else -> "任务失败，请查看下方日志"
    }
}

private fun isLockError(log: String): Boolean {
    val lower = log.lowercase()
    return lower.contains("could not get lock") ||
        lower.contains("dpkg is locked") ||
        lower.contains("wait for it to finish") ||
        lower.contains("/var/lib/dpkg/lock") ||
        lower.contains("/var/cache/apt/archives/lock") ||
        lower.contains("cache/apt/archives/lock")
}

/**
 * apt/dpkg 交互式提示识别：在新增日志片段里找「阻塞式等待用户输入」的提示行。
 * 这类提示总是出现在流式输出的末尾，因此只检查片段末尾若干行即可，避免历史日志误命中。
 * 覆盖：apt 的 [Y/n] 确认、dpkg conffile 的 保留/替换 提示、apt-listbugs/listchanges 的 `*** ` 提示等。
 */
private val PKG_PROMPT_RE = Regex(
    """(\[[Yy]/[Nn]\]|\[[Yy]/[nN]\]|\[y/N\]|\[YES/no\]|\[yes/no\]|do you want to continue\?|would you like to do about it|install the package maintainer|keep the current version|keep your currently|modified.*conffile|conffile .* modified|configuration file .* has been|press \[enter\]|^\*\*\* )""",
    RegexOption.IGNORE_CASE
)

private fun detectPkgPrompt(chunk: String): String? {
    val lines = chunk.lines()
    val start = (lines.size - 8).coerceAtLeast(0)
    for (i in (lines.size - 1) downTo start) {
        val line = lines[i].trim()
        if (line.isEmpty()) continue
        if (PKG_PROMPT_RE.containsMatchIn(line)) return line
    }
    return null
}
