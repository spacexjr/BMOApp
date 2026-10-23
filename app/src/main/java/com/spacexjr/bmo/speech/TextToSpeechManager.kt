package com.spacexjr.bmo.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.spacexjr.bmo.data.LocalModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline neural speech synthesis using sherpa-onnx + Piper VITS (pt-BR Faber).
 * Audio is generated and rendered locally with AudioTrack; no text ever leaves
 * the device.
 */
class TextToSpeechManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object NotReady : Status
        data object Loading : Status
        data class Ready(val sampleRate: Int) : Status
        data class Error(val message: String) : Status
    }

    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow<Status>(Status.NotReady)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var tts: OfflineTts? = null
    private var prepareJob: Job? = null
    private var speakJob: Job? = null
    private var track: AudioTrack? = null
    private val stopped = AtomicBoolean(false)

    @Volatile var volume: Float = 1.0f
    @Volatile var speed: Float = 1.0f

    var onStart: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isSpeaking: Boolean get() = speakJob?.isActive == true

    fun prepare() {
        if (tts != null || prepareJob?.isActive == true) return
        prepareJob = scope.launch(Dispatchers.IO) {
            try {
                _status.value = Status.Loading
                val dir = LocalModelStore.ensureTts(context) { }
                val onnx = File(dir, "pt_BR-faber-medium.onnx")
                val tokens = File(dir, "tokens.txt")
                val dataDir = File(dir, "espeak-ng-data")
                if (!onnx.exists() || !tokens.exists() || !dataDir.exists()) {
                    throw IllegalStateException("Arquivos da voz pt-BR não foram encontrados.")
                }
                val vits = OfflineTtsVitsModelConfig(
                    model = onnx.absolutePath,
                    lexicon = "",
                    tokens = tokens.absolutePath,
                    dataDir = dataDir.absolutePath,
                    dictDir = "",
                )
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = vits,
                        numThreads = 2,
                        debug = false,
                    )
                )
                // sherpa-onnx expects a null AssetManager when model files are
                // absolute paths on the filesystem. The Kotlin constructor is
                // typed non-null, but the JNI layer supports null explicitly.
                val constructor = OfflineTts::class.java.getConstructor(
                    android.content.res.AssetManager::class.java,
                    OfflineTtsConfig::class.java,
                )
                val engine = constructor.newInstance(null, config) as OfflineTts
                tts = engine
                _status.value = Status.Ready(engine.sampleRate())
            } catch (t: Throwable) {
                Log.e(TAG, "TTS init failed", t)
                tts = null
                _status.value = Status.Error(t.message ?: "Falha ao iniciar a voz local.")
                main.post { onError?.invoke("Não foi possível iniciar a voz local.") }
            }
        }
    }

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        stop()
        stopped.set(false)
        speakJob = scope.launch(Dispatchers.IO) {
            try {
                val engine = ensureEngine() ?: return@launch
                val audio = engine.generate(clean, 0, speed.coerceIn(0.5f, 1.8f))
                if (stopped.get()) return@launch
                val samples = audio.samples
                if (samples.isEmpty()) {
                    throw IllegalStateException("A síntese não gerou áudio.")
                }
                play(samples, audio.sampleRate)
            } catch (t: Throwable) {
                if (!stopped.get()) {
                    Log.e(TAG, "speak failed", t)
                    main.post { onError?.invoke("Não foi possível reproduzir a voz: ${t.message}") }
                }
            }
        }
    }

    private suspend fun ensureEngine(): OfflineTts? {
        if (tts != null) return tts
        prepare()
        // Wait for preparation without blocking the caller thread.
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (tts == null && SystemClock.elapsedRealtime() < deadline) {
            when (val s = _status.value) {
                is Status.Error -> return null
                else -> kotlinx.coroutines.delay(50)
            }
        }
        return tts
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            val value = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            pcm[i] = value.toShort()
        }
        val bytes = pcm.size * 2
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(bytes, 32768))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audioTrack
        val focusManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        try {
            focusManager.requestAudioFocus(request)
            audioTrack.setVolume(volume.coerceIn(0f, 1f))
            audioTrack.play()
            main.post { onStart?.invoke() }
            var offset = 0
            val chunk = 2048
            while (offset < pcm.size && !stopped.get()) {
                val count = minOf(chunk, pcm.size - offset)
                val written = audioTrack.write(pcm, offset, count)
                if (written < 0) throw IllegalStateException("Falha ao escrever áudio (código $written).")
                offset += written
            }
            val framesLeft = pcm.size - audioTrack.playbackHeadPosition
            val waitMs = if (sampleRate > 0) (framesLeft * 1000L / sampleRate) else 0L
            var waited = 0L
            while (waited < waitMs && !stopped.get()) {
                Thread.sleep(40)
                waited += 40
            }
            if (!stopped.get()) main.post { onDone?.invoke() }
        } finally {
            try { audioTrack.stop() } catch (_: Throwable) {}
            try { audioTrack.release() } catch (_: Throwable) {}
            track = null
            focusManager.abandonAudioFocusRequest(request)
        }
    }

    fun stop() {
        stopped.set(true)
        speakJob?.cancel()
        speakJob = null
        try {
            track?.pause()
            track?.flush()
            track?.stop()
            track?.release()
        } catch (_: Throwable) {
        }
        track = null
    }

    fun release() {
        stop()
        tts?.release()
        tts = null
        _status.value = Status.NotReady
    }

    companion object {
        private const val TAG = "BmoTts"
    }
}
