package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

public class LanguageHelper {

    private static final String PREF_LANGUAGE = "ki_terminal_ux_language";
    private static final String KEY_LANGUAGE = "language";

    public static Context wrapContext(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREF_LANGUAGE, Context.MODE_PRIVATE);
        String language = preferences.getString(KEY_LANGUAGE, "en");

        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Resources resources = context.getResources();
        Configuration configuration = resources.getConfiguration();
        configuration.setLocale(locale);

        return context.createConfigurationContext(configuration);
    }

    public static void setLanguage(Context context, String language) {
        SharedPreferences preferences = context.getSharedPreferences(PREF_LANGUAGE, Context.MODE_PRIVATE);
        preferences.edit().putString(KEY_LANGUAGE, language).apply();
    }

    public static String getCurrentLanguage(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREF_LANGUAGE, Context.MODE_PRIVATE);
        return preferences.getString(KEY_LANGUAGE, "en");
    }
}