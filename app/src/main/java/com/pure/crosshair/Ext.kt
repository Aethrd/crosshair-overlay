package com.pure.crosshair

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

fun Context.windowManager(): WindowManager =
    getSystemService(Context.WINDOW_SERVICE) as WindowManager

/** Real screen size in pixels, including the area behind the system bars. */
fun Context.screenSize(): Point {
    val wm = windowManager()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = wm.currentWindowMetrics.bounds
        Point(bounds.width(), bounds.height())
    } else {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        Point(metrics.widthPixels, metrics.heightPixels)
    }
}
