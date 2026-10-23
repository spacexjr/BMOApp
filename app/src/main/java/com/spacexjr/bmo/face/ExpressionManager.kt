package com.spacexjr.bmo.face

import android.content.res.AssetManager
import android.util.Log

data class FaceAnimation(
    val frames: List<FaceFrame>,
    val fps: Float,
    val mode: Mode,
    val template: TemplateSpec? = null,
) {
    enum class Mode { STATIC, LOOP, PINGPONG, ONCE }
}

data class FaceFrame(val asset: String, val params: Map<String, Double> = emptyMap())

data class TemplateSpec(val asset: String, val param: String, val from: Double, val to: Double, val steps: Int)

class ExpressionManager(private val assets: AssetManager) {

    private val available: Set<String> by lazy {
        (assets.list("faces") ?: emptyArray())
            .filter { it.endsWith(".svg") }
            .map { it.removeSuffix(".svg") }
            .toSet()
    }

    fun availableExpressions(): List<String> = available.sorted()

    fun has(expression: String): Boolean = available.contains(expression)

    fun resolve(state: BmoState, defaultExpression: String = "neutral"): String {
        val wanted = when (state) {
            BmoState.RESTING -> canonical(defaultExpression)
            BmoState.LISTENING -> "listening"
            BmoState.THINKING -> "thinking"
            BmoState.SPEAKING -> "speaking"
            BmoState.HAPPY -> "happy"
            BmoState.SURPRISED -> "surprised"
            BmoState.SAD -> "sad"
            BmoState.CONFUSED -> "concerned"
            BmoState.ERROR -> "dismayed"
            BmoState.OFF -> "sleeping"
        }
        return if (has(wanted)) wanted else fallbackFor(state)
    }

    fun animationFor(state: BmoState, defaultExpression: String = "neutral"): FaceAnimation {
        val name = resolve(state, defaultExpression)
        return animationForExpression(name)
    }

    fun animationForExpression(expression: String): FaceAnimation {
        val name = canonical(expression)
        return when {
            name == "speaking" && !has("speaking_0") -> staticAnimation(name)
            name == "speaking" -> FaceAnimation(
                frames = (0..5).map { FaceFrame("speaking_$it") },
                fps = 13f,
                mode = FaceAnimation.Mode.PINGPONG,
            )
            name == "sleeping" -> templateAnimation(name, "t", 0.0, 1.0, 8, 5f, FaceAnimation.Mode.LOOP)
            name == "whistle" -> templateAnimation(name, "t", 0.0, 1.0, 6, 4f, FaceAnimation.Mode.LOOP)
            name == "look_around" -> templateAnimation(name, "x", -1.0, 1.0, 5, 3f, FaceAnimation.Mode.PINGPONG)
            else -> staticAnimation(name)
        }
    }

    private fun templateAnimation(
        name: String,
        param: String,
        from: Double,
        to: Double,
        steps: Int,
        fps: Float,
        mode: FaceAnimation.Mode,
    ): FaceAnimation {
        if (!has(name)) return staticAnimation("neutral")
        val frames = (0 until steps).map { index ->
            val value = if (steps == 1) from else from + (to - from) * index / (steps - 1)
            FaceFrame(name, mapOf(param to value))
        }
        return FaceAnimation(
            frames = frames,
            fps = fps,
            mode = mode,
            template = TemplateSpec(name, param, from, to, steps),
        )
    }

    private fun staticAnimation(name: String): FaceAnimation {
        val resolved = if (has(name)) name else fallbackStatic(name)
        return FaceAnimation(listOf(FaceFrame(resolved)), 0f, FaceAnimation.Mode.STATIC)
    }

    private fun fallbackFor(state: BmoState): String = when (state) {
        BmoState.OFF -> if (has("dead")) "dead" else "neutral"
        BmoState.ERROR -> if (has("glitch")) "glitch" else "neutral"
        BmoState.SAD -> if (has("gloomy")) "gloomy" else "neutral"
        BmoState.SURPRISED -> if (has("excited")) "excited" else "neutral"
        else -> "neutral"
    }

    private fun fallbackStatic(name: String): String = when (name) {
        "happy", "smile", "excited" -> if (has("neutral")) "neutral" else available.firstOrNull() ?: "neutral"
        else -> if (has("neutral")) "neutral" else available.firstOrNull() ?: "neutral"
    }

    fun canonical(expression: String): String {
        val value = expression.trim().lowercase()
        return when (value) {
            "", "idle", "resting", "neutral" -> "neutral"
            "listening", "listen" -> "listening"
            "thinking", "processing" -> "thinking"
            "speaking", "talking" -> "speaking"
            "surprised", "surprise", "shocked" -> "surprised"
            "confused", "concerned" -> "concerned"
            "error" -> "dismayed"
            "off", "sleep", "asleep", "sleeping" -> "sleeping"
            else -> if (has(value)) value else "neutral"
        }
    }

    companion object {
        private const val TAG = "BmoExpressions"
        fun logMissing(name: String) = Log.w(TAG, "expressão ausente nos assets: $name")
    }
}
