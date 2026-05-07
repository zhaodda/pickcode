package com.pickcode.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.pickcode.app.R
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.ui.activity.MainActivity
import org.json.JSONObject

/**
 * 小米澎湃OS 超级岛（Super Island）通知管理器
 *
 * ══ 接入方式 ══
 * 客户端实现：标准 Android Notification + miui.focus.param 扩展参数注入
 * 官方文档：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131
 *
 * ══ 工作原理 ══
 * 1. 构建标准 Android Notification（通过 Notification.Builder）
 * 2. 调用 builder.build() 获得 Notification 对象
 * 3. 向 notification.extras 注入 "miui.focus.param" JSON 字段
 *    ⚠️ 必须在 build() 之后执行（官方强制要求）
 * 4. 在支持超级岛的设备（澎湃OS3, focusProtocolVersion==3）上以岛形式展示
 * 5. 不支持的设备自动降级为普通通知
 *
 * ══ 降级策略 ══
 * - 澎湃OS3（focusProtocolVersion >= 3） → 超级岛展示（胶囊+动效）
 * - 澎湃OS1/OS2（focusProtocolVersion 1-2）→ 焦点通知横幅
 * - 其他（focusProtocolVersion == 0）    → 普通高优先级通知横幅
 *
 * ══ 支持检测 ══
 * Settings.System.getInt(cr, "notification_focus_protocol", 0) >= 3
 */
class MiuiIslandManager(context: Context) : IslandManagerBase(context) {

    override val managerName = "小米超级岛"

    companion object {
        // ── 小米超级岛 Bundle Key（来自官方文档）──
        private const val KEY_FOCUS_PARAM   = "miui.focus.param"
        private const val KEY_FOCUS_PICS    = "miui.focus.pics"
        private const val KEY_FOCUS_ACTIONS = "miui.focus.actions"
        private const val KEY_PIC_ICON      = "miui.focus.pic_icon"
        private const val KEY_PIC_TICKER    = "miui.focus.pic_ticker"
        private const val KEY_ACTION_COPY   = "miui.focus.action_copy"

        /**
         * 检测焦点通知协议版本
         * 返回值：0=不支持, 1=OS1焦点通知, 2=OS2焦点通知, 3=OS3超级岛
         */
        fun getFocusProtocolVersion(context: Context): Int {
            return try {
                Settings.System.getInt(
                    context.contentResolver,
                    "notification_focus_protocol",
                    0
                )
            } catch (e: Exception) {
                0
            }
        }

        /** 是否支持超级岛（focusProtocolVersion >= 3） */
        fun isIslandSupported(context: Context) = getFocusProtocolVersion(context) >= 3

        /** 通过系统属性检测超级岛能力 */
        fun isSupportIslandProperty(): Boolean {
            return try {
                val clazz  = Class.forName("android.os.SystemProperties")
                val method = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.java)
                method.invoke(null, "persist.sys.feature.island", false) as Boolean
            } catch (e: Exception) {
                false
            }
        }

        /** 检测焦点通知权限（⚠️ 耗时，勿在主线程调用） */
        fun hasFocusPermission(context: Context): Boolean {
            return try {
                val uri    = android.net.Uri.parse("content://miui.statusbar.notification.public")
                val extras = Bundle().apply { putString("package", context.packageName) }
                val bundle = context.contentResolver.call(uri, "canShowFocus", null, extras)
                bundle?.getBoolean("canShowFocus", false) ?: false
            } catch (e: Exception) {
                false
            }
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
        val typeLabel = "${record.codeType.emoji} ${record.codeType.label}"

        val mainIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val copyIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_COPY_CODE).apply {
                putExtra(EXTRA_CODE, record.code)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND) // 官方要求
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(context, CHANNEL_ISLAND)
        else
            @Suppress("DEPRECATION") Notification.Builder(context)

        builder
            .setContentTitle(typeLabel)
            .setContentText("取件码：${record.code}")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainIntent)
            .setAutoCancel(true)

        // Step1：build() 前注入图片/Action extras
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.addExtras(buildExtraBundle(copyIntent))
        }

        // Step2：build()
        val notification = builder.build()

        // Step3：build() 后注入 miui.focus.param（官方强制顺序）
        val protocolVersion = getFocusProtocolVersion(context)
        buildFocusParamJson(record, typeLabel, protocolVersion)?.let {
            notification.extras.putString(KEY_FOCUS_PARAM, it)
        }

        return notification
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun buildExtraBundle(copyIntent: PendingIntent): Bundle {
        val pics = Bundle().apply {
            putParcelable(KEY_PIC_ICON,   Icon.createWithResource(context, R.drawable.ic_notification))
            putParcelable(KEY_PIC_TICKER, Icon.createWithResource(context, R.drawable.ic_notification))
        }
        val actions = Bundle().apply {
            putParcelable(KEY_ACTION_COPY,
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_scan),
                    "复制验证码", copyIntent
                ).build()
            )
        }
        return Bundle().apply {
            putBundle(KEY_FOCUS_PICS,    pics)
            putBundle(KEY_FOCUS_ACTIONS, actions)
        }
    }

    private fun buildFocusParamJson(
        record: CodeRecord, typeLabel: String, protocolVersion: Int
    ): String? = when {
        protocolVersion >= 3 -> buildSuperIslandJson(record, typeLabel)
        protocolVersion >= 1 -> buildFocusOnlyJson(record, typeLabel)
        else                 -> null
    }

    /** 完整超级岛 JSON（澎湃OS3 专用，含 bigIslandArea / smallIslandArea / shareData） */
    private fun buildSuperIslandJson(record: CodeRecord, typeLabel: String): String {
        val color = getHighlightColor(record.codeType)
        return JSONObject().apply {
            put("param_v2", JSONObject().apply {
                put("business", "pickcode")
                put("islandFirstFloat", true)
                put("enableFloat", false)
                put("updatable", false)
                put("filterWhenNoPermission", false)
                put("ticker", "${typeLabel}：${record.code}")
                put("tickerPic", KEY_PIC_TICKER)
                put("aodTitle", "取件码 ${record.code}")
                put("aodPic", KEY_PIC_ICON)

                put("param_island", JSONObject().apply {
                    put("islandProperty", 1)
                    put("islandTimeout", 15)
                    put("highlightColor", color)

                    put("bigIslandArea", JSONObject().apply {
                        put("imageTextInfoLeft", JSONObject().apply {
                            put("type", 1)
                            put("picInfo", JSONObject().apply {
                                put("type", 1); put("pic", KEY_PIC_ICON)
                            })
                            put("textInfo", JSONObject().apply {
                                put("frontTitle", typeLabel)
                                put("title", record.code)
                                put("content", "点击复制到剪贴板")
                                put("useHighLight", true)
                            })
                        })
                        put("picInfo", JSONObject().apply {
                            put("type", 1); put("pic", KEY_PIC_ICON)
                        })
                    })

                    put("smallIslandArea", JSONObject().apply {
                        put("picInfo", JSONObject().apply {
                            put("type", 1); put("pic", KEY_PIC_ICON)
                        })
                    })

                    put("shareData", JSONObject().apply {
                        put("title", typeLabel)
                        put("content", record.code)
                        put("shareContent", record.code)
                        put("pic", KEY_PIC_ICON)
                    })
                })

                put("baseInfo", JSONObject().apply {
                    put("title", typeLabel)
                    put("content", "取件码：${record.code}")
                    put("colorTitle", color)
                    put("type", 2)
                })

                put("hintInfo", JSONObject().apply {
                    put("type", 1)
                    put("title", "点击复制")
                    put("actionInfo", JSONObject().apply { put("action", KEY_ACTION_COPY) })
                })
            })
        }.toString()
    }

    /** 轻量焦点通知 JSON（澎湃OS1/OS2 兼容） */
    private fun buildFocusOnlyJson(record: CodeRecord, typeLabel: String): String {
        return JSONObject().apply {
            put("param_v2", JSONObject().apply {
                put("ticker", "${typeLabel}：${record.code}")
                put("tickerPic", KEY_PIC_TICKER)
                put("aodTitle", "取件码 ${record.code}")
            })
        }.toString()
    }

    private fun buildPlainNotification(
        title: String, text: String, autoCancel: Boolean = false
    ): Notification {
        val mainIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(context, CHANNEL_ISLAND)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(mainIntent).setAutoCancel(autoCancel).build()
        else
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(mainIntent).setAutoCancel(autoCancel).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ISLAND, CHANNEL_ISLAND_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "显示识别到的取件码、取餐码等验证码（支持小米超级岛）"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
