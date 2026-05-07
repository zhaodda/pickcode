package com.pickcode.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pickcode.app.R
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.ui.activity.MainActivity

/**
 * 兜底通知管理器（标准 Android 高优先级横幅通知）
 *
 * 适用于：
 * - 非小米/OPPO/vivo 设备（三星、华为、荣耀、魅族等）
 * - 不满足厂商灵动岛条件的设备（系统版本低/未获白名单等）
 *
 * 展示形式：系统高优先级通知横幅（Head-Up Notification）
 */
class FallbackIslandManager(context: Context) : IslandManagerBase(context) {

    override val managerName = "标准通知横幅"

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    init { createNotificationChannel() }

    override fun showCode(record: CodeRecord) {
        val typeLabel  = "${record.codeType.emoji} ${record.codeType.label}"
        val color      = getHighlightColor(record.codeType)
        val mainIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val copyIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_COPY_CODE).apply {
                putExtra(EXTRA_CODE, record.code)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ISLAND)
            .setContentTitle(typeLabel)
            .setContentText("取件码：${record.code}")
            .setSubText(record.code)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(parseColor(color))
            .setColorized(true)
            .setContentIntent(mainIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "📋 复制验证码", copyIntent)
            .build()

        notificationManager.notify(ISLAND_NOTIFICATION_ID, notification)
    }

    override fun showNoResult() {
        val n = NotificationCompat.Builder(context, CHANNEL_ISLAND)
            .setContentTitle("未找到取件码")
            .setContentText("截图中未识别到有效验证码，请重试")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(buildMainPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(ISLAND_NOTIFICATION_ID, n)
    }

    override fun showError() {
        val n = NotificationCompat.Builder(context, CHANNEL_ISLAND)
            .setContentTitle("识别失败")
            .setContentText("OCR 识别出现错误，请重新截图")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(buildMainPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(ISLAND_NOTIFICATION_ID, n)
    }

    override fun dismiss() {
        notificationManager.cancel(ISLAND_NOTIFICATION_ID)
    }

    private fun buildMainPendingIntent() = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun parseColor(hexColor: String): Int {
        return try { android.graphics.Color.parseColor(hexColor) }
        catch (e: Exception) { android.graphics.Color.parseColor("#4CAF50") }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ISLAND, CHANNEL_ISLAND_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "显示识别到的取件码、取餐码等验证码"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
