package com.affissia.player

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class SignageWebClient(
    private val context: Context,
    private val configStore: ConfigStore,
    private val onLoadError: (String) -> Unit,
    private val onPageLoaded: (String) -> Unit,
) : WebViewClient() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingReload: Runnable? = null

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        cancelPendingReload()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val resolvedUrl = url.orEmpty()
        if (resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://")) {
            configStore.failedLoadCount = 0
            configStore.lastLoadedAt = System.currentTimeMillis()
            onPageLoaded(resolvedUrl)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return false
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?,
    ) {
        Log.w(TAG, "SSL error while loading signage content: $error")
        // TODO: replace permissive SSL handling with certificate pinning.
        if (BuildConfig.DEBUG || configStore.trustSelfSigned) {
            handler?.proceed()
            return
        }

        handler?.cancel()
        onLoadError(context.getString(R.string.error_ssl_message))
        view?.loadDataWithBaseURL(
            null,
            buildErrorHtml(
                title = context.getString(R.string.error_ssl_title),
                message = context.getString(R.string.error_ssl_message),
                details = error?.toString().orEmpty(),
            ),
            "text/html",
            "utf-8",
            null,
        )
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (view == null || request?.isForMainFrame != true) {
            return
        }

        val failedCount = configStore.failedLoadCount + 1
        configStore.failedLoadCount = failedCount
        val description = error?.description?.toString().orEmpty().ifBlank {
            context.getString(R.string.error_unknown)
        }

        Log.w(TAG, "WebView load failed ($failedCount/$MAX_FAILURES_BEFORE_ERROR): $description")
        if (failedCount < MAX_FAILURES_BEFORE_ERROR) {
            onLoadError(
                context.getString(
                    R.string.error_retry_toast,
                    failedCount,
                    MAX_FAILURES_BEFORE_ERROR - 1,
                ),
            )
            scheduleReload(view)
            return
        }

        onLoadError(context.getString(R.string.error_screen_message))
        view.loadDataWithBaseURL(
            null,
            buildErrorHtml(
                title = context.getString(R.string.error_screen_title),
                message = context.getString(R.string.error_screen_message),
                details = description,
            ),
            "text/html",
            "utf-8",
            null,
        )
    }

    private fun scheduleReload(view: WebView) {
        cancelPendingReload()
        pendingReload = Runnable {
            view.reload()
        }.also {
            mainHandler.postDelayed(it, RETRY_DELAY_MS)
        }
    }

    private fun cancelPendingReload() {
        pendingReload?.let(mainHandler::removeCallbacks)
        pendingReload = null
    }

    private fun buildErrorHtml(title: String, message: String, details: String): String {
        val safeTitle = TextUtils.htmlEncode(title)
        val safeMessage = TextUtils.htmlEncode(message)
        val safeDetails = TextUtils.htmlEncode(details)
        val safeHint = TextUtils.htmlEncode(context.getString(R.string.error_screen_tip))

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>$safeTitle</title>
              <style>
                body {
                  margin: 0;
                  min-height: 100vh;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  background: #050505;
                  color: #ffffff;
                  font-family: sans-serif;
                }
                .card {
                  max-width: 860px;
                  padding: 48px;
                  text-align: center;
                }
                h1 {
                  margin: 0 0 16px;
                  font-size: 44px;
                  color: #ff5c5c;
                }
                p {
                  margin: 0 0 12px;
                  font-size: 22px;
                  line-height: 1.5;
                }
                .details {
                  margin-top: 20px;
                  color: #ffb4b4;
                  font-size: 18px;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>$safeTitle</h1>
                <p>$safeMessage</p>
                <p>$safeHint</p>
                <p class="details">$safeDetails</p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "SignageWebClient"
        private const val RETRY_DELAY_MS = 3_000L
        private const val MAX_FAILURES_BEFORE_ERROR = 3
    }
}
