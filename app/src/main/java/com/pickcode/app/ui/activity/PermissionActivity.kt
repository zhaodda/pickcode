package com.pickcode.app.ui.activity

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * 保留兼容：旧版权限请求 Activity
 * v1.1.0 后主要截屏入口改为 CaptureActivity，此类作为备用
 */
class PermissionActivity : Activity() {

    private val REQ_MEDIA_PROJECTION = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
        // 如果用户授权了结果也没地方用了，直接 finish
        // 这个类现在仅作兼容保留，实际功能已迁移到 CaptureActivity
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 授权后启动 CaptureActivity 来完成实际截图
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK) {
            CaptureActivity.startCapture(this)
        }
        finish()
    }
}
