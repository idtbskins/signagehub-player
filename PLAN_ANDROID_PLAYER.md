# SignageHub Player — Android APK 开发计划

**目标**:做一个 Android 播放器 APK,装到任何 Android 设备(手机 / TV 盒子 / Fire TV Stick)上,就是一块 SignageHub 屏幕。解决 iPad Safari 全屏不稳 / 浏览器跨设备行为不一致的痛。

**产品定位**:SignageHub 整个系统的**显示端**。服务器侧代码在 `~/Documents/claude/signagehub/`,不改。

---

## 1. 用户故事

**非程序员店主的使用流程**:

1. 任意 Android 设备(手机 / TV 盒子 / Fire TV Stick)
2. 下载我们的 `signagehub-player.apk` 装上(一次性)
3. 打开应用 → 首次弹输入框让填服务器地址(例:`https://192.168.1.16:5010`)
4. 保存 → 应用进入**全屏**、显示 **6 位配对码**(大字居中,黑底白字)
5. 店主在 Mac/手机浏览器打开后台 `/screens/pair` → 输入配对码 → 完成绑定
6. 应用画面**立刻换成推送的内容**,之后开机自动启动,永远不用碰

---

## 2. MVP 范围

### 必做

| 功能 | 说明 |
|---|---|
| WebView 播放 | Single Activity 包一个 WebView,加载 `https://<server>/display/new` 或 `/display/<screen_id>` |
| 全屏沉浸模式 | 隐藏 status bar + nav bar(`WindowInsetsController` / `systemUiVisibility`) |
| 保持屏幕常亮 | `FLAG_KEEP_SCREEN_ON` + `PARTIAL_WAKE_LOCK`(CPU 不睡) |
| 自签名证书信任 | WebViewClient.onReceivedSslError → `handler.proceed()`(仅在 debug/手动信任模式下) |
| JavaScript / localStorage / IndexedDB 启用 | 我们服务器的 display.js 要用 |
| 第一次启动引导 | 让用户填服务器 URL,保存到 SharedPreferences |
| 开机自启 | `BOOT_COMPLETED` BroadcastReceiver → 启动 MainActivity |
| 崩溃自重启 | `UncaughtExceptionHandler` 捕获后重启 Activity |
| 服务器 URL 变更 | 组合键 / 长按屏幕 5 秒 → 弹设置对话框改 URL |
| 应用图标 + 启动画面 | 黑底 SignageHub 文字,不做精细品牌 |
| 网络丢失重试 | WebView 加载失败 → 3 秒后重试,重试 3 次后显示红色提示 |

### 不做(v1 省略,v2 再说)

- Kiosk 锁定(client 侧限制出入)— Android 侧用第三方 MDM 或 device owner mode 才能真锁,v1 只做全屏,不做硬锁
- 多 WebView 同时播多屏(一 APK 一屏)
- 视频编解码优化(用系统 WebView 默认的 MediaPlayer)
- 自更新(通过 Play Store 或自建 OTA,v2)
- Samsung Tizen / LG WebOS 版本(永远不做,走外挂 Fire TV Stick)
- 登录/账号(服务器侧已有 pairing 机制,APK 本地不做账号)

---

## 3. 技术栈

- **语言**:Kotlin
- **Min SDK**:24(Android 7.0,覆盖 2016+ 所有设备,~95% 市场)
- **Target SDK**:34(Android 14)
- **Build system**:Gradle 8.x + Kotlin DSL
- **Single Activity**(不用 Fragment / Navigation / Compose,纯 XML View,最小化复杂度)
- **依赖**:只用 AndroidX 核心(`core-ktx`, `appcompat`),不引第三方库
- **构建产物**:`app-debug.apk`(开发测试用)+ `app-release.apk`(签名后,部署用)

---

## 4. 项目结构

```
~/Documents/claude/signagehub-player/
├── PLAN_ANDROID_PLAYER.md       ← 本文件
├── README.md                    ← 部署/装机说明
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/              ← gradle-wrapper.jar + properties
├── gradlew                      ← Unix 脚本
├── gradlew.bat
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/signagehub/player/
│   │   │   ├── MainActivity.kt
│   │   │   ├── SetupActivity.kt        ← 首次 URL 输入
│   │   │   ├── BootReceiver.kt
│   │   │   ├── ConfigStore.kt          ← SharedPreferences wrapper
│   │   │   └── SignageWebClient.kt     ← 自签名证书 + 错误重试
│   │   └── res/
│   │       ├── layout/
│   │       │   ├── activity_main.xml   ← 单一 WebView 布局
│   │       │   └── activity_setup.xml  ← EditText + Save 按钮
│   │       ├── values/
│   │       │   ├── strings.xml
│   │       │   ├── colors.xml
│   │       │   └── themes.xml
│   │       ├── mipmap/                 ← 图标
│   │       └── drawable/               ← 启动画面
│   └── src/test/                       ← 单元测试(可选)
└── keystore/                           ← 打 release APK 的签名 key(不 commit)
    └── README.md                       ← 说明怎么生成 keystore
```

---

## 5. 实现细节(逐文件)

### `MainActivity.kt`
- 读 `ConfigStore.serverUrl`,如果空 → 跳 `SetupActivity`
- 初始化 WebView:
  ```kotlin
  webView.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      databaseEnabled = true
      mediaPlaybackRequiresUserGesture = false
      mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE
  }
  webView.webViewClient = SignageWebClient(::onLoadError)
  webView.loadUrl("$serverUrl/display/new")
  ```
- 沉浸模式(API 30+ 用 `WindowInsetsController`,老版本 fallback `systemUiVisibility`)
- `FLAG_KEEP_SCREEN_ON`
- 长按屏幕 5 秒 → 弹 AlertDialog → "Change server URL" / "Reload" / "Cancel"
- Back 键 → 吃掉不响应(避免误退出)

### `SetupActivity.kt`
- 启动时检查:如果 `serverUrl` 已设置,立刻跳 MainActivity
- 单个 EditText + "Save" 按钮
- 校验:URL 必须以 `http://` 或 `https://` 开头,否则 Toast
- 保存后 `finish()` + startActivity(MainActivity)

### `BootReceiver.kt`
- `<receiver>` 监听 `android.intent.action.BOOT_COMPLETED`
- `onReceive` → `Intent(context, MainActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK)`
- AndroidManifest 加 `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>`

### `ConfigStore.kt`
- 简单 SharedPreferences wrapper
- 字段:`serverUrl`, `lastLoadedAt`, `failedLoadCount`

### `SignageWebClient.kt`
- `onReceivedSslError(handler, error)` → 记 log + `handler.proceed()`(自签名证书通过)
- `onReceivedError(...)` → 调 callback,触发 `Handler().postDelayed` 3 秒后 `webView.reload()`,重试 3 次后显示 XML 错误页
- `shouldOverrideUrlLoading` → 全部走 WebView 内(不让跳外部浏览器)

### `AndroidManifest.xml`
- 应用名:`SignageHub Player`
- 主题:`Theme.AppCompat.NoActionBar.FullScreen`(自定义)
- 权限:
  - `INTERNET`
  - `ACCESS_NETWORK_STATE`
  - `WAKE_LOCK`
  - `RECEIVE_BOOT_COMPLETED`
- Activity flags:`screenOrientation=landscape`(可配置)/ `configChanges=keyboardHidden|orientation|screenSize`
- `<intent-filter>` MainActivity 加 LAUNCHER + HOME(可选,若设为 HOME 则成为默认启动器 → 更 Kiosk)

### `strings.xml`(en + zh)
- `app_name` = "SignageHub Player"
- `setup_title` = "First time setup"
- `setup_hint` = "Enter your SignageHub server URL"
- `setup_example` = "e.g. https://192.168.1.16:5010"
- `setup_save` = "Save"
- `dialog_title` = "SignageHub Player"
- `dialog_change_url` = "Change server URL"
- `dialog_reload` = "Reload"
- `dialog_cancel` = "Cancel"
- 中文对照

---

## 6. 构建 / 安装前置

### Codex 第一步:装工具链(如果没有)

```bash
# 检查 Java 17+
java -version 2>&1 | grep -qE "version \"(1[7-9]|[2-9][0-9])" || brew install openjdk@17

# 检查 Android SDK
[ -d "$HOME/Library/Android/sdk" ] || {
  brew install --cask android-commandlinetools
  mkdir -p $HOME/Library/Android/sdk
  export ANDROID_HOME=$HOME/Library/Android/sdk
  yes | sdkmanager --licenses
  sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
}
```

环境变量:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

### Codex 第二步:初始化 Gradle 工程

手写 `settings.gradle.kts` / `build.gradle.kts` / `app/build.gradle.kts`,不用 Android Studio(无 GUI)。

### Codex 第三步:实现代码,按 §5 每个文件写。

### Codex 第四步:构建

```bash
cd ~/Documents/claude/signagehub-player
./gradlew assembleDebug
# 产出:app/build/outputs/apk/debug/app-debug.apk
```

### Codex 第五步:生成签名(release build)

```bash
keytool -genkey -v -keystore keystore/signagehub-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias signagehub -storepass signagehub2026 -keypass signagehub2026 \
  -dname "CN=SignageHub, OU=Signage, O=Bravotech, L=Rome, S=Lazio, C=IT"

./gradlew assembleRelease
# 产出:app/build/outputs/apk/release/app-release.apk
```

keystore 文件**不要 commit** git,加 `.gitignore`。

---

## 7. 测试方案

### 测试机清单

- **必测**:勇哥手头任一 Android 手机(他会提供型号)
- **建议**:Fire TV Stick 4K(€40,之后下单)

### 测试步骤(debug APK)

1. 手机 → 设置 → 允许未知来源
2. USB / AirDrop / 邮件把 `app-debug.apk` 传到手机
3. 点击安装
4. 启动 → 首屏输入 `https://192.168.1.16:5010` → Save
5. 看到 6 位配对码
6. Mac 浏览器 `/screens/pair` 输入配对码
7. 手机屏立刻变成后台推送的内容
8. 锁屏 / 开屏 → 内容仍在播
9. 重启手机(测开机自启)→ 应用自动启动,继续播

### 测试用例 checklist

- [ ] WebView 加载自签名 HTTPS 成功
- [ ] 6 位配对码全屏大字显示
- [ ] 配对成功后内容立刻切换
- [ ] 推送新图片 → 屏幕秒换
- [ ] Wi-Fi 断开 → 显示错误页,恢复后自动重连
- [ ] 屏幕不熄灭(至少 30 分钟观测)
- [ ] 长按 5 秒 → 弹设置框 → 改 URL → 生效
- [ ] 开机自启有效
- [ ] 崩溃后自恢复(人为 kill 进程测)
- [ ] Back 键不退出

---

## 8. 交付物

- `~/Documents/claude/signagehub-player/` 完整 Gradle 工程
- `app/build/outputs/apk/debug/app-debug.apk` 可装机测试
- `app/build/outputs/apk/release/app-release.apk` 签名后的发布版
- `README.md`:装机步骤图文
- 服务器侧 `signagehub/` 不改动(零侵入)

---

## 9. 硬约束

- 不改 `~/Documents/claude/signagehub/` 任何文件
- 不加第三方 maven 依赖(只用 AndroidX 官方 core + appcompat)
- 不用 Jetpack Compose(XML View 更轻)
- 不引 Kotlin Coroutines / Flow(用 Handler / simpler)
- APK 大小目标 < 5MB
- Min SDK 24
- 禁止在 UI 用 emoji(遵循 signagehub UI_GUIDELINES.md,虽然 APK 项目不用完整 guidelines,但"禁 emoji"这条通用)
- 所有字符串走 `strings.xml`(中英双语)

---

## 10. 时间估算

| 阶段 | 时间 | 产物 |
|---|---|---|
| 工具链安装 | 30 分钟 | openjdk + Android SDK |
| Gradle 工程骨架 | 30 分钟 | 能 `./gradlew build` |
| WebView + 全屏 + 常亮 | 1 小时 | 基本播放功能 |
| SetupActivity + 配置存储 | 30 分钟 | 首次 URL 输入 |
| 自签证书 + 错误重试 | 30 分钟 | 稳定连自签 HTTPS |
| BootReceiver + 长按设置 | 30 分钟 | 自启 + 换 URL |
| 应用图标 + 主题 | 20 分钟 | 图标与启动画面 |
| 打 debug APK + release APK | 30 分钟 | 两份 APK |
| README 装机指南 | 30 分钟 | `README.md` |

**总计**:约 5-6 小时 Codex 工作

---

## 11. 成功标准

1. 勇哥手机装上 APK
2. 输入他 Linux 服务器 URL(`https://192.168.1.16:5010`)
3. 显示 6 位配对码
4. 后台配对成功
5. 推图立即显示
6. 重启手机自动启动并继续播

这 6 条全通 → MVP 完成,可以买 Fire TV Stick 做真机二测。

---

*Drafted: 2026-04-18 · Claude PM · Codex 执行*
