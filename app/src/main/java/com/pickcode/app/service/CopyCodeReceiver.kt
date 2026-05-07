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
 * 用途：接收小米超级岛 Action 按钮（"复制验证码"）触发的广播
 *
 * 触发路径：
 * 1. 超级岛展示时包含一个 Notification.Action（KEY_ACTION_COPY）
 * 2. 用户点击超级岛上的"复制"按钮
 * 3. 系统发送 ACTION_COPY_CODE 广播（含验证码 extra）
 * 4. 本 Receiver 接收广播，执行复制并取消通知
 *
 * ⚠️ 在 AndroidManifest.xml 中声明为 exported="true"，因为 PendingIntent
 *    从系统通知栏触发时属于外部调用（即使 Action 定义在 App 内部）
 */
class CopyCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IslandNotificationManager.ACTION_COPY_CODE) return

        val code = intent.getStringExtra(IslandNotificationManager.EXTRA_CODE) ?: return

        // 复制到剪贴板
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("取件码", code))

        // 提示用户
        Toast.makeText(context, "已复制：$code", Toast.LENGTH_SHORT).show()

        // 取消通知
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.cancel(IslandNotificationManager.ISLAND_NOTIFICATION_ID)
    }
}
