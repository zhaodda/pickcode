package com.pickcode.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class PickCodeApp : Application() {

    companion object {
        const val CHANNEL_PERSISTENT = "pickcode_persistent"
        const val ACTION_CODE_UPDATED = "com.pickcode.CODE_UPDATED"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // 常驻通知频道（低重要性，不弹出，前台服务保活用）
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PERSISTENT,
                getString(R.string.notification_channel_persistent),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_persistent_desc)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
        )

        // 取件码通知频道由 IslandNotificationManager 在初始化时自动创建（IMPORTANCE_HIGH）
    }
}
