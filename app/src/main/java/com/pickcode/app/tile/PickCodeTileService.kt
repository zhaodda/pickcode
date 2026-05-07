package com.pickcode.app.tile

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pickcode.app.ui.activity.CaptureActivity

/**
 * 快速设置磁贴（Quick Settings Tile）
 *
 * v1.1.0: 点击后启动 CaptureActivity 进行截屏识别
 * CaptureActivity 会处理 MediaProjection 授权、截图、OCR 全流程
 *
 * ══ 流程 ═══
 * 用户点击 Tile -> 折叠面板(400ms) -> CaptureActivity.startCapture()
 * -> 首次: 弹录屏选择框 -> 截图 -> OCR -> 展示结果 -> 自动关闭
 * -> 后续: 直接截图（token 已缓存）-> OCR -> 展示结果 -> 自动关闭
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

        // 1. 视觉反馈
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "识别中..."
            updateTile()
        }

        // 2. 折叠面板 + 延迟启动截屏
        tryCollapsePanel()
        handler.postDelayed({ CaptureActivity.startCapture(this@PickCodeTileService) }, 400)

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
