package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

public class LocaleHelper {

    private static final String PREF_LANGUAGE = "app_language";
    private static final String VALUE_ENGLISH = "en";
    private static final String VALUE_CHINESE = "zh";

    public static Context attachBaseContext(Context context) {
        String language = getLanguage(context);
        return setLocale(context, language);
    }

    public static Context setLocale(Context context, String language) {
        saveLanguage(context, language);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return updateResources(context, language);
        }

        return updateResourcesLegacy(context, language);
    }

    private static String getLanguage(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        String language = preferences.getString(PREF_LANGUAGE, VALUE_CHINESE);
        if (language == null || language.isEmpty()) {
            return VALUE_CHINESE;
        }
        return language;
    }

    private static void saveLanguage(Context context, String language) {
        SharedPreferences preferences = context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        preferences.edit().putString(PREF_LANGUAGE, language).apply();
    }

    private static Context updateResources(Context context, String language) {
        Locale locale = getLocaleFromString(language);
        Locale.setDefault(locale);

        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(locale);

        return context.createConfigurationContext(configuration);
    }

    @SuppressWarnings("deprecation")
    private static Context updateResourcesLegacy(Context context, String language) {
        Locale locale = getLocaleFromString(language);
        Locale.setDefault(locale);

        Resources resources = context.getResources();
        Configuration configuration = resources.getConfiguration();
        configuration.locale = locale;

        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
        return context;
    }

    private static Locale getLocaleFromString(String language) {
        if (VALUE_CHINESE.equals(language)) {
            return Locale.SIMPLIFIED_CHINESE;
        } else {
            return Locale.ENGLISH;
        }
    }

    public static void setChinese(Context context) {
        setLocale(context, VALUE_CHINESE);
    }

    public static void setEnglish(Context context) {
        setLocale(context, VALUE_ENGLISH);
    }

    public static boolean isChinese(Context context) {
        return VALUE_CHINESE.equals(getLanguage(context));
    }
}