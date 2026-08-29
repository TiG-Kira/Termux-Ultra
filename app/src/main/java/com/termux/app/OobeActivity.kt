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
import com.termux.app.compose.OobeScreen

class OobeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IS_UPGRADE = "extra_is_upgrade"
        
        // 许可条款最终修改日期 (YYYYMMDD)
        const val EULA_LAST_MODIFIED = "20260829"
    }

    private var isUpgrade by mutableStateOf(false)
    private var currentPage by mutableStateOf(0)
    private var eulaAgreed by mutableStateOf(false)
    
    private var permissionStatus by mutableStateOf("")
    private var isPermissionGranted by mutableStateOf(false)
    private var isBootstrapping by mutableStateOf(false)
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

        isUpgrade = intent.getBooleanExtra(EXTRA_IS_UPGRADE, false)
        Log.d("OobeActivity", "isUpgrade=$isUpgrade")

        try {
            requestPermissionsLauncher = registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { _ ->
                updatePermissionStatus()
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
                            isBootstrapping = isBootstrapping,
                            bootstrapComplete = bootstrapComplete,
                            bootstrapError = bootstrapError,
                            releaseNotes = releaseNotes,
                            currentVersionName = com.termux.BuildConfig.VERSION_NAME,
                            onGrantAllPermissions = { grantAllPermissions() },
                            onStartBootstrap = { performBootstrap() },
                            onRetryBootstrap = { retryBootstrap() },
                            onExitApp = { exitApp() },
                            onComplete = { completeOobe() }
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
        bootstrapError = null
        
        TermuxInstaller.setupBootstrapIfNeeded(this) {
            // Bootstrap zip 解压成功后，立即建立 storage symlinks
            try {
                TermuxInstaller.setupStorageSymlinks(this)
            } catch (_: Throwable) {}
            runOnUiThread {
                isBootstrapping = false
                bootstrapComplete = true
            }
        }
    }

    private fun retryBootstrap() {
        bootstrapError = null
        performBootstrap()
    }

    private fun grantAllPermissions() {
        val deniedPermissions = mutableListOf<String>()
        
        for (permission in normalPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                deniedPermissions.add(permission)
            }
        }

        if (deniedPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(deniedPermissions.toTypedArray())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            manageStorageLauncher.launch(intent)
        } else {
            updatePermissionStatus()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in normalPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) return false
        }
        return true
    }

    private fun updatePermissionStatus() {
        var grantedCount = normalPermissions.count {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        var totalPermissions = normalPermissions.size
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            totalPermissions += 1
            if (Environment.isExternalStorageManager()) grantedCount += 1
        }

        permissionStatus = String.format("%s %d/%d",
            getString(R.string.oobe_permission_progress),
            grantedCount,
            totalPermissions)

        isPermissionGranted = allPermissionsGranted()
        if (isPermissionGranted) {
            permissionStatus = getString(R.string.oobe_permission_all_granted)
        }
    }

    private fun exitApp() {
        SplashActivity.resetOobe(this)
        finish()
    }

    private fun completeOobe() {
        SplashActivity.setEulaDate(this, EULA_LAST_MODIFIED)
        SplashActivity.setProvisioned(this, true)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
