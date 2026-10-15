package com.termux.app.ftp

import com.termux.app.compose.pagePaddingWithoutTop
import com.termux.app.compose.topBarClearance
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.compose.rememberGlassPageBackdrop
import com.termux.app.compose.KiTerminalTheme
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

class FtpInfoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与其他独立玻璃页一致：状态栏交给 Compose 画布，玻璃顶栏才能盖到状态栏后面，否则异色。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val navDispatcher = com.termux.app.compose.NavigationHelper.createDispatcher()
            val navDispatcherOwner = com.termux.app.compose.NavigationHelper.createOwner(navDispatcher)
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                KiTerminalTheme {
                    FtpInfoScreen()
                }
            }
        }
    }
    
    companion object {
        fun start(context: Context) {
            val intent = Intent(context, FtpInfoActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

@Composable
fun FtpInfoScreen() {
    val context = LocalContext.current
    // 本页在 MainScreen 取景层之外，自建一层供玻璃顶栏折射页面内容
    val glassPage = rememberGlassPageBackdrop()
    val prefs = remember { context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE) }
    
    var username by remember { mutableStateOf(prefs.getString("sftp_username", "termux") ?: "termux") }
    var password by remember { mutableStateOf(FtpCredentialStore.getPassword(context)) }
    var port by remember { mutableStateOf(prefs.getInt("sftp_port", 8021)) }
    var bindAddress by remember { mutableStateOf(prefs.getString("sftp_bind_address", "127.0.0.1") ?: "127.0.0.1") }
    var isEditing by remember { mutableStateOf(false) }

    val isLoopback = bindAddress == "127.0.0.1" || bindAddress.startsWith("127.") || bindAddress == "localhost"
    
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = "FTP 连接信息",
                backdrop = glassPage.backdrop,
                navigationIcon = {
                    GlassIconButton(onClick = { (context as FtpInfoActivity).finish() }) {
                        Icon(
                            imageVector = MiuixGlassIcons.ChevronBackward,
                            contentDescription = "返回",
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
                .then(glassPage.contentModifier)
                .fillMaxSize()
                .padding(pagePaddingWithoutTop(padding))
                // insets 已归零，底部导航栏让位由页面自己负责。
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 这页内容固定不滚动，让位落在首个 item 上；滚动页才把它折进 contentPadding。
            Spacer(Modifier.height(topBarClearance(padding)))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "地址: ftp://$bindAddress:$port",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    )

                    if (!isLoopback) {
                        Text(
                            text = "⚠ 当前监听地址非回环，FTP 服务将对所在网络开放，请确保已设置强密码。",
                            style = TextStyle(
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.error
                            )
                        )
                    }

                    if (isEditing) {
                        TextField(
                            label = "监听地址",
                            value = bindAddress,
                            onValueChange = { bindAddress = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            keyboardActions = KeyboardActions()
                        )
                        TextField(
                            label = "端口",
                            value = port.toString(),
                            onValueChange = {
                                it.toIntOrNull()?.let { p ->
                                    if (p in 1024..65535) port = p
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            keyboardActions = KeyboardActions()
                        )
                        TextField(
                            label = "用户名",
                            value = username,
                            onValueChange = { username = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            keyboardActions = KeyboardActions()
                        )
                        TextField(
                            label = "密码",
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            keyboardActions = KeyboardActions()
                        )
                        Text(
                            text = if (FtpServiceManager.isPasswordStrong(password)) "密码强度: 合格" else "密码强度: 偏弱（需 ≥8 位且含 3 类以上字符）",
                            style = TextStyle(
                                fontSize = 13.sp,
                                color = if (FtpServiceManager.isPasswordStrong(password)) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error
                            )
                        )
                    } else {
                        Text(
                            text = "端口: $port",
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        )
                        Text(
                            text = "用户名: $username",
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        )
                        Text(
                            text = "密码: ${if (password.isEmpty()) "（未设置）" else "•".repeat(password.length.coerceAtMost(16))}",
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isEditing) {
                    Button(
                        onClick = {
                            prefs.edit()
                                .putInt("sftp_port", port)
                                .putString("sftp_username", username)
                                .putString("sftp_bind_address", bindAddress.ifEmpty { "127.0.0.1" })
                                .apply()
                            FtpCredentialStore.setPassword(context, password)
                            isEditing = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary
                        )
                    ) {
                        Text("保存", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Button(
                        onClick = {
                            isEditing = false
                            port = prefs.getInt("sftp_port", 8021)
                            username = prefs.getString("sftp_username", "termux") ?: "termux"
                            password = FtpCredentialStore.getPassword(context)
                            bindAddress = prefs.getString("sftp_bind_address", "127.0.0.1") ?: "127.0.0.1"
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text("取消", fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                    }
                } else {
                    Button(
                        onClick = { isEditing = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary
                        )
                    ) {
                        Text("修改", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}
