package br.com.bmoapp.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import br.com.bmoapp.BmoState
import kotlinx.coroutines.delay

/**
 * Controlador de expressões. Usa somente os PNGs fornecidos pelo projeto
 * original. "happy" e "confused" não existem nos assets; nesses casos usamos
 * expressões existentes (idle e thinking), sem criar arte nova.
 */
object FaceController {
    fun framesFor(expression: String): List<String> = when (expression.lowercase()) {
        "listening" -> listOf("listening/listen 01.png", "listening/listen 02.png")
        "thinking" -> listOf(
            "thinking/thinking 01.png",
            "thinking/thinking 02.png",
            "thinking/thinking 03.png",
            "thinking/thinking 04.png",
        )
        "speaking" -> listOf(
            "speaking/speaking 01.png",
            "speaking/speaking 02.png",
            "speaking/speaking 03.png",
        )
        "error" -> listOf("error/error 01.png")
        "warmup", "connecting", "reconnecting" -> listOf("warmup/warmup 01.png")
        "happy" -> listOf("idle/idle 01.png")
        "confused" -> listOf("thinking/thinking 03.png")
        "capturing" -> listOf("capturing/capturing 01.png")
        else -> listOf("idle/idle 01.png")
    }

    fun intervalFor(expression: String): Long = when (expression.lowercase()) {
        "thinking" -> 180
        "speaking" -> 130
        "listening" -> 380
        "warmup", "connecting", "reconnecting" -> 500
        else -> 700
    }

    fun expressionFor(state: BmoState): String = when (state) {
        BmoState.DISCONNECTED -> "idle"
        BmoState.CONNECTING -> "warmup"
        BmoState.IDLE -> "idle"
        BmoState.LISTENING -> "listening"
        BmoState.THINKING -> "thinking"
        BmoState.SPEAKING -> "speaking"
        BmoState.ERROR -> "error"
    }
}

@Composable
fun BmoFace(
    state: BmoState,
    modifier: Modifier = Modifier,
    fullBleed: Boolean = false,
) {
    val context = LocalContext.current
    val expression = FaceController.expressionFor(state)
    val paths = remember(expression) { FaceController.framesFor(expression) }
    val frames: List<ImageBitmap> = remember(paths) {
        paths.mapNotNull { path ->
            runCatching {
                context.assets.open("faces/$path").use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    var frameIndex by remember(expression) { mutableIntStateOf(0) }
    LaunchedEffect(expression, frames.size) {
        if (frames.size <= 1) return@LaunchedEffect
        while (true) {
            delay(FaceController.intervalFor(expression))
            frameIndex = (frameIndex + 1) % frames.size
        }
    }
    val shape = if (fullBleed) RoundedCornerShape(0.dp) else RoundedCornerShape(34.dp)
    Box(
        modifier = modifier
            .then(
                if (fullBleed) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().aspectRatio(800f / 480f)
                }
            )
            .clip(shape)
            .background(BmoGreen),
    ) {
        val frame = frames.getOrNull(frameIndex % frames.size.coerceAtLeast(1))
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = "Rosto do BMO: ${state.label}",
                modifier = if (fullBleed) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
