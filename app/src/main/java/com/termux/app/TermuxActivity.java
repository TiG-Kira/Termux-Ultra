package com.termux.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import com.termux.app.compose.IntegratedTools;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import java.util.List;

import com.termux.shared.termux.TermuxConstants;
import com.termux.R;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.packages.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalSessionClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView;
import com.termux.app.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.app.utils.CrashUtils;
import com.termux.shared.shell.TermuxSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import androidx.activity.ComponentActivity;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends ComponentActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionClient mTermuxTerminalSessionClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app shared properties manager, loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean isOnResumeAfterOnCreate = false;

    /**
     * True if an ACTION_RUN intent has already been processed to create a session.
     * Used to prevent duplicate session creation when Activity is recreated
     * (e.g., configuration change) with the same ACTION_RUN intent.
     */
    private boolean mActionRunHandled = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private int mTerminalToolbarDefaultHeight;

    private ComposeView mTerminalToolbar;
    private String mCurrentTitle = "";

    /** True if activity was launched (or is being resumed) from the notification
     *  "end sessions" action. When true, we show a data-loss warning dialog if VM/container
     *  processes are running, and then instruct TermuxService to force-stop sessions. */
    private boolean mPendingTriggerStopService = false;

    /** True if activity was launched from Quick Settings Tile to create a new terminal session. */
    private boolean mPendingNewTerminal = false;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";

    private static final String LOG_TAG = "TermuxActivity";

    /** Intent extra: when set to true, the activity was launched in fallback mode
     *  (miuix UI library unavailable). This skips miuix-dependent UI and makes
     *  the back button return to the launcher instead of finishing. */
    public static final String EXTRA_FALLBACK_MODE = "extra_fallback_mode";

    /**
     * Callback interface for requesting a context menu (used by Compose mode to show miuix-styled menu).
     */
    public interface OnContextMenuRequestedListener {
        void onContextMenuRequested();
    }

    private boolean mIsFallbackMode = false;
    private OnContextMenuRequestedListener mContextMenuListener;

    @Override
    public void onCreate(Bundle savedInstanceState) {

        Logger.logDebug(LOG_TAG, "onCreate");
        isOnResumeAfterOnCreate = true;

        if (savedInstanceState != null) {
            mActionRunHandled = savedInstanceState.getBoolean("mActionRunHandled", false);
        }

        mIsFallbackMode = getIntent() != null && getIntent().getBooleanExtra(EXTRA_FALLBACK_MODE, false);

        Intent i = getIntent();
        if (i != null && i.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_STOP_SERVICE, false)) {
            mPendingTriggerStopService = true;
            i.removeExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_STOP_SERVICE);
        }

        CrashUtils.notifyAppCrashOnLastRun(this, LOG_TAG);
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        mProperties = new TermuxAppSharedProperties(this);
        setActivityTheme();
        super.onCreate(savedInstanceState);

        if (mIsFallbackMode) {
            setContentView(R.layout.activity_termux);
            mPreferences = TermuxAppSharedPreferences.build(this, true);
            if (mPreferences == null) { mIsInvalidState = true; return; }
            setMargins();
            mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
            mTermuxActivityRootView.setActivity(this);
            mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
            mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());
            View content = findViewById(android.R.id.content);
            if (content != null) {
                content.setOnApplyWindowInsetsListener((v, insets) -> {
                    mNavBarHeight = insets.getSystemWindowInsetBottom();
                    return insets;
                });
            }
            if (mProperties.isUsingFullScreen()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            setDrawerTheme();
            getDrawer().setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT);
            setTermuxTerminalViewAndClients();
            setTerminalToolbarView(savedInstanceState);
            setNewSessionButtonView();
            setToggleKeyboardView();
            setTerminalToolbar();
            registerForContextMenu(mTerminalView);
            startTermuxAndBindService();
            return;
        }

        // === Compose mode ===
        // Step 1: Inflate XML into a detached container to extract legacy views
        // that existing code (TermuxTerminalViewClient etc.) still accesses via
        // findViewById. The detached views are cached.
        preInflateLegacyViews();

        // Step 2: Initialize preferences and terminal clients
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) { mIsInvalidState = true; return; }

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();
        setTerminalToolbarView(savedInstanceState);

        // Step 3: Replace window content with Compose-based TerminalDetailScreen.
        // TerminalView has already been extracted and detached; it will be re-hosted
        // inside Compose via AndroidView. KiTerminalTheme wraps it with MiuixTheme.
        com.termux.app.compose.TermuxActivityBridge.setTerminalDetailContent(
            this,
            mTerminalView,
            () -> finishActivityIfNotFinishing()
        );

        registerForContextMenu(mTerminalView);
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

    // Cached legacy views — extracted from XML in preInflateLegacyViews()
    // and returned via the findViewById override below.
    private View mLegacyRootView;
    private DrawerLayout mLegacyDrawerLayout;
    private ViewPager mLegacyToolbarPager;
    private LinearLayout mLegacyLeftDrawer;
    private ListView mLegacySessionsList;
    private EditText mLegacyTextInput;
    private ImageButton mLegacySettingsButton;
    private View mLegacyNewSessionButton;
    private View mLegacyToggleKeyboardButton;
    private View mLegacyComposeToolbar;
    private View mLegacyRootRelativeLayout;

    /**
     * Inflate {@code activity_termux.xml} into a detached container so we
     * can extract and cache every view that legacy code still needs to find
     * via {@link #findViewById(int)}. The cached views are never attached to
     * the window — they exist purely as data sources for legacy APIs.
     */
    private void preInflateLegacyViews() {
        android.view.LayoutInflater inflater = (android.view.LayoutInflater)
            getSystemService(LAYOUT_INFLATER_SERVICE);
        if (inflater == null) return;

        android.widget.FrameLayout detachedRoot = new android.widget.FrameLayout(this);
        detachedRoot.setLayoutParams(new android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mLegacyRootView = inflater.inflate(R.layout.activity_termux, detachedRoot, false);

        if (mLegacyRootView instanceof android.view.ViewGroup) {
            android.view.ViewGroup root = (android.view.ViewGroup) mLegacyRootView;

            // Also set mTermuxActivityRootView (used by legacy clients via getTermuxActivityRootView())
            if (mLegacyRootView instanceof TermuxActivityRootView) {
                mTermuxActivityRootView = (TermuxActivityRootView) mLegacyRootView;
                mTermuxActivityRootView.setActivity(this);
            }

            mLegacyComposeToolbar = root.findViewById(R.id.terminal_toolbar);

            // Extract TerminalView — must be detached before being re-hosted by
            // TerminalDetailScreen via AndroidView.
            View terminalView = root.findViewById(R.id.terminal_view);
            if (terminalView instanceof TerminalView) {
                mTerminalView = (TerminalView) terminalView;
                if (terminalView.getParent() instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) terminalView.getParent()).removeView(terminalView);
                }
            }

            View bottomSpace = root.findViewById(R.id.activity_termux_bottom_space_view);
            if (bottomSpace != null) mTermuxActivityBottomSpaceView = bottomSpace;

            mLegacyRootRelativeLayout = root.findViewById(R.id.activity_termux_root_relative_layout);

            View innerRoot = mLegacyRootRelativeLayout;
            if (innerRoot instanceof android.view.ViewGroup) {
                android.view.ViewGroup inner = (android.view.ViewGroup) innerRoot;

                View drawer = inner.findViewById(R.id.drawer_layout);
                if (drawer instanceof DrawerLayout) mLegacyDrawerLayout = (DrawerLayout) drawer;

                View pager = inner.findViewById(R.id.terminal_toolbar_view_pager);
                if (pager instanceof ViewPager) mLegacyToolbarPager = (ViewPager) pager;

                View leftDrawer = inner.findViewById(R.id.left_drawer);
                if (leftDrawer instanceof LinearLayout) mLegacyLeftDrawer = (LinearLayout) leftDrawer;

                View sessionsList = inner.findViewById(R.id.terminal_sessions_list);
                if (sessionsList instanceof ListView) mLegacySessionsList = (ListView) sessionsList;

                View textInput = pager != null ? pager.findViewById(R.id.terminal_toolbar_text_input) : null;
                if (textInput instanceof EditText) mLegacyTextInput = (EditText) textInput;

                View settingsBtn = inner.findViewById(R.id.settings_button);
                if (settingsBtn instanceof ImageButton) mLegacySettingsButton = (ImageButton) settingsBtn;

                mLegacyNewSessionButton = inner.findViewById(R.id.new_session_button);
                mLegacyToggleKeyboardButton = inner.findViewById(R.id.toggle_keyboard_button);
            }
        }
    }

    /**
     * Override findViewById to return cached legacy views when Compose is
     * the primary UI. This allows TermuxTerminalViewClient, toolbar code
     * and other legacy components to keep working after setContent().
     */
    @Override
    public <T extends View> T findViewById(int id) {
        T v = super.findViewById(id);
        if (v != null) return v;

        if (id == R.id.activity_termux_root_view) return (T) mLegacyRootView;
        if (id == R.id.activity_termux_root_relative_layout) return (T) mLegacyRootRelativeLayout;
        if (id == R.id.drawer_layout) return (T) mLegacyDrawerLayout;
        if (id == R.id.terminal_toolbar_view_pager) return (T) mLegacyToolbarPager;
        if (id == R.id.terminal_toolbar_text_input) return (T) mLegacyTextInput;
        if (id == R.id.left_drawer) return (T) mLegacyLeftDrawer;
        if (id == R.id.terminal_sessions_list) return (T) mLegacySessionsList;
        if (id == R.id.settings_button) return (T) mLegacySettingsButton;
        if (id == R.id.new_session_button) return (T) mLegacyNewSessionButton;
        if (id == R.id.toggle_keyboard_button) return (T) mLegacyToggleKeyboardButton;
        if (id == R.id.terminal_toolbar) return (T) mLegacyComposeToolbar;
        if (id == R.id.terminal_view) return (T) mTerminalView;

        return null;
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();

        // Notification "end sessions" action routed us here; show warning dialog if needed.
        if (mPendingTriggerStopService) {
            mPendingTriggerStopService = false;
            com.termux.app.compose.StopConfirmDialog.start(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // Activity is already running (single-top / reorder-to-front), and the user
        // tapped the notification "end sessions" action again.
        if (intent != null && intent.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_STOP_SERVICE, false)) {
            // Clear extra right after reading
            intent.removeExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_STOP_SERVICE);
            if (mIsVisible) {
                com.termux.app.compose.StopConfirmDialog.start(this);
            } else {
                // Activity not yet visible; defer to onStart
                mPendingTriggerStopService = true;
            }
        }

        // 处理从 Quick Settings Tile 来的新建终端请求
        if (intent != null && intent.getBooleanExtra(com.termux.app.NewTerminalTileService.EXTRA_NEW_TERMINAL, false)) {
            intent.removeExtra(com.termux.app.NewTerminalTileService.EXTRA_NEW_TERMINAL);
            if (mTermuxTerminalSessionClient != null) {
                mTermuxTerminalSessionClient.addNewSession(false, null);
            } else {
                mPendingNewTerminal = true;
            }
        }

        // Compose 模式：第三方页面通过 Java 接口创建新会话时，正在运行中的 Compose 终端
        // 需同步切换到该会话（ComposeSessionManager.switchTo 由桥接层在主线程执行）。
        if (com.termux.app.compose.TerminalRuntimeCore.isComposeMode(this)) {
            String composeHandle = intent != null ? intent.getStringExtra("sessionHandle") : null;
            if (composeHandle != null) {
                com.termux.app.compose.ComposeSessionBridge.INSTANCE.switchToSessionByHandle(this, composeHandle);
            }
        }

        // 处理风险确认结果（从主页返回时携带）
        handleRiskConfirmResult(intent);
    }

    /**
     * 处理从主页返回的风险确认结果。
     * 根据结果对终端会话执行确认或拒绝操作。
     */
    private void handleRiskConfirmResult(Intent intent) {
        if (intent == null) return;

        String result = intent.getStringExtra(com.termux.app.compose.RiskConfirmManager.EXTRA_RISK_RESULT);
        String sessionHandle = intent.getStringExtra(com.termux.app.compose.RiskConfirmManager.EXTRA_SESSION_HANDLE);

        if (result == null || sessionHandle == null) return;

        TerminalSession session = findSessionByHandle(sessionHandle);
        if (session == null) {
            Logger.logWarn(LOG_TAG, "Risk confirm result: session not found for handle " + sessionHandle);
            return;
        }

        if (com.termux.app.compose.RiskConfirmManager.RESULT_CONFIRMED.equals(result)) {
            session.confirmPendingCommand();
            Logger.logInfo(LOG_TAG, "Risk confirm: command confirmed, handle=" + sessionHandle);
        } else if (com.termux.app.compose.RiskConfirmManager.RESULT_DENIED.equals(result)) {
            session.denyPendingCommand();
            Logger.logInfo(LOG_TAG, "Risk confirm: command denied, handle=" + sessionHandle);
        }

        // 成功处理后清除状态
        intent.removeExtra(com.termux.app.compose.RiskConfirmManager.EXTRA_RISK_RESULT);
        intent.removeExtra(com.termux.app.compose.RiskConfirmManager.EXTRA_SESSION_HANDLE);
        com.termux.app.compose.RiskConfirmManager.INSTANCE.clearPendingState(this);
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        updateTerminalToolbarTitle();

        // 检查是否有待处理的风险确认结果
        // 1. 先检查 Intent extras（onNewIntent 传递的）
        handleRiskConfirmResult(getIntent());
        // 2. 再检查 SharedPreferences 备份
        handlePendingRiskConfirmFromPrefs();

        isOnResumeAfterOnCreate = false;
    
        // 处理待执行的新建终端请求
        if (mPendingNewTerminal) {
            mPendingNewTerminal = false;
            if (mTermuxTerminalSessionClient != null) {
                mTermuxTerminalSessionClient.addNewSession(false, null);
            }
        }

    }

    /**
     * 从 SharedPreferences 检查并处理待确认的风险命令结果。
     * 作为 Intent 传递的备用方案，防止 Activity 被系统回收后结果丢失。
     */
    private void handlePendingRiskConfirmFromPrefs() {
        if (mTermuxService == null) {
            Logger.logVerbose(LOG_TAG, "handlePendingRiskConfirmFromPrefs: mTermuxService is null, skip");
            return;
        }

        android.util.Pair<String, String> pendingResult = com.termux.app.compose.RiskConfirmManager.INSTANCE
            .consumePendingResult(this);

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

        if (com.termux.app.compose.RiskConfirmManager.RESULT_CONFIRMED.equals(result)) {
            session.confirmPendingCommand();
            Logger.logInfo(LOG_TAG, "Risk confirm (from prefs): command confirmed");
        } else if (com.termux.app.compose.RiskConfirmManager.RESULT_DENIED.equals(result)) {
            session.denyPendingCommand();
            Logger.logInfo(LOG_TAG, "Risk confirm (from prefs): command denied");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiever();
        getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putBoolean("mActionRunHandled", mActionRunHandled);
        saveTerminalToolbarTextInput(savedInstanceState);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {

        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();

        if (mTermuxService.getTermuxSessionsSize() == 0) {
            if (com.termux.app.compose.TerminalRuntimeCore.isComposeMode(this)) {
                // Compose 模式：会话由 ComposeSessionManager 管理，Java 侧会话列表为空属正常。
                // 不能走 Java 版的 finishActivityIfNotFinishing()（否则 Compose 终端页刚进入
                // 就被关闭，导致无法进入终端）。
                // 效仿 Java 版空会话自动 addNewSession 开启服务的策略：
                // Compose 侧无任何会话时新建默认会话并立即启动，服务随前台通知保活。
                if (com.termux.app.compose.terminal.ComposeSessionManager.getInstance(this)
                        .getSessions().getValue().isEmpty()) {
                    com.termux.app.compose.terminal.ComposeSessionManager.getInstance(this)
                        .createDefaultSession(true, false);
                }
            } else if (mIsVisible) {
                Intent i = getIntent();
                boolean tileRequest = i != null && i.getBooleanExtra(com.termux.app.NewTerminalTileService.EXTRA_NEW_TERMINAL, false);
                if (i != null && Intent.ACTION_RUN.equals(i.getAction()) && !mActionRunHandled) {
                    mActionRunHandled = true;
                    TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                        if (mTermuxService == null) return;
                        try {
                            boolean isFailSafe = i.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                            mTermuxTerminalSessionClient.addNewSession(isFailSafe, null);
                        } catch (WindowManager.BadTokenException e) {
                        }
                    });
                } else if (tileRequest) {
                    // Quick Settings Tile 请求：无条件创建首个 session
                    mTermuxTerminalSessionClient.addNewSession(false, null);
                } else if (mIsFallbackMode) {
                    // Fallback mode: auto-create a session so the terminal view isn't empty
                    mTermuxTerminalSessionClient.addNewSession(false, null);
                } else {
                    finishActivityIfNotFinishing();
                }
            } else if (!mIsFallbackMode) {
                // 即使不可见，如果是 Tile 请求也应该创建 session（让后台服务有活干）
                Intent i = getIntent();
                boolean tileRequest = i != null && i.getBooleanExtra(com.termux.app.NewTerminalTileService.EXTRA_NEW_TERMINAL, false);
                if (!tileRequest) {
                    finishActivityIfNotFinishing();
                }
            }
        } else {
            Intent i = getIntent();
            if (i != null && Intent.ACTION_RUN.equals(i.getAction()) && !mActionRunHandled) {
                mActionRunHandled = true;
                boolean isFailSafe = i.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionClient.addNewSession(isFailSafe, null);
            } else if (i != null && i.hasExtra("sessionHandle")) {
                String sessionHandle = i.getStringExtra("sessionHandle");
                TerminalSession targetSession = findSessionByHandle(sessionHandle);
                if (targetSession != null) {
                    mTermuxTerminalSessionClient.setCurrentSession(targetSession);
                } else {
                    mTermuxTerminalSessionClient.setCurrentSession(mTermuxTerminalSessionClient.getCurrentStoredSessionOrLast());
                }
            } else {
                mTermuxTerminalSessionClient.setCurrentSession(mTermuxTerminalSessionClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionClient);

        // 处理从主页返回的风险确认结果（Activity 重建场景下 onNewIntent 不会被调用，
        // 但 onServiceConnected 一定会在服务绑定后触发，此时 mTermuxService 已就绪）
        handlePendingRiskConfirmFromPrefs();

        // 同时检查 Intent extras（如果是通过 Intent 跳转回来的）
        handleRiskConfirmResult(getIntent());
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }





    private void setActivityTheme() {
        if (mProperties.isUsingBlackUI()) {
            this.setTheme(R.style.Theme_Termux_Black);
        } else {
            this.setTheme(R.style.Theme_Termux);
        }
    }

    private void setDrawerTheme() {
        if (mProperties.isUsingBlackUI()) {
            findViewById(R.id.left_drawer).setBackgroundColor(ContextCompat.getColor(this,
                android.R.color.background_dark));
            ((ImageButton) findViewById(R.id.settings_button)).setColorFilter(Color.WHITE);
        }
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        TermuxActivityRootView root = getTermuxActivityRootView();
        if (root != null) {
            root.getViewTreeObserver().addOnGlobalLayoutListener(root);
        }
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionClient = new TermuxTerminalSessionClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionClient);

        // Set termux terminal view
        if (mTerminalView == null) {
            mTerminalView = (TerminalView) findViewById(R.id.terminal_view);
        }
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        if (layoutParams != null) {
            mTerminalToolbarDefaultHeight = layoutParams.height;
        }

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = (int) Math.round(mTerminalToolbarDefaultHeight *
            (mProperties.getExtraKeysInfo() == null ? 0 : mProperties.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView =  findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }

    private void setTerminalToolbar() {
        mTerminalToolbar = findViewById(R.id.terminal_toolbar);

        // In fallback mode (miuix unavailable), skip setting up the miuix-based toolbar
        // to avoid crashes. The terminal view itself will still work.
        if (mIsFallbackMode) {
            return;
        }

        mTerminalToolbar.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed.INSTANCE
        );
        com.termux.app.compose.TerminalTopBarKt.setTerminalTopBarContent(
            mTerminalToolbar,
            () -> {
                Intent intent = new Intent(TermuxActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
                return kotlin.Unit.INSTANCE;
            },
            () -> {
                int sessionCount = mTermuxService.getTermuxSessions().size();
                String sessionName = com.termux.app.LocaleHelper.isChinese(this)
                    ? "会话 " + (sessionCount + 1)
                    : "Session " + (sessionCount + 1);
                mTermuxTerminalSessionClient.addNewSession(false, sessionName);
                updateTerminalToolbarTitle();
                return kotlin.Unit.INSTANCE;
            },
            () -> {
                TerminalSession currentSession = getCurrentSession();
                if (currentSession != null) {
                    List<TermuxSession> sessions = mTermuxService.getTermuxSessions();
                    int currentIndex = -1;
                    for (int i = 0; i < sessions.size(); i++) {
                        if (sessions.get(i).getTerminalSession() == currentSession) {
                            currentIndex = i;
                            break;
                        }
                    }
                    TermuxSession targetTermuxSession = null;
                    if (sessions.size() > 1 && currentIndex >= 0) {
                        if (currentIndex > 0) {
                            targetTermuxSession = sessions.get(currentIndex - 1);
                        } else {
                            targetTermuxSession = sessions.get(currentIndex + 1);
                        }
                    }
                    String sessionName = currentSession.mSessionName;
                    if (sessionName == null || sessionName.isEmpty()) {
                        sessionName = getString(R.string.terminal);
                    }
                    showToast(sessionName + " 已停止，返回代码: 137", true);
                    mTermuxService.removeTermuxSession(currentSession);
                    if (targetTermuxSession != null) {
                        mTermuxTerminalSessionClient.setCurrentSession(targetTermuxSession.getTerminalSession());
                    }
                }
                if (mTermuxService.getTermuxSessions().isEmpty()) {
                    finish();
                } else {
                    updateTerminalToolbarTitle();
                }
                return kotlin.Unit.INSTANCE;
            },
            () -> {
                mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
                return kotlin.Unit.INSTANCE;
            },
            () -> {
                if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
                    getDrawer().closeDrawer(Gravity.LEFT);
                } else {
                    getDrawer().openDrawer(Gravity.LEFT);
                }
                return kotlin.Unit.INSTANCE;
            }
        );
        updateTerminalToolbarTitle();
        setupKeyboardVisibilityListener();
        setupTopBarIconColor();
    }

    private void setupTopBarIconColor() {
        int backgroundColor = android.graphics.Color.BLACK;
        try {
            android.graphics.drawable.Drawable background = mTerminalToolbar.getBackground();
            if (background instanceof android.graphics.drawable.ColorDrawable) {
                backgroundColor = ((android.graphics.drawable.ColorDrawable) background).getColor();
            }
        } catch (Exception e) {
        }
        com.termux.app.compose.TerminalTopBarKt.updateIconColorForBackground(backgroundColor);
    }

    private void setupKeyboardVisibilityListener() {
        final View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int heightDiff = rootView.getRootView().getHeight() - rootView.getHeight();
            boolean isKeyboardVisible = heightDiff > 200;
            com.termux.app.compose.TerminalTopBarKt.updateKeyboardVisibility(isKeyboardVisible);
        });
    }

    public void updateTerminalToolbarTitle() {
        if (mTermuxService != null) {
            TerminalSession currentSession = getCurrentSession();
            if (currentSession != null) {
                String sessionTitle = currentSession.mSessionName;
                if (sessionTitle != null && !sessionTitle.isEmpty()) {
                    mCurrentTitle = sessionTitle;
                } else {
                    List<TermuxSession> sessions = mTermuxService.getTermuxSessions();
                    int sessionIndex = -1;
                    for (int i = 0; i < sessions.size(); i++) {
                        if (sessions.get(i).getTerminalSession() == currentSession) {
                            sessionIndex = i + 1;
                            break;
                        }
                    }
                    if (sessionIndex > 0) {
                        mCurrentTitle = com.termux.app.LocaleHelper.isChinese(this)
                            ? "会话 " + sessionIndex
                            : "Session " + sessionIndex;
                    } else {
                        mCurrentTitle = getString(R.string.terminal);
                    }
                }
            } else {
                mCurrentTitle = getString(R.string.terminal);
            }
            com.termux.app.compose.TerminalTopBarKt.updateTerminalTitle(mCurrentTitle);
        }
    }


    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else if (mIsFallbackMode) {
            // In fallback mode, pressing back returns to the launcher/desktop
            // instead of finishing the activity (which would exit the app).
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
        } else {
            finishActivityIfNotFinishing();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
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



    /**
     * Set listener to receive context menu requests (for Compose mode to show miuix-styled menu).
     */
    public void setOnContextMenuRequestedListener(OnContextMenuRequestedListener listener) {
        mContextMenuListener = listener;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        // In Compose mode (non-fallback), delegate to Compose for miuix-styled context menu
        if (!mIsFallbackMode && mContextMenuListener != null) {
            TerminalSession currentSession = getCurrentSession();
            if (currentSession == null) return;
            mContextMenuListener.onContextMenuRequested();
            return; // Don't create system Material menu
        }

        // Fallback mode: use standard Material context menu
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                startActivity(new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionClient != null)
                mTermuxTerminalSessionClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(getPackageName(), "com.termux.app.activities.TermuxStylingActivity");
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
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



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    /**
     * Property accessor used by Compose TerminalToolbar composable to obtain
     * the shared ViewPager instance that is wired up with PageAdapter and
     * OnPageChangeListener in {@link #setTerminalToolbarView(Bundle)}.
     */
    public ViewPager getTerminalToolbarViewPagerInstance() {
        return getTerminalToolbarViewPager();
    }

    /**
     * Accessor for the default terminal toolbar height, used by Compose.
     */
    public int getTerminalToolbarDefaultHeightValue() {
        return mTerminalToolbarDefaultHeight;
    }

    /**
     * Update the cached text input EditText after Compose mounts the
     * shared toolbar ViewPager. Called by TerminalToolbar composable via
     * {@link #updateCachedToolbarTextInput(EditText)}.
     */
    public void updateCachedToolbarTextInput(EditText editText) {
        mLegacyTextInput = editText;
    }

    public boolean isTerminalViewSelected() {
        ViewPager pager = getTerminalToolbarViewPager();
        return pager != null && pager.getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        ViewPager pager = getTerminalToolbarViewPager();
        return pager != null && pager.getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
        updateTerminalToolbarTitle();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return isOnResumeAfterOnCreate;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
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

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        context.sendBroadcast(stylingIntent);
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
        // Compose 模式：Styling 页/termux-reload 写盘后，Compose 终端直接从
        // ~/.termux/colors.properties 与 font.ttf 重新加载（与 Java 模式共用主题，双向同步）
        if (com.termux.app.compose.TerminalRuntimeCore.isComposeMode(this)) {
            com.termux.app.compose.terminal.ComposeTerminalSettings.INSTANCE.init(this);
            com.termux.app.compose.terminal.ComposeTerminalSettings.INSTANCE.reloadFromStylingDisk();
        }

        if (mProperties!= null) {
            mProperties.loadTermuxPropertiesFromDisk();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mProperties.getExtraKeysInfo());
            }
        }

        setMargins();
        setTerminalToolbarHeight();

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onReload();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReload();

        if (mTermuxService != null)
            mTermuxService.setTerminalTranscriptRows();

        // To change the activity and drawer theme, activity needs to be recreated.
        // But this will destroy the activity, and will call the onCreate() again.
        // We need to investigate if enabling this is wise, since all stored variables and
        // views will be destroyed and bindService() will be called again. Extra keys input
        // text will we restored since that has already been implemented. Terminal sessions
        // and transcripts are also already preserved. Theme does change properly too.
        // TermuxActivity.this.recreate();
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        context.startActivity(newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
