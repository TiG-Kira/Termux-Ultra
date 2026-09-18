package com.termux.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.PowerManager
import com.termux.app.compose.IntegratedTools
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Termux:Boot integration receiver.
 *
 * Registered in the manifest with `android:enabled="false"` and toggled at runtime by the
 * "Termux:Boot" integrated tool switch in Settings (see [IntegratedTools]). On
 * `BOOT_COMPLETED` it runs the executable scripts placed in `~/.termux/boot/` (sorted by
 * name) as new Termux sessions.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val BOOT_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termux/boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED != intent.action) return
        // The component is only enabled when the toggle is on, but double-check defensively.
        if (!IntegratedTools.isEnabled(context, IntegratedTools.Tool.TERMUX_BOOT)) return

        val bootDir = File(BOOT_DIR)
        val scripts = bootDir.listFiles { file ->
            file.isFile && !file.isHidden && file.canExecute()
        }?.sortedBy { it.name } ?: emptyList()
        if (scripts.isEmpty()) return

        // Keep the device awake while we bind to the service and enqueue sessions.
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Termux:BootReceiver")
        wakeLock.acquire(30_000L)

        // Make sure the service is alive.
        val serviceIntent = Intent(context, TermuxService::class.java)
        context.startService(serviceIntent)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val termuxService = (service as? TermuxService.LocalBinder)?.service ?: return
                    for (script in scripts) {
                        termuxService.createTermuxSession(
                            script.absolutePath,
                            null,
                            null,
                            null,
                            false,
                            script.name
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try { context.unbindService(this) } catch (_: Exception) {}
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }

        try {
            context.bindService(serviceIntent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            e.printStackTrace()
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}
