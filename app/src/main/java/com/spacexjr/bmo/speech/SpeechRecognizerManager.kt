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
import org.vosk.Recognizer

class SpeechRecognizerManager(
    private val context: Context,
    private val models: VoskModelProvider,
    private val scope: CoroutineScope,
) {
    data class Result(val text: String)
    data class Error(val message: String)

    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    @Volatile private var running = false
    private var audioRecord: AudioRecord? = null

    var onPartial: ((String) -> Unit)? = null
    var onResult: ((Result) -> Unit)? = null
    var onError: ((Error) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    val isListening: Boolean get() = running

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running) return
        if (!hasMicrophonePermission()) {
            postError("Permissão de microfone não concedida.")
            return
        }
        running = true
        job = scope.launch(Dispatchers.IO) {
            var recognizer: Recognizer? = null
            var record: AudioRecord? = null
            try {
                val model = models.get { progress ->
                    main.post { onPartial?.invoke(progress.label + " ${progress.percent}%") }
                }
                if (!running) return@launch
                main.post { onReady?.invoke() }
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
                recognizer.setWords(false)

                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(BUFFER_BYTES * 2)
                record = AudioRecord.Builder()
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
                audioRecord = record
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Não foi possível inicializar o microfone.")
                }
                record.startRecording()
                val buffer = ShortArray(BUFFER_SAMPLES)
                val startedAt = SystemClock.elapsedRealtime()
                var lastVoiceAt = 0L
                var heardSpeech = false
                var lastPartial = ""

                while (running && scope.isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                            throw IllegalStateException("Falha ao ler o microfone (código $read).")
                        }
                        continue
                    }
                    val rms = rms(buffer, read)
                    if (rms > VOICE_RMS) {
                        lastVoiceAt = SystemClock.elapsedRealtime()
                        heardSpeech = true
                    }
                    if (recognizer.acceptWaveForm(buffer, read)) {
                        val text = extractText(recognizer.result)
                        if (text.isNotBlank()) {
                            postResult(text.trim())
                            return@launch
                        }
                    } else {
                        val partial = extractText(recognizer.partialResult, "partial")
                        if (partial.isNotBlank() && partial != lastPartial) {
                            lastPartial = partial
                            main.post { onPartial?.invoke(partial) }
                        }
                    }
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val silence = SystemClock.elapsedRealtime() - lastVoiceAt
                    if (heardSpeech && silence > SILENCE_END_MS) {
                        val finalText = extractText(recognizer.finalResult)
                        if (finalText.isNotBlank()) {
                            postResult(finalText.trim())
                        } else if (lastPartial.isNotBlank()) {
                            postResult(lastPartial.trim())
                        } else {
                            postError("Não consegui entender. Tente falar um pouco mais perto do microfone.")
                        }
                        return@launch
                    }
                    if (!heardSpeech && elapsed > NO_SPEECH_TIMEOUT_MS) {
                        postError("Não ouvi nada. Toque no rosto para tentar novamente.")
                        return@launch
                    }
                    if (elapsed > MAX_LISTEN_MS) {
                        val finalText = extractText(recognizer.finalResult)
                        if (finalText.isNotBlank()) postResult(finalText.trim())
                        else postError("A fala demorou demais. Tente uma frase mais curta.")
                        return@launch
                    }
                }
            } catch (t: Throwable) {
                if (running) postError("Reconhecimento de voz falhou: ${t.message ?: t.javaClass.simpleName}")
                Log.e(TAG, "ASR error", t)
            } finally {
                running = false
                audioRecord = null
                try {
                    record?.stop()
                } catch (_: Throwable) {
                }
                try {
                    record?.release()
                } catch (_: Throwable) {
                }
                try {
                    recognizer?.close()
                } catch (_: Throwable) {
                }
                main.post {
                    onFinished?.invoke()
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        job?.cancel()
    }

    private fun postResult(text: String) {
        running = false
        main.post { onResult?.invoke(Result(text)) }
    }

    private fun postError(message: String) {
        running = false
        main.post { onError?.invoke(Error(message)) }
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

    private fun extractText(json: String?, key: String = "text"): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString(key, "")
        } catch (_: Throwable) {
            ""
        }
    }

    companion object {
        private const val TAG = "BmoSpeechRecognizer"
        const val SAMPLE_RATE = 16000
        private const val BUFFER_SAMPLES = 1600
        private const val BUFFER_BYTES = BUFFER_SAMPLES * 2
        private const val VOICE_RMS = 420.0
        private const val SILENCE_END_MS = 1250L
        private const val NO_SPEECH_TIMEOUT_MS = 6000L
        private const val MAX_LISTEN_MS = 15000L
    }
}
