package com.sirilerklab.svcgeyser.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions to a file so crashes can be diagnosed without adb/logcat.
 *
 * The default uncaught-exception handler on Android terminates the process with no visible
 * dialog ("app closed instantly"). We chain into it: write the trace, then delegate to the
 * previous handler so the OS still tears down normally. On next launch the app reads and
 * shows the saved trace (see [consumeLastCrash]).
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
            runCatching { writeReport(appContext, thread, throwable) }
            // Never swallow the crash — let the OS handler terminate the process.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Append a non-fatal throwable (e.g. recovered audio-loop error) to the same log. */
    fun logNonFatal(context: Context, where: String, throwable: Throwable) {
        runCatching {
            val header = "${timestamp()} NON-FATAL [$where] on ${Thread.currentThread().name}\n"
            crashFile(context).appendText(header + throwable.stackTraceToString() + "\n\n")
        }
        Log.w(TAG, "non-fatal [$where]", throwable)
    }

    /** Returns the saved crash report (if any) and deletes the file. */
    fun consumeLastCrash(context: Context): String? {
        val file = crashFile(context)
        if (!file.exists()) return null
        return runCatching {
            val text = file.readText()
            file.delete()
            text.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val header = "${timestamp()} FATAL on ${thread.name}\n"
        crashFile(context).appendText(header + throwable.stackTraceToString() + "\n\n")
        Log.e(TAG, "uncaught on ${thread.name}", throwable)
    }

    private fun crashFile(context: Context) = File(context.filesDir, FILE_NAME)

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
