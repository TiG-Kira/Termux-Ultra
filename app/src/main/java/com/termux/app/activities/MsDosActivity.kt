package com.termux.app.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.R
import com.termux.app.TermuxService
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.MsDosAssetDownloader
import com.termux.app.compose.MsDosLauncher
import com.termux.app.compose.MsDosPrefs
import com.termux.app.compose.MsDosScreen
import com.termux.app.compose.NavigationHelper
import com.termux.app.vnc.VncConnection
import com.termux.app.vnc.connectToVnc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** MS-DOS 4.0 模拟器独立页：创建 Termux 会话启动 QEMU，VNC 模式在端口就绪后拉起 AVNC。 */
class MsDosActivity : ComponentActivity() {

    private val vncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        MsDosPrefs.init(this)
        setContent {
            val dispatcher = NavigationHelper.createDispatcher()
            val owner = NavigationHelper.createOwner(dispatcher)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                KiTerminalTheme {
                    MsDosScreen(
                        onBack = { finish() },
                        onLaunch = { mode -> launch(mode) },
                        onOpenDownloadPage = {
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(MsDosAssetDownloader.DOWNLOAD_PAGE_URL)
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    private var termuxService: TermuxService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            termuxService = (service as TermuxService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            termuxService = null
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, TermuxService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        try { unbindService(serviceConnection) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        vncScope.cancel()
    }

    private fun launch(mode: MsDosLauncher.Mode) {
        if (!MsDosAssetDownloader.isDiskReady()) {
            Toast.makeText(this, R.string.msdos_asset_missing, Toast.LENGTH_SHORT).show()
            return
        }
        if (MsDosLauncher.isContainerMissing()) {
            Toast.makeText(this, R.string.msdos_container_missing, Toast.LENGTH_LONG).show()
            return
        }
        vncScope.launch {
            val port = withContext(Dispatchers.IO) {
                if (mode == MsDosLauncher.Mode.VNC) {
                    findFreeVncPort(MsDosPrefs.config.value.vncPort)
                } else {
                    MsDosPrefs.config.value.vncPort
                }
            }
            if (port < 0) {
                Toast.makeText(this@MsDosActivity, R.string.msdos_port_exhausted, Toast.LENGTH_LONG).show()
                return@launch
            }
            val command = MsDosLauncher.buildCommand(this@MsDosActivity, mode, port)
            if (command == null || isFinishing || isDestroyed) {
                Toast.makeText(this@MsDosActivity, R.string.msdos_launch_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val session = termuxService?.createTermuxSession(
                null, arrayOf("-c", command), null, null, false, "MS-DOS 4.0"
            )
            if (session == null) {
                Toast.makeText(this@MsDosActivity, R.string.msdos_launch_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(
                Intent(this@MsDosActivity, com.termux.app.TermuxActivity::class.java)
                    .putExtra("sessionHandle", session.getTerminalSession().mHandle)
            )
            if (mode == MsDosLauncher.Mode.VNC) {
                openVncWhenReady(port)
            }
        }
    }

    /** 与已运行的 QEMU VM 抢 5900 时向后顺延；全部占用返回 -1，不回落到可能属于别人的端口。 */
    private fun findFreeVncPort(preferred: Int): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        for (p in preferred.coerceIn(5900, 5999)..5999) {
            try {
                ServerSocket(p, 1, loopback).use { return p }
            } catch (_: Exception) { }
        }
        return -1
    }

    /** 首次在容器里 apt 安装 QEMU 可能要数分钟，轮询窗口放宽到 5 分钟。 */
    private fun openVncWhenReady(port: Int) {
        vncScope.launch {
            val ready = withContext(Dispatchers.IO) {
                repeat(600) {
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                            socket.soTimeout = 500
                            val banner = ByteArray(12)
                            val read = socket.getInputStream().read(banner)
                            if (read >= 4 && String(banner, 0, 4, Charsets.US_ASCII) == "RFB ") {
                                return@withContext true
                            }
                        }
                    } catch (_: Exception) { }
                    delay(500)
                }
                false
            }
            if (isFinishing || isDestroyed) return@launch
            if (ready) {
                connectToVnc(
                    this@MsDosActivity,
                    VncConnection(
                        id = "msdos-vnc-$port",
                        name = "MS-DOS 4.0",
                        host = "127.0.0.1",
                        port = port,
                        password = "",
                        isFromTermux = true
                    )
                )
            } else {
                Toast.makeText(this@MsDosActivity, R.string.msdos_vnc_timeout, Toast.LENGTH_LONG).show()
            }
        }
    }
}
