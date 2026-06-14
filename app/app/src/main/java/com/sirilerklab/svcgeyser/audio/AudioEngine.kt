package com.sirilerklab.svcgeyser.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import java.util.concurrent.atomic.AtomicInteger

// Concentus JAR required at app/app/libs/Concentus.jar.
// Download: https://github.com/lostromb/concentus/releases/tag/v1.0-java
class AudioEngine(
    private val context: Context,
    private val onUplinkFrame: (ByteArray) -> Unit,
) {

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 960       // 20 ms at 48 kHz mono
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val MAX_JITTER_FRAMES = 12    // 240 ms overflow guard
        /** RMS threshold for 16-bit mono PCM — frames below this are treated as silence. */
        private const val SPEECH_RMS_THRESHOLD = 600.0
        /** Keep sending briefly after speech ends to avoid clipped syllables. */
        private const val SPEECH_HANG_MS = 100L
    }

    private val encoder = OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
    private val decoder = OpusDecoder(SAMPLE_RATE, 1)

    private val jitterBuffer = ArrayDeque<ShortArray>()
    private val jitterLock = Any()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val seqNum = AtomicInteger(0)
    @Volatile private var running = false
    @Volatile var isMuted = false
    @Volatile var isDeafened = false
    @Volatile var speakerOn = false

    @Volatile private var speechHangUntilMs = 0L

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestVoiceFocus()
        applySpeakerRoute()

        val minRecBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minRecBuf, FRAME_BYTES * 4),
        ).also { ar ->
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(ar.audioSessionId)
                    ?.also { it.enabled = true }
            }
            ar.startRecording()
        }

        createAndStartAudioTrack()

        captureThread = Thread(::captureLoop, "svcgeyser-capture").also { it.start() }
        playbackThread = Thread(::playbackLoop, "svcgeyser-playback").also { it.start() }
    }

    fun stop() {
        running = false
        captureThread?.interrupt()
        playbackThread?.interrupt()
        captureThread?.join(500)
        playbackThread?.join(500)
        captureThread = null
        playbackThread = null
        echoCanceler?.release()
        echoCanceler = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        abandonVoiceFocus()
        audioManager = null
        synchronized(jitterLock) { jitterBuffer.clear() }
    }

    fun applySpeakerRoute(enabled: Boolean) {
        speakerOn = enabled
        applySpeakerRoute()
    }

    private fun requestVoiceFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            am.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonVoiceFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private fun createAndStartAudioTrack() {
        val minPlayBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minPlayBuf, FRAME_BYTES * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.play()
                routePlaybackDevice(audioManager!!)
            }
    }

    @Suppress("DEPRECATION")
    private fun applySpeakerRoute() {
        val am = audioManager ?: return
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isBluetoothScoOn = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
            val devices = am.availableCommunicationDevices
            val target = if (speakerOn) {
                devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else {
                devices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
                    ?: devices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                    ?: devices.find { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                    ?: devices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                    ?: devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            if (target != null) {
                am.setCommunicationDevice(target)
            }
        }
        // Always set speakerphone as fallback — some devices ignore setCommunicationDevice alone.
        am.isSpeakerphoneOn = speakerOn

        routePlaybackDevice(am)
    }

    private fun routePlaybackDevice(am: AudioManager) {
        val track = audioTrack ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val target = if (speakerOn) {
            outputs.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            outputs.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
                ?: outputs.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                ?: outputs.find { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                ?: outputs.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
        if (target != null) {
            track.setPreferredDevice(target)
        }
    }

    private fun captureLoop() {
        val pcm = ShortArray(FRAME_SAMPLES)
        val encodeBuf = ByteArray(1275) // RFC 6716 §3.1 max single frame
        while (running && !Thread.currentThread().isInterrupted) {
            val read = audioRecord?.read(pcm, 0, FRAME_SAMPLES) ?: break
            if (read < FRAME_SAMPLES) continue
            if (isMuted) continue
            if (!shouldUplink(pcm)) continue
            try {
                val len = encoder.encode(pcm, 0, FRAME_SAMPLES, encodeBuf, 0, encodeBuf.size)
                if (len > 0) onUplinkFrame(buildUplinkFrame(encodeBuf, len))
            } catch (_: Exception) { /* skip malformed frame */ }
        }
    }

    private fun shouldUplink(pcm: ShortArray): Boolean {
        val now = System.currentTimeMillis()
        if (computeRms(pcm) >= SPEECH_RMS_THRESHOLD) {
            speechHangUntilMs = now + SPEECH_HANG_MS
            return true
        }
        return now < speechHangUntilMs
    }

    private fun computeRms(pcm: ShortArray): Double {
        var sum = 0.0
        for (s in pcm) {
            val v = s.toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / pcm.size)
    }

    private fun playbackLoop() {
        val silence = ShortArray(FRAME_SAMPLES)
        while (running && !Thread.currentThread().isInterrupted) {
            val frame = if (isDeafened) {
                null
            } else {
                synchronized(jitterLock) {
                    if (jitterBuffer.isEmpty()) null else jitterBuffer.removeFirst()
                }
            }
            audioTrack?.write(frame ?: silence, 0, FRAME_SAMPLES)
        }
    }

    fun handleDownlinkFrame(bytes: ByteArray) {
        if (isDeafened) return
        val pcm = decodeDownlink(bytes) ?: return
        synchronized(jitterLock) {
            if (jitterBuffer.size < MAX_JITTER_FRAMES) jitterBuffer.addLast(pcm)
        }
    }

    private fun buildUplinkFrame(buf: ByteArray, len: Int): ByteArray {
        val seq = seqNum.getAndIncrement()
        return ByteArray(3 + len).also { f ->
            f[0] = 0x01
            f[1] = (seq shr 8).toByte()
            f[2] = (seq and 0xFF).toByte()
            buf.copyInto(f, 3, 0, len)
        }
    }

    private fun decodeDownlink(bytes: ByteArray): ShortArray? {
        if (bytes.size < 18 || bytes[0] != 0x02.toByte()) return null
        val flags = bytes[17].toInt() and 0xFF
        val opusStart = if (flags and 0x04 != 0) 34 else 18
        if (bytes.size <= opusStart) return null
        val pcm = ShortArray(FRAME_SAMPLES)
        return try {
            decoder.decode(bytes, opusStart, bytes.size - opusStart, pcm, 0, FRAME_SAMPLES, false)
            pcm
        } catch (_: Exception) { null }
    }
}
