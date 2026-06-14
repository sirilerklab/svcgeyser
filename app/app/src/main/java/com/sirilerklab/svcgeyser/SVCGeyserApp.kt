package com.sirilerklab.svcgeyser

import android.app.Application
import com.sirilerklab.svcgeyser.diag.CrashReporter

/**
 * Custom Application so the crash reporter is installed before any Activity or Service starts —
 * crashes can happen during voice sessions driven by services, when MainActivity is not foreground.
 */
class SVCGeyserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
