package com.pickcode.app.tile

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pickcode.app.service.PickCodeService

/**
 * 快速设置磁贴（Quick Settings Tile）
 *
 * 用户可在下拉通知栏添加并点击，一键触发截屏识别。
 *
 * ══ 点击后的完整流程（v1.0.3 修复）══
 *
 * ┌──────────────┐
 * │  用户点击 Tile  │
 * └──────┬───────┘
 *        ▼
 * ┌──────────────┐     ┌─────────────────────┐
 * │ 1. 更新状态    │────▶│ 2. 折叠通知面板       │
 * │ STATE_ACTIVE  │    │ (startActivityAnd-   │
 * │ "识别中..."    │    │  Collapse)           │
 * └──────────────┘     └──────────┬──────────┘
 *                                 ▼
 *                       ┌─────────────────────┐
 *                       │ 3. 启动 Permission-  │
 *                       │    Activity (透明)   │
 *                       │ → 弹出系统录屏选择框   │
 *                       └──────────┬──────────┘
 *                                  ▼
 *                       ┌─────────────────────┐
 *                       │ 4. 用户选择屏幕后     │
 *                       │ PermissionActivity   │
 *                       │ 将结果回传给 Service   │
 *                       └──────────┬──────────┘
 *                                  ▼
 *                       ┌─────────────────────┐
 *                       │ 5. Service 截图+OCR  │
 *                       │ → 展示灵动岛          │
 *                       └─────────────────────┘
 *
 * 关键修复：
 * - 不再同时调用 triggerCapture + startActivityAndCollapse（避免竞争）
 * - 统一由 PermissionActivity 作为唯一授权入口
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

        // 2. 先确保服务在运行（不触发截图，只是让前台通知常驻）
        try {
            PickCodeService.triggerCapture(this)
        } catch (_: Exception) {
            // 服务可能已在运行，忽略
        }

        // 3. 启动 PermissionActivity（系统录屏授权页）并折叠面板
        // 这是唯一的截屏授权入口，用户选择屏幕后由 PermissionActivity 回传结果给 Service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startActivityAndCollapse(
                    Intent(this, com.pickcode.app.ui.activity.PermissionActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra("from_tile", true)
                    }
                )
            } catch (_: Exception) {
                // 某些 ROM 可能限制，尝试用普通 startActivity
                try {
                    startActivity(
                        Intent(this, com.pickcode.app.ui.activity.PermissionActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra("from_tile", true)
                        }
                    )
                } catch (_: Exception) { /* 无力回天 */ }
            }
        } else {
            // API < 34: 先折叠状态栏，再启动授权页
            try {
                val statusBarManager = getSystemService("statusbar")
                val collapseMethod = statusBarManager?.javaClass?.getMethod("collapsePanels")
                collapseMethod?.invoke(statusBarManager)
            } catch (_: Exception) { /* 忽略 */ }

            try {
                startActivity(
                    Intent(this, com.pickcode.app.ui.activity.PermissionActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("from_tile", true)
                    }
                )
            } catch (_: Exception) { /* 忽略 */ }
        }

        // 4. 1.5 秒后恢复图标状态
        handler.postDelayed({
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "点击识别验证码"
                updateTile()
            }
        }, 1500)
    }
}
