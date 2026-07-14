package com.termux.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.termux.R
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.OobeScreen
import com.termux.app.TermuxInstaller

class OobeActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.attachBaseContext(newBase))
    }

    private var permissionStatus by mutableStateOf("")
    private var isNextEnabled by mutableStateOf(false)
    private var isBootstrapping by mutableStateOf(false)

    private val normalPermissions = arrayOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.VIBRATE
    )

    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var manageStorageLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            Log.d("OobeActivity", "Normal permissions result received")
            val allNormalGranted = normalPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            
            if (allNormalGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                Log.d("OobeActivity", "Normal permissions granted, requesting manage storage")
                requestManageStoragePermission()
            } else {
                updatePermissionStatus()
            }
        }

        manageStorageLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            Log.d("OobeActivity", "Manage storage result received")
            updatePermissionStatus()
        }

        updatePermissionStatus()

        setContent {
            KiTerminalTheme {
                OobeScreen(
                    permissionStatus = permissionStatus,
                    isNextEnabled = isNextEnabled,
                    isBootstrapping = isBootstrapping,
                    onGrantAllPermissions = { grantAllPermissions() },
                    onComplete = {
                        if (isNextEnabled) {
                            performBootstrap()
                        } else {
                            Toast.makeText(this, R.string.oobe_permission_required, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("OobeActivity", "onResume - refreshing permission status")
        updatePermissionStatus()
    }

    private fun performBootstrap() {
        isBootstrapping = true
        
        TermuxInstaller.setupBootstrapIfNeeded(this) {
            runOnUiThread {
                isBootstrapping = false
                SplashActivity.setProvisioned(this@OobeActivity, true)
                startActivity(Intent(this@OobeActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun grantAllPermissions() {
        val deniedPermissions = mutableListOf<String>()
        
        for (permission in normalPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                deniedPermissions.add(permission)
            }
        }

        if (deniedPermissions.isNotEmpty()) {
            Log.d("OobeActivity", "Requesting permissions: ${deniedPermissions.joinToString()}")
            requestPermissionsLauncher.launch(deniedPermissions.toTypedArray())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Log.d("OobeActivity", "All normal permissions granted, requesting manage storage")
            requestManageStoragePermission()
        } else {
            Log.d("OobeActivity", "All permissions already granted")
            updatePermissionStatus()
        }
    }

    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                manageStorageLauncher.launch(intent)
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in normalPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                return false
            }
        }

        return true
    }

    private fun updatePermissionStatus() {
        Log.d("OobeActivity", "=== Permission Check ===")
        
        for (permission in normalPermissions) {
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            Log.d("OobeActivity", "$permission: $granted")
        }
        
        var grantedCount = normalPermissions.count {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        
        var totalPermissions = normalPermissions.size
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            totalPermissions += 1
            if (Environment.isExternalStorageManager()) {
                grantedCount += 1
            }
        }
        
        Log.d("OobeActivity", "SDK_INT: ${Build.VERSION.SDK_INT}, isExternalStorageManager: ${Environment.isExternalStorageManager()}")
        Log.d("OobeActivity", "grantedCount: $grantedCount, total: $totalPermissions")

        permissionStatus = String.format("%s %d/%d",
            getString(R.string.oobe_permission_progress),
            grantedCount,
            totalPermissions)

        if (allPermissionsGranted()) {
            permissionStatus = getString(R.string.oobe_permission_all_granted)
            isNextEnabled = true
            Log.d("OobeActivity", "All permissions granted! isNextEnabled: true")
        } else {
            isNextEnabled = false
            Log.d("OobeActivity", "Not all permissions granted. isNextEnabled: false")
        }
    }
}