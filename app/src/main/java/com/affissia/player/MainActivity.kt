package com.affissia.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {
    private lateinit var configStore: ConfigStore
    private lateinit var deviceIdentity: DeviceIdentity
    private lateinit var secretStore: SecretStore
    private lateinit var heartbeat: HeartbeatScheduler
    private lateinit var ota: OtaChecker
    private lateinit var webView: WebView
    private var wakeLock: PowerManager.WakeLock? = null
    private var longPressRunnable: Runnable? = null
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressTriggered = false
    private var downX = 0f
    private var downY = 0f
    private var lastRemoteUrl: String = ""
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    // OTA poll cadence: every hour after launch. Cheap GET; tolerates 404.
    private val otaCheckIntervalMs: Long = 60L * 60L * 1000L
    private val otaCheckRunnable = object : Runnable {
        override fun run() {
            if (!::ota.isInitialized || !::configStore.isInitialized) return
            val url = configStore.serverUrl
            if (url.isNotBlank()) {
                ota.checkForUpdate(url, BuildConfig.VERSION_NAME)
            }
            longPressHandler.postDelayed(this, otaCheckIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installCrashHandlerIfNeeded()
        configStore = ConfigStore(this)
        if (configStore.serverUrl.isBlank()) {
            startActivity(SetupActivity.createIntent(this))
            finish()
            return
        }

        deviceIdentity = DeviceIdentity(this)
        secretStore = SecretStore(this)
        heartbeat = HeartbeatScheduler(deviceIdentity, configStore, secretStore)
        heartbeat.setDisplaySizeProvider { DisplayInfo.resolution(this) }
        heartbeat.setOnSecretRevoked {
            // PR #5 / decision row 12: server signalled the secret is
            // invalid. SecretStore is already cleared by the heartbeat
            // handler; route the WebView back to /display/new so the
            // operator can re-pair via the on-screen 4-digit code.
            val pendingUrl = "${configStore.normalizeServerUrl(configStore.serverUrl)}/display/new"
            lastRemoteUrl = pendingUrl
            if (::webView.isInitialized) {
                webView.loadUrl(pendingUrl)
            }
        }
        ota = OtaChecker(this)
        heartbeat.setOnBoundUrl { redirectUrl ->
            // Triggered when the heartbeat sees the admin bind us through
            // /screens/pair. The URL already contains the one-shot
            // ?token= query string for the auto-pair flow; the display
            // page's display.js stashes it to localStorage and replaces
            // the URL on first load. Re-loading is idempotent: if the
            // WebView is already on this URL (modulo the token we just
            // stripped), the early-out below avoids a flicker.
            if (lastRemoteUrl != redirectUrl) {
                lastRemoteUrl = redirectUrl
                webView.loadUrl(redirectUrl)
            }
        }
        heartbeat.setOnPairCode { pairCode ->
            reloadPendingPageWithPairCode(pairCode)
        }

        lastRemoteUrl = buildDisplayUrl(configStore.serverUrl)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.web_view)
        configureWindow()
        configureWakeLock()
        configureWebView()
        installLongPressGesture()
        loadInitialContent()
        announcePairCodeIfMissing()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
        }
        enterImmersiveMode()
        acquireWakeLock()
        // v2.0: heartbeat keeps the operator console aware of online
        // status; OTA check ensures the device pulls new APK builds
        // automatically (instead of needing manual reinstall).
        if (::heartbeat.isInitialized) heartbeat.start()
        if (::ota.isInitialized) {
            longPressHandler.removeCallbacks(otaCheckRunnable)
            longPressHandler.post(otaCheckRunnable)
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
        }
        if (::heartbeat.isInitialized) heartbeat.stop()
        longPressHandler.removeCallbacks(otaCheckRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        cancelLongPress()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.destroy()
        }
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersiveMode()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            true
        } else {
            super.dispatchKeyEvent(event)
        }
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun configureWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:PlayerWakeLock")
            ?.apply {
                setReferenceCounted(false)
            }
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        wakeLock?.let { lock ->
            if (!lock.isHeld) {
                lock.acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
    }

    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.BLACK)
        webView.isHorizontalScrollBarEnabled = false
        webView.isVerticalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            // v2.0 light offline cache: respect HTTP Cache-Control from
            // the server. If the network blip happens after content is
            // already loaded, WebView serves from disk cache instead of
            // showing an error. Full pre-download offline cache lands in
            // v2.1 (server-driven manifest + local-file:// playback).
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(TAG, "[web:${message.messageLevel()}] ${message.message()} @ ${message.sourceId()}:${message.lineNumber()}")
                return true
            }
        }
        webView.webViewClient = SignageWebClient(
            context = this,
            configStore = configStore,
            onLoadError = ::onLoadError,
            onPageLoaded = { loadedUrl ->
                lastRemoteUrl = loadedUrl
            },
        )
    }

    private fun installLongPressGesture() {
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    downX = event.x
                    downY = event.y
                    scheduleLongPress()
                }

                MotionEvent.ACTION_MOVE -> {
                    val movedTooFar =
                        abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
                    if (movedTooFar) {
                        cancelLongPress()
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    cancelLongPress()
                }
            }
            false
        }
    }

    private fun scheduleLongPress() {
        cancelLongPress()
        longPressRunnable = Runnable {
            longPressTriggered = true
            showSettingsDialog()
        }.also {
            longPressHandler.postDelayed(it, LONG_PRESS_TIMEOUT_MS)
        }
    }

    private fun cancelLongPress() {
        longPressRunnable?.let(longPressHandler::removeCallbacks)
        longPressRunnable = null
    }

    private fun showSettingsDialog() {
        val options = arrayOf(
            getString(R.string.dialog_change_url),
            getString(R.string.dialog_reload),
            getString(R.string.dialog_advanced),
            getString(R.string.dialog_cancel),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> {
                        startActivity(SetupActivity.createIntent(this, true))
                        finish()
                    }

                    1 -> {
                        reloadCurrentContent()
                        Toast.makeText(this, R.string.reload_requested, Toast.LENGTH_SHORT).show()
                    }

                    2 -> showAdvancedDialog()

                    else -> dialog.dismiss()
                }
            }
            .setOnDismissListener {
                enterImmersiveMode()
            }
            .show()
    }

    /** Decision 2B (PR #5): factory reset is hidden one level below
     *  the main settings menu so a passing customer or untrained
     *  staff can't trip it from the long-press dialog directly.
     *  Anyone with intent (the shop owner) finds it via Advanced
     *  → Factory reset → confirm. */
    private fun showAdvancedDialog() {
        val options = arrayOf(
            getString(R.string.dialog_factory_reset),
            getString(R.string.dialog_cancel),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_advanced)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> confirmFactoryReset()
                    else -> dialog.dismiss()
                }
            }
            .setOnDismissListener { enterImmersiveMode() }
            .show()
    }

    private fun confirmFactoryReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.factory_reset_confirm_title)
            .setMessage(R.string.factory_reset_confirm_body)
            .setPositiveButton(R.string.factory_reset_confirm_yes) { _, _ ->
                performFactoryReset()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .setOnDismissListener { enterImmersiveMode() }
            .show()
    }

    private fun performFactoryReset() {
        // Wipe local state. ``device_id`` is intentionally NOT wiped
        // — it's derived from ANDROID_ID, so the next announce arrives
        // with the same id and the server keeps the existing tenant
        // mapping. We just reset the secret + pair_code so the
        // operator goes through a fresh adoption.
        secretStore.clear()
        configStore.pairCode = ""
        Toast.makeText(this, R.string.factory_reset_done, Toast.LENGTH_LONG).show()
        // Re-announce immediately so the server mints a new pair_code
        // and the on-screen waiting page can render it without making
        // the operator wait for the next heartbeat tick.
        announcePairCodeIfMissing()
        // Drop the WebView back to the unbound waiting page.
        val pendingUrl = "${configStore.normalizeServerUrl(configStore.serverUrl)}/display/new"
        lastRemoteUrl = pendingUrl
        if (::webView.isInitialized) {
            webView.loadUrl(pendingUrl)
        }
    }

    private fun loadInitialContent() {
        configStore.failedLoadCount = 0
        webView.loadUrl(lastRemoteUrl)
    }

    private fun reloadCurrentContent() {
        configStore.failedLoadCount = 0
        val targetUrl = if (lastRemoteUrl.startsWith("http")) lastRemoteUrl else buildDisplayUrl(
            configStore.serverUrl,
        )
        webView.loadUrl(targetUrl)
    }

    private fun buildDisplayUrl(serverUrl: String): String {
        val base = "${configStore.normalizeServerUrl(serverUrl)}/display/new"
        // Render the operator-facing 4-digit pair_code on the kiosk so
        // the operator can match the row in /screens/pair against the
        // screen in front of them. Empty string ⇒ first boot before
        // announce returned, or this device just got unbound; the page
        // gracefully omits the big code badge in that case.
        val code = configStore.pairCode
        return if (code.isNotBlank()) "$base?pair_code=$code" else base
    }

    private fun announcePairCodeIfMissing() {
        if (configStore.pairCode.isNotBlank()) {
            // SetupActivity may have already finished its async announce
            // and stored a fresh pair_code, but onCreate's loadInitialContent
            // ran before that completed — so the WebView is on a /display/new
            // URL that has no ?pair_code= and the operator stares at a blank
            // waiting screen until the next heartbeat tick. Force a refresh
            // here so the code shows up immediately.
            // (v2.1.1 hotfix.)
            reloadPendingPageWithPairCode(configStore.pairCode)
            return
        }
        val serverUrl = configStore.serverUrl
        if (serverUrl.isBlank()) {
            return
        }
        val displaySize = DisplayInfo.resolution(this)
        Thread {
            val result = AnnouncementClient.announce(
                serverUrl = serverUrl,
                deviceId = deviceIdentity.deviceId,
                deviceLabel = deviceIdentity.deviceLabel,
                appVersion = BuildConfig.VERSION_NAME,
                displaySize = displaySize,
            )
            val pairCode = result.pairCode
            if (result.ok && !pairCode.isNullOrBlank()) {
                configStore.pairCode = pairCode
                runOnUiThread { reloadPendingPageWithPairCode(pairCode) }
            }
            // PR #5: capture device_secret on the announce that
            // follows merchant adoption. Quietly no-op if the response
            // doesn't carry one — the server only emits it on the
            // first post-adoption announce.
            result.deviceSecret?.let { secret ->
                if (secretStore.isAvailable) {
                    secretStore.secret = secret
                }
            }
        }.apply {
            isDaemon = true
            name = "announce-pair-code"
            start()
        }
    }

    private fun reloadPendingPageWithPairCode(pairCode: String) {
        if (pairCode.isBlank() || !::webView.isInitialized) {
            return
        }
        val currentUrl = webView.url ?: lastRemoteUrl
        if (!currentUrl.contains("/display/new")) {
            return
        }
        val targetUrl = buildDisplayUrl(configStore.serverUrl)
        if (targetUrl != currentUrl) {
            lastRemoteUrl = targetUrl
            webView.loadUrl(targetUrl)
        }
    }

    private fun onLoadError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun installCrashHandlerIfNeeded() {
        if (crashHandlerInstalled) {
            return
        }

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception. Scheduling restart.", throwable)
            scheduleRestart()
            previousHandler?.uncaughtException(thread, throwable) ?: run {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
        crashHandlerInstalled = true
    }

    private fun scheduleRestart() {
        val restartIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            restartIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = getSystemService(AlarmManager::class.java)
        alarmManager?.setExact(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + CRASH_RESTART_DELAY_MS,
            pendingIntent,
        )
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val LONG_PRESS_TIMEOUT_MS = 5_000L
        private const val CRASH_RESTART_DELAY_MS = 1_500L

        @Volatile
        private var crashHandlerInstalled = false
    }
}
