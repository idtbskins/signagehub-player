package com.affissia.player

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

object DisplayInfo {
    fun resolution(context: Context): Pair<Int, Int>? {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return null

        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay ?: return null
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
        }

        if (width <= 0 || height <= 0) {
            return null
        }
        return width to height
    }
}
