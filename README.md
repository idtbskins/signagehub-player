# SignageHub Player

## English

### Install on Android
1. Copy `app-debug.apk` or `app-release.apk` to the Android phone, tablet, TV box, or Fire TV Stick.
2. On the device, allow installation from unknown sources for the file manager, browser, or transfer app you used.
3. Open the APK and complete the install.
4. Launch **SignageHub Player**.

### First-time setup
1. Enter the full SignageHub server URL, including `http://` or `https://`.
2. Tap **Save**.
3. The app opens the full-screen player and loads `/display/new`.

### Pairing flow
1. Wait until the device shows the six-digit pairing code from the server page.
2. In a browser, open your SignageHub admin site and go to `/screens/pair`.
3. Enter the pairing code.
4. The player should switch to the assigned content immediately.

### Long-press settings
1. Press and hold anywhere on the player screen for 5 seconds.
2. Choose **Change server URL** to reopen setup.
3. Choose **Reload** to request a fresh load from the current server.

### FAQ
- **Self-signed HTTPS**: MVP accepts self-signed certificates by default. Replace this with certificate pinning in a later version.
- **Server port not reachable**: Verify the Android device is on the same network, the server port is open, and the firewall allows inbound traffic.
- **Screen sleep**: The app keeps the screen on and holds a partial wake lock, but some vendors still apply aggressive battery policies. Exempt the app from battery optimization if needed.

## 中文

### Android 安装步骤
1. 把 `app-debug.apk` 或 `app-release.apk` 传到 Android 手机、平板、TV 盒子或 Fire TV Stick。
2. 在设备里给对应的文件管理器、浏览器或传输工具开启“允许安装未知来源应用”。
3. 打开 APK 并完成安装。
4. 启动 **SignageHub Player**。

### 首次设置
1. 输入完整的 SignageHub 服务器地址，必须包含 `http://` 或 `https://`。
2. 点击 **保存**。
3. 应用会进入全屏播放器并加载 `/display/new`。

### 配对流程
1. 等待设备显示服务器页面里的 6 位配对码。
2. 在浏览器打开 SignageHub 后台的 `/screens/pair` 页面。
3. 输入配对码。
4. 播放器应立即切换到已分配内容。

### 长按设置
1. 在播放器任意位置长按 5 秒。
2. 选择 **修改服务器地址** 可重新进入设置页。
3. 选择 **重新加载** 可向当前服务器重新请求内容。

### 常见问题
- **自签名 HTTPS**：MVP 默认接受自签名证书，后续版本建议改为证书 pinning。
- **端口不通**：确认 Android 设备与服务器在同一网络、服务器端口已开放、防火墙允许访问。
- **屏幕休眠**：应用会保持屏幕常亮并持有部分唤醒锁，但部分厂商仍会执行激进省电策略，必要时请把应用加入电池优化白名单。
