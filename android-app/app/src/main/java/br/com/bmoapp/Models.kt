package br.com.bmoapp

/** Configuração persistida no aparelho. O token é guardado cifrado. */
data class AppSettings(
    val host: String = "",
    val port: Int = 8765,
    val token: String = "",
    val volume: Float = 0.9f,
    val keepScreenOn: Boolean = false,
    val backgroundListening: Boolean = false,
    val autoConnect: Boolean = true,
) {
    val isConfigured: Boolean get() = host.isNotBlank() && port in 1..65535
    val wsUrl: String
        get() {
            val cleanHost = host.trim().removePrefix("ws://").removePrefix("http://").trimEnd('/')
            return "ws://$cleanHost:$port/ws"
        }
    val httpUrl: String
        get() {
            val cleanHost = host.trim().removePrefix("ws://").removePrefix("http://").trimEnd('/')
            return "http://$cleanHost:$port"
        }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

enum class BmoState(val label: String) {
    DISCONNECTED("Desconectado"),
    CONNECTING("Conectando"),
    IDLE("Aguardando"),
    LISTENING("Ouvindo"),
    THINKING("Pensando"),
    SPEAKING("Falando"),
    ERROR("Erro"),
}

data class ServiceStatus(
    val protocol: String = "",
    val version: String = "",
    val llmAvailable: Boolean = false,
    val sttAvailable: Boolean = false,
    val ttsAvailable: Boolean = false,
    val llmModel: String = "",
    val wakeMode: String = "",
    val serverTime: String = "",
)

data class LogEntry(
    val time: String,
    val level: String,
    val message: String,
)

data class BmoUiState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val bmoState: BmoState = BmoState.DISCONNECTED,
    val statusDetail: String = "Configure o servidor do PC para começar.",
    val listening: Boolean = false,
    val micLevel: Float = 0f,
    val transcript: String = "",
    val assistantText: String = "",
    val streamingText: Boolean = false,
    val lastError: String? = null,
    val ttsAvailable: Boolean = true,
    val serviceStatus: ServiceStatus? = null,
    val reconnectAttempt: Int = 0,
    val micTesting: Boolean = false,
    val serverSessionId: String = "",
    val faceState: BmoState = BmoState.DISCONNECTED,
) {
    val connected: Boolean get() = connection == ConnectionState.CONNECTED
}
