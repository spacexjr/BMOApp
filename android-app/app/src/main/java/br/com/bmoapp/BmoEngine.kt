package br.com.bmoapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import br.com.bmoapp.audio.AudioCapture
import br.com.bmoapp.audio.AudioPlayer
import br.com.bmoapp.net.BmoClient
import br.com.bmoapp.net.ServerDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Núcleo do app: conexão WebSocket, microfone, reprodução e estado da UI.
 * Um único singleton atende a Activity e o serviço em primeiro plano.
 */
class BmoEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = SettingsStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val _ui = MutableStateFlow(BmoUiState())
    val uiState: StateFlow<BmoUiState> = _ui.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    @Volatile
    private var settings: AppSettings = store.load()

    private var client: BmoClient? = null
    private var capture: AudioCapture? = null
    private var testCapture: AudioCapture? = null
    private val player = AudioPlayer()
    private var reconnectJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var playbackJob: Job? = null
    private var wantListening = false
    private var manualDisconnect = false
    private var captureForTest = false
    private var lastSeq = -1L
    private var currentAssistantId = ""
    private var audioStarted = false
    private var currentSampleRate = 22050
    private var currentChannels = 1

    init {
        player.updateVolume(settings.volume)
        addLog("INFO", "Aplicativo iniciado. Servidor: ${if (settings.isConfigured) settings.host else "não configurado"}.")
        if (settings.autoConnect && settings.isConfigured) connect(settings, automatic = true)
    }

    // ------------------------------------------------------------------
    // Configuração e conexão
    // ------------------------------------------------------------------

    fun currentSettings(): AppSettings = settings

    fun saveSettings(newSettings: AppSettings, reconnect: Boolean = true) {
        settings = newSettings.copy(host = newSettings.host.trim())
        store.save(settings)
        player.updateVolume(settings.volume)
        addLog("INFO", "Configurações salvas. Servidor ${settings.host}:${settings.port}.")
        if (reconnect) connect(settings)
    }

    fun setVolume(value: Float) {
        settings = settings.copy(volume = value.coerceIn(0f, 1f))
        store.saveVolume(settings.volume)
        player.updateVolume(settings.volume)
    }

    fun connect(newSettings: AppSettings = settings, automatic: Boolean = false) {
        settings = newSettings.copy(host = newSettings.host.trim())
        manualDisconnect = false
        reconnectJob?.cancel()
        connectTimeoutJob?.cancel()
        client?.close()
        client = null
        stopCaptureInternal(sendEnd = false)
        player.stop()
        if (!settings.isConfigured) {
            _ui.update {
                it.copy(
                    connection = ConnectionState.DISCONNECTED,
                    bmoState = BmoState.DISCONNECTED,
                    faceState = BmoState.DISCONNECTED,
                    statusDetail = "Configure o endereço do PC nas opções.",
                    lastError = "Servidor não configurado.",
                )
            }
            addLog("AVISO", "Conexão cancelada: servidor não configurado.")
            return
        }
        lastSeq = -1
        _ui.update {
            it.copy(
                connection = if (automatic) ConnectionState.RECONNECTING else ConnectionState.CONNECTING,
                bmoState = BmoState.CONNECTING,
                faceState = BmoState.CONNECTING,
                statusDetail = "Conectando a ${settings.host}:${settings.port}...",
                lastError = null,
            )
        }
        addLog("INFO", "Conectando a ${settings.wsUrl}${if (automatic) " (automático)" else ""}.")
        val newClient = BmoClient(object : BmoClient.Listener {
            override fun onOpen() {
                addLog("INFO", "Canal aberto. Aguardando saudação do backend.")
            }

            override fun onText(text: String) = handleServerText(text)

            override fun onBinary(bytes: ByteArray) = handleServerAudio(bytes)

            override fun onClosed(code: Int, reason: String) {
                if (manualDisconnect) return
                connectionLost("Conexão encerrada pelo servidor (código $code).")
            }

            override fun onFailure(message: String) {
                if (manualDisconnect) return
                connectionLost("Falha de conexão: $message")
            }
        })
        client = newClient
        newClient.connect(settings)
        connectTimeoutJob = scope.launch {
            delay(15_000)
            if (_ui.value.connection == ConnectionState.CONNECTING ||
                _ui.value.connection == ConnectionState.RECONNECTING
            ) {
                connectionLost("Tempo esgotado ao conectar. Confira o IP, a porta e o Wi-Fi.")
            }
        }
    }

    fun disconnect() {
        manualDisconnect = true
        wantListening = false
        reconnectJob?.cancel()
        connectTimeoutJob?.cancel()
        playbackJob?.cancel()
        stopCaptureInternal(sendEnd = false)
        stopMicTest()
        player.stop()
        client?.close()
        client = null
        _ui.update {
            it.copy(
                connection = ConnectionState.DISCONNECTED,
                bmoState = BmoState.DISCONNECTED,
                faceState = BmoState.DISCONNECTED,
                listening = false,
                statusDetail = "Desconectado.",
                micLevel = 0f,
            )
        }
        addLog("INFO", "Desconectado manualmente.")
    }

    fun ensureConnected() {
        if (!_ui.value.connected && settings.isConfigured) connect(settings, automatic = true)
    }

    /**
     * Pede para manter o microfone ativo (inclusive após uma reconexão).
     * O serviço em primeiro plano usa este método porque pode ser iniciado
     * antes de o WebSocket terminar de conectar.
     */
    fun requestListening(): Boolean {
        wantListening = true
        if (_ui.value.connected) return startListening()
        if (settings.isConfigured) {
            addLog("INFO", "Escuta solicitada; aguardando conexão com o PC.")
            ensureConnected()
        } else {
            wantListening = false
            fail("Configure o servidor do PC antes de ativar o microfone.")
        }
        return false
    }

    private fun connectionLost(message: String) {
        connectTimeoutJob?.cancel()
        client?.close()
        client = null
        stopCaptureInternal(sendEnd = false)
        player.stop()
        playbackJob?.cancel()
        _ui.update {
            it.copy(
                connection = if (settings.autoConnect && settings.isConfigured)
                    ConnectionState.RECONNECTING else ConnectionState.DISCONNECTED,
                bmoState = BmoState.DISCONNECTED,
                faceState = BmoState.DISCONNECTED,
                listening = false,
                micLevel = 0f,
                statusDetail = message,
                lastError = message,
            )
        }
        addLog("ERRO", message)
        if (settings.autoConnect && settings.isConfigured && !manualDisconnect) {
            val attempt = _ui.value.reconnectAttempt + 1
            val waitMs = min(15_000L, 1000L * (1 shl min(attempt, 4)))
            _ui.update { it.copy(reconnectAttempt = attempt) }
            addLog("INFO", "Nova tentativa em ${waitMs / 1000}s.")
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(waitMs)
                if (!manualDisconnect) connect(settings, automatic = true)
            }
        }
    }

    // ------------------------------------------------------------------
    // Microfone
    // ------------------------------------------------------------------

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun startListening(): Boolean {
        if (!_ui.value.connected) {
            fail("Conecte-se ao PC antes de ativar o microfone.")
            return false
        }
        if (!hasMicrophonePermission()) {
            fail("Permissão de microfone não concedida. Autorize em Configurações do Android.")
            return false
        }
        if (capture?.isRunning == true) return true
        stopMicTest()
        val newCapture = AudioCapture(appContext, object : AudioCapture.Listener {
            override fun onLevel(level: Float) {
                _ui.update { it.copy(micLevel = level) }
            }

            override fun onPcm(bytes: ByteArray) {
                if (!captureForTest && _ui.value.listening && !audioMuted) {
                    client?.sendBytes(bytes)
                }
            }

            override fun onError(message: String) {
                fail(message)
                stopCaptureInternal(sendEnd = false)
            }
        })
        if (!newCapture.start()) {
            _ui.update { it.copy(micLevel = 0f) }
            return false
        }
        capture = newCapture
        captureForTest = false
        wantListening = true
        val sent = sendJson(
            JSONObject()
                .put("type", "audio.start")
                .put("format", "pcm_s16le")
                .put("sample_rate", AudioCapture.SAMPLE_RATE)
                .put("channels", AudioCapture.CHANNELS)
        )
        if (!sent) {
            newCapture.stop()
            capture = null
            fail("Não foi possível avisar o servidor sobre o áudio.")
            return false
        }
        _ui.update {
            it.copy(
                listening = true,
                bmoState = BmoState.LISTENING,
                faceState = BmoState.LISTENING,
                statusDetail = "Microfone ativo. Diga \"Ei, BMO\".",
                lastError = null,
            )
        }
        addLog("INFO", "Microfone ativo (16 kHz, mono, PCM 16 bits).")
        return true
    }

    fun stopListening() {
        wantListening = false
        if (capture?.isRunning != true && !_ui.value.listening) return
        stopCaptureInternal(sendEnd = true)
        stopMicTest()
        _ui.update {
            it.copy(
                listening = false,
                micLevel = 0f,
                bmoState = if (it.bmoState == BmoState.LISTENING) BmoState.IDLE else it.bmoState,
                faceState = if (it.faceState == BmoState.LISTENING) BmoState.IDLE else it.faceState,
                statusDetail = "Microfone pausado.",
            )
        }
        addLog("INFO", "Microfone pausado.")
    }

    fun toggleListening() {
        if (_ui.value.listening) stopListening() else startListening()
    }

    private fun stopCaptureInternal(sendEnd: Boolean) {
        val current = capture
        capture = null
        current?.stop()
        captureForTest = false
        if (sendEnd) sendJson(JSONObject().put("type", "audio.end"))
    }

    fun activateOnce() {
        if (!_ui.value.connected) {
            fail("Conecte-se ao PC antes de falar.")
            return
        }
        if (capture?.isRunning != true) {
            if (!startListening()) return
        }
        if (_ui.value.bmoState == BmoState.SPEAKING) interrupt()
        sendJson(JSONObject().put("type", "activate"))
        _ui.update {
            it.copy(
                bmoState = BmoState.LISTENING,
                faceState = BmoState.LISTENING,
                statusDetail = "Ativação manual. Pode falar.",
            )
        }
        addLog("INFO", "Ativação manual solicitada (sem palavra de ativação).")
    }

    @Volatile
    private var audioMuted = false

    private fun setAudioMuted(muted: Boolean) {
        audioMuted = muted
        capture?.muted = muted
    }

    fun startMicTest() {
        if (testCapture?.isRunning == true) return
        if (capture?.isRunning == true) {
            _ui.update { it.copy(micTesting = true) }
            addLog("INFO", "Teste de microfone usando a captura já ativa.")
            return
        }
        if (!hasMicrophonePermission()) {
            fail("Permissão de microfone não concedida.")
            return
        }
        val test = AudioCapture(appContext, object : AudioCapture.Listener {
            override fun onLevel(level: Float) {
                _ui.update { it.copy(micLevel = level) }
            }

            override fun onPcm(bytes: ByteArray) {
                // Teste local: não envia áudio a lugar nenhum.
            }

            override fun onError(message: String) {
                fail(message)
                stopMicTest()
            }
        })
        if (!test.start()) return
        testCapture = test
        captureForTest = true
        _ui.update { it.copy(micTesting = true) }
        addLog("INFO", "Teste de microfone iniciado (somente nível local).")
    }

    fun stopMicTest() {
        val test = testCapture
        val wasTesting = _ui.value.micTesting
        testCapture = null
        test?.stop()
        captureForTest = false
        _ui.update { it.copy(micTesting = false, micLevel = 0f) }
        if (wasTesting) {
            val message = if (capture?.isRunning == true) {
                "Teste de microfone encerrado; a captura principal continua."
            } else {
                "Teste de microfone encerrado."
            }
            addLog("INFO", message)
        }
    }

    // ------------------------------------------------------------------
    // Comandos
    // ------------------------------------------------------------------

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!_ui.value.connected) {
            fail("Conecte-se ao PC antes de enviar uma mensagem.")
            return
        }
        if (clean.length > 1000) {
            fail("A mensagem é longa demais (máximo de 1000 caracteres).")
            return
        }
        if (_ui.value.bmoState == BmoState.SPEAKING) interrupt()
        val sent = sendJson(JSONObject().put("type", "text").put("text", clean))
        if (sent) {
            currentAssistantId = ""
            _ui.update {
                it.copy(
                    transcript = clean,
                    assistantText = "",
                    streamingText = false,
                    statusDetail = "Mensagem enviada.",
                )
            }
            addLog("USUÁRIO", if (clean.length > 120) clean.take(120) + "..." else clean)
        } else {
            fail("Não foi possível enviar a mensagem.")
        }
    }

    fun interrupt() {
        if (_ui.value.connected) sendJson(JSONObject().put("type", "interrupt"))
        playbackJob?.cancel()
        player.stop()
        setAudioMuted(false)
        _ui.update {
            it.copy(
                bmoState = if (it.listening) BmoState.LISTENING else BmoState.IDLE,
                faceState = if (it.listening) BmoState.LISTENING else BmoState.IDLE,
                streamingText = false,
                statusDetail = "Fala interrompida.",
            )
        }
        addLog("INFO", "Fala interrompida pelo usuário.")
    }

    fun resetHistory() {
        if (!_ui.value.connected) {
            fail("Conecte-se ao PC para apagar o histórico.")
            return
        }
        sendJson(JSONObject().put("type", "reset"))
        _ui.update { it.copy(transcript = "", assistantText = "") }
        addLog("INFO", "Pedido para apagar o histórico da conversa.")
    }

    fun testTone() {
        scope.launch {
            addLog("INFO", "Reproduzindo tom de teste no alto-falante.")
            val ok = player.playTestTone(settings.volume)
            addLog(if (ok) "INFO" else "AVISO",
                if (ok) "Tom de teste concluído." else "Falha ao reproduzir o tom de teste.")
        }
    }

    fun discoverServers(callback: (List<ServerDiscovery.FoundServer>) -> Unit) {
        scope.launch {
            addLog("INFO", "Procurando o BMO na rede local (UDP).")
            val found = ServerDiscovery.discover()
            addLog(
                "INFO",
                if (found.isEmpty()) "Nenhum servidor encontrado na rede."
                else "${found.size} servidor(es) encontrado(s)."
            )
            mainHandler.post { callback(found) }
        }
    }

    fun testConnection(target: AppSettings, callback: (Result<ServiceStatus>) -> Unit) {
        scope.launch {
            val result = runCatching {
                val health = http.newCall(
                    Request.Builder().url("${target.httpUrl}/health").build()
                ).execute()
                health.use {
                    if (!it.isSuccessful) throw IllegalStateException("Servidor respondeu HTTP ${it.code}.")
                }
                val infoRequest = Request.Builder()
                    .url("${target.httpUrl}/api/v1/info")
                    .header("Authorization", "Bearer ${target.token}")
                    .build()
                http.newCall(infoRequest).execute().use { response ->
                    if (response.code == 401) {
                        throw IllegalStateException("Token inválido. Copie o token do arquivo backend/data/token.")
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("O servidor respondeu HTTP ${response.code}.")
                    }
                    val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia.")
                    val json = JSONObject(body)
                    val llm = json.optJSONObject("llm")
                    val stt = json.optJSONObject("stt")
                    val tts = json.optJSONObject("tts")
                    ServiceStatus(
                        protocol = json.optString("protocol"),
                        version = json.optString("version"),
                        llmAvailable = llm?.optBoolean("available") == true,
                        sttAvailable = stt?.optBoolean("available") == true,
                        ttsAvailable = tts?.optBoolean("available") == true,
                        llmModel = llm?.optString("model").orEmpty(),
                        wakeMode = json.optJSONObject("wakeword")?.optString("mode").orEmpty(),
                        serverTime = json.optString("server_time"),
                    )
                }
            }.mapCatching { it }
            mainHandler.post { callback(result) }
        }
    }

    private fun refreshServiceStatus() {
        testConnection(settings) { result ->
            result.onSuccess { status ->
                _ui.update { it.copy(serviceStatus = status, ttsAvailable = status.ttsAvailable) }
                addLog(
                    if (status.llmAvailable && status.sttAvailable && status.ttsAvailable) "INFO" else "AVISO",
                    "Serviços do PC — LLM: ${sim(status.llmAvailable)}, " +
                        "STT: ${sim(status.sttAvailable)}, TTS: ${sim(status.ttsAvailable)}."
                )
            }
            result.onFailure { error ->
                addLog("AVISO", "Diagnóstico do servidor: ${error.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // Eventos do servidor
    // ------------------------------------------------------------------

    private fun handleServerText(text: String) {
        val json = try {
            JSONObject(text)
        } catch (exc: Exception) {
            addLog("ERRO", "O servidor enviou um JSON inválido.")
            return
        }
        val seq = json.optLong("seq", -1)
        if (seq > 0) {
            if (seq <= lastSeq) return
            lastSeq = seq
        }
        when (val type = json.optString("type")) {
            "hello" -> handleHello(json)
            "state" -> handleState(json)
            "transcript" -> {
                val value = json.optString("text")
                _ui.update {
                    it.copy(
                        transcript = value,
                        assistantText = "",
                        streamingText = false,
                        statusDetail = if (value.isBlank()) "Não entendi." else "Fala reconhecida.",
                    )
                }
                addLog("STT", if (value.isBlank()) "(vazio)" else value)
            }
            "assistant.text.delta" -> {
                val id = json.optString("id", "")
                val piece = json.optString("text", "")
                if (id.isNotEmpty() && id != currentAssistantId) {
                    currentAssistantId = id
                    _ui.update { it.copy(assistantText = "") }
                }
                _ui.update {
                    it.copy(
                        assistantText = it.assistantText + piece,
                        streamingText = true,
                        statusDetail = "Respondendo...",
                    )
                }
            }
            "assistant.text.done" -> {
                val answer = json.optString("text", "")
                currentAssistantId = json.optString("id", currentAssistantId)
                _ui.update {
                    it.copy(
                        assistantText = answer,
                        streamingText = false,
                        ttsAvailable = json.optBoolean("audio_available", it.ttsAvailable),
                        statusDetail = "Resposta recebida.",
                    )
                }
                addLog("BMO", if (answer.length > 200) answer.take(200) + "..." else answer)
            }
            "audio.start" -> {
                val rate = json.optInt("sample_rate", 22050)
                val channels = json.optInt("channels", 1)
                currentSampleRate = rate
                currentChannels = channels
                audioStarted = player.start(rate, channels, settings.volume)
                setAudioMuted(true)
                _ui.update {
                    it.copy(
                        bmoState = BmoState.SPEAKING,
                        faceState = BmoState.SPEAKING,
                        statusDetail = "Falando...",
                    )
                }
                if (!audioStarted) addLog("ERRO", "O aparelho não conseguiu abrir a saída de áudio.")
                addLog("INFO", "Áudio do BMO recebido (${json.optInt("duration_ms", 0)} ms, $rate Hz).")
            }
            "audio.stop" -> {
                playbackJob?.cancel()
                player.stop()
                setAudioMuted(false)
                _ui.update {
                    it.copy(
                        bmoState = if (it.listening) BmoState.LISTENING else BmoState.IDLE,
                        faceState = if (it.listening) BmoState.LISTENING else BmoState.IDLE,
                        statusDetail = "Áudio interrompido.",
                    )
                }
                addLog("INFO", "Reprodução interrompida pelo servidor.")
            }
            "audio.unavailable" -> {
                _ui.update { it.copy(ttsAvailable = false) }
                addLog("AVISO", json.optString("message", "Resposta sem áudio."))
            }
            "vad" -> {
                val speaking = json.optBoolean("speaking")
                _ui.update {
                    it.copy(statusDetail = if (speaking) "Ouvindo sua fala..." else "Processando...")
                }
            }
            "wakeword.detected" -> {
                _ui.update { it.copy(statusDetail = "Palavra de ativação reconhecida.") }
                addLog("INFO", "Palavra de ativação detectada.")
            }
            "wakeword.activated" -> {
                addLog("INFO", "Ativação manual aceita pelo servidor.")
            }
            "wakeword.not_detected" -> {
                val message = json.optString("message", "Palavra de ativação não detectada.")
                _ui.update { it.copy(statusDetail = message) }
                addLog("AVISO", message)
            }
            "error" -> handleError(json)
            "pong" -> Unit
            "history.reset" -> {
                _ui.update { it.copy(transcript = "", assistantText = "") }
                addLog("INFO", "Histórico da conversa apagado no PC.")
            }
            "audio.ready" -> addLog("INFO", "Servidor pronto para receber áudio.")
            "hello.ok" -> Unit
            else -> Log.d(TAG, "Evento não tratado: $type")
        }
    }

    private fun handleHello(json: JSONObject) {
        val protocol = json.optString("protocol")
        if (protocol.isNotEmpty() && protocol != "bmo/1") {
            fail("Protocolo $protocol não suportado. Atualize o app ou o backend.")
            client?.close()
            return
        }
        connectTimeoutJob?.cancel()
        val session = json.optString("session_id")
        _ui.update {
            it.copy(
                connection = ConnectionState.CONNECTED,
                bmoState = BmoState.IDLE,
                faceState = BmoState.IDLE,
                statusDetail = "Conectado ao PC. Toque em Ouvir para começar.",
                reconnectAttempt = 0,
                lastError = null,
                serverSessionId = session,
            )
        }
        addLog("INFO", "Conectado ao backend (protocolo $protocol, sessão $session).")
        refreshServiceStatus()
        if (wantListening && capture?.isRunning != true) {
            startListening()
        }
    }

    private fun handleState(json: JSONObject) {
        val state = json.optString("state", "idle")
        val detail = json.optString("detail")
        val mapped = when (state) {
            "listening" -> BmoState.LISTENING
            "thinking" -> BmoState.THINKING
            "speaking" -> BmoState.SPEAKING
            "error" -> BmoState.ERROR
            "idle" -> BmoState.IDLE
            else -> BmoState.IDLE
        }
        if (mapped == BmoState.THINKING || mapped == BmoState.SPEAKING) setAudioMuted(true)
        if (mapped == BmoState.LISTENING || mapped == BmoState.IDLE) {
            if (!player.isActive) setAudioMuted(false)
        }
        if (mapped == BmoState.LISTENING && capture?.isRunning != true && wantListening) {
            // O servidor voltou a ouvir, mas a captura local não está ativa.
            startListening()
        }
        _ui.update {
            it.copy(
                bmoState = mapped,
                faceState = mapped,
                statusDetail = detail.ifBlank { mapped.label },
                lastError = if (mapped == BmoState.ERROR) detail else it.lastError,
            )
        }
        addLog(
            if (mapped == BmoState.ERROR) "ERRO" else "INFO",
            "Estado: ${mapped.label}${if (detail.isNotBlank()) " — $detail" else ""}"
        )
    }

    private fun handleServerAudio(bytes: ByteArray) {
        if (!audioStarted) return
        player.write(bytes)
        if (!_ui.value.streamingText && currentAssistantId.isNotEmpty()) {
            // mantém o texto final; nada a fazer
        }
        _ui.update {
            if (it.bmoState != BmoState.SPEAKING) {
                it.copy(bmoState = BmoState.SPEAKING, faceState = BmoState.SPEAKING)
            } else {
                it
            }
        }
        if (playbackJob?.isActive != true) {
            sendJson(JSONObject().put("type", "playback.started"))
            playbackJob = scope.launch {
                val finished = player.waitForPlayback()
                if (finished) {
                    sendJson(JSONObject().put("type", "playback.done"))
                    delay(250)
                    audioStarted = false
                    setAudioMuted(false)
                    _ui.update {
                        it.copy(
                            bmoState = if (it.listening) BmoState.LISTENING else BmoState.IDLE,
                            faceState = if (it.listening) BmoState.LISTENING else BmoState.IDLE,
                            statusDetail = "Pronto para ouvir.",
                        )
                    }
                    addLog("INFO", "Reprodução concluída.")
                }
            }
        }
    }

    private fun handleError(json: JSONObject) {
        val code = json.optString("code")
        val message = json.optString("message", "Erro no servidor.")
        val fatal = json.optBoolean("fatal", false)
        _ui.update {
            it.copy(
                lastError = message,
                bmoState = BmoState.ERROR,
                faceState = BmoState.ERROR,
                statusDetail = message,
                streamingText = false,
            )
        }
        addLog("ERRO", "$message${if (code.isNotBlank()) " (código: $code)" else ""}")
        if (fatal) {
            client?.close()
            connectionLost(message)
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun sendJson(payload: JSONObject): Boolean {
        val sent = client?.sendJson(payload) ?: false
        if (!sent) Log.w(TAG, "Tentativa de envio sem WebSocket ativo: ${payload.optString("type")}")
        return sent
    }

    private fun fail(message: String) {
        _ui.update {
            it.copy(
                lastError = message,
                statusDetail = message,
                bmoState = BmoState.ERROR,
                faceState = BmoState.ERROR,
            )
        }
        addLog("ERRO", message)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun addLog(level: String, message: String) {
        val entry = LogEntry(
            time = SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("pt-BR")).format(Date()),
            level = level,
            message = message,
        )
        _logs.update { (it + entry).takeLast(300) }
        Log.i(TAG, "[$level] $message")
    }

    private fun sim(value: Boolean) = if (value) "ok" else "indisponível"

    companion object {
        private const val TAG = "BmoEngine"

        @Volatile
        private var instance: BmoEngine? = null

        fun get(context: Context): BmoEngine {
            return instance ?: synchronized(this) {
                instance ?: BmoEngine(context).also { instance = it }
            }
        }
    }
}
