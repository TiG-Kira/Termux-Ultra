package com.termux.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.ComponentActivity;
import androidx.core.view.WindowCompat;

import com.termux.app.terminal.shell.ComposeSessionManager;
import com.termux.app.terminal.shell.ComposeTerminalSettings;
import com.termux.app.compose.RiskConfirmManager;
import com.termux.app.compose.StopConfirmDialog;
import com.termux.app.compose.TermuxActivityBridge;
import com.termux.app.terminal.shell.TerminalSessionAdapter;
import com.termux.app.utils.CrashUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.settings.properties.TermuxAppSharedProperties;
import com.termux.app.TermuxInstaller;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.terminal.TerminalSession;

import java.util.List;

import com.termux.shared.activities.ReportActivity;

/**
 * Termux 终端控制台（libterminal 引擎）。
 *
 * 窗口内容由 {@link TermuxActivityBridge#setTerminalDetailContent} 以 Compose +
 * {@code TerminalDetailScreenCompose} 呈现，会话由 {@link ComposeSessionManager} 单例管理。
 */
public final class TermuxActivity extends ComponentActivity implements ServiceConnection {

    private static final String LOG_TAG = "TermuxActivity";

    /** Intent extra: when set to true, the activity was launched in fallback mode
     *  (miuix UI library unavailable). Kept for compatibility with FallbackHelper;
     *  since phase 3 the terminal UI is a single Compose screen regardless of this flag. */
    public static final String EXTRA_FALLBACK_MODE = "extra_fallback_mode";

    TermuxService mTermuxService;

    private TermuxAppSharedPreferences mPreferences;
    private TermuxAppSharedProperties mProperties;

    private boolean mIsVisible;
    private boolean isOnResumeAfterOnCreate = false;
    private boolean mIsInvalidState = false;

    private boolean mPendingTriggerStopService = false;
    private boolean mPendingNewTerminal = false;

    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        isOnResumeAfterOnCreate = true;

        Intent i = getIntent();
        if (i != null && i.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_STOP_SERVICE, false)) {
            mPendingTriggerStopService = true;
            i.removeExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_STOP_SERVICE);
        }

        CrashUtils.notifyAppCrashOnLastRun(this, LOG_TAG);
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        mProperties = new TermuxAppSharedProperties(this);
        super.onCreate(savedInstanceState);

        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) { mIsInvalidState = true; return; }

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        // Compose 内容需铺满去除装饰区（状态栏/导航栏），否则 topbar 收缩时
        // 状态栏区域透出固定黑色 windowBackground；与 adjustResize +
        // 容器级 imePadding 协同提供完整的 insets 语义（对齐上游 765af91）
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // 本页继承 application 主题 Theme.Termux.AppBase，该主题只设了 statusBarColor，
        // 导航栏色会落回 AppCompat 的不透明值，把 Compose 键盘底整个盖成一条黑带
        // （状态栏正常、导航栏发黑即源于此）。补齐透明并关掉 Android 10+ 的对比度自动补色，
        // 键盘底才能延展到导航栏（键盘的 background 画在 navigationBarsPadding() 之前）。
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        // 终端页由 Compose 呈现，会话由 ComposeSessionManager 单例管理
        TermuxActivityBridge.setTerminalDetailContent(
            this,
            () -> finishActivityIfNotFinishing()
        );

        startTermuxAndBindService();
    }

    private void startTermuxAndBindService() {
        Intent serviceIntent = new Intent(this, TermuxService.class);
        startService(serviceIntent);

        if (!bindService(serviceIntent, this, 0)) {
            Logger.logError(LOG_TAG, "bindService() failed");
        }

        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();
        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        registerTermuxActivityBroadcastReceiver();

        // Notification "end sessions" action routed us here; show warning dialog if needed.
        if (mPendingTriggerStopService) {
            mPendingTriggerStopService = false;
            StopConfirmDialog.start(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // Activity is already running (single-top / reorder-to-front), and the user
        // tapped the notification "end sessions" action again.
        if (intent != null && intent.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_STOP_SERVICE, false)) {
            intent.removeExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_STOP_SERVICE);
            if (mIsVisible) {
                StopConfirmDialog.start(this);
            } else {
                mPendingTriggerStopService = true;
            }
        }

        // 处理从 Quick Settings Tile 来的新建终端请求
        if (intent != null && intent.getBooleanExtra(com.termux.app.NewTerminalTileService.EXTRA_NEW_TERMINAL, false)) {
            intent.removeExtra(com.termux.app.NewTerminalTileService.EXTRA_NEW_TERMINAL);
            createNewComposeSessionIfBound();
        }

        // 第三方页面通过 Java 接口创建新会话时，正在运行中的终端控制台
        // 需同步切换到该会话（会话本体即 TerminalSessionAdapter）。
        String composeHandle = intent != null ? intent.getStringExtra("sessionHandle") : null;
        if (composeHandle != null) {
            TerminalSession target = findSessionByHandle(composeHandle);
            if (target instanceof TerminalSessionAdapter) {
                int sessionId = ((TerminalSessionAdapter) target).getSessionId();
                ComposeSessionManager.getInstance(this).switchTo(sessionId);
            }
        }

        // 处理风险确认结果（从主页返回时携带）
        handleRiskConfirmResult(intent);
    }

    private void createNewComposeSessionIfBound() {
        if (mTermuxService != null) {
            ComposeSessionManager.getInstance(this).createDefaultSession(true, false);
        } else {
            mPendingNewTerminal = true;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        // 检查是否有待处理的风险确认结果
        handleRiskConfirmResult(getIntent());
        handlePendingRiskConfirmFromPrefs();

        isOnResumeAfterOnCreate = false;

        // 处理待执行的新建终端请求
        if (mPendingNewTerminal) {
            mPendingNewTerminal = false;
            if (mTermuxService != null) {
                ComposeSessionManager.getInstance(this).createDefaultSession(true, false);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;
        unregisterTermuxActivityBroadcastReceiever();
    }

    @Override
    public void onBackPressed() {
        finishActivityIfNotFinishing();
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        if (mTermuxService.getTermuxSessionsSize() == 0) {
            // 会话由 ComposeSessionManager 管理，Java 侧会话列表为空属正常。
            // Compose 侧无任何会话时新建默认会话并立即启动（避免终端页刚进入就被关闭）。
            if (ComposeSessionManager.getInstance(this).getSessions().getValue().isEmpty()) {
                ComposeSessionManager.getInstance(this).createDefaultSession(true, false);
            }
        }

        // Activity 重建场景下处理 Quick Settings Tile 请求
        Intent i = getIntent();
        if (i != null && i.getBooleanExtra(com.termux.app.NewTerminalTileService.EXTRA_NEW_TERMINAL, false)) {
            i.removeExtra(com.termux.app.NewTerminalTileService.EXTRA_NEW_TERMINAL);
            ComposeSessionManager.getInstance(this).createDefaultSession(true, false);
        }

        // 通过 sessionHandle 进入指定终端会话
        String composeHandle = i != null ? i.getStringExtra("sessionHandle") : null;
        if (composeHandle != null) {
            TerminalSession target = findSessionByHandle(composeHandle);
            if (target instanceof TerminalSessionAdapter) {
                int sessionId = ((TerminalSessionAdapter) target).getSessionId();
                ComposeSessionManager.getInstance(this).switchTo(sessionId);
            }
        }

        // 处理从主页返回的风险确认结果
        handlePendingRiskConfirmFromPrefs();
        handleRiskConfirmResult(getIntent());
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");
        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }

    /**
     * 处理从主页返回的风险确认结果。
     * shell hook + SecuritySocketServer 接管命令拦截后此路径已废弃（no-op）。
     */
    private void handleRiskConfirmResult(Intent intent) {
        if (intent == null) return;

        String result = intent.getStringExtra(RiskConfirmManager.EXTRA_RISK_RESULT);
        String sessionHandle = intent.getStringExtra(RiskConfirmManager.EXTRA_SESSION_HANDLE);

        if (result == null || sessionHandle == null) return;

        TerminalSession session = findSessionByHandle(sessionHandle);
        if (session == null) {
            Logger.logWarn(LOG_TAG, "Risk confirm result: session not found for handle " + sessionHandle);
            return;
        }

        // shell hook 接管命令拦截后，Java/Kotlin 层的 confirm/deny 已删除，此路径为 no-op。
        Logger.logInfo(LOG_TAG, "Risk confirm result received (shell hook mode, no-op): " + result + ", handle=" + sessionHandle);

        intent.removeExtra(RiskConfirmManager.EXTRA_RISK_RESULT);
        intent.removeExtra(RiskConfirmManager.EXTRA_SESSION_HANDLE);
        RiskConfirmManager.INSTANCE.clearPendingState(this);
    }

    /**
     * 作为 Intent 传递的备用方案，防止 Activity 被系统回收后结果丢失。
     */
    private void handlePendingRiskConfirmFromPrefs() {
        if (mTermuxService == null) {
            Logger.logVerbose(LOG_TAG, "handlePendingRiskConfirmFromPrefs: mTermuxService is null, skip");
            return;
        }

        android.util.Pair<String, String> pendingResult = RiskConfirmManager.INSTANCE.consumePendingResult(this);

        if (pendingResult == null) {
            Logger.logVerbose(LOG_TAG, "handlePendingRiskConfirmFromPrefs: no pending result");
            return;
        }

        String sessionHandle = pendingResult.first;
        String result = pendingResult.second;
        Logger.logInfo(LOG_TAG, "handlePendingRiskConfirmFromPrefs: handle=" + sessionHandle + ", result=" + result);

        TerminalSession session = findSessionByHandle(sessionHandle);
        if (session == null) {
            Logger.logWarn(LOG_TAG, "handlePendingRiskConfirmFromPrefs: session not found for handle " + sessionHandle);
            return;
        }

        // shell hook 接管后此路径已废弃，不再调用 Java 层拦截 API
        Logger.logInfo(LOG_TAG, "handlePendingRiskConfirmFromPrefs: no-op (shell hook mode), result=" + result);
    }

    /**
     * For processes to access shared internal storage (/sdcard) we need this permission.
     */
    public boolean ensureStoragePermissionGranted() {
        if (PermissionUtils.checkPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return true;
        } else {
            Logger.logInfo(LOG_TAG, "Storage permission not granted, requesting permission.");
            PermissionUtils.requestPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE, PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION);
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Logger.logInfo(LOG_TAG, "Storage permission granted by user on request.");
            TermuxInstaller.setupStorageSymlinks(this);
        } else {
            Logger.logInfo(LOG_TAG, "Storage permission denied by user on request.");
        }
    }

    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTermuxService == null) return null;
        com.awkoo.libterminal.engine.TerminalSession current = ComposeSessionManager.getInstance(this).getCurrentSession();
        if (current == null) return null;
        for (TermuxSession termuxSession : mTermuxService.getTermuxSessions()) {
            TerminalSession terminalSession = termuxSession.getTerminalSession();
            if (terminalSession instanceof TerminalSessionAdapter
                && ((TerminalSessionAdapter) terminalSession).getSessionId() == current.getId()) {
                return terminalSession;
            }
        }
        return null;
    }

    @Nullable
    private TerminalSession findSessionByHandle(String handle) {
        if (mTermuxService == null || handle == null) return null;
        List<TermuxSession> sessions = mTermuxService.getTermuxSessions();
        for (TermuxSession session : sessions) {
            if (handle.equals(session.getTerminalSession().mHandle)) {
                return session.getTerminalSession();
            }
        }
        return null;
    }

    public void finishActivityIfNotFinishing() {
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a snackbar and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        com.termux.app.utils.SnackbarHelper.INSTANCE.show(
            this,
            text,
            com.termux.app.utils.SnackbarHelper.INSTANCE.getDuration(longDuration),
            findViewById(android.R.id.content)
        );
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public static void updateTermuxActivityStyling(Context context) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        context.sendBroadcast(stylingIntent);
    }

    public static void startTermuxActivity(@NonNull final Context context) {
        context.startActivity(newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
        }
    }

    private void unregisterTermuxActivityBroadcastReceiever() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceieverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceieverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        if (ensureStoragePermissionGranted())
                            TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling();
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling() {
        // Styling 页/termux-reload 写盘后，终端视图直接从
        // ~/.termux/colors.properties 与 font.ttf 重新加载（与 Java 模式共用主题，双向同步）
        ComposeTerminalSettings.INSTANCE.init(this);
        ComposeTerminalSettings.INSTANCE.reloadFromStylingDisk();

        if (mProperties != null) {
            mProperties.loadTermuxPropertiesFromDisk();
        }

        if (mTermuxService != null)
            mTermuxService.setTerminalTranscriptRows();
    }

}