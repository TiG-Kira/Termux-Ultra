package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.AlarmManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.settings.properties.TermuxAppSharedProperties;
import com.termux.app.terminal.TermuxTerminalSessionClient;
import com.termux.app.receiver.MemoryBroadcastReceiver;
import com.termux.app.utils.DomesticOSDetector;
import com.termux.app.utils.PluginUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.models.errors.Errno;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.TermuxShellEnvironmentClient;
import com.termux.shared.shell.TermuxShellUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.shell.TermuxSession;
import com.termux.shared.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.packages.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.models.ExecutionCommand;
import com.termux.shared.shell.TermuxTask;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A service holding a list of {@link TermuxSession} in {@link #mTermuxSessions} and background {@link TermuxTask}
 * in {@link #mTermuxTasks}, showing a foreground notification while running so that it is not terminated.
 * The user interacts with the session through {@link TermuxActivity}, but this service may outlive
 * the activity when the user or the system disposes of the activity. In that case the user may
 * restart {@link TermuxActivity} later to yet again access the sessions.
 * <p/>
 * In order to keep both terminal sessions and spawned processes (who may outlive the terminal sessions) alive as long
 * as wanted by the user this service is a foreground service, {@link Service#startForeground(int, Notification)}.
 * <p/>
 * Optionally may hold a wake and a wifi lock, in which case that is shown in the notification - see
 * {@link #buildNotification()}.
 */
public final class TermuxService extends Service implements TermuxTask.TermuxTaskClient, TermuxSession.TermuxSessionClient {

    private static int EXECUTION_ID = 1000;

    /** Service process start time (elapsedRealtime ms) for uptime display. */
    public static long serviceStartTimeMs = 0;

    /** This service is only bound from inside the same process and never uses IPC. */
    public class LocalBinder extends Binder {
        public final TermuxService service = TermuxService.this;
    }

    private final IBinder mBinder = new LocalBinder();

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /**
     * The foreground TermuxSessions which this service manages.
     * Note that this list is observed by {@link TermuxActivity#mTermuxSessionListViewController},
     * so any changes must be made on the UI thread and followed by a call to
     * {@link ArrayAdapter#notifyDataSetChanged()} }.
     */
    final List<TermuxSession> mTermuxSessions = new ArrayList<>();

    /**
     * The background TermuxTasks which this service manages.
     */
    final List<TermuxTask> mTermuxTasks = new ArrayList<>();

    /**
     * The pending plugin ExecutionCommands that have yet to be processed by this service.
     */
    final List<ExecutionCommand> mPendingPluginExecutionCommands = new ArrayList<>();

    /** The full implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that holds activity references for activity related functions.
     * Note that the service may often outlive the activity, so need to clear this reference.
     */
    TermuxTerminalSessionClient mTermuxTerminalSessionClient;

    /** The basic implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that does not hold activity references.
     */
    final TermuxTerminalSessionClientBase mTermuxTerminalSessionClientBase = new TermuxTerminalSessionClientBase();

    /** The wake lock and wifi lock are always acquired and released together. */
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    /** If the user has executed the {@link TERMUX_SERVICE#ACTION_STOP_SERVICE} intent. */
    boolean mWantsToStop = false;

    /** If "end all sessions" has been clicked and all sessions/tasks were killed.
     *  While true and no sessions are running, notification is downgraded to a normal
     *  (non-LiveUpdate) format with text "终端会话已清理(已无会话运行)。".
     *  Reset to false when a new session/task is created. */
    boolean mAllSessionsCleared = false;

    public Integer mTerminalTranscriptRows;

    private static final String LOG_TAG = "TermuxService";

    private MemoryBroadcastReceiver mMemoryBroadcastReceiver;
    private boolean mIsMemoryWarningActive = false;
    private boolean mIsMemoryKillActive = false;
    private boolean mAreSessionsFrozen = false;
    private String mKilledSessionName = null;

    /**
     * 已结束（被杀死/自然退出）的会话信息队列。
     *
     * 当 [onTermuxSessionExited] 触发时，会话会立即从 [mTermuxSessions] 移除，
     * UI 层来不及捕获退出代码。这里在移除前把会话名 + 退出代码 + 时间戳记录下来，
     * 供 TerminalListScreen 拉取并以"死亡卡片"形式展示（红色标题 + 退出代码小字），
     * 直到用户手动消除。
     *
     * 死亡会话不计入 [mTermuxSessions]，因此 LiveUpdate 通知中的会话数量自动排除。
     */
    private final List<DeadSessionInfo> mDeadSessionInfos = new ArrayList<>();

    /** 已结束会话的信息载体（name + exitCode + exitedAt）。 */
    public static class DeadSessionInfo {
        public final String sessionName;
        public final int exitCode;
        public final long exitedAt;

        public DeadSessionInfo(String sessionName, int exitCode, long exitedAt) {
            this.sessionName = sessionName;
            this.exitCode = exitCode;
            this.exitedAt = exitedAt;
        }
    }

    private Handler mMemoryCheckHandler;
    private Runnable mMemoryCheckRunnable;
    private static final long MEMORY_CHECK_INTERVAL = 5000;

    private static final String FROZEN_SESSIONS_DIR = "frozen_sessions";

    @Override
    public void onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate");
        serviceStartTimeMs = android.os.SystemClock.elapsedRealtime();
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
        runStartForeground();
        registerMemoryBroadcastReceiver();

        // 预加载增强防护缓存，避免首次命令读取 SharedPreferences 造成延迟
        com.termux.app.compose.RiskConfirmManager.INSTANCE.preloadCache(this);

        // 按运行核心设置项同步镜像写转发状态（Kotlin+Compose 时启用，Java+NDK 时禁用）
        com.termux.terminal.TerminalSession.setComposeForwardingEnabled(
            com.termux.app.compose.TerminalRuntimeCore.isComposeMode(this));

        // Compose 会话创建/关闭时刷新前台通知，保证 LiveUpdate 通知中的会话数量
        // 对 Compose 直建的会话（主页/终端页新建）也保持准确
        com.termux.app.compose.terminal.ComposeSessionManager.setOnSessionsChanged(() -> {
            try {
                updateNotification();
            } catch (Throwable t) {
                Logger.logDebug(LOG_TAG, "Failed to update notification on Compose session change: " + t.getMessage());
            }
            return kotlin.Unit.INSTANCE;
        });

        // 注册终端输入拦截器，用于检测高危命令
        com.termux.terminal.TerminalSession.setInputInterceptor(new com.termux.terminal.TerminalSession.InputInterceptor() {
            @Override
            public boolean onCommandEntered(com.termux.terminal.TerminalSession session, String command) {
                return com.termux.app.compose.RiskConfirmManager.INSTANCE
                    .handleTerminalCommand(TermuxService.this, session, command);
            }

            @Override
            public void onCommandBlocked(com.termux.terminal.TerminalSession session, String command) {
                // 被拦截时显示 Toast
                android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                handler.post(() -> {
                    android.widget.Toast.makeText(TermuxService.this,
                        getString(R.string.access_denied),
                        android.widget.Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public boolean onCommandAutoBlocked(com.termux.terminal.TerminalSession session, String command) {
                // 检查是否为自动拦截模式（AUTO_BLOCK）
                boolean autoBlocked = com.termux.app.compose.RiskConfirmManager.INSTANCE.isLastCommandAutoBlocked();
                if (autoBlocked) {
                    // 重置标志
                    com.termux.app.compose.RiskConfirmManager.INSTANCE.resetAutoBlockedFlag();
                }
                return autoBlocked;
            }
        });

        // 注册 Compose 核心的终端输入拦截器（增强防护对 Compose 会话的适配，
        // 与 Java 核心走同一套 RiskConfirmManager 检测/确认流程）
        com.termux.app.compose.terminal.engine.TerminalSession.setInputInterceptor(
            new com.termux.app.compose.terminal.engine.TerminalSession.InputInterceptor() {
                @Override
                public boolean onCommandEntered(com.termux.app.compose.terminal.engine.TerminalSession session, String command) {
                    return com.termux.app.compose.RiskConfirmManager.INSTANCE
                        .handleComposeTerminalCommand(TermuxService.this, session, command);
                }

                @Override
                public void onCommandBlocked(com.termux.app.compose.terminal.engine.TerminalSession session, String command) {
                    // 被拦截时显示 Toast
                    android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                    handler.post(() -> {
                        android.widget.Toast.makeText(TermuxService.this,
                            getString(R.string.access_denied),
                            android.widget.Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public boolean onCommandAutoBlocked(com.termux.app.compose.terminal.engine.TerminalSession session, String command) {
                    // 检查是否为自动拦截模式（AUTO_BLOCK）
                    boolean autoBlocked = com.termux.app.compose.RiskConfirmManager.INSTANCE.isLastCommandAutoBlocked();
                    if (autoBlocked) {
                        // 重置标志
                        com.termux.app.compose.RiskConfirmManager.INSTANCE.resetAutoBlockedFlag();
                    }
                    return autoBlocked;
                }
            });
    }

    @SuppressLint("Wakelock")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.logDebug(LOG_TAG, "onStartCommand");

        // Run again in case service is already started and onCreate() is not called
        runStartForeground();

        if (intent == null) {
            Logger.logDebug(LOG_TAG, "onStartCommand called with null intent");
            return START_STICKY;
        }

        String action = intent.getAction();

        if (action != null) {
            switch (action) {
                case TERMUX_SERVICE.ACTION_STOP_SERVICE:
                    Logger.logDebug(LOG_TAG, "ACTION_STOP_SERVICE intent received");
                    actionStopService();
                    break;
                case TERMUX_SERVICE.ACTION_STOP_SERVICE_FORCE:
                    Logger.logDebug(LOG_TAG, "ACTION_STOP_SERVICE_FORCE intent received (skip data-loss checks, already confirmed by user)");
                    actionStopServiceForce();
                    break;
                case TERMUX_SERVICE.ACTION_QUIT_APP:
                    Logger.logDebug(LOG_TAG, "ACTION_QUIT_APP intent received");
                    actionQuitApp();
                    break;
                case TERMUX_SERVICE.ACTION_QUIT_APP_FORCE:
                    Logger.logDebug(LOG_TAG, "ACTION_QUIT_APP_FORCE intent received (skip data-loss checks, already confirmed by user)");
                    actionQuitAppForce();
                    break;
                case TERMUX_SERVICE.ACTION_KILL_SESSIONS:
                    Logger.logDebug(LOG_TAG, "ACTION_KILL_SESSIONS intent received (switching runtime core)");
                    killAllTermuxExecutionCommands();
                    // 切换运行核心：显式清空所有会话（含 Compose 镜像会话——无真实进程，
                    // 结束后不会触发 onSessionFinished 回调，需手动移除并刷新会话列表）
                    removeAllTermuxSessions();
                    runStopForeground();
                    updateNotification();
                    break;
                case TERMUX_SERVICE.ACTION_WAKE_LOCK:
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_LOCK intent received");
                    actionAcquireWakeLock();
                    break;
                case TERMUX_SERVICE.ACTION_WAKE_UNLOCK:
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_UNLOCK intent received");
                    actionReleaseWakeLock(true);
                    break;
                case TERMUX_SERVICE.ACTION_SERVICE_EXECUTE:
                    Logger.logDebug(LOG_TAG, "ACTION_SERVICE_EXECUTE intent received");
                    actionServiceExecute(intent);
                    break;
                case TERMUX_SERVICE.ACTION_MEMORY_WARNING:
                    Logger.logDebug(LOG_TAG, "ACTION_MEMORY_WARNING intent received");
                    actionMemoryWarning();
                    break;
                case TERMUX_SERVICE.ACTION_MEMORY_KILL:
                    Logger.logDebug(LOG_TAG, "ACTION_MEMORY_KILL intent received");
                    actionMemoryKill();
                    break;
                case TERMUX_SERVICE.ACTION_THAW_SESSION:
                    Logger.logDebug(LOG_TAG, "ACTION_THAW_SESSION intent received");
                    actionThawSessions();
                    break;
                default:
                    Logger.logError(LOG_TAG, "Invalid action: \"" + action + "\"");
                    break;
            }
        }

        // If this service really do get killed, there is no point restarting it automatically - let the user do on next
        // start of {@link Term):
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        Logger.logVerbose(LOG_TAG, "onDestroy - mWantsToStop=" + mWantsToStop);

        TermuxShellUtils.clearTermuxTMPDIR(true);

        if (mWantsToStop) {
            actionReleaseWakeLock(false);
            killAllTermuxExecutionCommands();
            runStopForeground();
        }

        unregisterMemoryBroadcastReceiver();
        stopMemoryCheck();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Logger.logVerbose(LOG_TAG, "onTaskRemoved");
        super.onTaskRemoved(rootIntent);
        
        if (mTermuxSessions.size() > 0) {
            runStartForeground();
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Handler handler = new Handler(Looper.getMainLooper());
                handler.postDelayed(() -> {
                    Intent restartIntent = new Intent(this, TermuxService.class);
                    restartIntent.setAction(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
                    try {
                        startForegroundService(restartIntent);
                    } catch (Exception e) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "Failed to restart service after task removed", e);
                    }
                }, 1000);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Logger.logVerbose(LOG_TAG, "onBind");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Logger.logVerbose(LOG_TAG, "onUnbind");

        // Since we cannot rely on {@link TermuxActivity.onDestroy()} to always complete,
        // we unset clients here as well if it failed, so that we do not leave service and session
        // clients with references to the activity.
        if (mTermuxTerminalSessionClient != null)
            unsetTermuxTerminalSessionClient();
        return false;
    }

    /** Make service run in foreground mode. */
    private void runStartForeground() {
        setupNotificationChannel();
        startForeground(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification());
    }

    /** Make service leave foreground mode. */
    private void runStopForeground() {
        stopForeground(true);
    }

    /** Request to stop service. */
    private void requestStopService() {
        Logger.logDebug(LOG_TAG, "Requesting to stop service");
        runStopForeground();
        stopSelf();
    }

    /** Process action to stop service.
     *  If QEMU VMs or proot containers are detected running, this will NOT kill sessions directly;
     *  instead it launches MainActivity with EXTRA_TRIGGER_STOP_SERVICE so that a data-loss
     *  warning OverlayDialog can be shown and user must confirm. Once confirmed the activity
     *  sends ACTION_STOP_SERVICE_FORCE, handled by {@link #actionStopServiceForce()}.
     *  If no VMs/containers are running, sessions are killed immediately and MainActivity
     *  is brought to the foreground so the user sees the refreshed terminal page. */
    private void actionStopService() {
        // Detect running QEMU / proot containers
        com.termux.app.compose.ProcessDetector.DetectionResult detection =
                com.termux.app.compose.ProcessDetector.detectAllBlocking(this);
        boolean hasDangerousProcesses = detection.getQemuCount() > 0 || detection.getContainerRunning();

        if (hasDangerousProcesses) {
            // 有容器/虚拟机运行：直接启动 AlertDialogActivity 显示 WindowDialog 警告
            Logger.logDebug(LOG_TAG, "QEMU or proot container running; launching AlertDialogActivity for stop confirmation (qemu=" + detection.getQemuCount() + ", container=" + detection.getContainerRunning() + ")");
            com.termux.app.compose.StopConfirmDialog.startWithDetection(
                    this, false, detection.getQemuCount(), detection.getContainerRunning());
            return;
        }

        // No dangerous processes: bring MainActivity to foreground, then kill sessions and refresh
        Logger.logDebug(LOG_TAG, "No dangerous processes; launching MainActivity and killing sessions");
        Intent mainIntent = new Intent(this, com.termux.app.MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(mainIntent); } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to launch MainActivity", e);
        }

        actionStopServiceForce();
    }

    /** Process action to quit the entire app.
     *  If QEMU VMs or proot containers are detected running, launches MainActivity with
     *  EXTRA_TRIGGER_QUIT_APP so that a data-loss warning OverlayDialog can be shown.
     *  Once confirmed, the activity sends ACTION_QUIT_APP_FORCE.
     *  If no VMs/containers are running, the app exits immediately. */
    private void actionQuitApp() {
        // Detect running QEMU / proot containers
        com.termux.app.compose.ProcessDetector.DetectionResult detection =
                com.termux.app.compose.ProcessDetector.detectAllBlocking(this);
        boolean hasDangerousProcesses = detection.getQemuCount() > 0 || detection.getContainerRunning();

        if (hasDangerousProcesses) {
            // 有容器/虚拟机运行：直接启动 AlertDialogActivity 显示 WindowDialog 警告
            Logger.logDebug(LOG_TAG, "QEMU or proot container running; launching AlertDialogActivity for quit confirmation (qemu=" + detection.getQemuCount() + ", container=" + detection.getContainerRunning() + ")");
            com.termux.app.compose.StopConfirmDialog.startWithDetection(
                    this, true, detection.getQemuCount(), detection.getContainerRunning());
            return;
        }

        // No dangerous processes: directly exit
        actionQuitAppForce();
    }

    /** Force quit the entire app without any further data-loss checks.
     *  Kills all sessions, stops foreground service, and terminates the process. */
    private void actionQuitAppForce() {
        mWantsToStop = true;
        killAllTermuxExecutionCommands();
        runStopForeground();
        stopSelf();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** Force stop service without any further data-loss checks. Called after TermuxActivity
     *  has already warned the user (if QEMU/container running) and user confirmed "继续". */
    private void actionStopServiceForce() {
        mWantsToStop = false;
        killAllTermuxExecutionCommands();

        int remainingSessions = getTermuxSessionsSize();
        int remainingTasks = mTermuxTasks.size();
        if (remainingSessions == 0 && remainingTasks == 0) {
            // Mark "sessions cleaned" state so notification downgrades (requirement 1)
            mAllSessionsCleared = true;
        }

        updateNotification();

        // 结束反馈：toast 通知用户已结束所有会话
        final String toastMsg = "已结束所有会话";
        new Handler(Looper.getMainLooper()).post(() ->
            android.widget.Toast.makeText(TermuxService.this, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
        );
    }

    /** Kill all TermuxSessions and TermuxTasks by sending SIGKILL to their processes.
     *
     * For TermuxSessions, all sessions will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will only be done if user manually exited termux or if the session was started by a plugin
     * which **expects** the result back via a pending intent.
     *
     * For TermuxTasks, only tasks that were started by a plugin which **expects** the result
     * back via a pending intent will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will always be done for the tasks that are killed. The remaining processes will keep on
     * running until the termux app process is killed by android, like by OOM, so we let them run
     * as long as they can.
     *
     * Some plugin execution commands may not have been processed and added to mTermuxSessions and
     * mTermuxTasks lists before the service is killed, so we maintain a separate
     * mPendingPluginExecutionCommands list for those, so that we can notify the pending intent
     * creators that execution was cancelled.
     *
     * Note that if user didn't manually exit Termux and if onDestroy() was directly called because
     * of unintended shutdown, like android deciding to kill the service, then there will be no
     * guarantee that onDestroy() will be allowed to finish and termux app process may be killed before
     * it has finished. This means that in those cases some results may not be sent back to their
     * creators for plugin commands but we still try to process whatever results can be processed
     * despite the unreliable behaviour of onDestroy().
     *
     * Note that if don't kill the processes started by plugins which **expect** the result back
     * and notify their creators that they have been killed, then they may get stuck waiting for
     * the results forever like in case of commands started by Termux:Tasker or RUN_COMMAND intent,
     * since once TermuxService has been killed, no result will be sent back. They may still get
     * stuck if termux app process gets killed, so for this case reasonable timeout values should
     * be used, like in Tasker for the Termux:Tasker actions.
     *
     * We make copies of each list since items are removed inside the loop.
     */
    private synchronized void killAllTermuxExecutionCommands() {
        boolean processResult;

        Logger.logDebug(LOG_TAG, "Killing TermuxSessions=" + mTermuxSessions.size() + ", TermuxTasks=" + mTermuxTasks.size() + ", PendingPluginExecutionCommands=" + mPendingPluginExecutionCommands.size());

        List<TermuxSession> termuxSessions = new ArrayList<>(mTermuxSessions);
        for (int i = 0; i < termuxSessions.size(); i++) {
            ExecutionCommand executionCommand = termuxSessions.get(i).getExecutionCommand();
            processResult = mWantsToStop || executionCommand.isPluginExecutionCommandWithPendingResult();
            termuxSessions.get(i).killIfExecuting(this, processResult);
        }

        List<TermuxTask> termuxTasks = new ArrayList<>(mTermuxTasks);
        for (int i = 0; i < termuxTasks.size(); i++) {
            ExecutionCommand executionCommand = termuxTasks.get(i).getExecutionCommand();
            if (executionCommand.isPluginExecutionCommandWithPendingResult())
                termuxTasks.get(i).killIfExecuting(this, true);
        }

        List<ExecutionCommand> pendingPluginExecutionCommands = new ArrayList<>(mPendingPluginExecutionCommands);
        for (int i = 0; i < pendingPluginExecutionCommands.size(); i++) {
            ExecutionCommand executionCommand = pendingPluginExecutionCommands.get(i);
            if (!executionCommand.shouldNotProcessResults() && executionCommand.isPluginExecutionCommandWithPendingResult()) {
                if (executionCommand.setStateFailed(Errno.ERRNO_CANCELLED.getCode(), this.getString(com.termux.shared.R.string.error_execution_cancelled))) {
                    PluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);
                }
            }
        }
    }



    /** Process action to acquire Power and Wi-Fi WakeLocks. */
    @SuppressLint({"WakelockTimeout", "BatteryLife"})
    private void actionAcquireWakeLock() {
        if (mWakeLock != null) {
            Logger.logDebug(LOG_TAG, "Ignoring acquiring WakeLocks since they are already held");
            return;
        }

        Logger.logDebug(LOG_TAG, "Acquiring WakeLocks");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TermuxConstants.TERMUX_APP_NAME.toLowerCase() + ":service-wakelock");
        mWakeLock.acquire();

        // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TermuxConstants.TERMUX_APP_NAME.toLowerCase());
        mWifiLock.acquire();

        String packageName = getPackageName();
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Intent whitelist = new Intent();
            whitelist.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            whitelist.setData(Uri.parse("package:" + packageName));
            whitelist.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            try {
                startActivity(whitelist);
            } catch (ActivityNotFoundException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to call ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", e);
            }
        }

        startKeepAliveStrategies();

        updateNotification();

        Logger.logDebug(LOG_TAG, "WakeLocks acquired successfully");

    }

    /** Process action to release Power and Wi-Fi WakeLocks. */
    private void actionReleaseWakeLock(boolean updateNotification) {
        if (mWakeLock == null && mWifiLock == null) {
            Logger.logDebug(LOG_TAG, "Ignoring releasing WakeLocks since none are already held");
            return;
        }

        Logger.logDebug(LOG_TAG, "Releasing WakeLocks");

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        if (mWifiLock != null) {
            mWifiLock.release();
            mWifiLock = null;
        }

        stopKeepAliveStrategies();

        if (updateNotification)
            updateNotification();

        Logger.logDebug(LOG_TAG, "WakeLocks released successfully");
    }

    /** Process {@link TERMUX_SERVICE#ACTION_SERVICE_EXECUTE} intent to execute a shell command in
     * a foreground TermuxSession or in a background TermuxTask. */
    private void actionServiceExecute(Intent intent) {
        if (intent == null) {
            Logger.logError(LOG_TAG, "Ignoring null intent to actionServiceExecute");
            return;
        }

        ExecutionCommand executionCommand = new ExecutionCommand(getNextExecutionId());

        executionCommand.executableUri = intent.getData();
        executionCommand.inBackground = intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false);

        if (executionCommand.executableUri != null) {
            executionCommand.executable = executionCommand.executableUri.getPath();
            executionCommand.arguments = IntentUtils.getStringArrayExtraIfSet(intent, TERMUX_SERVICE.EXTRA_ARGUMENTS, null);
            if (executionCommand.inBackground)
                executionCommand.stdin = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_STDIN, null);
                executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(intent, TERMUX_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, null);
        }

        executionCommand.workingDirectory = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_WORKDIR, null);
        executionCommand.isFailsafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
        executionCommand.sessionAction = intent.getStringExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION);
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Execution Intent Command");
        executionCommand.commandDescription = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, null);
        executionCommand.commandHelp = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_HELP, null);
        executionCommand.pluginAPIHelp = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_PLUGIN_API_HELP, null);
        executionCommand.isPluginExecutionCommand = true;
        executionCommand.resultConfig.resultPendingIntent = intent.getParcelableExtra(TERMUX_SERVICE.EXTRA_PENDING_INTENT);
        executionCommand.resultConfig.resultDirectoryPath = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_DIRECTORY, null);
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile = intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_RESULT_SINGLE_FILE, false);
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_BASENAME, null);
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null);
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null);
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null);
        }

        // Guard: ignore ACTION_SERVICE_EXECUTE intents with no executable data.
        // These are sent by keep-alive mechanisms (AlarmManager, JobScheduler, onTaskRemoved)
        // and should NOT create new Termux sessions.
        if (executionCommand.executableUri == null && executionCommand.executable == null) {
            Logger.logDebug(LOG_TAG, "Ignoring ACTION_SERVICE_EXECUTE intent with no executable data (likely keep-alive/restart)");
            return;
        }

        // Add the execution command to pending plugin execution commands list
        mPendingPluginExecutionCommands.add(executionCommand);

        if (executionCommand.inBackground) {
            executeTermuxTaskCommand(executionCommand);
        } else {
            executeTermuxSessionCommand(executionCommand);
        }
    }





    /** Execute a shell command in background {@link TermuxTask}. */
    private void executeTermuxTaskCommand(ExecutionCommand executionCommand) {
        if (executionCommand == null) return;

        Logger.logDebug(LOG_TAG, "Executing background \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask command");

        TermuxTask newTermuxTask = createTermuxTask(executionCommand);
    }

    /** Create a {@link TermuxTask}. */
    @Nullable
    public TermuxTask createTermuxTask(String executablePath, String[] arguments, String stdin, String workingDirectory) {
        return createTermuxTask(new ExecutionCommand(getNextExecutionId(), executablePath, arguments, stdin, workingDirectory, true, false));
    }

    /** Create a {@link TermuxTask}. */
    @Nullable
    public synchronized TermuxTask createTermuxTask(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask");

        if (!executionCommand.inBackground) {
            Logger.logDebug(LOG_TAG, "Ignoring a foreground execution command passed to createTermuxTask()");
            return null;
        }

        // 高危命令检测：显示弹窗二次确认
        if (isRiskConfirmationEnabled()) {
            String cmdStr = extractCommandString(executionCommand);
            if (cmdStr != null && com.termux.app.compose.RiskCommandDetector.INSTANCE.isDangerous(cmdStr)) {
                Logger.logWarn(LOG_TAG, "High-risk task command detected, requesting confirmation: " + cmdStr);
                boolean confirmed = com.termux.app.compose.RiskConfirmManager.INSTANCE
                    .requestConfirmationBlocking(this, cmdStr);
                if (!confirmed) {
                    Logger.logWarn(LOG_TAG, "Blocked high-risk task command: " + cmdStr);
                    Toast.makeText(this, R.string.access_denied, Toast.LENGTH_LONG).show();
                    return null;
                }
            }
        }

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        TermuxTask newTermuxTask = TermuxTask.execute(this, executionCommand, this, new TermuxShellEnvironmentClient(), false);
        if (newTermuxTask == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxTask command for:\n" + executionCommand.getCommandIdAndLabelLogString());
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                PluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            else
                Logger.logErrorExtended(LOG_TAG, executionCommand.toString());
            return null;
        }

        mTermuxTasks.add(newTermuxTask);

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mPendingPluginExecutionCommands.remove(executionCommand);

        // A new background task was created => leave the "sessions cleaned" state so
        // notification priority and format are restored to LiveUpdate style
        mAllSessionsCleared = false;

        updateNotification();

        return newTermuxTask;
    }

    /** Callback received when a {@link TermuxTask} finishes. */
    @Override
    public void onTermuxTaskExited(final TermuxTask termuxTask) {
        mHandler.post(() -> {
            if (termuxTask != null) {
                ExecutionCommand executionCommand = termuxTask.getExecutionCommand();

                Logger.logVerbose(LOG_TAG, "The onTermuxTaskExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask command");

                // If the execution command was started for a plugin, then process the results
                if (executionCommand != null && executionCommand.isPluginExecutionCommand)
                    PluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);

                mTermuxTasks.remove(termuxTask);
            }

            updateNotification();
        });
    }





    /** Execute a shell command in a foreground {@link TermuxSession}. */
    private void executeTermuxSessionCommand(ExecutionCommand executionCommand) {
        if (executionCommand == null) return;

        Logger.logDebug(LOG_TAG, "Executing foreground \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession command");

        String sessionName = null;

        // Transform executable path to session name, e.g. "/bin/do-something.sh" => "do something.sh".
        if (executionCommand.executable != null) {
            sessionName = ShellUtils.getExecutableBasename(executionCommand.executable).replace('-', ' ');
        }

        TermuxSession newTermuxSession = createTermuxSession(executionCommand, sessionName);
        if (newTermuxSession == null) return;

        handleSessionAction(DataUtils.getIntFromString(executionCommand.sessionAction,
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY),
            newTermuxSession.getTerminalSession());
    }

    /**
     * Create a {@link TermuxSession}.
     * Currently called by {@link TermuxTerminalSessionClient#addNewSession(boolean, String)} to add a new {@link TermuxSession}.
     */
    @Nullable
    public TermuxSession createTermuxSession(String executablePath, String[] arguments, String stdin, String workingDirectory, boolean isFailSafe, String sessionName) {
        return createTermuxSession(new ExecutionCommand(getNextExecutionId(), executablePath, arguments, stdin, workingDirectory, false, isFailSafe), sessionName);
    }

    /** Create a {@link TermuxSession}. */
    @Nullable
    public synchronized TermuxSession createTermuxSession(ExecutionCommand executionCommand, String sessionName) {
        if (executionCommand == null) return null;

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession");

        if (executionCommand.inBackground) {
            Logger.logDebug(LOG_TAG, "Ignoring a background execution command passed to createTermuxSession()");
            return null;
        }

        // 高危命令检测：显示弹窗二次确认
        if (isRiskConfirmationEnabled()) {
            String cmdStr = extractCommandString(executionCommand);
            if (cmdStr != null && com.termux.app.compose.RiskCommandDetector.INSTANCE.isDangerous(cmdStr)) {
                Logger.logWarn(LOG_TAG, "High-risk command detected, requesting confirmation: " + cmdStr);
                boolean confirmed = com.termux.app.compose.RiskConfirmManager.INSTANCE
                    .requestConfirmationBlocking(this, cmdStr);
                if (!confirmed) {
                    Logger.logWarn(LOG_TAG, "Blocked high-risk command: " + cmdStr);
                    Toast.makeText(this, R.string.access_denied, Toast.LENGTH_LONG).show();
                    return null;
                }
            }
        }

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        // Compose 模式：会话由 ComposeSessionManager 管理。这里通过镜像句柄保持 Java 接口
        // 完全兼容——第三方页面（资源中心/工具中心等）无需改动即可直接调用创建/写入/切换。
        if (com.termux.app.compose.TerminalRuntimeCore.isComposeMode(this)) {
            TermuxSession composeMirror = com.termux.app.compose.ComposeSessionBridge.INSTANCE
                .createComposeMirrorSession(this, executionCommand, sessionName);
            if (composeMirror == null) {
                Logger.logError(LOG_TAG, "Failed to create Compose mirror TermuxSession for:\n" + executionCommand.getCommandIdAndLabelLogString());
                return null;
            }
            mTermuxSessions.add(composeMirror);

            // Remove the execution command from the pending plugin execution commands list since it has
            // now been processed
            if (executionCommand.isPluginExecutionCommand)
                mPendingPluginExecutionCommands.remove(executionCommand);

            mAllSessionsCleared = false;

            // Notify UI that sessions list has been updated
            if (mTermuxTerminalSessionClient != null)
                mTermuxTerminalSessionClient.termuxSessionListNotifyUpdated();

            // Auto acquire WakeLock if session is running a server/listening program (VNC, SSH, etc.)
            if (isServerProgram(executionCommand)) {
                actionAcquireWakeLock();
            }

            updateNotification();
            TermuxActivity.updateTermuxActivityStyling(this);

            return composeMirror;
        }

        // If the execution command was started for a plugin, only then will the stdout be set
        // Otherwise if command was manually started by the user like by adding a new terminal session,
        // then no need to set stdout
        executionCommand.terminalTranscriptRows = getTerminalTranscriptRows();
        TermuxSession newTermuxSession = TermuxSession.execute(this, executionCommand, getTermuxTerminalSessionClient(), this, new TermuxShellEnvironmentClient(), sessionName, executionCommand.isPluginExecutionCommand);
        if (newTermuxSession == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxSession command for:\n" + executionCommand.getCommandIdAndLabelLogString());
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                PluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            else
                Logger.logErrorExtended(LOG_TAG, executionCommand.toString());
            return null;
        }

        mTermuxSessions.add(newTermuxSession);

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mPendingPluginExecutionCommands.remove(executionCommand);

        // A new foreground session was created => restore LiveUpdate notification format
        mAllSessionsCleared = false;

        // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
        // activity in is foreground
        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.termuxSessionListNotifyUpdated();

        // Auto acquire WakeLock if session is running a server/listening program (VNC, SSH, etc.)
        if (isServerProgram(executionCommand)) {
            actionAcquireWakeLock();
        }

        updateNotification();
        TermuxActivity.updateTermuxActivityStyling(this);

        return newTermuxSession;
    }

    /** Remove a TermuxSession. */
    
    /**
     * 注册一个插件创建的 TermuxSession 到服务管理。
     *
     * 插件持久化会话也需要进入 mTermuxSessions，这样终端页面能看到并管理它，
     * 通知栏计数也能正确包含。commandLabel 前缀为 "PluginSession:" 供 UI 区分。
     */
    public synchronized void registerPluginSession(@NonNull final TermuxSession session) {
        if (session == null) return;
        if (!session.getExecutionCommand().commandLabel.startsWith("PluginSession:")) {
            Logger.logWarn(LOG_TAG, "registerPluginSession 收到的会话 commandLabel 不以 PluginSession: 开头，可能不是插件会话");
        }
        session.setSource(TermuxSession.SessionSource.PLUGIN);
        mTermuxSessions.add(session);
        Logger.logDebug(LOG_TAG, "registerPluginSession: 已添加插件会话 " + session.getTerminalSession().mSessionName + " (total=" + mTermuxSessions.size() + ")");

        // 通知 UI
        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.termuxSessionListNotifyUpdated();

        // 插件持久化会话是一个普通 shell（login shell），设置 emulator 尺寸
        TerminalSession terminal = session.getTerminalSession();
        if (terminal.getEmulator() == null && terminal.getShellPid() > 0) {
            terminal.updateSize(80, 24, 0, 0);
        }

        updateNotification();
        TermuxActivity.updateTermuxActivityStyling(this);
    }

public synchronized int removeTermuxSession(TerminalSession sessionToRemove) {
        int index = getIndexOfSession(sessionToRemove);

        if (index >= 0) {
            // Compose 模式镜像：同时结束并注销对应的 Compose 会话
            com.termux.app.compose.ComposeSessionBridge.INSTANCE.removeByJavaMirror(sessionToRemove);
            mTermuxSessions.get(index).getTerminalSession().finishIfRunning();
            mTermuxSessions.remove(index);
            // 清理环境缓存
            if (sessionToRemove.mHandle != null) {
                com.termux.app.compose.RiskConfirmManager.INSTANCE.invalidateEnvironmentCache(sessionToRemove.mHandle);
            }
        }

        updateNotification();
        return index;
    }

    /** Force remove a TermuxSession from the list immediately. */
    public synchronized void forceRemoveTermuxSession(TermuxSession sessionToRemove) {
        int index = -1;
        for (int i = 0; i < mTermuxSessions.size(); i++) {
            if (mTermuxSessions.get(i).getTerminalSession() == sessionToRemove.getTerminalSession()) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            TermuxSession session = mTermuxSessions.get(index);
            String sessionName = session.getTerminalSession().mSessionName;
            if (sessionName == null || sessionName.isEmpty()) {
                sessionName = getString(R.string.terminal);
            }
            // Compose 模式镜像：同时结束并注销对应的 Compose 会话
            com.termux.app.compose.ComposeSessionBridge.INSTANCE.removeByJavaMirror(session.getTerminalSession());
            session.getTerminalSession().finishIfRunning();
            mTermuxSessions.remove(index);
            // 清理环境缓存
            TerminalSession terminalSession = sessionToRemove.getTerminalSession();
            if (terminalSession != null && terminalSession.mHandle != null) {
                com.termux.app.compose.RiskConfirmManager.INSTANCE.invalidateEnvironmentCache(terminalSession.mHandle);
            }
            final String finalSessionName = sessionName;
            new Handler(getMainLooper()).post(() ->
                Toast.makeText(TermuxService.this, finalSessionName + " 已停止，返回代码: 137", Toast.LENGTH_SHORT).show()
            );
        }
        updateNotification();
    }

    /** Callback received when a {@link TermuxSession} finishes. */
    @Override
    public void onTermuxSessionExited(final TermuxSession termuxSession) {
        if (termuxSession != null) {
            ExecutionCommand executionCommand = termuxSession.getExecutionCommand();

            Logger.logVerbose(LOG_TAG, "The onTermuxSessionExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession command");

            int exitCode = termuxSession.getTerminalSession().getExitStatus();
            String sessionName = termuxSession.getTerminalSession().mSessionName;
            if (sessionName == null || sessionName.isEmpty()) {
                sessionName = getString(R.string.terminal);
            }

            // 记录到死亡会话队列，供 TerminalListScreen 以"死亡卡片"形式展示
            // （红色标题 + 退出代码小字 + 手动消除按钮）
            mDeadSessionInfos.add(new DeadSessionInfo(sessionName, exitCode, System.currentTimeMillis()));

            if (exitCode == 137 && !mIsMemoryKillActive && !MemoryBroadcastReceiver.isMemoryKillReceived()) {
                mKilledSessionName = sessionName;
                Logger.logDebug(LOG_TAG, "Session killed by system: " + sessionName);
            }

            // If the execution command was started for a plugin, then process the results
            if (executionCommand != null && executionCommand.isPluginExecutionCommand)
                PluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);

            mTermuxSessions.remove(termuxSession);

            // 清理环境缓存
            String handle = termuxSession.getTerminalSession().mHandle;
            if (handle != null) {
                com.termux.app.compose.RiskConfirmManager.INSTANCE.invalidateEnvironmentCache(handle);
            }

            // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
            // activity in is foreground
            if (mTermuxTerminalSessionClient != null)
                mTermuxTerminalSessionClient.termuxSessionListNotifyUpdated();
        }

        updateNotification();
    }

    /** 获取已结束会话信息列表（供 TerminalListScreen 渲染死亡卡片）。 */
    public synchronized List<DeadSessionInfo> getDeadSessionInfos() {
        return new ArrayList<>(mDeadSessionInfos);
    }

    /** 用户手动消除某个死亡会话卡片时调用。 */
    public synchronized void clearDeadSessionInfo(String sessionName, long exitedAt) {
        mDeadSessionInfos.removeIf(info ->
            info.sessionName.equals(sessionName) && info.exitedAt == exitedAt);
    }

    /** 清除所有死亡会话信息（例如用户点击"全部清除"）。 */
    public synchronized void clearAllDeadSessionInfos() {
        mDeadSessionInfos.clear();
    }

    /** Get the terminal transcript rows to be used for new {@link TermuxSession}. */
    public Integer getTerminalTranscriptRows() {
        if (mTerminalTranscriptRows == null)
            setTerminalTranscriptRows();
        return mTerminalTranscriptRows;
    }

    public void setTerminalTranscriptRows() {
        // TermuxService only uses this termux property currently, so no need to load them all into
        // an internal values map like TermuxActivity does
        mTerminalTranscriptRows = TermuxAppSharedProperties.getTerminalTranscriptRows(this);
    }





    /** Process session action for new session. */
    private void handleSessionAction(int sessionAction, TerminalSession newTerminalSession) {
        Logger.logDebug(LOG_TAG, "Processing sessionAction \"" + sessionAction + "\" for session \"" + newTerminalSession.mSessionName + "\"");

        switch (sessionAction) {
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY:
                setCurrentStoredTerminalSession(newTerminalSession);
                if (mTermuxTerminalSessionClient != null)
                    mTermuxTerminalSessionClient.setCurrentSession(newTerminalSession);
                startTermuxActivity();
                break;
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY:
                if (getTermuxSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession);
                startTermuxActivity();
                break;
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY:
                setCurrentStoredTerminalSession(newTerminalSession);
                if (mTermuxTerminalSessionClient != null)
                    mTermuxTerminalSessionClient.setCurrentSession(newTerminalSession);
                break;
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY:
                if (getTermuxSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession);
                break;
            default:
                Logger.logError(LOG_TAG, "Invalid sessionAction: \"" + sessionAction + "\". Force using default sessionAction.");
                handleSessionAction(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY, newTerminalSession);
                break;
        }
    }

    /** Launch the {@link }TermuxActivity} to bring it to foreground. */
    private void startTermuxActivity() {
        // For android >= 10, apps require Display over other apps permission to start foreground activities
        // from background (services). If it is not granted, then TermuxSessions that are started will
        // show in Termux notification but will not run until user manually clicks the notification.
        if (PermissionUtils.validateDisplayOverOtherAppsPermissionForPostAndroid10(this, true)) {
            TermuxActivity.startTermuxActivity(this);
        } else {
            TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
            if (preferences == null) return;
            if (preferences.arePluginErrorNotificationsEnabled())
                Logger.showToast(this, this.getString(R.string.error_display_over_other_apps_permission_not_granted), true);
        }
    }





    /** If {@link TermuxActivity} has not bound to the {@link TermuxService} yet or is destroyed, then
     * interface functions requiring the activity should not be available to the terminal sessions,
     * so we just return the {@link #mTermuxTerminalSessionClientBase}. Once {@link TermuxActivity} bind
     * callback is received, it should call {@link #setTermuxTerminalSessionClient} to set the
     * {@link TermuxService#mTermuxTerminalSessionClient} so that further terminal sessions are directly
     * passed the {@link TermuxTerminalSessionClient} object which fully implements the
     * {@link TerminalSessionClient} interface.
     *
     * @return Returns the {@link TermuxTerminalSessionClient} if {@link TermuxActivity} has bound with
     * {@link TermuxService}, otherwise {@link TermuxTerminalSessionClientBase}.
     */
    public synchronized TermuxTerminalSessionClientBase getTermuxTerminalSessionClient() {
        if (mTermuxTerminalSessionClient != null)
            return mTermuxTerminalSessionClient;
        else
            return mTermuxTerminalSessionClientBase;
    }

    /** This should be called when {@link TermuxActivity#onServiceConnected} is called to set the
     * {@link TermuxService#mTermuxTerminalSessionClient} variable and update the {@link TerminalSession}
     * and {@link TerminalEmulator} clients in case they were passed {@link TermuxTerminalSessionClientBase}
     * earlier.
     *
     * @param termuxTerminalSessionClient The {@link TermuxTerminalSessionClient} object that fully
     * implements the {@link TerminalSessionClient} interface.
     */
    public synchronized void setTermuxTerminalSessionClient(TermuxTerminalSessionClient termuxTerminalSessionClient) {
        mTermuxTerminalSessionClient = termuxTerminalSessionClient;

        for (int i = 0; i < mTermuxSessions.size(); i++)
            mTermuxSessions.get(i).getTerminalSession().updateTerminalSessionClient(mTermuxTerminalSessionClient);
    }

    /** This should be called when {@link TermuxActivity} has been destroyed and in {@link #onUnbind(Intent)}
     * so that the {@link TermuxService} and {@link TerminalSession} and {@link TerminalEmulator}
     * clients do not hold an activity references.
     */
    public synchronized void unsetTermuxTerminalSessionClient() {
        for (int i = 0; i < mTermuxSessions.size(); i++)
            mTermuxSessions.get(i).getTerminalSession().updateTerminalSessionClient(mTermuxTerminalSessionClientBase);

        mTermuxTerminalSessionClient = null;
    }





    private Notification buildNotification() {
        Resources res = getResources();

        // Set pending intent to be launched when notification is clicked
        Intent notificationIntent = TermuxActivity.newInstance(this);
        int pendingIntentFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags);


        // Set notification text
        int sessionCount = getTermuxSessionsSize();
        // Compose 模式：mTermuxSessions 只含第三方路径的镜像会话，Compose 直建的会话
        // （主页/终端页"新建会话"）不在其中。以 ComposeSessionManager 的实际会话数为准，
        // 避免 LiveUpdate 通知的会话数量计算错误。
        if (com.termux.app.compose.TerminalRuntimeCore.isComposeMode(this)) {
            try {
                sessionCount = com.termux.app.compose.terminal.ComposeSessionManager
                    .getInstance(this).getSessions().getValue().size();
            } catch (Throwable t) {
                Logger.logDebug(LOG_TAG, "Failed to count Compose sessions: " + t.getMessage());
            }
        }
        int taskCount = mTermuxTasks.size();
        String notificationText;

        // Reset cleared state if a session/task came back (requirement 1)
        if (mAllSessionsCleared && (sessionCount > 0 || taskCount > 0)) {
            mAllSessionsCleared = false;
        }

        // --- Detect QEMU / proot container processes (requirement 2) ---
        com.termux.app.compose.ProcessDetector.DetectionResult detection = null;
        if (sessionCount > 0 || taskCount > 0) {
            // Only run detection when something is running; keeps the no-session path fast
            detection = com.termux.app.compose.ProcessDetector.detectAllBlocking(this);
        }
        final int qemuCount  = (detection == null) ? 0 : detection.getQemuCount();
        final boolean containerRunning = (detection != null) && detection.getContainerRunning();

        // --- Format notification text ---
        // 三档优先级（与 LiveUpdate 药丸 shortCriticalText 保持一致）：
        //   1) 有 QEMU 虚拟机运行        → 以虚拟机数量为主线
        //   2) 否则有 proot 容器在运行    → 会话数 + (含容器) 标记
        //   3) 都没有                     → 仅会话数
        if (mAllSessionsCleared && sessionCount == 0 && taskCount == 0) {
            // Requirement 1: downgrade text after end-sessions click until new sessions start
            notificationText = "终端会话已清理(已无会话运行)。";
        } else if (sessionCount == 0 && taskCount == 0) {
            notificationText = res.getString(R.string.notification_no_terminals_running);
        } else if (qemuCount > 0) {
            // 最优先：有虚拟机运行 —— 以虚拟机数量为主线
            notificationText = "正运行 " + qemuCount + " 台虚拟机";
            if (taskCount > 0) {
                notificationText += "，" + taskCount + " 个任务";
            }
        } else if (containerRunning) {
            // 次优先：有容器运行 —— 会话数 + 含容器标记
            notificationText = "正运行 " + sessionCount + " 个会话(含容器)";
            if (taskCount > 0) {
                notificationText += "，" + taskCount + " 个任务";
            }
        } else {
            // 默认：仅会话数
            notificationText = "正运行 " + sessionCount + " 个会话";
            if (taskCount > 0) {
                notificationText += "，" + taskCount + " 个任务";
            }
        }

        final boolean wakeLockHeld = mWakeLock != null;
        if (wakeLockHeld && !(mAllSessionsCleared && sessionCount == 0 && taskCount == 0)) {
            notificationText += " (" + res.getString(R.string.notification_wake_lock_held) + ")";
        }


        // Set notification priority
        // Requirement 1: if sessions have just been cleaned -> normal (low) priority, NOT high/LiveUpdate
        int priority;
        if (mAllSessionsCleared && sessionCount == 0 && taskCount == 0) {
            priority = Notification.PRIORITY_LOW;
        } else {
            priority = (wakeLockHeld) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW;
        }


        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("Termux 终端");
        builder.setContentText(notificationText);
        builder.setContentIntent(contentIntent);
        builder.setPriority(priority);
        builder.setShowWhen(false);
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setOngoing(true);

        // "结束会话" action: send ACTION_STOP_SERVICE to service, which will launch MainActivity
        // and show a data-loss warning dialog if VMs/containers are running.
        Intent stopIntent = new Intent(this, TermuxService.class).setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE);
        // Use FLAG_UPDATE_CURRENT so the extra is delivered correctly even when the same PendingIntent already exists
        int exitPiFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) exitPiFlags |= PendingIntent.FLAG_IMMUTABLE;
        builder.addAction(android.R.drawable.ic_delete, res.getString(R.string.notification_action_exit),
                PendingIntent.getService(this, 1, stopIntent, exitPiFlags));

        // "关闭程序" action: send ACTION_QUIT_APP to service, which will exit immediately
        // or show a data-loss warning dialog if VMs/containers are running.
        Intent quitIntent = new Intent(this, TermuxService.class).setAction(TERMUX_SERVICE.ACTION_QUIT_APP);
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, res.getString(R.string.notification_action_quit),
                PendingIntent.getService(this, 2, quitIntent, exitPiFlags));

        String newWakeAction = wakeLockHeld ? TERMUX_SERVICE.ACTION_WAKE_UNLOCK : TERMUX_SERVICE.ACTION_WAKE_LOCK;
        Intent toggleWakeLockIntent = new Intent(this, TermuxService.class).setAction(newWakeAction);
        String actionTitle = res.getString(wakeLockHeld ? R.string.notification_action_wake_unlock : R.string.notification_action_wake_lock);
        int actionIcon = wakeLockHeld ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_lock_lock;
        builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, pendingIntentFlags));

        if (Build.VERSION.SDK_INT >= 36) {
            try {
                // Requirement 1: when sessions cleared, do NOT promote to LiveUpdate,
                // do NOT set HIGH priority, and do NOT use shortCriticalText.
                if (!(mAllSessionsCleared && sessionCount == 0 && taskCount == 0)) {
                    builder.setPriority(Notification.PRIORITY_HIGH);

                    if (sessionCount > 0 || qemuCount > 0 || containerRunning) {
                        builder.setStyle(new Notification.BigTextStyle().bigText(notificationText));
                    }

                    if (sessionCount > 0) {
                        // Android 16+ Live Update (Promoted Ongoing): opt in via extras + short critical text.
                        // Requires an ongoing notification with a Style (BigTextStyle set above).
                        Bundle promotedExtras = new Bundle();
                        promotedExtras.putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true);
                        builder.addExtras(promotedExtras);
                        // 药丸文字（shortCriticalText）三档优先级，与通知正文保持一致：
                        //   1) 有 QEMU 虚拟机运行 → "<N> 台虚拟机"
                        //   2) 否则有 proot 容器在运行 → "<M> 个会话(含容器)"
                        //   3) 都没有 → "<M> 个会话"
                        // 注：QEMU 检测已统一使用 ProcessDetector.countRunningQemuBlocking()，
                        // 与 QemuVmActivity 虚拟机页面卡片上的"运行中"数量同步（包含容器内 QEMU 进程）。
                        if (qemuCount > 0) {
                            builder.setShortCriticalText(qemuCount + " 台虚拟机");
                        } else if (containerRunning) {
                            builder.setShortCriticalText(sessionCount + " 个会话(含容器)");
                        } else {
                            builder.setShortCriticalText(sessionCount + " 个会话");
                        }
                    }
                }
            } catch (Exception e) {
                Logger.logDebug(LOG_TAG, "Failed to set Live Update notification properties: " + e.getMessage());
            }
        }

        return builder.build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationUtils.setupNotificationChannel(this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
    }

    /** Update the shown foreground service notification after making any changes that affect it. */
    private synchronized void updateNotification() {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification());
    }





    private void setCurrentStoredTerminalSession(TerminalSession session) {
        if (session == null) return;
        // Make the newly created session the current one to be displayed
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
        if (preferences == null) return;
        preferences.setCurrentSession(session.mHandle);
    }

    public synchronized boolean isWakeLockHeld() {
        return mWakeLock != null;
    }

    public synchronized int getTermuxSessionsSize() {
        return mTermuxSessions.size();
    }

    public synchronized List<TermuxSession> getTermuxSessions() {
        return mTermuxSessions;
    }

    /** Update the session list with a new ordered list. */
    public synchronized void updateSessionList(List<TermuxSession> newSessions) {
        mTermuxSessions.clear();
        mTermuxSessions.addAll(newSessions);
        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.termuxSessionListNotifyUpdated();
        updateNotification();
    }

    /**
     * 清空所有会话与任务并刷新会话列表（切换运行核心时使用）。
     * 含 Compose 镜像会话——无真实进程，结束后不会触发 onSessionFinished 回调，
     * 必须手动从列表移除，否则切换回 Java 核心后会残留无效的会话卡片。
     */
    public synchronized void removeAllTermuxSessions() {
        for (TermuxSession session : new ArrayList<>(mTermuxSessions)) {
            try {
                session.getTerminalSession().finishIfRunning();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to finish session while clearing all sessions", e);
            }
        }
        mTermuxSessions.clear();
        mTermuxTasks.clear();
        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.termuxSessionListNotifyUpdated();
        updateNotification();
    }

    @Nullable
    public synchronized TermuxSession getTermuxSession(int index) {
        if (index >= 0 && index < mTermuxSessions.size())
            return mTermuxSessions.get(index);
        else
            return null;
    }

    public synchronized TermuxSession getLastTermuxSession() {
        return mTermuxSessions.isEmpty() ? null : mTermuxSessions.get(mTermuxSessions.size() - 1);
    }

    /**
     * 上一次进入的会话（存储的当前会话，否则最后一个会话），供主页终端列表高亮显示。
     * 语义与 {@link TermuxTerminalSessionClient#getCurrentStoredSessionOrLast()} 一致，
     * 返回值映射回列表中的 {@link TermuxSession}。
     */
    public synchronized TermuxSession getLastEnteredTermuxSession() {
        TerminalSession terminalSession = null;
        if (mTermuxTerminalSessionClient != null)
            terminalSession = mTermuxTerminalSessionClient.getCurrentStoredSessionOrLast();
        if (terminalSession == null) return null;
        for (TermuxSession session : mTermuxSessions) {
            if (session.getTerminalSession() == terminalSession)
                return session;
        }
        return null;
    }

    public synchronized int getIndexOfSession(TerminalSession terminalSession) {
        for (int i = 0; i < mTermuxSessions.size(); i++) {
            if (mTermuxSessions.get(i).getTerminalSession().equals(terminalSession))
                return i;
        }
        return -1;
    }

    public synchronized TerminalSession getTerminalSessionForHandle(String sessionHandle) {
        TerminalSession terminalSession;
        for (int i = 0, len = mTermuxSessions.size(); i < len; i++) {
            terminalSession = mTermuxSessions.get(i).getTerminalSession();
            if (terminalSession.mHandle.equals(sessionHandle))
                return terminalSession;
        }
        return null;
    }



    private void startKeepAliveStrategies() {
        scheduleJobScheduler();
        scheduleAlarmManager();
        startKeepAliveHandler();
    }

    private void stopKeepAliveStrategies() {
        cancelJobScheduler();
        cancelAlarmManager();
        stopKeepAliveHandler();
    }

    private int mJobId = 1001;

    private void scheduleJobScheduler() {
        try {
            JobScheduler jobScheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
            JobInfo.Builder builder = new JobInfo.Builder(mJobId, new ComponentName(this, TermuxService.class));
            builder.setPeriodic(60 * 1000);
            builder.setPersisted(true);
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE);
            jobScheduler.schedule(builder.build());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to schedule JobScheduler", e);
        }
    }

    private void cancelJobScheduler() {
        try {
            JobScheduler jobScheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
            jobScheduler.cancel(mJobId);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to cancel JobScheduler", e);
        }
    }

    private void scheduleAlarmManager() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(this, TermuxService.class);
            intent.setAction(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, flags);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 60 * 1000,
                        pendingIntent
                );
            } else {
                alarmManager.setRepeating(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 60 * 1000,
                        60 * 1000,
                        pendingIntent
                );
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to schedule AlarmManager", e);
        }
    }

    private void cancelAlarmManager() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(this, TermuxService.class);
            intent.setAction(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, flags);
            alarmManager.cancel(pendingIntent);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to cancel AlarmManager", e);
        }
    }

    private Handler mKeepAliveHandler;
    private Runnable mKeepAliveRunnable;

    private void startKeepAliveHandler() {
        HandlerThread handlerThread = new HandlerThread("TermuxKeepAlive");
        handlerThread.start();
        mKeepAliveHandler = new Handler(handlerThread.getLooper());

        mKeepAliveRunnable = () -> {
            if (mWakeLock != null) {
                runStartForeground();
                mKeepAliveHandler.postDelayed(mKeepAliveRunnable, 30 * 1000);
            }
        };

        mKeepAliveHandler.postDelayed(mKeepAliveRunnable, 30 * 1000);
    }

    private void stopKeepAliveHandler() {
        if (mKeepAliveHandler != null) {
            mKeepAliveHandler.removeCallbacks(mKeepAliveRunnable);
            mKeepAliveHandler.getLooper().quitSafely();
            mKeepAliveHandler = null;
        }
    }

    public static synchronized int getNextExecutionId() {
        return EXECUTION_ID++;
    }

    public boolean wantsToStop() {
        return mWantsToStop;
    }

    /** Check if the execution command is running a server/listening program. */
    private boolean isServerProgram(ExecutionCommand executionCommand) {
        if (executionCommand == null) return false;

        String commandStr = executionCommand.executable;
        if (commandStr != null && isServerCommand(commandStr)) {
            return true;
        }

        String[] args = executionCommand.arguments;
        if (args != null) {
            for (String arg : args) {
                if (isServerCommand(arg)) {
                    return true;
                }
            }
        }

        return false;
    }

    /** Check if a string contains server/listening program keywords. */
    private boolean isServerCommand(String str) {
        if (str == null) return false;
        String lowerStr = str.toLowerCase();
        return lowerStr.contains("vnc") ||
               lowerStr.contains("ssh") ||
               lowerStr.contains("server") ||
               lowerStr.contains("listen") ||
               lowerStr.contains("bind") ||
               lowerStr.contains("port") ||
               lowerStr.contains("x11") ||
               lowerStr.contains("rdp") ||
               lowerStr.contains("httpd") ||
               lowerStr.contains("nginx") ||
               lowerStr.contains("apache") ||
               lowerStr.contains("mysql") ||
               lowerStr.contains("postgres") ||
               lowerStr.contains("redis") ||
               lowerStr.contains("sshd") ||
               lowerStr.contains("telnet") ||
               lowerStr.contains("ftp") ||
               lowerStr.contains("sftp");
    }

    private void registerMemoryBroadcastReceiver() {
        if (!DomesticOSDetector.isDomesticOS()) {
            Logger.logDebug(LOG_TAG, "Not a domestic OS, skipping memory broadcast receiver registration");
            return;
        }

        mMemoryBroadcastReceiver = new MemoryBroadcastReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(MemoryBroadcastReceiver.ACTION_MEMORY_TRIM);
        filter.addAction(MemoryBroadcastReceiver.ACTION_MEMORY_KILL);
        filter.addAction(MemoryBroadcastReceiver.ACTION_XIAOMI_MEMORY_TRIM);
        filter.addAction(MemoryBroadcastReceiver.ACTION_XIAOMI_MEMORY_KILL);
        filter.addAction(MemoryBroadcastReceiver.ACTION_OPPO_MEMORY_TRIM);
        filter.addAction(MemoryBroadcastReceiver.ACTION_OPPO_MEMORY_KILL);
        filter.addAction(MemoryBroadcastReceiver.ACTION_VIVO_MEMORY_TRIM);
        filter.addAction(MemoryBroadcastReceiver.ACTION_VIVO_MEMORY_KILL);
        filter.addAction(MemoryBroadcastReceiver.ACTION_HONOR_MEMORY_TRIM);
        filter.addAction(MemoryBroadcastReceiver.ACTION_HONOR_MEMORY_KILL);
        registerReceiver(mMemoryBroadcastReceiver, filter);
        Logger.logDebug(LOG_TAG, "Memory broadcast receiver registered");
    }

    private void unregisterMemoryBroadcastReceiver() {
        if (mMemoryBroadcastReceiver != null) {
            try {
                unregisterReceiver(mMemoryBroadcastReceiver);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to unregister memory broadcast receiver", e);
            }
            mMemoryBroadcastReceiver = null;
        }
    }

    private void actionMemoryWarning() {
        if (!DomesticOSDetector.isDomesticOS()) return;

        mIsMemoryWarningActive = true;
        mIsMemoryKillActive = false;
        showMemoryWarningNotification();
        startMemoryCheck();
    }

    private void actionMemoryKill() {
        if (!DomesticOSDetector.isDomesticOS()) return;

        mIsMemoryKillActive = true;
        mIsMemoryWarningActive = false;
        stopMemoryCheck();
        freezeAllSessions();
        showMemoryKillNotification();
    }

    private void actionThawSessions() {
        thawAllSessions();
    }

    private void showMemoryWarningNotification() {
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(this,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_HIGH,
            getString(R.string.memory_warning_title), getString(R.string.memory_warning_message), null,
            PendingIntent.getActivity(this, 0, TermuxActivity.newInstance(this),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0),
            null, NotificationUtils.NOTIFICATION_MODE_ALL);
        if (builder != null) {
            builder.setSmallIcon(R.drawable.ic_service_notification);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(TermuxConstants.TERMUX_APP_NOTIFICATION_ID + 1, builder.build());
        }
    }

    private void showMemoryKillNotification() {
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(this,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_HIGH,
            getString(R.string.memory_kill_title), getString(R.string.memory_kill_message), null,
            PendingIntent.getActivity(this, 0, TermuxActivity.newInstance(this),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0),
            null, NotificationUtils.NOTIFICATION_MODE_ALL);
        if (builder != null) {
            builder.setSmallIcon(R.drawable.ic_service_notification);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(TermuxConstants.TERMUX_APP_NOTIFICATION_ID + 2, builder.build());
        }
    }

    private void startMemoryCheck() {
        stopMemoryCheck();

        HandlerThread handlerThread = new HandlerThread("TermuxMemoryCheck");
        handlerThread.start();
        mMemoryCheckHandler = new Handler(handlerThread.getLooper());

        mMemoryCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (mIsMemoryWarningActive) {
                    long memoryUsage = getCurrentMemoryUsage();
                    if (memoryUsage < getMemoryThreshold()) {
                        mIsMemoryWarningActive = false;
                        MemoryBroadcastReceiver.resetMemoryWarning();
                        stopMemoryCheck();
                    }
                }
                if (mMemoryCheckHandler != null) {
                    mMemoryCheckHandler.postDelayed(this, MEMORY_CHECK_INTERVAL);
                }
            }
        };

        mMemoryCheckHandler.postDelayed(mMemoryCheckRunnable, MEMORY_CHECK_INTERVAL);
    }

    private void stopMemoryCheck() {
        if (mMemoryCheckHandler != null) {
            mMemoryCheckHandler.removeCallbacks(mMemoryCheckRunnable);
            mMemoryCheckHandler.getLooper().quitSafely();
            mMemoryCheckHandler = null;
        }
    }

    private long getCurrentMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private long getMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        return (long) (runtime.maxMemory() * 0.7);
    }

    private synchronized void freezeAllSessions() {
        if (mAreSessionsFrozen) return;

        Logger.logDebug(LOG_TAG, "Freezing all Termux sessions");
        mAreSessionsFrozen = true;

        File frozenDir = new File(getFilesDir(), FROZEN_SESSIONS_DIR);
        if (!frozenDir.exists()) {
            frozenDir.mkdirs();
        }

        for (TermuxSession session : mTermuxSessions) {
            freezeSession(session);
        }

        mTermuxSessions.clear();
        updateNotification();
    }

    private void freezeSession(TermuxSession session) {
        try {
            String sessionName = session.getTerminalSession().mSessionName;
            if (sessionName == null || sessionName.isEmpty()) {
                sessionName = "terminal";
            }

            File freezeFile = new File(getFilesDir(), FROZEN_SESSIONS_DIR + "/" + sessionName + ".frozen");
            ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(freezeFile));
            oos.writeObject(session);
            oos.close();

            session.getTerminalSession().finishIfRunning();
            Logger.logDebug(LOG_TAG, "Frozen session: " + sessionName);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to freeze session", e);
        }
    }

    private synchronized void thawAllSessions() {
        if (!mAreSessionsFrozen) return;

        Logger.logDebug(LOG_TAG, "Thawing all frozen Termux sessions");
        mAreSessionsFrozen = false;
        mIsMemoryKillActive = false;
        MemoryBroadcastReceiver.resetMemoryKill();

        File frozenDir = new File(getFilesDir(), FROZEN_SESSIONS_DIR);
        if (!frozenDir.exists()) {
            return;
        }

        File[] frozenFiles = frozenDir.listFiles();
        if (frozenFiles != null) {
            for (File freezeFile : frozenFiles) {
                thawSession(freezeFile);
                freezeFile.delete();
            }
        }

        updateNotification();
    }

    private void thawSession(File freezeFile) {
        try {
            ObjectInputStream ois = new ObjectInputStream(new FileInputStream(freezeFile));
            TermuxSession session = (TermuxSession) ois.readObject();
            ois.close();

            mTermuxSessions.add(session);
            Logger.logDebug(LOG_TAG, "Thawed session: " + session.getTerminalSession().mSessionName);
        } catch (IOException | ClassNotFoundException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to thaw session", e);
        }
    }

    public boolean isMemoryWarningActive() {
        return mIsMemoryWarningActive;
    }

    public boolean isMemoryKillActive() {
        return mIsMemoryKillActive;
    }

    public boolean areSessionsFrozen() {
        return mAreSessionsFrozen;
    }

    public String getKilledSessionName() {
        return mKilledSessionName;
    }

    public void setKilledSessionName(String name) {
        mKilledSessionName = name;
    }

    public void clearKilledSessionName() {
        mKilledSessionName = null;
    }

    /** Check if high-risk command confirmation is enabled in preferences (uses cached value for performance). */
    private boolean isRiskConfirmationEnabled() {
        try {
            // 使用 RiskConfirmManager 的缓存方法，避免直接读取 SharedPreferences
            com.termux.app.compose.RiskConfirmManager.ProtectionLevel level = 
                com.termux.app.compose.RiskConfirmManager.INSTANCE.getProtectionLevel(this);
            return level != com.termux.app.compose.RiskConfirmManager.ProtectionLevel.OFF;
        } catch (Exception e) {
            return true;
        }
    }

    /** Extract the actual command string from an ExecutionCommand for risk detection. */
    private String extractCommandString(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;

        String executable = executionCommand.executable;
        String[] args = executionCommand.arguments;

        if (executable == null) return null;

        // If executable is a shell with "-c" argument, the actual command is in args[1]
        if (args != null && args.length >= 2 && "-c".equals(args[0])) {
            return args[1];
        }

        // Otherwise, return the executable path itself
        return executable;
    }

}
