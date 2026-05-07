package com.pickcode.app.overlay

import android.content.Context
import com.pickcode.app.data.model.CodeRecord

/**
 * 各厂商"灵动岛"通知管理器的统一抽象基类
 *
 * 实现类：
 * - [MiuiIslandManager]  小米澎湃OS   超级岛（miui.focus.param）
 * - [OppoIslandManager]  OPPO ColorOS 流体云（notification_intelligent_intent）
 * - [VivoIslandManager]  vivo OriginOS 原子岛（notification_type=atom_notification）
 * - [FallbackIslandManager] 兜底：其他 Android 设备（标准高优先级通知横幅）
 *
 * ╔══════════════════════════════════════════════╗
 * ║  使用方：统一通过 IslandNotificationManager   ║
 * ║  调用，无需关心具体厂商实现                    ║
 * ╚══════════════════════════════════════════════╝
 */
abstract class IslandManagerBase(protected val context: Context) {

    companion object {
        const val ISLAND_NOTIFICATION_ID = 2001
        const val CHANNEL_ISLAND         = "pickcode_island"
        const val CHANNEL_ISLAND_NAME    = "取件码提醒"

        // 复制验证码广播 Action（全厂商公用）
        const val ACTION_COPY_CODE = "com.pickcode.ACTION_COPY_CODE"
        const val EXTRA_CODE       = "extra_code"
    }

    /**
     * 展示识别到的验证码
     * 各实现类负责选择最优展示形式（胶囊/横幅等）
     */
    abstract fun showCode(record: CodeRecord)

    /**
     * 展示"未找到验证码"提示
     */
    abstract fun showNoResult()

    /**
     * 展示识别错误提示
     */
    abstract fun showError()

    /**
     * 取消/关闭通知
     */
    abstract fun dismiss()

    /**
     * 当前实现类的名称（用于日志、调试）
     */
    abstract val managerName: String

    /**
     * 验证码类型 → 颜色映射（全厂商通用）
     */
    protected fun getHighlightColor(codeType: com.pickcode.app.data.model.CodeType): String {
        return when (codeType) {
            com.pickcode.app.data.model.CodeType.EXPRESS  -> "#534AB7"  // 快递：品牌紫
            com.pickcode.app.data.model.CodeType.FOOD     -> "#FF6B35"  // 餐饮：橙色
            com.pickcode.app.data.model.CodeType.PARKING  -> "#00BCD4"  // 停车：青色
            com.pickcode.app.data.model.CodeType.OTHER    -> "#4CAF50"  // 其他：绿色
        }
    }
}
