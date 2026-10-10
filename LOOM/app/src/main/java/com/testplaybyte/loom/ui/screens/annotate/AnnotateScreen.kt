package com.testplaybyte.loom.ui.screens.annotate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.testplaybyte.loom.data.image.ImageStore
import com.testplaybyte.loom.data.scene.CompiledScene
import com.testplaybyte.loom.data.scene.SceneLibrary
import com.testplaybyte.loom.domain.mesh.MeshMath
import com.testplaybyte.loom.domain.model.ImageState
import com.testplaybyte.loom.domain.model.ImageStatus
import com.testplaybyte.loom.domain.model.LoomRules
import com.testplaybyte.loom.domain.model.ToastIcon
import com.testplaybyte.loom.ui.LoomViewModel
import com.testplaybyte.loom.ui.components.LoomButton
import com.testplaybyte.loom.ui.components.LoomButtonSize
import com.testplaybyte.loom.ui.components.LoomButtonVariant
import com.testplaybyte.loom.ui.components.LabelChip
import com.testplaybyte.loom.ui.components.LoomChip
import com.testplaybyte.loom.ui.components.LoomIconButton
import com.testplaybyte.loom.ui.components.LoomInput
import com.testplaybyte.loom.ui.components.LoomSheet
import com.testplaybyte.loom.ui.components.LoomStepper
import com.testplaybyte.loom.ui.components.ProjectImageArt
import com.testplaybyte.loom.ui.components.StatusDot
import com.testplaybyte.loom.ui.icons.IconAlert
import com.testplaybyte.loom.ui.icons.IconBrush
import com.testplaybyte.loom.ui.icons.IconCheck
import com.testplaybyte.loom.ui.icons.IconChevronLeft
import com.testplaybyte.loom.ui.icons.IconClose
import com.testplaybyte.loom.ui.icons.IconDotMinus
import com.testplaybyte.loom.ui.icons.IconDotPlus
import com.testplaybyte.loom.ui.icons.IconEraser
import com.testplaybyte.loom.ui.icons.IconFit
import com.testplaybyte.loom.ui.icons.IconHelp
import com.testplaybyte.loom.ui.icons.IconMesh
import com.testplaybyte.loom.ui.icons.IconNodes
import com.testplaybyte.loom.ui.icons.IconPan
import com.testplaybyte.loom.ui.icons.IconPlus
import com.testplaybyte.loom.ui.icons.IconRedo
import com.testplaybyte.loom.ui.icons.IconReset
import com.testplaybyte.loom.ui.icons.IconSave
import com.testplaybyte.loom.ui.icons.IconSettings
import com.testplaybyte.loom.ui.icons.IconTag
import com.testplaybyte.loom.ui.icons.IconUndo
import com.testplaybyte.loom.ui.icons.IconZoomIn
import com.testplaybyte.loom.ui.icons.IconZoomOut
import com.testplaybyte.loom.ui.theme.LoomMotion
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SUGGEST_DEBOUNCE_MS = LoomMotion.MESH_SCULPT_DEBOUNCE_MS
private const val MAX_TAGS = 8
private val QUICK_TAGS = listOf("Blurry", "Occluded", "Low light", "Truncated", "Duplicate", "Reviewed")

/**
 * §6 Annotate — the precision annotation screen (docs/03 §6, docs/04).
 *
 * Fixed vertical layout: top bar (back · NN/NN + scene·target · tags ·
 * undo · redo · help) → tool bar (6 segments) → optional tag row → the
 * canvas → bottom dock (object bar only when a mesh exists + action row) →
 * filmstrip. Sheets: canvas guide, clear confirm, image tags, object.
 *
 * Owns: history (via [AnnotateState]), AUTO-SUGGEST (the mesh re-sculpts
 * 600ms after a dot change — no button), density remesh on release,
 * autosave (300ms debounce, flushed on switch/exit) and Save & next.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnnotateScreen(
    vm: LoomViewModel,
    projectId: String?,
    startIndex: Int,
    onExit: () -> Unit,
) {
    val c = loomColors
    val projects by vm.projects.collectAsState()
    val settings by vm.settings.collectAsState()
    val project = projects.find { it.id == projectId }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    if (project == null || project.images.isEmpty()) {
        EmptyAnnotate(onExit = onExit)
        return
    }

    val state = remember { AnnotateState(settings.undoDepth) }
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, project.images.lastIndex)) }
    val image = project.images[index]
    val scene: CompiledScene? = image.sceneId?.let { SceneLibrary.byId(it) }

    // Sheets / transient UI
    var helpOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var tagsOpen by remember { mutableStateOf(false) }
    var objOpen by remember { mutableStateOf(false) }
    var tagDraft by remember { mutableStateOf("") }

    // Real photo decode (demo scenes draw their vector art instead).
    val photo: ImageBitmap? by produceState<ImageBitmap?>(null, image.id) {
        value = if (image.sceneId != null) null else withContext(Dispatchers.IO) {
            ImageStore.load(
                vm.repository.currentWorkspace(), project.slug, image.file,
                ImageStore.MAX_CANVAS_DIM, cacheKey = "${project.slug}/${image.file}",
            )?.asImageBitmap()
        }
    }

    // Visual pulse (280ms) + real haptic on commit actions (docs/04 §11).
    val pulseAlpha = remember { Animatable(0f) }
    val pulseNow: () -> Unit = {
        scope.launch {
            if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            pulseAlpha.snapTo(1f)
            pulseAlpha.animateTo(0f, tween(LoomMotion.HAPTIC_PULSE_MS.toInt(), easing = LoomMotion.ease))
        }
    }

    // ── image load / switch: persist the previous, reset history ─────────
    var loadedImageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(image.id) {
        if (loadedImageId != null && loadedImageId != image.id) {
            vm.repository.saveImageState(project.id, loadedImageId!!, state.present)
        }
        state.reset(project.stateOf(image.id))
        loadedImageId = image.id
    }

    // ── autosave (300ms debounce, settings-gated) ────────────────────────
    LaunchedEffect(state.present, image.id) {
        if (loadedImageId != image.id) return@LaunchedEffect
        if (!settings.autosave) return@LaunchedEffect
        delay(LoomMotion.AUTOSAVE_THROTTLE_MS)
        vm.repository.saveImageState(project.id, image.id, state.present)
    }

    // ── auto-suggest: re-sculpt 600ms after a dot change ─────────────────
    val dotsBaseline = remember { intArrayOf(state.present.dots.size) }
    var suppressAuto by remember { mutableStateOf(false) }
    LaunchedEffect(state.present.dots.size, image.id) {
        val count = state.present.dots.size
        val base = dotsBaseline[0]
        dotsBaseline[0] = count
        if (suppressAuto) {
            suppressAuto = false
            return@LaunchedEffect
        }
        if (base == count) return@LaunchedEffect
        state.sculpting = true
        delay(SUGGEST_DEBOUNCE_MS)
        while (state.gestureActive) delay(LoomMotion.SUGGEST_DEFER_REARM_MS)
        val hasPos = state.present.dots.any { it.isPos }
        if (!hasPos && state.present.mesh == null) {
            state.sculpting = false
            return@LaunchedEffect
        }
        state.commit { prev ->
            prev.copy(
                mesh = if (hasPos) {
                    MeshMath.buildMesh(
                        SceneLibrary.byId(image.sceneId ?: "kitchen").target.anchors.map { a -> Pt(a.first, a.second) },
                        LoomRules.clampDensity(state.densityDraft ?: prev.density),
                        prev.dots,
                    )
                } else {
                    null
                },
            )
        }
        state.meshGen++
        state.sculpting = false
        pulseNow()
    }

    // ── exit flush ───────────────────────────────────────────────────────
    fun flushAndExit() {
        scope.launch {
            vm.repository.saveImageState(project.id, image.id, state.present)
            onExit()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── top bar ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoomIconButton(IconChevronLeft, "Back to project", { flushAndExit() }, iconSize = 20.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${fmt2(index + 1)} / ${fmt2(project.images.size)}",
                    style = loomType.monoCounter,
                    color = c.text,
                )
                Text(
                    text = "${scene?.title ?: image.file} · ${scene?.target?.name ?: targetName(project, image.id)}",
                    style = loomType.small.copy(fontSize = 11.sp),
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // tags with count badge
            Box {
                LoomIconButton(
                    icon = IconTag,
                    contentDescription = "Image tags" + if (state.present.tags.isNotEmpty()) " — ${state.present.tags.size} applied" else "",
                    onClick = {
                        tagDraft = ""
                        tagsOpen = true
                    },
                )
                if (state.present.tags.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 4.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(c.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${state.present.tags.size}",
                            style = loomType.monoChip.copy(fontSize = 9.sp, fontWeight = FontWeight.W600),
                            color = c.primaryFg,
                        )
                    }
                }
            }
            LoomIconButton(IconUndo, "Undo", {
                state.densityDraft = null
                suppressAuto = true
                state.sculpting = false
                state.undo()
            }, enabled = state.canUndo)
            LoomIconButton(IconRedo, "Redo", {
                state.densityDraft = null
                suppressAuto = true
                state.sculpting = false
                state.redo()
            }, enabled = state.canRedo)
            LoomIconButton(IconHelp, "Canvas guide", { helpOpen = true })
        }

        // ── tool bar (6 segments, top chrome) ────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.surface1)
                .border(1.dp, c.hairline, RoundedCornerShape(10.dp)),
        ) {
            ToolButton(Tool.PAN, IconPan, "Pan canvas", state, Modifier.weight(1f))
            ToolButton(Tool.DOT_POS, IconDotPlus, "Add positive dots", state, Modifier.weight(1f))
            ToolButton(Tool.DOT_NEG, IconDotMinus, "Add negative dots", state, Modifier.weight(1f))
            ToolButton(Tool.BRUSH, IconBrush, "Exclusion brush", state, Modifier.weight(1f))
            ToolButton(Tool.EDIT, IconNodes, "Edit mesh vertices", state, Modifier.weight(1f))
            ToolButton(Tool.ERASER, IconEraser, "Eraser", state, Modifier.weight(1f))
        }

        // ── tag row (only when tags exist) ───────────────────────────────
        if (state.present.tags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.present.tags.forEach { t ->
                    LoomChip(
                        label = t,
                        icon = IconTag,
                        onRemove = {
                            state.commit { prev -> prev.copy(tags = prev.tags.filter { it != t }) }
                        },
                    )
                }
            }
        }

        // ── canvas + overlays ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(c.text.copy(alpha = 0.03f * pulseAlpha.value)),
        ) {
            CanvasStage(
                scene = scene,
                photo = photo,
                toneHint = scene?.tone ?: c.surface2.toArgbInt(),
                state = state,
                dotSizeWorld = settings.dotSize,
                magnifier = settings.magnifier,
                modifier = Modifier.fillMaxSize(),
            )

            // status pill (top-center): "sculpting…" / "mesh · N pts"
            val meshSize = state.mesh?.size ?: 0
            if (state.sculpting || state.mesh != null) {
                Text(
                    text = if (state.sculpting) "sculpting…" else "mesh · $meshSize pts",
                    style = loomType.monoPill,
                    color = if (state.sculpting) c.primary else c.text,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(CircleShape)
                        .background(c.surface1.copy(alpha = 0.9f))
                        .border(1.dp, if (state.sculpting) c.mesh.copy(alpha = 0.55f) else c.hairline, CircleShape)
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                )
            }

            // zoom chip (bottom-right)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.surface1.copy(alpha = 0.92f))
                    .border(1.dp, c.hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoomIconButton(IconZoomOut, "Zoom out", { state.requestZoom(1 / 1.25f) }, size = 32.dp, iconSize = 15.dp)
                Text(
                    text = "${state.zoomPct}%",
                    style = loomType.monoSmall,
                    color = c.text,
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.Center,
                )
                LoomIconButton(IconZoomIn, "Zoom in", { state.requestZoom(1.25f) }, size = 32.dp, iconSize = 15.dp)
                Box(Modifier.width(1.dp).height(18.dp).background(c.hairline))
                LoomIconButton(IconFit, "Fit to screen", { state.requestFit() }, size = 32.dp, iconSize = 15.dp)
            }
        }

        // ── bottom dock ─────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface1)
                .border(1.dp, c.hairline),
        ) {
            // object bar — ONLY when a mesh exists
            if (state.mesh != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Object", style = loomType.small, color = c.textMuted)
                    Spacer(Modifier.width(10.dp))
                    val curLabel = project.labels.firstOrNull { it.id == state.present.labelId }
                    Box(modifier = Modifier.weight(1f)) {
                        if (curLabel != null) {
                            LabelChip(
                                name = curLabel.name,
                                colorToken = curLabel.color,
                                active = true,
                                onClick = { objOpen = true },
                            )
                        } else {
                            LoomChip(
                                label = "＋ Name this object",
                                icon = IconPlus,
                                onClick = { objOpen = true },
                            )
                        }
                    }
                    LoomIconButton(IconSettings, "Configure object", { objOpen = true }, size = 36.dp, iconSize = 16.dp)
                }
            }
            // action row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoomStepper(
                    value = state.densityDraft ?: state.present.density,
                    min = LoomRules.DENSITY_MIN,
                    max = LoomRules.DENSITY_MAX,
                    contentDescription = "Mesh density",
                    suffix = "pts",
                    onChange = { v ->
                        state.densityDraft = v
                        scope.launch {
                            delay(LoomMotion.DENSITY_REMESH_COMMIT_MS)
                            if (state.densityDraft == v) {
                                val anchors = SceneLibrary.byId(image.sceneId ?: "kitchen").target.anchors
                                state.commit { prev ->
                                    prev.copy(
                                        density = v,
                                        mesh = prev.mesh?.let { MeshMath.buildMesh(anchors.map { a -> Pt(a.first, a.second) }, v, prev.dots) },
                                    )
                                }
                                state.densityDraft = null
                                state.meshGen++
                                pulseNow()
                            }
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                LoomButton(
                    onClick = { clearOpen = true },
                    size = LoomButtonSize.SM,
                    variant = LoomButtonVariant.TONAL,
                    icon = IconReset,
                    label = null,
                )
                Spacer(Modifier.weight(1f))
                LoomButton(
                    onClick = {
                        scope.launch {
                            vm.repository.saveImageState(project.id, image.id, state.present)
                            vm.repository.setImageStatus(project.id, image.id, ImageStatus.DONE)
                            pulseNow()
                            val next = index + 1
                            if (next < project.images.size) {
                                vm.toast("Saved", ToastIcon.SAVE)
                                index = next
                            } else {
                                vm.toast("Last image", ToastIcon.INFO)
                                onExit()
                            }
                        }
                    },
                    size = LoomButtonSize.SM,
                    icon = IconSave,
                    label = "Save & next",
                )
            }

            // filmstrip
            Filmstrip(
                ws = vm.repository.currentWorkspace(),
                project = project,
                currentIndex = index,
                onSelect = { i ->
                    if (i != index) {
                        scope.launch {
                            vm.repository.saveImageState(project.id, image.id, state.present)
                            index = i
                        }
                    }
                },
            )
        }
    }

    // ── sheets ───────────────────────────────────────────────────────────
    LoomSheet(open = helpOpen, onClose = { helpOpen = false }, title = "Canvas guide") {
        GuideRow(IconDotPlus, "Prompt dots", "+ dots mark what to include, − dots what to avoid. The mesh bends toward your dots.")
        GuideRow(IconMesh, "Auto mesh & density", "The mesh grows itself from your dots — add or remove + dots and it re-sculpts. Density sets its vertex count; remeshing keeps your dots.")
        GuideRow(IconSettings, "Object label", "Once a mesh exists the object bar rises in with the object it belongs to — tap the object chip or the gear to edit the mesh: pick its class, tune density, check the vertex count, remove it.")
        GuideRow(IconNodes, "Edit vertices", "Drag a vertex — the loupe follows your finger. Tap an edge midpoint to add one; long-press or Delete removes the selected vertex, arrows nudge by 8.")
        GuideRow(IconBrush, "Brush & eraser", "Paint an exclusion to keep the mesh out of an area. Tap a painted stroke with the eraser to remove it.")
        GuideRow(IconTag, "Image tags", "Flag the image itself — Blurry, Occluded, Low light or your own. Tags sit in the top bar, ride with the annotations and export with the dataset.")
        GuideRow(IconPan, "Gestures", "Pan with one finger, pinch or scroll to zoom 50–500%, double-tap for 2×. Undo and redo live in the top bar.")
    }

    LoomSheet(open = clearOpen, onClose = { clearOpen = false }, title = "Clear annotations?") {
        Text(
            text = "Removes the dots, the mesh and all brush strokes on this image. The label and density stay.",
            style = loomType.small,
            color = c.textMuted,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoomButton(onClick = { clearOpen = false }, variant = LoomButtonVariant.TONAL, label = "Keep editing")
            LoomButton(
                onClick = {
                    clearOpen = false
                    suppressAuto = true
                    state.sculpting = false
                    state.commit { prev ->
                        prev.copy(dots = emptyList(), mesh = null, strokes = emptyList(), status = ImageStatus.UNLABELED)
                    }
                    pulseNow()
                    vm.toast("Annotations cleared", ToastIcon.CHECK)
                },
                variant = LoomButtonVariant.DANGER,
                label = "Clear all",
            )
        }
    }

    LoomSheet(open = tagsOpen, onClose = { tagsOpen = false }, title = "Image tags") {
        Text(
            text = "Quality flags for this image, saved with its annotations — they export with the dataset.",
            style = loomType.small,
            color = c.textMuted,
        )
        Spacer(Modifier.height(10.dp))
        Text("SUGGESTED", style = loomType.sectionLabel, color = c.textMuted)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            QUICK_TAGS.forEach { t ->
                val on = state.present.tags.any { it.equals(t, ignoreCase = true) }
                LoomChip(
                    label = t,
                    icon = if (on) IconCheck else null,
                    active = on,
                    onClick = { toggleTag(state, t, vm, pulseNow) },
                )
            }
        }
        Text("CUSTOM", style = loomType.sectionLabel, color = c.textMuted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LoomInput(
                value = tagDraft,
                onValueChange = { tagDraft = it },
                placeholder = "Add a tag",
                maxLength = LoomRules.TAG_NAME_MAX,
                modifier = Modifier.weight(1f),
            )
            LoomButton(
                onClick = {
                    val t = LoomRules.cleanTagName(tagDraft)
                    when {
                        t.isEmpty() -> Unit
                        state.present.tags.any { it.equals(t, ignoreCase = true) } ->
                            vm.toast("Tag already added", ToastIcon.WARN)
                        state.present.tags.size >= MAX_TAGS ->
                            vm.toast("Tag limit reached ($MAX_TAGS)", ToastIcon.WARN)
                        else -> {
                            state.commit { prev -> prev.copy(tags = prev.tags + t) }
                            tagDraft = ""
                            pulseNow()
                        }
                    }
                },
                size = LoomButtonSize.SM,
                label = "Add",
            )
        }
        if (state.present.tags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "APPLIED (${state.present.tags.size}/$MAX_TAGS)",
                style = loomType.sectionLabel,
                color = c.textMuted,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.present.tags.forEach { t ->
                    LoomChip(
                        label = t,
                        icon = IconTag,
                        onRemove = { state.commit { prev -> prev.copy(tags = prev.tags.filter { it != t }) } },
                    )
                }
            }
        }
    }

    LoomSheet(open = objOpen, onClose = { objOpen = false }, title = "Object") {
        Text(
            text = "The mesh on this image is one object. Name it with a class label and tune how it is built — everything here lands in the same undo history.",
            style = loomType.small,
            color = c.textMuted,
        )
        Spacer(Modifier.height(10.dp))
        Text("CLASS", style = loomType.sectionLabel, color = c.textMuted)
        if (project.labels.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("No labels yet — add classes on the project screen first.", style = loomType.small, color = c.textMuted)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                project.labels.forEach { l ->
                    LabelChip(
                        name = l.name,
                        colorToken = l.color,
                        active = state.present.labelId == l.id,
                        onClick = {
                            if (state.present.labelId != l.id) {
                                state.commit { prev -> prev.copy(labelId = l.id) }
                            }
                        },
                    )
                }
            }
        }
        Text("MESH DENSITY", style = loomType.sectionLabel, color = c.textMuted)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoomStepper(
                value = state.present.density,
                min = LoomRules.DENSITY_MIN,
                max = LoomRules.DENSITY_MAX,
                contentDescription = "Mesh density",
                suffix = "pts",
                onChange = { v ->
                    val anchors = SceneLibrary.byId(image.sceneId ?: "kitchen").target.anchors
                    state.commit { prev ->
                        prev.copy(
                            density = v,
                            mesh = prev.mesh?.let { MeshMath.buildMesh(anchors.map { a -> Pt(a.first, a.second) }, v, prev.dots) },
                        )
                    }
                    state.meshGen++
                },
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${state.mesh?.size ?: 0} vertices · ${state.present.dots.size} dots",
                style = loomType.monoSmall,
                color = c.textMuted,
            )
        }
        Spacer(Modifier.height(14.dp))
        LoomButton(
            onClick = {
                objOpen = false
                suppressAuto = true
                state.sculpting = false
                state.commit { prev -> prev.copy(mesh = null) }
                state.selection = null
                pulseNow()
                vm.toast("Mesh removed — dots kept", ToastIcon.CHECK)
            },
            size = LoomButtonSize.SM,
            variant = LoomButtonVariant.DANGER,
            icon = IconMesh,
            label = "Remove mesh",
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Removing the mesh keeps your prompt dots — the next dot change re-sculpts it.",
            style = loomType.small,
            color = c.textMuted,
        )
    }
}

// ── small pieces ──────────────────────────────────────────────────────────

private fun fmt2(n: Int) = n.toString().padStart(2, '0')

private fun targetName(project: com.testplaybyte.loom.domain.model.Project, imageId: String): String =
    project.labels.firstOrNull { it.id == project.stateOf(imageId).labelId }?.name ?: "—"

@Composable
private fun ToolButton(
    tool: Tool,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    state: AnnotateState,
    modifier: Modifier = Modifier,
) {
    val c = loomColors
    val active = state.tool == tool
    Box(
        modifier = modifier
            .height(44.dp)
            .background(if (active) c.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                state.tool = tool
                state.selection = null
                state.live = null
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) c.primary else c.text,
            modifier = Modifier.size(20.dp),
        )
        if (active) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.7f)
                    .height(1.dp)
                    .background(c.primary.copy(alpha = 0.7f)),
            )
        }
    }
}

@Composable
private fun GuideRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, copy: String) {
    val c = loomColors
    Row(modifier = Modifier.padding(vertical = 7.dp)) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(c.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = loomType.bodyStrong, color = c.text)
            Text(copy, style = loomType.small, color = c.textMuted)
        }
    }
}

@Composable
private fun Filmstrip(
    ws: com.testplaybyte.loom.data.workspace.Workspace,
    project: com.testplaybyte.loom.domain.model.Project,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val c = loomColors
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        listState.animateScrollToItem(currentIndex.coerceAtLeast(0))
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().background(c.surface1)) {
        val itemW = 64.dp
        val pad = ((maxWidth - itemW) / 2)
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = pad.coerceAtLeast(8.dp), vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(project.images.size) { i ->
                val im = project.images[i]
                val status = project.stateOf(im.id).status
                val cur = i == currentIndex
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface2)
                        .border(
                            if (cur) 2.dp else 1.dp,
                            if (cur) c.primary else c.hairlineStrong,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                ) {
                    ProjectImageArt(
                        ws = ws,
                        projectSlug = project.slug,
                        image = im,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (status != ImageStatus.UNLABELED) {
                        StatusDot(
                            status = status,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                            punchOutColor = c.surface1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyAnnotate(onExit: () -> Unit) {
    val c = loomColors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("This project has no images to annotate.", style = loomType.body, color = c.text)
            Spacer(Modifier.height(12.dp))
            LoomButton(onClick = onExit, variant = LoomButtonVariant.TONAL, label = "Back to project")
        }
    }
}

// ── helpers ───────────────────────────────────────────────────────────────

private fun toggleTag(
    state: AnnotateState,
    tag: String,
    vm: LoomViewModel,
    pulseNow: () -> Unit,
) {
    val existing = state.present.tags.firstOrNull { it.equals(tag, ignoreCase = true) }
    when {
        existing != null -> state.commit { prev -> prev.copy(tags = prev.tags.filter { it != existing }) }
        state.present.tags.size >= MAX_TAGS -> vm.toast("Tag limit reached ($MAX_TAGS)", ToastIcon.WARN)
        else -> {
            state.commit { prev -> prev.copy(tags = prev.tags + tag) }
            pulseNow()
        }
    }
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)
