package com.pickcode.app.overlay

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * 超级岛管理器工厂
 *
 * 根据设备品牌和系统版本选择通知实现：
 * 1. 小米澎湃OS（focusProtocolVersion >= 1） → MiuiIslandManager
 * 2. 其他所有设备                         → FallbackIslandManager（标准通知横幅）
 */
object IslandManagerFactory {

    private const val TAG = "IslandManagerFactory"

    /**
     * 创建当前设备最优的 IslandManagerBase 实现
     */
    fun create(context: Context): IslandManagerBase {
        val manager = when {
            isMiuiDevice() -> {
                val version = MiuiIslandManager.getFocusProtocolVersion(context)
                if (version >= 1) {
                    Log.i(TAG, "小米澎湃OS超级岛 (focusProtocolVersion=$version)")
                    MiuiIslandManager(context)
                } else {
                    Log.i(TAG, "小米设备但不支持焦点通知，降级为标准通知")
                    FallbackIslandManager(context)
                }
            }
            else -> {
                Log.i(TAG, "非小米设备，使用标准通知横幅")
                FallbackIslandManager(context)
            }
        }
        Log.i(TAG, "已选择: ${manager.managerName}")
        return manager
    }

    /**
     * 检测是否为小米/Redmi/Poco 设备（澎湃OS）
     */
    private fun isMiuiDevice(): Boolean {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return false
        if (mfr != "xiaomi" && mfr != "redmi") return false
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
            val miuiVer = method.invoke(null, "ro.miui.ui.version.name", "") as String
            miuiVer.isNotEmpty()
        } catch (e: Exception) {
            // 进一步尝试检测 HyperOS
            try {
                val clazz = Class.forName("android.os.SystemProperties")
                val method = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
                val hyperOs = method.invoke(null, "ro.mi.os.version.name", "") as String
                hyperOs.isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 获取当前设备的超级岛类型描述文本（用于 UI 展示）
     */
    fun getIslandTypeDescription(context: Context): String {
        return if (isMiuiDevice()) {
            when (val v = MiuiIslandManager.getFocusProtocolVersion(context)) {
                0    -> "小米设备（不支持超级岛）"
                1, 2 -> "小米焦点通知（澎湃OS1/2）"
                else -> "小米超级岛（澎湃OS3+，版本=$v）"
            }
        } else {
            "标准通知横幅"
        }
    }
}
