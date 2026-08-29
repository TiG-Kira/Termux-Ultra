package com.termux.shared.crash;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.models.errors.Error;
import com.termux.shared.termux.AndroidUtils;

import java.lang.reflect.Method;

import java.nio.charset.Charset;

/**
 * Catches uncaught exceptions and logs them.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context mContext;
    private final CrashHandlerClient mCrashHandlerClient;
    private final Thread.UncaughtExceptionHandler defaultUEH;

    private static final String LOG_TAG = "CrashUtils";

    private CrashHandler(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient) {
        this.mContext = context;
        this.mCrashHandlerClient = crashHandlerClient;
        this.defaultUEH = Thread.getDefaultUncaughtExceptionHandler();
    }

    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        // 1. 先记录崩溃日志
        logCrash(mContext, mCrashHandlerClient, thread, throwable);

        // 2. 尝试启动崩溃提示 Activity（使用 WindowDialog，不是 OverlayDialog）
        try {
            startCrashErrorDialog(throwable);
        } catch (Throwable ignored) {
        }

        // 3. 延迟 2 秒让崩溃提示 Activity 有机会显示，再让系统退出进程
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // 4. 最后让系统默认处理器处理
        if (defaultUEH != null && defaultUEH != this) {
            defaultUEH.uncaughtException(thread, throwable);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        }
    }

    /**
     * 启动 AlertDialogActivity 显示崩溃提示（使用 WindowDialog）。
     * 通过反射调用 app 模块，因为 termux-shared 不能依赖 app。
     */
    private void startCrashErrorDialog(Throwable throwable) {
        try {
            StringBuilder sb = new StringBuilder();
            Throwable cause = throwable;
            int depth = 0;
            while (cause != null && depth < 3) {
                sb.append(cause.getClass().getSimpleName())
                  .append(": ")
                  .append(cause.getMessage() != null ? cause.getMessage() : "");
                if (cause.getCause() != null && depth < 2) {
                    sb.append(" <- ");
                }
                cause = cause.getCause();
                depth++;
            }

            String errorMsg = sb.toString();
            if (errorMsg.length() > 300) {
                errorMsg = errorMsg.substring(0, 300) + "...";
            }

            Class<?> activityClass = Class.forName("com.termux.app.activities.AlertDialogActivity");
            java.lang.reflect.Method startMethod = activityClass.getMethod(
                "startCrashError",
                android.content.Context.class,
                String.class,
                boolean.class
            );
            startMethod.invoke(null, mContext.getApplicationContext(), errorMsg, true);
        } catch (ClassNotFoundException e) {
            // app 模块没有这个 Activity，跳过
        } catch (Throwable t) {
            android.util.Log.w(LOG_TAG, "启动崩溃提示 Activity 失败: " + t.getMessage());
        }
    }

    /**
     * Set default uncaught crash handler of current thread to {@link CrashHandler}.
     */
    public static void setCrashHandler(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient) {
        if (!(Thread.getDefaultUncaughtExceptionHandler() instanceof CrashHandler)) {
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context, crashHandlerClient));
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
