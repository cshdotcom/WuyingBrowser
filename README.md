# 无影浏览器 (Wuying Browser)

> Android 端"无影"浏览器 —— 退出最近任务后自动隐藏，桌面图标可伪装消失，但程序在后台持续运行，并具备强保活能力。下次启动直接恢复上次浏览的页面。

## 项目特性

### 无影特性
- **最近任务无痕**：`AndroidManifest.xml` 配置 `excludeFromRecents=true` + `autoRemoveFromRecents=true`，从最近任务列表里看不到本应用
- **桌面图标隐藏**：设置页提供「隐藏桌面图标」开关，通过 `PackageManager.setComponentEnabledSetting` 让 LAUNCHER 组件被禁用，桌面图标直接消失
- **任务隔离**：`taskAffinity=".wuying_ui"` 单独任务栈 + `launchMode=singleTask`，与后台服务完全解耦
- **后台静默运行**：退出时 `moveTaskToBack(true)` + `finish()`，进程不退出，仅是 UI 不可见

### 强保活机制（5 重）
1. **前台服务**：`CoreService` 启用 `foregroundServiceType=dataSync`，系统正常不会杀
2. **双进程守护**：`CoreService` 跑在 `:core` 进程，`DaemonService` 跑在 `:daemon` 进程，互相监听互相拉起
3. **`onTaskRemoved` 自启**：用户从最近任务滑掉时，通过 `AlarmManager` 1 秒后拉回服务，并按偏好决定是否唤起 UI
4. **`AlarmManager` 心跳**：每 5 分钟周期性 ping 一次服务
5. **开机自启**：`BootReceiver` 监听 `BOOT_COMPLETED` / `QUICKBOOT_POWERON` / `MY_PACKAGE_REPLACED` 等多种系统广播

### 浏览器内核
- 基于系统 `WebView`（Android 上系统 WebView 即 Chromium 内核）
- **支持所有 Chromium 特性**：JS、IndexedDB、Service Worker、WebGL、WebRTC、WebAssembly、Notifications、Permissions API、媒体流等
- **权限代理**：网页请求定位/相机/MIC 时，路由到系统运行时权限弹窗
- **文件选择**：网页 `<input type="file">` 弹起系统文件选择器
- **下载管理**：调用系统 `DownloadManager`，下载完成发通知
- **暗黑网页**：CSS 注入强制反转，给不支持暗黑模式的网站用

### 浏览器功能
- ✅ 多标签页（最多展示 6 个 tab chip，长按关闭）
- ✅ 无痕模式（不写历史，Cookie 内存态）
- ✅ 广告拦截（内置 60+ 黑名单域名 + URL 模式规则）
- ✅ 历史记录（Room 数据库，500 条上限）
- ✅ 下载管理（系统 DownloadManager）
- ✅ 书签管理
- ✅ 会话持久化（关闭后下次启动恢复上次所有标签页 URL）
- ✅ 设置页（PreferenceFragment，所有偏好实时生效）
- ✅ 全局快捷键（详见下文「唤出方式」）

### UI 风格
暗黑极客：纯黑底（#0A0A0F）+ 青色强调（#00FFA3），类 Tor Browser 隐私感

---

## 工程结构

```
WuyingBrowser/
├── app/
│   ├── build.gradle                       # 模块 Gradle 配置
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml            # ⭐ 无影核心配置
│       ├── java/com/wuying/browser/
│       │   ├── BrowserApplication.kt       # 入口，初始化 DB/Prefs/服务
│       │   ├── service/
│       │   │   ├── CoreService.kt          # ⭐ 前台保活服务（:core 进程）
│       │   │   └── DaemonService.kt        # ⭐ 守护进程（:daemon 进程）
│       │   ├── receiver/
│       │   │   ├── BootReceiver.kt         # 开机自启
│       │   │   ├── KeepAliveReceiver.kt   # 兜底心跳（USER_PRESENT/TIME_TICK 等）
│       │   │   └── DownloadCompleteReceiver.kt
│       │   ├── web/
│       │   │   ├── WuyingWebView.kt        # 自定义 WebView + 权限代理
│       │   │   └── AdBlocker.kt            # 广告拦截
│       │   ├── data/
│       │   │   ├── AppDatabase.kt          # Room DB + DAO
│       │   │   ├── PreferenceManager.kt    # SharedPreferences 封装
│       │   │   ├── SessionManager.kt       # 会话持久化
│       │   │   ├── HistoryManager.kt       # 历史记录
│       │   │   ├── BookmarkManager.kt     # 书签
│       │   │   └── DownloadManagerHelper.kt
│       │   ├── ui/
│       │   │   ├── BrowserActivity.kt      # 主浏览器界面
│       │   │   ├── TabsManager.kt          # 多标签管理
│       │   │   ├── SettingsActivity.kt    # 设置页
│       │   │   ├── HistoryActivity.kt
│       │   │   ├── DownloadsActivity.kt
│       │   │   └── BookmarksActivity.kt
│       │   └── util/
│       │       ├── WuyingLog.kt
│       │       ├── CrashHandler.kt         # 全局崩溃捕获 + 服务自启
│       │       └── WuyingBackupAgent.kt     # 数据云备份
│       └── res/                            # 暗黑主题、图标、布局
├── gradle/wrapper/                          # Gradle 8.2 wrapper
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── build.gradle                            # 工程 Gradle
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat                   # 跨平台 wrapper 启动器
└── README.md
```

---

## 编译方式

### 方式 1：Android Studio（推荐）

1. Android Studio → `File → Open` → 选 `WuyingBrowser` 目录
2. IDE 自动下载 Gradle 8.2 和 Android SDK
3. 等待 Sync 完成（首次约 5-10 分钟）
4. `Build → Build APK(s)` → 输出 `app/build/outputs/apk/debug/app-debug.apk`

### 方式 2：命令行

需要先装 Android SDK（设置 `ANDROID_HOME` 环境变量）和 JDK 17+：

```bash
# 1. 设置环境
export ANDROID_HOME=/path/to/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools

# 2. 编译 Debug APK
./gradlew assembleDebug

# 3. 产物
ls app/build/outputs/apk/debug/app-debug.apk
```

### 方式 3：装到手机

```bash
# USB 连接手机，开启开发者选项 + USB 调试
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 使用说明

### 基本使用
1. 桌面图标「无影」点击启动
2. 地址栏输入 URL 或搜索词 → 回车跳转
3. 底部 `+` 新建标签，长按 tab chip 关闭
4. 返回键：
   - 网页可后退 → 后退网页
   - 否则 → 隐藏到后台（最近任务列表自动消失）
5. 菜单 → 历史记录 / 下载管理 / 书签 / 清除数据 / 退出

### 唤出方式
当前版本默认唤出方式：
- 桌面图标（未隐藏时）
- 后台通知点击（`CoreService` 持久通知，点击打开主界面）
- 设置页打开「隐藏桌面图标」后，可通过 `adb` 唤起：
  ```bash
  adb shell am start -n com.wuying.browser/.ui.BrowserActivity
  ```

> **关于「全局快捷键唤出」**：Android 系统不允许普通应用注册真正的全局快捷键。可行的方案有：
> - **辅助功能服务（AccessibilityService）**：监听物理按键序列。需要用户在系统设置里手动授权"无障碍"。本次工程未实现，可作为下个版本增强
> - **悬浮窗 + 拖拽触发**：在屏幕边缘放一个 1px 悬浮按钮，从边缘滑入唤出。需 `SYSTEM_ALERT_WINDOW` 权限
> - **桌面快捷方式伪装**：在桌面生成一个"计算器"图标的快捷方式，点击唤出。需要 `INSTALL_SHORTCUT` 权限
>
> 你需要哪个我可以追加实现，告诉我即可。

### 设置页
- **无影模式**：无痕浏览、保留会话、退出清数据、隐藏桌面图标
- **网页内核**：JS、图片、DOM 存储、Cookie、强制暗黑、定位、HTTPS Only、DNT、首页、搜索引擎
- **广告拦截**：开关 + 自定义 EasyList URL
- **后台保活**：强保活、开机自启、显示后台通知
- **数据管理**：立即清除所有浏览器数据

---

## 关键技术点

### 1. 最近任务无痕
```xml
<activity
    android:name=".ui.BrowserActivity"
    android:excludeFromRecents="true"          <!-- ⭐ 关键 -->
    android:autoRemoveFromRecents="true"        <!-- ⭐ 任务移除时自动清理 -->
    android:taskAffinity=".wuying_ui"           <!-- 独立任务栈 -->
    android:launchMode="singleTask" />
```
当用户按 Home 或返回键，Activity 被 finish 后，最近任务列表不会出现本应用。

### 2. 后台强保活
```
主进程 com.wuying.browser
  └─ BrowserActivity (UI)

:core 进程 com.wuying.browser:core
  └─ CoreService (前台服务 + 通知)

:daemon 进程 com.wuying.browser:daemon
  └─ DaemonService (前台服务 + 通知)
```
- CoreService 跑前台服务，系统正常不会杀
- DaemonService 跑另一个进程，每分钟检查 CoreService 是否存活，被杀则拉起
- 反之 CoreService 也定期 ping DaemonService
- Android 系统同一时刻杀两个独立进程的概率极低

### 3. onTaskRemoved 重启
```kotlin
override fun onTaskRemoved(rootIntent: Intent?) {
    val restart = Intent(applicationContext, CoreService::class.java)
    val pi = PendingIntent.getService(this, 1, restart, ...)
    val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
    alarm[AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000] = pi

    // 如果开启了会话恢复，1.5 秒后拉起 BrowserActivity
    if (persistSession) {
        scope.launch {
            delay(1500)
            startActivity(Intent(this, BrowserActivity::class.java))
        }
    }
}
```

### 4. 会话持久化
每次 `onPause` / `onDestroy`，所有标签页的 URL + title 写入 Room `session_tab` 表。下次启动 `onCreate` 时读取并恢复所有标签页，自动 loadUrl。

### 5. Chromium 内核
Android 系统的 WebView 实质是 Chromium 内核（Android 7+ 已脱离系统升级，独立从 Play Store 更新）。本工程使用 `androidx.webkit:webkit:1.10.0` 接入最新 WebView 特性，包括：
- Algorithmic Darkening（API 29+）
- 安全浏览
- 所有 Chromium 标准 API

### 6. 权限代理
WebView 网页请求权限时，`WebChromeClient.onPermissionRequest` 接管：
```kotlin
override fun onPermissionRequest(request: PermissionRequest) {
    val allowLocation = PreferenceManager.get(KEY_LOCATION_ENABLED, true)
    val granted = if (!allowLocation && request.resources.contains(RESOURCE_VIDEO_STREAM)) {
        request.resources.filter { it != RESOURCE_VIDEO_STREAM }.toTypedArray()
    } else request.resources
    request.grant(granted)
}
```
真正调用 `request.grant()` 仍受系统运行时权限约束：BrowserActivity 在 `onCreate` 时已请求所有运行时权限。

---

## 已知限制 / 待增强

| 功能 | 状态 | 备注 |
|------|------|------|
| 真正的全局快捷键唤出 | ❌ 未实现 | Android 不支持普通应用全局快捷键。可通过 AccessibilityService 或悬浮窗实现，需要额外权限 |
| 进程名伪装 | ❌ 未实现 | Android 不允许运行时改进程名，需在 Manifest 静态配置。可通过 `android:process` 指定名字 |
| Alt+Tab 隐藏 | N/A | Android 无此概念 |
| 双进程 Binder 桥接 | ⚠️ 简化版 | 当前用 `startService` 互拉，没用 `ServiceConnection` 强绑定。可改为 AidlBinder 提升保活强度 |
| 广告拦截 EasyList 自动更新 | ⚠️ 简化版 | 内置黑名单已足够，可加 WorkManager 定期从 `ad_block_list_url` 拉取并解析 |
| WebRTC 代理权限 | ✅ 已支持 | 通过 `PermissionRequest.RESOURCE_VIDEO_STREAM/AUDIO_STREAM` 接管 |

---

## 系统要求

- Android 7.0 (API 24) 及以上
- 系统 WebView 已更新至 Chromium 90+ (基本所有 Android 7+ 设备都满足)
- ARM / ARM64 / x86 / x86_64 架构

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 浏览器基本功能 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | 前台服务保活（Android 9+ / 14+ 必需） |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `WAKE_LOCK` | 心跳唤醒 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 申请加入电池白名单（部分机型保活需要） |
| `ACCESS_FINE_LOCATION` / `COARSE_LOCATION` | 网页定位权限代理 |
| `CAMERA` / `RECORD_AUDIO` | 网页摄像头/MIC 权限代理 |
| `READ_MEDIA_IMAGES/VIDEO/AUDIO` | Android 13+ 媒体权限代理 |
| `REQUEST_INSTALL_PACKAGES` | 网页下载 APK 直接安装 |

---

## 法律 / 道德免责声明

本项目为隐私/便携浏览器的技术演示。请仅用于合法用途（隐私保护、防家长管控误删等），不要用于任何违反他人知情同意或法律法规的场景。作者不对滥用造成的后果负责。

---

## 反馈

- 想加全局快捷键唤出？告诉我用 `AccessibilityService` 还是 `悬浮窗`
- 想加进程名伪装？告诉我把进程改成什么名字（如 `com.android.systemhelper`）
- 想加更狠的双进程 Binder 强绑定？告诉我
- 想出 Release 签名版？告诉我你的 keystore 信息
