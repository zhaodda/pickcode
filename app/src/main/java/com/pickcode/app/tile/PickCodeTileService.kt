package com.pickcode.app.tile

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 快速设置磁贴（Quick Settings Tile）
 *
 * v1.1.0: 点击后通过 PickCodeService 中转触发截屏识别
 * 不再直接调用 AccessibilityService，因为 A11y 服务不保证常驻运行。
 *
 * ══ 流程（v1.1.0）══
 * 用户点击 Tile -> 折叠面板(400ms) -> PickCodeService(ACTION_TRIGGER)
 * -> PickCodeService 尝试 AccessibilityService -> 降级/提示
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

        // 2. 折叠通知面板 + 延迟后启动服务触发截图
        collapseAndTrigger()

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
     * 折叠通知面板后通过 PickCodeService 触发截图
     */
    private fun collapseAndTrigger() {
        // 折叠通知面板（让屏幕内容可见后再截图）
        tryCollapsePanel()

        // 延迟后启动 PickCodeService 触发截图
        handler.postDelayed({
            val intent = Intent(this, com.pickcode.app.service.PickCodeService::class.java).apply {
                action = "com.pickcode.TRIGGER"
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Exception) {
                // 启动失败
            }
        }, 400)
    }

    /** 尝试折叠通知栏/快速设置面板 */
    private fun tryCollapsePanel() {
        try {
            val sbm = getSystemService("statusbar")
            sbm?.javaClass?.getMethod("collapsePanels")?.invoke(sbm)
        } catch (_: Exception) {}
    }
}
