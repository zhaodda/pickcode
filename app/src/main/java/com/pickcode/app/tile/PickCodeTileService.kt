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
 * 用户可在下拉通知栏添加并点击，一键触发截屏识别。
 *
 * ══ 点击后的完整流程（v1.0.4 修复）══
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
 * v1.0.4 修复要点：
 * - 不再调用 PickCodeService.triggerCapture()（避免 Service 无权限时发无效广播）
 * - 直接启动 PermissionActivity 作为唯一入口，简单可靠
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

        // 1. 切换到激活状态（视觉反馈：让用户看到点击生效了）
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "识别中..."
            updateTile()
        }

        // 2. 直接启动 PermissionActivity（录屏授权页）— 唯一入口
        // 用户选择屏幕后，PermissionActivity 将 MediaProjection 结果回传给 Service
        val permissionIntent = Intent(
            this,
            com.pickcode.app.ui.activity.PermissionActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("from_tile", true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: startActivityAndCollapse 同时折叠通知面板 + 启动 Activity
            try {
                startActivityAndCollapse(permissionIntent)
            } catch (_: Exception) {
                // 某些 ROM 可能限制，降级为普通 startActivity
                try {
                    startActivity(permissionIntent)
                } catch (_: Exception) { /* 无力回天 */ }
            }
        } else {
            // API < 34: 先反射折叠状态栏，再启动授权页
            try {
                val statusBarManager = getSystemService("statusbar")
                val collapseMethod = statusBarManager?.javaClass?.getMethod("collapsePanels")
                collapseMethod?.invoke(statusBarManager)
            } catch (_: Exception) { /* 忽略 */ }

            try {
                startActivity(permissionIntent)
            } catch (_: Exception) { /* 忽略 */ }
        }

        // 3. 1.5 秒后恢复图标状态
        handler.postDelayed({
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "点击识别验证码"
                updateTile()
            }
        }, 1500)
    }
}
