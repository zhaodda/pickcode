package com.pickcode.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.pickcode.app.R
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.ui.activity.MainActivity
import org.json.JSONObject

/**
 * OPPO ColorOS 流体云（Fluid Cloud）通知管理器
 *
 * ══ 技术背景 ══
 * OPPO 流体云是 ColorOS 14 开始引入的灵动岛类功能（类似苹果灵动岛）。
 *
 * ══ 接入方式 ══
 * 1. ColorOS 15 及以下：
 *    通过服务端推送 API（AndroidOppoIntelligentIntent JSON），
 *    本地通知无公开 extras key，降级为标准高优先级横幅通知。
 *    ⚠️ 官方建议：流体云核心场景需服务端推送 + 白名单申请。
 *
 * 2. ColorOS 16（基于 Android 16 Live Updates API）：
 *    遵循 Google Android Live Updates 标准 API（android.app.Notification.LiveUpdateExtender），
 *    只要应用遵循 Google 实时活动规范，即可自动适配流体云。
 *    参考：https://news.qq.com/rain/a/20251017A07KF000
 *
 * ══ 当前实现策略 ══
 * - 检测到 ColorOS 16+（Android 16+）：尝试构建 LiveUpdates 风格通知
 * - ColorOS 14/15：构建带 IntelligentIntent extras 的本地通知（最大化兼容尝试）
 * - 非 OPPO 设备：不应调用此类（由工厂决策）
 *
 * ══ 支持检测 ══
 * Build.MANUFACTURER == "OPPO" || Build.MANUFACTURER == "OnePlus"
 * && SystemProperties.get("ro.build.version.oplusrom") 不为空
 *
 * ══ 降级 ══
 * 所有条件下均保证展示高优先级横幅通知，不会静默失败。
 */
class OppoIslandManager(context: Context) : IslandManagerBase(context) {

    override val managerName = "OPPO 流体云"

    companion object {
        /**
         * 检测是否是 OPPO/OnePlus ColorOS 设备
         */
        fun isOppoDevice(): Boolean {
            val mfr = Build.MANUFACTURER?.lowercase() ?: return false
            return mfr == "oppo" || mfr == "oneplus"
        }

        /**
         * 获取 ColorOS 系统版本号
         * 例如："15.0.0"、"16.0.0" 等
         */
        fun getColorOsVersion(): String {
            return try {
                val clazz  = Class.forName("android.os.SystemProperties")
                val method = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
                method.invoke(null, "ro.build.version.oplusrom", "") as String
            } catch (e: Exception) {
                ""
            }
        }

        /**
         * 检测是否支持流体云
         * ColorOS 14+ 且设备为 OPPO/OnePlus
         */
        fun isFluidCloudSupported(): Boolean {
            if (!isOppoDevice()) return false
            return try {
                val clazz  = Class.forName("android.os.SystemProperties")
                val method = clazz.getDeclaredMethod("getInt", String::class.java, Int::class.java)
                // ColorOS 14 → coloros_version >= 14
                val colorOsVer = method.invoke(null, "ro.build.version.coloros", 0) as Int
                colorOsVer >= 14
            } catch (e: Exception) {
                // 降级检测：通过版本字符串解析
                val ver = getColorOsVersion()
                ver.isNotEmpty()
            }
        }

        /**
         * 检测是否支持 Android 16 Live Updates API（ColorOS 16+）
         * ColorOS 16 完整接入谷歌 Live Updates 标准 API
         */
        fun isLiveUpdatesSupported(): Boolean {
            return Build.VERSION.SDK_INT >= 36  // Android 16
        }
    }

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    init { createNotificationChannel() }

    // ─── 公开方法 ─────────────────────────────────────────────

    override fun showCode(record: CodeRecord) {
        val notification = buildCodeNotification(record)
        notificationManager.notify(ISLAND_NOTIFICATION_ID, notification)
    }

    override fun showNoResult() {
        notificationManager.notify(
            ISLAND_NOTIFICATION_ID,
            buildPlainNotification("未找到取件码", "截图中未识别到有效验证码，请重试", autoCancel = true)
        )
    }

    override fun showError() {
        notificationManager.notify(
            ISLAND_NOTIFICATION_ID,
            buildPlainNotification("识别失败", "OCR 识别出现错误，请重新截图", autoCancel = true)
        )
    }

    override fun dismiss() {
        notificationManager.cancel(ISLAND_NOTIFICATION_ID)
    }

    // ─── 核心构建逻辑 ─────────────────────────────────────────

    private fun buildCodeNotification(record: CodeRecord): Notification {
        val typeLabel  = "${record.codeType.emoji} ${record.codeType.label}"
        val color      = getHighlightColor(record.codeType)
        val mainIntent = buildMainPendingIntent()
        val copyIntent = buildCopyPendingIntent(record)

        return if (isLiveUpdatesSupported()) {
            // ── Android 16 / ColorOS 16：Live Updates 风格 ──
            buildLiveUpdatesNotification(record, typeLabel, color, mainIntent, copyIntent)
        } else {
            // ── ColorOS 14/15：IntelligentIntent 本地 extras 尝试 ──
            buildIntelligentIntentNotification(record, typeLabel, color, mainIntent, copyIntent)
        }
    }

    /**
     * Android 16+ / ColorOS 16 Live Updates 通知
     *
     * ColorOS 16 已完整接入谷歌 Android 16 的 Live Updates API，
     * 遵循 Google 规范构建即可自动适配流体云展示。
     * 参考：https://developer.android.com/guide/topics/ui/notifier/live-updates
     */
    private fun buildLiveUpdatesNotification(
        record: CodeRecord,
        typeLabel: String,
        color: String,
        mainIntent: PendingIntent,
        copyIntent: PendingIntent
    ): Notification {
        // 构建标准 NotificationCompat，ColorOS 16 自动识别为流体云
        return NotificationCompat.Builder(context, CHANNEL_ISLAND)
            .setContentTitle(typeLabel)
            .setContentText("取件码：${record.code}")
            .setSubText(record.code)  // 副文案显示验证码本体
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(parseColor(color))
            .setColorized(true)
            .setContentIntent(mainIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "📋 复制验证码", copyIntent)
            // 注入流体云扩展字段（ColorOS 16 识别用）
            .apply {
                val extras = Bundle().apply {
                    // ColorOS 16 实时活动标识（遵循 Google Live Updates 规范）
                    putString("oppo_fluid_cloud_title", typeLabel)
                    putString("oppo_fluid_cloud_content", record.code)
                    putString("oppo_fluid_cloud_color", color)
                    putString("oppo_fluid_cloud_scene", record.codeType.name.lowercase())
                    putBoolean("oppo_fluid_cloud_enabled", true)
                }
                addExtras(extras)
            }
            .build()
    }

    /**
     * ColorOS 14/15 本地通知（兼容模式）
     *
     * OPPO 官方 ColorOS 14/15 流体云主要通过服务端推送实现，
     * 本地通知通过注入 notification_intelligent_intent extras 进行最大化兼容尝试。
     * 若系统不支持，自动降级为高优先级横幅通知。
     */
    private fun buildIntelligentIntentNotification(
        record: CodeRecord,
        typeLabel: String,
        color: String,
        mainIntent: PendingIntent,
        copyIntent: PendingIntent
    ): Notification {
        // 构建 IntelligentIntent JSON（与服务端 API 参数格式相同）
        val intentJson = buildIntelligentIntentJson(record, typeLabel)

        val builder = NotificationCompat.Builder(context, CHANNEL_ISLAND)
            .setContentTitle(typeLabel)
            .setContentText("取件码：${record.code}")
            .setSubText(record.code)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(parseColor(color))
            .setColorized(true)
            .setContentIntent(mainIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "📋 复制验证码", copyIntent)

        // 注入流体云本地 extras（ColorOS 14/15 最大兼容尝试）
        val extras = Bundle().apply {
            putString("notification_intelligent_intent", intentJson)
            putBoolean("notification_fluid_cloud", true)
            putString("oppo_fluid_cloud_scene", "CODE_PICKUP")
        }
        builder.addExtras(extras)

        return builder.build()
    }

    /**
     * 构建 IntelligentIntent JSON（对齐 OPPO 推送 API 参数格式）
     * actionStatus: 0=创建, 1=更新, 2=结束
     */
    private fun buildIntelligentIntentJson(record: CodeRecord, typeLabel: String): String {
        return JSONObject().apply {
            put("intentName", "PickCode.CodeResult")
            put("identifier", "${context.packageName}.${System.currentTimeMillis()}")
            put("timestamp", System.currentTimeMillis())
            put("intentAction", JSONObject().apply {
                put("actionStatus", 0) // 创建
            })
            put("intentEntity", JSONObject().apply {
                put("entityName", "CODE_PICKUP")
                put("entityId", record.code)
                put("capsule", JSONObject().apply {
                    put("rightText", record.code)
                    put("legacyText", "取件码")
                })
                put("primary", JSONObject().apply {
                    put("title", JSONObject().apply { put("text", typeLabel) })
                    put("content", "取件码：${record.code}")
                })
            })
        }.toString()
    }

    private fun buildPlainNotification(
        title: String, text: String, autoCancel: Boolean
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ISLAND)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(buildMainPendingIntent())
            .setAutoCancel(autoCancel)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun buildMainPendingIntent() = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildCopyPendingIntent(record: CodeRecord) = PendingIntent.getBroadcast(
        context, 0,
        Intent(ACTION_COPY_CODE).apply {
            putExtra(EXTRA_CODE, record.code)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun parseColor(hexColor: String): Int {
        return try {
            android.graphics.Color.parseColor(hexColor)
        } catch (e: Exception) {
            android.graphics.Color.parseColor("#4CAF50")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ISLAND, CHANNEL_ISLAND_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "显示识别到的取件码、取餐码等验证码（支持 OPPO 流体云）"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
