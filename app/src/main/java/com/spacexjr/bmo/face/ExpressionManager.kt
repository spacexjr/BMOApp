package com.spacexjr.bmo.face

import android.content.res.AssetManager

/**
 * Catálogo das sequências de quadros originais do Be More Agent.
 *
 * Os arquivos são cópias exatas dos PNGs presentes em `faces/` no projeto
 * original. Nenhum rosto é desenhado, gerado ou alterado pelo aplicativo:
 * apenas os quadros existentes são alternados.
 */
data class FaceAnimation(
    val frames: List<String>,
    val frameDurationMs: Long,
    val loop: Boolean,
)

data class FaceExpression(
    val id: String,
    val label: String,
    val frames: List<String>,
    val frameDurationMs: Long,
)

class ExpressionManager(private val assets: AssetManager) {

    private val expressions: Map<String, FaceExpression> = linkedMapOf(
        "idle" to FaceExpression(
            id = "idle",
            label = "Repouso",
            frames = listOf("faces/idle/idle_01.png"),
            frameDurationMs = 0L,
        ),
        "listening" to FaceExpression(
            id = "listening",
            label = "Escutando",
            frames = listOf(
                "faces/listening/listen_01.png",
                "faces/listening/listen_02.png",
            ),
            frameDurationMs = 450L,
        ),
        "thinking" to FaceExpression(
            id = "thinking",
            label = "Processando",
            frames = listOf(
                "faces/thinking/thinking_01.png",
                "faces/thinking/thinking_02.png",
                "faces/thinking/thinking_03.png",
                "faces/thinking/thinking_04.png",
            ),
            frameDurationMs = 400L,
        ),
        "speaking" to FaceExpression(
            id = "speaking",
            label = "Falando",
            frames = listOf(
                "faces/speaking/speaking_01.png",
                "faces/speaking/speaking_02.png",
                "faces/speaking/speaking_03.png",
            ),
            frameDurationMs = 90L,
        ),
        "error" to FaceExpression(
            id = "error",
            label = "Erro / confusão",
            frames = listOf("faces/error/error_01.png"),
            frameDurationMs = 0L,
        ),
        "warmup" to FaceExpression(
            id = "warmup",
            label = "Inicialização",
            frames = listOf("faces/warmup/warmup_01.png"),
            frameDurationMs = 0L,
        ),
    )

    /** Expressões que realmente possuem quadros no projeto original. */
    fun availableExpressions(): List<String> = expressions.keys.toList()

    fun labelFor(expression: String): String =
        expressions[canonical(expression)]?.label ?: canonical(expression)

    fun has(expression: String): Boolean = expressions.containsKey(canonical(expression))

    fun canonical(expression: String): String {
        val value = expression.trim().lowercase()
        return when (value) {
            "", "idle", "resting", "repouso", "neutral" -> "idle"
            "listening", "listen", "escutando" -> "listening"
            "thinking", "processing", "processando" -> "thinking"
            "speaking", "talking", "falando" -> "speaking"
            "error", "erro", "confused", "confuso" -> "error"
            "warmup", "inicializacao", "inicialização" -> "warmup"
            "happy", "feliz" -> "listening"
            "sad", "triste" -> "thinking"
            "surprised", "surpreso" -> "listening"
            "off", "sleeping", "dormindo" -> "idle"
            else -> if (expressions.containsKey(value)) value else "idle"
        }
    }

    fun resolve(state: BmoState, defaultExpression: String = "idle"): String = when (state) {
        BmoState.RESTING -> canonical(defaultExpression)
        BmoState.LISTENING -> "listening"
        BmoState.THINKING -> "thinking"
        BmoState.SPEAKING -> "speaking"
        BmoState.HAPPY -> "listening"
        BmoState.SURPRISED -> "listening"
        BmoState.SAD -> "thinking"
        BmoState.CONFUSED -> "error"
        BmoState.ERROR -> "error"
        BmoState.OFF -> "idle"
    }

    fun animationFor(state: BmoState, defaultExpression: String = "idle"): FaceAnimation =
        animationForExpression(resolve(state, defaultExpression))

    fun animationForExpression(expression: String): FaceAnimation {
        val item = expressions[canonical(expression)]
            ?: expressions.getValue("idle")
        return FaceAnimation(
            frames = item.frames,
            frameDurationMs = item.frameDurationMs,
            loop = item.frames.size > 1,
        )
    }

    fun firstFrame(expression: String): String =
        animationForExpression(expression).frames.first()

    fun assetExists(path: String): Boolean = try {
        assets.open(path).close()
        true
    } catch (_: Throwable) {
        false
    }
}
