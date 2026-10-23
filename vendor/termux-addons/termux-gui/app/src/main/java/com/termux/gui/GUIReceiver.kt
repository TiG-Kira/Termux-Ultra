package com.termux.gui

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receives broadcasts from clients with the address of the main and event socket, adds the requests to the queue and starts the foreground service if necessary.
 */
class GUIReceiver : BroadcastReceiver() {
    companion object {
        private val TAG: String? = GUIReceiver::class.java.canonicalName
    }


    override fun onReceive(context: Context, intent: Intent) {
        try {
            // 关键路径日志不走 Logger：默认 loglevel=0 会把它吞掉，排障时看不到任何痕迹
            Log.i(TAG, "received broadcast: mainSocket=" + intent.getStringExtra("mainSocket") +
                    " eventSocket=" + intent.getStringExtra("eventSocket"))
            GUIService.requests.add(GUIService.ConnectionRequest(intent.getStringExtra("mainSocket"),
                    intent.getStringExtra("eventSocket")))
            // 必须用 startForegroundService：GUIService 是前台服务，Android 8+ 从广播里
            // startService 属于后台启动服务，会被系统拒绝（服务起不来 → 客户端永久等待）
            val service = Intent(context, GUIService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "could not start service due to foreground service restriction")
            }
            Log.e(TAG, "failed to start GUIService", e)
        }
    }
}