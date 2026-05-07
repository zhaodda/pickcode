package com.pickcode.app.tile

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pickcode.app.ui.activity.CaptureActivity
import com.pickcode.app.util.AppLog

/**
 * 快速设置磁贴（Quick Settings Tile）
 *
 * 点击后启动 CaptureActivity 进行截屏识别
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

        AppLog.i("PickCodeTileService", "磁贴被点击", "tile")

        // 1. 视觉反馈
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "识别中..."
            updateTile()
        }

        // 2. 折叠面板 + 延迟启动截屏
        tryCollapsePanel()
        handler.postDelayed({ CaptureActivity.startCapture(this@PickCodeTileService, "tile") }, 400)
        AppLog.i("PickCodeTileService", "已调度 CaptureActivity.startCapture (延迟400ms)", "tile")

        // 3. 恢复图标
        handler.postDelayed({
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "点击识别验证码"
                updateTile()
            }
        }, 1500)
    }

    private fun tryCollapsePanel() {
        try {
            val sbm = getSystemService("statusbar")
            sbm?.javaClass?.getMethod("collapsePanels")?.invoke(sbm)
        } catch (_: Exception) {}
    }
}
