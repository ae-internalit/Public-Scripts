package com.doubletap.screenshot

import android.app.Application
import android.content.Context

/**
 * Records the last fatal error so the app can show it on next launch. Crashes
 * here come from an accessibility service running on someone else's device,
 * where there is no other way to see a stack trace.
 */
class ScreenshotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                getSharedPreferences(ScreenshotService.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(ScreenshotService.KEY_LAST_CRASH, describe(error))
                    .commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun describe(error: Throwable): String = buildString {
        append(error.toString()).append('\n')
        error.stackTrace.take(14).forEach { append("  at ").append(it).append('\n') }
        var cause = error.cause
        var depth = 0
        while (cause != null && depth < 3) {
            append("Caused by: ").append(cause.toString()).append('\n')
            cause.stackTrace.take(8).forEach { append("  at ").append(it).append('\n') }
            cause = cause.cause
            depth++
        }
    }
}
