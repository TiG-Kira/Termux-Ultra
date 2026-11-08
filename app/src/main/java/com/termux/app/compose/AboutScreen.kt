package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import coil.compose.AsyncImage
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.termux.R
import com.termux.app.utils.UpdateChecker
import com.termux.app.utils.UpdateResult
import com.termux.app.utils.ApkDownloader
import com.termux.app.utils.SnackbarHelper
import com.google.android.material.snackbar.Snackbar
import com.termux.BuildConfig
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.collect

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 本页在 MainScreen 取景层之外，自建一层供玻璃顶栏折射页面内容
    val glassPage = rememberGlassPageBackdrop()
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
    val termuxCoreVersion = remember { BuildConfig.TERMUX_CORE_VERSION }
    val isWorkflowCliBuild = remember { try { BuildConfig.WORKFLOW_CLI } catch (_: Throwable) { false } }

    LaunchedEffect(Unit) {
        scope.launch {
            val status = UpdateChecker.getReleaseStatus(currentVersion)
            if (status != null) {
                releaseStatus = status
            }
        }
    }

    val darkTheme = isSystemInDarkTheme()

    // 动效时间轴：背景 / Logo / 字标共用同一组调色板，视觉上是一体的。
    // 只在 draw 阶段读取，逐帧只失效绘制，不会让整页重组。
    val aboutTime = rememberAboutAnimationTime()
    val enterLogo = rememberHeroEnter(0)
    val enterTitle = rememberHeroEnter(90)
    val enterVersion = rememberHeroEnter(180)

    // 滚动状态跟踪
    val listState = rememberLazyListState()
    val headerHeightPx = with(density) { 100.dp.toPx() }
    var scrollFraction by remember { mutableFloatStateOf(0f) }
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

    // 动态变色背景：Compose 渐变场（线性底色 + 4 个正弦漂移光斑），滚过头部即整体淡出。
    // 旧实现是 AndroidView + RuntimeShader + 120dp blur，既贵又只能当背景。
    Box(
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
    ) {
        AboutGradientBackground(
            animationTime = aboutTime,
            dark = darkTheme,
            alpha = { 1f - scrollFraction },
            modifier = Modifier.fillMaxSize(),
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                GlassTopAppBar(
                    title = context.getString(R.string.about_preference_title),
                    // 本页渐变背景画在 Scaffold 之外（取景层里没有内容），
                    // 开带区模糊会采到透明(=黑)形成墨团，所以这页保留底板不模糊。
                    bandBlurRadius = 0.dp,
                    backdrop = glassPage.backdrop,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        GlassIconButton(onClick = onBack) {
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
        ) { padding ->
            // 取景层里必须有不透明底色：Scaffold 是 Transparent + 渐变画在外面，
            // 否则模糊采样到"透明=黑"，顶栏会出现墨团。
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .then(glassPage.contentModifier)
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = standaloneContentPadding(padding, bottom = systemNavBarsHeight + 26.dp),
                verticalArrangement = Arrangement.Top
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = 1f - scrollFraction }
                            .padding(top = 60.dp, bottom = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val headerFg = if (darkTheme) Color.White else Color(0xFF333333)
                        // Logo 特效：单色 `>_` 图形当蒙版，流动渐变灌进形状 + 呼吸辉光。
                        // 蒙版用 ic_foreground_mono（透明底、只有描线），不能用带深色 squircle 底的
                        // 彩色 launcher 图标——DstIn 吃 alpha，整块不透明会把渐变灌成一块实心色板。
                        val logoMask = rememberGradientLogoMask(R.drawable.ic_foreground_mono)
                        if (logoMask != null) {
                            AboutGradientLogo(
                                logo = logoMask,
                                animationTime = aboutTime,
                                dark = darkTheme,
                                logoSize = 128.dp,
                                modifier = Modifier.heroEnter(enterLogo)
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_terminal),
                                contentDescription = "Logo",
                                modifier = Modifier.size(60.dp).heroEnter(enterLogo),
                                tint = headerFg
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        // 文字特效：文字本身当蒙版灌渐变 + 扫光
                        GradientWordmark(
                            text = "Termux Ultra",
                            animationTime = aboutTime,
                            dark = darkTheme,
                            fontSize = 36,
                            modifier = Modifier.heroEnter(enterTitle)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heroEnter(enterVersion)
                        ) {
                            Text(
                                text = currentVersion,
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    color = headerFg
                                )
                            )
                            if (isWorkflowCliBuild) {
                                WorkflowTag()
                            } else if (releaseStatus == UpdateChecker.ReleaseStatus.PRERELEASE) {
                                BetaTag()
                            } else if (releaseStatus == UpdateChecker.ReleaseStatus.NOT_FOUND) {
                                InternalBuildTag()
                            }
                        }
                    }
                }

                item {
                     Spacer(modifier = Modifier.height(12.dp))
                 }

                item {
                    Card(
                        modifier = Modifier
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
                     Spacer(modifier = Modifier.height(12.dp))
                 }

                item {
                     Card(
                         modifier = Modifier
                             .fillMaxWidth()
                             .padding(horizontal = 16.dp)
                     ) {
                         ArrowPreference(
                             title = context.getString(R.string.developer_name),
                             summary = "@TiG-Kira",
                             onClick = {
                                 val intent = android.content.Intent(
                                     android.content.Intent.ACTION_VIEW,
                                     android.net.Uri.parse("https://github.com/TiG-Kira")
                                 )
                                 context.startActivity(intent)
                             },
                             startAction = {
                                 Box(
                                     modifier = Modifier
                                         .size(40.dp)
                                         .clip(CircleShape)
                                         .background(MiuixTheme.colorScheme.surfaceVariant),
                                     contentAlignment = Alignment.Center
                                 ) {
                                     AsyncImage(
                                         model = "https://github.com/TiG-Kira.png",
                                         contentDescription = "Developer Avatar",
                                         modifier = Modifier.size(40.dp)
                                     )
                                 }
                             }
                         )
                     }
                 }

                 item {
                     Spacer(modifier = Modifier.height(12.dp))
                 }

                 item {
                     Card(
                         modifier = Modifier
                             .fillMaxWidth()
                             .padding(horizontal = 16.dp)
                     ) {
                         ArrowPreference(
                             title = context.getString(R.string.contributor_yhlfurry_name),
                             summary = context.getString(R.string.contributor_yhlfurry_summary),
                             onClick = {
                                 val intent = android.content.Intent(
                                     android.content.Intent.ACTION_VIEW,
                                     android.net.Uri.parse("https://github.com/" +
                                         context.getString(R.string.contributor_yhlfurry_github))
                                 )
                                 context.startActivity(intent)
                             },
                             startAction = {
                                 Box(
                                     modifier = Modifier
                                         .size(40.dp)
                                         .clip(CircleShape)
                                         .background(MiuixTheme.colorScheme.surfaceVariant),
                                     contentAlignment = Alignment.Center
                                 ) {
                                     AsyncImage(
                                         model = "https://github.com/" +
                                             context.getString(R.string.contributor_yhlfurry_github) + ".png",
                                         contentDescription = "Developer Avatar",
                                         modifier = Modifier.size(40.dp)
                                     )
                                 }
                             }
                         )
                     }
                 }

                item {
                    Text(
                        text = context.getString(R.string.about_contributors_section),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                }

                 item {
                     Card(
                         modifier = Modifier
                             .fillMaxWidth()
                             .padding(horizontal = 16.dp)
                     ) {
                         ArrowPreference(
                             title = context.getString(R.string.contributor_awkoo_name),
                             summary = "@awkox",
                             onClick = {
                                 val intent = android.content.Intent(
                                     android.content.Intent.ACTION_VIEW,
                                     android.net.Uri.parse("https://github.com/awkox")
                                 )
                                 context.startActivity(intent)
                             },
                             startAction = {
                                 Box(
                                     modifier = Modifier
                                         .size(40.dp)
                                         .clip(CircleShape)
                                         .background(MiuixTheme.colorScheme.surfaceVariant),
                                     contentAlignment = Alignment.Center
                                 ) {
                                     AsyncImage(
                                         model = "https://avatars.githubusercontent.com/u/133107732?v=4",
                                         contentDescription = "Contributor Avatar",
                                         modifier = Modifier.size(40.dp)
                                     )
                                 }
                             }
                         )
                     }
                 }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                 item {
                     val updateSummary = when {
                         checkingUpdate -> context.getString(R.string.checking_updates)
                         updateResult is UpdateResult.UpdateAvailable -> {
                             val available = updateResult as UpdateResult.UpdateAvailable
                             if (available.isBeta) context.getString(R.string.beta_version_available)
                             else context.getString(R.string.new_version_available)
                         }
                         updateResult is UpdateResult.UpToDate -> context.getString(R.string.up_to_date)
                         else -> context.getString(R.string.about_check_updates_desc)
                     }
                     Card(
                         modifier = Modifier
                             .fillMaxWidth()
                             .padding(horizontal = 16.dp)
                     ) {
                         ArrowPreference(
                             title = context.getString(R.string.check_updates),
                             summary = updateSummary,
                             onClick = {
                                if (!checkingUpdate) {
                                    if (isWorkflowCliBuild) {
                                        SnackbarHelper.show(context, context.getString(R.string.workflow_cli_cannot_update), Snackbar.LENGTH_SHORT, null)
                                    } else {
                                        checkingUpdate = true
                                        scope.launch {
                                            updateResult = UpdateChecker.checkForUpdates(currentVersion, betaUpdateEnabled)
                                            showUpdateDialog = true
                                            checkingUpdate = false
                                        }
                                    }
                                }
                            }
                         )
                     }
                 }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                item {
                    Card(
                        modifier = Modifier
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
                        modifier = Modifier
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = currentVersion,
                                    style = TextStyle(
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                )
                                if (isWorkflowCliBuild) WorkflowTag()
                            }
                            Text(
                                text = context.getString(R.string.based_on_termux_version) + " " + termuxCoreVersion + "\n" +
                                    context.getString(R.string.libterminal_core_info, BuildConfig.LIBTERMINAL_VERSION) + "\n" +
                                    context.getString(R.string.rustdesk_core_version_info, BuildConfig.RUSTDESK_CORE_VERSION),
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
                        modifier = Modifier
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
                            Text(
                                text = context.getString(R.string.about_upstream_copyright),
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                        }
                    }
                }
            }

    if (showUpdateDialog && updateResult != null) {
        if (pendingInstallVersion != null && ApkDownloader.hasInstallPermission(context)) {
            val apkFile = ApkDownloader.getDownloadedApkFile(context, pendingInstallVersion!!)
            LaunchedEffect(pendingInstallVersion) {
                // 授权往返期间产物可能被替换，安装前在后台重新校验一次
                val verified = withContext(Dispatchers.IO) {
                    ApkDownloader.verifyApkFile(context, apkFile).isSuccess
                }
                if (verified) {
                    runCatching { ApkDownloader.installApk(context, apkFile) }
                } else {
                    withContext(Dispatchers.IO) { apkFile.delete() }
                }
                pendingInstallVersion = null
                showUpdateDialog = false
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
                // 有新版本时展示新版本的 Release Notes；
                // 已是最新版时，若当前版本自身有 Release Notes 也照样展示。
                val notesToShow = when (result) {
                    is UpdateResult.UpdateAvailable -> result.releaseNotes
                    is UpdateResult.UpToDate -> result.releaseNotes
                    is UpdateResult.CheckFailed -> ""
                }
                if (notesToShow.isNotBlank()) {
                    Text(
                        text = context.getString(R.string.update_log),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    MarkdownContent(
                        text = notesToShow,
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
private fun WorkflowTag() {
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
