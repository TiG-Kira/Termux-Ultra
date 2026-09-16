package com.termux.app.compose

import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.google.android.material.snackbar.Snackbar
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.termux.R
import com.termux.app.utils.SnackbarHelper

object AccessibilityGuard {

    private val SYSTEM_PACKAGE_PREFIXES = setOf(
        "com.google.android",
        "com.android",
        "com.miui",
        "com.mi",
        "com.xiaomi",
        "com.huawei",
        "com.hi",
        "com.oplus",
        "realme",
        "com.oneplus",
        "com.samsung.android",
        "com.sec.android",
        "com.sonyericsson",
        "com.nokia",
        "com.asus",
        "com.lenovo",
        "com.zte",
        "com.alcatel",
        "com.wileyfox",
        "android"
    )

    @Volatile
    private var lastPhysicalTouchMs = 0L

    fun onPhysicalTouch() {
        lastPhysicalTouchMs = System.currentTimeMillis()
    }

    fun wasPhysicalTouchRecent(windowMs: Long = 500L): Boolean {
        return System.currentTimeMillis() - lastPhysicalTouchMs <= windowMs
    }

    fun hasThirdPartyAccessibility(context: Context): Boolean {
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val services = am.getEnabledAccessibilityServiceList(1)
            for (service in services) {
                val resolveInfo = service.javaClass.getMethod("getResolveInfo").invoke(service)
                val serviceInfo = resolveInfo?.javaClass?.getMethod("getServiceInfo")?.invoke(resolveInfo)
                val pkg = serviceInfo?.javaClass?.getMethod("getPackageName")?.invoke(serviceInfo) as? String
                if (pkg != null) {
                    val isSystem = SYSTEM_PACKAGE_PREFIXES.any { prefix -> pkg.startsWith(prefix) }
                    if (!isSystem) return true
                }
            }
            return false
        } catch (_: Exception) {
            return false
        }
    }
}

@Composable
fun rememberThirdPartyBlocked(context: Context): Boolean {
    var blocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        blocked = AccessibilityGuard.hasThirdPartyAccessibility(context)
    }
    return blocked
}

fun Modifier.accessibilityGuard(blocked: Boolean): Modifier {
    return if (blocked) this.clearAndSetSemantics { } else this
}

fun Modifier.physicalTouchDetector(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        AccessibilityGuard.onPhysicalTouch()
    }
}

fun guardedOnClick(
    context: Context,
    thirdPartyBlocked: Boolean,
    onBlocked: () -> Unit = {
        SnackbarHelper.show(
            context,
            context.getString(R.string.accessibility_guard_blocked_toast),
            Snackbar.LENGTH_LONG
        )
    },
    onClick: () -> Unit
): () -> Unit {
    return {
        if (thirdPartyBlocked && !AccessibilityGuard.wasPhysicalTouchRecent()) {
            onBlocked()
        } else {
            onClick()
        }
    }
}
