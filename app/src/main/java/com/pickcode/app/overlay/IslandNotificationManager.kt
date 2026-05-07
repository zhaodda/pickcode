package com.pickcode.app.overlay

import android.content.Context
import android.util.Log
import com.pickcode.app.data.model.CodeRecord

/**
 * 超级岛通知管理器（统一入口）
 *
 * 专注小米澎湃OS超级岛，自动降级为标准通知横幅。
 *
 * ┌─────────────────────────┬──────────────────────────────────┐
 * │ 设备条件                │ 实现方式                         │
 * ├─────────────────────────┼──────────────────────────────────┤
 * │ 澎湃OS3+ (protocol>=3) │ MiuiIslandManager 超级岛胶囊     │
 * │ 澎湃OS1/2 (protocol1-2)│ MiuiIslandManager 焦点通知横幅   │
 * │ 小米设备(无焦点通知)    │ FallbackIslandManager 标准横幅   │
 * │ 非小米设备              │ FallbackIslandManager 标准横幅   │
 * └─────────────────────────┴──────────────────────────────────┘
 *
 * ══ 使用方式 ══
 * ```kotlin
 * val islandManager = IslandNotificationManager(context)
 * islandManager.showCode(record)
 * islandManager.dismiss()
 * ```
 *
 * ══ 权限要求 ══
 * - 需要 POST_NOTIFICATIONS（Android 13+ 通知权限）
 * - 小米设备推荐开启"焦点通知"权限以获得最佳体验
 */
class IslandNotificationManager(context: Context) {

    companion object {
        const val ISLAND_NOTIFICATION_ID = IslandManagerBase.ISLAND_NOTIFICATION_ID
        const val CHANNEL_ISLAND         = IslandManagerBase.CHANNEL_ISLAND
        const val ACTION_COPY_CODE       = IslandManagerBase.ACTION_COPY_CODE
        const val EXTRA_CODE             = IslandManagerBase.EXTRA_CODE

        fun getFocusProtocolVersion(context: Context) =
            MiuiIslandManager.getFocusProtocolVersion(context)

        fun isIslandSupported(context: Context) =
            MiuiIslandManager.isIslandSupported(context)

        fun isSupportIslandProperty() =
            MiuiIslandManager.isSupportIslandProperty()

        fun hasFocusPermission(context: Context) =
            MiuiIslandManager.hasFocusPermission(context)

        /** 获取当前设备的超级岛类型描述文本 */
        fun getIslandTypeDescription(context: Context): String =
            IslandManagerFactory.getIslandTypeDescription(context)
    }

    private val delegate: IslandManagerBase = IslandManagerFactory.create(context).also {
        Log.i("IslandNotificationManager", "初始化完成: delegate=${it.managerName}")
    }

    val managerName: String get() = delegate.managerName

    /**
     * 展示识别到的验证码到超级岛/通知栏
     */
    fun showCode(record: CodeRecord) {
        Log.i("IslandNotificationManager", "showCode → ${delegate.managerName}")
        delegate.showCode(record)
    }

    /** 展示"未找到验证码"提示 */
    fun showNoResult() = delegate.showNoResult()

    /** 展示识别错误提示 */
    fun showError() = delegate.showError()

    /** 取消通知 */
    fun dismiss() = delegate.dismiss()
}
