package com.pickcode.app.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.pickcode.app.PickCodeApp
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.data.repository.CodeRepository
import com.pickcode.app.ocr.CodeExtractor
import com.pickcode.app.overlay.IslandNotificationManager
import com.pickcode.app.util.AppLog
import kotlinx.coroutines.*
import java.util.concurrent.Executors

/**
 * 截屏识别 Activity（透明全屏，用完即关）
 *
 * 使用方式：CaptureActivity.startCapture(context, triggerFrom)
 * triggerFrom: "notification" / "tile" / "fab" / "manual" / "unknown"
 */
class CaptureActivity : Activity() {

    companion object {
        private const val TAG = "CaptureActivity"
        private const val REQ_SCREEN_CAPTURE = 1001
        private const val CAPTURE_DELAY_MS = 350L

        @Volatile
        private var cachedProjection: MediaProjection? = null
        private var cachedResultCode: Int = 0
        private var cachedResultData: Intent? = null

        fun startCapture(context: Context, triggerFrom: String = "unknown") {
            AppLog.i("CaptureActivity", "startCapture 被调用", triggerFrom)
            val intent = Intent(context, CaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("trigger_from", triggerFrom)
            }
            context.startActivity(intent)
        }

        fun clearCachedProjection() {
            cachedProjection?.stop()
            cachedProjection = null
            cachedResultData = null
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val extractor = CodeExtractor()
    private lateinit var islandManager: IslandNotificationManager
    private lateinit var repository: CodeRepository
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var triggerFrom: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        triggerFrom = intent?.getStringExtra("trigger_from") ?: "unknown"

        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setContentView(View(this).apply { alpha = 0f })

        islandManager = IslandNotificationManager(this)
        repository = CodeRepository(this)

        Log.d(TAG, "onCreate. Cached projection exists: ${cachedProjection != null}, triggerFrom=$triggerFrom")
        AppLog.i("CaptureActivity", "onCreate — 缓存token存在=${cachedProjection != null}, 触发入口=$triggerFrom", triggerFrom)

        handler.postDelayed({ beginCapture() }, CAPTURE_DELAY_MS)
    }

    private fun beginCapture() {
        if (cachedProjection != null && cachedResultData != null) {
            Log.d(TAG, "Using cached projection")
            AppLog.i("CaptureActivity", "使用缓存的 MediaProjection，跳过授权", triggerFrom)
            performScreenCapture(cachedResultCode, cachedResultData!!)
        } else {
            Log.d(TAG, "No cache, requesting permission")
            AppLog.i("CaptureActivity", "无缓存，请求录屏授权", triggerFrom)
            requestScreenCapturePermission()
        }
    }

    private fun requestScreenCapturePermission() {
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
            AppLog.i("CaptureActivity", "弹出录屏授权对话框", triggerFrom)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture intent", e)
            AppLog.e("CaptureActivity", "启动录屏授权对话框失败", e, triggerFrom)
            showToast("无法启动截屏权限请求")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCREEN_CAPTURE) return

        if (resultCode == RESULT_OK && data != null) {
            Log.d(TAG, "User granted permission")
            AppLog.i("CaptureActivity", "用户授权了录屏权限", triggerFrom)
            cachedResultCode = resultCode
            cachedResultData = data

            val mpm = getSystemService(MediaProjectionManager::class.java)
            cachedProjection = mpm.getMediaProjection(resultCode, data)
            cachedProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "Projection stopped, clearing cache")
                    AppLog.w("CaptureActivity", "MediaProjection 已停止，缓存已清除", triggerFrom)
                    cachedProjection = null
                }
            }, null)

            performScreenCapture(resultCode, data)
        } else {
            AppLog.w("CaptureActivity", "用户拒绝了录屏授权", triggerFrom)
            showToast("需要截屏权限才能识别验证码")
            finish()
        }
    }

    private fun performScreenCapture(resultCode: Int, resultData: Intent) {
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val projection = cachedProjection ?: mpm.getMediaProjection(resultCode, resultData)

            val wm = getSystemService(WindowManager::class.java)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            val dpi = metrics.densityDpi
            Log.d(TAG, "Screen: ${w}x${h} @ ${dpi}dpi")
            AppLog.d("CaptureActivity", "屏幕参数: ${w}x${h} @ ${dpi}dpi", triggerFrom)

            imageReader?.close()
            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            AppLog.d("CaptureActivity", "创建 VirtualDisplay (${w}x${h})", triggerFrom)

            virtualDisplay = projection.createVirtualDisplay(
                "PickCode-Capture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )

            scope.launch {
                delay(400)
                captureFrameAndProcess()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException - token expired?", e)
            AppLog.e("CaptureActivity", "SecurityException - token 可能已过期，重新请求授权", e, triggerFrom)
            clearCachedProjection()
            requestScreenCapturePermission()
        } catch (e: Exception) {
            Log.e(TAG, "performScreenCapture failed", e)
            AppLog.e("CaptureActivity", "performScreenCapture 失败", e, triggerFrom)
            islandManager.showError()
            safeFinishDelayed(500)
        }
    }

    private suspend fun captureFrameAndProcess() {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                Log.w(TAG, "acquireLatestImage returned null")
                AppLog.w("CaptureActivity", "acquireLatestImage 返回 null，截图失败", triggerFrom)
                islandManager.showNoResult()
                safeFinishDelayed(300)
                return
            }

            val bitmap: Bitmap = try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width
                Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                ).also { it.copyPixelsFromBuffer(buffer) }
            } finally { image.close() }

            Log.d(TAG, "Captured: ${bitmap.width}x${bitmap.height}, running OCR...")
            AppLog.d("CaptureActivity", "截图成功 (${bitmap.width}x${bitmap.height})，开始 OCR", triggerFrom)

            val record = withContext(Dispatchers.IO) {
                try { extractor.extractFromBitmap(bitmap) }
                finally { bitmap.recycle() }
            }

            withContext(Dispatchers.Main) {
                if (record != null) {
                    Log.i(TAG, "Code found: ${AppLog.maskCode(record.code)} (${record.codeType})")
                    AppLog.i("CaptureActivity", "OCR识别成功：${record.codeType.emoji} ${AppLog.maskCode(record.code)}", triggerFrom)
                    scope.launch(Dispatchers.IO) { repository.insert(record) }
                    islandManager.showCode(record)
                    sendBroadcast(Intent(PickCodeApp.ACTION_CODE_UPDATED).setPackage(packageName))
                } else {
                    Log.d(TAG, "No code found")
                    AppLog.w("CaptureActivity", "OCR未识别到取件码", triggerFrom)
                    islandManager.showNoResult()
                }
                safeFinishDelayed(500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureFrameAndProcess error", e)
            AppLog.e("CaptureActivity", "captureFrameAndProcess 异常", e, triggerFrom)
            islandManager.showError()
            safeFinishDelayed(500)
        }
    }

    private fun safeFinishDelayed(ms: Long) {
        handler.postDelayed({
            runOnUiThread { try { finish() } catch (_: Exception) {} }
        }, ms)
    }

    override fun onDestroy() {
        AppLog.d("CaptureActivity", "onDestroy — Activity 销毁", triggerFrom)
        scope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun showToast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }
}
