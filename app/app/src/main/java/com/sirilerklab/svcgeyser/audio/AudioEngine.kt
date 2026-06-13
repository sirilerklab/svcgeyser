package com.sirilerklab.svcgeyser.audio

import android.annotation.SuppressLint
import android.media.audiofx.AcousticEchoCanceler
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import java.util.concurrent.atomic.AtomicInteger

// Concentus JAR required at app/app/libs/Concentus.jar.
// Download: https://github.com/lostromb/concentus/releases/tag/v1.0-java
class AudioEngine(private val onUplinkFrame: (ByteArray) -> Unit) {

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 960       // 20 ms at 48 kHz mono
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val MAX_JITTER_FRAMES = 12    // 240 ms overflow guard
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

    private val seqNum = AtomicInteger(0)
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true

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
            .also { it.play() }

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
        synchronized(jitterLock) { jitterBuffer.clear() }
    }

    private fun captureLoop() {
        val pcm = ShortArray(FRAME_SAMPLES)
        val encodeBuf = ByteArray(1275) // RFC 6716 §3.1 max single frame
        while (running && !Thread.currentThread().isInterrupted) {
            val read = audioRecord?.read(pcm, 0, FRAME_SAMPLES) ?: break
            if (read < FRAME_SAMPLES) continue
            try {
                val len = encoder.encode(pcm, 0, FRAME_SAMPLES, encodeBuf, 0, encodeBuf.size)
                if (len > 0) onUplinkFrame(buildUplinkFrame(encodeBuf, len))
            } catch (_: Exception) { /* skip malformed frame */ }
        }
    }

    private fun playbackLoop() {
        val silence = ShortArray(FRAME_SAMPLES)
        while (running && !Thread.currentThread().isInterrupted) {
            val frame = synchronized(jitterLock) {
                if (jitterBuffer.isEmpty()) null else jitterBuffer.removeFirst()
            }
            // AudioTrack.write() blocks until the frame is queued — this paces the loop naturally.
            audioTrack?.write(frame ?: silence, 0, FRAME_SAMPLES)
        }
    }

    // Called from the AppViewModel coroutine for each binary downlink frame.
    fun handleDownlinkFrame(bytes: ByteArray) {
        val pcm = decodeDownlink(bytes) ?: return
        synchronized(jitterLock) {
            if (jitterBuffer.size < MAX_JITTER_FRAMES) jitterBuffer.addLast(pcm)
        }
    }

    // Uplink wire: [u8 0x01][u16 seq BE][opus payload]
    private fun buildUplinkFrame(buf: ByteArray, len: Int): ByteArray {
        val seq = seqNum.getAndIncrement()
        return ByteArray(3 + len).also { f ->
            f[0] = 0x01
            f[1] = (seq shr 8).toByte()
            f[2] = (seq and 0xFF).toByte()
            buf.copyInto(f, 3, 0, len)
        }
    }

    // Downlink wire (plugin AudioFrameSerializer):
    //   [u8 0x02][16B senderUuid][u8 flags][f32 x,y,z,distance]?[opus]
    //   flag 0x04 = HAS_SPATIAL → 16-byte spatial header present
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
