package com.termux.app.compose

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.termux.R

object NotificationHelper {

    private const val CHANNEL_ID = "termux_backup_channel"
    private const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.backup_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.backup_notification_channel_desc)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Show a progress notification. If [max] <= 0 the progress bar is shown as indeterminate.
     */
    fun showProgressNotification(context: Context, title: String, progress: Int, max: Int, message: String = "", cancelIntent: PendingIntent? = null) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val indeterminate = max <= 0
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (message.isNotEmpty()) message else context.getString(R.string.initializing))
            .setSmallIcon(R.drawable.ic_backup)
            .setProgress(if (indeterminate) 0 else max, if (indeterminate) 0 else progress, indeterminate)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (cancelIntent != null) {
            builder.addAction(
                R.drawable.ic_close,
                context.getString(R.string.cancel_action),
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
