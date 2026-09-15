package com.termux.app.compose

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.BlendMode
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
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
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.CheckboxPreference

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon as MaterialIcon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.delay

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
    onCompleteStart: () -> Unit,   // 启动 MainActivity (动画开始前调用)
    onCompleteFinish: () -> Unit   // finish OOBE Activity (动画结束后调用)
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
            androidx.compose.ui.graphics.lerp(Color(0xFFF52828), Color(0xFFFF7878), gradientFraction),
            androidx.compose.ui.graphics.lerp(Color(0xFF9AA8F5), Color(0xFFC5CCFC), gradientFraction),
            androidx.compose.ui.graphics.lerp(Color(0xFF4D49D6), Color(0xFF8A87E6), gradientFraction)
        )
    )
    val darkGradient = Brush.verticalGradient(
        colors = listOf(
            androidx.compose.ui.graphics.lerp(Color(0xFF8B1A1A), Color(0xFFF52828), gradientFraction),
            androidx.compose.ui.graphics.lerp(Color(0xFF3A4273), Color(0xFF9AA8F5), gradientFraction),
            androidx.compose.ui.graphics.lerp(Color(0xFF1E1C63), Color(0xFF4D49D6), gradientFraction)
        )
    )

    // 跳过逻辑
    val shouldSkipEula = isUpgrade && eulaLastModified == eulaLastStored && eulaLastStored.isNotEmpty()
    val shouldSkipPermissionsAndInstall = isUpgrade

    // 计算实际页面索引（考虑跳过）
    val actualPage = remember(currentPage, isUpgrade, shouldSkipEula, shouldSkipPermissionsAndInstall) {
        currentPage
    }

    // Circular reveal state (Welcome -> EULA transition)
    var isCircularRevealing by remember { mutableStateOf(false) }
    var revealRadius by remember { mutableStateOf(0f) }
    var revealAlpha by remember { mutableStateOf(1f) }
    var revealCenter by remember { mutableStateOf(Offset.Zero) }
    var revealTargetPage by remember { mutableStateOf<Int?>(null) }

    // REAL button center captured via onGloballyPositioned (not hardcoded!)
    var welcomeBtnCenter by remember { mutableStateOf(Offset.Zero) }
    var completeBtnCenter by remember { mutableStateOf(Offset.Zero) }

    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    // maxRevealRadius large enough so that scaleXY = r/maxHalf covers entire screen
    // Button center is near bottom; need radius >= center.y to cover top of screen
    val maxRevealRadius = with(density) {
        maxOf(
            configuration.screenWidthDp.dp.toPx(),
            configuration.screenHeightDp.dp.toPx()
        ) * 3.0f
    }

    // Trigger circular reveal transition from Welcome to next page
    fun triggerCircularReveal(btnCenter: Offset, targetPage: Int) {
        if (isCircularRevealing) return
        revealCenter = btnCenter
        revealRadius = 0f
        revealTargetPage = targetPage
        isCircularRevealing = true
        coroutineScope.launch {
            val duration = 600
            val steps = 60
            val stepDuration = duration / steps
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                revealRadius = maxRevealRadius * eased
                if (i % 10 == 0) {
                }
                delay(stepDuration.toLong())
            }
            // Animation complete — Layer 2 (clip circle at full size) covers entire screen.
            // Now switch Layer 1 to target, then cleanup Layer 2 (which is already invisible on target page)
            onPageChange(targetPage)
            delay(80)
            isCircularRevealing = false
            revealTargetPage = null
            revealRadius = 0f
        }
    }

    // Complete 按钮 → MainActivity：普通 Activity 切换（无动画）
    fun triggerCompleteReveal(btnCenter: Offset) {
        onCompleteStart()
        onCompleteFinish()
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

    // Dual-track background: API 33+ RuntimeShader animated, older Brush fallback
    val useShaderBg = android.os.Build.VERSION.SDK_INT >= 31
    var bgController by remember { mutableStateOf<AboutBgEffect.ShaderController?>(null) }
    var bgDarkTheme by remember { mutableStateOf(darkTheme) }

    Box(
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
    ) {
        if (useGradient) {
            if (useShaderBg) {
                AndroidView(
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer { alpha = if (darkTheme) 0.5f else 1f }
                        .blur(120.dp)
                        .zIndex(-1f),
                    factory = { ctx ->
                        android.view.View(ctx).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            val ctl = AboutBgEffect.createFor(this, darkTheme)
                            bgController = ctl
                            bgDarkTheme = darkTheme
                            ctl?.start()
                        }
                    },
                    update = { v ->
                        if (bgDarkTheme != darkTheme) {
                            bgController?.updateParams(AboutBgEffect.getParams(darkTheme))
                            bgDarkTheme = darkTheme
                        }
                    }
                )
                DisposableEffect(Unit) {
                    onDispose { bgController?.stop() }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (darkTheme) 0.5f else 1f }
                        .background(if (darkTheme) darkGradient else lightGradient)
                )
            }
        }
        // Circular Reveal Transition Box
        Box(modifier = Modifier.fillMaxSize()) {
            // Layer 1: Current page — Welcome 独立，中间页(1-5)用 AnimatedContent 做 slide+fade
            Box(modifier = Modifier.fillMaxSize()) {
                if (currentPage == 0) {
                    // Welcome 页：独立显示，Circular Reveal 接管切出动画
                    OobeWelcomePage(
                        isUpgrade = isUpgrade,
                        onNext = { _ ->
                            val nextP = if (!isUpgrade) 1 else if (shouldSkipEula) 4 else 1
                            if (currentPage == 0 && !isCircularRevealing) {
                                if (welcomeBtnCenter != Offset.Zero) {
                                    triggerCircularReveal(welcomeBtnCenter, nextP)
                                } else {
                                    goNext()
                                }
                            } else {
                                goNext()
                            }
                        },
                        darkTheme = darkTheme,
                        onButtonPositioned = { centerPx ->
                            welcomeBtnCenter = centerPx
                        }
                    )
                } else {
                    // 中间页 + Complete 页：HyperCeiler 风格 slide+fade 切换
                    androidx.compose.animation.AnimatedContent(
                        targetState = currentPage,
                        label = "oobeMiddlePages",
                        transitionSpec = {
                            val isForward = targetState > initialState
                            if (isForward) {
                                // 前进：新页从右滑入 + fadeIn，旧页向左滑出小距离 + fadeOut
                                androidx.compose.animation.slideInHorizontally(
                                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                                ) { fullWidth -> fullWidth } +
                                androidx.compose.animation.fadeIn(
                                    animationSpec = tween(durationMillis = 200, delayMillis = 60, easing = LinearOutSlowInEasing)
                                ) togetherWith
                                androidx.compose.animation.slideOutHorizontally(
                                    animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
                                ) { fullWidth -> -fullWidth / 5 } +
                                androidx.compose.animation.fadeOut(
                                    animationSpec = tween(durationMillis = 120, easing = FastOutLinearInEasing)
                                )
                            } else {
                                // 后退：新页从左滑入小距离 + fadeIn，旧页向右滑出 + fadeOut
                                androidx.compose.animation.slideInHorizontally(
                                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                                ) { fullWidth -> -fullWidth / 5 } +
                                androidx.compose.animation.fadeIn(
                                    animationSpec = tween(durationMillis = 200, delayMillis = 60, easing = LinearOutSlowInEasing)
                                ) togetherWith
                                androidx.compose.animation.slideOutHorizontally(
                                    animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
                                ) { fullWidth -> fullWidth } +
                                androidx.compose.animation.fadeOut(
                                    animationSpec = tween(durationMillis = 120, easing = FastOutLinearInEasing)
                                )
                            }
                        }
                    ) { page ->
                        when (page) {
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
                                onComplete = {
                                    onCompleteStart()
                                    onCompleteFinish()
                                },
                                darkTheme = darkTheme,
                                onButtonPositioned = { centerPx ->
                                    completeBtnCenter = centerPx
                                }
                            )
                        }
                    }
                }
            }

            // Layer 2: Circular Reveal — fillMaxSize + 动态 DynamicCircleShape clip
            // 目标页保持正常屏幕尺寸，只在圆形区域内可见
            if (isCircularRevealing && revealTargetPage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(DynamicCircleShape(revealCenter, revealRadius.coerceAtLeast(0f)))
                ) {
                    // Target page fills the clipped box — 正常屏幕尺寸！
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (revealTargetPage) {
                            1 -> OobeEulaPage(
                                eulaAgreed = eulaAgreed,
                                onEulaAgreeChange = onEulaAgreeChange,
                                onBack = { goBack() },
                                onNext = { goNext() },
                                eulaLastModified = eulaLastModified,
                                darkTheme = darkTheme
                            )
                            4 -> OobeReleaseNotesPage(
                                releaseNotes = releaseNotes,
                                currentVersionName = currentVersionName,
                                onNext = { goNext() },
                                onBack = { goBack() }
                            )
                        }
                    }
                }
            }
        }
    }
}


// ==================== 第一页: 欢迎 ====================

@Composable
private fun OobeWelcomePage(
    isUpgrade: Boolean,
    onNext: (Offset) -> Unit,
    darkTheme: Boolean,
    onButtonPositioned: (Offset) -> Unit = {}
) {
    val context = LocalContext.current
    val headerFg = if (darkTheme) Color.White else Color(0xFF333333)
    val fgInt = android.graphics.Color.argb(
        (headerFg.alpha * 255).toInt(),
        (headerFg.red * 255).toInt(),
        (headerFg.green * 255).toInt(),
        (headerFg.blue * 255).toInt()
    )
    val appIcon = remember(darkTheme) {
        val orig = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()
        orig?.let { bm ->
            val copy = bm.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(copy.width * copy.height)
            copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
            for (i in pixels.indices) {
                val px = pixels[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val a = (px shr 24) and 0xFF
                val brightness = (r + g + b) / 3f
                if (a > 128 && brightness > 180f) {
                    pixels[i] = 0x00000000
                } else if (a > 128) {
                    pixels[i] = fgInt
                }
            }
            copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
            copy.asImageBitmap()
        }
    }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    // ========== HyperCeiler OOBE 入场动画 ==========
    var animStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animStarted = true }

    // LOGO: 0.5→0.95 (sinOut 440ms) → 1.0 (cubicOut 700ms) - Folme state machine
    var logoScaleState by remember { mutableStateOf(0.5f) }
    LaunchedEffect(animStarted) {
        if (animStarted) {
            logoScaleState = 0.95f
            delay(440)
            logoScaleState = 1.0f
        }
    }
    val logoScale by animateFloatAsState(
        targetValue = logoScaleState,
        animationSpec = tween(
            durationMillis = if (logoScaleState == 0.95f) 440 else 700,
            easing = if (logoScaleState == 0.95f) FastOutSlowInEasing else CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
        ),
        label = "logoScale"
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (animStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 230, delayMillis = 60),
        label = "logoAlpha"
    )

    // TEXT: translationY 100dp→0 + alpha 0→1 (HyperCeiler AnimHelper)
    val textOffsetY by animateFloatAsState(
        targetValue = if (animStarted) 0f else 100f,
        animationSpec = tween(durationMillis = 1700, easing = FastOutSlowInEasing),
        label = "textOffset"
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (animStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 1400, delayMillis = 300, easing = FastOutSlowInEasing),
        label = "textAlpha"
    )

    // BUTTON: scale 0.9→1.0 + alpha 0→1, delay 1340ms (logo settle first)
    val btnScale by animateFloatAsState(
        targetValue = if (animStarted) 1f else 0.9f,
        animationSpec = tween(durationMillis = 450, delayMillis = 1340,
                              easing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)),
        label = "btnScale"
    )
    val btnAlpha by animateFloatAsState(
        targetValue = if (animStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 450, delayMillis = 1340,
                              easing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)),
        label = "btnAlpha"
    )
    val upgradeAlpha by animateFloatAsState(
        targetValue = if (animStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 500, delayMillis = 1340),
        label = "upgradeAlpha"
    )

    // Glow pulse: infinite breathing behind logo (HyperCeiler GlowController)
    val infiniteTransition = rememberInfiniteTransition(label = "oobeWelcomeGlow")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    // Real button center captured via onGloballyPositioned (for circular reveal transition)
    

    Box(modifier = Modifier.fillMaxSize()) {
        // Header - vertically centered
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().align(Alignment.Center)
        ) {
            // Glow + Logo
            Box(
                modifier = Modifier.size(160.dp).graphicsLayer {
                    scaleX = logoScale; scaleY = logoScale; alpha = logoAlpha
                },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size((120 * glowPulse).dp).graphicsLayer { alpha = glowAlpha }
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    headerFg.copy(alpha = 0.35f),
                                    headerFg.copy(alpha = 0.15f),
                                    Color.Transparent
                                ),
                                radius = 140f
                            ),
                            shape = CircleShape
                        )
                )
                if (appIcon != null) {
                    Image(bitmap = appIcon, contentDescription = "Logo", modifier = Modifier.size(100.dp))
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_terminal),
                        contentDescription = "Logo",
                        modifier = Modifier.size(60.dp),
                        tint = headerFg
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Termux Ultra",
                style = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Black, color = headerFg),
                modifier = Modifier.graphicsLayer {
                    translationY = textOffsetY
                    alpha = textAlpha
                }
            )

            if (isUpgrade) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.upgrade_complete),
                    style = TextStyle(fontSize = 16.sp, color = headerFg.copy(alpha = 0.7f)),
                    modifier = Modifier.graphicsLayer {
                        alpha = upgradeAlpha
                    }
                )
            }
        }

        // Bottom button - triggers circular reveal transition
        Box(
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
                .size(64.dp)
                .onGloballyPositioned { coords ->
                    val topLeft = coords.positionInRoot()
                    val size = coords.size
                    val center = Offset(
                        x = topLeft.x + size.width / 2f,
                        y = topLeft.y + size.height / 2f
                    )
                    onButtonPositioned(center)
                }
                .graphicsLayer { scaleX = btnScale; scaleY = btnScale; alpha = btnAlpha }
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.White.copy(alpha = if (darkTheme) 0.2f else 0.9f))
                .clickable { onNext(Offset.Zero) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = "Next",
                tint = if (darkTheme) Color.White else Color.Black,
                modifier = Modifier.size(32.dp)
            )
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
    darkTheme: Boolean,
    transparentBackground: Boolean = false
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (transparentBackground) Color.Transparent else MiuixTheme.colorScheme.surface)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                start = 24.dp,
                end = 24.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
            )
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = stringResource(R.string.provision_back),
                    tint = MiuixTheme.colorScheme.onSurface
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.oobe_terms),
                contentDescription = null,
                modifier = Modifier.size(70.dp)
            )
        }

        Text(
            text = stringResource(R.string.license_agreement),
            style = TextStyle(
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "请仔细阅读以下条款，继续使用即表示您同意受其约束。",
            style = TextStyle(
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        MaterialIcon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "在使用 Termux Ultra 前，您必须阅读并同意我们的用户协议和隐私政策。我们将依法保护您的个人信息。",
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Termux Ultra 用户许可条款",
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

        Column {
            CheckboxPreference(
                title = stringResource(R.string.i_agree_license),
                checked = eulaAgreed,
                onCheckedChange = { onEulaAgreeChange(it) }
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onNext() },
                    enabled = eulaAgreed,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                ) {
                    Text(
                        text = stringResource(R.string.critical_force_enable_action_continue),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
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
        "11. VorteX Guard 安全引擎：VorteX Guard 仅作为辅助风险提示与拦截工具，其规则基于已知特征与启发式判断，不可能识别所有潜在风险，亦不保证 100% 拦截恶意行为。当您将防护等级设置为「仅提示」「关闭」，或开启「仅审查 Root 命令」「自定义规则」等选项后，安全防护能力会相应降低，由此带来的风险由您自行承担。",
        "",
        "12. 插件与第三方扩展：本软件支持加载第三方插件。插件由其作者独立开发并承担责任，开发者不对第三方插件的安全性、正确性、合规性作出担保。您应在加载前自行审查插件来源与代码。",
        "",
        "13. 自定义脚本与规则：您导入的自定义安全规则、自定义启动脚本、Agent 提示词等内容，其合法性、安全性、有效性由您自行负责。因上述内容导致的虚拟机异常、数据损坏、安全绕过，开发者不承担责任。",
        "",
        "14. 开源组件声明：本软件基于 Termux 相关项目与 QEMU 等开源项目构建，受其上游许可条款约束。上游项目的 bug、安全问题与行为变更，按上游项目的政策处理，不属于本软件开发者的维护范围。",
        "",
        "15. 不提供担保：在适用法律允许的最大范围内，本软件按「现状」（AS IS）提供，不附带任何明示或默示担保，包括但不限于对适销性、特定用途适用性、不侵权的默示担保。",
        "",
        "16. 责任上限：在适用法律允许的最大范围内，开发者及其贡献者不对任何间接、附带、特殊、惩罚性或后果性损害（包括数据丢失、利润损失、业务中断、设备损坏）承担责任，即便已被告知该等损害的可能性。",
        "",
        "三、知识产权",
        "本软件遵循 GNU General Public License v3.0（GPL-3.0）发布。",
        "本软件中集成的各组件分别受其各自开源许可协议约束。",
        "您有权依据 GPL-3.0 的条款使用、修改、再分发本软件源码；但再分发时必须同样以 GPL-3.0 开源，并保留原始版权声明与许可声明。您不得将本软件及其修改版本用于闭源、专有或商业分发目的，除非另行取得书面授权。",
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
    onNext: () -> Unit,
    transparentBackground: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (transparentBackground) Color.Transparent else MiuixTheme.colorScheme.surface)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                start = 24.dp,
                end = 24.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
            )
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = stringResource(R.string.provision_back),
                    tint = MiuixTheme.colorScheme.onSurface
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.oobe_service_state),
                contentDescription = null,
                modifier = Modifier.size(70.dp)
            )
        }

        Text(
            text = stringResource(R.string.file_info_permissions),
            style = TextStyle(
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "授予所需权限以确保 Termux Ultra 正常运行",
            style = TextStyle(
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            PermissionItemCard(
                title = "网络访问",
                desc = "运行命令、下载包、远程连接",
                granted = true,
                icon = { MaterialIcon(imageVector = Icons.Default.Wifi, contentDescription = null, tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) }
            )
            Spacer(modifier = Modifier.height(12.dp))
            PermissionItemCard(
                title = "文件存储",
                desc = "访问设备存储空间",
                granted = true,
                icon = { MaterialIcon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) }
            )
            Spacer(modifier = Modifier.height(12.dp))
            PermissionItemCard(
                title = "唤醒锁定",
                desc = "后台运行时保持活跃",
                granted = true,
                icon = { MaterialIcon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) }
            )
            Spacer(modifier = Modifier.height(12.dp))
            PermissionItemCard(
                title = "震动反馈",
                desc = "触觉反馈",
                granted = true,
                icon = { MaterialIcon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) }
            )
        }

        Text(
            text = permissionStatus,
            style = TextStyle(
                fontSize = 13.sp,
                color = if (isPermissionGranted) MiuixTheme.colorScheme.primary 
                       else MiuixTheme.colorScheme.onSurfaceVariantSummary
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { onGrantAllPermissions() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "授权所有",
                    color = MiuixTheme.colorScheme.onSurface
                )
            }
            Button(
                onClick = { onNext() },
                enabled = isPermissionGranted,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
            ) {
                Text(
                    text = stringResource(R.string.critical_force_enable_action_continue),
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun PermissionItemCard(
    title: String,
    desc: String,
    granted: Boolean,
    icon: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
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
            Spacer(modifier = Modifier.width(12.dp))
            MaterialIcon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (granted) MiuixTheme.colorScheme.primary 
                       else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(22.dp)
            )
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
    onBack: () -> Unit,
    transparentBackground: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (transparentBackground) Color.Transparent else MiuixTheme.colorScheme.surface)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                start = 24.dp,
                end = 24.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
            )
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = stringResource(R.string.provision_back),
                    tint = MiuixTheme.colorScheme.onSurface
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.oobe_basic_settings),
                contentDescription = null,
                modifier = Modifier.size(70.dp)
            )
        }

        Text(
            text = stringResource(R.string.action_styling_install),
            style = TextStyle(
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "首次安装需要下载并配置终端环境，通常需要几分钟",
            style = TextStyle(
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isBootstrapping -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(56.dp))
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "正在配置终端环境...",
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "请稍候，首次安装需要下载基础环境",
                            style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        )
                    }
                }
                bootstrapComplete -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MiuixTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            MaterialIcon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(text = "配置完成", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "终端环境已成功初始化", style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                    }
                }
                bootstrapError != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MiuixTheme.colorScheme.error),
                            contentAlignment = Alignment.Center
                        ) {
                            MaterialIcon(imageVector = Icons.Default.Error, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(text = stringResource(R.string.install_failed), style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.error))
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = bootstrapError, style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = "可能原因：网络连接不稳定 / 存储空间不足 / 设备不支持", style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MiuixTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(text = "准备安装", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "点击下方按钮开始配置终端环境", style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                    }
                }
            }
        }

        when {
            bootstrapComplete -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onNext() }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)) {
                        Text(text = stringResource(R.string.critical_force_enable_action_continue), fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
            bootstrapError != null -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onExitApp() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.error)) {
                        Text(text = "退出", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Button(onClick = { onRetryBootstrap() }, modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.bootstrap_error_try_again), fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
            isBootstrapping -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)) {
                        Text(text = "正在配置...", fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
            else -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onStartBootstrap() }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)) {
                        Text(text = "开始安装", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}



// ==================== 第五页: 版本更新日志 ====================

@Composable
private fun OobeReleaseNotesPage(
    releaseNotes: String?,
    currentVersionName: String,
    onNext: () -> Unit,
    onBack: () -> Unit,
    transparentBackground: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (transparentBackground) Color.Transparent else MiuixTheme.colorScheme.surface)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                start = 24.dp,
                end = 24.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
            )
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = stringResource(R.string.provision_back),
                    tint = MiuixTheme.colorScheme.onSurface
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.oobe_terms),
                contentDescription = null,
                modifier = Modifier.size(70.dp)
            )
        }

        Text(
            text = "版本更新日志",
            style = TextStyle(
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Termux Ultra $currentVersionName",
            style = TextStyle(
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (releaseNotes != null && releaseNotes.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        MarkdownContent(text = releaseNotes)
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        MaterialIcon(imageVector = Icons.Default.Info, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "暂无更新日志", style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = { onNext() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)) {
                Text(text = stringResource(R.string.critical_force_enable_action_continue), fontWeight = FontWeight.Bold, color = Color.White)
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
    darkTheme: Boolean,
    onButtonPositioned: (Offset) -> Unit = {}
) {
    val context = LocalContext.current
    val headerFg = if (darkTheme) Color.White else Color(0xFF333333)
    val fgInt = android.graphics.Color.argb(
        (headerFg.alpha * 255).toInt(),
        (headerFg.red * 255).toInt(),
        (headerFg.green * 255).toInt(),
        (headerFg.blue * 255).toInt()
    )
    val appIcon = remember(darkTheme) {
        val orig = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()
        orig?.let { bm ->
            val copy = bm.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(copy.width * copy.height)
            copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val a = (c shr 24) and 0xFF
                val brightness = (r + g + b) / 3f
                if (a > 128 && brightness > 180f) {
                    pixels[i] = 0x00000000
                } else if (a > 128) {
                    pixels[i] = fgInt
                }
            }
            copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
            copy.asImageBitmap()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header — 真正垂直居中
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().align(Alignment.Center)
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = "Logo",
                    modifier = Modifier.size(100.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Termux Ultra",
                style = TextStyle(
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Black,
                    color = headerFg
                )
            )

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = "配置完成",
                style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = headerFg.copy(alpha = 0.85f)
                )
            )
        }

        // 完成按钮 — 底部居中
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = if (darkTheme) 0.15f else 0.9f))
                .clickable { onComplete() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.overview_done),
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (darkTheme) Color.White else Color.Black
                )
            )
        }
    }
}

// ==================== 动态圆形揭示 Shape ====================
// 用于 Circular Reveal 动画，圆心和半径都可以动态改变
private class DynamicCircleShape(
    private val center: Offset,  // px - 相对于 Box 左上角
    private val radius: Float    // px
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val r = radius.coerceAtLeast(0f)
        val path = androidx.compose.ui.graphics.Path()
        val rect = androidx.compose.ui.geometry.Rect(
            left = center.x - r,
            top = center.y - r,
            right = center.x + r,
            bottom = center.y + r
        )
        path.addOval(rect)
        return Outline.Generic(path)
    }
}



