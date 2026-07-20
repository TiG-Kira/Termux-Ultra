package com.termux.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.termux.app.TermuxService;
import com.termux.app.utils.DomesticOSDetector;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

public class MemoryBroadcastReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "MemoryBroadcastReceiver";
    private static final String BUNDLE_KEY_COMMON = "common";
    private static final String BUNDLE_KEY_EXTRA = "extra";
    private static final String KEY_NOTIFY_TYPE = "notifyType";
    private static final String KEY_NOTIFY_ID = "notifyId";
    private static final String KEY_CALLBACK = "callback";
    private static final int TRANSACTION_EXCEPTION_REPLY = IBinder.FIRST_CALL_TRANSACTION;

    public static final String ACTION_MEMORY_TRIM = "itgsa.intent.action.TRIM";
    public static final String ACTION_MEMORY_KILL = "itgsa.intent.action.KILL";

    public static final String ACTION_XIAOMI_MEMORY_TRIM = "itgsa.intent.action.TRIM";
    public static final String ACTION_XIAOMI_MEMORY_KILL = "itgsa.intent.action.KILL";

    public static final String ACTION_OPPO_MEMORY_TRIM = "itgsa.intent.action.TRIM";
    public static final String ACTION_OPPO_MEMORY_KILL = "itgsa.intent.action.KILL";

    public static final String ACTION_VIVO_MEMORY_TRIM = "itgsa.intent.action.TRIM";
    public static final String ACTION_VIVO_MEMORY_KILL = "itgsa.intent.action.KILL";

    public static final String ACTION_HONOR_MEMORY_TRIM = "itgsa.intent.action.TRIM";
    public static final String ACTION_HONOR_MEMORY_KILL = "itgsa.intent.action.KILL";

    private static boolean sMemoryWarningReceived = false;
    private static boolean sMemoryKillReceived = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DomesticOSDetector.isDomesticOS()) {
            return;
        }

        String action = intent.getAction();
        if (action == null) {
            return;
        }

        Log.d(LOG_TAG, "Received broadcast action: " + action);

        int notifyType = 0;
        int notifyId = 0;
        IBinder callbackBinder = null;

        Bundle data = intent.getExtras();
        if (data != null) {
            Bundle common = data.getBundle(BUNDLE_KEY_COMMON);
            if (common != null) {
                notifyType = common.getInt(KEY_NOTIFY_TYPE, 0);
                notifyId = common.getInt(KEY_NOTIFY_ID, 0);
                callbackBinder = common.getBinder(KEY_CALLBACK);
            }
        }

        if (ACTION_MEMORY_TRIM.equals(action)) {
            handleMemoryWarning(context, notifyType, notifyId, callbackBinder);
        } else if (ACTION_MEMORY_KILL.equals(action)) {
            handleMemoryKill(context, notifyType, notifyId, callbackBinder);
        }
    }

    private void handleMemoryWarning(Context context, int notifyType, int notifyId, IBinder callback) {
        Log.d(LOG_TAG, "Memory warning received, notifyType: " + notifyType + ", notifyId: " + notifyId);
        sMemoryWarningReceived = true;

        Intent serviceIntent = new Intent(context, TermuxService.class);
        serviceIntent.setAction(TERMUX_SERVICE.ACTION_MEMORY_WARNING);
        context.startService(serviceIntent);

        replyToSystem(notifyType, notifyId, 0, callback);
    }

    private void handleMemoryKill(Context context, int notifyType, int notifyId, IBinder callback) {
        Log.d(LOG_TAG, "Memory kill received, notifyType: " + notifyType + ", notifyId: " + notifyId);
        sMemoryKillReceived = true;

        Intent serviceIntent = new Intent(context, TermuxService.class);
        serviceIntent.setAction(TERMUX_SERVICE.ACTION_MEMORY_KILL);
        context.startService(serviceIntent);

        replyToSystem(notifyType, notifyId, 0, callback);
    }

    private void replyToSystem(int notifyType, int notifyId, int result, IBinder callback) {
        if (callback == null) {
            Log.w(LOG_TAG, "Callback binder is null, cannot reply to system");
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(notifyType);
            data.writeInt(notifyId);
            data.writeInt(result);
            Bundle extra = new Bundle();
            data.writeBundle(extra);
            callback.transact(TRANSACTION_EXCEPTION_REPLY, data, reply, IBinder.FLAG_ONEWAY);
            reply.readException();
            Log.d(LOG_TAG, "Successfully replied to system, notifyType: " + notifyType + ", notifyId: " + notifyId);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Failed to reply to system", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static boolean isMemoryWarningReceived() {
        return sMemoryWarningReceived;
    }

    public static boolean isMemoryKillReceived() {
        return sMemoryKillReceived;
    }

    public static void resetMemoryWarning() {
        sMemoryWarningReceived = false;
    }

    public static void resetMemoryKill() {
        sMemoryKillReceived = false;
    }
}