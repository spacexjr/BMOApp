package com.spacexjr.bmo.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Desenha os PNGs originais do BMO em tela cheia.
 *
 * O conteúdo nunca é redesenhado: os bitmaps são apenas decodificados,
 * redimensionados proporcionalmente (center-crop) e alternados conforme o
 * estado do assistente. O estado "falando" só é exibido enquanto existe áudio
 * realmente sendo reproduzido.
 */
class BmoFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val assets = context.assets
    private val expressions = ExpressionManager(assets)
    private val handler = Handler(Looper.getMainLooper())

    private val bitmapCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val backgroundPaint = Paint().apply { color = BACKGROUND_COLOR }
    private val destination = RectF()
    private val source = Rect()

    private var currentState = BmoState.RESTING
    private var defaultExpression = "idle"
    private var faceScale = 1.0f
    private var animation = expressions.animationFor(BmoState.RESTING)
    private var currentBitmap: Bitmap? = null
    private var previousBitmap: Bitmap? = null
    private var transitionStart = 0L
    private var frameIndex = 0
    private var stopped = false
    private var previewUntil = 0L

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (stopped) return
            advanceFrame()
            if (animation.frameDurationMs > 0L && animation.frames.size > 1) {
                handler.postDelayed(this, animation.frameDurationMs)
            }
        }
    }

    init {
        setBackgroundColor(BACKGROUND_COLOR)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setState(state: BmoState) {
        if (stopped) return
        currentState = state
        if (SystemClock.uptimeMillis() >= previewUntil) {
            applyAnimation(expressions.animationFor(state, defaultExpression), crossfade = true)
        }
    }

    fun setDefaultExpression(expression: String) {
        defaultExpression = expressions.canonical(expression)
        if (currentState == BmoState.RESTING && SystemClock.uptimeMillis() >= previewUntil) {
            applyAnimation(expressions.animationFor(currentState, defaultExpression), crossfade = true)
        }
    }

    fun setFaceScale(scale: Float) {
        faceScale = scale.coerceIn(0.75f, 1.2f)
        updateDestination()
        invalidate()
    }

    fun currentExpressionName(): String = expressions.resolve(currentState, defaultExpression)

    fun availableExpressions(): List<String> = expressions.availableExpressions()

    fun labelFor(expression: String): String = expressions.labelFor(expression)

    /** Mostra uma expressão por alguns segundos e depois volta ao estado real. */
    fun previewExpression(expression: String, durationMs: Long = 3000L) {
        if (stopped) return
        previewUntil = SystemClock.uptimeMillis() + durationMs
        applyAnimation(expressions.animationForExpression(expression), crossfade = true)
        handler.postDelayed({
            if (stopped) return@postDelayed
            previewUntil = 0L
            applyAnimation(expressions.animationFor(currentState, defaultExpression), crossfade = true)
        }, durationMs)
    }

    /** Expressão estática usada pela pré-visualização das configurações. */
    fun setPreviewExpression(expression: String) {
        previewUntil = Long.MAX_VALUE
        applyAnimation(expressions.animationForExpression(expression), crossfade = true)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateDestination()
        bitmapCache.evictAll()
        currentBitmap = null
        previousBitmap = null
        applyAnimation(animation, crossfade = false)
    }

    private fun updateDestination() {
        if (width <= 0 || height <= 0) return
        val viewRatio = width.toFloat() / height.toFloat()
        val imageRatio = 800f / 480f
        val baseWidth: Float
        val baseHeight: Float
        if (viewRatio > imageRatio) {
            baseWidth = width.toFloat()
            baseHeight = baseWidth / imageRatio
        } else {
            baseHeight = height.toFloat()
            baseWidth = baseHeight * imageRatio
        }
        val drawWidth = baseWidth * faceScale
        val drawHeight = baseHeight * faceScale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        destination.set(left, top, left + drawWidth, top + drawHeight)
    }

    private fun applyAnimation(next: FaceAnimation, crossfade: Boolean) {
        handler.removeCallbacks(frameRunnable)
        animation = next
        frameIndex = 0
        showFrame(animation.frames.firstOrNull(), crossfade)
        if (animation.frames.size > 1 && animation.frameDurationMs > 0L) {
            handler.postDelayed(frameRunnable, animation.frameDurationMs)
        }
    }

    private fun advanceFrame() {
        if (animation.frames.size <= 1) return
        frameIndex = if (animation.loop) {
            (frameIndex + 1) % animation.frames.size
        } else {
            min(frameIndex + 1, animation.frames.size - 1)
        }
        showFrame(animation.frames[frameIndex], crossfade = false)
    }

    private fun showFrame(assetPath: String?, crossfade: Boolean) {
        if (assetPath == null) return
        val bitmap = decode(assetPath) ?: return
        if (crossfade && currentBitmap != null && currentBitmap !== bitmap) {
            previousBitmap = currentBitmap
            transitionStart = SystemClock.uptimeMillis()
        } else {
            previousBitmap = null
        }
        currentBitmap = bitmap
        invalidate()
    }

    private fun decode(path: String): Bitmap? {
        bitmapCache.get(path)?.let { return it }
        return try {
            val bitmap = assets.open(path).use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                Log.e(TAG, "PNG inválido ou ausente: $path")
                null
            } else {
                bitmapCache.put(path, bitmap)
                bitmap
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao abrir $path", t)
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val current = currentBitmap ?: return
        val previous = previousBitmap
        if (previous != null) {
            val progress = ((SystemClock.uptimeMillis() - transitionStart) / 150f).coerceIn(0f, 1f)
            paint.alpha = ((1f - progress) * 255f).toInt()
            drawBitmap(canvas, previous)
            paint.alpha = (progress * 255f).toInt()
            drawBitmap(canvas, current)
            paint.alpha = 255
            if (progress < 1f) postInvalidateOnAnimation()
        } else {
            paint.alpha = 255
            drawBitmap(canvas, current)
        }
    }

    private fun drawBitmap(canvas: Canvas, bitmap: Bitmap) {
        source.set(0, 0, bitmap.width, bitmap.height)
        canvas.drawBitmap(bitmap, source, destination, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopped = false
        updateDestination()
        applyAnimation(expressions.animationFor(currentState, defaultExpression), crossfade = false)
    }

    override fun onDetachedFromWindow() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        bitmapCache.evictAll()
        currentBitmap = null
        previousBitmap = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "BmoFaceView"
        private val BACKGROUND_COLOR = Color.rgb(189, 255, 203)
    }
}
