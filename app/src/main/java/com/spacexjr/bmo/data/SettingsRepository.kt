package com.spacexjr.bmo.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class BmoSettings(
    val modelPath: String = "",
    val thinkingEnabled: Boolean = false,
    val maxTokens: Int = 96,
    val contextSize: Int = 1024,
    val threads: Int = 4,
    val speechRecognitionEnabled: Boolean = true,
    val speechSynthesisEnabled: Boolean = true,
    val ttsSpeed: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    val wakeWordEnabled: Boolean = false,
    val faceScale: Float = 1.0f,
    val defaultExpression: String = "idle",
    val showTranscript: Boolean = true,
    val autoListen: Boolean = true,
    val historyLimit: Int = 12,
)

class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("bmo_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<BmoSettings> = _settings.asStateFlow()

    fun get(): BmoSettings = _settings.value

    fun update(transform: (BmoSettings) -> BmoSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putString(KEY_MODEL_PATH, next.modelPath)
            .putBoolean(KEY_THINKING, next.thinkingEnabled)
            .putInt(KEY_MAX_TOKENS, next.maxTokens)
            .putInt(KEY_CONTEXT, next.contextSize)
            .putInt(KEY_THREADS, next.threads)
            .putBoolean(KEY_ASR, next.speechRecognitionEnabled)
            .putBoolean(KEY_TTS, next.speechSynthesisEnabled)
            .putFloat(KEY_TTS_SPEED, next.ttsSpeed)
            .putFloat(KEY_TTS_VOLUME, next.ttsVolume)
            .putBoolean(KEY_WAKE, next.wakeWordEnabled)
            .putFloat(KEY_FACE_SCALE, next.faceScale)
            .putString(KEY_DEFAULT_EXPRESSION, next.defaultExpression)
            .putBoolean(KEY_TRANSCRIPT, next.showTranscript)
            .putBoolean(KEY_AUTO_LISTEN, next.autoListen)
            .putInt(KEY_HISTORY_LIMIT, next.historyLimit)
            .apply()
    }

    fun setModelPath(path: String) = update { it.copy(modelPath = path) }

    /**
     * Procura um GGUF já copiado para a pasta privada externa do app.
     * Qwen2.5-0.5B-Instruct (Q4_K_M) tem prioridade; qualquer outro GGUF é
     * aceito como alternativa, mas nunca é baixado automaticamente.
     */
    fun findLocalModel(context: Context): File? {
        val dir = File(context.getExternalFilesDir(null), "models")
        val files = dir.listFiles { file -> file.isFile && file.extension.equals("gguf", true) }
            ?: return null
        return files.firstOrNull { it.name.contains("qwen2.5", ignoreCase = true) }
            ?: files.firstOrNull { it.name.contains("qwen", ignoreCase = true) }
            ?: files.maxByOrNull { it.length() }
    }

    private fun read(): BmoSettings {
        val storedPath = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        val preferred = findLocalModel(appContext)
        // Migração da versão anterior: se havia um Qwen3 selecionado mas o
        // Qwen2.5 pedido pelo projeto está presente, passa a usá-lo.
        val modelPath = if (storedPath.contains("qwen3", ignoreCase = true) &&
            preferred != null && preferred.name.contains("qwen2.5", ignoreCase = true)
        ) {
            preferred.absolutePath.also { prefs.edit().putString(KEY_MODEL_PATH, it).apply() }
        } else {
            storedPath
        }
        // A expressão padrão é usada apenas em repouso. "Falando" e
        // "Inicialização" são estados reais e não podem ficar fixos na tela.
        val storedExpression = prefs.getString(KEY_DEFAULT_EXPRESSION, "idle") ?: "idle"
        val restingExpression = if (
            storedExpression.equals("speaking", ignoreCase = true) ||
            storedExpression.equals("warmup", ignoreCase = true)
        ) {
            "idle".also {
                prefs.edit().putString(KEY_DEFAULT_EXPRESSION, it).apply()
            }
        } else {
            storedExpression
        }
        return BmoSettings(
            modelPath = modelPath,
            thinkingEnabled = prefs.getBoolean(KEY_THINKING, false),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, 96).coerceIn(16, 512),
            contextSize = prefs.getInt(KEY_CONTEXT, 1024).coerceIn(256, 4096),
            threads = prefs.getInt(KEY_THREADS, 4).coerceIn(1, 6),
            speechRecognitionEnabled = prefs.getBoolean(KEY_ASR, true),
            speechSynthesisEnabled = prefs.getBoolean(KEY_TTS, true),
            ttsSpeed = prefs.getFloat(KEY_TTS_SPEED, 1.0f),
            ttsVolume = prefs.getFloat(KEY_TTS_VOLUME, 1.0f),
            wakeWordEnabled = prefs.getBoolean(KEY_WAKE, false),
            faceScale = prefs.getFloat(KEY_FACE_SCALE, 1.0f),
            defaultExpression = restingExpression,
            showTranscript = prefs.getBoolean(KEY_TRANSCRIPT, true),
            autoListen = prefs.getBoolean(KEY_AUTO_LISTEN, true),
            historyLimit = prefs.getInt(KEY_HISTORY_LIMIT, 12).coerceIn(0, 40),
        ).also {
            if (it.modelPath.isBlank()) {
                // The default location is resolved by the UI once the app directory exists.
            }
        }
    }

    companion object {
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_THINKING = "thinking"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_CONTEXT = "context_size"
        private const val KEY_THREADS = "threads"
        private const val KEY_ASR = "asr_enabled"
        private const val KEY_TTS = "tts_enabled"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_TTS_VOLUME = "tts_volume"
        private const val KEY_WAKE = "wake_enabled"
        private const val KEY_FACE_SCALE = "face_scale"
        private const val KEY_DEFAULT_EXPRESSION = "default_expression"
        private const val KEY_TRANSCRIPT = "show_transcript"
        private const val KEY_AUTO_LISTEN = "auto_listen"
        private const val KEY_HISTORY_LIMIT = "history_limit"
    }
}
