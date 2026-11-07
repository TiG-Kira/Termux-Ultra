package com.termux.app.rustdesk

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.termux.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/** 会话页弹窗状态。核心的 msgbox 事件在这里按 type 分流，不再退化成一行状态文字。 */
internal sealed interface SessionDialog {
    data class Message(val payload: RustDeskCore.EventPayload) : SessionDialog
    data class Password(val payload: RustDeskCore.EventPayload) : SessionDialog
    data class TwoFactor(val payload: RustDeskCore.EventPayload) : SessionDialog
    data class OsLogin(val payload: RustDeskCore.EventPayload, val withPassword: Boolean) : SessionDialog
}

/** 凭据弹窗期间核心阻塞等待输入，误关会让会话卡死，因此不允许返回键或外部点击关闭。 */
internal val SessionDialog.isCredential: Boolean get() = this !is SessionDialog.Message

internal fun dialogFor(payload: RustDeskCore.EventPayload): SessionDialog? = when (payload.type) {
    "input-password", "re-input-password", "custom-os-password" -> SessionDialog.Password(payload)
    "input-2fa" -> SessionDialog.TwoFactor(payload)
    "terminal-admin-login" -> SessionDialog.OsLogin(payload, false)
    "terminal-admin-login-password" -> SessionDialog.OsLogin(payload, true)
    "success", "restarting", "restarting-show", "wait-uac",
    "wait-remote-accept", "toast", "cancel_msgbox" -> null
    else -> SessionDialog.Message(payload)
}

/** [onDefer] 只收起凭据弹窗，由会话页保留重开入口；核心仍在等这次输入。 */
@Composable
internal fun RustDeskSessionDialogHost(
    dialog: SessionDialog?,
    sessionId: String,
    onDismiss: () -> Unit,
    onDefer: () -> Unit,
) {
    if (dialog == null) return
    val context = LocalContext.current
    val credential = dialog.isCredential
    Dialog(
        onDismissRequest = { if (!credential) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !credential,
            dismissOnClickOutside = !credential,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1E1E20))
                .padding(20.dp)
        ) {
            when (dialog) {
                is SessionDialog.Message -> MessageContent(dialog.payload, context, sessionId, onDismiss)
                is SessionDialog.Password -> PasswordContent(dialog.payload, sessionId, onDismiss, onDefer)
                is SessionDialog.TwoFactor -> TwoFactorContent(sessionId, onDismiss, onDefer)
                is SessionDialog.OsLogin -> OsLoginContent(dialog.withPassword, sessionId, onDismiss, onDefer)
            }
        }
    }
}

@Composable
private fun DialogTitle(text: String) {
    Text(text = text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun DialogBody(text: String) {
    if (text.isNotEmpty()) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun DialogActions(
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        extra?.invoke()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                text = dismissLabel,
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = stringResource(R.string.rustdesk_session_submit),
                onClick = onSubmit,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

@Composable
private fun MessageContent(
    payload: RustDeskCore.EventPayload,
    context: Context,
    sessionId: String,
    onDismiss: () -> Unit,
) {
    val defaultTitle = stringResource(R.string.rustdesk_session_error_default_title)
    val fallbackText = stringResource(R.string.rustdesk_session_msgbox_fallback, payload.type)
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DialogTitle(payload.title.ifBlank { defaultTitle })
        DialogBody(payload.text.ifBlank { fallbackText })
        if (payload.link.startsWith("http")) {
            TextButton(
                text = stringResource(R.string.rustdesk_session_link),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(payload.link))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (payload.hasRetry) {
            TextButton(
                text = stringResource(R.string.rustdesk_session_retry),
                onClick = {
                    RustDeskCore.nativeSessionReconnect(sessionId, false)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
        TextButton(
            text = stringResource(R.string.rustdesk_session_ok),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    }
}

@Composable
private fun PasswordContent(
    payload: RustDeskCore.EventPayload,
    sessionId: String,
    onDismiss: () -> Unit,
    onDefer: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(false) }
    val defaultTitle = stringResource(R.string.rustdesk_session_password_title)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DialogTitle(payload.title.ifBlank { defaultTitle })
        DialogBody(payload.text)
        TextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.rustdesk_session_password_hint),
            visualTransformation = PasswordVisualTransformation()
        )
        DialogActions(
            onSubmit = {
                RustDeskCore.nativeSessionLogin(sessionId, "", "", password, rememberMe)
                onDismiss()
            },
            onDismiss = onDefer,
            dismissLabel = stringResource(R.string.rustdesk_session_defer),
            extra = {
                RememberToggle(rememberMe) { rememberMe = it }
            }
        )
    }
}

@Composable
private fun TwoFactorContent(sessionId: String, onDismiss: () -> Unit, onDefer: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var trust by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DialogTitle(stringResource(R.string.rustdesk_session_2fa_title))
        TextField(
            value = code,
            onValueChange = { code = it },
            label = stringResource(R.string.rustdesk_session_2fa_hint)
        )
        DialogActions(
            onSubmit = {
                RustDeskCore.nativeSessionSend2fa(sessionId, code, trust)
                onDismiss()
            },
            onDismiss = onDefer,
            dismissLabel = stringResource(R.string.rustdesk_session_defer),
            extra = { RememberToggle(trust) { trust = it } }
        )
    }
}

@Composable
private fun OsLoginContent(
    withPassword: Boolean,
    sessionId: String,
    onDismiss: () -> Unit,
    onDefer: () -> Unit,
) {
    var user by remember { mutableStateOf("") }
    var osPassword by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DialogTitle(stringResource(R.string.rustdesk_session_os_login_title))
        DialogBody(stringResource(R.string.rustdesk_session_os_login_summary))
        TextField(
            value = user,
            onValueChange = { user = it },
            label = stringResource(R.string.rustdesk_session_os_user)
        )
        if (withPassword) {
            TextField(
                value = osPassword,
                onValueChange = { osPassword = it },
                label = stringResource(R.string.rustdesk_session_os_password),
                visualTransformation = PasswordVisualTransformation()
            )
        }
        DialogActions(
            onSubmit = {
                RustDeskCore.nativeSessionLogin(sessionId, user, osPassword, "", rememberMe)
                onDismiss()
            },
            onDismiss = onDefer,
            dismissLabel = stringResource(R.string.rustdesk_session_defer),
            extra = { RememberToggle(rememberMe) { rememberMe = it } }
        )
    }
}

@Composable
private fun RememberToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    TextButton(
        text = stringResource(
            if (checked) R.string.rustdesk_session_remember_on
            else R.string.rustdesk_session_remember_off
        ),
        onClick = { onChange(!checked) },
        modifier = Modifier.fillMaxWidth()
    )
}
