package com.example.aimeshvision.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * IOSToggle - an iOS-inspired switch (Carbon/Fluent-style proportions):
 * a fixed-size rounded track that dims when off, fills with the accent when
 * on, and a white circular knob that slides with a springy ease. Also
 * supports a compact variant for tighter rows.
 *
 * Pure custom drawing (no switch library), so it looks identical on every
 * Android version and theme.
 */
class IOSToggle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        // Standard and compact sizes (pixels are dp-scaled in init).
        private const val STD_W_DP = 52f
        private const val STD_H_DP = 31f
        private const val COMPACT_W_DP = 44f
        private const val COMPACT_H_DP = 26f
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()

    private val trackW: Float
    private val trackH: Float
    private val knobR: Float

    var isChecked = false
        private set

    /** Colors follow the app accent: dim slate when off, lime when on. */
    private val offColor = Color.parseColor("#243044")
    private val onColor = Color.parseColor("#BCF362")

    /** 0..1 animation position between off and on. */
    private var animPos = 0f
    private var animator: ValueAnimator? = null

    var onCheckedChangeListener: ((Boolean) -> Unit)? = null

    init {
        val compact = getAttributeValue(attrs, "compactToggle")
        val w = if (compact == "true") COMPACT_W_DP else STD_W_DP
        val h = if (compact == "true") COMPACT_H_DP else STD_H_DP
        val d = resources.displayMetrics.density
        trackW = w * d
        trackH = h * d
        knobR = (trackH - 4f * d) / 2f

        isClickable = true
        // Default view size; the parent row centers us vertically.
        minimumWidth = trackW.toInt()
        minimumHeight = trackH.toInt()
    }

    private fun getAttributeValue(attrs: AttributeSet?, name: String): String? {
        attrs ?: return null
        for (i in 0 until attrs.attributeCount) {
            if (attrs.getAttributeName(i) == name) return attrs.getAttributeValue(i)
        }
        return null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(trackW.toInt(), trackH.toInt())
    }

    /** External state setter (does not fire the listener). */
    fun setCheckedSilent(checked: Boolean) {
        isChecked = checked
        animPos = if (checked) 1f else 0f
        animator?.cancel()
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        toggle()
        return true
    }

    private fun toggle() {
        isChecked = !isChecked
        animateTo(if (isChecked) 1f else 0f)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onCheckedChangeListener?.invoke(isChecked)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animPos, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animPos = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Track: interpolate off -> on color; width bulges slightly while
        // animating (the iOS "stretch" feel).
        val bulge = 1f + 0.04f * min(animPos, 1f - animPos) * 2f
        val trackWidth = trackW * bulge
        trackRect.set(0f, 0f, trackWidth, trackH)

        trackPaint.color = blend(offColor, onColor, animPos)
        canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, trackPaint)

        // Knob: travels from left margin to right edge, grows slightly
        // mid-animation (iOS-style squash & stretch). The travel range keeps
        // the knob inside the track: from (margin + r) to (trackWidth - margin - r).
        val d = resources.displayMetrics.density
        val margin = 2f * d
        val knobGrow = 1f + 0.08f * min(animPos, 1f - animPos) * 2f
        val r = knobR * knobGrow
        val minCx = margin + r
        val maxCx = trackWidth - margin - r
        val cx = minCx + animPos * (maxCx - minCx)

        knobPaint.color = Color.WHITE
        canvas.drawCircle(cx, trackH / 2f, r, knobPaint)
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.argb(a, r, g, b)
    }
}
