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
 * vivo OriginOS 原子岛（Atom Island）通知管理器
 *
 * ══ 技术背景 ══
 * vivo 原子岛是 OriginOS 5（2024年9月）从"原子通知"升级而来的灵动岛类功能。
 * 官方开发者文档：https://dev.vivo.com.cn/documentCenter/doc/894
 *                https://dev.vivo.com.cn/documentCenter/doc/896
 *
 * ══ 接入要求 ══
 * 1. 需在 vivo 开放平台提交白名单申请（邮件至 oosyztz@vivo.com）
 * 2. 审核通过（7-10个工作日）后，应用才能触发原子岛展示
 * 3. 设备要求：vivo X/S/iQOO 系列，OriginOS 3.0+ / Funtouch OS 13+
 *
 * ══ 接入方式 ══
 * 客户端本地通知：通过 Notification extras Bundle 注入特定字段，
 * 在白名单应用 + 支持设备上自动展示原子岛；否则降级为标准通知。
 *
 * ══ 核心 extras 字段 ══
 * "notification_type"       = "atom_notification"  （原子通知类型标识）
 * "vivo_atom_json"          = JSON字符串            （原子岛完整数据）
 * "atom_island_type"        = 1                    （展示类型：1=展开,2=胶囊）
 * "atom_event_id"           = 唯一事件ID            （用于更新/取消）
 *
 * ══ 降级策略 ══
 * - 白名单内 + OriginOS 3+：原子岛展示
 * - 其他 vivo 设备：标准高优先级横幅通知
 * - 非 vivo 设备：不应调用此类（由工厂决策）
 *
 * ══ 注意事项 ══
 * ⚠️ extras 字段基于官方公开文档整理，具体 key 名以最新 vivo 官方文档为准。
 * ⚠️ 未获白名单时，extras 字段被系统忽略，通知正常展示为标准形式。
 */
class VivoIslandManager(context: Context) : IslandManagerBase(context) {

    override val managerName = "vivo 原子岛"

    companion object {
        /**
         * 检测是否是 vivo 设备
         */
        fun isVivoDevice(): Boolean {
            return Build.MANUFACTURER?.lowercase() == "vivo"
        }

        /**
         * 检测是否支持原子岛（OriginOS 3.0+ / Funtouch OS 13+）
         *
         * 检测方式：
         * 1. SystemProperties: ro.vivo.os.build.display.id 不为空 → vivo 设备
         * 2. SystemProperties: ro.vivo.os.version >= "3.0" → OriginOS 3+
         */
        fun isAtomIslandSupported(): Boolean {
            if (!isVivoDevice()) return false
            return try {
                val clazz  = Class.forName("android.os.SystemProperties")
                val method = clazz.getDeclaredMethod("get", String::class.java, String::class.java)

                // 检测 OriginOS 版本（方案1：版本号字符串）
                val originOsVer = method.invoke(null, "ro.vivo.os.version", "") as String
                if (originOsVer.isNotEmpty()) {
                    val major = originOsVer.split(".").firstOrNull()?.toIntOrNull() ?: 0
                    return major >= 3
                }

                // 检测 OriginOS 版本（方案2：display id 含 OriginOS）
                val displayId = method.invoke(null, "ro.vivo.os.build.display.id", "") as String
                displayId.contains("OriginOS", ignoreCase = true)
            } catch (e: Exception) {
                false
            }
        }

        /**
         * 检测原子岛功能是否可用（系统特性）
         */
        fun hasAtomIslandFeature(context: Context): Boolean {
            return context.packageManager.hasSystemFeature("com.vivo.atom.island")
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

        // 注入 vivo 原子岛 extras
        builder.addExtras(buildAtomIslandExtras(record, typeLabel, color))

        return builder.build()
    }

    /**
     * 构建 vivo 原子岛 extras Bundle
     *
     * 核心字段（来自 vivo 开放平台官方文档）：
     * - notification_type：固定值 "atom_notification"，标识这是原子通知
     * - vivo_atom_json：原子岛完整数据 JSON
     * - atom_island_type：展示形态（1=展开态，2=胶囊态）
     * - atom_event_id：业务事件唯一ID（更新/取消通知时需保持一致）
     * - atom_scene：业务场景标识
     * - atom_bg_color：原子岛背景色
     */
    private fun buildAtomIslandExtras(
        record: CodeRecord, typeLabel: String, color: String
    ): Bundle {
        val eventId  = "${context.packageName}.${record.codeType.name}.${record.code}"
        val atomJson = buildAtomIslandJson(record, typeLabel, color, eventId)

        return Bundle().apply {
            // ── 类型标识（核心字段，决定是否触发原子岛）──
            putString("notification_type", "atom_notification")

            // ── 原子岛完整数据 ──
            putString("vivo_atom_json", atomJson)

            // ── 快速访问字段（vivo 系统读取优化）──
            putInt("atom_island_type", 1)           // 1=展开态
            putString("atom_event_id", eventId)     // 事件唯一ID（用于更新）
            putString("atom_scene", record.codeType.name.lowercase())
            putString("atom_title", typeLabel)
            putString("atom_content", record.code)
            putString("atom_sub_content", "点击复制验证码")
            putString("atom_bg_color", color)
            putLong("atom_start_time", System.currentTimeMillis())
            putLong("atom_end_time",   System.currentTimeMillis() + 30_000L) // 30秒后到期

            // ── 兼容字段（OriginOS 3 / Funtouch 格式）──
            putString("atom_island_title", typeLabel)
            putString("atom_island_content", record.code)
            putString("atom_island_sub_content", "点击复制验证码")
            putString("atom_island_color", color)
        }
    }

    /**
     * 构建原子岛完整 JSON 数据
     *
     * 对应 vivo 开放平台原子通知 API 的 atom_extra_data 字段结构。
     */
    private fun buildAtomIslandJson(
        record: CodeRecord, typeLabel: String, color: String, eventId: String
    ): String {
        return JSONObject().apply {
            put("version", "1.0")
            put("event_id", eventId)
            put("scene", record.codeType.name.lowercase())
            put("island_type", 1)

            put("display", JSONObject().apply {
                put("title", typeLabel)
                put("content", record.code)
                put("sub_content", "点击复制验证码")
                put("bg_color", color)
                put("text_color", "#FFFFFF")
            })

            put("capsule", JSONObject().apply {
                put("text", record.code)
                put("sub_text", record.codeType.emoji)
                put("bg_color", color)
            })

            put("time", JSONObject().apply {
                put("start", System.currentTimeMillis())
                put("end",   System.currentTimeMillis() + 30_000L)
            })

            put("extra", JSONObject().apply {
                put("package_name", context.packageName)
                put("code_type", record.codeType.name)
                put("code_value", record.code)
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
                description = "显示识别到的取件码、取餐码等验证码（支持 vivo 原子岛）"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
