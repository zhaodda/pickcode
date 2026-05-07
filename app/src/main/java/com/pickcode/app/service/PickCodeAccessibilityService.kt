package com.pickcode.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.ocr.CodeExtractor
import com.pickcode.app.overlay.IslandNotificationManager
import kotlinx.coroutines.*

/**
 * 码速达无障碍服务（AccessibilityService）
 *
 * 核心功能：通过系统无障碍 API 截取屏幕内容，无需 MediaProjection 录屏权限弹窗。
 *
 * ══ 为什么用 AccessibilityService？══
 * 1. 一次授权永久使用，无需每次弹出录屏选择框
 * 2. 点击即识别，不依赖 FGS 权限，不会闪退
 * 3. Tile / 通知 / FAB 统一路径，稳定可靠
 *
 * ══ 截屏流程 ═══
 * triggerScreenshot() -> Handler 延迟(500ms) -> takeScreenshot() -> OCR -> 展示灵动岛
 */
class PickCodeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PickCodeA11y"

        /** 通知栏"立即识别"按钮广播 Action */
        const val ACTION_TRIGGER_SCREENSHOT = "com.pickcode.TRIGGER_SCREENSHOT"

        /** 识别成功广播 Action */
        const val ACTION_CODE_FOUND = "com.pickcode.CODE_FOUND"

        /** MediaProjection 降级：需要权限时发出的广播 */
        const val ACTION_NEED_PERMISSION = "com.pickcode.NEED_PERMISSION"

        @Volatile
        private var instance: PickCodeAccessibilityService? = null

        /** 服务是否已连接并可用 */
        val isAvailable: Boolean get() = instance != null

        /**
         * 触发截图识别（从任意入口调用）
         * @return true=已触发, false=服务未连接
         */
        fun triggerScreenshot(): Boolean {
            val svc = instance ?: return false
            Log.d(TAG, "triggerScreenshot() called")
            svc.doCapture()
            return true
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val extractor = CodeExtractor()
    private lateinit var islandManager: IslandNotificationManager
    private lateinit var repository: com.pickcode.app.data.repository.CodeRepository

    private val handler = Handler(Looper.getMainLooper())

    /** 广播接收器：接收通知栏按钮的 ACTION_TRIGGER_SCREENSHOT */
    private val screenshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TRIGGER_SCREENSHOT) {
                doCapture()
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  生命周期
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        repository = com.pickcode.app.data.repository.CodeRepository(this)
        islandManager = IslandNotificationManager(this)

        // 动态配置截屏能力
        try {
            val info = serviceInfo
            // flagRequestScreenshot 是 API 34 的字段，运行时通过反射设置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val flagField = AccessibilityServiceInfo::class.java.getDeclaredField("flagRequestScreenshot")
                    val currentFlags = info.flags
                    val newFlags = currentFlags or flagField.getInt(null)
                    info.flags = newFlags
                    Log.d(TAG, "Set flagRequestScreenshot via reflection")
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot set flagRequestScreenshot", e)
                }
            }
            serviceInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "Cannot set flagRequestScreenshot", e)
        }

        // 注册广播接收器
        registerReceiver(screenshotReceiver, IntentFilter(ACTION_TRIGGER_SCREENSHOT))

        Log.i(TAG, "AccessibilityService connected. SDK ${Build.VERSION.SDK_INT}")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { unregisterReceiver(screenshotReceiver) } catch (_: Exception) {}
        scope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  截屏 & 识别核心逻辑
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    internal fun doCapture() {
        handler.postDelayed({ performScreenshot() }, 500)
    }

    private fun performScreenshot() {
        try {
            when {
                Build.VERSION.SDK_INT >= 34 -> takeScreenshotApi34()
                else -> takeScreenshotLegacy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed", e)
            islandManager.showError()
        }
    }

    /**
     * API 34+ (Android 15): 使用 takeScreenshot(int, Executor, TakeScreenshotCallback) 异步回调
     *
     * 注意：此方法使用了 @Suppress("NewApi") 因为 targetSdk=33 编译时该重载不可见。
     * 但运行时如果设备是 API 34+ 则可通过反射或直接调用。
     * 为保证编译通过，使用反射调用方式。
     */
    private fun takeScreenshotApi34() {
        try {
            // 反射获取 takeScreenshot(int, Executor, TakeScreenshotCallback) 方法
            val method = AccessibilityService::class.java.getDeclaredMethod(
                "takeScreenshot",
                Int::class.javaPrimitiveType,
                java.util.concurrent.Executor::class.java,
                Class.forName("android.accessibilityservice.AccessibilityService\$TakeScreenshotCallback")
            )
            method.isAccessible = true

            // 创建回调对象（使用 Proxy 动态代理）
            val callbackClass = Class.forName("android.accessibilityservice.AccessibilityService\$TakeScreenshotCallback")
            val handler = object : java.lang.reflect.InvocationHandler {
                override fun invoke(proxy: Any?, m: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                    if (m.name == "onCompleted") {
                        handleScreenshotResult(args?.get(0))
                    } else if (m.name == "toString") {
                        return "PickCodeScreenshotCallback"
                    }
                    return null
                }
            }
            val callback = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass),
                handler
            )

            method.invoke(this, 0, mainExecutor, callback)
        } catch (e: Exception) {
            Log.e(TAG, "API 34 screenshot failed, falling back to legacy", e)
            // 反射失败则降级到同步方式
            takeScreenshotLegacy()
        }
    }

    /**
     * 通过反射处理 ScreenshotResult → Bitmap
     */
    /**
     * 处理 API 34+ takeScreenshot 回调结果（在 InvocationHandler 中调用）
     */
    private fun handleScreenshotResult(result: Any?) {
        if (result == null) {
            Log.w(TAG, "takeScreenshot returned null")
            handler.post { islandManager.showError() }
            return
        }

        val bitmap = try {
            val hwMethod = result.javaClass.getMethod("getHardwareBitmap")
            hwMethod.invoke(result) as? Bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Cannot getHardwareBitmap via reflection", e)
            null
        }

        if (bitmap != null) {
            processBitmap(bitmap)
        } else {
            Log.w(TAG, "hardwareBitmap is null, trying snapshot conversion")
            processSnapshotViaReflection(result)
        }
    }

    private fun processSnapshotViaReflection(result: Any) {
        try {
            val hwBitmap = result.javaClass.methods.find { it.name == "getHardwareBitmap" }?.invoke(result) as? Bitmap
            if (hwBitmap != null) {
                processBitmap(hwBitmap)
            } else {
                Log.w(TAG, "Cannot extract bitmap from snapshot result at all")
                islandManager.showError()
            }
        } catch (e: Exception) {
            Log.e(TAG, "processSnapshotViaReflection failed", e)
            islandManager.showError()
        }
    }

    /**
     * 降级方案: API < 34 时使用同步 takeScreenshot
     * API 28-33: takeScreenshot(int) 同步返回 ScreenshotResult
     */
    @Suppress("DEPRECATION")
    private fun takeScreenshotLegacy() {
        // 尝试调用单参数版本的 takeScreenshot(int)
        val result = try {
            val method = AccessibilityService::class.java.methods.find {
                it.name == "takeScreenshot" && it.parameterCount == 1
            }
            method?.invoke(this, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Legacy takeScreenshot failed", e)
            null
        }

        if (result == null) {
            Log.w(TAG, "takeScreenshot not supported on this device (SDK ${Build.VERSION.SDK_INT})")
            islandManager.showError()
            return
        }

        // 处理结果 - 尝试获取 Bitmap
        processSnapshotViaReflection(result)
    }

    private fun processBitmap(bitmap: Bitmap) {
        Log.d(TAG, "Screenshot captured: ${bitmap.width}x${bitmap.height}")

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
        islandManager.showCode(record)
        sendBroadcast(Intent(ACTION_CODE_FOUND).setPackage(packageName))
    }
}
