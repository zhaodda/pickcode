package com.pickcode.app.tile

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.pickcode.app.service.PickCodeAccessibilityService
import com.pickcode.app.ui.activity.CaptureActivity
import com.pickcode.app.util.AppLog

/**
 * 快速设置磁贴（Quick Settings Tile）
 *
 * 点击后优先使用无障碍服务截屏，失败后降级到 CaptureActivity
 */
class PickCodeTileService : TileService() {

    companion object {
        private const val TAG = "PickCodeTileService"
    }

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
        Log.i(TAG, "Tile clicked")

        // 1. 视觉反馈
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "识别中..."
            updateTile()
        }

        // 2. 折叠面板
        tryCollapsePanel()

        // 3. 优先尝试无障碍节点树文字提取（效仿Tally，无需截图）
        val result = PickCodeAccessibilityService.extractFromScreenText()
        if (result != null) {
            Log.i(TAG, "Accessibility text extraction succeeded: ${result.code}")
            AppLog.i("PickCodeTileService", "✅ 无障碍文字提取成功: ${result.code}", "tile")
        } else if (PickCodeAccessibilityService.isAvailable) {
            // 无障碍可用但文字未提取到 → 尝试异步截屏
            Log.i(TAG, "Text extraction empty, trying screenshot fallback")
            AppLog.i("PickCodeTileService", "文字提取无结果，尝试截屏降级", "tile")
            PickCodeAccessibilityService.triggerScreenshot()
        } else {
            // 完全降级到 CaptureActivity（MediaProjection 录屏OCR）
            Log.w(TAG, "Accessibility service not available, falling back to CaptureActivity")
            AppLog.w("PickCodeTileService", "无障碍服务未连接，降级到 CaptureActivity", "tile")
            handler.postDelayed({ CaptureActivity.startCapture(this@PickCodeTileService, "tile") }, 400)
        }

        // 4. 恢复图标
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
