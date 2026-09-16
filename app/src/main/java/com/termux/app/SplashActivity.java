package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;

import com.termux.BuildConfig;
import java.util.Locale;

/**
 * 冷启动分发 Activity。继承轻量的 Activity 而非 AppCompatActivity，
 * 避免 AppCompat 初始化开销（可节省 300-800ms）。只做 SharedPreferences 读取 +
 * Intent 跳转，无需 AppCompat 主题/功能。
 */
public class SplashActivity extends Activity {

    public static final String PREF_OOBE_STATE = "ki_terminal_ux_oobe_state";
    public static final String KEY_IS_PROVISIONED = "is_provisioned";
    public static final String KEY_INSTALLED_VERSION = "installed_version";
    public static final String KEY_EULA_DATE = "eula_date";
    private static final String PREF_LANGUAGE = "app_language";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedLanguage(this);
        super.onCreate(savedInstanceState);

        try {
            SharedPreferences preferences = getSharedPreferences(PREF_OOBE_STATE, MODE_PRIVATE);
            boolean isProvisioned = preferences.getBoolean(KEY_IS_PROVISIONED, false);
            String lastInstalledVersion = preferences.getString(KEY_INSTALLED_VERSION, "");
            String currentVersion = BuildConfig.VERSION_NAME;

            Intent intent;
            if (!isProvisioned) {
                // 全新安装
                intent = new Intent(this, OobeActivity.class);
                intent.putExtra(OobeActivity.EXTRA_IS_UPGRADE, false);
            } else if (!currentVersion.equals(lastInstalledVersion)) {
                // 升级用户
                intent = new Intent(this, OobeActivity.class);
                intent.putExtra(OobeActivity.EXTRA_IS_UPGRADE, true);
            } else {
                // 正常启动
                intent = new Intent(this, MainActivity.class);
            }
            startActivity(intent);
            finish();
        } catch (Throwable t) {
            // 启动阶段任何异常（API版本不兼容、类加载失败等）直接降级到终端模式
            FallbackHelper.INSTANCE.enterTerminalOnlyMode(this);
        }
    }

    private void applySavedLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("termux_ultra_settings", Context.MODE_PRIVATE);
        String language = prefs.getString(PREF_LANGUAGE, null);
        if (language != null) {
            Locale locale = new Locale(language);
            Locale.setDefault(locale);
            Configuration config = new Configuration();
            config.setLocale(locale);
            context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
        }
    }

    public static void setProvisioned(Context context, boolean provisioned) {
        context.getSharedPreferences(PREF_OOBE_STATE, MODE_PRIVATE).edit()
            .putBoolean(KEY_IS_PROVISIONED, provisioned)
            .putString(KEY_INSTALLED_VERSION, com.termux.BuildConfig.VERSION_NAME)
            .apply();
    }

    public static String getInstalledVersion(Context context) {
        return context.getSharedPreferences(PREF_OOBE_STATE, MODE_PRIVATE)
            .getString(KEY_INSTALLED_VERSION, "");
    }

    public static void setEulaDate(Context context, String date) {
        context.getSharedPreferences(PREF_OOBE_STATE, MODE_PRIVATE).edit()
            .putString(KEY_EULA_DATE, date)
            .apply();
    }

    public static String getEulaDate(Context context) {
        return context.getSharedPreferences(PREF_OOBE_STATE, MODE_PRIVATE)
            .getString(KEY_EULA_DATE, "");
    }

    public static void resetOobe(Context context) {
        context.getSharedPreferences(PREF_OOBE_STATE, MODE_PRIVATE).edit()
            .putBoolean(KEY_IS_PROVISIONED, false)
            .putString(KEY_INSTALLED_VERSION, "")
            .apply();
    }
}
