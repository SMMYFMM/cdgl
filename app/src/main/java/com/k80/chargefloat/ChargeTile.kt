package com.k80.chargefloat

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * 下拉状态栏里的快捷开关：一键开 / 关悬浮窗。
 * 用户在控制中心编辑页里可以把它拖到常用位置。
 */
class ChargeTile : TileService() {

    private val ui = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (FloatService.running) {
            stopService(Intent(this, FloatService::class.java))
        } else if (!Settings.canDrawOverlays(this)) {
            openApp()
            return
        } else {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, FloatService::class.java))
            }.onFailure { openApp() }
        }
        updateTile()
        // 服务启停是异步的，稍后再同步一次状态
        ui.postDelayed({ updateTile() }, 600)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val on = FloatService.running
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) "已开启" else "已关闭"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_bolt)
        }
        tile.updateTile()
    }

    /** 没有悬浮窗权限 / 启动被系统拦下时，把用户带回主界面处理 */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
