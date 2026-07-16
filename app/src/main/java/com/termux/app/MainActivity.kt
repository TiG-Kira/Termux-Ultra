package com.termux.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.app.compose.AboutScreen
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.MainScreen
import com.termux.shared.shell.TermuxSession as SharedTermuxSession
import com.termux.app.TermuxService
import com.termux.terminal.TerminalSession

class MainActivity : ComponentActivity() {

    private var termuxService: TermuxService? = null
    private var sessions by mutableStateOf<List<SharedTermuxSession>>(emptyList())
    private var selectedTab by mutableStateOf(0)
    private var showAbout by mutableStateOf(false)
    private var showVnc by mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as TermuxService.LocalBinder
            termuxService = binder.service
            updateSessions()
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
        showVnc = prefs.getBoolean("vnc_enabled", false)

        val intent = Intent(this, TermuxService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            KiTerminalTheme {
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
                        onAboutClick = { showAbout = true },
                        showVnc = showVnc
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateSessions()
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        showVnc = prefs.getBoolean("vnc_enabled", false)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        unbindService(serviceConnection)
    }

    private fun updateSessions() {
        sessions = termuxService?.getTermuxSessions()?.toList() ?: emptyList()
    }
}
