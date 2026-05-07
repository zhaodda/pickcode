package com.pickcode.app.overlay

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * 灵动岛管理器工厂
 *
 * 根据设备品牌和系统版本自动选择最优的通知实现类。
 *
 * ══ 选择优先级 ══
 * 1. 小米澎湃OS（focusProtocolVersion >= 1） → MiuiIslandManager
 * 2. OPPO/OnePlus ColorOS（14+）            → OppoIslandManager
 * 3. vivo OriginOS（3.0+）                  → VivoIslandManager
 * 4. 其他 Android 设备                      → FallbackIslandManager
 *
 * ══ 使用方式 ══
 * ```kotlin
 * val islandManager = IslandManagerFactory.create(context)
 * islandManager.showCode(record)
 * ```
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
                    Log.i(TAG, "🏝️ 小米澎湃OS 超级岛 (focusProtocolVersion=$version)")
                    MiuiIslandManager(context)
                } else {
                    Log.i(TAG, "📲 小米设备但不支持焦点通知，降级为标准通知")
                    FallbackIslandManager(context)
                }
            }
            OppoIslandManager.isOppoDevice() -> {
                val supported = OppoIslandManager.isFluidCloudSupported()
                Log.i(TAG, "🌊 OPPO ColorOS 流体云 (supported=$supported, LiveUpdates=${OppoIslandManager.isLiveUpdatesSupported()})")
                OppoIslandManager(context)
            }
            VivoIslandManager.isVivoDevice() -> {
                val supported = VivoIslandManager.isAtomIslandSupported()
                Log.i(TAG, "⚛️ vivo 原子岛 (supported=$supported)")
                VivoIslandManager(context)
            }
            else -> {
                Log.i(TAG, "📢 通用设备，使用标准通知横幅")
                FallbackIslandManager(context)
            }
        }
        Log.i(TAG, "已选择：${manager.managerName}")
        return manager
    }

    /**
     * 检测当前设备是否为小米/Redmi/Poco 设备（澎湃OS）
     */
    private fun isMiuiDevice(): Boolean {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return false
        if (mfr != "xiaomi" && mfr != "redmi") return false
        return try {
            val clazz  = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
            val miuiVer = method.invoke(null, "ro.miui.ui.version.name", "") as String
            miuiVer.isNotEmpty()
        } catch (e: Exception) {
            // 进一步尝试：检测 HyperOS
            try {
                val clazz  = Class.forName("android.os.SystemProperties")
                val method = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
                val hyperOs = method.invoke(null, "ro.mi.os.version.name", "") as String
                hyperOs.isNotEmpty()
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * 获取当前设备灵动岛类型描述（用于 UI 展示）
     */
    fun getIslandTypeDescription(context: Context): String {
        return when {
            isMiuiDevice() -> {
                when (val v = MiuiIslandManager.getFocusProtocolVersion(context)) {
                    0    -> "小米设备（不支持超级岛）"
                    1, 2 -> "小米焦点通知（澎湃OS1/2）"
                    else -> "小米超级岛（澎湃OS3，版本=$v）"
                }
            }
            OppoIslandManager.isOppoDevice() -> {
                if (OppoIslandManager.isLiveUpdatesSupported())
                    "OPPO 流体云（ColorOS 16 + Live Updates）"
                else
                    "OPPO 流体云（ColorOS 14/15）"
            }
            VivoIslandManager.isVivoDevice() -> {
                if (VivoIslandManager.isAtomIslandSupported())
                    "vivo 原子岛（OriginOS 3+）"
                else
                    "vivo 设备（不支持原子岛）"
            }
            else -> "标准通知横幅（其他 Android 设备）"
        }
    }
}
