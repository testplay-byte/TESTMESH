package com.example.aimeshvision.ui

import android.graphics.Color

/**
 * Single source of truth for per-class display styling.
 *
 * The overlay (mesh, outline, label) and the top-bar class chips both pull
 * from here so a class always carries the same color everywhere it appears.
 * Class id -> color mapping is stable: class 0 is always the first color,
 * class 1 the second, and so on.
 */
object DetectionStyle {

    /** Palette order matches class id order (class 0 = lime, class 1 = cyan...). */
    val CLASS_COLORS = intArrayOf(
        Color.parseColor("#BCF362"), // lime    (class 0 - CAT)
        Color.parseColor("#00E5FF"), // cyan    (class 1 - HAND)
        Color.parseColor("#FF6EC7"), // pink
        Color.parseColor("#FFD166"), // amber
        Color.parseColor("#FF6B4A"), // coral
        Color.parseColor("#5EA8FF"), // blue
        Color.parseColor("#C084FC"), // violet
        Color.parseColor("#4ADE80"), // green
    )

    /** Stable color for a class id. */
    fun colorFor(classId: Int): Int = CLASS_COLORS[classId % CLASS_COLORS.size]

    /**
     * Vibrant, opaque, full-saturation variant of [base] for outline strokes:
     * HSV saturation pushed to 1.0 and value boosted, fully opaque so the
     * border reads as a crisp highlight rather than a tinted wash.
     */
    fun vibrantFor(base: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[1] = 1.0f                       // full saturation
        hsv[2] = (hsv[2] * 0.85f + 0.15f).coerceAtMost(1.0f) // brighter value
        return Color.HSVToColor(255, hsv)
    }
}
