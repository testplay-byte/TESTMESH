package com.example.aimeshvision.crash

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aimeshvision.MainActivity
import com.example.aimeshvision.R
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashReportActivity
 *
 * The app-styled end of the crash pipeline. When the uncaught-exception
 * handler fires, [CrashHandler] stores the stack trace in a temp file and
 * launches THIS activity instead of letting the process die with only the
 * system dialog. The user sees what happened in the app's own dark/lime
 * design and can:
 *  - COPY   the trace to the clipboard (to paste back to the developer)
 *  - RESTART the app (MainActivity, fresh task)
 *  - CLOSE  the app gracefully
 *
 * The activity lives in its own process (:crash) so a dying main process
 * can never take it down before the user has read the report.
 */
class CrashReportActivity : AppCompatActivity() {

    companion object {
        /** Where CrashHandler drops the pending trace before relaunching us. */
        const val EXTRA_TRACE_FILE = "crash_trace_file"
        private const val PENDING_TRACE = "pending_crash.txt"
    }

    private lateinit var trace: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_report)

        // CrashHandler writes the trace to a temp file and passes the path -
        // a full stack trace can exceed the Intent transaction limit.
        val traceFile = File(cacheDir, PENDING_TRACE)
        trace = if (traceFile.exists()) {
            runCatching { traceFile.readText() }.getOrDefault("(trace unreadable)")
        } else {
            intent.getStringExtra(EXTRA_TRACE_FILE) ?: "(no trace available)"
        }

        val tvTime = findViewById<TextView>(R.id.tvCrashTime)
        val tvBody = findViewById<TextView>(R.id.tvCrashBody)
        tvTime.text = SimpleDateFormat("dd MMM yyyy · HH:mm:ss", Locale.getDefault())
            .format(Date())
        tvBody.text = trace

        findViewById<TextView>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AI-MESH-FLOW crash report", trace))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.btnRestart).setOnClickListener {
            // Fresh task: the old (crashed) process state is never reused.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }

        findViewById<TextView>(R.id.btnClose).setOnClickListener { finishAffinity() }
    }
}
