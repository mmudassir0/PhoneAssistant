package com.mudassir.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.mudassir.assistant.R
import kotlin.math.PI
import kotlin.math.sin

/** Three softly pulsing dots shown while the assistant is thinking. */
class TypingDots @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(ctx, R.color.violet) }
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) anim.start() else anim.cancel()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 5f
        val gap = (width - 6 * r) / 2f
        for (i in 0 until 3) {
            val p = ((phase - i * 0.18f) % 1f + 1f) % 1f
            val lift = sin(p * 2 * PI).toFloat().coerceAtLeast(0f)
            paint.alpha = (90 + 165 * lift).toInt()
            canvas.drawCircle(r + i * (2 * r + gap), height / 2f - lift * r, r, paint)
        }
    }
}
