package com.termux.app.activities;

import android.content.Context;
import android.os.Bundle;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.file.FileUtils;
import com.termux.shared.models.ReportInfo;
import com.termux.app.models.UserAction;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.packages.PackageUtils;
import com.termux.shared.settings.preferences.TermuxAPIAppSharedPreferences;
import com.termux.shared.settings.preferences.TermuxFloatAppSharedPreferences;
import com.termux.shared.settings.preferences.TermuxTaskerAppSharedPreferences;
import com.termux.shared.settings.preferences.TermuxWidgetAppSharedPreferences;
import com.termux.shared.termux.AndroidUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new RootPreferencesFragment())
                .commit();
        }
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class RootPreferencesFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            configureTermuxAPIPreference(context);
            configureTermuxFloatPreference(context);
            configureTermuxTaskerPreference(context);
            configureTermuxWidgetPreference(context);
            configureAboutPreference(context);
        }

        private void configureTermuxAPIPreference(@NonNull Context context) {
            Preference termuxAPIPreference = findPreference("termux_api");
            if (termuxAPIPreference != null) {
                TermuxAPIAppSharedPreferences preferences = TermuxAPIAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxAPIPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxFloatPreference(@NonNull Context context) {
            Preference termuxFloatPreference = findPreference("termux_float");
            if (termuxFloatPreference != null) {
                TermuxFloatAppSharedPreferences preferences = TermuxFloatAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxFloatPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxTaskerPreference(@NonNull Context context) {
            Preference termuxTaskerPreference = findPreference("termux_tasker");
            if (termuxTaskerPreference != null) {
                TermuxTaskerAppSharedPreferences preferences = TermuxTaskerAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxTaskerPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxWidgetPreference(@NonNull Context context) {
            Preference termuxWidgetPreference = findPreference("termux_widget");
            if (termuxWidgetPreference != null) {
                TermuxWidgetAppSharedPreferences preferences = TermuxWidgetAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxWidgetPreference.setVisible(preferences != null);
            }
        }

        private void configureAboutPreference(@NonNull Context context) {
            Preference aboutPreference = findPreference("about");
            if (aboutPreference != null) {
                aboutPreference.setOnPreferenceClickListener(preference -> {
                    new Thread() {
                        @Override
                        public void run() {
                            String title = "About";

                            StringBuilder aboutString = new StringBuilder();
                            aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, false));

                            String termuxPluginAppsInfo =  TermuxUtils.getTermuxPluginAppsInfoMarkdownString(context);
                            if (termuxPluginAppsInfo != null)
                                aboutString.append("\n\n").append(termuxPluginAppsInfo);

                            aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context));
                            aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context));

                            String userActionName = UserAction.ABOUT.getName();
                            ReportActivity.startReportActivity(context, new ReportInfo(userActionName,
                                TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title, null,
                                aboutString.toString(), null, false,
                                userActionName,
                                Environment.getExternalStorageDirectory() + "/" +
                                    FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true)));
                        }
                    }.start();

                    return true;
                });
            }
        }

        }

}
