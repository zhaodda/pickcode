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

        // 3. 无障碍节点树文字提取（tile/notification 来源会自动折叠面板后延迟提取）
        //    注意：tile 触发时此方法会异步执行（先折叠通知栏→延迟500ms→提取），
        //    所以这里返回 null 是正常的，结果由 AccessibilityService 内部回调处理
        PickCodeAccessibilityService.extractFromScreenText()
        Log.i(TAG, "Tile: extraction dispatched (async for tile source)")

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
