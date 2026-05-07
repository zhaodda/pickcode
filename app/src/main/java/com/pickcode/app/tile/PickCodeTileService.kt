package com.pickcode.app.tile

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pickcode.app.service.PickCodeService

/**
 * 快速设置磁贴（Quick Settings Tile）
 * 用户可在下拉通知面板添加并点击，一键触发截屏识别
 */
class PickCodeTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "码速达"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = "点击识别验证码"
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // 将 Tile 切换到激活状态（短暂闪烁）
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "识别中..."
            updateTile()
        }

        // 折叠面板并触发识别
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                Intent(this, com.pickcode.app.ui.activity.PermissionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("from_tile", true)
                }
            )
        } else {
            // API < 34: 折叠状态栏（通过 StatusBarManager 反射）
            try {
                val statusBarManager = getSystemService("statusbar")
                val collapseMethod = statusBarManager?.javaClass?.getMethod("collapsePanels")
                collapseMethod?.invoke(statusBarManager)
            } catch (_: Exception) {
                // 忽略，尽力而为
            }
            PickCodeService.triggerCapture(this)
        }

        // 1.5 秒后恢复图标状态
        handler.postDelayed({
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "点击识别验证码"
                updateTile()
            }
        }, 1500)
    }
}
