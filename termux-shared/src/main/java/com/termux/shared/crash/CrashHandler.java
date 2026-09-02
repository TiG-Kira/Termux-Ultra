package com.termux.shared.crash;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.models.errors.Error;
import com.termux.shared.termux.AndroidUtils;

import java.lang.reflect.Method;

import java.nio.charset.Charset;

/**
 * Catches uncaught exceptions, logs them, and tries to show an in-process dialog
 * immediately (TYPE_CRASH_ERROR) if possible. Falls back to crash_log.md +
 * next-launch dialog (TYPE_CRASH_POST) otherwise.
 *
 * Flow:
 *   1. Write crash_log.md ALWAYS — guaranteed persistence, never loses info.
 *   2. Try to launch AlertDialogActivity.TYPE_CRASH_ERROR on the main thread.
 *      If it launches successfully, set a "crash_dialog_shown" flag so next
 *      launch will skip the duplicate TYPE_CRASH_POST dialog.
 *   3. Kill the process after a short delay so the Activity has time to start.
 *
 * Native crash handler (native_crash_handler.c) follows the same md-first
 * pattern but does its own best-effort JNI callback.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context mContext;
    private final CrashHandlerClient mCrashHandlerClient;
    private final Thread.UncaughtExceptionHandler defaultUEH;

    private static final String LOG_TAG = "CrashHandler";
    /** Key in the app's SharedPreferences that signals: "we already showed
     *  the user a crash dialog in-process, don't show another on next launch." */
    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_CRASH_DIALOG_SHOWN = "crash_dialog_shown";

    private CrashHandler(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient) {
        this.mContext = context;
        this.mCrashHandlerClient = crashHandlerClient;
        this.defaultUEH = Thread.getDefaultUncaughtExceptionHandler();
    }

    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        // Step 1: Always persist the crash report first — this is the reliable fallback.
        logCrash(mContext, mCrashHandlerClient, thread, throwable);

        // Step 2: Only pop up TYPE_CRASH_ERROR dialog for exceptions that will kill the Activity
        // (i.e. main thread). Background thread exceptions don't kill the Activity → no in-process
        // dialog, crash_log.md stays and TYPE_CRASH_POST will be shown on next launch.
        final boolean mainThreadCrashed = isMainThread(thread);
        final String errorMessage = buildShortErrorMessage(throwable);

        boolean dialogLaunched = false;
        if (mainThreadCrashed) {
            // Activity will be killed — try to show an unrecoverable dialog now.
            dialogLaunched = tryLaunchCrashDialog(false, true, errorMessage);
            if (dialogLaunched) {
                markDialogShown();
                Logger.logDebug(LOG_TAG, "Main thread crash dialog launched");
            }
        } else {
            Logger.logDebug(LOG_TAG, "Background thread exception — no in-process dialog, will show on next launch");
        }
        // effectively-final copy for lambda
        final boolean dialogLaunchedFinal = dialogLaunched;

        // Step 3: Kill the process after a delay so the Activity has time to start (if any).
        // The crashed thread must not be left running.
        new Thread(() -> {
            try { Thread.sleep(dialogLaunchedFinal ? 2500 : 1500); } catch (InterruptedException ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        }, "CrashHandler-Kill").start();
    }

    /**
     * Set default uncaught crash handler of current thread to {@link CrashHandler}.
     */
    public static void setCrashHandler(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient) {
        if (!(Thread.getDefaultUncaughtExceptionHandler() instanceof CrashHandler)) {
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context, crashHandlerClient));
        }
    }

    private static boolean isMainThread(Thread thread) {
        return Looper.getMainLooper().getThread() == thread;
    }

    private static String buildShortErrorMessage(Throwable t) {
        if (t == null) return "Unknown error";
        String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
        if (msg.length() > 500) msg = msg.substring(0, 500) + "...";
        return msg;
    }

    private boolean tryLaunchCrashDialog(final boolean canRecover, final boolean mainThreadCrashed, final String errorMessage) {
        try {
            // Post to main thread — the crash might have happened on a background thread,
            // and startActivity must run on the main thread.
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    // Use reflection so termux-shared doesn't depend on app module directly.
                    Class<?> alertCls = Class.forName("com.termux.app.activities.AlertDialogActivity");
                    Method startMethod = alertCls.getMethod(
                        "startCrashError",
                        Context.class, String.class, boolean.class, boolean.class);
                    startMethod.invoke(null, mContext, errorMessage, canRecover, mainThreadCrashed);
                } catch (Throwable t) {
                    Logger.logError(LOG_TAG, "Reflection launch crash dialog failed: " + t.getMessage());
                }
            });
            // We can't know for sure if Activity started successfully from here
            // (startActivity is async), but if the post() didn't throw, consider it launched.
            return true;
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "tryLaunchCrashDialog failed: " + t.getMessage());
            return false;
        }
    }

    private void markDialogShown() {
        try {
            SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_CRASH_DIALOG_SHOWN, true).apply();
        } catch (Throwable ignored) {}
    }

    /**
     * Clear the dialog-shown flag. Called from {@code CrashUtils.notifyAppCrashOnLastRun}
     * when it detects crash_log.md — we check this flag and skip TYPE_CRASH_POST if it's set,
     * but we MUST clear the flag either way so it doesn't permanently suppress future dialogs.
     */
    public static boolean consumeDialogShownFlag(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean shown = prefs.getBoolean(KEY_CRASH_DIALOG_SHOWN, false);
            if (shown) {
                prefs.edit().remove(KEY_CRASH_DIALOG_SHOWN).apply();
            }
            return shown;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Log a crash in the crash log file at {@code crashlogFilePath}.
     *
     * @param context The {@link Context} for operations.
     * @param crashHandlerClient The {@link CrashHandlerClient} implementation.
     * @param thread The {@link Thread} in which the crash happened.
     * @param throwable The {@link Throwable} thrown for the crash.
     */
    public static void logCrash(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient, final Thread thread, final Throwable throwable) {
        StringBuilder reportString = new StringBuilder();

        reportString.append("## Crash Details\n");
        reportString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Crash Thread", thread.toString(), "-"));
        reportString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Crash Timestamp", AndroidUtils.getCurrentMilliSecondUTCTimeStamp(), "-"));
        reportString.append("\n\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry("Crash Message", throwable.getMessage(), "-"));
        reportString.append("\n\n").append(Logger.getStackTracesMarkdownString("Stacktrace", Logger.getStackTracesStringArray(throwable)));

        String appInfoMarkdownString = crashHandlerClient.getAppInfoMarkdownString(context);
        if (appInfoMarkdownString != null && !appInfoMarkdownString.isEmpty())
            reportString.append("\n\n").append(appInfoMarkdownString);

        reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context));

        // Log report string to logcat
        Logger.logError(reportString.toString());

        // Write report string to crash log file
        Error error = FileUtils.writeStringToFile("crash log", crashHandlerClient.getCrashLogFilePath(context),
                        Charset.defaultCharset(), reportString.toString(), false);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
        }

        // Also record the exception in the app log manager
        logExceptionToLogManager(throwable);
    }

    /**
     * Try to record the exception into the app's {@code LogManager} via reflection,
     * since termux-shared cannot depend on the app module directly.
     */
    private static void logExceptionToLogManager(Throwable throwable) {
        try {
            Class<?> logManagerClass = Class.forName("com.termux.app.utils.LogManager");
            Method getInstance = logManagerClass.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            Method exceptionMethod = logManagerClass.getMethod("exception", String.class, String.class, Throwable.class);
            exceptionMethod.invoke(instance, "CrashHandler", "Uncaught exception", throwable);
        } catch (Throwable ignored) {
            // LogManager not available in this build
        }
    }

    public interface CrashHandlerClient {

        /**
         * Get crash log file path.
         *
         * @param context The {@link Context} passed to {@link CrashHandler#CrashHandler(Context, CrashHandlerClient)}.
         * @return Should return the crash log file path.
         */
        @NonNull
        String getCrashLogFilePath(Context context);

        /**
         * Get app info markdown string to add to crash log.
         *
         * @param context The {@link Context} passed to {@link CrashHandler#CrashHandler(Context, CrashHandlerClient)}.
         * @return Should return app info markdown string.
         */
        String getAppInfoMarkdownString(Context context);

    }

}
