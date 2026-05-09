package com.pickcode.app.util

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibrationEffect
import android.preference.PreferenceManager

/**
 * 读取「识别成功震动」偏好，若开启则触发短震动
 *
 * 应在识别到取件码、通知已展示后调用。
 * 震动时长：100ms，幅度：DEFAULT_AMPLITUDE
 */
fun vibrateIfEnabled(context: Context) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    if (!prefs.getBoolean("vibrate_on_success", true)) return

    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(100)
    }
}
