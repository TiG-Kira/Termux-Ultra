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
        // 1. 记录崩溃日志（写入 crash_log.md，下次启动时检测并弹窗）
        logCrash(mContext, mCrashHandlerClient, thread, throwable);

        // 2. 启动子线程延迟杀进程，给系统足够时间完成日志写入
        //    不在崩溃瞬间启动 Activity —— 主线程崩溃时主线程已死，startActivity 无效
        //    也不调用 defaultUEH（系统会走默认弹窗+杀进程，我们已记录好日志）
        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
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
