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
import kotlinx.coroutines.*
import java.util.concurrent.Executors

/**
 * 截屏识别 Activity（透明全屏，用完即关）
 *
 * ══ 使用方式 ═══
 * 从任意入口调用 CaptureActivity.startCapture(context)
 * Activity 会自动处理：
 *   1. 首次使用 → 弹录屏授权 → 缓存 token → 截图
 *   2. 后续使用 → 直接用缓存 token 截图（不再弹窗！）
 *   3. 截图 → OCR → 展示灵动岛 → 自动关闭
 *
 * ══ 为什么不用 AccessibilityService？══
 * - takeScreenshot() API 在部分设备/ROM 上不稳定
 * - 无障碍服务不保证常驻运行
 * - MediaProjection 是最广泛兼容的方案
 */
class CaptureActivity : Activity() {

    companion object {
        private const val TAG = "CaptureActivity"
        private const val REQ_SCREEN_CAPTURE = 1001
        private const val CAPTURE_DELAY_MS = 350L

        /** 缓存的 MediaProjection（首次授权后复用，避免每次弹窗） */
        @Volatile
        private var cachedProjection: MediaProjection? = null
        private var cachedResultCode: Int = 0
        private var cachedResultData: Intent? = null

        /** 启动截屏识别（统一入口） */
        fun startCapture(context: Context) {
            val intent = Intent(context, CaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }

        fun clearCachedProjection() {
            cachedProjection?.stop()
            cachedProjection = null
            cachedResultData = null
        }
    }

    // 用自定义 CoroutineScope 替代 lifecycleScope（因为 Activity 没有内置 lifecycleScope）
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val extractor = CodeExtractor()
    private lateinit var islandManager: IslandNotificationManager
    private lateinit var repository: CodeRepository
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        Log.d(TAG, "onCreate. Cached projection exists: ${cachedProjection != null}")

        handler.postDelayed({ beginCapture() }, CAPTURE_DELAY_MS)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  截屏主流程
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun beginCapture() {
        if (cachedProjection != null && cachedResultData != null) {
            Log.d(TAG, "Using cached projection")
            performScreenCapture(cachedResultCode, cachedResultData!!)
        } else {
            Log.d(TAG, "No cache, requesting permission")
            requestScreenCapturePermission()
        }
    }

    private fun requestScreenCapturePermission() {
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture intent", e)
            showToast("无法启动截屏权限请求")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCREEN_CAPTURE) return

        if (resultCode == RESULT_OK && data != null) {
            Log.d(TAG, "User granted permission")
            cachedResultCode = resultCode
            cachedResultData = data

            val mpm = getSystemService(MediaProjectionManager::class.java)
            cachedProjection = mpm.getMediaProjection(resultCode, data)
            cachedProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "Projection stopped, clearing cache")
                    cachedProjection = null
                }
            }, null)

            performScreenCapture(resultCode, data)
        } else {
            showToast("需要截屏权限才能识别验证码")
            finish()
        }
    }

    /**
     * 创建 VirtualDisplay -> ImageReader 接帧 -> OCR
     */
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

            imageReader?.close()
            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

            virtualDisplay = projection.createVirtualDisplay(
                "PickCode-Capture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )

            // 等渲染完成再读取
            scope.launch {
                delay(400)
                captureFrameAndProcess()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException - token expired?", e)
            clearCachedProjection()
            requestScreenCapturePermission()
        } catch (e: Exception) {
            Log.e(TAG, "performScreenCapture failed", e)
            islandManager.showError()
            safeFinishDelayed(500)
        }
    }

    private suspend fun captureFrameAndProcess() {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                Log.w(TAG, "acquireLatestImage returned null")
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

            val record = withContext(Dispatchers.IO) {
                try { extractor.extractFromBitmap(bitmap) }
                finally { bitmap.recycle() }
            }

            withContext(Dispatchers.Main) {
                if (record != null) {
                    Log.i(TAG, "Code found: ${record.code} (${record.codeType})")
                    scope.launch(Dispatchers.IO) { repository.insert(record) }
                    islandManager.showCode(record)
                    sendBroadcast(Intent(PickCodeApp.ACTION_CODE_UPDATED).setPackage(packageName))
                } else {
                    Log.d(TAG, "No code found")
                    islandManager.showNoResult()
                }
                safeFinishDelayed(500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureFrameAndProcess error", e)
            islandManager.showError()
            safeFinishDelayed(500)
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  工具方法
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun safeFinishDelayed(ms: Long) {
        handler.postDelayed({
            runOnUiThread { try { finish() } catch (_: Exception) {} }
        }, ms)
    }

    override fun onDestroy() {
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
