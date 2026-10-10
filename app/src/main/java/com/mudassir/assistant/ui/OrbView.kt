package com.mudassir.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The assistant's voice orb: a glowing teal-to-violet sphere.
 * Idle: slow breathing. Listening: swells with your voice. Thinking: a swirl runs around it.
 * Speaking: gentle ripple rings. Honors the system "remove animations" setting.
 */
class OrbView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode = Mode.IDLE
        set(v) {
            if (field != v) { field = v; invalidate() }
        }

    /** Live microphone level 0..1 while listening. */
    var level = 0f
        set(v) { field = v }

    private val teal = Color.parseColor("#3EE6C1")
    private val violet = Color.parseColor("#8B7BFF")
    private val deep = Color.parseColor("#2B1F7A")

    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val swirl = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val swirlMatrix = Matrix()

    private var t = 0f          // seconds
    private var smoothLevel = 0f
    private val animate = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        var last = 0L
        addUpdateListener {
            val now = System.nanoTime()
            if (last != 0L) t += (now - last) / 1e9f
            last = now
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // blur needs a software layer; the view is small
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f * BASE
        core.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.6f, intArrayOf(teal, violet, deep), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        shine.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.45f, r * 0.7f, Color.argb(150, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(cx, cy, r * 1.9f, intArrayOf(Color.argb(140, 110, 140, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        glow.maskFilter = BlurMaskFilter(r * 0.25f, BlurMaskFilter.Blur.NORMAL)
        swirl.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, teal, Color.WHITE, violet, Color.TRANSPARENT), null)
        swirl.strokeWidth = r * 0.09f
        ring.strokeWidth = r * 0.04f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animate) ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val max = min(width, height) / 2f
        val base = max * BASE

        smoothLevel += ((if (mode == Mode.LISTENING) level else 0f) - smoothLevel) * 0.25f
        val breathe = if (animate) sin(t * 2 * PI.toFloat() / 4f) * 0.03f else 0f
        val scale = when (mode) {
            Mode.LISTENING -> 1f + smoothLevel * 0.22f
            Mode.SPEAKING -> 1f + (if (animate) sin(t * 9f) * 0.025f else 0f)
            else -> 1f + breathe
        }
        val r = base * scale

        // Outer glow (stronger while listening / speaking).
        val glowAlpha = when (mode) { Mode.IDLE -> 0.55f; Mode.THINKING -> 0.75f; else -> 0.9f + smoothLevel * 0.1f }
        glow.alpha = (255 * glowAlpha).toInt()
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawCircle(cx, cy, base * 1.45f, glow)
        canvas.restore()

        // Ripples while speaking or listening.
        if (animate && (mode == Mode.SPEAKING || mode == Mode.LISTENING)) {
            for (i in 0 until 2) {
                val p = ((t * 0.7f) + i * 0.5f) % 1f
                ring.color = if (mode == Mode.LISTENING) teal else violet
                ring.alpha = ((1f - p) * 140).toInt()
                canvas.drawCircle(cx, cy, r * (1.05f + p * 0.32f), ring)
            }
        }

        // The sphere.
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawCircle(cx, cy, base, core)
        canvas.drawCircle(cx, cy, base, shine)
        canvas.restore()

        // Thinking swirl.
        if (mode == Mode.THINKING) {
            swirlMatrix.setRotate((t * 300f) % 360f, cx, cy)
            swirl.shader.setLocalMatrix(swirlMatrix)
            canvas.drawCircle(cx, cy, r * 1.14f, swirl)
        }
    }

    private companion object {
        /** Sphere radius as a fraction of the view, leaving room for glow and ripples. */
        const val BASE = 0.62f
    }
}
