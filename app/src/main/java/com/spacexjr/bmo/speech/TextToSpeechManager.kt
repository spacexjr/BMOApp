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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Síntese de voz neural offline com sherpa-onnx + Piper VITS (pt-BR Faber).
 *
 * O áudio é gerado e reproduzido no próprio aparelho. Cada pedido de fala
 * recebe um identificador; resultados de sínteses antigas são descartados para
 * impedir que uma resposta anterior seja reproduzida depois de uma nova.
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

    private val lock = Any()
    private val prepareMutex = Mutex()
    private val requestCounter = AtomicLong(0L)

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var requestId: Long = 0L
    @Volatile private var speakingNow: Boolean = false

    private var prepareJob: Job? = null
    private var speakJob: Job? = null
    private var activeTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    @Volatile var volume: Float = 1.0f
    @Volatile var speed: Float = 1.0f

    var onStart: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isSpeaking: Boolean get() = speakingNow

    fun prepare() {
        if (tts != null || prepareJob?.isActive == true) return
        prepareJob = scope.launch(Dispatchers.IO) {
            try {
                prepareMutex.withLock {
                    if (tts != null) return@withLock
                    _status.value = Status.Loading
                    val dir = LocalModelStore.ensureTts(context)
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
                    // O sherpa-onnx aceita AssetManager nulo quando os
                    // caminhos são absolutos no sistema de arquivos.
                    val constructor = OfflineTts::class.java.getConstructor(
                        android.content.res.AssetManager::class.java,
                        OfflineTtsConfig::class.java,
                    )
                    val engine = constructor.newInstance(null, config) as OfflineTts
                    tts = engine
                    _status.value = Status.Ready(engine.sampleRate())
                    Log.i(TAG, "modelo de voz local carregado (${engine.sampleRate()} Hz)")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "falha ao iniciar TTS", t)
                tts = null
                _status.value = Status.Error(t.message ?: "Falha ao iniciar a voz local.")
                main.post { onError?.invoke("Não foi possível iniciar a voz local.") }
            }
        }
    }

    fun speak(text: String) {
        val clean = sanitizeForSpeech(text)
        if (clean.isEmpty()) return

        // Invalida qualquer fala anterior antes de agendar a nova.
        val myId = requestCounter.incrementAndGet()
        synchronized(lock) { requestId = myId }
        cancelPlayback()

        speakJob = scope.launch(Dispatchers.IO) {
            try {
                val engine = ensureEngine(myId) ?: return@launch
                if (myId != requestId) return@launch
                val audio = engine.generate(clean, 0, speed.coerceIn(0.5f, 1.8f))
                if (myId != requestId) return@launch
                val samples = audio.samples
                if (samples.isEmpty()) {
                    throw IllegalStateException("A síntese não gerou áudio.")
                }
                play(samples, audio.sampleRate, myId)
            } catch (_: CancellationException) {
                // Uma fala mais nova substituiu esta.
            } catch (t: Throwable) {
                if (myId == requestId) {
                    Log.e(TAG, "falha ao falar", t)
                    speakingNow = false
                    main.post { onError?.invoke("Não foi possível reproduzir a voz: ${t.message}") }
                }
            }
        }
    }

    private suspend fun ensureEngine(myId: Long): OfflineTts? {
        tts?.let { return it }
        prepare()
        val deadline = SystemClock.elapsedRealtime() + 25_000L
        while (tts == null && SystemClock.elapsedRealtime() < deadline) {
            if (myId != requestId) return null
            when (_status.value) {
                is Status.Error -> return null
                else -> delay(50)
            }
        }
        return tts
    }

    private fun play(samples: FloatArray, sampleRate: Int, myId: Long) {
        if (myId != requestId) return
        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            pcm[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(pcm.size * 2, 32768))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        synchronized(lock) {
            if (myId != requestId) {
                track.release()
                return
            }
            activeTrack = track
            focusRequest = focus
        }
        try {
            audioManager.requestAudioFocus(focus)
            track.setVolume(volume.coerceIn(0f, 1f))
            track.play()
            speakingNow = true
            main.post { if (myId == requestId) onStart?.invoke() }

            var offset = 0
            val chunk = 2048
            while (offset < pcm.size) {
                if (myId != requestId) return
                val written = track.write(pcm, offset, minOf(chunk, pcm.size - offset))
                if (written < 0) {
                    throw IllegalStateException("Falha ao escrever áudio (código $written).")
                }
                offset += written
            }
            val expectedMs = pcm.size * 1000L / sampleRate.coerceAtLeast(1)
            val deadline = SystemClock.elapsedRealtime() + expectedMs + 2_000L
            while (myId == requestId &&
                track.playbackHeadPosition < pcm.size &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                Thread.sleep(30L)
            }
            if (myId == requestId) {
                speakingNow = false
                main.post {
                    if (myId == requestId) onDone?.invoke()
                }
            }
        } finally {
            synchronized(lock) {
                if (activeTrack === track) activeTrack = null
                if (focusRequest === focus) focusRequest = null
            }
            try { track.pause() } catch (_: Throwable) {}
            try { track.flush() } catch (_: Throwable) {}
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
            try { audioManager.abandonAudioFocusRequest(focus) } catch (_: Throwable) {}
        }
    }

    private fun cancelPlayback() {
        val track: AudioTrack?
        val focus: AudioFocusRequest?
        synchronized(lock) {
            track = activeTrack
            focus = focusRequest
            activeTrack = null
            focusRequest = null
        }
        speakJob?.cancel()
        speakJob = null
        speakingNow = false
        if (track != null) {
            try { track.pause() } catch (_: Throwable) {}
            try { track.flush() } catch (_: Throwable) {}
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
        if (focus != null) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.abandonAudioFocusRequest(focus)
            } catch (_: Throwable) {}
        }
    }

    fun stop() {
        requestCounter.incrementAndGet()
        cancelPlayback()
    }

    fun release() {
        stop()
        prepareJob?.cancel()
        prepareJob = null
        try { tts?.release() } catch (_: Throwable) {}
        tts = null
        _status.value = Status.NotReady
    }

    companion object {
        private const val TAG = "BmoTts"

        /**
         * Remove markdown, URLs, emojis e símbolos que o Piper tentaria
         * pronunciar. Mantém letras, números e pontuação de fala.
         */
        fun sanitizeForSpeech(input: String): String {
            var text = input
            text = Regex("(?s)```.*?```").replace(text, " ")
            text = Regex("`([^`]*)`").replace(text, "$1")
            text = Regex("!\\[([^]]*)]\\([^)]*\\)").replace(text, "$1")
            text = Regex("\\[([^]]*)]\\([^)]*\\)").replace(text, "$1")
            text = Regex("https?://\\S+").replace(text, " ")
            text = Regex("<[^>]+>").replace(text, " ")
            text = Regex("[\\uD800-\\uDFFF\\uFE0F\\u200D\\u2600-\\u27BF]").replace(text, " ")
            text = Regex("\\p{So}").replace(text, " ")
            text = Regex("[*_#>`~^|]+").replace(text, " ")
            text = text.replace('\u00A0', ' ')
            text = Regex("\\s+").replace(text, " ").trim()
            // Evita pedidos gigantes de síntese em respostas anormais.
            return if (text.length > 800) text.take(800).substringBeforeLast(' ') else text
        }
    }
}
