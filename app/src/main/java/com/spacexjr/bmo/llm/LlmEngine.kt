package com.spacexjr.bmo.llm

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

sealed interface ModelStatus {
    data object NotLoaded : ModelStatus
    data object Loading : ModelStatus
    data class Ready(val name: String, val info: String, val supportsThinking: Boolean, val chatTemplate: String) : ModelStatus
    data class Error(val message: String) : ModelStatus
}

class LlamaCppBridge {
    private var handle: Long = 0L

    private external fun nativeBackendInit()
    private external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nBatch: Int): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeCancel(handle: Long)
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        seed: Int,
    ): String?
    private external fun nativeSupportsThinking(handle: Long): Boolean
    private external fun nativeChatTemplate(handle: Long): String
    private external fun nativeInfo(handle: Long): String

    val isLoaded: Boolean get() = handle != 0L

    fun load(path: String, contextSize: Int, threads: Int): ModelStatus.Ready {
        if (handle != 0L) throw IllegalStateException("Já existe um modelo carregado.")
        nativeBackendInit()
        val value = nativeLoadModel(path, contextSize, threads, 512)
        if (value == 0L) throw IllegalStateException("O motor nativo não retornou um contexto válido.")
        handle = value
        val supportsThinking = nativeSupportsThinking(handle)
        val template = nativeChatTemplate(handle)
        return ModelStatus.Ready(
            name = File(path).name,
            info = nativeInfo(handle),
            supportsThinking = supportsThinking,
            chatTemplate = template,
        )
    }

    fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 40,
        seed: Int = -1,
    ): String {
        if (handle == 0L) throw IllegalStateException("Modelo não carregado.")
        val actualSeed = if (seed < 0) (System.nanoTime() and 0x7fffffff).toInt() else seed
        return nativeGenerate(handle, prompt, maxTokens, temperature, topP, topK, actualSeed)
            ?: throw IllegalStateException("A inferência não produziu resposta.")
    }

    fun cancel() {
        if (handle != 0L) nativeCancel(handle)
    }

    fun unload() {
        if (handle != 0L) {
            nativeFreeModel(handle)
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "LlamaCppBridge"

        init {
            try {
                System.loadLibrary("bmo")
            } catch (t: Throwable) {
                Log.e(TAG, "Biblioteca nativa bmo não carregou", t)
            }
        }

        fun nativeAvailable(): Boolean = try {
            System.loadLibrary("bmo")
            true
        } catch (t: Throwable) {
            false
        }
    }
}

class LlmEngine {
    private val bridge = LlamaCppBridge()
    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotLoaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    private var loadedSearchPath: String = ""
    private var loadedTemplate: String = ""
    private var loadedContextSize: Int = 0
    private var loadedThreads: Int = 0

    val isLoaded: Boolean get() = bridge.isLoaded

    fun validateModelFile(file: File): String? {
        if (!file.exists()) return "Arquivo não encontrado: ${file.absolutePath}"
        if (!file.canRead()) return "Sem permissão de leitura para o arquivo."
        if (file.length() < 1_000_000L) return "O arquivo é pequeno demais para ser um modelo GGUF válido."
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                if (raf.read(magic) != 4) return "Não foi possível ler o cabeçalho do arquivo."
                if (String(magic) != "GGUF") return "O arquivo não possui o cabeçalho GGUF esperado."
            }
            with(file.inputStream()) {
                val buffer = ByteArray(1 shl 20)
                var total = 0L
                var read: Int
                while (read(buffer).also { read = it } > 0) {
                    total += read
                    if (total > 8L * 1024 * 1024) break
                }
            }
            null
        } catch (t: Throwable) {
            "Não foi possível validar o modelo: ${t.message}"
        }
    }

    suspend fun load(path: String, contextSize: Int, threads: Int): Result<ModelStatus.Ready> =
        withContext(Dispatchers.IO) {
            if (path.isBlank()) {
                val error = ModelStatus.Error("Nenhum modelo GGUF selecionado.")
                _status.value = error
                return@withContext Result.failure(IllegalStateException(error.message))
            }
            val file = File(path)
            validateModelFile(file)?.let {
                val error = ModelStatus.Error(it)
                _status.value = error
                return@withContext Result.failure(IllegalStateException(it))
            }
            // Evita descarregar e recarregar centenas de MB quando a
            // atividade é recriada com o mesmo modelo e os mesmos parâmetros.
            val current = _status.value
            if (bridge.isLoaded &&
                current is ModelStatus.Ready &&
                path == loadedSearchPath &&
                contextSize == loadedContextSize &&
                threads == loadedThreads
            ) {
                return@withContext Result.success(current)
            }
            unloadInternal()
            _status.value = ModelStatus.Loading
            try {
                val ready = bridge.load(path, contextSize, threads)
                loadedSearchPath = path
                loadedTemplate = ready.chatTemplate
                _status.value = ready
                Result.success(ready)
            } catch (t: Throwable) {
                bridge.unload()
                val message = t.message ?: t.javaClass.simpleName
                _status.value = ModelStatus.Error(message)
                if (t is OutOfMemoryError) {
                    Result.failure(IllegalStateException("Memória insuficiente para carregar o modelo. Feche outros aplicativos ou use uma quantização menor."))
                } else {
                    Result.failure(t)
                }
            }
        }

    suspend fun generate(prompt: String, maxTokens: Int): Result<String> = withContext(Dispatchers.IO) {
        if (!bridge.isLoaded) {
            return@withContext Result.failure(IllegalStateException("Modelo indisponível. Selecione e carregue um GGUF nas configurações."))
        }
        try {
            Result.success(bridge.generate(prompt, maxTokens))
        } catch (t: Throwable) {
            val msg = t.message ?: "Falha na inferência."
            if (msg.contains("cancelada", ignoreCase = true)) {
                Result.failure(CancellationException(msg))
            } else {
                Result.failure(t)
            }
        }
    }

    fun cancel() = bridge.cancel()

    fun unload() {
        unloadInternal()
        _status.value = ModelStatus.NotLoaded
    }

    private fun unloadInternal() {
        bridge.unload()
        loadedSearchPath = ""
        loadedTemplate = ""
    }

    fun templateSupportsThinking(): Boolean =
        (status.value as? ModelStatus.Ready)?.supportsThinking == true

    fun usingModelPath(): String = loadedSearchPath
}
