package com.sirilerklab.svcgeyser.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sirilerklab.svcgeyser.MainActivity
import com.sirilerklab.svcgeyser.R

class VoiceService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "svcgeyser_voice"
        private const val EXTRA_VOICE_ACTIVE = "voiceActive"

        /** Call when WebSocket connects (auth_ok). Keeps process alive in background. */
        fun startConnected(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceService::class.java).putExtra(EXTRA_VOICE_ACTIVE, false),
            )
        }

        /** Call when mic/audio starts. Updates notification to "voice active". */
        fun startVoice(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceService::class.java).putExtra(EXTRA_VOICE_ACTIVE, true),
            )
        }

        /** Call on explicit disconnect or ViewModel cleared. */
        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val nm: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Voice chat", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "SVCGeyser voice bridge" }
        nm.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val voiceActive = intent?.getBooleanExtra(EXTRA_VOICE_ACTIVE, false) ?: false
        val notification = buildNotification(voiceActive)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Refresh notification if service was already running (mode changed).
        nm.notify(NOTIFICATION_ID, notification)

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "svcgeyser::bridge",
            ).apply { acquire(4 * 60 * 60 * 1000L) }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(voiceActive: Boolean): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = if (voiceActive)
            "Voice chat active" to "SVCGeyser is bridging your voice"
        else
            "Connected to SVCGeyser" to "Tap to return to the app"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
