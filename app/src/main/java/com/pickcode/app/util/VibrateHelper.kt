package com.pickcode.app.util

import android.content.Context
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.preference.PreferenceManager

/**
 * 读取「识别成功震动」偏好，若开启则触发短震动
 *
 * 应在识别到取件码、通知已展示后调用。
 * 震动时长：100ms，幅度：DEFAULT_AMPLITUDE
 */
fun vibrateIfEnabled(context: Context) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    if (!prefs.getBoolean("vibrate_on_success", true)) return

    val vibrator = context.getSystemService(Vibrator::class.java)
    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
}
