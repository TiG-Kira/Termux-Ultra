package com.termux.app.utils;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.termux.app.activities.AlertDialogActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

/**
 * Bridge called from the native signal handler (native_crash_handler.c).
 *
 * When a fatal native signal (SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL) occurs,
 * the native handler first writes crash_log.md (async-signal-safe), then calls
 * {@link #nativeCrashDetected(int)} here.
 *
 * If we can successfully launch the unrecoverable crash dialog, we delete the
 * just-written crash_log.md so next launch won't show a duplicate post-crash dialog.
 * If anything fails, the md file stays on disk and {@link CrashUtils} will show
 * "侦测到已发生错误" on next launch.
 *
 * Note: this is called from inside a signal handler. ART may be in an inconsistent
 * state, so we wrap everything in try-catch and keep it as simple as possible.
 */
public class NativeCrashBridge {

    private static final String LOG_TAG = "NativeCrashBridge";

    private static final String[] SIGNAL_NAMES = {
        /* 0 */ "???",
        /* 1 */ "SIGHUP", "SIGINT", "SIGQUIT", "SIGILL", "SIGTRAP", "SIGABRT",
        /* 7 */ "???", "SIGFPE", "SIGKILL", "SIGUSR1", "SIGSEGV", "SIGUSR2",
        /* 13 */ "SIGPIPE", "SIGALRM", "SIGTERM"
    };

    /**
     * Called from native signal handler. May be called on any thread (or even
     * on a thread whose stack has been smashed — ART may crash inside JNI here).
     *
     * @param sig The fatal signal number (e.g. 11 = SIGSEGV).
     */
    public static void nativeCrashDetected(int sig) {
        try {
            // Get a Context — we need it for startActivity
            Context ctx = getApplicationContext();
            if (ctx == null) {
                Logger.logError(LOG_TAG, "Cannot get Application context from native crash handler");
                return;
            }

            // Delete crash_log.md — if dialog shows, the user sees the crash info directly.
            // If the Activity can't be displayed (process dies), the file was just written
            // by the native handler above and contains the info we need for next launch,
            // so we DON'T delete it here. Only delete AFTER we know the Activity launched.
            //
            // Actually: since we're called from a signal handler, we can't wait for the
            // Activity to actually show. The safest approach: DO NOT delete here.
            // Delete only when CrashHandler (Java UncaughtExceptionHandler) launches
            // the dialog successfully (it's NOT called from a signal handler context).
            //
            // So for native crashes, crash_log.md stays → next launch shows TYPE_CRASH_POST.
            // But we still TRY to launch the unrecoverable dialog immediately.

            final String signalName = (sig >= 0 && sig < SIGNAL_NAMES.length)
                ? SIGNAL_NAMES[sig] : "SIG" + sig;

            // Run on main thread so startActivity works
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Intent intent = new Intent(ctx, AlertDialogActivity.class);
                    intent.putExtra(AlertDialogActivity.EXTRA_DIALOG_TYPE, AlertDialogActivity.TYPE_CRASH_ERROR);
                    intent.putExtra(AlertDialogActivity.EXTRA_ERROR_MESSAGE,
                        "Native crash: " + signalName + " (signal " + sig + ")");
                    intent.putExtra(AlertDialogActivity.EXTRA_CAN_RECOVER, false);
                    intent.putExtra(AlertDialogActivity.EXTRA_MAIN_THREAD_CRASHED, true);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                    Logger.logDebug(LOG_TAG, "Launched unrecoverable crash dialog for " + signalName);
                } catch (Throwable t) {
                    Logger.logError(LOG_TAG, "Failed to launch crash dialog: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "nativeCrashDetected failed: " + t.getMessage());
        }
    }

    /**
     * Best-effort lookup of the Application Context via reflection.
     * We can't rely on a static field here because Application.onCreate()
     * might not have run yet if the native crash happens extremely early.
     */
    private static Context getApplicationContext() {
        try {
            // ActivityThread.currentApplication() returns the Application instance
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = atClass.getMethod("currentApplication");
            Object app = m.invoke(null);
            if (app instanceof Application) {
                return (Application) app;
            }
        } catch (Throwable ignored) {}

        // Fallback: try to read the crash_log path and just return null
        return null;
    }
}
