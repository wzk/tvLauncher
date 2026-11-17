# tvLauncher — Android TV 自定义桌面

适用于 Android TV / 电视盒子的自定义 Launcher（桌面程序），支持开机自启、网络等待、默认应用自动拉起（Kiosk 模式）与遥控器焦点交互。兼容 Android 4.4（API 19）至 Android 14，已在小米电视2s（4.4）、小米盒子3（5.1）及 Android 6.0+ 设备上验证。

## 功能特性

### 开机启动流程
- **开机自启**：通过 `BOOT_COMPLETED` 广播接收器（含盒子 QUICKBOOT 兜底）开机自动拉起桌面
- **网络检查**：启动后立即检测网络（Wi-Fi / 有线以太网均可）
  - 未联网 → 显示等待画面，并监听网络变化，联网后自动继续
  - 已联网 → 延时 2 秒后自动启动设置的默认应用

### 启动等待画面
- 开机到进入默认应用之间的整个等待过程，全屏只显示一张启动图片，不显示任何桌面元素
- 优先级：用户自定义图片 > 内置默认图（`assets/logo.jpg`）
- 图片渲染使用 WebView（Chromium 解码），绕过部分老设备损坏的 BitmapFactory；WebView 不可用时自动降级为内置渐变背景，不影响核心功能

### 默认应用 Kiosk 保活
- 从默认应用退出（或崩溃）返回桌面后，立即自动重新拉起该应用
- 等待重进期间仍显示启动图片；按遥控器**返回键**取消自动启动并进入桌面，按**菜单键**直接进入桌面设置页
- 防止应用循环崩溃导致无法进入桌面的安全阀设计

### 桌面与应用列表
- RecyclerView 网格展示所有具备 `CATEGORY_LAUNCHER` / `CATEGORY_LEANBACK_LAUNCHER` 的应用（含电视专属应用）
- 遥控器 D-Pad 聚焦高亮、放大效果，确认键启动应用
- 监听系统安装 / 卸载 / 覆盖安装广播，应用变更后自动刷新列表，无需重启
- 顶部「设置」按钮进入本桌面的设置页；`KEYCODE_SETTINGS` 快捷键等效

### 设置页
- **默认启动应用**：从已安装应用列表中选择开机自动启动的应用
- **自定义启动图片**：三种方式——内置文件选择器（浏览 `/sdcard`）、手动输入路径 / URL（支持 HTTP 下载）、清除恢复默认图
- **预览效果**：全屏预览当前启动图片实际效果（centerCrop 铺满）
- 显示当前版本号

## 快捷键

| 按键 | 行为 |
|---|---|
| 返回键 | 取消自动启动默认应用，进入桌面 |
| 菜单键 | 进入桌面设置页 |
| 设置键 | 进入桌面设置页 |

## 环境要求

- Android 4.4+（minSdk 19），targetSdk 34
- Kotlin 1.9 / AGP 8.7 / Gradle 8.10（Wrapper 已内置，配置了腾讯镜像加速）
- 权限：`BOOT_COMPLETED`、`ACCESS_NETWORK_STATE`、`READ_MEDIA_IMAGES` / 存储（仅图片读取）、`QUERY_ALL_PACKAGES`

## 构建与安装

```bash
# 构建
./gradlew assembleDebug

# 安装（签名固定于 app/tvlauncher.keystore，任意机器构建均可覆盖安装）
adb install -r tvLauncher-vX.Y.Z-debug.apk
```

产物命名规则：`tvLauncher-v{版本}-{构建类型}.apk`，输出于项目根目录与 `app/build/outputs/apk/`。

## 使用说明

1. 安装后按遥控器 HOME 键，选择本桌面并勾选「始终」设为默认桌面
   - 若系统不弹选择框（如部分小米设备绑定自家桌面），可通过：
     ```bash
     adb shell cmd package set-home-activity com.tvlauncher/.MainActivity   # Android 8+
     ```
2. 按菜单键进入设置 → 「默认启动应用」选择开机要自动启动的应用
3. 如需自定义启动图片：`adb push logo.jpg /sdcard/` 后在设置中选择，或输入 HTTP 直链
4. 重启设备验证：开机 → 启动图 → 联网 → 2 秒后自动进入默认应用；按返回键可进入桌面

## 项目结构

```
app/src/main/
├── AndroidManifest.xml          # 权限、HOME 桌面属性、开机广播
├── assets/logo.jpg              # 内置默认启动图
└── java/com/tvlauncher/
    ├── MainActivity.kt          # 主逻辑：网络检测/监听、等待画面、Kiosk 启动与重进、按键处理
    ├── SettingsActivity.kt      # 设置页：默认应用、启动图（选择/路径/URL）、预览
    ├── AppListAdapter.kt        # 应用网格适配器 + D-Pad 焦点高亮
    ├── AppInfo.kt               # 应用数据类 + 应用列表加载（含电视应用入口合并）
    ├── BootReceiver.kt          # 开机自启广播接收器
    ├── WallpaperLoader.kt       # 启动图路径管理
    ├── FilePickerDialog.kt      # 内置文件选择器（不依赖系统 DocumentsUI）
    └── Prefs.kt                 # SharedPreferences 配置读写
```

## 已知设备兼容说明

| 设备 | 系统 | 状态 |
|---|---|---|
| 小米电视2s | Android 4.4 (API 19) | 可用 |
| 小米盒子3 | Android 5.1 (API 22) | 可用（v1.5.1 修复启动崩溃） |
| Android 6.0+ 通用 | API 23+ | 可用 |

- 老设备 BitmapFactory 解码异常不影响使用：启动图走 WebView 渲染
- 部分厂商 ROM 绑定自家桌面时，HOME 键可能不弹出选择框，需用 adb 设置默认桌面或禁用厂商桌面

## 致谢

本项目灵感来自 [AnyLauncher](https://github.com/tumuyan/AnyLauncher)，根据实际设备环境与使用需求重新设计实现，由 AI 辅助开发。
