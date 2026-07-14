package com.termux.app.fragments;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.LanguageHelper;
import com.termux.app.MainActivity;
import com.termux.app.activities.AboutActivity;
import com.termux.shared.packages.PackageUtils;
import com.termux.shared.settings.preferences.TermuxAPIAppSharedPreferences;
import com.termux.shared.settings.preferences.TermuxFloatAppSharedPreferences;
import com.termux.shared.settings.preferences.TermuxTaskerAppSharedPreferences;
import com.termux.shared.settings.preferences.TermuxWidgetAppSharedPreferences;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

public class SettingsFragment extends PreferenceFragmentCompat {

    private String[] languageOptions = {"English", "中文"};
    private String[] languageValues = {"en", "zh"};

    public SettingsFragment() {
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        configureTermuxAPIPreference(context);
        configureTermuxFloatPreference(context);
        configureTermuxTaskerPreference(context);
        configureTermuxWidgetPreference(context);
        configureLanguagePreference(context);
        configureAboutPreference(context);
        configureDonatePreference(context);
    }

    private void configureTermuxAPIPreference(@NonNull Context context) {
        Preference termuxAPIPreference = findPreference("termux_api");
        if (termuxAPIPreference != null) {
            TermuxAPIAppSharedPreferences preferences = TermuxAPIAppSharedPreferences.build(context, false);
            termuxAPIPreference.setVisible(preferences != null);
        }
    }

    private void configureTermuxFloatPreference(@NonNull Context context) {
        Preference termuxFloatPreference = findPreference("termux_float");
        if (termuxFloatPreference != null) {
            TermuxFloatAppSharedPreferences preferences = TermuxFloatAppSharedPreferences.build(context, false);
            termuxFloatPreference.setVisible(preferences != null);
        }
    }

    private void configureTermuxTaskerPreference(@NonNull Context context) {
        Preference termuxTaskerPreference = findPreference("termux_tasker");
        if (termuxTaskerPreference != null) {
            TermuxTaskerAppSharedPreferences preferences = TermuxTaskerAppSharedPreferences.build(context, false);
            termuxTaskerPreference.setVisible(preferences != null);
        }
    }

    private void configureTermuxWidgetPreference(@NonNull Context context) {
        Preference termuxWidgetPreference = findPreference("termux_widget");
        if (termuxWidgetPreference != null) {
            TermuxWidgetAppSharedPreferences preferences = TermuxWidgetAppSharedPreferences.build(context, false);
            termuxWidgetPreference.setVisible(preferences != null);
        }
    }

    private void configureLanguagePreference(@NonNull Context context) {
        Preference languagePreference = findPreference("language");
        if (languagePreference != null) {
            String currentLang = LanguageHelper.getCurrentLanguage(context);
            languagePreference.setSummary(currentLang.equals("zh") ? getString(R.string.chinese) : getString(R.string.english));

            languagePreference.setOnPreferenceClickListener(preference -> {
                showLanguageDialog();
                return true;
            });
        }
    }

    private void configureAboutPreference(@NonNull Context context) {
        Preference aboutPreference = findPreference("about");
        if (aboutPreference != null) {
            aboutPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(context, AboutActivity.class);
                startActivity(intent);
                return true;
            });
        }
    }

    private void configureDonatePreference(@NonNull Context context) {
        Preference donatePreference = findPreference("donate");
        if (donatePreference != null) {
            String signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context);
            if (signingCertificateSHA256Digest != null) {
                String apkRelease = TermuxUtils.getAPKRelease(signingCertificateSHA256Digest);
                if (apkRelease == null || apkRelease.equals(TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST)) {
                    donatePreference.setVisible(false);
                    return;
                } else {
                    donatePreference.setVisible(true);
                }
            }

            donatePreference.setOnPreferenceClickListener(preference -> {
                ShareUtils.openURL(context, TermuxConstants.TERMUX_DONATE_URL);
                return true;
            });
        }
    }

    private void showLanguageDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getContext());
        builder.setTitle(R.string.language);
        builder.setItems(languageOptions, (dialog, which) -> {
            LanguageHelper.setLanguage(getContext(), languageValues[which]);
            getActivity().finish();
            startActivity(new Intent(getContext(), MainActivity.class));
        });
        builder.show();
    }
}