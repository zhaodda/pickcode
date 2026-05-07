package com.pickcode.app.service

import android.app.*
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
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pickcode.app.PickCodeApp
import com.pickcode.app.R
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.data.repository.CodeRepository
import com.pickcode.app.ocr.CodeExtractor
import com.pickcode.app.overlay.IslandNotificationManager
import com.pickcode.app.ui.activity.MainActivity
import com.pickcode.app.ui.activity.PermissionActivity
import kotlinx.coroutines.*

/**
 * PickCode 核心前台服务
 *
 * 职责：
 * - 常驻前台通知（低优先级，始终存在）
 * - 通过 MediaProjection 执行屏幕截图
 * - 调用 ML Kit OCR 识别取件码
 * - 通过 IslandNotificationManager 展示灵动岛通知（自动适配厂商）
 *
 * ══ 多厂商灵动岛展示策略 ══
 * IslandNotificationManager 内部由 IslandManagerFactory 自动选择最优实现：
 * - 小米澎湃OS3+（focusProtocolVersion >= 3）  → 原生超级岛（顶部胶囊展开动效）
 * - 小米澎湃OS1/OS2（focusProtocolVersion 1-2）→ 焦点通知横幅
 * - OPPO ColorOS 16+                           → 流体云（Live Updates API）
 * - OPPO ColorOS 14/15                         → 流体云（兼容模式）
 * - vivo OriginOS 3.0+                         → 原子岛（需白名单）
 * - 其他安卓设备                                → 标准高优先级通知横幅
 *
 * ⚠️ 已移除：SYSTEM_ALERT_WINDOW 悬浮窗方案（OverlayManager）
 * ✅ 替代方案：各厂商原生岛通知接口（统一由 IslandManagerFactory 分发）
 *
 * ══ 复制验证码 ══
 * 通知上的"复制"按钮由 [CopyCodeReceiver] 处理（在 Manifest 中注册）
 */
class PickCodeService : Service() {

    companion object {
        const val ACTION_TRIGGER       = "com.pickcode.TRIGGER"
        const val ACTION_STOP          = "com.pickcode.STOP"
        const val ACTION_MEDIA_RESULT  = "com.pickcode.MEDIA_RESULT"
        const val EXTRA_RESULT_CODE    = "result_code"
        const val EXTRA_RESULT_DATA    = "result_data"
        const val NOTIFICATION_ID      = 1001

        fun triggerCapture(context: Context) {
            context.startService(Intent(context, PickCodeService::class.java).apply {
                action = ACTION_TRIGGER
            })
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val extractor = CodeExtractor()
    private lateinit var repository: CodeRepository
    private lateinit var islandManager: IslandNotificationManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var pendingResultCode: Int = 0
    private var pendingResultData: Intent? = null

    override fun onCreate() {
        super.onCreate()
        repository = CodeRepository(this)
        islandManager = IslandNotificationManager(this)
        startForeground(NOTIFICATION_ID, buildPersistentNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER -> handleTrigger()
            ACTION_STOP    -> stopSelf()
            ACTION_MEDIA_RESULT -> {
                pendingResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                pendingResultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                startCapture()
            }
        }
        return START_STICKY
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  触发入口
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun handleTrigger() {
        if (mediaProjection != null) {
            // 已持有 MediaProjection 权限，直接截图
            startCapture()
        } else {
            // 启动透明 Activity 向用户申请截屏授权
            startActivity(
                Intent(this, PermissionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  截屏 & OCR
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun startCapture() {
        val resultCode = pendingResultCode
        val resultData = pendingResultData ?: return

        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mediaProjection == null) {
            mediaProjection = mpm.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { mediaProjection = null }
            }, null)
        }

        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        imageReader?.close()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PickCode",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        // 等待一帧渲染完成后再读取，避免获取到空帧
        scope.launch {
            delay(300)
            captureFrame()
        }
    }

    private fun captureFrame() {
        val image = imageReader?.acquireLatestImage() ?: return
        val bitmap: Bitmap = try {
            val planes  = image.planes
            val buffer  = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride   = planes[0].rowStride
            val rowPadding  = rowStride - pixelStride * image.width
            val bmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            bmp
        } finally {
            image.close()
        }

        scope.launch(Dispatchers.IO) {
            try {
                val record = extractor.extractFromBitmap(bitmap)
                bitmap.recycle()
                withContext(Dispatchers.Main) {
                    if (record != null) onCodeFound(record)
                    else islandManager.showNoResult()
                }
            } catch (e: Exception) {
                bitmap.recycle()
                withContext(Dispatchers.Main) { islandManager.showError() }
            }
        }
    }

    private fun onCodeFound(record: CodeRecord) {
        scope.launch { repository.insert(record) }
        // 展示超级岛通知
        // IslandNotificationManager 内部完成：版本检测 → miui.focus.param 注入 → 降级处理
        islandManager.showCode(record)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  常驻通知（前台服务保活）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun buildPersistentNotification(): Notification {
        val tapIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PickCodeService::class.java).apply { action = ACTION_TRIGGER },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PickCodeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val mainIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, PickCodeApp.CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("码速达 已就绪")
            .setContentText("点击立即识别屏幕取件码")
            .setContentIntent(tapIntent)
            .addAction(0, "去主界面", mainIntent)
            .addAction(0, "停止服务", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        islandManager.dismiss()
        super.onDestroy()
    }
}
