package com.pickcode.app.tile

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pickcode.app.service.PickCodeAccessibilityService

/**
 * 快速设置磁贴（Quick Settings Tile）
 *
 * v1.0.5: 点击后通过 PickCodeAccessibilityService 截屏识别
 * 不再启动 PermissionActivity，不再需要 MediaProjection 录屏弹窗
 *
 * ══ 流程（v1.0.5）══
 * 用户点击 Tile -> 折叠面板 -> 触发 AccessibilityService.takeScreenshot()
 * -> 延迟 500ms（等面板收起） -> 截图 -> OCR -> 展示灵动岛
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

        // 1. 切换到激活状态（视觉反馈）
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "识别中..."
            updateTile()
        }

        // 2. 折叠通知面板 + 触发无障碍截屏
        collapseAndCapture()

        // 3. 1.5 秒后恢复图标状态
        handler.postDelayed({
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "点击识别验证码"
                updateTile()
            }
        }, 1500)
    }

    /**
     * 折叠通知面板后触发截屏
     */
    private fun collapseAndCapture() {
        // 折叠面板（API 34+ 使用 StatusBarManager，旧版用反射）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                // API 34: StatusBarManager.collapsePanels()
                val sbm = getSystemService("statusbar")
                sbm?.javaClass?.getMethod("collapsePanels")?.invoke(sbm)
            } catch (_: Exception) {}
        } else {
            // 反射折叠
            try {
                val sb = getSystemService("statusbar")
                sb?.javaClass?.getMethod("collapsePanels")?.invoke(sb)
            } catch (_: Exception) {}
        }

        // 延迟后触发截图（确保面板已收起）
        handler.postDelayed({
            triggerScreenshot()
        }, 400)
    }

    /**
     * 触发 AccessibilityService 截屏
     * 如果无障碍服务不可用，尝试降级到 MediaProjection 路径
     */
    private fun triggerScreenshot() {
        // 方案1：优先走 AccessibilityService（主方案）
        if (PickCodeAccessibilityService.isAvailable) {
            if (PickCodeAccessibilityService.triggerScreenshot()) return
        }

        // 方案2：降级走 Service 的 MediaProjection 路径
        try {
            val serviceIntent = Intent(this, com.pickcode.app.service.PickCodeService::class.java).apply {
                action = "com.pickcode.TRIGGER"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {
            // 都失败了... 无力回天
        }
    }
}
