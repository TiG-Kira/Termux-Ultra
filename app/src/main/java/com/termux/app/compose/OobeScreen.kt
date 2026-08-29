package com.termux.app.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.termux.BuildConfig
import com.termux.R
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun OobeScreen(
    isUpgrade: Boolean,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
    eulaAgreed: Boolean,
    onEulaAgreeChange: (Boolean) -> Unit,
    eulaLastModified: String,
    eulaLastStored: String,
    permissionStatus: String,
    isPermissionGranted: Boolean,
    isBootstrapping: Boolean,
    bootstrapComplete: Boolean,
    bootstrapError: String?,
    releaseNotes: String?,
    currentVersionName: String,
    onGrantAllPermissions: () -> Unit,
    onStartBootstrap: () -> Unit,
    onRetryBootstrap: () -> Unit,
    onExitApp: () -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val density = LocalDensity.current
    val darkTheme = isSystemInDarkTheme()
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // 渐变背景 (和关于页面一致)
    val infiniteTransition = rememberInfiniteTransition(label = "oobeBreathing")
    val gradientFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "oobeGradient"
    )

    val lightGradient = Brush.verticalGradient(
        colors = listOf(
            androidx.compose.ui.graphics.lerp(Color(0xFF60A5FA), Color(0xFF93C5FD), gradientFraction),
            androidx.compose.ui.graphics.lerp(Color(0xFF818CF8), Color(0xFFC4B5FD), gradientFraction),
            androidx.compose.ui.graphics.lerp(Color(0xFFA78BFA), Color(0xFFF0ABFC), gradientFraction)
        )
    )
    val darkGradient = Brush.verticalGradient(
        colors = listOf(
            androidx.compose.ui.graphics.lerp(Color(0xFF1E3A5F), Color(0xFF0F172A), gradientFraction),
            androidx.compose.ui.graphics.lerp(Color(0xFF312E81), Color(0xFF1E1B4B), gradientFraction),
            androidx.compose.ui.graphics.lerp(Color(0xFF4C1D95), Color(0xFF1A1A2E), gradientFraction)
        )
    )

    // 跳过逻辑
    val shouldSkipEula = isUpgrade && eulaLastModified == eulaLastStored && eulaLastStored.isNotEmpty()
    val shouldSkipPermissionsAndInstall = isUpgrade

    // 计算实际页面索引（考虑跳过）
    val actualPage = remember(currentPage, isUpgrade, shouldSkipEula, shouldSkipPermissionsAndInstall) {
        currentPage
    }

    // 导航到下一页
    fun goNext() {
        val next: Int = if (!isUpgrade) {
            // 全新安装: 0→1→2→3→4→5
            (currentPage + 1).coerceAtMost(5)
        } else {
            // 升级用户:
            //   欢迎 → EULA(如需要) 或 版本日志
            //   EULA → 版本日志
            //   权限(跳过) / 安装(跳过) → 版本日志
            //   版本日志 → 完成
            when (currentPage) {
                0 -> if (shouldSkipEula) 4 else 1
                1 -> 4  // EULA 之后直接到版本日志
                2 -> 4  // 权限页(跳过) → 版本日志
                3 -> 4  // 安装页(跳过) → 版本日志
                4 -> 5  // 版本日志 → 完成
                else -> 5
            }
        }
        onPageChange(next)
    }

    fun goBack() {
        val prev: Int = if (!isUpgrade) {
            (currentPage - 1).coerceAtLeast(0)
        } else {
            when (currentPage) {
                1 -> 0            // EULA → 欢迎
                2 -> if (shouldSkipEula) 0 else 1
                3 -> if (shouldSkipEula) 0 else 1
                4 -> if (shouldSkipEula) 0 else 1  // 版本日志 → EULA 或欢迎
                5 -> 4            // 完成 → 版本日志
                else -> 0
            }
        }
        onPageChange(prev)
    }

    BackHandler {
        if (currentPage > 0) {
            goBack()
        }
    }

    // 第一页和最后一页使用渐变背景
    val useGradient = currentPage == 0 || currentPage == 5

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (useGradient) {
                    Modifier.background(if (darkTheme) darkGradient else lightGradient)
                } else {
                    Modifier.background(MiuixTheme.colorScheme.surface)
                }
            )
    ) {
        when (currentPage) {
            0 -> OobeWelcomePage(
                isUpgrade = isUpgrade,
                onNext = { goNext() },
                useGradient = useGradient,
                )
            1 -> OobeEulaPage(
                eulaAgreed = eulaAgreed,
                onEulaAgreeChange = onEulaAgreeChange,
                onBack = { goBack() },
                onNext = { goNext() },
                eulaLastModified = eulaLastModified,
                darkTheme = darkTheme
            )
            2 -> OobePermissionPage(
                permissionStatus = permissionStatus,
                isPermissionGranted = isPermissionGranted,
                onGrantAllPermissions = onGrantAllPermissions,
                onBack = { goBack() },
                onNext = { goNext() }
            )
            3 -> OobeInstallPage(
                isBootstrapping = isBootstrapping,
                bootstrapComplete = bootstrapComplete,
                bootstrapError = bootstrapError,
                onStartBootstrap = onStartBootstrap,
                onRetryBootstrap = onRetryBootstrap,
                onExitApp = onExitApp,
                onNext = { goNext() },
                onBack = { goBack() }
            )
            4 -> OobeReleaseNotesPage(
                releaseNotes = releaseNotes,
                currentVersionName = currentVersionName,
                onNext = { goNext() },
                onBack = { goBack() }
            )
            5 -> OobeCompletePage(
                onComplete = onComplete,
                useGradient = useGradient,
                )
        }
    }
}

// ==================== 第一页: 欢迎 ====================

@Composable
private fun OobeWelcomePage(
    isUpgrade: Boolean,
    onNext: () -> Unit,
    useGradient: Boolean
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 120.dp, bottom = 80.dp + 32.dp)
        ) {
            // Logo
            val appIcon = remember {
                ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                    ?.toBitmap()
                    ?.asImageBitmap()
            }
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = "Logo",
                        modifier = Modifier.size(96.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_terminal),
                        contentDescription = "Logo",
                        modifier = Modifier.size(48.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Termux Ultra",
                style = TextStyle(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
            )

            if (isUpgrade) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "升级完成",
                    style = TextStyle(
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 右箭头圆形按钮
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f))
                    .clickable { onNext() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = "Next",
                    tint = Color.Black,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

// ==================== 第二页: 许可条款 ====================

@Composable
private fun OobeEulaPage(
    eulaAgreed: Boolean,
    onEulaAgreeChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    eulaLastModified: String,
    darkTheme: Boolean
) {
    val scrollState = rememberScrollState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = "许可条款",
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
                            contentDescription = "Back",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 内容区域
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // EULA 部分
                Text(
                    text = "Termux Ultra 最终用户许可协议",
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "最后修改: $eulaLastModified",
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))

                EulaContent()

                Spacer(modifier = Modifier.height(24.dp))

                // GPL 3.0 部分
                Text(
                    text = "GNU General Public License v3.0",
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                Gpl3Summary()
            }

            // 同意复选框 + 继续按钮
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .padding(bottom = 32.dp)
            ) {
                CheckboxPreference(
                    title = "我已阅读并同意上述许可条款",
                    checked = eulaAgreed,
                    onCheckedChange = { onEulaAgreeChange(it) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { onNext() },
                    enabled = eulaAgreed,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        color = if (eulaAgreed) MiuixTheme.colorScheme.primary 
                                else MiuixTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "继续",
                        fontWeight = FontWeight.Bold,
                        color = if (eulaAgreed) Color.White 
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

@Composable
private fun EulaContent() {
    val lines = listOf(
        "欢迎使用 Termux Ultra（以下简称“本软件”）。在使用本软件前，请仔细阅读以下许可条款。通过安装、复制或使用本软件，即表示您已同意受本协议各项条款约束。",
        "",
        "一、服务内容",
        "Termux Ultra 是一款基于 Android 平台的终端模拟器及 Linux 环境管理工具，提供命令行终端、文件管理、远程连接（SSH/VNC）、包管理等功能。本软件集成了 Termux 上游项目及多个第三方开源组件。",
        "",
        "二、风险提示与免责声明",
        "",
        "1. 命令执行风险：本软件允许用户执行任意命令行指令，包括但不限于文件操作、网络请求、进程管理等。用户需自行评估所执行命令的安全性和合法性。因执行恶意命令、误操作或不了解命令含义而导致的数据丢失、系统损坏或其他后果，开发者不承担任何责任。",
        "",
        "2. 文件系统访问：本软件可访问设备上的文件系统。用户在进行文件读写、删除、移动、重命名等操作时应格外谨慎。误删重要文件、覆盖系统文件导致设备异常的，责任由用户自行承担。",
        "",
        "3. 网络安全：本软件的网络请求、SSH/VNC 远程连接、HTTPS/HTTP 通信由用户自行配置和发起。开发者不对网络中间人攻击、密码泄露、数据传输安全问题承担责任。",
        "",
        "4. Root/SU 权限：若用户通过本软件执行需要 Root 权限的操作，应了解相关操作的固有风险（包括但不限于设备变砖、保修失效、安全漏洞）。开发者不对 Root 操作的任何后果负责。",
        "",
        "5. 第三方脚本与包：通过 pkg/apt 安装的第三方软件包或从外部获取的 Shell/Python/其他语言脚本，其内容、安全性和合法性由提供者和使用者自行负责。用户在执行任何来源不明的脚本前应进行充分审查。",
        "",
        "6. 系统修改：通过本软件执行的任何修改设备系统、引导程序、分区表、内核等操作均属于高风险行为，可能导致设备无法正常启动。这些操作完全由用户主动发起，开发者对此不承担任何责任。",
        "",
        "7. 数据与隐私：本软件本身不收集用户隐私数据。但用户通过本软件执行的命令、安装的工具、配置的网络连接可能涉及隐私信息。用户需自行负责管理和保护自己的数据与隐私。",
        "",
        "8. 安全漏洞：若您发现本软件存在安全漏洞，请通过合法渠道向开发者报告。开发者不对因漏洞被恶意利用而造成的损失承担责任，但会尽力修复合理报告的问题。",
        "",
        "9. AI 功能：本软件集成的 AI 助手功能依赖用户自行配置的大语言模型服务（本地或云端）。AI 输出内容可能不准确、过时或存在偏见，用户需自行判断其正确性。因 AI 建议导致的任何操作后果由用户自行承担。",
        "",
        "10. 不保证特定功能：由于 Android 系统版本差异、厂商定制、设备硬件差异、网络环境等因素，本软件的部分功能可能无法在所有设备上正常工作。开发者不保证所有功能在所有设备上的可用性。",
        "",
        "三、知识产权",
        "本软件遵循 GNU General Public License v3.0（GPL-3.0）发布。本软件中集成的各组件分别受其各自开源许可协议约束。",
        "",
        "四、协议修改",
        "开发者保留随时修改本协议的权利。修改后的协议将在新版本中生效。继续使用本软件即视为同意修改后的条款。",
        "",
        "五、适用法律",
        "本协议的订立、执行和解释及争议的解决均应适用中华人民共和国法律。"
    )
    
    Column {
        for (line in lines) {
            if (line.isEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
            } else if (line.startsWith("一、") || line.startsWith("二、") || line.startsWith("三、") || 
                       line.startsWith("四、") || line.startsWith("五、")) {
                Text(
                    text = line,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            } else {
                Text(
                    text = line,
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun Gpl3Summary() {
    val lines = listOf(
        "本软件包含的部分组件采用 GNU General Public License v3.0 许可发布。以下是 GPL-3.0 的核心要点摘要（非法律条文）：",
        "",
        "• 您可以以任何目的运行该程序。",
        "• 您可以复制并分发明程序的原始代码或修改后的代码。",
        "• 分发时，您必须向接收者提供源代码，或提供书面要约以提供源代码。",
        "• 分发的程序必须同样采用 GPL-3.0 许可。",
        "• 分发时不得附加额外的限制或技术手段阻止他人行使上述权利。",
        "• 本软件按“现状”分发，不附带任何明示或暗示的担保。",
        "",
        "完整的 GPL-3.0 条款文本可在 https://www.gnu.org/licenses/gpl-3.0.txt 获取。"
    )
    
    Column {
        for (line in lines) {
            if (line.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
            } else if (line.startsWith("•")) {
                Row(
                    modifier = Modifier.padding(bottom = 2.dp)
                ) {
                    Text(
                        text = line.take(2),
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    )
                    Text(
                        text = line.drop(2),
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    )
                }
            } else {
                Text(
                    text = line,
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

// ==================== 第三页: 权限 ====================

@Composable
private fun OobePermissionPage(
    permissionStatus: String,
    isPermissionGranted: Boolean,
    onGrantAllPermissions: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = "权限",
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
                            contentDescription = "Back",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            
            Text(
                text = "授权所需权限",
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Termux Ultra 需要以下权限以确保正常运行",
                style = TextStyle(
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                ),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    PermissionItem("网络访问", "运行命令、下载包、远程连接")
                    PermissionItem("文件存储", "访问设备存储空间")
                    PermissionItem("唤醒锁定", "后台运行时保持活跃")
                    PermissionItem("震动反馈", "触觉反馈")
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = permissionStatus,
                style = TextStyle(
                    fontSize = 14.sp,
                    color = if (isPermissionGranted) MiuixTheme.colorScheme.primary 
                           else MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            Button(
                onClick = { onGrantAllPermissions() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "授权所有权限")
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = { onNext() },
                enabled = isPermissionGranted,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    color = if (isPermissionGranted) MiuixTheme.colorScheme.primary 
                            else MiuixTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "继续",
                    fontWeight = FontWeight.Bold,
                    color = if (isPermissionGranted) Color.White 
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionItem(name: String, desc: String) {
    Column(
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Text(
            text = name,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
        )
        Text(
            text = desc,
            style = TextStyle(
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        )
    }
}

// ==================== 第四页: 安装 ====================

@Composable
private fun OobeInstallPage(
    isBootstrapping: Boolean,
    bootstrapComplete: Boolean,
    bootstrapError: String?,
    onStartBootstrap: () -> Unit,
    onRetryBootstrap: () -> Unit,
    onExitApp: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = "安装",
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
                            contentDescription = "Back",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(80.dp))
            
            if (isBootstrapping) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "正在配置终端环境...",
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "首次安装需要下载并配置基础环境",
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                )
            } else if (bootstrapComplete) {
                // 安装成功
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓",
                        style = TextStyle(
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "配置完成",
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "终端环境已成功初始化",
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                Button(
                    onClick = { onNext() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "继续",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else if (bootstrapError != null) {
                // 安装失败
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.error),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "!",
                        style = TextStyle(
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "安装失败",
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.error
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = bootstrapError,
                            style = TextStyle(
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "可能原因:\n• 网络连接不稳定\n• 存储空间不足\n• 设备不支持",
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onExitApp() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            text = "退出",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Button(
                        onClick = { onRetryBootstrap() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "重试",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            } else {
                // 初始状态 - 等待开始
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "…",
                        style = TextStyle(
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "准备安装",
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "点击下方按钮开始配置终端环境",
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                Button(
                    onClick = { onStartBootstrap() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "开始安装",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ==================== 第五页: 版本更新日志 ====================

@Composable
private fun OobeReleaseNotesPage(
    releaseNotes: String?,
    currentVersionName: String,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = "版本更新日志",
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
                            contentDescription = "Back",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Termux Ultra $currentVersionName",
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                if (releaseNotes != null && releaseNotes.isNotBlank()) {
                    MarkdownContent(text = releaseNotes)
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无更新日志",
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                    }
                }
            }
            
            Button(
                onClick = { onNext() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "继续",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun MarkdownContent(text: String) {
    val lines = text.lines()
    Column {
        for (line in lines) {
            when {
                line.startsWith("### ") -> {
                    Text(
                        text = line.removePrefix("### "),
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                line.startsWith("## ") -> {
                    Text(
                        text = line.removePrefix("## "),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                    )
                }
                line.startsWith("# ") -> {
                    Text(
                        text = line.removePrefix("# "),
                        style = TextStyle(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    Row(
                        modifier = Modifier.padding(vertical = 1.dp)
                    ) {
                        Text(
                            text = "• ",
                            style = TextStyle(
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                        Text(
                            text = line.substring(2),
                            style = TextStyle(
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
                line.isBlank() -> {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                else -> {
                    Text(
                        text = line,
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    }
}

// ==================== 第六页: 完成 ====================

@Composable
private fun OobeCompletePage(
    onComplete: () -> Unit,
    useGradient: Boolean
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp, bottom = 60.dp + 32.dp)
        ) {
            // Logo
            val appIcon = remember {
                ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                    ?.toBitmap()
                    ?.asImageBitmap()
            }
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = "Logo",
                        modifier = Modifier.size(96.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Termux Ultra",
                style = TextStyle(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "配置完成",
                style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
            )

            Spacer(modifier = Modifier.weight(1f))

            // 完成按钮 - 白底黑字, 90%不透明度
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White.copy(alpha = 0.9f))
                    .clickable { onComplete() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "完成",
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                )
            }
        }
    }
}
