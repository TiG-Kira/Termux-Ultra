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
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.termux.app.compose.AboutScreen
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.MainScreen
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

class MainActivity : ComponentActivity() {

    private var termuxService: TermuxService? = null
    private var sessions by mutableStateOf<List<SharedTermuxSession>>(emptyList())
    private var selectedTab by mutableStateOf(0)
    private var showAbout by mutableStateOf(false)
    private var isWakeLockEnabled by mutableStateOf(false)
    private lateinit var appViewModel: AppViewModel
    private val handler = Handler(Looper.getMainLooper())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as TermuxService.LocalBinder
            termuxService = binder.service
            updateSessions()
            updateWakeLockState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            termuxService = null
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.attachBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val initialShowVnc = prefs.getBoolean("vnc_enabled", false)
        
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
            KiTerminalTheme {
                val showVnc by appViewModel.showVnc.collectAsState()

                if (showAbout) {
                    BackHandler { showAbout = false }
                    AboutScreen(onBack = { showAbout = false })
                } else {
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
                            val wrappedCommand = command + "; echo ''; echo '脚本执行完成，按回车键退出...'; read"
                            val newSession = termuxService?.createTermuxSession(
                                null,
                                arrayOf("-c", wrappedCommand),
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
                        onTypeInSession = { sessionId, command ->
                            try {
                                val session = sessions.find {
                                    it.getTerminalSession().mSessionName == sessionId ||
                                    it.getTerminalSession().mHandle.toString() == sessionId
                                }
                                session?.let { ts ->
                                    val terminalSession = ts.getTerminalSession()
                                    if (!terminalSession.isRunning()) {
                                        val intent = Intent(this, TermuxActivity::class.java)
                                        intent.putExtra("sessionHandle", terminalSession.mHandle)
                                        startActivity(intent)
                                        android.os.Handler().postDelayed({
                                            if (terminalSession.isRunning()) {
                                                terminalSession.write(command + "\n")
                                            }
                                        }, 2000)
                                    } else {
                                        terminalSession.write(command + "\n")
                                        val intent = Intent(this, TermuxActivity::class.java)
                                        intent.putExtra("sessionHandle", terminalSession.mHandle)
                                        startActivity(intent)
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        onAboutClick = { showAbout = true },
                        showVnc = showVnc,
                        isWakeLockEnabled = isWakeLockEnabled,
                        onToggleWakeLock = { toggleWakeLock() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateSessions()
        updateWakeLockState()
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val currentShowVnc = prefs.getBoolean("vnc_enabled", false)
        appViewModel.updateShowVnc(currentShowVnc)
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
