package com.pickcode.app.ui.activity

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import com.pickcode.app.service.PickCodeService

/**
 * 透明 Activity，专门用于请求 MediaProjection（截屏）权限
 * 获取权限后立即将结果传给 PickCodeService 并关闭自身
 */
class PermissionActivity : Activity() {

    private val REQ_MEDIA_PROJECTION = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, PickCodeService::class.java).apply {
                action = PickCodeService.ACTION_MEDIA_RESULT
                putExtra(PickCodeService.EXTRA_RESULT_CODE, resultCode)
                putExtra(PickCodeService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
        finish()
    }
}
