package com.termux.widget.activities;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.shared.logger.Logger;
import com.termux.shared.packages.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.widget.R;

public class TermuxWidgetActivity extends AppCompatActivity {

    private static final String LOG_TAG = "TermuxWidgetActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_termux_widget);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.title_widget_settings);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        TextView pluginInfo = findViewById(R.id.textview_plugin_info);
        pluginInfo.setText(getString(R.string.plugin_info, TermuxConstants.TERMUX_GITHUB_REPO_URL,
                TermuxConstants.TERMUX_WIDGET_GITHUB_REPO_URL));

        Button disableLauncherIconButton = findViewById(R.id.btn_disable_launcher_icon);
        disableLauncherIconButton.setOnClickListener(v -> {
            // As a library module, widget's own R does not merge termux-shared resources,
            // so reference the shared R directly for this shared string.
            String message = getString(com.termux.shared.R.string.msg_disabling_launcher_icon, TermuxConstants.TERMUX_WIDGET_APP_NAME);
            Logger.logInfo(LOG_TAG, message);
            // When integrated into the host app, the component lives under the host package
            // name (com.termux), not the legacy standalone widget package (com.termux.widget).
            // Use the activity's own package name so this works in both modes.
            PackageUtils.setComponentState(TermuxWidgetActivity.this,
                    getPackageName(), TermuxConstants.TERMUX_WIDGET.TERMUX_WIDGET_ACTIVITY_NAME,
                    false, message, true);
        });
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

}
