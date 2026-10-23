package com.spacexjr.bmo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.spacexjr.bmo.actions.ActionManager
import com.spacexjr.bmo.data.BmoSettings
import com.spacexjr.bmo.data.DiagnosticsManager
import com.spacexjr.bmo.data.SettingsRepository
import com.spacexjr.bmo.face.BmoState
import com.spacexjr.bmo.llm.LlmEngine
import com.spacexjr.bmo.llm.ModelStatus
import com.spacexjr.bmo.speech.SpeechRecognizerManager
import com.spacexjr.bmo.speech.TextToSpeechManager
import com.spacexjr.bmo.speech.VoskModelProvider
import com.spacexjr.bmo.speech.WakeWordManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class BmoAssistant(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    val diagnostics: DiagnosticsManager,
) {
    private val llm = LlmEngine()
    private val vosk = VoskModelProvider(context)
    private val asr = SpeechRecognizerManager(context, vosk, scope)
    private val wake = WakeWordManager(context, scope)
    private val tts = TextToSpeechManager(context, scope)
    private val actions = ActionManager(context)

    private val _state = MutableStateFlow(BmoState.RESTING)
    val state: StateFlow<BmoState> = _state.asStateFlow()

    private val _bubble = MutableStateFlow("")
    val bubble: StateFlow<String> = _bubble.asStateFlow()

    private val _status = MutableStateFlow("Toque no rosto para conversar")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    val modelStatus: StateFlow<ModelStatus> = llm.status
    val ttsStatus: StateFlow<TextToSpeechManager.Status> = tts.status

    private val busy = AtomicBoolean(false)
    private val handlesUtterance = AtomicBoolean(false)
    private var foreground = false
    private var history = mutableListOf<Pair<String, String>>()
    private var settings: BmoSettings = settingsRepository.get()

    @Volatile private var thinkingSupported = false
    @Volatile private var lastToolNotice = ""

    init {
        tts.onStart = {
            _speaking.value = true
            _thinking.value = false
            _state.value = BmoState.SPEAKING
            diagnostics.info("TTS", "reprodução iniciada")
        }
        tts.onDone = {
            _speaking.value = false
            _state.value = BmoState.RESTING
            diagnostics.info("TTS", "reprodução concluída")
            if (_bubble.value.isNotBlank()) {
                _status.value = "Pode falar de novo quando quiser"
            }
            resumeWakeIfPossible()
        }
        tts.onError = { message ->
            _speaking.value = false
            _thinking.value = false
            _state.value = BmoState.ERROR
            _status.value = message
            diagnostics.error("TTS", message)
            resumeWakeIfPossible()
        }

        asr.onReady = {
            _listening.value = true
            _state.value = BmoState.LISTENING
            _status.value = "Estou ouvindo..."
            diagnostics.info("ASR", "microfone ativo")
        }
        asr.onPartial = { text ->
            if (settings.showTranscript && text.isNotBlank()) _bubble.value = text
        }
        asr.onResult = { result ->
            handlesUtterance.set(true)
            _listening.value = false
            _bubble.value = result.text
            onUtterance(result.text)
        }
        asr.onError = { error ->
            handlesUtterance.set(false)
            _listening.value = false
            _state.value = BmoState.CONFUSED
            _status.value = "Toque no rosto para tentar de novo"
            diagnostics.warn("ASR", error.message)
            _bubble.value = error.message
            resumeWakeIfPossible()
        }
        asr.onFinished = {
            _listening.value = false
            if (!handlesUtterance.get()) {
                _state.value = BmoState.RESTING
                resumeWakeIfPossible()
            }
        }

        wake.onWakeWord = {
            if (!busy.get() && foreground) {
                diagnostics.info("WakeWord", "Ei, BMO detectado")
                _state.value = BmoState.SURPRISED
                _status.value = "Ei! Pode falar."
                scope.launch {
                    delay(420)
                    if (foreground) startListening(fromWake = true)
                }
            }
        }
        wake.onError = { message ->
            diagnostics.error("WakeWord", message)
            _status.value = message
        }
        wake.onStatus = { message -> diagnostics.info("WakeWord", message) }
    }

    fun updateSettings(settings: BmoSettings) {
        val previous = this.settings
        this.settings = settings
        tts.speed = settings.ttsSpeed
        tts.volume = settings.ttsVolume
        if (previous.wakeWordEnabled != settings.wakeWordEnabled) {
            if (settings.wakeWordEnabled) startWakeWord() else stopWakeWord()
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            resumeWakeIfPossible()
        } else {
            stopWakeWord()
            if (asr.isListening) asr.stop()
            if (busy.get()) llm.cancel()
            tts.stop()
            _speaking.value = false
            _listening.value = false
            _thinking.value = false
            busy.set(false)
            _state.value = BmoState.RESTING
        }
    }

    fun onSettingsChanged() {
        updateSettings(settingsRepository.get())
    }

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun startListening(fromWake: Boolean = false) {
        Log.i(TAG, "startListening fromWake=$fromWake busy=${busy.get()} asr=${asr.isListening} wake=${wake.isRunning}")
        if (busy.get() || asr.isListening) return
        if (!hasMicrophonePermission()) {
            _status.value = "Preciso da permissão de microfone para ouvir você."
            return
        }
        settings = settingsRepository.get()
        if (!settings.speechRecognitionEnabled) {
            _status.value = "O reconhecimento de voz está desativado nas configurações."
            return
        }
        tts.stop()
        wake.stop()
        _speaking.value = false
        handlesUtterance.set(false)
        _state.value = BmoState.THINKING
        _status.value = if (vosk.isLoaded()) "Preparando o microfone..." else "Carregando o modelo de voz local..."
        _bubble.value = ""
        scope.launch {
            delay(220)
            asr.start()
        }
    }

    fun stopListening() {
        asr.stop()
        _listening.value = false
        if (!busy.get()) _state.value = BmoState.RESTING
    }

    fun stopSpeaking() {
        tts.stop()
        _speaking.value = false
        _thinking.value = false
        _state.value = BmoState.RESTING
        _status.value = "Fala interrompida"
        resumeWakeIfPossible()
    }

    fun cancelEverything() {
        llm.cancel()
        tts.stop()
        asr.stop()
        stopWakeWord()
        if (busy.get()) {
            busy.set(false)
            _thinking.value = false
            _state.value = BmoState.RESTING
            _status.value = "Cancelado."
        }
    }

    fun submitText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (asr.isListening) asr.stop()
        tts.stop()
        stopWakeWord()
        _bubble.value = clean
        onUtterance(clean)
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        _bubble.value = text
        if (!settings.speechSynthesisEnabled || !foreground) {
            _thinking.value = false
            _state.value = BmoState.RESTING
            _status.value = "Resposta pronta"
            resumeWakeIfPossible()
            return
        }
        _state.value = BmoState.THINKING
        _status.value = "Preparando a voz..."
        tts.speak(forSpeech(text))
    }

    private fun onUtterance(text: String) {
        if (text.isBlank()) {
            handlesUtterance.set(false)
            resumeWakeIfPossible()
            return
        }
        settings = settingsRepository.get()
        if (text.lowercase(Locale.ROOT).contains("cancelar") &&
            (text.lowercase(Locale.ROOT).contains("geracao") || text.lowercase(Locale.ROOT).contains("geração"))
        ) {
            cancelEverything()
            return
        }
        if (!llm.isLoaded) {
            handlesUtterance.set(false)
            busy.set(false)
            _state.value = BmoState.ERROR
            val message = "Meu modelo de linguagem ainda não está carregado. Abra as configurações e selecione o arquivo GGUF."
            _bubble.value = message
            _status.value = "Modelo indisponível"
            diagnostics.warn("LLM", "tentativa de conversa sem modelo carregado")
            if (settings.speechSynthesisEnabled) tts.speak(message)
            return
        }
        if (!busy.compareAndSet(false, true)) return
        _thinking.value = true
        _state.value = BmoState.THINKING
        _status.value = "Pensando..."
        diagnostics.info("Assistant", "usuário: $text")
        scope.launch {
            try {
                val reply = generateReply(text)
                if (reply.isBlank()) {
                    throw IllegalStateException("O modelo não retornou uma resposta final.")
                }
                addHistory("user", text)
                addHistory("assistant", reply)
                _bubble.value = reply
                _status.value = "Resposta pronta"
                diagnostics.info("Assistant", "BMO: $reply")
                handlesUtterance.set(false)
                busy.set(false)
                speak(reply)
            } catch (t: Throwable) {
                handlesUtterance.set(false)
                busy.set(false)
                _thinking.value = false
                val cancelled = t.message?.contains("cancelada", ignoreCase = true) == true
                if (cancelled) {
                    _state.value = BmoState.RESTING
                    _status.value = "Geração cancelada."
                } else {
                    Log.e(TAG, "conversation failed", t)
                    diagnostics.error("Assistant", "falha na conversa", t)
                    _state.value = BmoState.ERROR
                    val message = friendlyError(t)
                    _bubble.value = message
                    _status.value = message
                    if (settings.speechSynthesisEnabled && t.message?.contains("memória", ignoreCase = true) != true) {
                        tts.speak(message)
                    }
                }
                resumeWakeIfPossible()
            }
        }
    }

    private suspend fun generateReply(userText: String): String {
        var prompt = buildPrompt(userText)
        var toolRound = 0
        while (toolRound < 2) {
            val raw = llm.generate(prompt, settings.maxTokens).getOrElse { throw it }
            val clean = stripThinking(raw)
            val tool = parseToolCall(clean)
            if (tool == null) {
                if (clean.isBlank()) throw IllegalStateException("Resposta vazia do modelo.")
                return sanitizeReply(clean)
            }
            val result = withContext(Dispatchers.Main) {
                actions.execute(tool.name, tool.arguments)
            }
            diagnostics.info("Action", "${tool.name} -> ${if (result.ok) "ok" else "falha"}: ${result.message}")
            lastToolNotice = result.message
            prompt = buildPrompt(
                userText,
                extraTail = buildString {
                    append("<|im_start|>assistant\n")
                    append(clean)
                    append("<|im_end|>\n")
                    append("<|im_start|>user\n")
                    append("Resultado real da ferramenta ${tool.name}: ")
                    append(result.message)
                    append("\nResponda ao usuário em português do Brasil, de forma curta. Não invente nada além deste resultado.")
                    append("<|im_end|>\n")
                    append(assistantHeader())
                }
            )
            toolRound++
        }
        val raw = llm.generate(prompt, settings.maxTokens).getOrElse { throw it }
        return sanitizeReply(stripThinking(raw))
    }

    private data class ParsedTool(val name: String, val arguments: JSONObject)

    private fun parseToolCall(text: String): ParsedTool? {
        val regex = Regex("(?s)<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>")
        val jsonText = regex.find(text)?.groupValues?.getOrNull(1)
            ?: if (text.trim().startsWith("{") && text.contains("\"name\"")) text.trim() else null
            ?: return null
        return try {
            val json = JSONObject(jsonText)
            val name = json.optString("name")
            if (name.isBlank()) return null
            val args = json.optJSONObject("arguments") ?: JSONObject()
            ParsedTool(name, args)
        } catch (_: Throwable) {
            null
        }
    }

    private fun stripThinking(raw: String): String {
        var text = raw
        text = text.replace(Regex("(?s) thinking.*?</think>"), "")
        val unclosed = text.indexOf(" thinking")
        if (unclosed >= 0) text = text.substring(0, unclosed)
        text = text.replace("<|im_end|>", "").replace("<|endoftext|>", "")
        return text.trim()
    }

    private fun sanitizeReply(text: String): String {
        var clean = text.trim()
        clean = clean.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("\\s*```$"), "")
        if (clean.startsWith("<|im_start|>")) clean = clean.substringAfter("\n").trim()
        clean = clean.replace(Regex("(?s)<tool_call>.*?</tool_call>"), "").trim()
        return clean.trim()
    }

    private fun buildPrompt(userText: String, extraTail: String? = null): String {
        val system = systemPrompt()
        val builder = StringBuilder()
        builder.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        var budget = (settings.contextSize * 3).coerceAtLeast(1800)
        budget -= builder.length + userText.length + 600
        val selected = ArrayList<Pair<String, String>>()
        for (message in history.asReversed()) {
            val cost = message.first.length + message.second.length + 20
            if (budget - cost <= 0) break
            selected.add(message)
            budget -= cost
        }
        selected.asReversed().forEach { (role, content) ->
            builder.append("<|im_start|>").append(role).append("\n")
                .append(content).append("<|im_end|>\n")
        }
        builder.append("<|im_start|>user\n").append(userText)
        if (!settings.thinkingEnabled && !thinkingSupported) {
            builder.append(" /no_think")
        }
        builder.append("<|im_end|>\n")
        if (extraTail != null) {
            builder.append(extraTail)
        } else {
            builder.append(assistantHeader())
        }
        return builder.toString()
    }

    private fun assistantHeader(): String {
        return if (!settings.thinkingEnabled && thinkingSupported) {
            // This is the exact mechanism the Qwen3 chat template uses when
            // enable_thinking=false: an empty think block forces a direct answer.
            "<|im_start|>assistant\n thinking\n\n</think>\n\n"
        } else {
            "<|im_start|>assistant\n"
        }
    }

    private fun systemPrompt(): String {
        return """
            Você é o BMO, um pequeno assistente robótico inspirado no personagem BMO de Adventure Time.
            Fale sempre em português brasileiro, com frases curtas, naturais e amigáveis. Seja curioso e divertido, mas sem exagerar.
            Você conversa por voz: evite listas longas, markdown e respostas com mais de três frases, a menos que o usuário peça detalhes.
            Não invente ações. Se uma ferramenta falhar, diga o que realmente aconteceu. Se não souber algo, admita com naturalidade.
            Para hora ou data, use as ferramentas disponíveis em vez de adivinhar.
            Nunca diga que está ouvindo se o microfone não estiver ativo, nem que está falando se não houver áudio.
            Não revele raciocínio interno; entregue apenas a resposta final.

            ${actions.definitionsPrompt()}
        """.trimIndent()
    }

    private fun addHistory(role: String, text: String) {
        history.add(role to text)
        if (history.size > 12) {
            history = history.takeLast(12).toMutableList()
        }
    }

    private fun forSpeech(text: String): String {
        return text
            .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("[*_`#>]"), "")
            .replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun friendlyError(t: Throwable): String {
        val msg = t.message ?: ""
        return when {
            msg.contains("memória", ignoreCase = true) -> "Fiquei sem memória para pensar agora. Tente uma pergunta mais curta."
            msg.contains("contexto", ignoreCase = true) -> "A conversa ficou longa demais. Vamos começar de novo?"
            msg.contains("Modelo", ignoreCase = true) || msg.contains("modelo", ignoreCase = true) -> "Não consegui usar meu modelo de linguagem. Verifique o arquivo nas configurações."
            else -> "Tive um problema para pensar: ${msg.ifBlank { "erro desconhecido" }}"
        }
    }

    suspend fun loadModelIfAvailable(): ModelStatus {
        settings = settingsRepository.get()
        val path = settings.modelPath.ifBlank {
            val fallback = java.io.File(context.getExternalFilesDir(null), "models/Qwen3-0.6B-Q8_0.gguf")
            if (fallback.exists()) fallback.absolutePath else ""
        }
        if (path.isBlank()) {
            return ModelStatus.NotLoaded
        }
        if (settings.modelPath.isBlank()) {
            settingsRepository.setModelPath(path)
            settings = settingsRepository.get()
        }
        val result = llm.load(path, settings.contextSize, settings.threads)
        result.onSuccess { ready ->
            thinkingSupported = ready.supportsThinking
            diagnostics.info("LLM", "modelo carregado: ${ready.name}; thinking=${if (ready.supportsThinking) "suportado" else "não suportado"}")
            _status.value = if (ready.supportsThinking) {
                "Modelo carregado"
            } else {
                "Modelo carregado (Thinking não suportado pelo template)"
            }
        }
        result.onFailure { error ->
            diagnostics.error("LLM", "falha ao carregar modelo", error)
        }
        return llm.status.value
    }

    suspend fun reloadModel(): ModelStatus {
        llm.unload()
        return loadModelIfAvailable()
    }

    fun unloadModel() {
        llm.unload()
        thinkingSupported = false
        diagnostics.info("LLM", "modelo descarregado pelo usuário")
    }

    fun modelTemplateSupportsThinking(): Boolean = thinkingSupported

    fun wakeWordRunning(): Boolean = wake.isRunning

    fun voskModelLoaded(): Boolean = vosk.isLoaded()

    fun ttsReady(): Boolean = (tts.status.value as? TextToSpeechManager.Status.Ready) != null

    fun hasLoadedModel(): Boolean = llm.isLoaded

    private fun startWakeWord() {
        if (!foreground || busy.get() || asr.isListening || tts.isSpeaking) return
        if (!settings.wakeWordEnabled) return
        if (!hasMicrophonePermission()) return
        scope.launch {
            try {
                wake.start(vosk)
            } catch (t: Throwable) {
                diagnostics.error("WakeWord", "falha ao iniciar", t)
            }
        }
    }

    private fun stopWakeWord() {
        wake.stop()
    }

    private fun resumeWakeIfPossible() {
        if (settings.wakeWordEnabled && foreground && !busy.get() && !asr.isListening && !tts.isSpeaking) {
            scope.launch {
                delay(350)
                if (!busy.get() && !asr.isListening && !tts.isSpeaking) startWakeWord()
            }
        }
    }

    fun shutdown() {
        llm.cancel()
        tts.release()
        asr.stop()
        wake.stop()
        vosk.close()
        llm.unload()
    }

    fun toolNotice(): String = lastToolNotice

    companion object {
        private const val TAG = "BmoAssistant"
    }
}
