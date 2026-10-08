package com.example.meshlabel.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.meshlabel.labelme.LabelMeJson
import com.example.meshlabel.model.Shape
import java.io.File
import java.io.FileInputStream

/**
 * File layout - exactly LabelMe's side-by-side convention inside one
 * folder per project:
 *
 *   filesDir/MeshLabel/<basename>/<basename>.jpg   (the image, raw bytes)
 *   filesDir/MeshLabel/<basename>/<basename>.json  (the annotation)
 *
 * <basename> doubles as the project id and as LabelMe's `imagePath`
 * (relative file name). All I/O is app-private - no storage permissions.
 */
object ProjectStore {

    /** Max decode dimension: keeps editor bitmaps OOM-safe on 100MP photos
     *  while staying far above any real annotation precision need. */
    const val MAX_IMAGE_DIM = 4096

    data class Project(
        val name: String,
        val imageFile: File,
        val jsonFile: File,
    ) {
        val hasJson: Boolean get() = jsonFile.exists()
    }

    private fun rootDir(context: Context): File =
        File(context.filesDir, "MeshLabel").apply { mkdirs() }

    /** All projects, newest first (by image last-modified). */
    fun list(context: Context): List<Project> {
        val root = rootDir(context)
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val name = dir.name
            val img = dir.listFiles { f ->
                val n = f.name.lowercase()
                n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                    n.endsWith(".png") || n.endsWith(".webp")
            }?.firstOrNull() ?: return@mapNotNull null
            Project(name, img, File(dir, "$name.json"))
        }.sortedByDescending { it.imageFile.lastModified() }
    }

    /**
     * Creates a project from an image stream. [displayName] comes from the
     * photo picker; it is sanitized into a filesystem-safe basename and
     * deduplicated against existing projects.
     */
    fun create(context: Context, displayName: String, input: java.io.InputStream): Project? {
        val base = sanitize(displayName)
        val dir = File(rootDir(context), base)
        dir.mkdirs()
        val ext = extOf(displayName)
        val img = File(dir, "$base.$ext")
        return try {
            input.use { ins -> img.outputStream().use { ins.copyTo(it) } }
            Project(base, img, File(dir, "$base.json"))
        } catch (e: Exception) {
            null
        }
    }

    /** Decodes the project image, downscaling past [MAX_IMAGE_DIM]. */
    fun loadBitmap(project: Project): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(project.imageFile.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(project.imageFile.path, opts)
        } catch (e: Exception) {
            null
        }
    }

    /** Parses the JSON annotation (null when absent/invalid + [error]). */
    fun loadShapes(project: Project): Pair<List<Shape>, String?> {
        if (!project.jsonFile.exists()) return emptyList<Shape>() to null
        return try {
            val text = project.jsonFile.readText()
            LabelMeJson.fromJson(text).shapes to null
        } catch (e: Exception) {
            emptyList<Shape>() to (e.message ?: "Invalid annotation file")
        }
    }

    /**
     * Saves image metadata + shapes as LabelMe JSON next to the image.
     * `imageData` is base64 of the STORED image bytes (labelme-compatible;
     * omitted -> null when [withImageData] is false).
     * @return null on success, or an error message.
     */
    fun save(
        context: Context,
        project: Project,
        shapes: List<Shape>,
        imageWidth: Int,
        imageHeight: Int,
        withImageData: Boolean = true,
    ): String? {
        return try {
            val b64 = if (withImageData) {
                FileInputStream(project.imageFile).use { ins ->
                    val bytes = ins.readBytes()
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            } else null
            val json = LabelMeJson.toJson(
                shapes = shapes,
                imagePath = project.imageFile.name,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                imageDataBase64 = b64,
            )
            project.jsonFile.writeText(json)
            null
        } catch (e: Exception) {
            e.message ?: "Save failed"
        }
    }

    /** Deletes the whole project folder. */
    fun delete(project: Project) {
        try {
            project.imageFile.parentFile?.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    /** Filesystem-safe basename: [A-Za-z0-9_-], fallback "image". */
    fun sanitize(displayName: String): String {
        val noExt = displayName.substringBeforeLast('.', displayName)
        val cleaned = noExt.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
        return cleaned.ifEmpty { "image" }
    }

    private fun extOf(displayName: String): String {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "webp" -> if (ext == "jpeg") "jpg" else ext
            else -> "jpg"
        }
    }
}
