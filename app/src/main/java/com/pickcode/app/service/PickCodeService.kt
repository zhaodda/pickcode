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
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pickcode.app.PickCodeApp
import com.pickcode.app.R
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.data.model.CodeType
import com.pickcode.app.data.repository.CodeRepository
import com.pickcode.app.ocr.CodeExtractor
import com.pickcode.app.overlay.IslandNotificationManager
import com.pickcode.app.ui.activity.MainActivity
import kotlinx.coroutines.*

/**
 * PickCode 核心前台服务
 *
 * 职责：
 * - 常驻前台通知（低优先级，始终存在）— 这是所有截屏入口的中转站
 * - 通过 AccessibilityService (v1.1.0+) 或 MediaProjection 执行屏幕截图
 * - 调用 ML Kit OCR 识别取件码
 * - 通过 IslandNotificationManager 展示灵动岛通知（自动适配厂商）
 *
 * ══ v1.1.0 架构变更 ═══
 * 所有截屏入口统一通过 PickCodeService 中转：
 * 通知栏/Tile/FAB -> PickCodeService (ACTION_TRIGGER) -> AccessibilityService 或 MediaProjection
 *
 * 原因：AccessibilityService 不保证常驻运行（系统可能延迟绑定或回收），
 * 但 PickCodeService 是前台服务，始终存活，作为可靠的中转枢纽。
 */
class PickCodeService : Service() {

    companion object {
        private const val TAG = "PickCodeSvc"

        const val ACTION_TRIGGER       = "com.pickcode.TRIGGER"
        const val ACTION_STOP          = "com.pickcode.STOP"
        const val ACTION_MEDIA_RESULT  = "com.pickcode.MEDIA_RESULT"
        const val ACTION_MANUAL_INPUT  = "com.pickcode.MANUAL_INPUT"
        const val EXTRA_RESULT_CODE    = "result_code"
        const val EXTRA_RESULT_DATA    = "result_data"
        const val EXTRA_MANUAL_CODE    = "manual_code"
        const val EXTRA_MANUAL_TYPE    = "manual_type"   // CodeType ordinal
        const val EXTRA_MANUAL_RAW     = "manual_raw"
        const val NOTIFICATION_ID      = 1001

        /**
         * 触发截屏识别（统一入口 — 从任意地方调用）
         *
         * 流程：启动 PickCodeService(ACTION_TRIGGER) -> 服务内部判断走哪条路径
         */
        fun triggerCapture(context: Context) {
            val intent = Intent(context, PickCodeService::class.java).apply {
                action = ACTION_TRIGGER
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start capture service", e)
            }
        }

        /**
         * 提交手动输入的取件码（从 MainActivity 对话框调用）
         */
        fun submitManualCode(context: Context, code: String, typeOrdinal: Int, rawText: String) {
            val intent = Intent(context, PickCodeService::class.java).apply {
                action = ACTION_MANUAL_INPUT
                putExtra(EXTRA_MANUAL_CODE, code)
                putExtra(EXTRA_MANUAL_TYPE, typeOrdinal)
                putExtra(EXTRA_MANUAL_RAW, rawText)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val extractor = CodeExtractor()
    private lateinit var repository: CodeRepository
    private lateinit var islandManager: IslandNotificationManager

    // MediaProjection 降级方案相关字段
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var pendingResultCode: Int = 0
    private var pendingResultData: Intent? = null

    /** 标记是否有有效的 MediaProjection 授权（用于外部查询） */
    var hasMediaProjection: Boolean
        get() = mediaProjection != null
        private set(_) {}

    override fun onCreate() {
        super.onCreate()
        repository = CodeRepository(this)
        islandManager = IslandNotificationManager(this)

        try {
            val notification = buildPersistentNotification()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else 0
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        Log.i(TAG, "PickCodeService created and running as foreground")
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
            ACTION_MANUAL_INPUT -> handleManualInput(intent)
        }
        return START_STICKY
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  触发入口（所有截屏的统一中转点）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 处理截屏触发请求
     *
     * 策略：
     * 1. 先尝试直接调 AccessibilityService.triggerScreenshot()（如果已连接）
     * 2. 如果没连接，发广播尝试唤醒它
     * 3. 最终兜底：Toast 提示用户开启无障碍服务
     */
    private fun handleTrigger() {
        Log.d(TAG, "handleTrigger() called")

        showToast("正在截图识别...")

        // 方案1：直接调用无障碍服务
        if (PickCodeAccessibilityService.isAvailable) {
            Log.d(TAG, "AccessibilityService is available, triggering directly")
            if (PickCodeAccessibilityService.triggerScreenshot()) {
                return
            }
        }

        // 方案2：发送广播唤醒无障碍服务
        Log.d(TAG, "AccessibilityService not available yet, sending wake-up broadcast")
        sendBroadcast(Intent(PickCodeAccessibilityService.ACTION_TRIGGER_SCREENSHOT).setPackage(packageName))

        // 方案2补充：给一点时间让广播到达后再检查一次
        scope.launch {
            delay(800)
            if (PickCodeAccessibilityService.isAvailable && PickCodeAccessibilityService.triggerScreenshot()) {
                return@launch
            }
            // 方案3：都没成功，提示用户
            Log.w(TAG, "All screenshot methods failed. A11y available=${PickCodeAccessibilityService.isAvailable}")
            showToast("请确保「无障碍服务」已开启（设置→辅助功能→码速达）")
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  截屏 & OCR（MediaProjection 降级路径）
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  手动输入处理
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun handleManualInput(intent: Intent) {
        val code = intent.getStringExtra(EXTRA_MANUAL_CODE) ?: return
        val typeOrdinal = intent.getIntExtra(EXTRA_MANUAL_TYPE, CodeType.OTHER.ordinal)
        val rawText = intent.getStringExtra(EXTRA_MANUAL_RAW) ?: code

        val codeType = CodeType.values().getOrElse(typeOrdinal) { CodeType.OTHER }
        val record = CodeRecord(
            code = code.trim(),
            codeType = codeType,
            rawText = rawText
        )
        onCodeFound(record)
    }

    private fun onCodeFound(record: CodeRecord) {
        scope.launch { repository.insert(record) }
        islandManager.showCode(record)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  常驻通知（前台服务保活）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 构建常驻前台通知
     *
     * v1.1.0: "立即识别"按钮改回 PendingIntent.getService -> PickCodeService
     * PickCodeService 是前台常驻服务，一定能收到并处理。
     */
    private fun buildPersistentNotification(): Notification {
        // 点击通知主体 -> 打开 MainActivity
        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // "立即识别" 按钮 -> 启动 PickCodeService 处理（常驻服务，必定在线）
        val triggerIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PickCodeService::class.java).apply { action = ACTION_TRIGGER },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // "停止服务" 按钮
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PickCodeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, PickCodeApp.CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("码速达 已就绪")
            .setContentText("点击打开主界面，或用下方按钮立即识别")
            .setContentIntent(mainIntent)
            .addAction(0, "\uD83D\uDCEE 立即识别", triggerIntent)
            .addAction(0, "停止服务", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 在主线程显示 Toast */
    private fun showToast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
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
