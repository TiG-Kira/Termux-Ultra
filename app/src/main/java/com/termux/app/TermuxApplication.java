package com.termux.app;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.TermuxApiLogger;
import com.termux.shared.crash.TermuxCrashUtils;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.logger.Logger;

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

    @Override
    public void onCreate() {
        super.onCreate();

        TermuxCrashUtils.setCrashHandler(this);
        setLogLevel();

        startTermuxApiListener(getApplicationContext());
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
