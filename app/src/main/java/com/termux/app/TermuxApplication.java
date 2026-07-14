package com.termux.app;

import android.app.Application;
import android.content.Context;

import com.termux.shared.crash.TermuxCrashUtils;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.logger.Logger;


public class TermuxApplication extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.attachBaseContext(base));
    }

    public void onCreate() {
        super.onCreate();

        TermuxCrashUtils.setCrashHandler(this);
        setLogLevel();
    }

    private void setLogLevel() {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(getApplicationContext());
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
        Logger.logDebug("Starting Application");
    }
}