package com.testplaybyte.loom.data.repo

import android.content.Context
import android.net.Uri
import com.testplaybyte.loom.data.image.ImageStore
import com.testplaybyte.loom.data.prefs.LoomPreferences
import com.testplaybyte.loom.data.scene.SceneImage
import com.testplaybyte.loom.data.scene.SceneLibrary
import com.testplaybyte.loom.data.workspace.FileWorkspace
import com.testplaybyte.loom.data.workspace.SafWorkspace
import com.testplaybyte.loom.data.workspace.Workspace
import com.testplaybyte.loom.data.workspace.WsNames
import com.testplaybyte.loom.domain.export.DatasetWriters
import com.testplaybyte.loom.domain.export.LoomJsonCodec
import com.testplaybyte.loom.domain.export.ProjectMetaCodec
import com.testplaybyte.loom.domain.model.ExportFormat
import com.testplaybyte.loom.domain.model.Ids
import com.testplaybyte.loom.domain.model.ImageState
import com.testplaybyte.loom.domain.model.ImageStatus
import com.testplaybyte.loom.domain.model.Label
import com.testplaybyte.loom.domain.model.LoomRules
import com.testplaybyte.loom.domain.model.LoomSettings
import com.testplaybyte.loom.domain.model.Perms
import com.testplaybyte.loom.domain.model.Project
import com.testplaybyte.loom.domain.model.ProjectImage
import com.testplaybyte.loom.domain.model.ToastIcon
import com.testplaybyte.loom.domain.model.ToastMsg
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LoomRepository — the single source of truth for projects, settings and
 * permissions (the Android counterpart of the prototype's `loom-context`,
 * with the same action names, docs/06 §3).
 *
 * STORAGE MODEL (the user requirement): the workspace folder IS the
 * database.
 *
 *   <workspace>/
 *     <project-slug>/            one folder per project
 *       loom-project.json        app metadata (ids, labels, image list, options)
 *       annotations.json         Loom JSON — everything the user annotated,
 *                                kept up to date on every save (world coords)
 *       IMG_2041.jpg …           the images (demo scenes render to real
 *                                JPEGs; imported photos are copied in)
 *       labels/, xml/, README.txt   written by the Export action only
 *
 * Consequences, by design:
 *  · creating a project creates a real folder in the workspace;
 *  · images the user drops into a project folder are DETECTED on rescan
 *    and get their own (empty) annotation state;
 *  · every JSON the app writes lands in that folder — portable datasets.
 *
 * A graceful fallback keeps the app fully usable without any grant: when
 * no SAF tree is active, [FileWorkspace] backs the same layout inside
 * app-private storage ("continue with limited access", docs/03 §2).
 */
class LoomRepository(
    private val appContext: Context,
    private val prefs: LoomPreferences,
) {

    companion object {
        /** Folder names never auto-adopted as projects at workspace level. */
        private val SYSTEM_FOLDERS = setOf(
            "dcim", "pictures", "download", "downloads", "documents", "android",
            "movies", "music", "ringtones", "alarms", "notifications", "podcasts",
            "whatsapp", "telegram", "snapchat", "screenshots", "lost.dir", "bluetooth",
        )
    }

    // ── reactive state ────────────────────────────────────────────────────

    private val _hydrated = MutableStateFlow(false)
    val hydrated: StateFlow<Boolean> = _hydrated.asStateFlow()

    private val _settings = MutableStateFlow(LoomSettings.DEFAULT)
    val settings: StateFlow<LoomSettings> = _settings.asStateFlow()

    private val _perms = MutableStateFlow(Perms.DEFAULT)
    val perms: StateFlow<Perms> = _perms.asStateFlow()

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _workspacePath = MutableStateFlow("")
    val workspacePath: StateFlow<String> = _workspacePath.asStateFlow()

    private val _toasts = MutableSharedFlow<ToastMsg>(extraBufferCapacity = 8)
    val toasts: SharedFlow<ToastMsg> = _toasts.asSharedFlow()

    // ── internals ─────────────────────────────────────────────────────────

    private val io = Dispatchers.IO
    private val mutex = Mutex()
    private val appContextRef = appContext.applicationContext
    private var workspace: Workspace = FileWorkspace(File(appContext.filesDir, "workspace"))
    private var dismissed: Set<String> = emptySet()

    fun toast(text: String, icon: ToastIcon = ToastIcon.INFO) {
        _toasts.tryEmit(ToastMsg(System.currentTimeMillis(), text, icon))
    }

    private fun buildWorkspace(uriString: String?): Workspace {
        val uri = uriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri != null) {
            val saf = SafWorkspace(appContextRef, uri)
            if (saf.isAlive()) return saf
        }
        return FileWorkspace(File(appContextRef.filesDir, "workspace"))
    }

    val usingSafWorkspace: Boolean get() = workspace is SafWorkspace

    /** The active workspace for UI-side reads (thumbnails, art). */
    fun currentWorkspace(): Workspace = workspace

    // ── initialize ────────────────────────────────────────────────────────

    /**
     * Loads preferences, builds the workspace, scans every project folder
     * and seeds the demo project on the true first run. Call once from the
     * app scope; the UI gates on [hydrated].
     */
    suspend fun initialize() {
        withContext(io) {
            _settings.value = prefs.currentSettings()
            _perms.value = prefs.currentPerms()
            dismissed = prefs.currentDismissed()
            workspace = buildWorkspace(prefs.currentWorkspaceUri())
            _workspacePath.value = workspace.displayPath
            _projects.value = scanProjects()
            maybeSeed()
            _hydrated.value = true
        }
    }

    // ── permissions / folder ──────────────────────────────────────────────

    suspend fun grantPerm(key: String) = withContext(io) {
        val next = when (key) {
            "media" -> _perms.value.copy(media = true)
            "files" -> _perms.value.copy(files = true)
            "folder" -> _perms.value.copy(folder = true)
            else -> _perms.value
        }
        _perms.value = next
        prefs.updatePerms(next)
    }

    /** Persist the picked SAF tree; grants files+folder in one move. */
    suspend fun setFolder(uri: String, folderName: String) = withContext(io) {
        prefs.setWorkspaceUri(uri)
        val next = _perms.value.copy(files = true, folder = true, folderName = folderName)
        _perms.value = next
        prefs.updatePerms(next)
        workspace = buildWorkspace(uri)
        _workspacePath.value = workspace.displayPath
        rescanLocked()
        // The demo project may have been seeded into the app-private
        // fallback before a folder existed — offer it in the real folder too.
        maybeSeed()
    }

    suspend fun skipPerms() = withContext(io) {
        val next = _perms.value.copy(skipped = true)
        _perms.value = next
        prefs.updatePerms(next)
    }

    // ── settings ──────────────────────────────────────────────────────────

    suspend fun patchSettings(next: LoomSettings) = withContext(io) {
        _settings.value = next
        prefs.updateSettings(next)
    }

    /** Clear-all (docs/03 §8): every project's app data, settings, perms. */
    suspend fun clearAllData() = withContext(io) {
        mutex.withLock {
            for (p in _projects.value) removeProjectData(p, deleteIfPureDemo = true)
        }
        _projects.value = emptyList()
        dismissed = emptySet()
        prefs.clearAll()
        ImageStore.evictAll()
        _settings.value = LoomSettings.DEFAULT
        _perms.value = Perms.DEFAULT
        workspace = FileWorkspace(File(appContextRef.filesDir, "workspace"))
        _workspacePath.value = workspace.displayPath
    }

    // ── project actions (mirroring loom-context) ──────────────────────────

    fun projectById(id: String?): Project? = _projects.value.find { it.id == id }

    /**
     * Create a project: real folder in the workspace, demo images rendered
     * to JPEG files, metadata + initial annotations written.
     */
    suspend fun createProject(
        name: String,
        sceneIds: List<String>,
        labelNames: List<String>,
        /** Optional subfolder inside the workspace (New project → Location). */
        parentFolder: String? = null,
    ): Project =
        withContext(io) {
            mutex.withLock {
                val cleanName = LoomRules.cleanProjectName(name)
                val base = LoomRules.slugify(cleanName)
                val prefix = parentFolder?.takeIf { it.isNotBlank() }?.let { "$it/" } ?: ""
                val slug = uniqueSlug("$prefix$base")
                val labels = labelNames.take(LoomRules.LABEL_MAX).mapIndexed { i, n ->
                    Label(Ids.uid("lb"), n, "c${(i % 8) + 1}")
                }
                val now = System.currentTimeMillis()
                val images = sceneIds.map { sid ->
                    val scene = SceneLibrary.byId(sid)
                    ProjectImage(Ids.uid("im"), scene.file, sceneId = scene.id)
                }
                var project = Project(
                    id = Ids.uid("pr"),
                    slug = slug,
                    name = cleanName,
                    createdAt = now,
                    updatedAt = now,
                    images = images,
                    labels = labels,
                    format = _settings.value.defaultFormat,
                    states = images.associate { it.id to ImageState.empty(_settings.value.defaultDensity) },
                )
                writeProjectFolder(project, renderMissingImages = true)
                project = touch(project)
                _projects.value = (listOf(project) + _projects.value).sortedByDescending { it.updatedAt }
                project
            }
        }

    suspend fun deleteProject(projectId: String) = withContext(io) {
        mutex.withLock {
            val p = projectById(projectId) ?: return@withLock
            removeProjectData(p, deleteIfPureDemo = true)
            if (workspace.folderExists(p.slug)) {
                // User images (imported / manually added) stay on disk; the
                // folder is dismissed so it is not re-adopted (spec copy:
                // "Images on disk are not touched").
                dismissed = dismissed + p.slug
                prefs.addDismissed(p.slug)
            }
            _projects.value = _projects.value.filter { it.id != projectId }
            ImageStore.evict(p.slug)
        }
    }

    /**
     * Copy picked photos into the project folder (the "Add images" action
     * on Project Detail). Files land beside the other images and get fresh
     * empty annotation states.
     */
    suspend fun addImages(projectId: String, uris: List<Uri>): Int = withContext(io) {
        mutex.withLock {
            val p = projectById(projectId) ?: return@withLock 0
            val resolver = appContextRef.contentResolver
            val newImages = ArrayList<ProjectImage>()
            for (uri in uris) {
                val display = queryDisplayName(uri) ?: "photo.jpg"
                val fileName = uniqueFileName(p, display)
                val stream = runCatching { resolver.openInputStream(uri) }.getOrNull() ?: continue
                val written = stream.use { workspace.copyIn(p.slug, fileName, it) } ?: continue
                if (written <= 0) continue
                val (w, h) = ImageStore.bounds(workspace, p.slug, fileName) ?: (800 to 600)
                newImages.add(ProjectImage(Ids.uid("im"), fileName, sceneId = null, width = w, height = h))
            }
            if (newImages.isEmpty()) return@withLock 0
            val states = HashMap(p.states)
            for (im in newImages) states[im.id] = ImageState.empty(_settings.value.defaultDensity)
            val next = touch(p.copy(images = p.images + newImages, states = states))
            writeProjectFolder(next, renderMissingImages = false)
            replaceInMemory(next)
            toast("Added ${newImages.size} image${if (newImages.size == 1) "" else "s"}", ToastIcon.CHECK)
            newImages.size
        }
    }

    suspend fun removeImage(projectId: String, imageId: String) = withContext(io) {
        mutex.withLock {
            val p = projectById(projectId) ?: return@withLock
            val next = touch(
                p.copy(
                    images = p.images.filter { it.id != imageId },
                    states = p.states - imageId,
                ),
            )
            writeProjectFolder(next, renderMissingImages = false)
            replaceInMemory(next)
        }
    }

    /** Adds a label; returns null on success or a toast-worthy error. */
    suspend fun addLabel(projectId: String, rawName: String): String? = withContext(io) {
        mutex.withLock {
            val p = projectById(projectId) ?: return@withLock null
            val name = LoomRules.cleanLabelName(rawName)
            when {
                name.isEmpty() -> return@withLock null
                p.labels.size >= LoomRules.LABEL_MAX -> return@withLock "Label limit reached"
                LoomRules.isDuplicate(p.labels.map { it.name }, name) -> return@withLock "Label already exists"
            }
            val label = Label(Ids.uid("lb"), name, "c${(p.labels.size % 8) + 1}")
            val next = touch(p.copy(labels = p.labels + label))
            writeProjectFolder(next, renderMissingImages = false)
            replaceInMemory(next)
            null
        }
    }

    suspend fun removeLabel(projectId: String, labelId: String) = withContext(io) {
        mutex.withLock {
            val p = projectById(projectId) ?: return@withLock
            // Removing a label clears it from every image that used it
            // (docs/05 §6).
            val states = p.states.mapValues { (_, st) ->
                if (st.labelId == labelId) st.copy(labelId = null) else st
            }
            val next = touch(p.copy(labels = p.labels.filter { it.id != labelId }, states = states))
            writeProjectFolder(next, renderMissingImages = false)
            replaceInMemory(next)
        }
    }

    suspend fun setFormat(projectId: String, format: ExportFormat) = withContext(io) {
        mutex.withLock {
            val p = projectById(projectId) ?: return@withLock
            val next = touch(p.copy(format = format))
            writeMetaOnly(next)
            replaceInMemory(next)
        }
    }

    suspend fun setExportOptions(projectId: String, includeMasks: Boolean? = null, splitPct: Int? = null) =
        withContext(io) {
            mutex.withLock {
                val p = projectById(projectId) ?: return@withLock
                val next = touch(
                    p.copy(
                        includeMasks = includeMasks ?: p.includeMasks,
                        splitPct = splitPct?.let { LoomRules.clampSplit(it) } ?: p.splitPct,
                    ),
                )
                writeMetaOnly(next)
                replaceInMemory(next)
            }
        }

    // ── per-image annotation state ────────────────────────────────────────

    /**
     * Persist one image's state (autosave flush / image switch / exit).
     * Applies the status-upgrade rule: a committed edit on an unlabeled
     * image becomes a draft (docs/05 §4).
     */
    suspend fun saveImageState(projectId: String, imageId: String, state: ImageState) = withContext(io) {
        mutex.withLock {
            val p = projectById(projectId) ?: return@withLock
            var st = state
            if (st.status == ImageStatus.UNLABELED &&
                (st.mesh != null || st.dots.isNotEmpty() || st.strokes.isNotEmpty())
            ) {
                st = st.copy(status = ImageStatus.DRAFT)
            }
            if (p.states[imageId] == st) return@withLock
            val next = touch(p.copy(states = p.states + (imageId to st)))
            writeAnnotationsOnly(next)
            replaceInMemory(next, touchOnly = true)
        }
    }

    suspend fun setImageStatus(projectId: String, imageId: String, status: ImageStatus) = withContext(io) {
        mutex.withLock {
            val p = projectById(projectId) ?: return@withLock
            val st = p.stateOf(imageId).copy(status = status)
            val next = touch(p.copy(states = p.states + (imageId to st)))
            writeAnnotationsOnly(next)
            replaceInMemory(next)
        }
    }

    // ── scanning (manual rescan / folder changes) ─────────────────────────

    /** Re-reads the workspace: new/removed folders and images are picked up. */
    suspend fun rescan() = withContext(io) {
        mutex.withLock { rescanLocked() }
    }

    private fun rescanLocked() {
        _projects.value = scanProjects()
    }

    // ── export ────────────────────────────────────────────────────────────

    data class ExportResult(
        val files: Int,
        val outDir: String,
        val preview: String,
    )

    /**
     * Writes the selected format into the project folder. Progress is
     * reported from ACTUAL writes (bytes/files completed).
     */
    suspend fun exportDataset(
        projectId: String,
        onProgress: (Float) -> Unit,
    ): ExportResult? = withContext(io) {
        mutex.withLock {
            val p = projectById(projectId) ?: return@withLock null
            // Guarantee the images exist on disk (demo scenes render on demand).
            for (im in p.images) {
                if (!workspace.fileExists(p.slug, im.file)) {
                    val scene = im.sceneId?.let { SceneLibrary.byId(it) } ?: continue
                    workspace.writeBytes(p.slug, scene.file, SceneImage.renderJpeg(scene))
                }
            }
            val files = DatasetWriters.write(p, System.currentTimeMillis())
            var done = 0
            for (f in files) {
                val sub = f.relPath.substringBeforeLast('/', "")
                if (sub.isNotEmpty()) workspace.createSubfolder(p.slug, sub)
                workspace.writeText(p.slug, f.relPath, f.content)
                done++
                onProgress(done.toFloat() / files.size)
            }
            ExportResult(
                files = files.size,
                outDir = "${workspace.displayPath}/${p.slug}",
                preview = LoomJsonCodec.previewJson(p, 3),
            )
        }
    }

    /**
     * Reads an export artifact (e.g. `annotations.json`) through the
     * workspace — used by the Share action, which must work for SAF trees
     * where the display path is not a directly readable [File].
     */
    suspend fun readExportFile(projectId: String, name: String): ByteArray? = withContext(io) {
        val p = projectById(projectId) ?: return@withContext null
        try {
            workspace.openInput(p.slug, name)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    /** Total size of Loom-written data (settings readout, docs/03 §8). */
    suspend fun storageBytes(): Long = withContext(io) {
        var total = 0L
        for (p in _projects.value) {
            for (name in listOf(ProjectMetaCodec.FILE_NAME, "annotations.json", "README.txt", "classes.txt")) {
                total += workspace.sizeBytes(p.slug, name) ?: 0L
            }
            total += workspace.listFiles(p.slug, "labels").sumOf { workspace.sizeBytes(p.slug, "labels/$it") ?: 0L }
            total += workspace.listFiles(p.slug, "xml").sumOf { workspace.sizeBytes(p.slug, "xml/$it") ?: 0L }
        }
        total
    }

    // ── project folder IO ─────────────────────────────────────────────────

    /**
     * Writes meta + annotations for a project. When [renderMissingImages]
     * is true, demo images not yet on disk are rendered to JPEGs.
     */
    private fun writeProjectFolder(project: Project, renderMissingImages: Boolean) {
        // slug may be "parent/child": create each level so the folder tree
        // matches what the Location step showed the user.
        val parts = project.slug.split('/')
        if (parts.size == 2) {
            if (!workspace.folderExists(parts[0])) workspace.createFolder(parts[0])
        }
        if (!workspace.folderExists(project.slug)) workspace.createFolder(project.slug)
        if (renderMissingImages) {
            for (im in project.images) {
                val scene = im.sceneId?.let { SceneLibrary.byId(it) } ?: continue
                if (!workspace.fileExists(project.slug, im.file)) {
                    workspace.writeBytes(project.slug, im.file, SceneImage.renderJpeg(scene))
                }
            }
        }
        writeMetaOnly(project)
        writeAnnotationsOnly(project)
    }

    private fun writeMetaOnly(project: Project) {
        workspace.writeText(project.slug, ProjectMetaCodec.FILE_NAME, ProjectMetaCodec.encode(project))
    }

    /** The working file: world coordinates, 800×600 reported (see codec). */
    private fun writeAnnotationsOnly(project: Project) {
        workspace.writeText(
            project.slug,
            "annotations.json",
            LoomJsonCodec.encode(
                project,
                exportedAt = System.currentTimeMillis(),
                scaleToRealPixels = false,
                includeMasks = true,
            ),
        )
    }

    /**
     * Removes Loom's own data from a project folder. The folder itself is
     * deleted only when it contains nothing but app-rendered demo images
     * (all images have sceneIds) — user photos are never deleted.
     */
    private fun removeProjectData(project: Project, deleteIfPureDemo: Boolean) {
        val names = mutableListOf(ProjectMetaCodec.FILE_NAME, "annotations.json", "README.txt", "classes.txt", "dataset.yaml")
        names += workspace.listFiles(project.slug).filter { it.endsWith(".json") || it.endsWith(".txt") || it.endsWith(".yaml") }
        workspace.deleteFiles(project.slug, names.distinct())
        workspace.deleteSubfolder(project.slug, "labels")
        workspace.deleteSubfolder(project.slug, "xml")
        val pureDemo = deleteIfPureDemo && project.images.isNotEmpty() && project.images.all { it.sceneId != null }
        if (pureDemo) {
            workspace.deleteFolder(project.slug)
        }
    }

    private fun replaceInMemory(project: Project, touchOnly: Boolean = false) {
        val next = if (touchOnly) project else touch(project)
        _projects.value = _projects.value.map { if (it.id == next.id) next else it }
    }

    private fun touch(p: Project): Project = p.copy(updatedAt = System.currentTimeMillis())

    private fun uniqueSlug(base: String): String {
        if (!workspace.folderExists(base)) return base
        var i = 2
        while (workspace.folderExists("$base-$i")) i++
        return "$base-$i"
    }

    /** Workspace subfolders offered as Locations on New project. */
    suspend fun workspaceSubfolders(): List<String> = withContext(io) {
        workspace.listSubfolders("").filter { !it.startsWith(".") }
    }

    /** Creates a grouping folder inside the workspace (Location → Create). */
    suspend fun createWorkspaceSubfolder(name: String) = withContext(io) {
        mutex.withLock {
            val slug = LoomRules.slugify(name)
            if (!workspace.folderExists(slug)) workspace.createFolder(slug)
        }
    }

    private fun uniqueFileName(project: Project, displayName: String): String {
        val raw = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "jpg").lowercase()
            .let { if (it in WsNames.IMAGE_EXTS) it else "jpg" }
        val base = raw.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifEmpty { "photo" }
        var candidate = "$base.$ext"
        var i = 2
        val existing = project.images.map { it.file }.toMutableSet()
        existing += workspace.listFiles(project.slug)
        while (candidate in existing) {
            candidate = "$base-$i.$ext"
            i++
        }
        return candidate
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        appContextRef.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    // ── scanning ──────────────────────────────────────────────────────────

    private fun scanProjects(): List<Project> {
        val out = ArrayList<Project>()
        for (folder in workspace.listFolders()) {
            if (folder.startsWith(".") || folder in dismissed) continue
            // Nested layout: a folder that only groups projects (created by
            // the Location step) has no images and no Loom files — its
            // CHILD folders are the projects (slug "parent/child").
            val direct = loadProject(folder)
            if (direct != null) {
                out.add(direct)
                continue
            }
            for (child in workspace.listSubfolders(folder)) {
                val nestedSlug = "$folder/$child"
                if (nestedSlug in dismissed) continue
                val p = loadProject(nestedSlug) ?: continue
                out.add(p)
            }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    /**
     * Loads one project folder. Handles three shapes:
     *  1. managed project (loom-project.json), with annotations.json states;
     *  2. adopted dataset (annotations.json only — e.g. an export copied in);
     *  3. image-only folder (no Loom files) — adopted when it looks like a
     *     deliberate dataset drop (has images, no system-folder name).
     * Finally the folder is re-scanned for images added by hand.
     */
    private fun loadProject(folder: String): Project? {
        val metaText = workspace.readText(folder, ProjectMetaCodec.FILE_NAME)
        val annText = workspace.readText(folder, "annotations.json")
        // Support an images/ subfolder both on adoption and on rescan.
        val rootImages = workspace.listFiles(folder).filter { WsNames.isImage(it) }
        val subImages = workspace.listFiles(folder, "images").filter { WsNames.isImage(it) }
        val allImageNames = (rootImages + subImages).distinct()
        if (allImageNames.isEmpty() && metaText == null) return null
        if (metaText == null && annText == null && !isAdoptable(folder)) return null

        var project: Project = when {
            metaText != null -> runCatching { ProjectMetaCodec.decode(metaText, folder) }
                .getOrElse { ProjectMetaCodec.decode("{}", folder) }

            annText != null -> adoptFromAnnotations(folder, annText)

            else -> Project(
                id = "pr-${folder}",
                slug = folder,
                name = folder,
                createdAt = 0L,
                updatedAt = 0L,
            )
        }

        // Apply the annotation states (matched by file name).
        if (annText != null) {
            val decoded = runCatching { LoomJsonCodec.decode(annText) }.getOrNull()
            if (decoded != null) {
                // Adopt class colors from the file when the meta has none.
                if (project.labels.isEmpty() && decoded.classes.isNotEmpty()) {
                    project = project.copy(
                        labels = decoded.classes.mapIndexed { i, c ->
                            Label(c.id ?: "lb-$i", c.name, c.color.ifEmpty { "c${(i % 8) + 1}" })
                        },
                    )
                }
                val states = HashMap<String, ImageState>()
                for (im in project.images) {
                    val d = decoded.images.firstOrNull { it.file == im.file } ?: continue
                    states[im.id] = ImageState(
                        dots = d.dots,
                        mesh = d.mesh,
                        strokes = d.strokes,
                        density = d.density,
                        labelId = d.labelName?.let { name ->
                            project.labels.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
                        },
                        tags = d.tags,
                        status = d.status,
                    )
                }
                project = project.copy(states = project.states + states)
            }
        }

        // Detect images added by hand since the last save.
        val known = project.images.map { it.file }.toSet()
        val added = allImageNames.filter { it !in known }
        if (added.isNotEmpty() || project.images.isEmpty()) {
            val extra = ArrayList<ProjectImage>()
            for (name in added) {
                val (w, h) = ImageStore.bounds(workspace, folder, name) ?: (800 to 600)
                val scene = SceneLibrary.scenes.firstOrNull { it.file == name }
                extra.add(
                    ProjectImage(
                        id = Ids.uid("im"),
                        file = name,
                        sceneId = scene?.id,
                        width = w,
                        height = h,
                    ),
                )
            }
            val states = HashMap(project.states)
            for (im in extra) {
                states[im.id] = ImageState.empty(_settings.value.defaultDensity)
            }
            if (project.images.isEmpty() && extra.isNotEmpty()) {
                // Image-only folder adoption: name it after the folder.
                project = project.copy(name = if (project.name.isEmpty()) folder else project.name)
            }
            project = project.copy(images = project.images + extra, states = states)
            // Adopted/mutated folders become managed on next write.
            if (metaText == null) {
                writeMetaOnly(project)
                writeAnnotationsOnly(project)
            } else if (added.isNotEmpty()) {
                writeMetaOnly(project)
            }
        }
        return project
    }

    private fun adoptFromAnnotations(folder: String, annText: String): Project {
        val decoded = runCatching { LoomJsonCodec.decode(annText) }.getOrNull()
        val labels = decoded?.classes?.mapIndexed { i, c ->
            Label(c.id ?: "lb-$i", c.name, c.color.ifEmpty { "c${(i % 8) + 1}" })
        } ?: emptyList()
        val images = decoded?.images?.mapIndexed { i, d ->
            ProjectImage("im-$folder-$i", d.file, width = d.width, height = d.height)
        } ?: emptyList()
        return Project(
            id = "pr-$folder",
            slug = folder,
            name = folder,
            createdAt = 0L,
            updatedAt = 0L,
            images = images,
            labels = labels,
        )
    }

    /** Conservative adoption rule for folders with images but no Loom files. */
    private fun isAdoptable(folder: String): Boolean {
        if (folder.lowercase() in SYSTEM_FOLDERS) return false
        val files = workspace.listFiles(folder)
        val images = files.filter { WsNames.isImage(it) }
        if (images.isEmpty()) return false
        // Reject folders holding unrelated file types (documents, media…).
        return files.all { WsNames.isImage(it) || it.endsWith(".json") || it.endsWith(".txt") || it.endsWith(".yaml") }
    }

    // ── seed (docs/01 §Demo content / docs/05 §4) ─────────────────────────

    /**
     * Seeds the demo project whenever the workspace scan came up empty and
     * the user has not deliberately dismissed it. This runs on every
     * initialize and after a storage-folder change: the first seed often
     * lands in the app-private fallback (no folder granted yet), and the
     * demo must appear once the real folder exists. Deleting the demo
     * dismisses its slug, so it never resurrects against the user's will.
     * Never throws — a failed seed (unwritable folder) just retries later.
     */
    private suspend fun maybeSeed() {
        if (_projects.value.isNotEmpty()) return
        if ("object-scan-demo" in dismissed) return
        try {
            seedDemoProject()
            prefs.setSeedDone()
        } catch (t: Throwable) {
            // Seeding is best-effort; leave seedDone unset and retry later.
        }
    }

    /**
     * The first-run demo project: 6 scenes, 6 labels, street pre-marked
     * done and kitchen draft so the progress UI is populated immediately.
     */
    private suspend fun seedDemoProject() {
        val labelSpecs = listOf(
            "lb-fruit" to ("Fruit" to "c2"),
            "lb-vehicle" to ("Vehicle" to "c1"),
            "lb-tool" to ("Tool" to "c3"),
            "lb-plant" to ("Plant" to "c5"),
            "lb-animal" to ("Animal" to "c4"),
            "lb-device" to ("Device" to "c6"),
        )
        val labels = labelSpecs.map { (id, nc) -> Label(id, nc.first, nc.second) }
        val labelFor = mapOf(
            "kitchen" to "lb-fruit",
            "street" to "lb-vehicle",
            "workbench" to "lb-tool",
            "shelf" to "lb-plant",
            "park" to "lb-animal",
            "desk" to "lb-device",
        )
        val now = System.currentTimeMillis()
        val images = SceneLibrary.scenes.map { s -> ProjectImage("im-seed-${s.id}", s.file, sceneId = s.id) }
        val states = HashMap<String, ImageState>()
        for (im in images) {
            var st = ImageState.empty(16).copy(labelId = labelFor[im.sceneId])
            if (im.sceneId == "street") st = st.copy(status = ImageStatus.DONE)
            if (im.sceneId == "kitchen") st = st.copy(status = ImageStatus.DRAFT)
            states[im.id] = st
        }
        val project = Project(
            id = "pr-street-demo",
            slug = "object-scan-demo",
            name = "Object scan — demo set",
            createdAt = now - 1000L * 60 * 60 * 26,
            updatedAt = now - 1000L * 60 * 42,
            images = images,
            labels = labels,
            format = ExportFormat.LOOM_JSON,
            states = states,
        )
        if (project.slug in dismissed) {
            dismissed = dismissed - project.slug
        }
        writeProjectFolder(project, renderMissingImages = true)
        _projects.value = listOf(project)
    }
}
