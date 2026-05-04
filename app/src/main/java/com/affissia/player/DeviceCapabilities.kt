package com.affissia.player

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.webkit.WebView
import org.json.JSONObject

object DeviceCapabilities {
    fun collect(context: Context): JSONObject {
        val webViewPackage = currentWebViewPackage(context)
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER.orEmpty())
            put("model", Build.MODEL.orEmpty())
            put("device", Build.DEVICE.orEmpty())
            put("android_sdk", Build.VERSION.SDK_INT)
            put("android_version", Build.VERSION.RELEASE.orEmpty())
            put("webview_package", webViewPackage?.packageName ?: JSONObject.NULL)
            put("webview_version", webViewPackage?.versionName ?: JSONObject.NULL)
            put("webview_version_code", webViewPackage?.versionCodeCompat() ?: JSONObject.NULL)
        }
    }

    private fun currentWebViewPackage(context: Context): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WebView.getCurrentWebViewPackage()
            } else {
                knownLegacyWebViewPackages.firstNotNullOfOrNull { packageName ->
                    try {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(packageName, 0)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }

    private fun PackageInfo.versionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
    }

    private val knownLegacyWebViewPackages = listOf(
        "com.google.android.webview",
        "com.android.webview",
        "com.android.chrome",
    )
}
