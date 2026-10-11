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
import com.termux.app.utils.BootstrapDownloader

class OobeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IS_UPGRADE = "extra_is_upgrade"
        
        // 许可条款最终修改日期 (YYYYMMDD)
        const val EULA_LAST_MODIFIED = "20260916"

        private const val TAG_PERM = "OobePermissions"

        // bootstrap zip 通常 ~30MB。读取上限放宽到 256MB，既容纳正常包，
        // 又避免用户误选超大文件时把内存撑爆（低端机 OOM 会直接崩）。
        private const val MAX_LOCAL_ZIP_BYTES = 256L * 1024 * 1024
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

    // ===== 安装方式：online（在线下载，默认）/ local（用户选择的本地自备 bootstrap）=====
    private val InstallModeOnline = "online"
    private val InstallModeLocal = "local"

    // 放在方式常量之后：属性按声明顺序初始化，否则 installMode 会读到未初始化的 InstallModeOnline。
    private var installMode by mutableStateOf(InstallModeOnline)
    private var localZipName by mutableStateOf<String?>(null)
    private var localZipState by mutableStateOf<String?>(null)
    // 选中的包：用户确认「来源可信」后放行安装，需保留字节直到真正安装。
    private var pendingLocalZip by mutableStateOf<LocalBootstrap?>(null)
    // 与官方 SHA-256 不一致时的二次确认弹窗（null=不显示）。
    private var untrustedSourcePrompt by mutableStateOf<UntrustedSourcePrompt?>(null)

    private val normalPermissions = arrayOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.VIBRATE
    )

    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var manageStorageLauncher: ActivityResultLauncher<Intent>
    private lateinit var pickLocalZipLauncher: ActivityResultLauncher<Array<String>>

    /** 「从本地安装」选中的 bootstrap 包。shaMatches=false 表示与内置官方校验不一致。 */
    private data class LocalBootstrap(val name: String, val bytes: ByteArray, val shaMatches: Boolean)

    /** 非官方校验包的确认弹窗数据：保留字节与文件名，确认后直接进入安装。 */
    private data class UntrustedSourcePrompt(val name: String, val bytes: ByteArray)
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

            // 「从本地安装」的文件选择器。ACTION_OPEN_DOCUMENT 由系统选择器代读，
            // 本应用无需持有存储权限即可拿到内容，因此不依赖「文件存储」授权是否完成。
            pickLocalZipLauncher = registerForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) onLocalZipPicked(uri)
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
                            installMode = installMode,
                            localZipName = localZipName,
                            localZipState = localZipState,
                            downloadPageUrl = BootstrapDownloader.DOWNLOAD_PAGE_URL,
                            untrustedSourcePromptActive = untrustedSourcePrompt != null,
                            onInstallModeChange = { mode -> changeInstallMode(mode) },
                            onPickLocalBootstrap = { pickLocalBootstrap() },
                            onStartLocalBootstrap = { performLocalBootstrap() },
                            onConfirmUntrustedSource = { confirmUntrustedSource() },
                            onDismissUntrustedSource = { untrustedSourcePrompt = null },
                            onOpenBootstrapDownloadPage = { openBootstrapDownloadPage() },
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

    /** 安装页主按钮：按当前方式分派到在线下载或本地安装。 */
    private fun performBootstrap() {
        if (installMode == InstallModeLocal) performLocalBootstrap() else performOnlineBootstrap()
    }

    private fun performOnlineBootstrap() {
        isBootstrapping = true
        isDownloading = true
        isInstalling = false
        bootstrapError = null
        // 必须复位：安装页的 when 里 bootstrapComplete 分支排在 bootstrapError 之前，
        // 不清零的话「成功过一次之后再失败」会继续显示成功页而盖掉失败原因。
        bootstrapComplete = false

        // OOBE 自带 Compose 进度与两阶段文案，用回调接住阶段与失败，避免叠系统弹窗。
        TermuxInstaller.setupBootstrapIfNeeded(this, bootstrapDoneRunnable(), bootstrapProgressCallback())
    }

    /**
     * 「从本地安装」：字节已在选择时读出并通过（必要的）校验，
     * 这里只负责把两阶段进度与终态接回 Compose 状态机。
     */
    private fun performLocalBootstrap() {
        val local = pendingLocalZip
        if (local == null) {
            // 理论上不会进这里（UI 已按 state 禁用按钮），兜底防误触发。
            bootstrapError = "未选择本地 bootstrap 文件"
            return
        }

        isBootstrapping = true
        isDownloading = false
        isInstalling = true
        bootstrapError = null
        bootstrapComplete = false

        TermuxInstaller.setupBootstrapFromLocal(
            this, bootstrapDoneRunnable(), bootstrapProgressCallback(), local.bytes
        )
    }

    /** 解压成功收尾：建立 storage symlinks 并把三态全部落定。 */
    private fun bootstrapDoneRunnable(): Runnable = Runnable {
        try {
            TermuxInstaller.setupStorageSymlinks(this)
        } catch (_: Throwable) {}
        isBootstrapping = false
        isDownloading = false
        isInstalling = false
        bootstrapComplete = true
    }

    private fun bootstrapProgressCallback() = object : TermuxInstaller.BootstrapCallback {
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

    /** 用户切换安装方式时清空上一方式的中间状态，避免两种方式的进度/错误互相污染。 */
    private fun changeInstallMode(mode: String) {
        if (isBootstrapping || isDownloading || isInstalling) return
        installMode = mode
        bootstrapError = null
        bootstrapComplete = false
        if (mode != InstallModeLocal) {
            localZipName = null
            localZipState = null
            pendingLocalZip = null
        }
    }

    private fun pickLocalBootstrap() {
        try {
            // MIME 不做过窄限定：不同设备对 zip 的标注差异较大（application/zip、
            // application/octet-stream、application/x-zip-compressed），限定过窄会选不到文件。
            pickLocalZipLauncher.launch(arrayOf("*/*"))
        } catch (t: Throwable) {
            Log.w(TAG_PERM, "Failed to launch local bootstrap picker", t)
            localZipState = "无法打开文件选择器"
        }
    }

    /**
     * 读取并校验用户选中的本地 bootstrap。
     *
     * 判定顺序：非 zip → 直接拒绝；官方 SHA-256 一致 → 放行；
     * 结构合法但校验不一致 → 弹窗请用户确认来源可信，确认后才进入安装。
     */
    private fun onLocalZipPicked(uri: Uri) {
        val name = resolveDisplayName(uri) ?: "bootstrap.zip"
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                localZipName = name
                localZipState = "无法读取所选文件"
                pendingLocalZip = null
                return
            }
            if (bytes.size > MAX_LOCAL_ZIP_BYTES) {
                localZipName = name
                localZipState = "文件过大（超过 ${MAX_LOCAL_ZIP_BYTES / 1024 / 1024}MB），请确认选择的是 bootstrap 包"
                pendingLocalZip = null
                return
            }

            localZipName = name
            // 先把字节留下：无论走哪条分支，用户确认后都要用它直接安装。
            pendingLocalZip = LocalBootstrap(name, bytes, shaMatches = false)

            when {
                !BootstrapDownloader.isZipMagic(bytes) -> {
                    localZipState = "该文件不是有效的 bootstrap 包（zip 格式校验失败）"
                    pendingLocalZip = null
                }
                BootstrapDownloader.hasExpectedSha256(bytes, BootstrapDownloader.getArchForAbi()) -> {
                    localZipState = "已通过官方校验，可以开始安装"
                    pendingLocalZip = LocalBootstrap(name, bytes, shaMatches = true)
                }
                else -> {
                    // 非官方来源包：本地状态先提示待确认，同时弹窗要求确认来源可信。
                    localZipState = "签名与官方不一致，待确认来源"
                    untrustedSourcePrompt = UntrustedSourcePrompt(name, bytes)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG_PERM, "Failed to read local bootstrap", t)
            localZipName = name
            localZipState = "读取失败：${t.message ?: "未知错误"}"
            pendingLocalZip = null
        }
    }

    /** 用户在二次确认弹窗中选择继续：来源被认可，直接开始本地安装。 */
    private fun confirmUntrustedSource() {
        val prompt = untrustedSourcePrompt ?: return
        untrustedSourcePrompt = null
        pendingLocalZip = LocalBootstrap(prompt.name, prompt.bytes, shaMatches = true)
        localZipState = "已确认来源可信，可以开始安装"
    }

    /** 打开 bootstrap 下载页，引导在线安装超时的用户手动取包后走「从本地安装」。 */
    private fun openBootstrapDownloadPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BootstrapDownloader.DOWNLOAD_PAGE_URL)))
        } catch (t: Throwable) {
            Log.w(TAG_PERM, "No activity to open bootstrap download page", t)
        }
    }

    /** 从 SAF Uri 取展示用文件名，取不到则返回 null（调用方给默认名）。 */
    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
        } catch (t: Throwable) {
            Log.w(TAG_PERM, "Failed to resolve display name for $uri", t)
            null
        }
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
