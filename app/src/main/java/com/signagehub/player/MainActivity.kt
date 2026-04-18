package com.signagehub.player

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
    private lateinit var webView: WebView
    private var wakeLock: PowerManager.WakeLock? = null
    private var longPressRunnable: Runnable? = null
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressTriggered = false
    private var downX = 0f
    private var downY = 0f
    private var lastRemoteUrl: String = ""
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installCrashHandlerIfNeeded()
        configStore = ConfigStore(this)
        if (configStore.serverUrl.isBlank()) {
            startActivity(SetupActivity.createIntent(this))
            finish()
            return
        }

        lastRemoteUrl = buildDisplayUrl(configStore.serverUrl)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.web_view)

        configureWindow()
        configureWakeLock()
        configureWebView()
        installLongPressGesture()
        loadInitialContent()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
        }
        enterImmersiveMode()
        acquireWakeLock()
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
        }
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

                    else -> dialog.dismiss()
                }
            }
            .setOnDismissListener {
                enterImmersiveMode()
            }
            .show()
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
        return "${configStore.normalizeServerUrl(serverUrl)}/display/new"
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
