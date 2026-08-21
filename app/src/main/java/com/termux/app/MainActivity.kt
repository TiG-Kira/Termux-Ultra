package com.termux.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.compose.NavigationHelper
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.termux.app.activities.AboutActivity
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.MainScreen
import com.termux.app.compose.RiskConfirmDialogHost
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.shell.TermuxSession as SharedTermuxSession
import com.termux.app.TermuxService
import com.termux.terminal.TerminalSession

class AppViewModel : ViewModel() {
    private val _showVnc = MutableStateFlow(false)
    val showVnc: StateFlow<Boolean> = _showVnc

    fun updateShowVnc(value: Boolean) {
        _showVnc.value = value
    }
}

class MainActivity : FragmentActivity() {

    private var termuxService: TermuxService? = null
    private var sessions by mutableStateOf<List<SharedTermuxSession>>(emptyList())
    private var selectedTab by mutableStateOf(0)
    private var isWakeLockEnabled by mutableStateOf(false)
    private lateinit var appViewModel: AppViewModel
    private val handler = Handler(Looper.getMainLooper())

    /** True if activity was launched (or is being resumed) from the notification
     *  "end sessions" action. When true, we show a data-loss warning dialog if VM/container
     *  processes are running, and then instruct TermuxService to force-stop sessions. */
    private var pendingTriggerStopService = false

    /** True if activity was launched (or is being resumed) from the notification
     *  "quit app" action. When true, we show a data-loss warning dialog if VM/container
     *  processes are running, and then instruct TermuxService to force-quit the app. */
    private var pendingTriggerQuitApp = false

    /** If between onStart() and onStop(). Used to decide whether to show the OverlayDialog
     *  immediately or defer it to onStart(). */
    private var isVisible = false

    /**
     * 会话列表 & 服务状态的后台轮询周期（毫秒）。
     *
     * 用户在后台运行 Termux 会话时，会话可能自行结束（例如脚本执行完毕、exit、被系统杀死），
     * 如果没有定期刷新，MainScreen 终端列表卡片会一直显示过期的会话数量。
     * 这里采用 2 秒轮询（比 UtilityCenterActivity 的 3 秒更积极），
     * 让用户切回应用时能立刻看到最新状态。
     */
    private val sessionRefreshPeriodMs: Long = 2000
    private val sessionRefreshCallback = object : Runnable {
        override fun run() {
            if (!isDestroyed) {
                try {
                    updateSessions()
                    updateWakeLockState()
                } catch (_: Throwable) {
                    // 低版本 API 可能偶发崩溃，忽略避免 App 崩溃
                }
                handler.postDelayed(this, sessionRefreshPeriodMs)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as TermuxService.LocalBinder
            termuxService = binder.service
            updateSessions()
            updateWakeLockState()
            // 服务绑定后开启后台会话列表轮询，保证即使 Termux 在后台运行
            // （会话自行结束/exit/被系统杀死）时终端卡片也能实时更新。
            // 先移除已有回调，避免重复调度。
            handler.removeCallbacks(sessionRefreshCallback)
            handler.postDelayed(sessionRefreshCallback, sessionRefreshPeriodMs)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            termuxService = null
            handler.removeCallbacks(sessionRefreshCallback)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.attachBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.navigationBarColor = android.graphics.Color.TRANSPARENT

            val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val initialShowVnc = prefs.getBoolean("vnc_enabled", false)

            // Handle the EXTRA_TRIGGER_STOP_SERVICE / EXTRA_TRIGGER_QUIT_APP sent from the
            // notification buttons. We'll process these in onStart() once the activity is
            // visible (so that OverlayDialog can attach to a valid window), but remember the
            // intent here so it's not lost after rotation or process death.
            val i = intent
            if (i != null) {
                if (i.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_STOP_SERVICE, false)) {
                    pendingTriggerStopService = true
                    i.removeExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_STOP_SERVICE)
                }
                if (i.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_QUIT_APP, false)) {
                    pendingTriggerQuitApp = true
                    i.removeExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_QUIT_APP)
                }
            }

            if ("SHOW_SFTP_INFO" == intent?.action) {
                prefs.edit().putBoolean("showSftpInfo", true).apply()
                selectedTab = 1
            }

            appViewModel = ViewModelProvider(this)[AppViewModel::class.java]
            appViewModel.updateShowVnc(initialShowVnc)

            val intent = Intent(this, TermuxService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

            setContent {
                val navDispatcher = remember { NavigationHelper.createDispatcher() }
                val navDispatcherOwner = remember { NavigationHelper.createOwner(navDispatcher) }
                CompositionLocalProvider(
                    LocalNavigationEventDispatcherOwner provides navDispatcherOwner
                ) {
                KiTerminalTheme {
                    val showVnc by appViewModel.showVnc.collectAsState()

                    MainScreen(
                            selectedTab = selectedTab,
                            onTabChange = { index -> selectedTab = index },
                            sessions = sessions,
                            onSessionClick = { session ->
                                val intent = Intent(this, TermuxActivity::class.java)
                                intent.putExtra("sessionHandle", session.getTerminalSession().mHandle)
                                startActivity(intent)
                            },
                            onNewTerminal = {
                                val sessionCount = sessions.size
                                val sessionName = if (LocaleHelper.isChinese(this)) {
                                    "会话 ${sessionCount + 1}"
                                } else {
                                    "Session ${sessionCount + 1}"
                                }
                                termuxService?.createTermuxSession(null, null, null, null, false, sessionName)
                                updateSessions()
                                handler.postDelayed({ updateSessions() }, 500)
                            },
                            onStopTerminal = { session ->
                                termuxService?.removeTermuxSession(session.getTerminalSession())
                                updateSessions()
                                handler.postDelayed({ updateSessions() }, 300)
                            },
                            onRenameTerminal = { session, newName ->
                                session.getTerminalSession().mSessionName = newName
                                updateSessions()
                            },
                            onExecuteScript = { scriptName, command ->
                                val sessionName = scriptName
                                val newSession = termuxService?.createTermuxSession(
                                    null,
                                    arrayOf("-c", command),
                                    null,
                                    null,
                                    false,
                                    sessionName
                                )
                                updateSessions()
                                handler.postDelayed({ updateSessions() }, 500)
                                if (newSession != null) {
                                    val intent = Intent(this, TermuxActivity::class.java)
                                    intent.putExtra("sessionHandle", newSession.getTerminalSession().mHandle)
                                    startActivity(intent)
                                }
                            },
                            onAboutClick = { startActivity(Intent(this, AboutActivity::class.java)) },
                            showVnc = showVnc,
                            isWakeLockEnabled = isWakeLockEnabled,
                            onToggleWakeLock = { toggleWakeLock() },
                            onRefreshSessions = { updateSessions() }
                        )
                    RiskConfirmDialogHost(collectSnackbarEvents = false)
                }
                }
            }
        } catch (t: Throwable) {
            // 启动阶段渲染异常 → 按崩溃位置粒度降级：
            // 能识别到具体页面的 → 屏蔽该页面并重建（让 MainScreen 过滤入口）
            // 无法识别 → 终端锁定 Fallback
            FallbackHelper.onMainRenderFailure(this, t)
        }
    }

    override fun onStart() {
        super.onStart()
        isVisible = true

        // 通知"结束会话/停止程序"按钮跳转到此。延迟触发弹窗，确保主页 Compose
        // 内容先完成首帧渲染，避免白屏后弹窗。
        if (pendingTriggerStopService) {
            pendingTriggerStopService = false
            handler.postDelayed({
                com.termux.app.compose.StopConfirmDialog.start(this, isQuitApp = false)
            }, 300)
        }
        if (pendingTriggerQuitApp) {
            pendingTriggerQuitApp = false
            handler.postDelayed({
                com.termux.app.compose.StopConfirmDialog.start(this, isQuitApp = true)
            }, 300)
        }
    }

    override fun onResume() {
        try {
            super.onResume()
            // 从后台回到前台，立即刷新会话数量与服务状态 —— 解决"后台会话已结束但仍显示旧数量"的不实时问题
            updateSessions()
            updateWakeLockState()
            val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val currentShowVnc = prefs.getBoolean("vnc_enabled", false)
            appViewModel.updateShowVnc(currentShowVnc)
            // 确保后台轮询仍在运行（onStop/系统回收可能导致 handler 回调被暂停或清理）
            if (termuxService != null) {
                handler.removeCallbacks(sessionRefreshCallback)
                handler.postDelayed(sessionRefreshCallback, sessionRefreshPeriodMs)
            }
        } catch (t: Throwable) {
            // onResume 期间也可能因低版本缺少 API 而崩溃，统一走分级降级
            FallbackHelper.onMainRenderFailure(this, t)
        }
    }

    override fun onStop() {
        super.onStop()
        isVisible = false
        // Activity 进入后台时保留轮询，方便用户过一会儿回来时已经是最新状态；
        // 但如果进程被系统回收，则由 onDestroy 统一清理回调。
        // 这里不主动停止轮询。
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 防止外部/系统重新派发的 ACTION_SERVICE_EXECUTE 等 intent 被错误地当成新会话请求
        setIntent(intent)

        // Activity is already running (single-top / reorder-to-front), and the user
        // tapped a notification action again.
        if (intent.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_STOP_SERVICE, false)) {
            intent.removeExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_STOP_SERVICE)
            if (isVisible) {
                com.termux.app.compose.StopConfirmDialog.start(this, isQuitApp = false)
            } else {
                pendingTriggerStopService = true
            }
        }
        if (intent.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_QUIT_APP, false)) {
            intent.removeExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_TRIGGER_QUIT_APP)
            if (isVisible) {
                com.termux.app.compose.StopConfirmDialog.start(this, isQuitApp = true)
            } else {
                pendingTriggerQuitApp = true
            }
        }

        updateSessions()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        unbindService(serviceConnection)
    }

    private fun updateSessions() {
        sessions = termuxService?.getTermuxSessions()?.toList() ?: emptyList()
    }

    private fun updateWakeLockState() {
        isWakeLockEnabled = termuxService?.isWakeLockHeld() ?: false
    }

    private fun toggleWakeLock() {
        val service = termuxService ?: return
        val intent = Intent(this, TermuxService::class.java)
        intent.action = if (service.isWakeLockHeld()) {
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_UNLOCK
        } else {
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_LOCK
        }
        startService(intent)
        handler.postDelayed({ updateWakeLockState() }, 500)
    }
}
