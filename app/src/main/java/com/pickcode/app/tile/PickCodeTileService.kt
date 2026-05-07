package com.pickcode.app.tile

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
 *
 * ══ 关键：QS 面板处理 ═══
 * 点击磁贴时 QS 面板处于展开状态，rootInActiveWindow 返回的是 QS 面板的 UI 节点树，
 * 而非屏幕上实际 App 的内容。因此需要：
 * 1. 先收起 QS 面板（startActivityAndCollapse 或 GLOBAL_ACTION_BACK）
 * 2. 等待面板动画结束 + 系统刷新节点树（约 1000ms）
 * 3. 再执行节点树文字提取
 */
class PickCodeTileService : TileService() {

    companion object {
        private const val TAG = "PickCodeTileService"

        /** QS 面板收起后等待多久再提取文字（毫秒） */
        private const val PANEL_COLLAPSE_DELAY_MS = 1000L
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

        // 2. 收起 QS 面板 — 这是 TileService 官方 API
        //    startActivityAndCollapse 会自动关闭 QS 面板并启动指定 Activity
        //    我们传一个空的 Intent（不启动任何 Activity），纯粹利用它的"收起面板"副作用
        //    这比 performGlobalAction(GLOBAL_ACTION_BACK) 更可靠，因为：
        //    a) 它是 TileService 原生能力，不受 ROM 定制干扰
        //    b) 不触发额外的 BACK 事件（不会意外退出当前 App）
        try {
            startActivityAndCollapse(android.content.Intent())
            Log.d(TAG, "QS panel collapsed via startActivityAndCollapse")
        } catch (e: Exception) {
            Log.w(TAG, "startActivityAndCollapse failed, fallback to global action", e)
            // 降级：通过 AccessibilityService 执行返回键
            PickCodeAccessibilityService.collapsePanelIfNeeded()
        }

        // 3. 延迟执行文字提取（等 QS 面板完全收起 + 节点树刷新）
        //    注意：必须在 handler 中延迟执行，不能同步调 extractFromScreenText()
        //    因为 onClick() 必须快速返回（Binder 线程限制），且此时 QS 面板还未收起
        handler.postDelayed({
            AppLog.i("PickCodeTileService", "延迟${PANEL_COLLAPSE_DELAY_MS}ms后开始提取屏幕文字", "tile")
            PickCodeAccessibilityService.extractFromScreenText("tile")
            Log.i(TAG, "Tile: delayed extraction executed")
        }, PANEL_COLLAPSE_DELAY_MS)

        // 4. 恢复图标状态
        handler.postDelayed({
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "点击识别验证码"
                updateTile()
            }
        }, 2000)
    }
}
