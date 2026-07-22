package com.termux.app.ftp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.app.compose.KiTerminalTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

class FtpInfoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KiTerminalTheme {
                FtpInfoScreen()
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
    val prefs = remember { context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE) }
    
    var username by remember { mutableStateOf(prefs.getString("sftp_username", "termux") ?: "termux") }
    var password by remember { mutableStateOf(prefs.getString("sftp_password", "termux123") ?: "termux123") }
    var isEditing by remember { mutableStateOf(false) }
    
    val ipAddress = getLocalIpAddress(context)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = "FTP 连接信息",
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { (context as FtpInfoActivity).finish() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
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
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "地址: ftp://$ipAddress:8021",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    
                    if (isEditing) {
                        TextField(
                            label = { Text("用户名", color = MiuixTheme.colorScheme.onSurface) },
                            value = username,
                            onValueChange = { username = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MiuixTheme.colorScheme.surface,
                                unfocusedContainerColor = MiuixTheme.colorScheme.surface,
                                disabledContainerColor = MiuixTheme.colorScheme.surface,
                                focusedIndicatorColor = MiuixTheme.colorScheme.primary,
                                unfocusedIndicatorColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                disabledIndicatorColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                cursorColor = MiuixTheme.colorScheme.primary,
                                focusedLabelColor = MiuixTheme.colorScheme.primary,
                                unfocusedLabelColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                disabledLabelColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            keyboardActions = KeyboardActions()
                        )
                        TextField(
                            label = { Text("密码", color = MiuixTheme.colorScheme.onSurface) },
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MiuixTheme.colorScheme.surface,
                                unfocusedContainerColor = MiuixTheme.colorScheme.surface,
                                disabledContainerColor = MiuixTheme.colorScheme.surface,
                                focusedIndicatorColor = MiuixTheme.colorScheme.primary,
                                unfocusedIndicatorColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                disabledIndicatorColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                cursorColor = MiuixTheme.colorScheme.primary,
                                focusedLabelColor = MiuixTheme.colorScheme.primary,
                                unfocusedLabelColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                disabledLabelColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            keyboardActions = KeyboardActions()
                        )
                    } else {
                        Text(
                            text = "用户名: $username",
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        )
                        Text(
                            text = "密码: $password",
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
                                .putString("sftp_username", username)
                                .putString("sftp_password", password)
                                .apply()
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
                            username = prefs.getString("sftp_username", "termux") ?: "termux"
                            password = prefs.getString("sftp_password", "termux123") ?: "termux123"
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
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary
                        )
                    ) {
                        Text("修改", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Button(
                        onClick = { (context as FtpInfoActivity).finish() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text("关闭", fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

fun getLocalIpAddress(context: Context): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    return address.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "127.0.0.1"
}