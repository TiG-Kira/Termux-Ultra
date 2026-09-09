package com.termux.app.compose

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.auth.AuthPromptCallback
import androidx.biometric.auth.startClass2BiometricOrCredentialAuthentication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.termux.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AuthorizationMask() {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            TopAppBar(title = "授权")
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = "敏感操作需要得到你的授权来继续",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
fun DisableWarningMask() {
    val context = LocalContext.current
    val disableWarningState by RiskConfirmManager.disableWarningState.collectAsState()
    var checkboxChecked by remember { mutableStateOf(false) }
    var isAuthenticating by remember { mutableStateOf(false) }

    val targetLevel = disableWarningState.targetLevel
    val isOff = targetLevel == RiskConfirmManager.ProtectionLevel.OFF
    val summaryRes = if (isOff) R.string.risk_command_disable_off_message
        else R.string.risk_command_disable_warn_message
    val checkboxRes = if (isOff) R.string.risk_command_disable_off_checkbox
        else R.string.risk_command_disable_warn_checkbox

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            TopAppBar(title = "授权")
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = "敏感操作需要得到你的授权来继续",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )

            OverlayDialog(
                show = disableWarningState.show,
                onDismissRequest = {
                    RiskConfirmManager.hideDisableWarning()
                },
                title = "调整增强防护模式？",
                summary = stringResource(summaryRes),
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        CheckboxPreference(
                            title = stringResource(checkboxRes),
                            checked = checkboxChecked,
                            onCheckedChange = { checkboxChecked = it },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Button(
                                onClick = {
                                    RiskConfirmManager.hideDisableWarning()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    color = Color.Transparent
                                )
                            ) {
                                Text(
                                    text = "取消",
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Button(
                                onClick = {
                                    isAuthenticating = true
                                    val activity = context as? FragmentActivity
                                    if (activity != null && hasDisableBiometricAuth(activity)) {
                                        launchDisableBiometricAuth(activity) { success ->
                                            isAuthenticating = false
                                            if (success) {
                                                RiskConfirmManager.confirmDisable(context)
                                            }
                                        }
                                    } else {
                                        RiskConfirmManager.confirmDisable(context)
                                    }
                                },
                                enabled = checkboxChecked && !isAuthenticating,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    color = if (checkboxChecked && !isAuthenticating) Color(0xFFD32F2F)
                                        else Color(0xFFBDBDBD)
                                )
                            ) {
                                Text(
                                    text = "确认调整",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}

private fun hasDisableBiometricAuth(activity: FragmentActivity): Boolean {
    val biometricManager = BiometricManager.from(activity)
    val canAuthenticate = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )
    return canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS
}

private fun launchDisableBiometricAuth(
    activity: FragmentActivity,
    onResult: (Boolean) -> Unit
) {
    if (!hasDisableBiometricAuth(activity)) {
        onResult(true)
        return
    }

    val title = "请验证您的身份以继续"
    val subtitle = "确认调整"

    activity.startClass2BiometricOrCredentialAuthentication(
        title = title,
        subtitle = subtitle,
        confirmationRequired = false,
        callback = object : AuthPromptCallback() {
            override fun onAuthenticationSucceeded(
                activity: FragmentActivity?,
                result: BiometricPrompt.AuthenticationResult
            ) {
                onResult(true)
            }

            override fun onAuthenticationError(
                activity: FragmentActivity?,
                errorCode: Int,
                errString: CharSequence
            ) {
                onResult(false)
            }

            override fun onAuthenticationFailed(activity: FragmentActivity?) {}
        }
    )
}
