package com.example.aimeshvision.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
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
 * Two-stage handling of an uncaught exception:
 *  1. The full trace is written to filesDir/crash_logs/ (newest 5 kept) for
 *     later inspection (settings → Crash Reports).
 *  2. A copy is dropped in cacheDir as "pending_crash.txt" and
 *     [CrashReportActivity] is launched in a separate :crash process, where
 *     the user can COPY the log, RESTART the app, or CLOSE it gracefully -
 *     in the app's own UI instead of a bare system dialog.
 *
 * All crash handling must never itself crash: every step is guarded, and on
 * any internal failure we fall back to the platform handler.
 */
class CrashHandler private constructor(
    private val context: Context,
    private val platformHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private const val DIR = "crash_logs"
        private const val KEEP = 5
        private const val PENDING_TRACE = "pending_crash.txt"

        @Volatile private var installed: CrashHandler? = null

        /** Call once from the Application class. */
        fun install(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            installed = CrashHandler(context.applicationContext, previous)
            Thread.setDefaultUncaughtExceptionHandler(installed)
        }

        /**
         * Saved crash reports, newest first (surfaced in settings for debugging).
         * Returns an empty list before install or when nothing has crashed.
         */
        fun reports(): List<File> =
            installed?.crashReports() ?: emptyList()

        /** Deletes every saved crash report (used by the "copy all" flow). */
        fun clearAll() {
            installed?.crashReports()?.forEach { it.delete() }
        }
    }

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val trace = buildTrace(thread, throwable)
        try {
            // 1. permanent archive (newest KEEP kept)
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            File(dir, "crash_${stamp.format(Date())}.txt").writeText(trace)
            dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash report", e)
        }
        try {
            // 2. handoff payload for the crash activity
            File(context.cacheDir, PENDING_TRACE).writeText(trace)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write pending trace", e)
        }
        try {
            // 3. show the app-styled crash screen in a separate process
            val intent = Intent(context, CrashReportActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra(CrashReportActivity.EXTRA_TRACE_FILE, PENDING_TRACE)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch crash activity", e)
        }
        // Give the crash activity a moment to come up, then end this process.
        try { Thread.sleep(400) } catch (_: InterruptedException) {}
        platformHandler?.uncaughtException(thread, throwable)
            ?: Process.killProcess(Process.myPid())
    }

    private fun buildTrace(thread: Thread, throwable: Throwable): String = buildString {
        appendLine("AI-MESH-FLOW crash report")
        appendLine("time     : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("thread   : ${thread.name}")
        appendLine("device   : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        appendLine("exception: ${throwable.javaClass.name}: ${throwable.message}")
        appendLine()
        appendLine(stringStackTrace(throwable))
        throwable.cause?.let { cause ->
            appendLine()
            appendLine("CAUSE: ${cause.javaClass.name}: ${cause.message}")
            appendLine(stringStackTrace(cause))
        }
    }

    private fun stringStackTrace(throwable: Throwable): String = StringWriter().also {
        throwable.printStackTrace(PrintWriter(it))
    }.toString()

    /** Newest crash reports, newest first (used by [companion.reports]). */
    private fun crashReports(): List<File> =
        File(context.filesDir, DIR).listFiles()?.sortedByDescending { it.name } ?: emptyList()
}
