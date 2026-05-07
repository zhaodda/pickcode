package com.pickcode.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * ══ 核心能力：AccessibilityNodeInfo 节点树文字提取 ═══
 *
 * 效仿 Tally 记账 App 的方案，通过 getRootInActiveWindow() 获取当前屏幕的 UI 节点树，
 * 递归遍历所有节点拼接 getText() + getContentDescription() 得到屏幕完整文字，
 * 再用 CodeExtractor 正则匹配提取取件码。
 *
 * 优势：无需截图、无需 OCR、无需录屏授权弹窗、即时响应、100% 准确率。
 *
 * ══ 工作流程 ═══
 * 触发入口(通知/Tile/FAB) → extractFromScreenText()
 *   → getRootInActiveWindow() → 遍历节点树拼接文本 → CodeExtractor.extractFromText()
 *   → 识别到取件码 → 展示灵动岛 + 存库
 *   → 未识别到 → showNoResult() 提示用户
 */
class PickCodeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PickCodeA11y"

        /** 通知栏"立即识别"按钮广播 Action */
        const val ACTION_TRIGGER_SCREENSHOT = "com.pickcode.TRIGGER_SCREENSHOT"

        /** 识别成功广播 Action */
        const val ACTION_CODE_FOUND = "com.pickcode.CODE_FOUND"

        @Volatile
        private var instance: PickCodeAccessibilityService? = null

        /** 服务是否已连接并可用 */
        val isAvailable: Boolean get() = instance != null

        /**
         * 从当前屏幕提取文字并识别取件码（唯一入口）
         *
         * 效仿 Tally 记账 App 的 AccessibilityNodeInfo 节点树遍历方案，
         * 直接读取系统 UI 组件的文字内容，无需截图和 OCR。
         *
         * @param from 触发来源，决定是否需要先折叠通知栏面板：
         *            "auto"=内部调用(不折叠) / "notification"=通知栏按钮(折叠) /
         *            "tile"=快速设置磁贴(已由 TileService 自行折叠,不再重复折叠) /
         *            "fab"=悬浮按钮(不折叠) / "manual"=手动(不折叠)
         * @return CodeRecord? 识别到的取件码记录，null 表示未识别到或服务不可用
         */
        fun extractFromScreenText(from: String = "auto"): CodeRecord? {
            val svc = instance ?: run {
                Log.d(TAG, "extractFromScreenText: service not available")
                return null
            }
            return svc.doExtractFromScreenText(from)
        }

        /**
         * 折叠通知栏/QS 面板（公开方法，供外部调用）
         *
         * 使用 performGlobalAction(GLOBAL_ACTION_BACK) 模拟返回键来关闭面板。
         * TileService 优先使用 startActivityAndCollapse()，此方法作为降级方案。
         */
        fun collapsePanelIfNeeded() {
            val svc = instance ?: return
            try {
                val result = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Log.d(TAG, "collapsePanelIfNeeded: GLOBAL_ACTION_BACK result=$result")
            } catch (e: Exception) {
                Log.w(TAG, "collapsePanelIfNeeded failed", e)
            }
        }

        /**
         * 通过广播触发识别（通知栏按钮使用）
         * @return true=已触发, false=服务未连接
         */
        fun triggerScreenshot(): Boolean {
            val svc = instance ?: run {
                Log.w(TAG, "triggerScreenshot: AccessibilityService not available")
                return false
            }
            Log.i(TAG, "triggerScreenshot() called — dispatching to text extraction (notification source)")
            svc.doExtractFromScreenText("notification")
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

        // 配置服务信息（仅保留节点树内容获取相关 flag）
        try {
            val info = serviceInfo
            Log.d(TAG, "onServiceConnected: flags=${info.flags}, SDK=${Build.VERSION.SDK_INT}")
            serviceInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "Cannot configure service info", e)
        }

        // 注册广播接收器（接收通知栏按钮的触发）
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
     * @param from 触发来源："auto" / "notification" / "tile" / "manual" / "fab"
     * @return CodeRecord? 识别结果（异步场景返回 null，结果通过回调处理）
     */
    internal fun doExtractFromScreenText(from: String): CodeRecord? {
        // 防抖：短时间内的重复调用直接忽略
        val now = System.currentTimeMillis()
        if (now - lastExtractTime < EXTRACT_DEBOUNCE_MS) {
            Log.d(TAG, "Ignored extract request (debounce), from=$from")
            return null
        }
        lastExtractTime = now

        // 通知栏触发时：先模拟返回键关闭面板，等面板收起后再读取屏幕内容
        // 否则 rootInActiveWindow 读到的是通知栏自身的 UI（按钮文字等），不是后面屏幕上的 App
        //
        // ⚠️ Tile ("tile") 不在此分支处理：
        // TileService.onClick() 已通过 startActivityAndCollapse() 自行收起 QS 面板，
        // 并延迟 ~1000ms 后才调用 extractFromScreenText("tile")，此时 QS 面板已完全收起。
        // 如果在这里再执行 GLOBAL_ACTION_BACK 反而会多按一次返回键，可能意外退出当前 App。
        if (from == "notification") {
            AppLog.i(TAG, "[$from] 模拟返回键关闭通知栏面板，800ms后提取屏幕文字", from)
            val collapsed = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Log.d(TAG, "GLOBAL_ACTION_BACK result=$collapsed")
            handler.postDelayed({ performExtract(from) }, 800)
            return null
        }

        // FAB/手动触发：直接提取（通知栏未展开，屏幕内容可见）
        return performExtract(from)
    }

    /**
     * 折叠通知栏/快速设置面板（备用方案）
     *
     * 注意：此方法通过反射调用 StatusBarManager.collapsePanels()，
     * 在 Android 8.0+ (API 26+) 上对第三方 App 基本无效（系统限制）。
     * 主流程已改用 performGlobalAction(GLOBAL_ACTION_BACK)，此方法仅作兜底保留。
     */
    @Suppress("unused")
    private fun collapseNotificationPanel() {
        try {
            val statusBarService = getSystemService("statusbar")
            val method = statusBarService?.javaClass?.getMethod("collapsePanels")
            method?.invoke(statusBarService)
            Log.d(TAG, "collapseNotificationPanel: called (may be no-op on API 26+)")
        } catch (e: Exception) {
            Log.w(TAG, "collapseNotificationPanel failed (expected on API 26+)", e)
        }
    }

    /**
     * 实际执行节点树遍历 + 正则匹配
     *
     * 包含面板残留检测：如果提取到的文字看起来像是通知栏/QS 面板的内容
     * （包含"码速达"、"立即识别"、"设置"、"蓝牙"、"WiFi"等特征关键词），
     * 说明面板还没完全收起，自动再执行一次 GLOBAL_ACTION_BACK 并延迟重试。
     */
    private fun performExtract(from: String, retryCount: Int = 0): CodeRecord? {
        AppLog.i(TAG, "开始从屏幕提取文字 [from=$from, retry=$retryCount]", from)

        // 最大重试次数（防止无限循环）
        val MAX_RETRIES = 2

        try {
            val root = rootInActiveWindow
            if (root == null) {
                Log.w(TAG, "rootInActiveWindow is null — 无障碍服务可能未正确授权或被系统限制")
                AppLog.w(TAG, "无法获取当前屏幕节点树（rootInActiveWindow=null）", from)
                handler.post { islandManager.showNoResult() }
                return null
            }

            // 提取全屏文字
            val fullText = getAllTextFromNode(root)
            if (fullText.isBlank()) {
                Log.w(TAG, "Screen text is empty — 当前屏幕可能无可见文字内容或节点树为空")
                AppLog.w(TAG, "屏幕文字为空（节点树遍历结果为空字符串）", from)
                handler.post { islandManager.showNoResult() }
                return null
            }

            // ══ 面板残留检测 ══
            // 如果提取到的文字包含通知栏/QS 面板的特征文字，说明面板还未完全收起。
            // 此时应该再按一次返回键，等一会儿重新提取。
            if (retryCount < MAX_RETRIES && looksLikePanelText(fullText)) {
                AppLog.w(TAG, "⚠️ 检测到面板残留文字(重试${retryCount + 1}/${MAX_RETRIES}): ${fullText.take(100)}", from)
                // 再按一次返回键关闭残留面板
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                // 递归重试，延迟更长一点
                handler.postDelayed({ performExtract(from, retryCount + 1) }, 800)
                return null
            }

            AppLog.d(TAG, "提取到屏幕文字(${fullText.length}字符): ${fullText.take(200)}")

            // 用正则解析取件码
            val record = extractor.extractFromText(fullText)
            if (record != null) {
                AppLog.i(TAG, "✅ 从屏幕文字识别到取件码: ${record.code} (${record.codeType})", from)
                onCodeFound(record)
                return record
            } else {
                AppLog.i(TAG, "❌ 屏幕文字中未找到取件码", from)
                handler.post { islandManager.showNoResult() }
                return null
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in extractFromScreenText", e)
            AppLog.e(TAG, "安全异常: ${e.message}", throwable = e, triggerFrom = from)
            handler.post { islandManager.showError() }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error in extractFromScreenText", e)
            AppLog.e(TAG, "提取异常: ${e.javaClass.simpleName}: ${e.message}", throwable = e, triggerFrom = from)
            handler.post { islandManager.showError() }
            return null
        }
    }

    /**
     * 检测提取到的文字是否像是通知栏/QS 面板的内容（而非正常 App 屏幕文字）
     *
     * 原理：通知栏和 QS 面板的 UI 节点树包含一些特征关键词，
     * 正常 App 的屏幕上不会同时出现这些词。
     * 只要匹配到任意一个就认为"看起来像面板"。
     */
    private fun looksLikePanelText(text: String): Boolean {
        val panelKeywords = arrayOf(
            // === 码速达自身通知栏文字（最精确的标识）===
            "码速达",          // 通知栏标题
            "已就绪",          // 通知栏内容
            "立即识别",        // 通知栏按钮
            "停止服务",        // 通知栏按钮
            // === QS 面板独有系统文字（正常App不会出现的）===
            "蓝牙",           // QS磁贴名称
            "移动数据",       // QS磁贴名称（"数据"单独太短不收录）
            "飞行模式",       // QS磁贴名称
            "手电筒",         //QS磁贴名称
            "自动旋转",       // QS磁贴名称
            // === 小米/澎湃OS 控制中心特有 ===
            "控制中心",
            "省电与电池",
        )
        return panelKeywords.any { it in text }
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
    //  结果处理
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun onCodeFound(record: CodeRecord) {
        scope.launch { repository.insert(record) }
        islandManager.showCode(record)
        sendBroadcast(Intent(ACTION_CODE_FOUND).setPackage(packageName))
    }
}
