package com.termux.app

import com.termux.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.OobePermissionIds
import com.termux.app.compose.OobeScreen

class OobeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IS_UPGRADE = "extra_is_upgrade"
        
        // 许可条款最终修改日期 (YYYYMMDD)
        const val EULA_LAST_MODIFIED = "20260916"

        private const val TAG_PERM = "OobePermissions"
    }

    private var isUpgrade by mutableStateOf(false)
    private var currentPage by mutableStateOf(0)
    private var eulaAgreed by mutableStateOf(false)
    
    private var permissionStatus by mutableStateOf("")
    private var isPermissionGranted by mutableStateOf(false)

    // 逐项权限状态：key 见 OobePermissionIds，value=是否真实持有。
    // 之前只把结果聚合成 permissionStatus/isPermissionGranted 两个值下发，
    // 逐项信息在 Activity 边界就被压扁了，导致 UI 只能硬编码 granted=true。
    private var permissionStates by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var isPermissionLoading by mutableStateOf(true)
    private var permissionLoadFailed by mutableStateOf(false)
    private var isBootstrapping by mutableStateOf(false)
    private var isDownloading by mutableStateOf(false)
    private var isInstalling by mutableStateOf(false)
    private var bootstrapComplete by mutableStateOf(false)
    private var bootstrapError by mutableStateOf<String?>(null)
    
    private var releaseNotes by mutableStateOf<String?>(null)

    private val normalPermissions = arrayOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.VIBRATE
    )

    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var manageStorageLauncher: ActivityResultLauncher<Intent>

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.attachBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 主题里已移除 windowTranslucentStatus/Navigation（那两个标志会盖半透明蒙版），
        // 边到边改为显式开启 —— 与其它使用 KiTerminalTheme 的页面保持一致。
        // OobeScreen 依赖 statusBars 内边距做顶部留白，必须保持延伸到状态栏，否则会多出一段空白。
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        isUpgrade = intent.getBooleanExtra(EXTRA_IS_UPGRADE, false)
        Log.d("OobeActivity", "isUpgrade=$isUpgrade")

        try {
            requestPermissionsLauncher = registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { _ ->
                updatePermissionStatus()
                // 运行时权限批结束后接着把「文件存储」补齐，否则串行申请会断在这一步。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    manageStorageLauncher.launch(intent)
                }
            }

            manageStorageLauncher = registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { _ ->
                updatePermissionStatus()
            }

            updatePermissionStatus()
            fetchReleaseNotes()

            setContent {
                val navDispatcher = com.termux.app.compose.NavigationHelper.createDispatcher()
                val navDispatcherOwner = com.termux.app.compose.NavigationHelper.createOwner(navDispatcher)
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner provides navDispatcherOwner
                ) {
                    KiTerminalTheme {
                        OobeScreen(
                            isUpgrade = isUpgrade,
                            currentPage = currentPage,
                            onPageChange = { page -> currentPage = page },
                            eulaAgreed = eulaAgreed,
                            onEulaAgreeChange = { agreed -> eulaAgreed = agreed },
                            eulaLastModified = EULA_LAST_MODIFIED,
                            eulaLastStored = SplashActivity.getEulaDate(this),
                            permissionStatus = permissionStatus,
                            isPermissionGranted = isPermissionGranted,
                            permissionStates = permissionStates,
                            isPermissionLoading = isPermissionLoading,
                            permissionLoadFailed = permissionLoadFailed,
                            isBootstrapping = isBootstrapping,
                            isDownloading = isDownloading,
                            isInstalling = isInstalling,
                            bootstrapComplete = bootstrapComplete,
                            bootstrapError = bootstrapError,
                            releaseNotes = releaseNotes,
                            currentVersionName = com.termux.BuildConfig.VERSION_NAME,
                            onGrantAllPermissions = { grantAllPermissions() },
                            onStartBootstrap = { performBootstrap() },
                            onRetryBootstrap = { retryBootstrap() },
                            onExitApp = { exitApp() },
                            onCompleteStart = { startMainActivity() },
                            onCompleteFinish = { finish() }
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            FallbackHelper.onOobeRenderFailure(this, t)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updatePermissionStatus()
        } catch (t: Throwable) {
            FallbackHelper.onOobeRenderFailure(this, t)
        }
    }

    private fun fetchReleaseNotes() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val currentVersion = com.termux.BuildConfig.VERSION_NAME
                
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val request = okhttp3.Request.Builder()
                    .url("https://api.github.com/repos/tig-kira/termux-ultra/releases?per_page=30")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    
                    val body = response.body?.string() ?: ""
                    val releases = org.json.JSONArray(body)
                    
                    for (i in 0 until releases.length()) {
                        val release = releases.getJSONObject(i)
                        if (release.optBoolean("draft", false)) continue
                        
                        val tagName = release.optString("tag_name", "")
                        val plainTag = tagName.removePrefix("v").removePrefix("V")
                        if (plainTag == currentVersion || tagName == currentVersion) {
                            val notes = release.optString("body", "")
                            if (notes.isNotBlank()) {
                                releaseNotes = notes
                            }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("OobeActivity", "Failed to fetch release notes: ${e.message}")
            }
        }
    }

    private fun performBootstrap() {
        isBootstrapping = true
        isDownloading = true
        isInstalling = false
        bootstrapError = null
        // 必须复位：安装页的 when 里 bootstrapComplete 分支排在 bootstrapError 之前，
        // 不清零的话「成功过一次之后再失败」会继续显示成功页而盖掉失败原因。
        bootstrapComplete = false

        // OOBE 自带 Compose 进度与两阶段文案，用回调接住阶段与失败，避免叠系统弹窗。
        val callback = object : TermuxInstaller.BootstrapCallback {
            override fun onDownloadStart() {
                isDownloading = true
                isInstalling = false
            }

            override fun onInstallStart() {
                isDownloading = false
                isInstalling = true
            }

            override fun onError(message: String) {
                isBootstrapping = false
                isDownloading = false
                isInstalling = false
                bootstrapError = message
            }
        }

        TermuxInstaller.setupBootstrapIfNeeded(
            this,
            { // whenDone：已在 UI 线程
                // Bootstrap zip 解压成功后，立即建立 storage symlinks
                try {
                    TermuxInstaller.setupStorageSymlinks(this)
                } catch (_: Throwable) {}
                isBootstrapping = false
                isDownloading = false
                isInstalling = false
                bootstrapComplete = true
            },
            callback
        )
    }

    private fun retryBootstrap() {
        bootstrapError = null
        performBootstrap()
    }

    private fun grantAllPermissions() {
        val deniedPermissions = normalPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        // 两类权限要串行申请，不能用 else if 互斥：只要有运行时权限被拒，
        // 文件存储的特殊权限设置页就永远打不开，用户无法补齐。
        if (deniedPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(deniedPermissions.toTypedArray())
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            manageStorageLauncher.launch(intent)
        } else {
            updatePermissionStatus()
        }
    }

    /** 「文件存储」是否真实持有：Android 11+ 是全文件访问特殊权限，之前是普通权限。 */
    private fun isFileStorageGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** 网络能力要 INTERNET 与 ACCESS_NETWORK_STATE 同时具备才算完整。 */
    private fun isNetworkAccessGranted(): Boolean =
        isGranted(Manifest.permission.INTERNET) && isGranted(Manifest.permission.ACCESS_NETWORK_STATE)

    private fun updatePermissionStatus() {
        // 逐项查询。任一项查询抛异常都不能让整页崩掉，也不能谎报全部已授权：
        // 标记为加载失败并清空状态，UI 会显示「无法读取」且所有项保持未勾选。
        val states: Map<String, Boolean> = try {
            linkedMapOf<String, Boolean>(
                OobePermissionIds.NETWORK to isNetworkAccessGranted(),
                OobePermissionIds.FILE_STORAGE to isFileStorageGranted(),
                OobePermissionIds.WAKE_LOCK to isGranted(Manifest.permission.WAKE_LOCK),
                OobePermissionIds.VIBRATE to isGranted(Manifest.permission.VIBRATE)
            )
        } catch (t: Throwable) {
            Log.w(TAG_PERM, "Failed to query permission states", t)
            permissionStates = emptyMap()
            isPermissionLoading = false
            permissionLoadFailed = true
            isPermissionGranted = false
            permissionStatus = getString(R.string.oobe_permission_status_unavailable)
            return
        }

        permissionStates = states
        isPermissionLoading = false
        permissionLoadFailed = false

        // 空结果不能算「全部已授权」，否则会放行一个什么都没查到的页面。
        isPermissionGranted = states.isNotEmpty() && states.values.all { it }
        permissionStatus = if (isPermissionGranted) {
            getString(R.string.oobe_permission_all_granted)
        } else {
            String.format(
                "%s %d/%d", getString(R.string.oobe_permission_progress),
                states.values.count { it }, states.size
            )
        }
    }

    private fun exitApp() {
        SplashActivity.resetOobe(this)
        finish()
    }

    private fun startMainActivity() {
        SplashActivity.setEulaDate(this, EULA_LAST_MODIFIED)
        SplashActivity.setProvisioned(this, true)
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(0, 0)
    }

}
