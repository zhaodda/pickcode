package com.pickcode.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.pickcode.app.overlay.IslandNotificationManager

class PickCodeApp : Application() {

    companion object {
        const val CHANNEL_PERSISTENT = "pickcode_persistent"
        // CHANNEL_ISLAND 由 IslandNotificationManager 自行创建（IMPORTANCE_HIGH，保障超级岛弹出）
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // 常驻通知频道（低重要性，不弹出，前台服务保活用）
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PERSISTENT,
                    "码速达运行中",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持服务运行的常驻通知，不会弹出或发声"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }
            )

            // 超级岛通知频道由 IslandNotificationManager 在初始化时自动创建
            // Channel ID: IslandNotificationManager.CHANNEL_ISLAND = "pickcode_island"
            // Importance: IMPORTANCE_HIGH（确保在非小米设备上也能弹出横幅）
        }
    }
}
