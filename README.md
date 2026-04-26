# Affissia Player

> **Mostra. Play. Vendi.** — Affissia 的 Android 播放器，把屏幕变成数字招牌。

## What's new in v2.0

- Brand: **Affissia** name + AFFISSIA logo + navy/orange color palette.
- Package id: `com.affissia.player` (clean break from the v1 `com.signagehub.player` — v1 installs become orphan, intentional).
- **mDNS auto-discovery**: on first launch the player scans the LAN for an Affissia server (`_signagehub._tcp.local.`) and auto-fills the setup field — no more typing the URL by hand. Falls back to manual entry if discovery times out (10 s) or finds nothing.
- **Device announcement**: a per-install device id (UUID) is generated on first launch and POSTed to `POST /api/v1/devices/announce` with model + version. Server side ships next; until then the call tolerates 404 and the existing 6-digit pairing flow still works.
- Setup screen redesigned with the AFFISSIA logo + slogan.

## English

### Install on Android
1. Copy `app-release.apk` to the Android phone, tablet, TV box, or Fire TV Stick.
2. On the device, allow installation from unknown sources for the file manager, browser, or transfer app you used.
3. Open the APK and complete the install.
4. Launch **Affissia Player**.

### First-time setup (auto-discovery)
1. The setup screen shows **Searching for Affissia server on this network…**
2. If a server is found on the LAN, the URL field is filled automatically — tap **Save**.
3. If no server is found within 10 s, type the URL manually (e.g. `https://signage.bravotech.it`) and tap **Save**.
4. The app opens the full-screen player and loads `/display/new`.

### Pairing flow (still required in v2.0)
1. Wait until the device shows the six-digit pairing code from the server page.
2. In a browser, open your Affissia admin site and go to `/screens/pair`.
3. Enter the pairing code.
4. The player switches to the assigned content immediately.

> The next backend release will let the operator claim the device with one click from the admin console (using the `/api/v1/devices/announce` payload), removing the need for the 6-digit code.

### Long-press settings
1. Press and hold anywhere on the player screen for 5 seconds.
2. Choose **Change server URL** to reopen setup.
3. Choose **Reload** to request a fresh load from the current server.

### FAQ
- **mDNS not finding the server**: verify the Affissia server is launched with `SIGNAGEHUB_ENABLE_MDNS=1`. Some routers (especially guest Wi-Fi or VLAN-segregated APs) block multicast — fall back to manual URL entry.
- **Self-signed HTTPS**: MVP accepts self-signed certificates by default. Replace with certificate pinning in a later version.
- **Server port not reachable**: verify the Android device is on the same network, the server port is open, and the firewall allows inbound traffic.
- **Screen sleep**: the app keeps the screen on and holds a partial wake lock, but some vendors still apply aggressive battery policies. Exempt the app from battery optimization if needed.

## 中文

### Android 安装步骤
1. 把 `app-release.apk` 传到 Android 手机、平板、TV 盒子或 Fire TV Stick。
2. 在设备里给对应的文件管理器、浏览器或传输工具开启“允许安装未知来源应用”。
3. 打开 APK 并完成安装。
4. 启动 **Affissia 播放器**。

### 首次设置（自动发现）
1. 设置页显示 **正在局域网中搜索 Affissia 服务器…**
2. 找到服务器 → URL 字段自动填好，直接点 **保存**。
3. 10 秒内没找到 → 手动输入地址（如 `https://signage.bravotech.it`），点 **保存**。
4. 应用进入全屏播放器并加载 `/display/new`。

### 配对流程（v2.0 仍需要）
1. 等待设备显示服务器页面里的 6 位配对码。
2. 在浏览器打开 Affissia 后台的 `/screens/pair` 页面。
3. 输入配对码 → 完成绑定。

> 下个版本后端做完后，管理员可以在控制台一键认领设备（基于 `/api/v1/devices/announce` 上报的信息），不再需要 6 位码。

### 长按设置
1. 在播放器任意位置长按 5 秒。
2. 选择 **修改服务器地址** 可重新进入设置页。
3. 选择 **重新加载** 可向当前服务器重新请求内容。

### 常见问题
- **mDNS 找不到服务器**：确认服务器以 `SIGNAGEHUB_ENABLE_MDNS=1` 启动。部分路由器（尤其是访客 Wi-Fi 或 VLAN 隔离的 AP）会屏蔽多播，回退手动输入地址即可。
- **自签名 HTTPS**：MVP 默认接受自签名证书，后续版本建议改为证书 pinning。
- **端口不通**：确认 Android 设备与服务器在同一网络、服务器端口已开放、防火墙允许访问。
- **屏幕休眠**：应用会保持屏幕常亮并持有部分唤醒锁，但部分厂商仍会执行激进省电策略，必要时请把应用加入电池优化白名单。
