package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class SplashActivity extends AppCompatActivity {

    public static final String PREF_OOBE_STATE = "ki_terminal_ux_oobe_state";
    public static final String KEY_IS_PROVISIONED = "is_provisioned";
    private static final String PREF_LANGUAGE = "app_language";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedLanguage(this);
        super.onCreate(savedInstanceState);

        try {
            SharedPreferences preferences = getSharedPreferences(PREF_OOBE_STATE, MODE_PRIVATE);
            boolean isProvisioned = preferences.getBoolean(KEY_IS_PROVISIONED, false);

            Intent intent;
            if (isProvisioned) {
                intent = new Intent(this, MainActivity.class);
            } else {
                intent = new Intent(this, OobeActivity.class);
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
            .apply();
    }
}