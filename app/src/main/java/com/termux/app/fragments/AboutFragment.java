package com.termux.app.fragments;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.preference.CircleIconPreference;

/**
 * HyperCeiler-style container for the About page.
 * Hosts a HeaderView (Logo + AppName + Version) and delegates the
 * actual Preference list work to an embedded {@link AboutPrefsFragment}.
 */
public class AboutFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupHeader(view);

        if (savedInstanceState == null) {
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.about_prefs_container, new AboutPrefsFragment())
                    .commit();
        }
    }

    private void setupHeader(@NonNull View view) {
        ImageView logo = view.findViewById(R.id.about_logo);
        TextView appName = view.findViewById(R.id.about_app_name);
        TextView version = view.findViewById(R.id.about_version);

        Drawable appIcon = ContextCompat.getDrawable(requireContext(), R.mipmap.ic_launcher);
        if (appIcon != null) {
            logo.setImageDrawable(appIcon);
        } else {
            logo.setImageResource(R.drawable.ic_terminal);
        }

        appName.setText("Termux Ultra");
        version.setText(BuildConfig.VERSION_NAME);
    }

    // ========================================================================
    // Embedded PreferenceFragment — mirrors HyperCeiler's AboutSettingsFragment
    // ========================================================================

    public static class AboutPrefsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.prefs_about, rootKey);
            initPrefs();
        }

        private void initPrefs() {
            PreferenceScreen screen = getPreferenceScreen();
            if (screen == null) return;

            // --- CircleIconPreference network avatars ---
            CircleIconPreference devPref = findPreference("about_developer");
            if (devPref != null) {
                devPref.setIconUrl("https://github.com/TiG-Kira.png");
                devPref.setOnPreferenceClickListener(p -> {
                    openUrl("https://github.com/TiG-Kira");
                    return true;
                });
            }

            CircleIconPreference contribPref1 = findPreference("about_contributor_1");
            if (contribPref1 != null) {
                contribPref1.setIconUrl(
                        "https://avatars.githubusercontent.com/u/133107732?v=4");
                contribPref1.setOnPreferenceClickListener(p -> {
                    openUrl("https://github.com/awkox");
                    return true;
                });
            }

            // --- Device info summaries ---
            Preference model = findPreference("device_model");
            if (model != null) model.setSummary(Build.MODEL);

            Preference androidVer = findPreference("android_version");
            if (androidVer != null) androidVer.setSummary(Build.VERSION.RELEASE);

            Preference kernelVer = findPreference("kernel_version");
            if (kernelVer != null) kernelVer.setSummary(Build.DISPLAY);

            // --- Version info summaries ---
            Preference tuVer = findPreference("tu_version");
            if (tuVer != null) tuVer.setSummary(BuildConfig.VERSION_NAME);

            Preference basedOn = findPreference("based_on_termux");
            if (basedOn != null)
                basedOn.setSummary(getString(R.string.termux_core_version));

            Preference libterminal = findPreference("libterminal_version");
            if (libterminal != null)
                libterminal.setSummary(getString(R.string.libterminal_core_info,
                        BuildConfig.LIBTERMINAL_VERSION));

            // --- Check updates click ---
            Preference check = findPreference("check_updates");
            if (check != null) {
                check.setOnPreferenceClickListener(p -> {
                    openUrl("https://github.com/TiG-Kira/Termux-Ultra/releases");
                    return true;
                });
            }
        }

        private void openUrl(@NonNull String url) {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }
}
