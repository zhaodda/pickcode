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
import androidx.core.app.ServiceCompat
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
 * - 处理手动输入的取件码并展示到灵动岛
 *
 * ══ 多厂商灵动岛展示策略 ═══
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
 * ══ 复制验证码 ═══
 * 通知上的"复制"按钮由 [CopyCodeReceiver] 处理（在 Manifest 中注册）
 */
class PickCodeService : Service() {

    companion object {
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
         * 触发截屏识别（从 Activity / Tile / 通知调用）
         *
         * 流程：
         * 1. 发送 ACTION_TRIGGER 给 Service
         * 2. Service 检查是否已有 MediaProjection 权限
         * 3. 有权限 → 直接截图 OCR
         * 4. 无权限 → 由调用方（Activity/Tile）负责拉起 PermissionActivity
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
                e.printStackTrace()
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

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var pendingResultCode: Int = 0
    private var pendingResultData: Intent? = null

    /** 标记是否有有效的 MediaProjection 授权（用于外部查询） */
    var hasMediaProjection: Boolean get() = mediaProjection != null

    override fun onCreate() {
        super.onCreate()
        repository = CodeRepository(this)
        islandManager = IslandNotificationManager(this)

        // 使用 ServiceCompat.startForeground() 兼容 Android 14+
        try {
            val notification = buildPersistentNotification()
            ServiceCompat.startForeground(
                /* service = */ this,
                /* id = */ NOTIFICATION_ID,
                /* notification = */ notification,
                /* foregroundServiceType = */
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else 0
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
    //  触发入口
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 处理截屏触发请求
     *
     * 关键逻辑变更：
     * - 如果已持有 MediaProjection 权限 → 直接截图
     * - 如果没有权限 → **发送广播通知调用方去拉起 PermissionActivity**
     *   （而不是自己 startActivity，因为 Service 启动 Activity 在某些场景受限）
     *
     * 调用方（MainActivity / Tile）收到此广播后应启动 PermissionActivity
     */
    private fun handleTrigger() {
        if (mediaProjection != null) {
            // 已持有 MediaProjection 权限，直接截图
            startCapture()
        } else {
            // 没有权限 → 发送广播，让 MainActivity 或 Tile 去拉起 PermissionActivity
            // 使用 LocalBroadcast 保证安全性和实时性
            val broadcastIntent = Intent(ACTION_NEED_PERMISSION).setPackage(packageName)
            sendBroadcast(broadcastIntent)
        }
    }

    companion object {
        /** Service 需要截屏权限时发出的广播，MainActivity/Tile 监听此广播来拉起授权页 */
        const val ACTION_NEED_PERMISSION = "com.pickcode.NEED_PERMISSION"
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  手动输入处理
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 处理从 MainActivity 提交的手动输入取件码
     * 直接保存到数据库并展示到灵动岛
     */
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
        // 展示超级岛通知
        islandManager.showCode(record)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  常驻通知（前台服务保活）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 构建常驻前台通知
     *
     * 通知交互设计：
     * - 点击通知主体 → 打开 MainActivity（同时携带触发识别的标记）
     * - "立即识别"按钮 → 发送 ACTION_TRIGGER（如果已有权限则直接截图）
     * - "停止服务"按钮 → 停止服务
     *
     * 设计原因：
     * - Android 对通知 PendingIntent 的限制：getService 在某些场景可能无法正确分发
     * - 打开 MainActivity 是最可靠的交互方式
     * - MainActivity 的 onResume 会检查是否需要触发识别
     */
    private fun buildPersistentNotification(): Notification {
        // 点击通知主体 → 打开 MainActivity
        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("from_notification", true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // "立即识别" 按钮 → 触发截屏（发送 ACTION_TRIGGER）
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
            .setContentText("点击打开主界面，或用下方按钮识别")
            .setContentIntent(mainIntent)
            .addAction(0, "\uD83D\uDCEE 立即识别", triggerIntent)
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
