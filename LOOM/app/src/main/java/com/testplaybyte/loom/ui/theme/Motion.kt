package com.testplaybyte.loom.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Motion tokens (docs/02-design-system.md §4, reference/tokens.json).
 *
 * dur-1 120 · dur-2 170 · dur-3 240 · sheet 320 (ms)
 * ease       cubic-bezier(0.2, 0, 0, 1)
 * easeSpring cubic-bezier(0.34, 1.36, 0.4, 1)
 *
 * Behavior timings that live outside composition (debounces) are kept in
 * the domain layer next to the feature they drive (e.g. mesh sculpt 600ms
 * in `AnnotateViewModel`), with the same values.
 */
object LoomMotion {
    /** Micro — chips, presses. */
    const val DUR_1 = 120
    /** Standard control transitions. */
    const val DUR_2 = 170
    /** Screen enter (`lm-enter`: fade + 6dp up slide). */
    const val DUR_3 = 240
    /** Sheets/dialogs (slide up + scrim fade). */
    const val DUR_SHEET = 320

    val ease: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val easeSpring: Easing = CubicBezierEasing(0.34f, 1.36f, 0.4f, 1f)

    // ── special timings (docs/02 §4) ────────────────────────────────────
    /** Mesh re-sculpts this long after the last dot change. */
    const val MESH_SCULPT_DEBOUNCE_MS = 600L
    /** Autosave debounce after any annotation state change. */
    const val AUTOSAVE_THROTTLE_MS = 300L
    /** Density remesh commits this long after the last stepper release. */
    const val DENSITY_REMESH_COMMIT_MS = 450L
    /** Toast lifetime. */
    const val TOAST_LIFETIME_MS = 2600L
    /** Splash auto-advance. */
    const val SPLASH_TOTAL_MS = 2250L
    /** Haptic/visual pulse length on commit actions. */
    const val HAPTIC_PULSE_MS = 280L
    /** Long-press on a selected vertex deletes it. */
    const val VERTEX_LONG_PRESS_MS = 550L
    /** A deferred sculpt commit re-arms this often while a gesture is live. */
    const val SUGGEST_DEFER_REARM_MS = 350L
}

/**
 * Shape tokens (docs/02 §3): surface 14dp, control 10dp, small 8dp;
 * borders are always 1dp hairlines.
 */
object LoomShape {
    const val RADIUS_SURFACE_DP = 14
    const val RADIUS_CONTROL_DP = 10
    const val RADIUS_SMALL_DP = 8
}
