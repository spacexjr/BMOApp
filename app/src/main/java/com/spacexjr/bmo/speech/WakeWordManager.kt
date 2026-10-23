package com.spacexjr.bmo.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.text.Normalizer

/**
 * Local wake-word detector. It uses a Vosk grammar constrained to BMO's name,
 * not a cloud service. Silence is gated by RMS before frames reach the
 * recogniser to keep CPU use down. This is an honest, lightweight equivalent:
 * accuracy is lower than a dedicated neural keyword spotter and it runs while
 * the app is in the foreground.
 */
class WakeWordManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var record: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var lastTrigger = 0L

    var onWakeWord: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start(modelProvider: VoskModelProvider) {
        if (running) return
        if (!hasMicrophonePermission()) {
            onError?.invoke("Permissão de microfone necessária para a palavra de ativação.")
            return
        }
        running = true
        paused = false
        job = scope.launch(Dispatchers.IO) {
            var recognizer: Recognizer? = null
            var audio: AudioRecord? = null
            try {
                val model: Model = modelProvider.get()
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), GRAMMAR)
                recognizer.setWords(false)
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(BUFFER_SAMPLES * 4)
                audio = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
                record = audio
                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Microfone indisponível para a palavra de ativação.")
                }
                audio.startRecording()
                main.post { onStatus?.invoke("Aguardando “Ei, BMO”") }
                val buffer = ShortArray(BUFFER_SAMPLES)
                while (running && scope.isActive) {
                    val read = audio.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    if (paused) continue
                    val level = rms(buffer, read)
                    if (level < SILENCE_RMS) continue
                    val final = recognizer.acceptWaveForm(buffer, read)
                    val text = if (final) {
                        extract(recognizer.result, "text")
                    } else {
                        extract(recognizer.partialResult, "partial")
                    }
                    if (matches(text)) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTrigger > COOLDOWN_MS) {
                            lastTrigger = now
                            main.post {
                                onStatus?.invoke("Palavra de ativação detectada")
                                onWakeWord?.invoke()
                            }
                            return@launch
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "wake word error", t)
                if (running) main.post { onError?.invoke("Palavra de ativação falhou: ${t.message}") }
            } finally {
                running = false
                record = null
                try { audio?.stop() } catch (_: Throwable) {}
                try { audio?.release() } catch (_: Throwable) {}
                try { recognizer?.close() } catch (_: Throwable) {}
            }
        }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Throwable) {}
        job?.cancel()
        job = null
    }

    val isRunning: Boolean get() = running

    private fun matches(raw: String): Boolean {
        if (raw.isBlank()) return false
        val text = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return text.contains("ei bmo") ||
            text.contains("eibmo") ||
            text.contains("hey bmo") ||
            text.contains("oi bmo") ||
            text.contains("ei be emo") ||
            text.contains("ei bemo")
    }

    private fun extract(json: String?, key: String): String = try {
        JSONObject(json ?: "{}").optString(key, "")
    } catch (_: Throwable) {
        ""
    }

    private fun rms(buffer: ShortArray, count: Int): Double {
        if (count == 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / count)
    }

    companion object {
        private const val TAG = "BmoWakeWord"
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SAMPLES = 1600
        private const val SILENCE_RMS = 380.0
        private const val COOLDOWN_MS = 2500L
        private const val GRAMMAR = """["ei bmo", "eibmo", "hey bmo", "oi bmo", "ei bemo", "[unk]"]"""
    }
}
