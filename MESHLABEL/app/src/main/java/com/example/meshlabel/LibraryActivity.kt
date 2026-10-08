package com.example.meshlabel

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.meshlabel.store.ProjectStore
import com.example.meshlabel.store.YoloSegExporter
import java.util.concurrent.Executors

/**
 * Launcher screen: the list of saved LabelMe projects (image + JSON pairs,
 * side by side in one folder each - labelme's own convention).
 *
 * Photo picker (PickVisualMedia) needs no storage permission on API 31+;
 * import + export run on a small IO executor so huge originals never block
 * the UI thread.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var projectList: LinearLayout
    private lateinit var emptyState: TextView
    private val io = Executors.newSingleThreadExecutor()
    private var busy = false

    private val picker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { importImage(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        projectList = findViewById(R.id.projectList)
        emptyState = findViewById(R.id.emptyState)

        findViewById<TextView>(R.id.btnAddImage).setOnClickListener {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<TextView>(R.id.btnExport).setOnClickListener { exportYolo() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Rebuilds the project rows from the store. */
    private fun refresh() {
        val projects = ProjectStore.list(this)
        // Remove previous rows (keep the empty-state view at index 0).
        for (i in projectList.childCount - 1 downTo 0) {
            val child = projectList.getChildAt(i)
            if (child.id != R.id.emptyState) projectList.removeViewAt(i)
        }
        emptyState.visibility = if (projects.isEmpty()) TextView.VISIBLE else TextView.GONE
        val inflater = LayoutInflater.from(this)
        for (p in projects) {
            val row = inflater.inflate(R.layout.item_project, projectList, false)
            row.findViewById<TextView>(R.id.projectName).text = p.name
            row.findViewById<TextView>(R.id.projectMeta).text =
                if (p.hasJson) "annotated · labelme JSON saved" else "not annotated yet"
            // Thumbnail (sampled - the row never needs full resolution).
            val thumb = row.findViewById<ImageView>(R.id.thumb)
            io.execute {
                val bmp = BitmapFactory.decodeFile(p.imageFile.path)
                runOnUiThread { if (bmp != null) thumb.setImageBitmap(bmp) }
            }
            row.setOnClickListener {
                startActivity(
                    Intent(this, EditorActivity::class.java)
                        .putExtra(EditorActivity.EXTRA_PROJECT, p.name)
                )
            }
            row.findViewById<TextView>(R.id.btnDelete).setOnClickListener {
                confirmDelete(p.name) { ProjectStore.delete(p); refresh() }
            }
            projectList.addView(row)
        }
    }

    /** Imports the picked image as a new project and opens its editor. */
    private fun importImage(uri: Uri) {
        if (busy) return
        busy = true
        io.execute {
            val name = queryDisplayName(uri) ?: "image.jpg"
            val stream = contentResolver.openInputStream(uri)
            val project = stream?.let { ProjectStore.create(this, name, it) }
            runOnUiThread {
                busy = false
                if (project == null) {
                    Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(
                        Intent(this, EditorActivity::class.java)
                            .putExtra(EditorActivity.EXTRA_PROJECT, project.name)
                    )
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i)
                }
            }
        return uri.lastPathSegment
    }

    /** YOLO-seg export of every saved annotation (background IO). */
    private fun exportYolo() {
        if (busy) return
        busy = true
        Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show()
        io.execute {
            val result = YoloSegExporter.export(this)
            runOnUiThread {
                busy = false
                val msg =
                    if (result.error != null) "Export failed: ${result.error}"
                    else "Exported ${result.projectsExported} images / " +
                        "${result.shapesExported} polygons → ${result.outDir.absolutePath}"
                AlertDialog.Builder(this)
                    .setTitle("YOLO-seg export")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun confirmDelete(name: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Delete project")
            .setMessage("Delete \"$name\" and its JSON permanently?")
            .setPositiveButton("Delete") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
