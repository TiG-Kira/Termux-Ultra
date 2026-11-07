package com.termux.app.rustdesk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.app.compose.OverlayDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** RustDesk 标签页顶部的账户卡片：未登录给两个入口，已登录显示账号与退出。 */
@Composable
internal fun RustDeskAccountCard(
    state: RustDeskAccount.State,
    onSignIn: () -> Unit,
    onQuickSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_rustdesk),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.rustdesk_account_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (state.signedIn) {
                            stringResource(
                                R.string.rustdesk_account_signed_in,
                                state.userName ?: stringResource(R.string.rustdesk_account_user_unknown)
                            )
                        } else {
                            stringResource(R.string.rustdesk_account_signed_out)
                        },
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        lineHeight = 17.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.signedIn) {
                    TextButton(
                        text = stringResource(R.string.rustdesk_account_sign_out),
                        onClick = onSignOut,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    TextButton(
                        text = stringResource(R.string.rustdesk_account_sign_in),
                        onClick = onSignIn,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = stringResource(R.string.rustdesk_account_quick_sign_in),
                        onClick = onQuickSignIn,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** 选择 RustDesk 账户授权方式；op 原样交给核心，服务端决定具体登录页。 */
@Composable
internal fun RustDeskLoginDialog(
    onStart: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var customOp by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }
    var show by remember { mutableStateOf(true) }

    OverlayDialog(
        show = show,
        onDismissRequest = {
            show = false
            onDismiss()
        },
        title = stringResource(R.string.rustdesk_account_login_title),
        summary = stringResource(R.string.rustdesk_account_login_summary),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.rustdesk_account_provider),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                RustDeskAccount.providers.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (op, label) ->
                            TextButton(
                                text = label,
                                onClick = {
                                    show = false
                                    onStart(op, rememberMe)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                TextField(
                    value = customOp,
                    onValueChange = { customOp = it },
                    label = stringResource(R.string.rustdesk_account_custom_op)
                )

                SwitchPreference(
                    title = stringResource(R.string.rustdesk_account_remember),
                    summary = stringResource(R.string.rustdesk_account_remember_desc),
                    checked = rememberMe,
                    onCheckedChange = { rememberMe = it }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = {
                            show = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = stringResource(R.string.rustdesk_account_custom_op_start),
                        onClick = {
                            if (customOp.isBlank()) return@TextButton
                            show = false
                            onStart(customOp, rememberMe)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

/** 轮询 `nativeAccountAuthResult()` 的结果展示；授权 URL 由核心生成，UI 只负责打开。 */
@Composable
internal fun RustDeskAuthProgressDialog(
    result: RustDeskCore.AccountAuthResult?,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(true) }
    val success = result?.stateMsg == "Login account auth" || result?.userName != null
    val stateText = when (result?.stateMsg) {
        "Requesting account auth" -> stringResource(R.string.rustdesk_account_state_requesting)
        "Waiting account auth" -> stringResource(R.string.rustdesk_account_state_waiting)
        "Login account auth" -> stringResource(R.string.rustdesk_account_state_success)
        else -> result?.stateMsg?.takeIf { it.isNotEmpty() }
            ?: stringResource(R.string.rustdesk_account_state_requesting)
    }

    fun dismiss() {
        show = false
        onClose()
    }

    OverlayDialog(
        show = show,
        onDismissRequest = { dismiss() },
        title = stringResource(R.string.rustdesk_account_progress_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = stateText, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                result?.failedMsg?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        fontSize = 13.sp,
                        color = Color(0xFFE04F5F),
                        lineHeight = 18.sp
                    )
                }
                result?.userName?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = stringResource(R.string.rustdesk_account_signed_in, it),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }

                result?.url?.let { url ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = stringResource(R.string.rustdesk_account_open_url),
                            onClick = { openAuthUrl(context, url) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                        TextButton(
                            text = stringResource(R.string.rustdesk_account_copy_url),
                            onClick = { copyAuthUrl(context, url) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!success) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = {
                                onCancel()
                                dismiss()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    TextButton(
                        text = stringResource(R.string.rustdesk_account_done),
                        onClick = { dismiss() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

private fun openAuthUrl(context: Context, url: String) {
    runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private fun copyAuthUrl(context: Context, url: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("RustDesk", url))
    }
}
