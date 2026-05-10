package com.pickcode.app.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color.parseColor
import androidx.core.app.NotificationCompat
import com.pickcode.app.R
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.data.model.CodeType
import com.pickcode.app.service.CopyCodeReceiver
import com.pickcode.app.ui.activity.MainActivity
import com.pickcode.app.util.AppLog

/**
 * 取件码通知管理器（标准 Android 通知）
 *
 * 每次识别到取件码发送一条独立的高优先级横幅通知，
 * 支持多条通知同时存在，点击"复制"后关闭单条。
 */
class IslandNotificationManager(private val context: Context) {

    companion object {
        /** 取件码通知 Channel */
        const val CHANNEL_CODE = "pickcode_code"

        /** 复制验证码广播 Action */
        const val ACTION_COPY_CODE = "com.pickcode.ACTION_COPY_CODE"

        /** 广播 Extra：验证码内容 */
        const val EXTRA_CODE = "extra_code"

        /** 广播 Extra：通知 ID（用于复制后精确取消） */
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        /** 通知 ID 起始值（与常驻通知 1001 错开） */
        private const val BASE_NOTIFICATION_ID = 3000
    }

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    private var nextId = BASE_NOTIFICATION_ID

    init { createNotificationChannel() }

    val managerName: String = "标准通知栏"

    /** 按 CodeType 返回高亮颜色 */
    private fun highlightColor(codeType: CodeType): Int = when (codeType) {
        CodeType.EXPRESS -> parseColor("#534AB7")  // 快递：紫
        CodeType.FOOD     -> parseColor("#FF6B35")  // 餐饮：橙
        CodeType.PARKING  -> parseColor("#00BCD4")  // 停车：青
        CodeType.OTHER    -> parseColor("#4CAF50")  // 其他：绿
    }

    /**
     * 展示识别到的取件码（发一条新通知，ID 自动递增）
     */
    fun showCode(record: CodeRecord) {
        val notificationId = nextId++
        val typeLabel = "${record.codeType.emoji} ${record.codeType.label}"

        val mainIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 携带 notificationId，复制后可精确关闭本条通知
        val copyIntent = PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, CopyCodeReceiver::class.java).apply {
                action = ACTION_COPY_CODE
                putExtra(EXTRA_CODE, record.code)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 构建通知内容：有地址时显示地址 + 取件码
        val contentText = if (record.address.isNotEmpty()) {
            context.getString(R.string.notification_content_code_address, record.code, record.address)
        } else {
            context.getString(R.string.notification_content_code, record.code)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_CODE)
            .setContentTitle(typeLabel)
            .setContentText(contentText)
            .setSubText(record.code)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(highlightColor(record.codeType))
            .setColorized(true)
            .setContentIntent(mainIntent)
            .setAutoCancel(false)          // 不自动消失，等用户复制或手动关闭
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(0, context.getString(R.string.notification_action_copy), copyIntent)
            .build()

        notificationManager.notify(notificationId, notification)
        AppLog.i(
            "NotifMgr",
            "发送取件码通知 [id=$notificationId] $typeLabel code=${AppLog.maskCode(record.code)}" +
                if (record.address.isNotEmpty()) " address_present=true" else ""
        )
    }

    /** 展示"未找到验证码"提示 */
    fun showNoResult() {
        val n = NotificationCompat.Builder(context, CHANNEL_CODE)
            .setContentTitle(context.getString(R.string.notification_no_result_title))
            .setContentText(context.getString(R.string.notification_no_result_text))
            .setSmallIcon(R.drawable.ic_notify)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(nextId++, n)
    }

    /** 展示识别错误提示 */
    fun showError() {
        val n = NotificationCompat.Builder(context, CHANNEL_CODE)
            .setContentTitle(context.getString(R.string.notification_error_title))
            .setContentText(context.getString(R.string.notification_error_text))
            .setSmallIcon(R.drawable.ic_notify)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(nextId++, n)
    }

    /** 关闭指定 ID 的通知 */
    fun dismiss(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    /** 关闭所有取件码通知（保留常驻前台通知） */
    fun dismissAll() {
        for (id in BASE_NOTIFICATION_ID until nextId) {
            notificationManager.cancel(id)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_CODE,
            context.getString(R.string.notification_channel_code),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_code_desc)
            setShowBadge(true)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
