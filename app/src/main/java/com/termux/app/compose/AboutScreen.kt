package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
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

    LaunchedEffect(Unit) {
        scope.launch {
            while (true) {
                gradientOffset += 0.002f
                if (gradientOffset > 1f) gradientOffset = 0f
                delay(16)
            }
        }
    }

    val gradientColors = listOf(
        Color(0xFFfce7f3),
        Color(0xFFe0e7ff),
        Color(0xFFc7d2fe),
        Color(0xFFfbcfe8)
    )

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
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF69B4)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_terminal),
                                contentDescription = "Logo",
                                modifier = Modifier.size(40.dp),
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Termux Ultra",
                            style = TextStyle(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = getVersionName(context),
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = Color.DarkGray
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
                                    android.net.Uri.parse("https://github.com/tig-kira")
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
                                    Text(
                                        text = "K",
                                        style = TextStyle(
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MiuixTheme.colorScheme.onSurface
                                        )
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
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = context.getString(R.string.open_source_projects),
                                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            )
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
                                    scope.launch {
                                        delay(1000)
                                        hasUpdate = false
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
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            InfoRow(
                                title = context.getString(R.string.version),
                                value = getVersionName(context)
                            )
                            Text(
                                text = context.getString(R.string.author),
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            )
                            Text(
                                text = context.getString(R.string.license),
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
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
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