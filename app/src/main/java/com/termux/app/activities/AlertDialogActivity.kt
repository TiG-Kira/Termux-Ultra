package com.termux.app.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.auth.AuthPromptCallback
import androidx.biometric.auth.startClass2BiometricOrCredentialAuthentication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.R
import com.termux.app.TermuxService
import com.termux.app.compose.NavigationHelper
import com.termux.app.compose.RiskConfirmManager
import com.termux.app.compose.accessibilityGuard
import com.termux.app.compose.guardedOnClick
import com.termux.app.compose.physicalTouchDetector
import com.termux.app.compose.rememberThirdPartyBlocked
import com.termux.app.compose.KiTerminalTheme
import com.termux.shared.termux.TermuxConstants
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

class AlertDialogActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dialogType = intent.getStringExtra(EXTRA_DIALOG_TYPE) ?: TYPE_STOP_CONFIRM

        when (dialogType) {
            TYPE_STOP_CONFIRM -> {
                val isQuitApp = intent.getBooleanExtra(EXTRA_IS_QUIT_APP, false)
                val qemuCount = intent.getIntExtra(EXTRA_QEMU_COUNT, 0)
                val containerRunning = intent.getBooleanExtra(EXTRA_CONTAINER_RUNNING, false)

                setContent {
                    ProvideNavDispatcher {
                        KiTerminalTheme {
                            StopConfirmDialogContent(
                                isQuitApp = isQuitApp,
                                qemuCount = qemuCount,
                                containerRunning = containerRunning,
                                onConfirm = {
                                    if (isQuitApp) {
                                        triggerForceQuit(this@AlertDialogActivity)
                                    } else {
                                        triggerForceStop(this@AlertDialogActivity)
                                    }
                                    finish()
                                },
                                onDismiss = {
                                    RiskConfirmManager.hideDisableWarning()
                                    finish()
                                }
                            )
                        }
                    }
                }
            }

            TYPE_DISABLE_WARNING -> {
                val targetLevelOrdinal = intent.getIntExtra(EXTRA_TARGET_LEVEL, 0)
                val targetLevel = RiskConfirmManager.ProtectionLevel.entries.getOrElse(targetLevelOrdinal) {
                    RiskConfirmManager.ProtectionLevel.OFF
                }

                setContent {
                    ProvideNavDispatcher {
                        KiTerminalTheme {
                            DisableWarningDialogContent(
                                targetLevel = targetLevel,
                                onConfirm = {
                                    RiskConfirmManager.setProtectionLevel(
                                        this@AlertDialogActivity,
                                        targetLevel
                                    )
                                    finish()
                                },
                                onDismiss = {
                                    RiskConfirmManager.hideDisableWarning()
                                    finish()
                                }
                            )
                        }
                    }
                }
            }

            else -> finish()
        }
    }

    companion object {
        const val EXTRA_DIALOG_TYPE = "extra_dialog_type"
        const val EXTRA_IS_QUIT_APP = "extra_is_quit_app"
        const val EXTRA_QEMU_COUNT = "extra_qemu_count"
        const val EXTRA_CONTAINER_RUNNING = "extra_container_running"
        const val EXTRA_TARGET_LEVEL = "extra_target_level"

        const val TYPE_STOP_CONFIRM = "stop_confirm"
        const val TYPE_DISABLE_WARNING = "disable_warning"

        fun startStopConfirm(
            context: Context,
            isQuitApp: Boolean,
            qemuCount: Int,
            containerRunning: Boolean
        ) {
            val intent = Intent(context, AlertDialogActivity::class.java).apply {
                putExtra(EXTRA_DIALOG_TYPE, TYPE_STOP_CONFIRM)
                putExtra(EXTRA_IS_QUIT_APP, isQuitApp)
                putExtra(EXTRA_QEMU_COUNT, qemuCount)
                putExtra(EXTRA_CONTAINER_RUNNING, containerRunning)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun startDisableWarning(
            context: Context,
            targetLevel: RiskConfirmManager.ProtectionLevel
        ) {
            val intent = Intent(context, AlertDialogActivity::class.java).apply {
                putExtra(EXTRA_DIALOG_TYPE, TYPE_DISABLE_WARNING)
                putExtra(EXTRA_TARGET_LEVEL, targetLevel.ordinal)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        private fun triggerForceStop(context: Context) {
            try {
                val intent = Intent(context, TermuxService::class.java)
                intent.action = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE_FORCE
                context.startService(intent)
            } catch (_: Exception) {}
        }

        private fun triggerForceQuit(context: Context) {
            try {
                val intent = Intent(context, TermuxService::class.java)
                intent.action = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_QUIT_APP_FORCE
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }
}

@Composable
private fun ProvideNavDispatcher(content: @Composable () -> Unit) {
    val navDispatcher = remember { NavigationHelper.createDispatcher() }
    val navDispatcherOwner = remember { NavigationHelper.createOwner(navDispatcher) }
    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides navDispatcherOwner
    ) {
        content()
    }
}

@Composable
private fun StopConfirmDialogContent(
    isQuitApp: Boolean,
    qemuCount: Int,
    containerRunning: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val thirdPartyBlocked = rememberThirdPartyBlocked(context)
    var showDialog by remember { mutableStateOf(true) }

    WindowDialog(
        show = showDialog,
        onDismissRequest = {
            showDialog = false
            onDismiss()
        },
        title = if (isQuitApp) "关闭程序" else "警告",
        summary = buildStopConfirmSummary(qemuCount, containerRunning),
        content = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .physicalTouchDetector()
                    .accessibilityGuard(thirdPartyBlocked),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                TextButton(
                    text = "否",
                    onClick = guardedOnClick(context, thirdPartyBlocked) {
                        showDialog = false
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "是",
                    onClick = guardedOnClick(context, thirdPartyBlocked) {
                        showDialog = false
                        onConfirm()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

@Composable
private fun DisableWarningDialogContent(
    targetLevel: RiskConfirmManager.ProtectionLevel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val thirdPartyBlocked = rememberThirdPartyBlocked(context)
    var showDialog by remember { mutableStateOf(true) }
    var checkboxChecked by remember { mutableStateOf(false) }
    var isAuthenticating by remember { mutableStateOf(false) }

    val isOff = targetLevel == RiskConfirmManager.ProtectionLevel.OFF
    val summaryRes = if (isOff) R.string.risk_command_disable_off_message
        else R.string.risk_command_disable_warn_message
    val checkboxRes = if (isOff) R.string.risk_command_disable_off_checkbox
        else R.string.risk_command_disable_warn_checkbox

    WindowDialog(
        show = showDialog,
        onDismissRequest = {
            showDialog = false
            onDismiss()
        },
        title = stringResource(R.string.risk_command_disable_title),
        summary = stringResource(summaryRes),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .physicalTouchDetector()
                    .accessibilityGuard(thirdPartyBlocked)
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
                        onClick = guardedOnClick(context, thirdPartyBlocked) {
                            showDialog = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = Color.Transparent
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Button(
                        onClick = guardedOnClick(context, thirdPartyBlocked) {
                            isAuthenticating = true
                            val activity = context as? FragmentActivity
                            if (activity != null && hasBiometricAuth(activity)) {
                                launchBiometricAuth(activity) { success ->
                                    isAuthenticating = false
                                    if (success) {
                                        showDialog = false
                                        onConfirm()
                                    }
                                }
                            } else {
                                showDialog = false
                                onConfirm()
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
                            text = stringResource(R.string.risk_command_disable_confirm),
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

private fun buildStopConfirmSummary(qemuCount: Int, containerRunning: Boolean): String {
    return buildString {
        append("如果这么做，您可能丢失虚拟机/容器内的数据，继续吗？")
        if (qemuCount > 0 || containerRunning) {
            append("\n\n当前检测到：")
            if (qemuCount > 0) append("\n· 运行中的虚拟机：").append(qemuCount).append(" 台")
            if (containerRunning) append("\n· proot 容器正在运行")
        }
    }
}

private fun hasBiometricAuth(activity: FragmentActivity): Boolean {
    val biometricManager = BiometricManager.from(activity)
    val canAuthenticate = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )
    return canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS
}

private fun launchBiometricAuth(
    activity: FragmentActivity,
    onResult: (Boolean) -> Unit
) {
    if (!hasBiometricAuth(activity)) {
        onResult(true)
        return
    }

    val title = activity.getString(R.string.risk_command_biometric_prompt)
    val subtitle = activity.getString(R.string.risk_command_disable_confirm)

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
