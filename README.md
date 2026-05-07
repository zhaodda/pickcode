# 码速达 (PickCode)

> 一键识别快递取件码 / 取餐码，通过小米澎湃 OS 超级岛（Super Island）展示在屏幕顶部，随取随用。

[![Build](https://github.com/zhaodda/pickcode/actions/workflows/build.yml/badge.svg)](https://github.com/zhaodda/pickcode/actions)

---

## 功能特性

| 功能 | 说明 |
|------|------|
| 屏幕文字提取 | 通过无障碍服务（AccessibilityService）读取屏幕节点树文字，无需截图、无需 OCR 授权弹窗 |
| 小米超级岛 | 识别结果通过澎湃 OS 原生 `miui.focus.param` 接口展示为灵动岛（非悬浮窗） |
| 通知栏入口 | 前台常驻通知，点击即触发屏幕识别 |
| Quick Settings Tile | 下拉快捷开关一键触发 |
| 手动输入 | 支持粘贴短信自动解析 + 手动填写验证码 |
| 智能分类 | 自动区分快递码 / 取餐码 / 停车码，对应颜色高亮 |
| 历史记录 | Room 本地存储，支持收藏、复制、删除 |
| 运行日志 | 完整的运行日志系统，方便排查问题 |
| 多设备兼容 | 超级岛优先；焦点通知降级；其他设备显示标准通知横幅 |

## 支持的验证码类型

- **快递取件码** - 菜鸟驿站、蜂鸟、京东快递、顺丰等 4~8 位取件码
- **餐饮取餐码** - 喜茶、奈雪等奶茶 / 外卖自提 3~6 位取餐号
- **停车取车码** - 1~4 位停车场短码
- **通用数字验证码** - 4~8 位数字兜底匹配

## 技术架构

### 整体架构：MVVM + 工厂模式 + 门面模式

```
用户触发入口（3 种）
├── 通知栏按钮 → PickCodeService → AccessibilityService.extractFromScreenText("notification")
├── QS Tile     → PickCodeTileService   → extractFromScreenText("tile")
└── App 内 FAB  → MainActivity          → extractFromScreenText("auto")
                                              │
                                         读取屏幕节点树文字
                                         (AccessibilityNodeInfo)
                                              │
                                    CodeExtractor 正则提取验证码
                                              │
                                   ┌──────────┴──────────┐
                              识别成功                  识别失败
                                   │                       │
                    IslandNotificationManager      showNoResult() / showError()
                      （门面模式 Facade）
                           │
                IslandManagerFactory（工厂模式）
                           │
              ┌────────────┼────────────┐
         小米设备+OS3+    小米设备+OS1/2   非小米设备
       focusProtocol>=3  focusProtocol 1~2
              │               │               │
      MiuiIslandManager  MiuiIslandManager  FallbackIslandManager
      完整超级岛JSON       焦点通知JSON      标准通知横幅
      (胶囊+展开态)      (状态栏横幅)      (PRIORITY_HIGH)
```

### 核心技术方案

#### 文字提取 — 无障碍节点树遍历（v1.3.0 起）

不截图、不走 OCR，直接读取 Android 系统提供的 UI 节点树：

1. `PickCodeAccessibilityService.getRootInActiveWindow()` 获取当前窗口根节点
2. `getAllTextFromNode()` 递归遍历所有子节点，拼接 `getText()` + `getContentDescription()`
3. `CodeExtractor.extractFromText()` 在纯文本中正则匹配取件码

**优势**：零权限弹窗、零网络请求、完全离线、即时响应。

#### 小米超级岛 — miui.focus.param 注入

遵循 [小米官方文档](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)：

```
构建标准 Notification.Builder
        │
        ├── build() 前 → addExtras(miui.focus.pics + miui.focus.actions)
        │                   图片资源 Bundle（pic_imageText / pic_ticker / pic_aod）
        │                   操作按钮 Bundle（复制验证码 PendingIntent）
        │
        ├── builder.build() → 获得 Notification 对象
        │
        └── build() 后 → notification.extras.putString("miui.focus.param", json)
                            JSON 结构：
                            {
                              "param_v2": {
                                "protocol": 1,           ← 必填！缺失则超级岛不生效
                                "business": "delivery",
                                "param_island": { ... }  ← 大岛/小岛配置
                              }
                            }
        │
notificationManager.notify(id, notification)
```

#### 设备适配策略

| 条件 | 展示形式 | 说明 |
|------|---------|------|
| 小米 + 澎湃 OS3+ (`focusProtocol >= 3`) | **超级岛** | 胶囊态 + 左图右文展开态 |
| 小米 + 澎湃 OS1/2 (`focusProtocol 1~2`) | **焦点通知** | 状态栏增强横幅 |
| 小米 + 无焦点协议 (`focusProtocol == 0`) | **标准通知** | 高优先级横幅 |
| 非小米设备 | **标准通知** | Fallback 兜底 |

## 项目结构

```
app/src/main/java/com/pickcode/app/
├── PickCodeApp.kt                          # Application 入口
│
├── data/
│   ├── model/CodeRecord.kt                 # 数据模型（Room Entity）
│   ├── db/                                 # Room DAO + Database
│   │   ├── CodeRecordDao.kt
│   │   └── PickCodeDatabase.kt
│   └── repository/CodeRepository.kt
│
├── ocr/
│   └── CodeExtractor.kt                    # 正则提取核心（纯文本入口）
│
├── overlay/                                # ★ 超级岛模块
│   ├── IslandManagerBase.kt                # 抽象基类（统一接口）
│   ├── IslandManagerFactory.kt             # 工厂类（设备检测 + Manager 选择）
│   ├── IslandNotificationManager.kt         # 门面类（对外统一入口）
│   ├── MiuiIslandManager.kt                # 小米澎湃 OS 超级岛实现
│   └── FallbackIslandManager.kt            # 兜底标准通知
│
├── service/
│   ├── PickCodeAccessibilityService.kt     # ★ 核心：无障碍节点树文字提取
│   ├── PickCodeService.kt                  # 前台常驻服务（触发中转）
│   ├── CopyCodeReceiver.kt                 # 超级岛"复制"按钮广播接收器
│   └── BootReceiver.kt                     # 开机自启
│
├── tile/
│   └── PickCodeTileService.kt              # Quick Settings Tile
│
├── ui/
│   ├── activity/
│   │   ├── MainActivity.kt                 # 主界面（FAB + 手动输入）
│   │   ├── LogViewerActivity.kt            # 运行日志查看器
│   │   ├── SettingsActivity.kt             # 设置页
│   │   ├── PermissionActivity.kt           # 权限引导页
│   │   └── CaptureActivity.kt             # 已弃用（保留文件，不再调用）
│   ├── fragment/SettingsFragment.kt
│   ├── adapter/CodeRecordAdapter.kt
│   └── viewmodel/MainViewModel.kt
│
└── util/
    └── AppLog.kt                          # 运行日志管理器
```

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | — |
| 架构 | MVVM + Coroutines + StateFlow | — |
| UI | Material Design 3 | 1.11.0 |
| 数据库 | Room | 2.6.1 |
| 生命周期 | Lifecycle ViewModel / LiveData | 2.7.0 |
| 协程 | kotlinx-coroutines-android | 1.7.3 |
| OCR 引擎 | ML Kit Text Recognition | 16.0.0（保留兼容） |
| 设置页 | PreferenceX | 1.2.1 |

**编译环境**：compileSdk 34 / minSdk 26 / targetSdk 33

## 所需权限

| 权限 | 用途 | 必须 |
|------|------|------|
| `BIND_ACCESSIBILITY_SERVICE` | 无障碍服务（屏幕文字提取） | ✅ 必须 |
| `POST_NOTIFICATIONS` | 通知栏 + 超级岛展示（Android 13+） | ✅ 必须 |
| `FOREGROUND_SERVICE` (specialUse) | 前台常驻服务保活 | ✅ 必须 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启服务 | ⚪ 可选 |

> 已移除 `SYSTEM_ALERT_WINDOW`（悬浮窗）、`FOREGROUND_SERVICE_MEDIA_PROJECTION`（录屏）。

## 首次使用指南

1. 安装后打开「码速达」，授予**通知权限**
2. 进入 **设置 → 无障碍**，找到「码速达」并开启无障碍服务
3. **小米设备**（可选优化）：进入 **设置 → 通知 → 焦点通知**，找到「码速达」开启
4. 添加 **Quick Settings Tile**（快捷开关）：下拉通知面板 → 编辑 ✏️ → 找到「码速达」拖入
5. 使用任意方式触发识别：
   - 点击通知栏上的 **「立即识别」** 按钮
   - 下拉 QS 面板点击 **「码速达」** 磁贴
   - 在 App 内点击 **识别** 按钮
6. 识别到的验证码出现在屏幕顶部（超级岛或通知横幅），点击 **复制** 即可

## 构建

### 环境要求

- JDK 17+
- Android SDK（build-tools 34.0.0、platforms android-34）
- Gradle 8.2（由 wrapper 管理）

```bash
# Debug 包
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/PickCode-v1.x.x-debug.apk
```

GitHub Actions 自动打包：push 到 `main` 分支即可触发，APK 在 Actions → Artifacts 中下载。

## 超级岛接入要点

- 官方文档：<https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131>
- `param_v2` 必须包含 `"protocol": 1`（缺失则系统不识别为超级岛）
- 图片 key 必须先在 `miui.focus.pics` Bundle 注册，再在 JSON 中引用
- `miui.focus.param` 必须在 `builder.build()` 之后注入
- 广播类型 Action 的 PendingIntent 必须含 `FLAG_RECEIVER_FOREGROUND`

## 版本历史

| 版本 | 要点 |
|------|------|
| v1.5.1 | 修复超级岛 JSON 格式（添加 protocol:1、规范图片 key、对齐官方文档）|
| v1.5.0 | 重构超级岛模块，剔除 OPPO/vivo 灵动岛代码（净减少 670 行）|
| v1.4.2 | 超级岛参数调优（timeout/islandTimeout/enableFloat/reopen）|
| v1.4.1 | 增加超级岛诊断日志链路 |
| v1.4.0 | 修复 QS 磁贴误按 BACK 导致 App 返回上一页 |
| v1.3.3 | 新增面板残留自动重试机制（looksLikePanelText）|
| v1.3.2 | Tile 磁贴 startActivityAndCollapse 修复 |
| v1.3.1 | GLOBAL_ACTION_BACK 替代无效的 collapsePanels 反射 |
| v1.3.0 | 彻底弃用截屏方案，改为纯无障碍节点树文字提取 |
| v1.1.0 | 新增运行日志系统；改用 AccessibilityService 截屏 |
| v1.0.3 | 新增手动输入功能 |
| v1.0.2 | 闪退修复（targetSdk 34→33 + FGS type=specialUse）|

## License

MIT
