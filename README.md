# 码速达 (PickCode)

> 一键截屏提取取件码、取餐码，通过小米超级岛（Super Island）原生接口展示在屏幕顶部，随取随用。

---

## 功能特性

| 功能 | 说明 |
|------|------|
| 屏幕识别 | 通过 MediaProjection 截图 + ML Kit OCR，完全离线识别 |
| **小米超级岛** | 识别结果通过澎湃OS3原生接口展示在屏幕顶部（非悬浮窗） |
| 通知栏入口 | 系统常驻通知，点击即触发截屏识别 |
| 快捷开关 | Quick Settings Tile，下拉通知面板一键触发 |
| 智能分类 | 自动区分快递码、取餐码、停车码，对应颜色高亮 |
| 历史记录 | 最近 50 条，支持收藏、复制、左滑删除 |
| 深色模式 | 完整适配 Android 夜间模式 |
| 多设备兼容 | 超级岛优先；OS1/2 降级为焦点通知；其他设备显示普通通知横幅 |

## 超级岛接入说明

本项目使用**小米澎湃OS官方 Super Island API**，通过原生通知实现灵动岛效果：

```
识别完成
    ↓
构建标准 Android Notification（Notification.Builder）
    ↓
检测 focusProtocolVersion（Settings.System）
    ↓
  ┌─ version >= 3 ──→ 注入完整 param_v2 JSON（bigIslandArea + smallIslandArea）
  ├─ version 1~2  ──→ 注入轻量 param_v2 JSON（ticker + aodTitle）
  └─ version == 0 ──→ 直接显示普通通知横幅
    ↓
notification.extras.putString("miui.focus.param", json)  ← build() 之后执行
    ↓
notificationManager.notify()
```

**关键技术要点（来自官方文档）：**
- Bundle Key：`miui.focus.param`（必须在 `build()` 之后通过 `extras.putString` 注入）
- 图片资源：通过 `miui.focus.pics` Bundle 传入（`builder.addExtras()` 在 build 前）
- Action 按钮：通过 `miui.focus.actions` Bundle 传入，`PendingIntent` 必须含 `FLAG_RECEIVER_FOREGROUND`
- 版本检测：`Settings.System.getInt(cr, "notification_focus_protocol", 0)`

## 支持场景的验证码类型

- **快递取件码** 📦：菜鸟驿站、蜂鸟、京东快递、顺丰等 4~8 位取件码
- **餐饮取餐码** 🍜：奶茶（喜茶、奈雪等）、外卖自提 3~6 位取餐号
- **停车取车码** 🚗：1~4 位停车场短码
- **其他验证码** 🔢：4~8 位通用数字验证码（兜底匹配）

## 项目结构

```
app/src/main/java/com/pickcode/app/
├── PickCodeApp.kt                      # Application，创建通知频道
├── data/
│   ├── model/CodeRecord.kt             # 数据模型（Room Entity）
│   ├── db/                             # Room DAO + Database
│   └── repository/                     # Repository 层
├── ocr/
│   └── CodeExtractor.kt               # ML Kit OCR + 正则提取核心
├── service/
│   ├── PickCodeService.kt             # 前台服务（截屏+分发+超级岛展示）
│   ├── CopyCodeReceiver.kt            # 超级岛"复制"按钮广播接收器
│   └── BootReceiver.kt                # 开机自启
├── tile/
│   └── PickCodeTileService.kt         # Quick Settings Tile
├── overlay/
│   └── IslandNotificationManager.kt   # 小米超级岛通知管理器（核心）
└── ui/
    ├── activity/
    │   ├── MainActivity.kt
    │   ├── PermissionActivity.kt       # 透明截屏授权页
    │   └── SettingsActivity.kt
    ├── fragment/
    │   └── SettingsFragment.kt
    ├── adapter/
    │   └── CodeRecordAdapter.kt
    └── viewmodel/
        └── MainViewModel.kt
```

## 技术栈

- **语言**：Kotlin
- **架构**：MVVM + Coroutines + StateFlow
- **数据库**：Room
- **OCR**：Google ML Kit Text Recognition（完全离线）
- **截屏**：MediaProjection API
- **超级岛**：小米澎湃OS3 原生 `miui.focus.param` 接口（官方 API）
- **快捷开关**：TileService（Android 7+）
- **UI**：Material Components 3

## 所需权限

| 权限 | 用途 |
|------|------|
| FOREGROUND_SERVICE_MEDIA_PROJECTION | 截屏服务（必须） |
| POST_NOTIFICATIONS | 超级岛通知展示（Android 13+） |
| RECEIVE_BOOT_COMPLETED | 开机自启（可选） |
| VIBRATE | 震动反馈（可选） |

> ⚠️ 本版本已**移除** `SYSTEM_ALERT_WINDOW`（悬浮窗权限）。  
> 超级岛功能通过原生通知接口实现，无需悬浮窗权限。

## 首次使用

1. 安装后打开 App，授予通知权限（Android 13+）
2. **小米设备**：进入 设置 → 通知 → 焦点通知，找到"码速达"并开启
3. 系统弹出截屏权限弹窗，选择「立即开始」
4. 对准包含取件码的界面，点击通知栏「码速达已就绪」或下拉快捷开关触发识别
5. 验证码出现在屏幕顶部超级岛（小米）或通知横幅（其他设备），点击「复制」即可

## 构建

```bash
# 需要 Android Studio Hedgehog 或更新版本
./gradlew assembleDebug
# APK 输出：app/build/outputs/apk/debug/app-debug.apk
```

## 超级岛功能要求

| 条件 | 说明 |
|------|------|
| 设备型号 | 小米 / 红米系列（支持超级岛的机型） |
| 系统版本 | 澎湃OS3（HyperOS 3.x）或更新版本 |
| 通知权限 | POST_NOTIFICATIONS（Android 13+） |
| 焦点通知权限 | 在系统设置中手动开启（首次提示引导） |

非小米设备或旧版本系统将自动降级为标准通知横幅，功能完全正常。
