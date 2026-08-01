package com.termux.app.compose

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.termux.R

/**
 * Manages the enable/disable state of the integrated Termux add-on tools
 * (Termux:API, Termux:Boot, Termux:Styling, Termux:Tasker, Termux:Widget).
 *
 * All tools default to OFF. When a feature that depends on one of these tools is used but the
 * corresponding toggle is off, callers should call [requireEnabled] (or [showEnablePrompt]) to
 * direct the user to Settings to enable it, instead of asking them to download a separate app.
 */
object IntegratedTools {

    const val PREFS_NAME = "integrated_tools_prefs"

    enum class Tool(val key: String, val nameRes: Int, val standalonePackage: String) {
        TERMUX_API("tool_termux_api", R.string.termux_api_tool, "com.termux.api"),
        TERMUX_BOOT("tool_termux_boot", R.string.termux_boot_tool, "com.termux.boot"),
        TERMUX_STYLING("tool_termux_styling", R.string.termux_styling_tool, "com.termux.styling"),
        TERMUX_TASKER("tool_termux_tasker", R.string.termux_tasker_tool, "com.termux.tasker"),
        TERMUX_WIDGET("tool_termux_widget", R.string.termux_widget_tool, "com.termux.widget")
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the given [tool] is enabled. Defaults to `false`. */
    @JvmStatic
    fun isEnabled(context: Context, tool: Tool): Boolean =
        prefs(context).getBoolean(tool.key, false)

    /** Set the enabled state of [tool]. */
    fun setEnabled(context: Context, tool: Tool, enabled: Boolean) {
        prefs(context).edit().putBoolean(tool.key, enabled).apply()
    }

    /**
     * The fully-qualified Android component class names that belong to each tool. These are declared
     * with `android:enabled="false"` in the merged manifest, and are toggled at runtime via
     * [PackageManager.setComponentEnabledSetting] when the user flips a tool's switch.
     */
    private fun componentsFor(tool: Tool): List<String> = when (tool) {
        Tool.TERMUX_API -> listOf(
            "com.termux.api.TermuxApiReceiver",
            "com.termux.api.DialogActivity",
            "com.termux.api.NfcActivity",
            "com.termux.api.FingerprintAPI\$FingerprintActivity",
            "com.termux.api.util.TermuxApiPermissionActivity",
            "com.termux.api.StorageGetAPI\$StorageActivity",
            "com.termux.api.SpeechToTextAPI\$SpeechToTextService",
            "com.termux.api.TextToSpeechAPI\$TextToSpeechService",
            "com.termux.api.SensorAPI\$SensorReaderService",
            "com.termux.api.ShareAPI\$ContentProvider",
            "com.termux.api.MediaPlayerAPI\$PlayerService",
            "com.termux.api.MicRecorderAPI\$MicRecorderService",
            "com.termux.api.WallpaperAPI\$WallpaperService",
            "com.termux.api.NotificationService",
            "com.termux.api.SchedulerJobService",
            "com.termux.api.KeepAliveService"
        )
        Tool.TERMUX_STYLING -> listOf("com.termux.styling.TermuxStyleActivity")
        Tool.TERMUX_TASKER -> listOf(
            "com.termux.tasker.EditConfigurationActivity",
            "com.termux.tasker.FireReceiver",
            "com.termux.tasker.PluginResultsService"
        )
        Tool.TERMUX_WIDGET -> listOf(
            "com.termux.widget.TermuxWidgetProvider",
            "com.termux.widget.TermuxWidgetControlExecutorReceiver",
            "com.termux.widget.activities.TermuxWidgetActivity",
            "com.termux.widget.TermuxCreateShortcutActivity",
            "com.termux.widget.TermuxLaunchShortcutActivity",
            "com.termux.widget.TermuxWidgetService",
            "com.termux.widget.TermuxWidgetControlsProviderService"
        )
        Tool.TERMUX_BOOT -> listOf(
            "com.termux.app.BootReceiver",
            "com.termux.boot.BootActivity"
        )
    }

    /**
     * Enable or disable every Android component belonging to [tool] to match [enabled]. Should be
     * called whenever a tool's toggle changes so the feature is actually turned on/off at the
     * system level (not just in shared preferences).
     */
    fun applyComponentState(context: Context, tool: Tool, enabled: Boolean) {
        val pm = context.packageManager
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (className in componentsFor(tool)) {
            runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName(context.packageName, className),
                    state,
                    PackageManager.DONT_KILL_APP
                )
            }
        }

        if (tool == Tool.TERMUX_API) {
            if (enabled) TermuxApiBroadcastFix.applyAmWrapper(context)
            else TermuxApiBroadcastFix.removeAmWrapper()
        }
    }

    /**
     * Check whether [tool] is enabled. If not, show a dialog prompting the user to go to Settings
     * to enable it, and return `false`. Callers should abort the feature use when this returns
     * `false`.
     */
    fun requireEnabled(context: Context, tool: Tool): Boolean {
        if (isEnabled(context, tool)) return true
        showEnablePrompt(context, tool)
        return false
    }

    /** Show a dialog directing the user to Settings to enable [tool]. */
    @JvmStatic
    fun showEnablePrompt(context: Context, tool: Tool) {
        val toolName = context.getString(tool.nameRes)
        AlertDialog.Builder(context)
            .setTitle(R.string.feature_not_enabled_title)
            .setMessage(context.getString(R.string.feature_not_enabled_message, toolName))
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                openSettings(context)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Launch the app's main settings screen. */
    private fun openSettings(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // -------------------------------------------------------------------------
    // Standalone APK detection
    // -------------------------------------------------------------------------

    /**
     * Returns `true` if the official standalone Termux add-on APK for [tool] is currently
     * installed on the device (e.g. `com.termux.api`). When this is the case Settings should show
     * the integrated toggle as disabled and advise the user to prefer the built-in plugin.
     */
    @JvmStatic
    fun isStandaloneInstalled(context: Context, tool: Tool): Boolean {
        if (tool.standalonePackage == context.packageName) return false
        return runCatching {
            context.packageManager.getPackageInfo(tool.standalonePackage, 0)
        }.getOrNull() != null
    }

    /**
     * If the standalone official APK is installed, show a dialog telling the user the integrated
     * duplicate is not recommended, and suggest uninstalling the standalone APK and using the
     * one bundled into this app instead. Called when the user clicks a disabled switch.
     */
    @JvmStatic
    fun showStandaloneConflictPrompt(context: Context, tool: Tool) {
        val toolName = context.getString(tool.nameRes)
        AlertDialog.Builder(context)
            .setTitle(R.string.standalone_plugin_installed_title)
            .setMessage(context.getString(R.string.standalone_plugin_installed_message, toolName, tool.standalonePackage))
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
