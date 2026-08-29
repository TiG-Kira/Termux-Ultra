package com.termux.app

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings Tile：一键新建终端并自动进入
 * - 非 toggle 模式（STATE_INACTIVE），不显示"开启"
 * - 点击时先确保 TermuxService 核心服务已启动，再创建终端
 */
class NewTerminalTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE  // 非 toggle 模式，不显示"开启/关闭"
        tile.label = "新建终端"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.subtitle = "一键启动终端"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        // ===== 先确保 TermuxService 核心服务已启动 =====
        try {
            val serviceIntent = Intent(this, TermuxService::class.java)
            // 直接 startService：TermuxService.onStartCommand 自己处理启动逻辑
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.w("NewTerminalTile", "TermuxService 启动失败: ${e.message}")
            // 即使服务启动失败也继续尝试启动 Activity，Activity 里会再尝试绑定
        }

        // ===== 启动 TermuxActivity 并传入新建终端的 extra =====
        val intent = Intent(this, TermuxActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NEW_TERMINAL, true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(intent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            startActivity(intent)
        } else {
            startActivity(intent)
        }
    }

    companion object {
        const val EXTRA_NEW_TERMINAL = "kiterminal.extra.NEW_TERMINAL"
    }
}
