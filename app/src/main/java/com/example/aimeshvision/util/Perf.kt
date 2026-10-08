package com.example.aimeshvision.util

import android.os.SystemClock
import android.util.Log

/**
 * Lightweight per-stage timing for the live pipeline.
 *
 * Why this exists: a device test reported ~500 ms average latency that was
 * NOT explainable from code inspection alone. Every hot stage (camera rotate,
 * letterbox, interpreter run, post-process, contour extraction, draw) now
 * reports its wall time to logcat under the "Perf" tag whenever it exceeds
 * [MIN_MS], so the next device test pinpoints the bottleneck instead of us
 * guessing.
 *
 * Usage:
 *   val t = Perf.start()
 *   ... work ...
 *   Perf.log("inference", t)          // logs only when >= 16 ms
 *
 *   val r = Perf.measure("post") { ... }   // lambda form
 *
 * View with:  adb logcat -s Perf
 */
object Perf {
    private const val TAG = "Perf"

    /** Below this a stage is normal-speed and stays quiet. */
    private const val MIN_MS = 16L

    /** True to silence logging entirely (tests). */
    @Volatile var enabled: Boolean = true

    /** Marks a stage start (elapsedRealtime, monotonic). */
    fun start(): Long = SystemClock.elapsedRealtime()

    /** Logs `label: Xms` when the stage took >= [MIN_MS]. */
    fun log(label: String, startedAt: Long) {
        if (!enabled) return
        val ms = SystemClock.elapsedRealtime() - startedAt
        if (ms >= MIN_MS) Log.d(TAG, "$label ${ms}ms")
    }

    /** Returns the stage duration in ms (also logs when slow). */
    fun <T> measure(label: String, block: () -> T): T {
        val t = start()
        try {
            return block()
        } finally {
            log(label, t)
        }
    }
}
