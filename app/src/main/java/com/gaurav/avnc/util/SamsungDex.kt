/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Utilities related to Samsung DeX
 */
object SamsungDex {
    private const val TAG = "DeX Support"

    /**
     * Returns true, if DeX mode is enabled.
     */


    /**
     * Enables/disables meta-key event capturing.
     */
    fun setMetaKeyCapture(activity: Activity, isEnabled: Boolean) {
        val managerClass = runCatching { Class.forName("com.samsung.android.view.SemWindowManager") }
                                   .onSuccess { Log.d(TAG, "Samsung device detected, setting meta key capture to: $isEnabled") }
                                   .onFailure { Log.d(TAG, "Samsung device not detected, skipping meta key capture") }
                                   .getOrNull() ?: return

        runCatching {
            val instanceMethod = managerClass.getMethod("getInstance")
            val manager = instanceMethod.invoke(null)

            val requestMethod = managerClass.getDeclaredMethod("requestMetaKeyEvent",
                                                               ComponentName::class.java,
                                                               Boolean::class.java)
            requestMethod.invoke(manager, activity.componentName, isEnabled)
        }.onFailure { Log.e(TAG, "Meta key capture error", it) }
    }
}