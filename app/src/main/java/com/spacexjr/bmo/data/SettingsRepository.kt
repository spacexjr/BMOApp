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
    val threads: Int = 3,
    val speechRecognitionEnabled: Boolean = true,
    val speechSynthesisEnabled: Boolean = true,
    val ttsSpeed: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    val wakeWordEnabled: Boolean = false,
    val faceScale: Float = 1.0f,
    val defaultExpression: String = "neutral",
    val showTranscript: Boolean = true,
    val autoListen: Boolean = true,
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("bmo_settings", Context.MODE_PRIVATE)

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
            .apply()
    }

    fun setModelPath(path: String) = update { it.copy(modelPath = path) }

    fun defaultModelFile(context: Context): File =
        File(context.getExternalFilesDir(null), "models/Qwen3-0.6B-Q8_0.gguf")

    private fun read(): BmoSettings {
        val defaultModel = File(
            prefs.getString(KEY_MODEL_PATH, "") ?: ""
        )
        return BmoSettings(
            modelPath = prefs.getString(KEY_MODEL_PATH, "") ?: "",
            thinkingEnabled = prefs.getBoolean(KEY_THINKING, false),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, 96).coerceIn(16, 512),
            contextSize = prefs.getInt(KEY_CONTEXT, 1024).coerceIn(256, 4096),
            threads = prefs.getInt(KEY_THREADS, 3).coerceIn(1, 6),
            speechRecognitionEnabled = prefs.getBoolean(KEY_ASR, true),
            speechSynthesisEnabled = prefs.getBoolean(KEY_TTS, true),
            ttsSpeed = prefs.getFloat(KEY_TTS_SPEED, 1.0f),
            ttsVolume = prefs.getFloat(KEY_TTS_VOLUME, 1.0f),
            wakeWordEnabled = prefs.getBoolean(KEY_WAKE, false),
            faceScale = prefs.getFloat(KEY_FACE_SCALE, 1.0f),
            defaultExpression = prefs.getString(KEY_DEFAULT_EXPRESSION, "neutral") ?: "neutral",
            showTranscript = prefs.getBoolean(KEY_TRANSCRIPT, true),
            autoListen = prefs.getBoolean(KEY_AUTO_LISTEN, true),
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
    }
}
