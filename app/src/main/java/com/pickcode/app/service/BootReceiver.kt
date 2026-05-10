package com.pickcode.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager

/**
 * 开机自启接收器
 * 若用户开启"开机自启"选项，则在设备启动后自动开启 PickCodeService
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("auto_start_on_boot", false)) return

        val serviceIntent = Intent(context, PickCodeService::class.java)
        context.startForegroundService(serviceIntent)
    }
}
