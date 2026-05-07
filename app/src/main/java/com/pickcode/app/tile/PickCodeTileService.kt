package com.pickcode.app.tile

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.pickcode.app.service.PickCodeAccessibilityService
import com.pickcode.app.util.AppLog

/**
 * 快速设置磁贴（Quick Settings Tile）
 *
 * 点击后使用无障碍节点树文字提取屏幕内容，识别取件码
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

        // 3. 无障碍节点树文字提取（唯一方案）
        val result = PickCodeAccessibilityService.extractFromScreenText()
        if (result != null) {
            Log.i(TAG, "Text extraction succeeded: ${result.code}")
            AppLog.i("PickCodeTileService", "✅ 识别成功: ${result.code}", "tile")
        } else {
            if (!PickCodeAccessibilityService.isAvailable) {
                Log.w(TAG, "Accessibility service not available")
                AppLog.w("PickCodeTileService", "⚠️ 无障碍服务未连接", "tile")
            } else {
                Log.d(TAG, "Text extraction returned no code")
                AppLog.w("PickCodeTileService", "❌ 当前屏幕未找到取件码", "tile")
            }
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
