package com.termux.app.compose

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.termux.R

object NotificationHelper {

    private const val CHANNEL_ID = "termux_backup_channel"
    private const val CHANNEL_NAME = "备份进度"
    private const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "显示备份和恢复的进度"
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showProgressNotification(context: Context, title: String, progress: Int, max: Int, message: String = "", cancelIntent: PendingIntent? = null) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (message.isNotEmpty()) message else "进度: $progress/$max")
            .setSmallIcon(R.drawable.ic_backup)
            .setProgress(max, progress, false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        
        if (cancelIntent != null) {
            builder.addAction(
                R.drawable.ic_close,
                "取消",
                cancelIntent
            )
        }
        
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun showCompleteNotification(context: Context, title: String, message: String, success: Boolean) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(if (success) R.drawable.ic_backup else R.drawable.ic_warning)
            .setAutoCancel(true)
            .setOngoing(false)
        
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancelNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
