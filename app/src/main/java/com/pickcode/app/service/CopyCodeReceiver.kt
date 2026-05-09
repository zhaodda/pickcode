package com.pickcode.app.service

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.pickcode.app.overlay.IslandNotificationManager

/**
 * 复制验证码广播接收器
 *
 * 触发路径：
 * 1. 取件码通知包含一个"复制"操作按钮
 * 2. 用户点击通知上的"复制"按钮
 * 3. 应用内显式 PendingIntent 发送 ACTION_COPY_CODE 广播（含验证码 + notificationId）
 * 4. 本 Receiver 接收广播，执行复制并取消对应的通知
 */
class CopyCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IslandNotificationManager.ACTION_COPY_CODE) return

        val code = intent.getStringExtra(IslandNotificationManager.EXTRA_CODE) ?: return
        val notificationId = intent.getIntExtra(IslandNotificationManager.EXTRA_NOTIFICATION_ID, -1)

        // 复制到剪贴板
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("取件码", code))

        // 提示用户
        Toast.makeText(context, "已复制：$code", Toast.LENGTH_SHORT).show()

        // 取消对应的通知（精确到单条）
        if (notificationId >= 0) {
            context.getSystemService(android.app.NotificationManager::class.java)
                .cancel(notificationId)
        }
    }
}
