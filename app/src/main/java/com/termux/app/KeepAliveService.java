package com.termux.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;

public class KeepAliveService extends Service {

    private static final String LOG_TAG = "KeepAliveService";
    private static final long ALARM_INTERVAL = 60 * 1000;
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(LOG_TAG, "KeepAliveService created");
        setupNotificationChannel();
        Notification notification = buildNotification();
        if (notification != null) {
            startForeground(NOTIFICATION_ID, notification);
        }
        scheduleAlarm();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(LOG_TAG, "KeepAliveService started");
        ensureTermuxServiceRunning();
        return START_STICKY;
    }

    private void ensureTermuxServiceRunning() {
        try {
            Intent serviceIntent = new Intent(this, TermuxService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to restart TermuxService", e);
        }
    }

    private void scheduleAlarm() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, KeepAliveReceiver.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, flags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ALARM_INTERVAL,
                    pendingIntent
            );
        } else {
            alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ALARM_INTERVAL,
                    ALARM_INTERVAL,
                    pendingIntent
            );
        }
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) return;

        NotificationChannel channel = notificationManager.getNotificationChannel(TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID);
        if (channel == null) {
            NotificationUtils.setupNotificationChannel(this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
                    TermuxConstants.TERMUX_APP_NAME, NotificationManager.IMPORTANCE_LOW);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(
                this,
                TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
                Notification.PRIORITY_LOW,
                TermuxConstants.TERMUX_APP_NAME,
                "Keep Alive",
                null,
                null,
                null,
                NotificationUtils.NOTIFICATION_MODE_SILENT
        );

        if (builder != null) {
            builder.setSmallIcon(com.termux.R.drawable.ic_service_notification);
            builder.setOngoing(true);
            builder.setShowWhen(false);
            return builder.build();
        }

        return buildFallbackNotification();
    }

    private Notification buildFallbackNotification() {
        try {
            String channelId = TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID;

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, channelId);
            } else {
                builder = new Notification.Builder(this);
                builder.setPriority(Notification.PRIORITY_LOW);
            }

            builder.setSmallIcon(com.termux.R.drawable.ic_service_notification);
            builder.setContentTitle(TermuxConstants.TERMUX_APP_NAME);
            builder.setContentText("Keep Alive");
            builder.setOngoing(true);
            builder.setShowWhen(false);

            return builder.build();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to build fallback notification", e);
            return null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(LOG_TAG, "KeepAliveService destroyed, restarting...");
        Intent intent = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
