package com.testplaybyte.loom.data.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

/**
 * Workspace — the user's storage folder, abstracted over two backings:
 *
 *  · [SafWorkspace]   a real, user-picked folder (Storage Access
 *    Framework tree URI, persisted). This is the primary mode: the
 *    workspace the user selects at first run is used for EVERYTHING —
 *    projects are folders inside it, images live in those folders, and
 *    every JSON file is written there (user requirement).
 *  · [FileWorkspace]  an app-private directory used as a graceful fallback
 *    when the user continues with "limited access" (spec) or the SAF
 *    grant is lost. Same layout, same code paths — only the backing
 *    differs, so every feature keeps working.
 *
 * All calls do file IO and must run off the main thread (the repository
 * wraps them in Dispatchers.IO).
 */
interface Workspace {

    /** Human-readable path for the UI (path pills, export destination). */
    val displayPath: String

    /** Direct child folder names of the workspace root. */
    fun listFolders(): List<String>

    /** Direct child file names of the workspace root. */
    fun listRootFiles(): List<String>

    /** Direct child FOLDER names of [dir] ("" = workspace root). */
    fun listSubfolders(dir: String): List<String>

    /** File names inside [dir] (project folder). */
    fun listFiles(dir: String): List<String>

    /** File names inside [dir]/[sub] (e.g. "images", "labels", "xml"). */
    fun listFiles(dir: String, sub: String): List<String>

    fun createFolder(dir: String): Boolean
    fun createSubfolder(dir: String, sub: String): Boolean
    fun folderExists(dir: String): Boolean
    fun fileExists(dir: String, name: String): Boolean
    fun readText(dir: String, name: String): String?
    fun writeText(dir: String, name: String, text: String): Boolean
    fun writeBytes(dir: String, name: String, bytes: ByteArray): Boolean
    fun openInput(dir: String, name: String): InputStream?

    /** Recursively deletes [dir] and everything in it. */
    fun deleteFolder(dir: String): Boolean

    /** Deletes every file in [dir] whose name is in [names] (files only). */
    fun deleteFiles(dir: String, names: Collection<String>)

    /** Deletes a subfolder (e.g. an old export's `labels/`). */
    fun deleteSubfolder(dir: String, sub: String)

    /** Byte size of one file (storage readout); null when missing. */
    fun sizeBytes(dir: String, name: String): Long?

    /** Copies [input] into [dir]/[name]; returns size written or null. */
    fun copyIn(dir: String, name: String, input: InputStream): Long?

    /** True when this workspace is writable (SAF grant alive). */
    fun isAlive(): Boolean
}

/** Shared helpers used by both implementations. */
internal object WsNames {
    val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp")

    fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS
}

/**
 * SAF-backed workspace: every operation resolves the project folder as a
 * `DocumentFile` child of the persisted tree. DocumentFile lookups are
 * cheap-ish but not free; callers already batch work on IO threads.
 */
class SafWorkspace(
    private val context: Context,
    private val treeUri: Uri,
) : Workspace {

    private val root: DocumentFile? by lazy {
        try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (e: Exception) {
            null
        }
    }

    override val displayPath: String by lazy { SafPaths.prettyPath(context, treeUri) }

    override fun isAlive(): Boolean = root?.canWrite() == true

    /** Resolves a possibly multi-segment dir path ("parent/child"). */
    private fun dir(name: String): DocumentFile? {
        if (name.isEmpty()) return root
        var current = root ?: return null
        for (segment in name.split('/')) {
            if (segment.isEmpty()) continue
            val next = current.findFile(segment) ?: return null
            if (!next.isDirectory) return null
            current = next
        }
        return current
    }

    override fun listFolders(): List<String> =
        root?.listFiles()?.filter { it.isDirectory }?.mapNotNull { it.name } ?: emptyList()

    override fun listRootFiles(): List<String> =
        root?.listFiles()?.filter { it.isFile }?.mapNotNull { it.name } ?: emptyList()

    override fun listSubfolders(dir: String): List<String> =
        this.dir(dir)?.listFiles()?.filter { it.isDirectory }?.mapNotNull { it.name } ?: emptyList()

    override fun listFiles(dir: String): List<String> {
        val d = dir(dir) ?: return emptyList()
        return d.listFiles().filter { it.isFile }.mapNotNull { it.name }
    }

    override fun listFiles(dir: String, sub: String): List<String> {
        val s = dir(dir)?.findFile(sub)?.takeIf { it.isDirectory } ?: return emptyList()
        return s.listFiles().filter { it.isFile }.mapNotNull { it.name }
    }

    override fun createFolder(dir: String): Boolean {
        val parent = dir.substringBeforeLast('/', "")
        if (parent.isEmpty()) return root?.createDirectory(dir) != null
        val parentDoc = this.dir(parent) ?: return false
        return parentDoc.createDirectory(dir.substringAfterLast('/')) != null
    }

    override fun createSubfolder(dir: String, sub: String): Boolean =
        dir(dir)?.createDirectory(sub) != null

    override fun folderExists(dir: String): Boolean = dir(dir) != null

    override fun fileExists(dir: String, name: String): Boolean =
        dir(dir)?.findFile(name)?.isFile == true

    override fun readText(dir: String, name: String): String? {
        val f = dir(dir)?.findFile(name)?.takeIf { it.isFile } ?: return null
        return try {
            context.contentResolver.openInputStream(f.uri)?.use { it.readBytes().decodeToString() }
        } catch (e: Exception) {
            null
        }
    }

    override fun writeText(dir: String, name: String, text: String): Boolean =
        writeBytes(dir, name, text.toByteArray())

    override fun writeBytes(dir: String, name: String, bytes: ByteArray): Boolean {
        val d = dir(dir) ?: return false
        val existing = d.findFile(name)
        val f = existing?.takeIf { it.isFile } ?: d.createFile(mimeOf(name), name) ?: return false
        return try {
            context.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(bytes) }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun openInput(dir: String, name: String): InputStream? {
        val f = dir(dir)?.findFile(name)?.takeIf { it.isFile } ?: return null
        return try {
            context.contentResolver.openInputStream(f.uri)
        } catch (e: Exception) {
            null
        }
    }

    override fun deleteFolder(dir: String): Boolean =
        dir(dir)?.delete() ?: false

    override fun deleteFiles(dir: String, names: Collection<String>) {
        val d = dir(dir) ?: return
        for (n in names) {
            d.findFile(n)?.takeIf { it.isFile }?.delete()
        }
    }

    override fun deleteSubfolder(dir: String, sub: String) {
        dir(dir)?.findFile(sub)?.takeIf { it.isDirectory }?.delete()
    }

    override fun sizeBytes(dir: String, name: String): Long? =
        dir(dir)?.findFile(name)?.takeIf { it.isFile }?.length()

    override fun copyIn(dir: String, name: String, input: InputStream): Long? {
        val d = dir(dir) ?: return null
        val f = d.findFile(name)?.takeIf { it.isFile }
            ?: d.createFile(mimeOf(name), name)
            ?: return null
        return try {
            context.contentResolver.openOutputStream(f.uri, "wt")?.use { out ->
                val n = input.copyTo(out)
                n
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "json" -> "application/json"
        "txt", "md" -> "text/plain"
        "xml" -> "text/xml"
        "yaml", "yml" -> "text/yaml"
        else -> "application/octet-stream"
    }

    private fun DocumentFile.nameOrNull(): String? = name
}

/** App-private fallback workspace (java.io.File, no permissions). */
class FileWorkspace(private val rootDir: File) : Workspace {

    init {
        rootDir.mkdirs()
    }

    override val displayPath: String get() = rootDir.absolutePath

    override fun isAlive(): Boolean = rootDir.canWrite() || rootDir.mkdirs()

    private fun dir(name: String) = File(rootDir, name)

    override fun listFolders(): List<String> =
        rootDir.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()

    override fun listRootFiles(): List<String> =
        rootDir.listFiles { f -> f.isFile }?.map { it.name } ?: emptyList()

    override fun listSubfolders(dir: String): List<String> =
        if (dir.isEmpty()) {
            rootDir.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
        } else {
            File(rootDir, dir).listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
        }

    override fun listFiles(dir: String): List<String> =
        dir(dir).listFiles { f -> f.isFile }?.map { it.name } ?: emptyList()

    override fun listFiles(dir: String, sub: String): List<String> =
        File(dir(dir), sub).listFiles { f -> f.isFile }?.map { it.name } ?: emptyList()

    override fun createFolder(dir: String): Boolean = dir(dir).mkdirs() || dir(dir).isDirectory

    override fun createSubfolder(dir: String, sub: String): Boolean =
        File(dir(dir), sub).let { it.mkdirs() || it.isDirectory }

    override fun folderExists(dir: String): Boolean = dir(dir).isDirectory

    override fun fileExists(dir: String, name: String): Boolean = File(dir(dir), name).isFile

    override fun readText(dir: String, name: String): String? {
        val f = File(dir(dir), name)
        if (!f.isFile) return null
        return try {
            f.readText()
        } catch (e: Exception) {
            null
        }
    }

    override fun writeText(dir: String, name: String, text: String): Boolean = writeBytes(dir, name, text.toByteArray())

    override fun writeBytes(dir: String, name: String, bytes: ByteArray): Boolean {
        val d = dir(dir)
        if (!d.isDirectory && !d.mkdirs()) return false
        return try {
            File(d, name).writeBytes(bytes)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun openInput(dir: String, name: String): InputStream? {
        val f = File(dir(dir), name)
        if (!f.isFile) return null
        return try {
            f.inputStream()
        } catch (e: Exception) {
            null
        }
    }

    override fun deleteFolder(dir: String): Boolean = dir(dir).deleteRecursively()

    override fun deleteFiles(dir: String, names: Collection<String>) {
        for (n in names) {
            val f = File(dir(dir), n)
            if (f.isFile) f.delete()
        }
    }

    override fun deleteSubfolder(dir: String, sub: String) {
        File(dir(dir), sub).deleteRecursively()
    }

    override fun sizeBytes(dir: String, name: String): Long? =
        File(dir(dir), name).takeIf { it.isFile }?.length()

    override fun copyIn(dir: String, name: String, input: InputStream): Long? {
        val d = dir(dir)
        if (!d.isDirectory && !d.mkdirs()) return null
        return try {
            File(d, name).outputStream().use { out -> input.copyTo(out) }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Best-effort pretty path for SAF trees: turns
 * `content://com.android.externalstorage.documents/tree/primary%3ALoomDatasets`
 * into `/storage/emulated/0/LoomDatasets`; unknown providers fall back to
 * the last path segment.
 */
internal object SafPaths {
    fun prettyPath(context: Context, treeUri: Uri): String {
        val docId = try {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            null
        } ?: return treeUri.lastPathSegment ?: treeUri.toString()
        val parts = docId.split(":", limit = 2)
        val volume = parts.getOrNull(0) ?: return docId
        val rel = parts.getOrNull(1) ?: ""
        val base = when (volume.lowercase()) {
            "primary" -> "/storage/emulated/0"
            else -> "/storage/$volume"
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}
