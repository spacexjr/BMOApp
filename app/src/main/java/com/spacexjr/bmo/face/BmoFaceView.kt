package com.spacexjr.bmo.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.view.View
import com.caverock.androidsvg.SVG
import kotlin.math.min
import kotlin.random.Random

class BmoFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val assets = context.assets
    private val renderer = SvgTemplateRenderer(assets)
    private val expressions = ExpressionManager(assets)
    private val handler = Handler(Looper.getMainLooper())

    private val bitmapCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val backgroundPaint = Paint().apply { color = Color.rgb(144, 229, 200) }
    private val faceRect = RectF()

    private var currentState = BmoState.RESTING
    private var defaultExpression = "neutral"
    private var faceScale = 1.0f
    private var animation: FaceAnimation = expressions.animationFor(BmoState.RESTING)
    private var currentBitmap: Bitmap? = null
    private var previousBitmap: Bitmap? = null
    private var transitionStart = 0L
    private var frameIndex = 0
    private var direction = 1
    private var lastFrameAt = 0L
    private var transientUntil = 0L
    private var stopped = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (stopped) return
            advanceFrame()
            val delay = if (animation.fps > 0f) (1000f / animation.fps).toLong() else 1000L
            handler.postDelayed(this, delay.coerceAtLeast(16L))
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setBackgroundColor(Color.rgb(144, 229, 200))
    }

    fun setState(state: BmoState) {
        if (stopped) return
        currentState = state
        if (transientUntil == 0L || SystemClock.uptimeMillis() > transientUntil) {
            applyAnimation(expressions.animationFor(state, defaultExpression), crossfade = true)
        }
        scheduleBlink()
    }

    fun setDefaultExpression(expression: String) {
        defaultExpression = expression
        if (currentState == BmoState.RESTING) {
            applyAnimation(expressions.animationFor(currentState, defaultExpression), crossfade = true)
        }
    }

    fun setFaceScale(scale: Float) {
        faceScale = scale.coerceIn(0.7f, 1.2f)
        updateFaceRect()
        invalidate()
    }

    fun currentExpressionName(): String = expressions.resolve(currentState, defaultExpression)

    fun availableExpressions(): List<String> = expressions.availableExpressions()

    fun testExpression(expression: String) {
        val saved = currentState
        applyAnimation(expressions.animationForExpression(expression), crossfade = true)
        currentState = saved
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateFaceRect()
        bitmapCache.evictAll()
        currentBitmap = null
        previousBitmap = null
        applyAnimation(animation, crossfade = false)
    }

    private fun updateFaceRect() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        var faceWidth = min(viewWidth * faceScale, viewHeight * 4f / 3f * faceScale)
        var faceHeight = faceWidth * 3f / 4f
        if (faceHeight > viewHeight * faceScale) {
            faceHeight = viewHeight * faceScale
            faceWidth = faceHeight * 4f / 3f
        }
        val left = (viewWidth - faceWidth) / 2f
        val top = (viewHeight - faceHeight) / 2f
        faceRect.set(left, top, left + faceWidth, top + faceHeight)
    }

    private fun applyAnimation(next: FaceAnimation, crossfade: Boolean) {
        handler.removeCallbacks(frameRunnable)
        animation = next
        frameIndex = 0
        direction = 1
        lastFrameAt = 0L
        transientUntil = 0L
        showFrame(animation.frames.first(), crossfade)
        if (animation.mode != FaceAnimation.Mode.STATIC && animation.frames.size > 1) {
            handler.postDelayed(frameRunnable, (1000f / animation.fps).toLong().coerceAtLeast(16L))
        }
    }

    private fun advanceFrame() {
        if (animation.frames.size <= 1) return
        when (animation.mode) {
            FaceAnimation.Mode.LOOP -> frameIndex = (frameIndex + 1) % animation.frames.size
            FaceAnimation.Mode.PINGPONG -> {
                frameIndex += direction
                if (frameIndex >= animation.frames.size - 1) {
                    frameIndex = animation.frames.size - 1
                    direction = -1
                } else if (frameIndex <= 0) {
                    frameIndex = 0
                    direction = 1
                }
            }
            FaceAnimation.Mode.ONCE -> {
                if (frameIndex >= animation.frames.size - 1) return
                frameIndex++
            }
            FaceAnimation.Mode.STATIC -> return
        }
        showFrame(animation.frames[frameIndex], crossfade = false)
    }

    private fun showFrame(frame: FaceFrame, crossfade: Boolean) {
        val bitmap = renderFrame(frame) ?: return
        if (crossfade && currentBitmap != null && currentBitmap !== bitmap && !stopped) {
            previousBitmap = currentBitmap
            transitionStart = SystemClock.uptimeMillis()
        } else {
            previousBitmap = null
        }
        currentBitmap = bitmap
        invalidate()
    }

    private fun showTransient(asset: String, durationMs: Long) {
        if (stopped) return
        val bitmap = renderFrame(FaceFrame(asset)) ?: return
        previousBitmap = currentBitmap
        transitionStart = SystemClock.uptimeMillis()
        currentBitmap = bitmap
        transientUntil = SystemClock.uptimeMillis() + durationMs
        invalidate()
        handler.postDelayed({
            if (stopped) return@postDelayed
            transientUntil = 0L
            applyAnimation(expressions.animationFor(currentState, defaultExpression), crossfade = true)
        }, durationMs)
    }

    private fun renderFrame(frame: FaceFrame): Bitmap? {
        val targetWidth = faceRect.width().toInt().coerceAtLeast(1)
        val targetHeight = faceRect.height().toInt().coerceAtLeast(1)
        val key = buildString {
            append(frame.asset)
            frame.params.toSortedMap().forEach { (k, v) -> append('|').append(k).append('=').append(v) }
            append('|').append(targetWidth).append('x').append(targetHeight)
        }
        bitmapCache.get(key)?.let { return it }
        return try {
            val svgText = if (frame.params.isEmpty()) {
                renderer.raw(frame.asset)
            } else {
                renderer.render(frame.asset, frame.params)
            }
            val svg = SVG.getFromString(svgText)
            svg.setDocumentWidth(targetWidth.toString())
            svg.setDocumentHeight(targetHeight.toString())
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(144, 229, 200))
            svg.renderToCanvas(canvas)
            bitmapCache.put(key, bitmap)
            bitmap
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao renderizar ${frame.asset}", t)
            null
        }
    }

    private fun scheduleBlink() {
        handler.removeCallbacks(blinkRunnable)
        if (currentState != BmoState.RESTING) return
        handler.postDelayed(blinkRunnable, Random.nextLong(4_500L, 8_500L))
    }

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (stopped || currentState != BmoState.RESTING) return
            if (expressions.has("blink")) {
                showTransient("blink", 120L)
            } else {
                scheduleBlink()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val current = currentBitmap ?: return
        val previous = previousBitmap
        if (previous != null) {
            val progress = ((SystemClock.uptimeMillis() - transitionStart) / 140f).coerceIn(0f, 1f)
            bitmapPaint.alpha = ((1f - progress) * 255).toInt()
            canvas.drawBitmap(previous, null, faceRect, bitmapPaint)
            bitmapPaint.alpha = (progress * 255).toInt()
            canvas.drawBitmap(current, null, faceRect, bitmapPaint)
            bitmapPaint.alpha = 255
            if (progress < 1f) postInvalidateOnAnimation()
        } else {
            bitmapPaint.alpha = 255
            canvas.drawBitmap(current, null, faceRect, bitmapPaint)
        }
    }

    override fun onDetachedFromWindow() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        bitmapCache.evictAll()
        currentBitmap = null
        previousBitmap = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopped = false
        updateFaceRect()
        applyAnimation(expressions.animationFor(currentState, defaultExpression), crossfade = false)
        scheduleBlink()
    }

    companion object {
        private const val TAG = "BmoFaceView"
    }
}
