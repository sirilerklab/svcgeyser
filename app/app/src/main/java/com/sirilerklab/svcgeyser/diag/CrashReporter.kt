package com.sirilerklab.svcgeyser.diag

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Captures uncaught exceptions to a file so they can be reviewed and uploaded on next launch
 * (the user has no easy access to adb/logcat). The default Android handler kills the process
 * with no visible dialog; we chain into it: write the trace, then delegate to the previous
 * handler so the OS still tears down normally.
 */
object CrashReporter {

    private const val TAG = "SVCGeyser.Crash"
    private const val FILE_NAME = "last_crash.log"

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { crashFile(appContext).writeText(throwable.stackTraceToString()) }
            Log.e(TAG, "uncaught on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns the saved crash trace (if any) and deletes the file. */
    fun consumeLastCrash(context: Context): String? {
        val file = crashFile(context)
        if (!file.exists()) return null
        return runCatching {
            val text = file.readText()
            file.delete()
            text.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun crashFile(context: Context) = File(context.filesDir, FILE_NAME)
}
