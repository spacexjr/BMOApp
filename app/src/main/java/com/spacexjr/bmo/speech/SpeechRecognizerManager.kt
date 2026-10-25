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
    /** Chamado quando o Vosk confirma que há fala humana no áudio. */
    var onSpeechDetected: (() -> Unit)? = null

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
                var lastSpeechAt = 0L
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

                    val now = SystemClock.elapsedRealtime()
                    var confirmedFinal = ""

                    if (recognizer.acceptWaveForm(buffer, read)) {
                        confirmedFinal = extractText(recognizer.result).trim()
                        if (isUsableSpeech(confirmedFinal)) {
                            postResult(confirmedFinal)
                            return@launch
                        }
                    } else {
                        val partial = extractText(recognizer.partialResult, "partial").trim()
                        // O Vosk devolve texto parcial somente enquanto há fala
                        // reconhecível. É ele que define o início e a duração da
                        // fala; o volume do microfone não é usado como limiar.
                        if (isUsableSpeech(partial)) {
                            if (!heardSpeech) {
                                heardSpeech = true
                                main.post { onSpeechDetected?.invoke() }
                            }
                            if (partial != lastPartial) {
                                lastPartial = partial
                                // Só renova a janela de silêncio quando o Vosk
                                // produz uma nova hipótese; texto parcial repetido
                                // não deve manter o microfone aberto para sempre.
                                lastSpeechAt = now
                                main.post { onPartial?.invoke(partial) }
                            } else if (lastSpeechAt == 0L) {
                                lastSpeechAt = now
                            }
                        }
                    }

                    val silence = now - lastSpeechAt
                    if (heardSpeech && silence > SILENCE_END_MS) {
                        val finalText = extractText(recognizer.finalResult).trim()
                        val text = when {
                            isUsableSpeech(finalText) -> finalText
                            isUsableSpeech(lastPartial) -> lastPartial
                            else -> ""
                        }
                        if (text.isNotBlank()) {
                            postResult(text)
                        } else {
                            postError("Não consegui entender. Tente falar um pouco mais perto do microfone.")
                        }
                        return@launch
                    }
                    if (!heardSpeech && now - startedAt > NO_SPEECH_TIMEOUT_MS) {
                        postError("Não ouvi nada. Toque no rosto para tentar novamente.")
                        return@launch
                    }
                    if (now - startedAt > MAX_LISTEN_MS) {
                        val finalText = extractText(recognizer.finalResult).trim()
                        val text = when {
                            isUsableSpeech(finalText) -> finalText
                            isUsableSpeech(lastPartial) -> lastPartial
                            isUsableSpeech(confirmedFinal) -> confirmedFinal
                            else -> ""
                        }
                        if (text.isNotBlank()) postResult(text)
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

    private fun isUsableSpeech(text: String): Boolean {
        // "[unk]" é ruído/termo fora do vocabulário e não deve iniciar a escuta.
        val clean = text.replace("[unk]", " ", ignoreCase = true).trim()
        if (clean.length < 2) return false
        // Evita ruído curto e pontuação isolada.
        return clean.any { it.isLetterOrDigit() }
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
        private const val SILENCE_END_MS = 1100L
        private const val NO_SPEECH_TIMEOUT_MS = 6000L
        private const val MAX_LISTEN_MS = 15000L
    }
}
