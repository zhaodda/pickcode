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
    private const val PANEL_COLLAPSE_DELAY_MS = 200L

    /**
     * Tile 磁贴点击后的时间线设计：
     *
     * ══ 为什么不用 GLOBAL_ACTION_BACK 兜底？══
     * v1.3.x 曾在 T=500ms 执行 collapsePanelIfNeeded() (GLOBAL_ACTION_BACK) 作为"兜底"，
     * 但这会导致严重副作用：
     * - 如果 startActivityAndCollapse() 已成功关闭面板，多余的 BACK 会作用到用户正在看的 App
     * - 表现为：识别完成后当前 App 自动返回上一页（用户反馈的问题）
     * - 澎湃OS中通知栏和QS是独立窗口，BACK的行为不可预测
     *
     * ══ 现行方案（v2.0.0）：短延迟(350ms) + 残留重试 ══
     * 1. T=0:   startActivityAndCollapse() — 官方API关闭QS面板（~200-300ms动画）
     * 2. T=350: extractFromScreenText("tile") — 提取屏幕文字
     *    如果 performExtract() 检测到面板残留（looksLikePanelText），
     *    才会执行 BACK 并自动重试（这是有条件的，不会误触）
     */
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "码住"
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
        //    短延迟策略（350ms）：只依赖 startActivityAndCollapse() 关闭 QS 面板，
        //    等待面板动画完成（~200-300ms）后立即提取。
        //    不再执行无条件的 GLOBAL_ACTION_BACK 兜底（会导致当前App被误返回）。
        //    如果面板确实没关好，performExtract() 的 looksLikePanelText() 残留检测会自动处理重试。
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
