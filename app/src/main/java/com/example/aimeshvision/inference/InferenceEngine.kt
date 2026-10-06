package com.example.aimeshvision.inference

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the single inference/analysis executor and guarantees that one bad
 * task can never kill the thread (F3), and that shutdown happens in a safe
 * order before the interpreter is closed (F4).
 *
 * All background work in the app goes through [run] / [post].
 */
class InferenceEngine {

    companion object {
        private const val TAG = "InferenceEngine"
        private const val SHUTDOWN_WAIT_MS = 2000L
    }

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "aimesh-inference").apply { priority = Thread.NORM_PRIORITY + 1 }
        }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val shuttingDown = AtomicBoolean(false)

    /** Exposed for CameraX's setAnalyzer (analyzer callbacks arrive on this thread). */
    val executorService: ExecutorService get() = executor

    /**
     * Runs [task] on the inference thread; delivers the result on the main
     * thread via [onResult], or the failure via [onError]. Never throws,
     * never kills the thread (F3).
     */
    fun <T> run(tag: String, task: () -> T, onResult: (T) -> Unit, onError: (Exception) -> Unit = {}) {
        if (shuttingDown.get()) return
        executor.execute {
            try {
                val result = task()
                mainHandler.post { onResult(result) }
            } catch (e: Exception) {
                Log.e(TAG, "Task '$tag' failed", e)
                mainHandler.post { onError(e) }
            } catch (t: Throwable) {
                // Even a non-Exception Throwable must not kill the thread.
                Log.e(TAG, "Task '$tag' threw a fatal throwable", t)
                mainHandler.post { onError(IllegalStateException(t.message, t)) }
            }
        }
    }

    /** Fire-and-forget variant of [run]. */
    fun post(tag: String, task: () -> Unit) {
        run(tag, task, onResult = {})
    }

    /** Runs [block] on the main thread (safe from any thread). */
    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    /**
     * Stops accepting work and waits briefly for the in-flight task to finish
     * BEFORE the interpreter gets closed by the caller (F4).
     */
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        executor.shutdown()
        try {
            executor.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
