// Owns one granted microphone session from route preparation and tones through Opus transmission.
package app.zenptt

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean

enum class AudioCaptureFailure {
    RouteUnavailable,
    RecorderUnavailable,
    RecorderRead,
    Codec,
    Transport,
}

internal class AudioFramePacer(
    private val frameDurationMs: Long = AudioConstants.FRAME_DURATION_MS.toLong(),
) {
    private var nextFrameAtMs: Long? = null

    fun delayBeforeFrame(nowMs: Long): Long {
        val targetMs = nextFrameAtMs ?: nowMs
        val waitMs = (targetMs - nowMs).coerceAtLeast(0)
        nextFrameAtMs = maxOf(targetMs, nowMs) + frameDurationMs
        return waitMs
    }
}

internal class AudioCapture(
    private val routeController: AudioRouteController,
    private val recordDiagnostic: (String) -> Unit,
    private val observeLifecycle: (AudioLifecycleObservation) -> Unit = {},
) {
    private val active = AtomicBoolean(false)
    private val granted = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    private val recorderLock = Any()
    private var activeRecorder: AudioRecord? = null

    fun prepareForRequest() {
        granted.set(false)
        active.set(true)
    }

    fun grant() {
        granted.set(true)
    }

    fun requestStop() {
        active.set(false)
        granted.set(false)
        synchronized(recorderLock) { runCatching { activeRecorder?.stop() } }
    }

    fun isCapturing(): Boolean = capturing.get()

    suspend fun run(
        sender: (ByteArray) -> Boolean,
        grantToneCount: () -> Int,
    ): AudioCaptureFailure? {
        while (currentCoroutineContext().isActive && active.get() && !granted.get()) {
            delay(GRANT_POLL_INTERVAL_MS)
        }
        if (!currentCoroutineContext().isActive || !active.get()) return null

        val minimum = AudioRecord.getMinBufferSize(
            AudioConstants.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioConstants.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum, AudioConstants.PCM_BYTES_PER_FRAME * 2))
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recordDiagnostic("capture recorder_not_initialized min_buffer=$minimum")
            recorder.release()
            active.set(false)
            return AudioCaptureFailure.RecorderUnavailable
        }
        val inputDevice = routeController.preferredInput()
        if (inputDevice == null || !recorder.setPreferredDevice(inputDevice)) {
            recorder.release()
            return AudioCaptureFailure.RouteUnavailable
        }
        if (!active.get()) {
            recorder.release()
            return null
        }

        synchronized(recorderLock) { activeRecorder = recorder }
        capturing.set(true)
        val recorderSampleRate = recorder.sampleRate
        var captureStartedAtMs: Long? = null
        var sentFrames = 0L
        return try {
            OpusEncoder().use { encoder ->
                warmUpRecorder(recorder)
                if (!currentCoroutineContext().isActive || !active.get()) return null
                if (!routeController.inputMatches(recorder.routedDevice)) return AudioCaptureFailure.RouteUnavailable
                playGrantTone(recorder, grantToneCount())
                if (!currentCoroutineContext().isActive || !active.get()) return null
                recordDiagnostic(
                    "capture granted buffered=0 routed=${routeController.deviceSummary(recorder.routedDevice)}",
                )

                val pcm = ByteArray(AudioConstants.PCM_BYTES_PER_FRAME)
                val pacer = AudioFramePacer()
                captureStartedAtMs = SystemClock.elapsedRealtime()
                while (currentCoroutineContext().isActive && active.get()) {
                    val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (!active.get()) return null
                    if (!routeController.inputMatches(recorder.routedDevice)) return AudioCaptureFailure.RouteUnavailable
                    if (count < 0) return AudioCaptureFailure.RecorderRead
                    if (count != pcm.size) continue
                    if (!currentCoroutineContext().isActive || !active.get()) break
                    val paceDelayMs = pacer.delayBeforeFrame(SystemClock.elapsedRealtime())
                    if (paceDelayMs > 0) delay(paceDelayMs)
                    if (!currentCoroutineContext().isActive || !active.get()) break
                    encoder.encode(pcm).forEach { packet ->
                        if (!active.get()) return null
                        if (!sender(packet)) return AudioCaptureFailure.Transport
                        sentFrames += 1
                    }
                }
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordDiagnostic("capture failure ${error.javaClass.simpleName}:${error.message}")
            AudioCaptureFailure.Codec
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            observeLifecycle(AudioLifecycleObservation.CaptureReleased)
            synchronized(recorderLock) {
                if (activeRecorder === recorder) activeRecorder = null
            }
            capturing.set(false)
            active.set(false)
            granted.set(false)
            val elapsedMs = captureStartedAtMs?.let {
                (SystemClock.elapsedRealtime() - it).coerceAtLeast(0)
            } ?: 0
            recordDiagnostic(
                "capture stopped frames=$sentFrames elapsed=${elapsedMs}ms " +
                    "recorder_rate=$recorderSampleRate",
            )
        }
    }

    private fun warmUpRecorder(recorder: AudioRecord) {
        if (!active.get()) return
        val startedAt = SystemClock.elapsedRealtime()
        val pcm = ByteArray(AudioConstants.PCM_BYTES_PER_FRAME)
        observeLifecycle(AudioLifecycleObservation.CaptureStarting)
        recorder.startRecording()
        while (active.get() && SystemClock.elapsedRealtime() - startedAt < CAPTURE_WARMUP_MS) {
            recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
        }
        recordDiagnostic(
            "capture warmed_up duration=${SystemClock.elapsedRealtime() - startedAt}ms " +
                "routed=${routeController.deviceSummary(recorder.routedDevice)}",
        )
    }

    private fun playGrantTone(recorder: AudioRecord, count: Int) {
        recordDiagnostic(
            "grant_tone started count=$count current=${routeController.currentDeviceSummary()}",
        )
        val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
        try {
            val discard = ByteArray(AudioConstants.PCM_BYTES_PER_FRAME)
            repeat(count) { index ->
                if (!active.get()) return
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, GRANT_TONE_DURATION_MS)
                drainRecorder(recorder, discard, GRANT_TONE_DURATION_MS)
                tone.stopTone()
                if (!active.get()) return
                if (index < count - 1) drainRecorder(recorder, discard, GRANT_TONE_GAP_MS)
            }
        } finally {
            tone.release()
            recordDiagnostic("grant_tone ended")
        }
    }

    private fun drainRecorder(recorder: AudioRecord, buffer: ByteArray, durationMs: Int) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (active.get() && SystemClock.elapsedRealtime() < deadline) {
            recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        }
    }

    private companion object {
        const val GRANT_POLL_INTERVAL_MS = 5L
        const val GRANT_TONE_DURATION_MS = 100
        const val GRANT_TONE_GAP_MS = 100
        const val CAPTURE_WARMUP_MS = 100L
    }
}
