package com.spacexjr.bmo.data

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * The speech models are shipped inside the APK as zip files. They are copied
 * once to the app's private storage so the native engines can open them by
 * path. No network access is ever required.
 */
object LocalModelStore {

    private const val VOSK_VERSION = "vosk-pt-0.3-v1"
    private const val TTS_VERSION = "piper-pt-br-faber-v1"

    data class ExtractionProgress(val label: String, val percent: Int)

    suspend fun ensureVosk(
        context: Context,
        onProgress: (ExtractionProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "models/vosk-pt")
        val target = File(root, "vosk-model-small-pt-0.3")
        val marker = File(root, ".installed")
        if (marker.readTextOrNull() == VOSK_VERSION && File(target, "final.mdl").exists()) {
            onProgress(ExtractionProgress("Reconhecimento de voz pronto", 100))
            return@withContext target
        }
        extract(context, "models/vosk-pt.zip", root, "Reconhecimento de voz", onProgress)
        marker.writeText(VOSK_VERSION)
        target
    }

    suspend fun ensureTts(
        context: Context,
        onProgress: (ExtractionProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "models/tts-pt")
        val target = File(root, "vits-piper-pt_BR-faber-medium")
        val marker = File(root, ".installed")
        if (marker.readTextOrNull() == TTS_VERSION && File(target, "pt_BR-faber-medium.onnx").exists()) {
            onProgress(ExtractionProgress("Síntese de voz pronta", 100))
            return@withContext target
        }
        extract(context, "models/tts-pt.zip", root, "Síntese de voz", onProgress)
        marker.writeText(TTS_VERSION)
        target
    }

    private fun extract(
        context: Context,
        assetPath: String,
        target: File,
        label: String,
        onProgress: (ExtractionProgress) -> Unit,
    ) {
        val needed = 220L * 1024 * 1024
        val stat = StatFs(context.filesDir.absolutePath)
        if (stat.availableBytes < needed) {
            throw IllegalStateException(
                "Espaço insuficiente para instalar $label. Libere pelo menos 220 MB."
            )
        }
        target.mkdirs()
        context.assets.open(assetPath).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var lastPercent = -1
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = File(target, entry.name)
                    val canonical = output.canonicalFile
                    if (!canonical.path.startsWith(target.canonicalFile.path + File.separator)) {
                        throw IllegalStateException("Entrada de zip inválida: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        canonical.mkdirs()
                    } else {
                        canonical.parentFile?.mkdirs()
                        FileOutputStream(canonical).use { out -> zip.copyTo(out) }
                    }
                    count++
                    if (count % 12 == 0) {
                        val percent = (count * 100 / 90).coerceIn(0, 99)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(ExtractionProgress(label, percent))
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        onProgress(ExtractionProgress("$label pronta", 100))
    }

    private fun File.readTextOrNull(): String? = try {
        if (exists()) readText() else null
    } catch (_: Throwable) {
        null
    }
}
