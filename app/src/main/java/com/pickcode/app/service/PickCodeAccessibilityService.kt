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
import android.view.accessibility.AccessibilityNodeInfo
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.ocr.CodeExtractor
import com.pickcode.app.overlay.IslandNotificationManager
import com.pickcode.app.util.AppLog
import kotlinx.coroutines.*

/**
 * 码速达无障碍服务（AccessibilityService）
 *
 * ══ 核心能力演进 ═══
 * v1.0: takeScreenshot() API 截图 → ML Kit OCR（不稳定，isAvailable=false）
 * v1.1: **AccessibilityNodeInfo 节点树文字提取**（效仿 Tally 记账App方案）
 *       直接从系统 UI 节点树读取屏幕文字，无需截图、无需OCR、速度快、准确率100%
 * 降级: 若节点树提取失败 → 仍保留 takeScreenshot + OCR 作为降级方案
 *
 * ══ 工作流程 ═══
 * 触发入口(通知/Tile/FAB) → extractFromScreenText()
 *   → getRootInActiveWindow() → 遍历节点树拼接文本 → CodeExtractor.parseCode()
 *   → 识别到取件码 → 展示灵动岛 + 存库
 *   → 未识别到 → 降级 CaptureActivity (MediaProjection)
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
         * 从当前屏幕提取文字并识别取件码（首选方案）
         *
         * 效仿 Tally 记账 App 的 AccessibilityNodeInfo 节点树遍历方案，
         * 直接读取系统 UI 组件的文字内容，无需截图和 OCR。
         *
         * @return CodeRecord? 识别到的取件码记录，null 表示未识别到或服务不可用
         */
        fun extractFromScreenText(): CodeRecord? {
            val svc = instance ?: run {
                Log.d(TAG, "extractFromScreenText: service not available")
                return null
            }
            return svc.doExtractFromScreenText("auto")
        }

        /**
         * 触发识别（兼容旧接口，内部优先走文字提取）
         * @return true=已触发, false=服务未连接
         */
        fun triggerScreenshot(): Boolean {
            val svc = instance ?: run {
                Log.w(TAG, "triggerScreenshot: AccessibilityService not available")
                return false
            }
            Log.i(TAG, "triggerScreenshot() called")
            // 优先走无障碍文字提取
            svc.handler.postDelayed({ svc.doExtractFromScreenText("manual") }, 300)
            return true
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val extractor = CodeExtractor()
    private lateinit var islandManager: IslandNotificationManager
    private lateinit var repository: com.pickcode.app.data.repository.CodeRepository

    private val handler = Handler(Looper.getMainLooper())

    /** 上次提取时间，防重复触发（3秒内不重复提取） */
    private var lastExtractTime = 0L
    private val EXTRACT_DEBOUNCE_MS = 3000L

    /** 广播接收器：接收通知栏按钮的 ACTION_TRIGGER_SCREENSHOT */
    private val screenshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TRIGGER_SCREENSHOT) {
                doExtractFromScreenText("notification")
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

        // 配置服务信息
        try {
            val info = serviceInfo
            Log.d(TAG, "onServiceConnected: flags=${info.flags}, SDK=${Build.VERSION.SDK_INT}")

            // API 34+ 设置截屏能力 flag（保留用于降级方案）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val flagField = AccessibilityServiceInfo::class.java.getDeclaredField("flagRequestScreenshot")
                    val newFlags = info.flags or flagField.getInt(null)
                    info.flags = newFlags
                    Log.d(TAG, "Set flagRequestScreenshot via reflection")
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot set flagRequestScreenshot", e)
                }
            }
            serviceInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "Cannot configure service info", e)
        }

        // 注册广播接收器
        try {
            registerReceiver(screenshotReceiver, IntentFilter(ACTION_TRIGGER_SCREENSHOT))
            Log.d(TAG, "Broadcast receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register broadcast receiver", e)
        }

        AppLog.i(TAG, "无障碍服务已连接（节点树文字提取模式）")
        Log.i(TAG, "AccessibilityService connected. SDK ${Build.VERSION.SDK_INT}")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { unregisterReceiver(screenshotReceiver) } catch (_: Exception) {}
        scope.cancel()
    }

    /**
     * 无障碍事件监听（保留用于未来自动检测场景）
     * 当前版本主要靠手动触发，此方法暂不做自动提取逻辑
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 暂不在事件中自动触发提取，避免在用户滑动等操作时误触发
        // 未来可考虑：监听特定包名（如快递App）的窗口变化自动提取
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  核心：节点树文字提取（效仿 Tally）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 执行从当前屏幕提取文字并识别取件码
     * @param from 触发来源："auto" / "notification" / "tile" / "manual"
     * @return CodeRecord? 识别结果
     */
    internal fun doExtractFromScreenText(from: String): CodeRecord? {
        // 防抖：短时间内的重复调用直接忽略
        val now = System.currentTimeMillis()
        if (now - lastExtractTime < EXTRACT_DEBOUNCE_MS) {
            Log.d(TAG, "Ignored extract request (debounce), from=$from")
            return null
        }
        lastExtractTime = now

        AppLog.i(TAG, "开始从屏幕提取文字 [from=$from]", from)

        try {
            val root = rootInActiveWindow
            if (root == null) {
                Log.w(TAG, "rootInActiveWindow is null")
                AppLog.w(TAG, "无法获取当前屏幕节点树", from)
                return null
            }

            // 提取全屏文字
            val fullText = getAllTextFromNode(root)
            if (fullText.isBlank()) {
                Log.w(TAG, "Screen text is empty")
                AppLog.w(TAG, "屏幕文字为空", from)
                return null
            }

            AppLog.d(TAG, "提取到屏幕文字(${fullText.length}字符): ${fullText.take(150)}")

            // 用正则解析取件码
            val record = extractor.extractFromText(fullText)
            if (record != null) {
                AppLog.i(TAG, "✅ 从屏幕文字识别到取件码: ${record.code} (${record.codeType})", from)
                onCodeFound(record)
                return record
            } else {
                AppLog.i(TAG, "❌ 屏幕文字中未找到取件码", from)
                return null
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in extractFromScreenText", e)
            AppLog.e(TAG, "安全异常: ${e.message}", throwable = e, triggerFrom = from)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error in extractFromScreenText", e)
            AppLog.e(TAG, "提取异常: ${e.javaClass.simpleName}: ${e.message}", throwable = e, triggerFrom = from)
            return null
        }
    }

    /**
     * 递归遍历 AccessibilityNodeInfo 节点树，提取所有文本内容
     *
     * 效仿 Tally 记账 App 的 getAllTextFromNode() 方案。
     * Android 系统的每个 UI 控件都是一个 AccessibilityNodeInfo 节点，
     * 通过 getText() 和 getContentDescription() 可以获取其显示的文字。
     * 递归遍历整棵树就能拿到屏幕上的所有可见文字。
     *
     * @param node 起始节点
     * @return 拼接后的完整屏幕文本
     */
    private fun getAllTextFromNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""

        val sb = StringBuilder()

        // 获取节点自身的文字
        node.text?.let { text ->
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                sb.append(trimmed).append(" ")
            }
        }

        // 获取 contentDescription（图片按钮等的描述文字）
        node.contentDescription?.let { desc ->
            val trimmed = desc.trim()
            // 避免与 text 重复添加
            if (trimmed.isNotEmpty() && !sb.contains(trimmed)) {
                sb.append(trimmed).append(" ")
            }
        }

        // 递归遍历子节点
        val childCount = node.childCount
        for (i in 0 until childCount) {
            sb.append(getAllTextFromNode(node.getChild(i)))
        }

        return sb.toString()
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  降级：takeScreenshot 截屏方案（保留）
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    internal fun doCapture() {
        handler.postDelayed({ performScreenshot() }, 500)
    }

    private fun performScreenshot() {
        try {
            Log.d(TAG, "performScreenshot (fallback): SDK=${Build.VERSION.SDK_INT}")
            when {
                Build.VERSION.SDK_INT >= 34 -> takeScreenshotApi34()
                Build.VERSION.SDK_INT >= 28 -> takeScreenshotLegacy()
                else -> {
                    Log.w(TAG, "Android version too low for accessibility screenshot")
                    islandManager.showError()
                    notifyNeedMediaProjection()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed", e)
            islandManager.showError()
            notifyNeedMediaProjection()
        }
    }

    private fun notifyNeedMediaProjection() {
        try {
            sendBroadcast(Intent(ACTION_NEED_PERMISSION).setPackage(packageName))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send fallback broadcast", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun takeScreenshotApi34() {
        try {
            val method = AccessibilityService::class.java.getDeclaredMethod(
                "takeScreenshot",
                Int::class.javaPrimitiveType,
                java.util.concurrent.Executor::class.java,
                Class.forName("android.accessibilityservice.AccessibilityService\$TakeScreenshotCallback")
            )
            method.isAccessible = true

            val callbackClass = Class.forName("android.accessibilityservice.AccessibilityService\$TakeScreenshotCallback")
            val callbackHandler = object : java.lang.reflect.InvocationHandler {
                override fun invoke(proxy: Any?, m: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                    if (m.name == "onCompleted") handleScreenshotResult(args?.get(0))
                    else if (m.name == "toString") return "PickCodeScreenshotCallback"
                    return null
                }
            }
            val callback = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader, arrayOf(callbackClass), callbackHandler
            )
            method.invoke(this, 0, mainExecutor, callback)
        } catch (e: Exception) {
            Log.e(TAG, "API 34 screenshot failed, falling back to legacy", e)
            takeScreenshotLegacy()
        }
    }

    private fun handleScreenshotResult(result: Any?) {
        if (result == null) {
            handler.post { islandManager.showError() }
            return
        }
        val bitmap = try {
            result.javaClass.getMethod("getHardwareBitmap").invoke(result) as? Bitmap
        } catch (e: Exception) { null }
        if (bitmap != null) processBitmap(bitmap)
        else processSnapshotViaReflection(result)
    }

    private fun processSnapshotViaReflection(result: Any) {
        try {
            val hwBitmap = result.javaClass.methods.find { it.name == "getHardwareBitmap" }?.invoke(result) as? Bitmap
            if (hwBitmap != null) processBitmap(hwBitmap)
            else islandManager.showError()
        } catch (e: Exception) {
            Log.e(TAG, "processSnapshotViaReflection failed", e)
            islandManager.showError()
        }
    }

    @Suppress("DEPRECATION")
    private fun takeScreenshotLegacy() {
        val result = try {
            val method = AccessibilityService::class.java.methods.find {
                it.name == "takeScreenshot" && it.parameterCount == 1
            }
            method?.invoke(this, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Legacy takeScreenshot failed", e); null
        }
        if (result == null) {
            islandManager.showError(); return
        }
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  结果处理
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun onCodeFound(record: CodeRecord) {
        scope.launch { repository.insert(record) }
        islandManager.showCode(record)
        sendBroadcast(Intent(ACTION_CODE_FOUND).setPackage(packageName))
    }
}
