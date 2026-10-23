package com.spacexjr.bmo.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import com.spacexjr.bmo.data.LocalModelStore
import java.io.File

/**
 * Loads the bundled Vosk pt-BR model exactly once and shares it between the
 * command recogniser and the wake-word detector.
 */
class VoskModelProvider(private val context: Context) {

    private val mutex = Mutex()
    @Volatile private var model: Model? = null

    suspend fun get(onProgress: (LocalModelStore.ExtractionProgress) -> Unit = {}): Model =
        withContext(Dispatchers.IO) {
            model?.let { return@withContext it }
            mutex.withLock {
                model?.let { return@withContext it }
                val dir = LocalModelStore.ensureVosk(context, onProgress)
                val loaded = Model(dir.absolutePath)
                model = loaded
                loaded
            }
        }

    fun close() {
        model?.close()
        model = null
    }

    fun isLoaded(): Boolean = model != null
}
