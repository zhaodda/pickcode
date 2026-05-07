package com.pickcode.app.overlay

import android.content.Context
import com.pickcode.app.data.model.CodeRecord

/**
 * 灵动岛通知管理器（统一入口 / Facade）
 *
 * 这是对外暴露的统一接口，内部自动通过 [IslandManagerFactory]
 * 根据设备品牌/系统版本选择最优的实现类：
 *
 * ┌─────────────────────────┬──────────────────────────────────────────┐
 * │ 设备                    │ 实现                                     │
 * ├─────────────────────────┼──────────────────────────────────────────┤
 * │ 小米澎湃OS3+             │ MiuiIslandManager（超级岛胶囊动效）       │
 * │ 小米澎湃OS1/2            │ MiuiIslandManager（焦点通知横幅）         │
 * │ OPPO ColorOS 16+        │ OppoIslandManager（流体云/Live Updates）  │
 * │ OPPO ColorOS 14/15      │ OppoIslandManager（流体云兼容模式）       │
 * │ vivo OriginOS 3+        │ VivoIslandManager（原子岛胶囊）           │
 * │ 其他 Android 设备        │ FallbackIslandManager（标准通知横幅）    │
 * └─────────────────────────┴──────────────────────────────────────────┘
 *
 * ══ 使用方式（调用方无需修改）══
 * ```kotlin
 * val islandManager = IslandNotificationManager(context)
 * islandManager.showCode(record)   // 自动选择最优展示方式
 * islandManager.dismiss()
 * ```
 *
 * ══ 权限要求 ══
 * - 无需 SYSTEM_ALERT_WINDOW（悬浮窗权限）
 * - 需要 POST_NOTIFICATIONS（Android 13+ 通知权限）
 * - 小米设备额外推荐：在系统设置中开启"焦点通知"权限
 * - vivo 设备需通过白名单申请才能触发原子岛
 */
class IslandNotificationManager(context: Context) {

    companion object {
        // 公开常量（兼容调用方代码，委托给 IslandManagerBase）
        const val ISLAND_NOTIFICATION_ID = IslandManagerBase.ISLAND_NOTIFICATION_ID
        const val CHANNEL_ISLAND         = IslandManagerBase.CHANNEL_ISLAND
        const val ACTION_COPY_CODE       = IslandManagerBase.ACTION_COPY_CODE
        const val EXTRA_CODE             = IslandManagerBase.EXTRA_CODE

        // ── 小米专属静态方法（兼容调用方）──

        fun getFocusProtocolVersion(context: Context) =
            MiuiIslandManager.getFocusProtocolVersion(context)

        fun isIslandSupported(context: Context) =
            MiuiIslandManager.isIslandSupported(context)

        fun isSupportIslandProperty() =
            MiuiIslandManager.isSupportIslandProperty()

        fun hasFocusPermission(context: Context) =
            MiuiIslandManager.hasFocusPermission(context)

        // ── 多厂商静态检测方法 ──

        /** 获取当前设备支持的灵动岛类型描述文本 */
        fun getIslandTypeDescription(context: Context): String =
            IslandManagerFactory.getIslandTypeDescription(context)
    }

    /** 实际执行通知的厂商实现类（由工厂自动选择） */
    private val delegate: IslandManagerBase = IslandManagerFactory.create(context)

    /** 当前选择的实现名称（用于日志/调试） */
    val managerName: String get() = delegate.managerName

    // ─── 公开方法（委托给具体实现）─────────────────────────────

    /**
     * 展示识别到的验证码
     * 自动根据设备能力选择：超级岛 / 流体云 / 原子岛 / 普通通知横幅
     */
    fun showCode(record: CodeRecord) = delegate.showCode(record)

    /** 展示"未找到验证码"提示 */
    fun showNoResult() = delegate.showNoResult()

    /** 展示识别错误提示 */
    fun showError() = delegate.showError()

    /** 取消通知 */
    fun dismiss() = delegate.dismiss()
}

// ── 扩展函数（向后兼容保留）──

/**
 * 为已构建的 Notification 注入小米超级岛参数
 * @deprecated 请直接使用 IslandNotificationManager，内部已自动处理
 */
@Deprecated("请直接使用 IslandNotificationManager，内部已自动处理注入逻辑")
fun android.app.Notification.applyIslandParams(focusParamJson: String): android.app.Notification {
    extras.putString("miui.focus.param", focusParamJson)
    return this
}
