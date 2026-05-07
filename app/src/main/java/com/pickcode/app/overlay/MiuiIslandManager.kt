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
import android.util.Log
import androidx.annotation.RequiresApi
import com.pickcode.app.R
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.ui.activity.MainActivity
import com.pickcode.app.util.AppLog
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

    private val TAG = "MiuiIslandManager"

    companion object {
        // ── 小米超级岛 Bundle Key（来自官方文档）──
        private const val KEY_FOCUS_PARAM   = "miui.focus.param"
        private const val KEY_FOCUS_PICS    = "miui.focus.pics"
        private const val KEY_FOCUS_ACTIONS = "miui.focus.actions"
        // 图片资源 key（命名遵循官方文档示例）
        private const val KEY_PIC_IMAGETEXT = "miui.focus.pic_imageText"  // 大岛/小岛主图（左图右文中的图片）
        private const val KEY_PIC_TICKER    = "miui.focus.pic_ticker"     // 状态栏/锁屏图标
        private const val KEY_PIC_AOD       = "miui.focus.pic_aod"        // 息屏显示图标
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

    init {
        createNotificationChannel()
        // 启动时记录设备支持情况（一次性诊断）
        val protocol = getFocusProtocolVersion(context)
        val islandProp = isSupportIslandProperty()
        Log.i(TAG, "=== MiuiIslandManager 初始化诊断 ===")
        Log.i(TAG, "  focusProtocolVersion = $protocol (>=3 为超级岛)")
        Log.i(TAG, "  persist.sys.feature.island = $islandProp")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val enabled = notificationManager.areNotificationsEnabled()
            Log.i(TAG, "  notificationsEnabled = $enabled")
        }
        AppLog.i(TAG, "小米超级岛初始化: protocol=$protocol, islandProperty=$islandProp")
    }

    // ─── 公开方法 ─────────────────────────────────────────────

    override fun showCode(record: CodeRecord) {
        AppLog.i(TAG, "showCode 被调用: code=${record.code}")
        val protocolVersion = getFocusProtocolVersion(context)
        val hasPermission = hasFocusPermission(context)
        Log.i(TAG, "showCode: focusProtocolVersion=$protocolVersion, hasFocusPermission=$hasPermission")
        try {
            // 先取消旧通知再发新的，确保每次都能触发超级岛展开
            notificationManager.cancel(ISLAND_NOTIFICATION_ID)

            val notification = buildCodeNotification(record)
            // 验证超级岛参数是否成功注入
            val focusParam = notification.extras.getString("miui.focus.param")
            if (focusParam != null) {
                AppLog.i(TAG, "✅ miui.focus.param 已注入 (${focusParam.length}字符), protocol=$protocolVersion, hasPermission=$hasPermission")
                Log.i(TAG, "miui.focus.param injected: ${focusParam.take(120)}")
            } else {
                AppLog.w(TAG, "⚠️ miui.focus.param 为空! protocolVersion=$protocolVersion, 将以普通通知展示")
                Log.w(TAG, "miui.focus.param is NULL! protocol=$protocolVersion")
            }
            notificationManager.notify(ISLAND_NOTIFICATION_ID, notification)
            AppLog.i(TAG, "✅ 通知已发送 notify(id=$ISLAND_NOTIFICATION_ID)")
            Log.i(TAG, "Notification sent: id=$ISLAND_NOTIFICATION_ID")
        } catch (e: Exception) {
            Log.e(TAG, "showCode failed", e)
            AppLog.e(TAG, "showCode 异常: ${e.javaClass.simpleName}: ${e.message}", throwable = e)
        }
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
        // 注册所有图片资源（key 必须与 JSON 中的引用一致）
        val pics = Bundle().apply {
            putParcelable(KEY_PIC_IMAGETEXT, Icon.createWithResource(context, R.drawable.ic_notification))
            putParcelable(KEY_PIC_TICKER,    Icon.createWithResource(context, R.drawable.ic_notification))
            putParcelable(KEY_PIC_AOD,       Icon.createWithResource(context, R.drawable.ic_notification))
        }
        // 注册操作按钮
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

    /** 完整超级岛 JSON（澎湃OS3 专用，含 bigIslandArea / smallIslandArea / shareData）
     *
     * 严格遵循官方文档格式：
     * https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131
     *
     * 关键修复点（v1.4.0）：
     * - ✅ 添加 "protocol": 1（必填字段，缺失会导致超级岛不识别）
     * - ✅ 使用标准图片 key：pic_imageText / pic_ticker / pic_aod
     * - ✅ aodPic 正确引用 pic_aod key
     * - ✅ timeout 调整为 120 分钟（合理范围）
     */
    private fun buildSuperIslandJson(record: CodeRecord, typeLabel: String): String {
        val color = getHighlightColor(record.codeType)
        return JSONObject().apply {
            put("param_v2", JSONObject().apply {
                // === 必填字段 ===
                put("protocol", 1)                 // ⚠️ 协议版本，缺失则超级岛不生效！

                // === 顶层配置 ===
                put("business", "delivery")         // 业务场景：快递/物流（官方预定义值）
                put("islandFirstFloat", true)       // 首次展示为展开态
                put("enableFloat", true)            // 更新时也展开
                put("updatable", false)             // 非持续性通知
                put("filterWhenNoPermission", false) // 无权限降级为普通通知
                put("reopen", "reopen")             // 取消后可重新发送展示
                put("timeout", 120)                 // 120分钟后自动消失（分钟）

                // === 状态栏/息屏文案 ===
                put("ticker", "${typeLabel}：${record.code}")
                put("tickerPic", KEY_PIC_TICKER)    // → miui.focus.pic_ticker
                put("aodTitle", "取件码 ${record.code}")
                put("aodPic", KEY_PIC_AOD)          // → miui.focus.pic_aod

                // === 超级岛核心配置 ===
                put("param_island", JSONObject().apply {
                    put("islandProperty", 1)         // 1=信息展示为主
                    put("islandTimeout", 90)         // 岛展示90秒后自动收缩（秒）
                    put("highlightColor", color)
                    put("islandOrder", false)

                    // 大岛（展开态）：左图右文布局
                    put("bigIslandArea", JSONObject().apply {
                        put("imageTextInfoLeft", JSONObject().apply {
                            put("type", 1)
                            put("picInfo", JSONObject().apply {
                                put("type", 1); put("pic", KEY_PIC_IMAGETEXT)
                            })
                            put("textInfo", JSONObject().apply {
                                put("frontTitle", typeLabel)
                                put("title", record.code)
                                put("content", "点击复制到剪贴板")
                                put("useHighLight", true)
                            })
                        })
                        put("picInfo", JSONObject().apply {
                            put("type", 1); put("pic", KEY_PIC_IMAGETEXT)
                        })
                    })

                    // 小岛（摘要态/胶囊态）
                    put("smallIslandArea", JSONObject().apply {
                        put("picInfo", JSONObject().apply {
                            put("type", 1); put("pic", KEY_PIC_IMAGETEXT)
                        })
                    })

                    // 分享拖拽数据
                    put("shareData", JSONObject().apply {
                        put("title", typeLabel)
                        put("content", record.code)
                        put("shareContent", record.code)
                        put("pic", KEY_PIC_IMAGETEXT)
                    })
                })

                // === 降级时的焦点通知数据 ===
                put("baseInfo", JSONObject().apply {
                    put("title", typeLabel)
                    put("content", "取件码：${record.code}")
                    put("colorTitle", color)
                    put("type", 2)
                })

                // === 操作提示 ===
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
                put("protocol", 1)
                put("ticker", "${typeLabel}：${record.code}")
                put("tickerPic", KEY_PIC_TICKER)
                put("aodTitle", "取件码 ${record.code}")
                put("aodPic", KEY_PIC_AOD)
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
            // 官方文档示例使用 IMPORTANCE_DEFAULT
            // IMPORTANCE_HIGH 在某些ROM版本可能导致通知行为异常（如自动折叠/不展示超级岛）
            val channel = NotificationChannel(
                CHANNEL_ISLAND, CHANNEL_ISLAND_NAME, NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "显示识别到的取件码、取餐码等验证码（支持小米超级岛）"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                // ⚠️ 不设置声音，避免打扰用户
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
