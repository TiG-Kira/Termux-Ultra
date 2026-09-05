package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.termux.R
import com.termux.app.utils.UpdateChecker
import com.termux.app.utils.UpdateResult
import com.termux.app.utils.ApkDownloader
import com.termux.BuildConfig
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.flow.collect

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val systemNavBarsHeight = with(density) {
        WindowInsets.navigationBars.getBottom(density).toDp()
    }

    val updatePrefs = remember { context.getSharedPreferences(PREF_UPDATE, android.content.Context.MODE_PRIVATE) }

    var checkingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateResult?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var downloadingApk by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }
    var pendingInstallVersion by remember { mutableStateOf<String?>(null) }
    var releaseStatus by remember { mutableStateOf<UpdateChecker.ReleaseStatus?>(null) }
    var betaUpdateEnabled by remember { mutableStateOf(updatePrefs.getBoolean(KEY_ENABLE_BETA, false)) }
    var showBetaWarningDialog by remember { mutableStateOf(false) }

    val currentVersion = remember { BuildConfig.VERSION_NAME }
    val termuxCoreVersion = remember { context.getString(R.string.termux_core_version) }

    // 呼吸渐变动画 (FeatureCenterCard 风格)
    val infiniteTransition = rememberInfiniteTransition(label = "breathingGradient")
    val gradientFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradientBreathing"
    )

    LaunchedEffect(Unit) {
        scope.launch {
            val status = UpdateChecker.getReleaseStatus(currentVersion)
            if (status != null) {
                releaseStatus = status
            }
        }
    }

    val darkTheme = isSystemInDarkTheme()

    // 呼吸渐变颜色 (FeatureCenterCard 风格)
    val lightGradient = Brush.verticalGradient(
        colors = listOf(
            lerp(Color(0xFFFFE5EF), Color(0xFFF9DBE5), gradientFraction),
            lerp(Color(0xFFF7BAD1), Color(0xFFBCC1FF), gradientFraction),
            lerp(Color(0xFFA3A5F9), Color(0xFF8EAFFF), gradientFraction)
        )
    )
    val darkGradient = Brush.verticalGradient(
        colors = listOf(
            lerp(Color(0xFF330FE0), Color(0xFF934CBC), gradientFraction),
            lerp(Color(0xFF4C238C), Color(0xFF9E35AA), gradientFraction),
            lerp(Color(0xFF1C28D3), Color(0xFF0033C6), gradientFraction)
        )
    )
    // 滚动状态跟踪
    val listState = rememberLazyListState()
    val headerHeightPx = with(density) { 100.dp.toPx() }
    var scrollFraction by remember { mutableStateOf(0f) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.offset ?: 0 }
            .collect { offset ->
                val firstIndex = listState.firstVisibleItemIndex
                scrollFraction = if (firstIndex == 0) {
                    (-offset.toFloat() / headerHeightPx).coerceIn(0f, 1f)
                } else {
                    1f
                }
            }
    }

    // TopAppBar 透明度动画
    val topBarAlphaAnim by animateFloatAsState(
        targetValue = scrollFraction,
        label = "topBarAlpha"
    )

    // 页面遮罩透明度动画 (亮色: surface, 暗色: surface)
    val pageMaskAlphaAnim by animateFloatAsState(
        targetValue = scrollFraction,
        label = "pageMaskAlpha"
    )

    // 头部卡片淡出动画
    val headerAlphaAnim by animateFloatAsState(
        targetValue = 0.9f * (1f - scrollFraction),
        label = "headerAlpha"
    )

    // 其他卡片透明度: 初始85%不透明, 上滑到100%
    val cardsAlphaAnim by animateFloatAsState(
        targetValue = 0.85f + scrollFraction * 0.15f,
        label = "cardsAlpha"
    )


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (darkTheme) darkGradient else lightGradient)
    ) {
        // 页面遮罩: 亮色白/暗色黑, 跟随上滑渐显
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = pageMaskAlphaAnim }
                .background(MiuixTheme.colorScheme.surface)
        )
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                SmallTopAppBar(
                    modifier = Modifier.graphicsLayer { alpha = topBarAlphaAnim },
                    title = context.getString(R.string.about_preference_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        Spacer(modifier = Modifier.size(40.dp))
                    }
                )
            }
        ) { paddingValues ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = systemNavBarsHeight + 26.dp
                ),
                verticalArrangement = Arrangement.Top
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = headerAlphaAnim },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Spacer(modifier = Modifier.height(40.dp))
                        val appIcon = remember {
                            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                                ?.toBitmap()
                                ?.asImageBitmap()
                                ?.let { BitmapPainter(it) }
                        }
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            if (appIcon != null) {
                                Image(
                                    painter = appIcon,
                                    contentDescription = "Logo",
                                    modifier = Modifier.size(80.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_terminal),
                                    contentDescription = "Logo",
                                    modifier = Modifier.size(44.dp),
                                    tint = Color.White
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Termux Ultra",
                            style = TextStyle(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = currentVersion,
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            )
                            if (releaseStatus == UpdateChecker.ReleaseStatus.PRERELEASE) {
                                BetaTag()
                            } else if (releaseStatus == UpdateChecker.ReleaseStatus.NOT_FOUND) {
                                InternalBuildTag()
                            }
                        }
                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }

                item {
                    Card(
                        modifier = Modifier.graphicsLayer { alpha = cardsAlphaAnim }
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            InfoRow(
                                title = context.getString(R.string.device_model),
                                value = android.os.Build.MODEL
                            )
                            InfoRow(
                                title = context.getString(R.string.android_version),
                                value = android.os.Build.VERSION.RELEASE
                            )
                            InfoRow(
                                title = context.getString(R.string.kernel_version),
                                value = android.os.Build.DISPLAY
                            )
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.graphicsLayer { alpha = cardsAlphaAnim }
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/TiG-Kira")
                                )
                                context.startActivity(intent)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MiuixTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = "https://github.com/TiG-Kira.png",
                                        contentDescription = "Developer Avatar",
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                                Column(
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = context.getString(R.string.developer_name),
                                        style = TextStyle(
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MiuixTheme.colorScheme.onSurface
                                        )
                                    )
                                    Text(
                                        text = "@TiG-Kira",
                                        style = TextStyle(
                                            fontSize = 13.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    )
                                }
                            }
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_right),
                                contentDescription = context.getString(R.string.arrow),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.graphicsLayer { alpha = cardsAlphaAnim }
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                if (!checkingUpdate) {
                                    checkingUpdate = true
                                    scope.launch {
                                        updateResult = UpdateChecker.checkForUpdates(currentVersion, betaUpdateEnabled)
                                        showUpdateDialog = true
                                        checkingUpdate = false
                                    }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (checkingUpdate) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                                Column {
                                    Text(
                                        text = context.getString(R.string.check_updates),
                                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    )
                                    val hasUpdate = updateResult is UpdateResult.UpdateAvailable
                                    if (hasUpdate) {
                                        val available = updateResult as UpdateResult.UpdateAvailable
                                        Text(
                                            text = if (available.isBeta)
                                                context.getString(R.string.beta_version_available)
                                            else
                                                context.getString(R.string.new_version_available),
                                            style = TextStyle(
                                                fontSize = 13.sp,
                                                color = MiuixTheme.colorScheme.error
                                            )
                                        )
                                    }
                                }
                            }
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_right),
                                contentDescription = context.getString(R.string.arrow),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                item {
                    Card(
                        modifier = Modifier.graphicsLayer { alpha = cardsAlphaAnim }
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        SwitchPreference(
                            title = context.getString(R.string.enable_beta_update),
                            summary = context.getString(R.string.enable_beta_update_desc),
                            checked = betaUpdateEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    betaUpdateEnabled = true
                                    showBetaWarningDialog = true
                                } else {
                                    betaUpdateEnabled = false
                                    updatePrefs.edit().putBoolean(KEY_ENABLE_BETA, false).apply()
                                }
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    Card(
                        modifier = Modifier.graphicsLayer { alpha = cardsAlphaAnim }
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = context.getString(R.string.termux_ultra_version),
                                    style = TextStyle(
                                        fontSize = 14.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                )
                            }
                            Text(
                                text = currentVersion,
                                style = TextStyle(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = context.getString(R.string.based_on_termux_version) + " " + termuxCoreVersion + "\n" +
                                    context.getString(R.string.libterminal_core_info, BuildConfig.LIBTERMINAL_VERSION),
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    Card(
                        modifier = Modifier.graphicsLayer { alpha = cardsAlphaAnim }
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = context.getString(R.string.about_license),
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }
            }

    if (showUpdateDialog && updateResult != null) {
        if (pendingInstallVersion != null && ApkDownloader.hasInstallPermission(context)) {
            val apkFile = ApkDownloader.getDownloadedApkFile(context, pendingInstallVersion!!)
            if (apkFile.exists()) {
                LaunchedEffect(Unit) {
                    ApkDownloader.installApk(context, apkFile)
                    pendingInstallVersion = null
                    showUpdateDialog = false
                }
            } else {
                pendingInstallVersion = null
            }
        }

        val result = updateResult!!
        OverlayDialog(
            show = showUpdateDialog,
            title = when (result) {
                is UpdateResult.UpdateAvailable -> {
                    if (result.isBeta) context.getString(R.string.beta_version_available)
                    else context.getString(R.string.new_version_found)
                }
                is UpdateResult.UpToDate -> context.getString(R.string.up_to_date)
                is UpdateResult.CheckFailed -> context.getString(R.string.up_to_date)
            },
            summary = when (result) {
                is UpdateResult.UpdateAvailable -> {
                    val preReleaseTag = if (result.isBeta) " (Beta) " else ""
                    "${context.getString(R.string.current_version)}: ${result.currentVersionName}\n${context.getString(R.string.latest_version)}: ${result.latestVersionName}${preReleaseTag}"
                }
                is UpdateResult.UpToDate -> {
                    "${context.getString(R.string.is_latest)} ${result.currentVersionName}"
                }
                is UpdateResult.CheckFailed -> {
                    context.getString(R.string.up_to_date)
                }
            },
            onDismissRequest = { showUpdateDialog = false },
            content = {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                if (result is UpdateResult.UpdateAvailable && result.releaseNotes.isNotBlank()) {
                    Text(
                        text = context.getString(R.string.update_log),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    MarkdownText(
                        text = result.releaseNotes,
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .heightIn(max = 200.dp)
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    )
                }
                if (downloadingApk) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = context.getString(R.string.downloading),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                            Text(
                                text = "$downloadProgress%",
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.primary
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MiuixTheme.colorScheme.surfaceVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(downloadProgress / 100f)
                                    .height(6.dp)
                                    .background(MiuixTheme.colorScheme.primary)
                            )
                        }
                        if (totalBytes > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${formatUpdateFileSize(downloadedBytes)} / ${formatUpdateFileSize(totalBytes)}",
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (result is UpdateResult.UpdateAvailable) Arrangement.spacedBy(8.dp) else Arrangement.Center
                ) {
                    if (result is UpdateResult.UpdateAvailable) {
                        TextButton(
                            text = context.getString(R.string.later),
                            onClick = { showUpdateDialog = false },
                            modifier = Modifier.weight(1f),
                            enabled = !downloadingApk
                        )
                        TextButton(
                            text = context.getString(R.string.manual),
                            onClick = {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(result.releaseUrl)
                                )
                                context.startActivity(intent)
                                showUpdateDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !downloadingApk
                        )
                        Button(
                            onClick = {
                                downloadingApk = true
                                downloadProgress = 0
                                downloadedBytes = 0L
                                totalBytes = 0L
                                val downloadUrl = ApkDownloader.constructDownloadUrl(result.latestVersionName, context)
                                scope.launch(Dispatchers.IO) {
                                    val downloadResult = ApkDownloader.downloadAndInstall(
                                        context,
                                        downloadUrl,
                                        result.latestVersionName
                                    ) { progress, downloaded, total ->
                                        downloadProgress = progress
                                        downloadedBytes = downloaded
                                        totalBytes = total
                                    }
                                    downloadingApk = false
                                    if (downloadResult.isFailure) {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(result.releaseUrl)
                                        )
                                        context.startActivity(intent)
                                    } else {
                                        if (!ApkDownloader.hasInstallPermission(context)) {
                                            pendingInstallVersion = result.latestVersionName
                                        }
                                    }
                                    if (ApkDownloader.hasInstallPermission(context)) {
                                        showUpdateDialog = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.primary
                            ),
                            enabled = !downloadingApk
                        ) {
                            Text(text = context.getString(R.string.download), fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = { showUpdateDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.primary
                            )
                        ) {
                            Text(text = context.getString(R.string.ok), fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
            }
        )
    }

    OverlayDialog(
        show = showBetaWarningDialog,
        title = context.getString(R.string.beta_warning_title),
        summary = context.getString(R.string.beta_warning_message),
        onDismissRequest = {
            betaUpdateEnabled = false
            showBetaWarningDialog = false
        },
        content = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = context.getString(R.string.beta_warning_cancel),
                    onClick = {
                        betaUpdateEnabled = false
                        showBetaWarningDialog = false
                    },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        updatePrefs.edit().putBoolean(KEY_ENABLE_BETA, true).apply()
                        showBetaWarningDialog = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = context.getString(R.string.beta_warning_confirm),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    )
    }

    // 独立返回按钮: 最顶层, 始终可见
    val statusBarHeightPx = WindowInsets.statusBars.getTop(LocalDensity.current)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp)
            .padding(top = (statusBarHeightPx.toFloat() / density.density).dp + 4.dp),
        contentAlignment = Alignment.TopStart
    ) {
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
    }
}
}

@Composable
private fun BetaTag() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.error.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "Beta",
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.error
            )
        )
    }
}

@Composable
private fun InternalBuildTag() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFF9800).copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "Internal",
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF9800)
            )
        )
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Column(
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = value,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
        )
        Text(
            text = title,
            style = TextStyle(
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            ),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val lines = text.lines()
    Column(modifier = modifier) {
        lines.forEach { line ->
            when {
                line.startsWith("### ") -> {
                    Text(
                        text = line.removePrefix("### "),
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
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
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
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
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
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
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
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
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    )
                }
            }
        }
    }
}

private fun formatUpdateFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes / 1024.0).toInt()} KB"
        bytes < 1024L * 1024 * 1024 -> "${(bytes / (1024.0 * 1024.0)).let { String.format("%.1f", it) }} MB"
        else -> "${(bytes / (1024.0 * 1024.0 * 1024.0)).let { String.format("%.2f", it) }} GB"
    }
}

private const val PREF_UPDATE = "update_preferences"
private const val KEY_ENABLE_BETA = "enable_beta_update"
