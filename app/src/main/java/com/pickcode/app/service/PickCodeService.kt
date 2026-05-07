package com.pickcode.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pickcode.app.PickCodeApp
import com.pickcode.app.R
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.data.model.CodeType
import com.pickcode.app.data.repository.CodeRepository
import com.pickcode.app.ocr.CodeExtractor
import com.pickcode.app.overlay.IslandNotificationManager
import com.pickcode.app.ui.activity.CaptureActivity
import com.pickcode.app.util.AppLog
import kotlinx.coroutines.*

/**
 * PickCode 核心前台服务
 *
 * 职责：
 * - 常驻前台通知（低优先级，始终存在）
 * - 通知栏"立即识别"按钮 → 启动 CaptureActivity 截屏识别
 * - 处理手动输入的取件码并展示到灵动岛
 * - 通过 IslandNotificationManager 展示灵动岛通知（自动适配厂商）
 *
 * ══ v1.1.0 架构变更 ═══
 * 截屏逻辑已从 Service 移至 CaptureActivity：
 * - 通知栏/Tile/FAB -> CaptureActivity.startCapture()
 * - CaptureActivity 自行处理 MediaProjection 授权 + 截图 + OCR
 * - PickCodeService 仅负责常驻通知保活 + 手动输入处理
 */
class PickCodeService : Service() {

    companion object {
        private const val TAG = "PickCodeSvc"

        const val ACTION_TRIGGER       = "com.pickcode.TRIGGER"
        const val ACTION_STOP          = "com.pickcode.STOP"
        const val ACTION_MANUAL_INPUT  = "com.pickcode.MANUAL_INPUT"
        const val EXTRA_MANUAL_CODE    = "manual_code"
        const val EXTRA_MANUAL_TYPE    = "manual_type"   // CodeType ordinal
        const val EXTRA_MANUAL_RAW     = "manual_raw"
        const val NOTIFICATION_ID      = 1001

        /**
         * 触发截屏识别
         * 优先使用无障碍节点树文字提取（无需截图弹窗），失败后降级到 CaptureActivity
         */
        fun triggerCapture(context: Context) {
            AppLog.i("PickCodeService", "triggerCapture 被调用，尝试无障碍文字提取", "notification")

            // 方案A：优先使用无障碍服务提取屏幕文字（效仿Tally方案，无需截图和OCR）
            val result = PickCodeAccessibilityService.extractFromScreenText()
            if (result != null) {
                AppLog.i("PickCodeService", "✅ 无障碍文字提取成功: ${result.code}", "notification")
                return
            }

            // 方案B：无障碍不可用或未识别到 → 尝试异步触发无障碍截图
            if (PickCodeAccessibilityService.isAvailable) {
                AppLog.i("PickCodeService", "文字提取无结果，触发无障碍截屏降级", "notification")
                PickCodeAccessibilityService.triggerScreenshot()
                return
            }

            // 方案C：完全降级到 CaptureActivity（MediaProjection 录屏OCR）
            AppLog.w("PickCodeService", "无障碍服务未连接，降级到 CaptureActivity 截屏OCR", "notification")
            CaptureActivity.startCapture(context, "notification")
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
    private lateinit var repository: CodeRepository
    private lateinit var islandManager: IslandNotificationManager

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

        Log.i(TAG, "PickCodeService running as foreground")
        AppLog.i("PickCodeService", "PickCodeService 已作为前台服务启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER -> {
                Log.d(TAG, "ACTION_TRIGGER received, launching CaptureActivity")
                AppLog.i("PickCodeService", "收到 ACTION_TRIGGER 广播，启动截屏识别", "notification")
                triggerCapture(this@PickCodeService)
            }
            ACTION_STOP    -> { Log.d(TAG, "ACTION_STOP received"); stopSelf() }
            ACTION_MANUAL_INPUT -> handleManualInput(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  手动输入处理
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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

        scope.launch { repository.insert(record) }
        islandManager.showCode(record)

        // 通知 MainActivity 刷新列表
        sendBroadcast(Intent(PickCodeApp.ACTION_CODE_UPDATED).setPackage(packageName))
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  常驻通知
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun buildPersistentNotification(): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.pickcode.app.ui.activity.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // "立即识别" → 启动 CaptureActivity
        val captureIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, CaptureActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

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
            .addAction(0, "\uD83D\uDCEE 立即识别", captureIntent)
            .addAction(0, "停止服务", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        islandManager.dismiss()
        super.onDestroy()
    }
}
