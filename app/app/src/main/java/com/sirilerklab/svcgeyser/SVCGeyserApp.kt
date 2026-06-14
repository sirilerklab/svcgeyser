package com.sirilerklab.svcgeyser

import android.app.Application
import com.sirilerklab.svcgeyser.diag.CrashReporter

/**
 * Custom Application so the crash reporter is installed before any Activity or Service
 * starts — the audio crash happens during voice sessions driven by services, when
 * MainActivity may not be in the foreground.
 */
class SVCGeyserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
