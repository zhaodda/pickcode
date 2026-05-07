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

        // 2. 收起 QS 面板 — TileService 官方 API
        //    startActivityAndCollapse 专门用于关闭 QS 面板（右边下拉的快速设置）
        try {
            startActivityAndCollapse(android.content.Intent())
            Log.d(TAG, "QS panel collapsed via startActivityAndCollapse")
        } catch (e: Exception) {
            Log.w(TAG, "startActivityAndCollapse failed", e)
        }

        // 3. 延迟执行文字提取
        //    澎湃OS 特殊性：状态栏左边=通知栏，右边=QS 面板，两者是独立的！
        //    startActivityAndCollapse 只关 QS（右边），通知栏（左边）可能还开着。
        //    所以在延迟回调中还需要再确认所有面板都已关闭。
        //
        //    时间线：
        //    T=0ms   onClick() 执行，startActivityAndCollapse 开始关闭 QS
        //    T~300ms QS 面板动画收起完成
        //    T=500ms  第一次 GLOBAL_ACTION_BACK（兜底关闭可能残留的通知栏）
        //    T=1000ms 第二次检查 + 提取屏幕文字
        //
        //    分两步延迟是为了：
        //    a) 给 QS 面板动画足够时间完成
        //    b) 再用一次 BACK 确保通知栏也被关闭
        //    c) 最后等节点树刷新后再提取
        handler.postDelayed({
            // 兜底：再按一次返回键，确保通知栏也关掉了
            AppLog.i("PickCodeTileService", "延迟500ms后执行兜底返回键(确保通知栏也关闭)", "tile")
            PickCodeAccessibilityService.collapsePanelIfNeeded()
        }, 500)

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
