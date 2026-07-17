package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.termux.R

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()

    var gradientOffset by remember { mutableFloatStateOf(0f) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var hasUpdate by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scope.launch {
            while (true) {
                gradientOffset += 0.002f
                if (gradientOffset > 1f) gradientOffset = 0f
                delay(16)
            }
        }
    }

    val darkTheme = isSystemInDarkTheme()
    val gradientColors = if (darkTheme) {
        listOf(
            Color(0xFF1a1a2e),
            Color(0xFF16213e),
            Color(0xFF0f3460),
            Color(0xFF1a1a2e)
        )
    } else {
        listOf(
            Color(0xFFfce7f3),
            Color(0xFFe0e7ff),
            Color(0xFFc7d2fe),
            Color(0xFFfbcfe8)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = gradientColors,
                    startY = gradientOffset * 2000f,
                    endY = gradientOffset * 2000f + 1000f
                )
            )
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = context.getString(R.string.about_preference_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { onBack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = context.getString(R.string.back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = 80.dp
                ),
                verticalArrangement = Arrangement.Top
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
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
                        Text(
                            text = getVersionName(context),
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        )
                        Spacer(modifier = Modifier.height(40.dp))
                    }
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
                    Card(
                        modifier = Modifier
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                if (!checkingUpdate) {
                                    checkingUpdate = true
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val fetchedVersion = fetchLatestVersion()
                                            latestVersion = fetchedVersion
                                            val currentVersion = getVersionName(context)
                                            hasUpdate = compareVersions(currentVersion, fetchedVersion) < 0
                                        } catch (e: Exception) {
                                            hasUpdate = false
                                        }
                                        checkingUpdate = false
                                        showUpdateDialog = true
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
                                        strokeWidth = 2.dp,
                                        color = MiuixTheme.colorScheme.primary
                                    )
                                }
                                Column {
                                    Text(
                                        text = context.getString(R.string.check_update),
                                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                            Text(
                                text = context.getString(R.string.termux_ultra_version),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                            Text(
                                text = getVersionName(context),
                                style = TextStyle(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = "${context.getString(R.string.based_on_termux_version)} 0.118.3",
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
                                text = "Termux Ultra 使用 GPL 3.0 以及 MIT 许可，部分代码使用了 Trae 进行 AI 生成。本项目 VNC 功能基于 avnc 项目，VNC 版权所属 ©2020  Gaurav Ujjwal。",
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
    }

    if (showUpdateDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showUpdateDialog = false }
        ) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (hasUpdate) context.getString(R.string.new_version_found) else context.getString(R.string.up_to_date),
                        style = TextStyle(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "${context.getString(R.string.current_version)}: ${getVersionName(context)}",
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        ),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    if (hasUpdate && latestVersion.isNotEmpty()) {
                        Text(
                            text = "${context.getString(R.string.latest_version)}: $latestVersion",
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            ),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (hasUpdate) {
                            Button(
                                onClick = {
                                    showUpdateDialog = false
                                    downloadUpdate(context, latestVersion)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    color = MiuixTheme.colorScheme.primary
                                )
                            ) {
                                Text(text = context.getString(R.string.update), fontWeight = FontWeight.Bold, color = Color.White)
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
        }
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

private fun getVersionName(context: android.content.Context): String {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo?.versionName ?: "1.0.0"
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        "1.0.0"
    }
}

private fun fetchLatestVersion(): String {
    return try {
        val url = java.net.URL("https://api.github.com/repos/TiG-Kira/Termux-Ultra/releases/latest")
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.setRequestProperty("User-Agent", "Termux-Ultra-App")
        
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val tagRegex = """"tag_name"\s*:\s*"([^"]+)"""".toRegex()
        val matchResult = tagRegex.find(response)
        val tagName = matchResult?.groupValues?.get(1) ?: "0.0.0.0.0"
        
        tagName.removePrefix("v").removePrefix("V")
    } catch (e: Exception) {
        "0.0.0.0.0"
    }
}

private fun compareVersions(version1: String, version2: String): Int {
    val parts1 = version1.split(".").map { it.toIntOrNull() ?: 0 }
    val parts2 = version2.split(".").map { it.toIntOrNull() ?: 0 }
    
    val maxLength = maxOf(parts1.size, parts2.size)
    
    for (i in 0 until maxLength) {
        val v1 = if (i < parts1.size) parts1[i] else 0
        val v2 = if (i < parts2.size) parts2[i] else 0
        
        if (v1 < v2) return -1
        if (v1 > v2) return 1
    }
    
    return 0
}

private fun getDeviceAbi(): String {
    return try {
        val abis = android.os.Build.SUPPORTED_ABIS
        if (abis.isNotEmpty()) {
            when (abis[0]) {
                "arm64-v8a" -> "arm64-v8a"
                "armeabi-v7a" -> "armeabi-v7a"
                "x86_64" -> "x86_64"
                "x86" -> "x86"
                else -> "universal"
            }
        } else {
            "universal"
        }
    } catch (e: Exception) {
        "universal"
    }
}

private fun downloadUpdate(context: android.content.Context, version: String) {
    val abi = getDeviceAbi()
    val buildType = getBuildType(context)
    val apkFileName = if (buildType == "release") {
        "app-${abi}-release.apk"
    } else {
        "app-${abi}-debug.apk"
    }
    val downloadUrl = "https://github.com/TiG-Kira/Termux-Ultra/releases/download/$version/$apkFileName"
    
    val intent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse(downloadUrl)
    )
    context.startActivity(intent)
}

private fun getBuildType(context: android.content.Context): String {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val applicationInfo = packageInfo?.applicationInfo
        if (applicationInfo != null && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)) {
            "debug"
        } else {
            "release"
        }
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        "debug"
    }
}