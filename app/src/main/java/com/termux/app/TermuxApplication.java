package com.termux.app;

import android.app.Application;
import android.app.Application.ActivityLifecycleCallbacks;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.TermuxApiLogger;
import com.termux.shared.crash.TermuxCrashUtils;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.logger.Logger;
import com.termux.terminal.JNI;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class TermuxApplication extends Application {

    /**
     * Mirrored from the standalone termux-api APK's {@code com.termux.api.App} Application
     * class.  When Termux:API is integrated as a library module the host app runs its own
     * Application subclass, so no-one would otherwise create the {@code LocalServerSocket}
     * that the {@code termux-api} shell binaries connect to.  Without this listener every
     * termux-api command would hang waiting for a peer at {@code com.termux.api://listen}.
     */
    private static final String LISTEN_ADDRESS = "com.termux.api://listen";
    private static final Pattern EXTRA_STRING = Pattern.compile("(-e|--es|--esa) +([^ ]+) +\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
    private static final Pattern EXTRA_BOOLEAN = Pattern.compile("--ez +([^ ]+) +([^ ]+)");
    private static final Pattern EXTRA_INT = Pattern.compile("--ei +([^ ]+) +(-?[0-9]+)");
    private static final Pattern EXTRA_FLOAT = Pattern.compile("--ef +([^ ]+) +(-?[0-9]+(?:\\.[0-9]+))");
    private static final Pattern EXTRA_INT_LIST = Pattern.compile("--eia +([^ ]+) +(-?[0-9]+(?:,-?[0-9]+)*)");
    private static final Pattern EXTRA_LONG_LIST = Pattern.compile("--ela +([^ ]+) +(-?[0-9]+(?:,-?[0-9]+)*)");
    private static final Pattern EXTRA_UNSUPPORTED = Pattern.compile("--e[^izs ] +[^ ]+ +[^ ]+");
    private static final Pattern ACTION = Pattern.compile("-a *([^ ]+)");
    private static final String LOG_TAG = "TermuxApplication.API";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.attachBaseContext(base));
    }

    // 防止重复弹窗的标记：一旦某个 Activity 已经弹出过崩溃提示，后续 Activity 不再弹
    private static volatile boolean sCrashDialogShown = false;

    @Override
    public void onCreate() {
        super.onCreate();

        // 注册 ActivityLifecycleCallbacks：每个 Activity 进入前台时检测是否有待处理的崩溃
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {
                // 每个 Activity 进入前台时检测一次崩溃（只弹一次）
                if (!sCrashDialogShown) {
                    try {
                        com.termux.app.utils.CrashUtils.notifyAppCrashOnLastRun(activity, "TermuxApplication");
                        sCrashDialogShown = true; // 标记已触发，避免重复（即使没弹成功也不再触发）
                    } catch (Throwable ignored) {}
                }
            }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });

        // Initialize LogManager first
        com.termux.app.utils.LogManager.init(this);

        // Check one-shot fallback flag: if user chose Fallback in last crash dialog,
        // enter terminal-lock mode on next launch and consume the flag.
        // 必须在主线程执行，因为决定了是否设置 crash recovery 标记
        try {
            if (com.termux.app.FallbackHelper.INSTANCE.consumeOneShotFallbackFlag(this)) {
                android.util.Log.i("TermuxApplication", "One-shot fallback flag consumed — entering terminal-lock mode");
                com.termux.app.compose.ApiCompat.INSTANCE.markMiuixUiFailed();
            }
        } catch (Throwable t) {
            android.util.Log.w("TermuxApplication", "One-shot fallback check failed", t);
        }

        // 以下所有可能涉及 native 加载、磁盘 I/O、shell 命令的操作全部放后台线程，
        // 保证 Application.onCreate() 在几毫秒内返回，不阻塞主线程冷启动。
        new Thread(() -> {
            // Install native signal handlers for SIGSEGV/SIGABRT/etc. BEFORE the Java
            // UncaughtExceptionHandler, so both layers work together. The libtermux
            // library (containing nativeSetupCrashHandler) is loaded lazily on first
            // JNI access — calling it here ensures registration happens early.
            // libtermux .so首次加载在低端机可耗时1-2秒
            try {
                JNI.nativeSetupCrashHandler();
                android.util.Log.i("TermuxApplication", "Native crash handler registered (bg)");
            } catch (Throwable t) {
                android.util.Log.w("TermuxApplication", "Native crash handler registration failed", t);
            }

            // Java 层 crash handler — 放在 native handler 之后
            TermuxCrashUtils.setCrashHandler(TermuxApplication.this);

            // 初始化 AI 模块（本地大模型 + Ollama）——仅存 context，极轻量
            try {
                com.termux.app.compose.AiLocalModel.init(TermuxApplication.this);
                com.termux.app.compose.AiOllamaManager.init(TermuxApplication.this);
                android.util.Log.i("TermuxApplication", "AI 模块初始化完成 (bg)");
            } catch (Throwable t) {
                android.util.Log.e("TermuxApplication", "AI 模块初始化失败", t);
            }
            setLogLevel();

            startTermuxApiListener(getApplicationContext());

            // am-wrapper 同步（可能跑 shell 命令）
            try {
                if (com.termux.app.compose.IntegratedTools.INSTANCE.isEnabled(getApplicationContext(), com.termux.app.compose.IntegratedTools.Tool.TERMUX_API)) {
                    com.termux.app.compose.TermuxApiBroadcastFix.applyAmWrapper(getApplicationContext());
                } else {
                    com.termux.app.compose.TermuxApiBroadcastFix.removeAmWrapper();
                }
            } catch (Throwable t) {
                android.util.Log.e(LOG_TAG, "Failed to sync Termux:API am wrapper", t);
            }
        }, "Termux-BgInit").start();
    }

    private static void startTermuxApiListener(final Context context) {
        new Thread(() -> {
            try (LocalServerSocket listen = new LocalServerSocket(LISTEN_ADDRESS)) {
                //noinspection InfiniteLoopStatement
                while (true) {
                    try (LocalSocket con = listen.accept();
                         DataInputStream in = new DataInputStream(con.getInputStream());
                         BufferedWriter out = new BufferedWriter(new OutputStreamWriter(con.getOutputStream()))) {
                        if (con.getPeerCredentials().getUid() != context.getApplicationInfo().uid) {
                            continue;
                        }
                        try {
                            int length = in.readUnsignedShort();
                            byte[] b = new byte[length];
                            in.readFully(b);
                            String cmdline = new String(b, StandardCharsets.UTF_8);

                            Intent intent = new Intent(context, TermuxApiReceiver.class);
                            HashMap<String, String> stringExtras = new HashMap<>();
                            HashMap<String, String[]> stringArrayExtras = new HashMap<>();
                            HashMap<String, Boolean> booleanExtras = new HashMap<>();
                            HashMap<String, Integer> intExtras = new HashMap<>();
                            HashMap<String, Float> floatExtras = new HashMap<>();
                            HashMap<String, int[]> intArrayExtras = new HashMap<>();
                            HashMap<String, long[]> longArrayExtras = new HashMap<>();
                            boolean err = false;

                            Matcher m = EXTRA_STRING.matcher(cmdline);
                            while (m.find()) {
                                String option = m.group(1);
                                if ("-e".equals(option) || "--es".equals(option)) {
                                    stringExtras.put(m.group(2), Objects.requireNonNull(m.group(3)).replaceAll("\\\\\"", "\""));
                                } else {
                                    String[] list = Objects.requireNonNull(m.group(3)).split("(?<!\\\\),");
                                    for (int i = 0; i < list.length; i++) {
                                        list[i] = list[i].replaceFirst("\\\\,", ",");
                                    }
                                    stringArrayExtras.put(m.group(2), list);
                                }
                            }
                            cmdline = m.replaceAll("");

                            m = EXTRA_BOOLEAN.matcher(cmdline);
                            while (m.find()) {
                                booleanExtras.put(m.group(1), Boolean.parseBoolean(m.group(2)));
                            }
                            cmdline = m.replaceAll("");

                            m = EXTRA_INT.matcher(cmdline);
                            while (m.find()) {
                                try {
                                    intExtras.put(m.group(1), Integer.parseInt(Objects.requireNonNull(m.group(2))));
                                } catch (NumberFormatException e) {
                                    String msg = "Invalid integer extra: " + m.group(0) + "\n";
                                    TermuxApiLogger.info(msg);
                                    out.write(msg);
                                    err = true;
                                    break;
                                }
                            }
                            cmdline = m.replaceAll("");

                            m = EXTRA_FLOAT.matcher(cmdline);
                            while (m.find()) {
                                try {
                                    floatExtras.put(m.group(1), Float.parseFloat(Objects.requireNonNull(m.group(2))));
                                } catch (NumberFormatException e) {
                                    String msg = "Invalid float extra: " + m.group(0) + "\n";
                                    TermuxApiLogger.info(msg);
                                    out.write(msg);
                                    err = true;
                                    break;
                                }
                            }
                            cmdline = m.replaceAll("");

                            m = EXTRA_INT_LIST.matcher(cmdline);
                            while (m.find()) {
                                try {
                                    String[] parts = Objects.requireNonNull(m.group(2)).split(",");
                                    int[] ints = new int[parts.length];
                                    for (int i = 0; i < parts.length; i++) {
                                        ints[i] = Integer.parseInt(parts[i]);
                                    }
                                    intArrayExtras.put(m.group(1), ints);
                                } catch (NumberFormatException e) {
                                    String msg = "Invalid int array extra: " + m.group(0) + "\n";
                                    TermuxApiLogger.info(msg);
                                    out.write(msg);
                                    err = true;
                                    break;
                                }
                            }
                            cmdline = m.replaceAll("");

                            m = EXTRA_LONG_LIST.matcher(cmdline);
                            while (m.find()) {
                                try {
                                    String[] parts = Objects.requireNonNull(m.group(2)).split(",");
                                    long[] longs = new long[parts.length];
                                    for (int i = 0; i < parts.length; i++) {
                                        longs[i] = Long.parseLong(parts[i]);
                                    }
                                    longArrayExtras.put(m.group(1), longs);
                                } catch (NumberFormatException e) {
                                    String msg = "Invalid long array extra: " + m.group(0) + "\n";
                                    TermuxApiLogger.info(msg);
                                    out.write(msg);
                                    err = true;
                                    break;
                                }
                            }
                            cmdline = m.replaceAll("");

                            m = ACTION.matcher(cmdline);
                            while (m.find()) {
                                intent.setAction(m.group(1));
                            }
                            cmdline = m.replaceAll("");

                            m = EXTRA_UNSUPPORTED.matcher(cmdline);
                            if (m.find()) {
                                String msg = "Unsupported argument type: " + m.group(0) + "\n";
                                TermuxApiLogger.info(msg);
                                out.write(msg);
                                err = true;
                            }
                            cmdline = m.replaceAll("");

                            cmdline = cmdline.replaceAll("\\s", "");
                            if (!"".equals(cmdline)) {
                                String msg = "Unsupported options: " + cmdline + "\n";
                                TermuxApiLogger.info(msg);
                                out.write(msg);
                                err = true;
                            }

                            if (err) {
                                out.flush();
                                continue;
                            }

                            for (Map.Entry<String, String> e : stringExtras.entrySet()) {
                                intent.putExtra(e.getKey(), e.getValue());
                            }
                            for (Map.Entry<String, String[]> e : stringArrayExtras.entrySet()) {
                                intent.putExtra(e.getKey(), e.getValue());
                            }
                            for (Map.Entry<String, Integer> e : intExtras.entrySet()) {
                                intent.putExtra(e.getKey(), e.getValue());
                            }
                            for (Map.Entry<String, Boolean> e : booleanExtras.entrySet()) {
                                intent.putExtra(e.getKey(), e.getValue());
                            }
                            for (Map.Entry<String, Float> e : floatExtras.entrySet()) {
                                intent.putExtra(e.getKey(), e.getValue());
                            }
                            for (Map.Entry<String, int[]> e : intArrayExtras.entrySet()) {
                                intent.putExtra(e.getKey(), e.getValue());
                            }
                            for (Map.Entry<String, long[]> e : longArrayExtras.entrySet()) {
                                intent.putExtra(e.getKey(), e.getValue());
                            }
                            context.sendOrderedBroadcast(intent, null);
                            con.getOutputStream().write(0);
                            con.getOutputStream().flush();
                        } catch (Exception e) {
                            TermuxApiLogger.error("Error parsing arguments", e);
                            out.write("Exception in the plugin\n");
                            out.flush();
                        }
                    }
                }
            } catch (Exception e) {
                TermuxApiLogger.error("Error listening for termux-api connections", e);
                Logger.logError(LOG_TAG, "API listener socket failed: " + e.getMessage());
            }
        }, "TermuxAPI-Listener").start();
    }

    private void setLogLevel() {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(getApplicationContext());
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
        Logger.logDebug("Starting Application");
    }
}
