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
import com.pickcode.app.overlay.IslandNotificationManager
import com.pickcode.app.util.AppLog
import kotlinx.coroutines.*

/**
 * PickCode 核心前台服务
 *
 * 职责：
 * - 常驻前台通知（低优先级，始终存在）
 * - 通知栏"立即识别"按钮 → 触发无障碍节点树文字提取
 * - 处理手动输入的取件码并展示到超级岛/通知栏
 * - 通过 IslandNotificationManager 展示通知（自动适配小米超级岛或标准横幅）
 *
 * ══ v1.3.0 架构（纯节点树提取） ═══
 * 所有识别入口统一走 AccessibilityService.extractFromScreenText()：
 * - 通知栏/Tile/FAB → extractFromScreenText() → 节点树遍历 → 正则匹配
 * - 不再使用 takeScreenshot API / CaptureActivity / MediaProjection
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
         * 触发识别：通过无障碍节点树文字提取屏幕内容
         * 唯一方案，无降级。如果无障碍服务不可用则提示用户开启。
         *
         * @param from 触发来源，notification/tile 会先关闭面板再提取
         */
        fun triggerCapture(context: Context, from: String = "auto") {
            AppLog.i("PickCodeService", "triggerCapture 被调用 [from=$from]", from)

            // 直接调用无障碍服务提取屏幕文字（传入来源以决定是否折叠面板）
            val result = PickCodeAccessibilityService.extractFromScreenText(from)
            if (result != null) {
                AppLog.i("PickCodeService", "✅ 识别成功: ${result.code}", from)
                return
            }

            // 无障碍服务不可用或未识别到 → 提示用户
            if (!PickCodeAccessibilityService.isAvailable) {
                AppLog.w("PickCodeService", "⚠️ 无障碍服务未连接，请确认已开启「码住」无障碍服务", from)
            } else {
                // notification/tile 触发时返回 null 是正常的（异步执行中）
                if (from == "notification" || from == "tile") {
                    AppLog.i("PickCodeService", "⏳ 异步提取中（面板关闭后自动执行）", from)
                } else {
                    AppLog.w("PickCodeService", "❌ 屏幕文字中未找到取件码", from)
                }
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
                Log.d(TAG, "ACTION_TRIGGER received")
                AppLog.i("PickCodeService", "收到 ACTION_TRIGGER 广播", "notification")
                triggerCapture(this@PickCodeService, "notification")
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
        com.pickcode.app.util.vibrateIfEnabled(this)

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

        // "立即识别" → 发广播给 AccessibilityService 执行节点树文字提取
        val captureIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(PickCodeAccessibilityService.ACTION_TRIGGER_SCREENSHOT).apply {
                `package` = packageName
                flags = Intent.FLAG_RECEIVER_FOREGROUND
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PickCodeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, PickCodeApp.CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("码住 已就绪")
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
        islandManager.dismissAll()
        super.onDestroy()
    }
}
