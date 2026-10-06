package com.example.aimeshvision.crash

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline crash management (keeps the app's zero-network purity).
 *
 * Installs a default [Thread.UncaughtExceptionHandler] that writes the full
 * stack trace to filesDir/crash_logs/ (keeping the newest 5) before handing
 * over to the platform handler, so the process still dies normally.
 */
class CrashHandler private constructor(
    private val context: Context,
    private val platformHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private const val DIR = "crash_logs"
        private const val KEEP = 5

        /** Call once from the Application class. */
        fun install(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext, previous))
        }
    }

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val file = File(dir, "crash_${stamp.format(Date())}.txt")
            file.writeText(buildString {
                appendLine("AI-MESH-FLOW crash report")
                appendLine("time    : ${Date()}")
                appendLine("thread  : ${thread.name}")
                appendLine("exception: ${throwable.javaClass.name}: ${throwable.message}")
                appendLine()
                appendLine(stringStackTrace(throwable))
                throwable.cause?.let { cause ->
                    appendLine()
                    appendLine("CAUSE: ${cause.javaClass.name}: ${cause.message}")
                    appendLine(stringStackTrace(cause))
                }
            })

            // keep only the newest KEEP reports
            dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        } catch (e: Exception) {
            // Crash handling must never itself crash - fall back to logging.
            Log.e(TAG, "Failed to write crash report", e)
        } finally {
            platformHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun stringStackTrace(throwable: Throwable): String = StringWriter().also {
        throwable.printStackTrace(PrintWriter(it))
    }.toString()

    /** Newest crash reports, newest first (surfaced in settings for debugging). */
    fun crashReports(): List<File> =
        File(context.filesDir, DIR).listFiles()?.sortedByDescending { it.name } ?: emptyList()
}
