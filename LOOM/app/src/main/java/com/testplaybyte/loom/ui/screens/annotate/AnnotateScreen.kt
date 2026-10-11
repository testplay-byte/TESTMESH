package com.testplaybyte.loom.ui.screens.annotate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.testplaybyte.loom.data.image.ImageStore
import com.testplaybyte.loom.data.scene.CompiledScene
import com.testplaybyte.loom.data.seg.SmartSegmenter
import com.testplaybyte.loom.data.scene.SceneLibrary
import com.testplaybyte.loom.domain.mesh.MeshMath
import com.testplaybyte.loom.domain.seg.MaskToPolygon
import com.testplaybyte.loom.domain.model.ImageState
import com.testplaybyte.loom.domain.model.Pt
import com.testplaybyte.loom.domain.model.ImageStatus
import com.testplaybyte.loom.domain.model.Ids
import com.testplaybyte.loom.domain.model.LoomRules
import com.testplaybyte.loom.domain.model.ObjAnn
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
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    val context = LocalContext.current
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

    // First-run canvas hint: square-ish card, 2s, tap-dismiss, ONCE ever.
    val hintShownEver by vm.repository.hintShown.collectAsState(initial = true)
    var hintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(hintShownEver) {
        if (!hintShownEver) {
            delay(400)
            // only burn the once-ever flag when the canvas can actually
            // show the card; the card stays until the user acts (max 10s)
            if (!state.present.hasContent) {
                hintVisible = true
                withTimeoutOrNull(10000) {
                    snapshotFlow { state.present.hasContent }.first { it }
                }
                hintVisible = false
            }
            vm.repository.markHintShown()
        }
    }
    fun dismissHint() {
        if (hintVisible) {
            hintVisible = false
            scope.launch { vm.repository.markHintShown() }
        }
    }

    // Last Magic Touch failure (drives the recovery card) + elapsed secs
    var segFailed by remember { mutableStateOf(false) }
    var sculptSecs by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.sculpting) {
        sculptSecs = 0
        while (state.sculpting) {
            delay(1000)
            sculptSecs++
        }
    }

    // Sheets / transient UI
    var helpOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var tagsOpen by remember { mutableStateOf(false) }
    var objOpen by remember { mutableStateOf(false) }
    var tagDraft by remember { mutableStateOf("") }

    // Real photo decode (demo scenes draw their vector art instead).
    val photo: ImageBitmap? by produceState<ImageBitmap?>(null, image.id) {
        value = null // never render the previous image's bitmap under new prompts
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

    // ── auto-suggest engine ──────────────────────────────────────────────
    //
    // Two mutually exclusive paths per image kind:
    //  · SCENE images (seeded demo art): deterministic anchor sculpt —
    //    the exact prototype mesh.ts semantics.
    //  · REAL PHOTOS: Magic Touch — the prompt dots (+/−) and brush
    //    strokes drive the MediaPipe InteractiveSegmenter; the traced
    //    mask contour becomes the mesh. Every dot add/MOVE/remove and
    //    every brush stroke re-runs it (600ms debounce, deferred while a
    //    gesture is live).
    val segmenter = remember { SmartSegmenter(context) }
    DisposableEffect(Unit) { onDispose { segmenter.close() } }
    var segRequest by remember { mutableIntStateOf(0) }
    val lastSig = remember { arrayOf<String?>(null) }      // last prompt signature processed
    val segSig = remember { arrayOf<String?>(null) }       // signature the in-flight request was built from
    var suppressAuto by remember { mutableStateOf(false) }

    val promptSig = remember(state.present.dots, state.present.strokes) { sigOf(state.present) }

    // ── image load / switch: persist the previous, reset history ─────────
    var loadedImageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(image.id) {
        if (loadedImageId != null && loadedImageId != image.id) {
            vm.repository.saveImageState(project.id, loadedImageId!!, state.present)
        }
        state.reset(project.stateOf(image.id))
        loadedImageId = image.id
        lastSig[0] = sigOf(state.present)
    }

    // ── autosave (300ms debounce, settings-gated) ────────────────────────
    LaunchedEffect(state.present, image.id) {
        if (loadedImageId != image.id) return@LaunchedEffect
        if (!settings.autosave) return@LaunchedEffect
        delay(LoomMotion.AUTOSAVE_THROTTLE_MS)
        vm.repository.saveImageState(project.id, image.id, state.present)
    }

    LaunchedEffect(promptSig, image.id, photo) {
        if (promptSig == lastSig[0]) return@LaunchedEffect
        if (suppressAuto) {
            // History op (undo/redo/clear) already restored the right state —
            // just sync the signature so the next real edit re-sculpts.
            suppressAuto = false
            state.sculpting = false
            lastSig[0] = promptSig
            return@LaunchedEffect
        }
        state.sculpting = true
        delay(SUGGEST_DEBOUNCE_MS)
        while (state.gestureActive) delay(LoomMotion.SUGGEST_DEFER_REARM_MS)

        if (image.sceneId != null) {
            // ── deterministic sculpt (prototype path, scene anchors) ────
            lastSig[0] = promptSig
            val hasPos = state.present.dots.any { it.isPos }
            if (!hasPos && state.present.mesh == null) {
                state.sculpting = false
                return@LaunchedEffect
            }
            state.commitOrFuse { prev ->
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
        } else {
            // ── Magic Touch (real photos) ────────────────────────────────
            val bmp = photo
            if (bmp == null) {
                // Photo still decoding — leave lastSig unconsumed so the
                // decoded-photo re-run performs the segmentation.
                state.sculpting = false
                return@LaunchedEffect
            }
            lastSig[0] = promptSig
            val hasPrompts = state.present.dots.isNotEmpty() || state.present.strokes.isNotEmpty()
            if (!hasPrompts) {
                state.sculpting = false
                return@LaunchedEffect
            }
            val layout = PhotoLayout.of(bmp.width, bmp.height)
            val sigAtRequest = promptSig
            // FULL state at request time — the delivery guard compares the
            // whole ImageState so mesh/density/objects edits made during the
            // 1-3s processing window can never be overwritten
            val presentAtRequest = state.present
            segSig[0] = sigAtRequest
            val specs = buildSegSpecs(state.present, layout)
            segRequest = segmenter.segment(
                bmp.asAndroidBitmap(),
                specs,
                { requestId, mask, w, h ->
                    // Stale guards: superseded request, switched image, or
                    // ANY state change meanwhile (undo/clear/remove-mesh/
                    // vertex edit/new prompts) — drop.
                    if (requestId != segRequest) return@segment
                    if (loadedImageId != image.id) return@segment
                    if (segSig[0] != sigAtRequest || state.present != presentAtRequest) return@segment
                    scope.launch {
                        // Mask tracing is heavy — keep it off the main thread.
                        // Trace DENSE (epsilon≈0) and let the adaptive fitter
                        // place vertices: dense on curves, minimal on runs.
                        val denseImg = withContext(Dispatchers.Default) {
                            MaskToPolygon.trace(mask, w, h, epsilon = 0.05f)
                        }
                        // re-verify after the trace window: any click may
                        // have landed while we traced
                        if (loadedImageId != image.id || state.present != presentAtRequest) {
                            return@launch
                        }
                        if (denseImg.isEmpty()) {
                            state.sculpting = false
                            segFailed = true
                            vm.toast("No object found — put a + dot on it", ToastIcon.WARN)
                            return@launch
                        }
                        val denseWorld = denseImg.map { layout.imageToWorld(it.x, it.y) }
                        // densityAuto → the optimal minimal fit; manual →
                        // refit the dense mask at the user's vertex count
                        val (ring, autoDensity) = withContext(Dispatchers.Default) {
                            if (state.present.densityAuto) {
                                MeshMath.autoFitRing(denseWorld, LoomRules.DENSITY_MIN, LoomRules.DENSITY_MAX)
                            } else {
                                MeshMath.fitRing(
                                    denseWorld,
                                    LoomRules.clampDensity(state.present.density),
                                ) to LoomRules.clampDensity(state.present.density)
                            }
                        }
                        state.denseRing = denseWorld
                        // fused with the prompt commit when armed: one Undo
                        // removes the dot AND the mask together
                        state.commitOrFuse { prev ->
                            prev.copy(
                                mesh = ring,
                                density = if (prev.densityAuto) autoDensity else prev.density,
                                // brush strokes are consumed by the mask
                                strokes = emptyList(),
                            )
                        }
                        state.meshGen++
                        state.sculpting = false
                        segFailed = false
                        // consuming the brush strokes changed promptSig —
                        // sync it so the mesh is NOT re-segmented 600ms later
                        lastSig[0] = sigOf(state.present)
                        pulseNow()
                    }
                },
                { requestId, message ->
                    if (requestId != segRequest || loadedImageId != image.id) return@segment
                    if (segSig[0] != sigAtRequest || state.present != presentAtRequest) return@segment
                    state.sculpting = false
                    segFailed = true
                    android.util.Log.w("LoomSeg", "segmentation failed: $message")
                    vm.toast("Magic Touch couldn\u2019t run — try again", ToastIcon.WARN)
                },
            )
        }
    }

    // A rebuilt mesh (sculpt/segment/remesh) invalidates vertex indices.
    LaunchedEffect(state.meshGen) {
        if (state.meshGen > 0) state.selection = null
    }

    // ── exit flush ───────────────────────────────────────────────────────
    fun flushAndExit() {
        scope.launch {
            vm.repository.saveImageState(project.id, image.id, state.present)
            if (state.present.objects.isNotEmpty() || state.present.mesh != null) {
                vm.repository.setImageStatus(project.id, image.id, ImageStatus.DONE)
            }
            onExit()
        }
    }
    BackHandler { flushAndExit() }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${fmt2(index + 1)} / ${fmt2(project.images.size)}",
                        style = loomType.monoCounter,
                        color = c.text,
                    )
                    Spacer(Modifier.width(8.dp))
                    val st = project.stateOf(image.id).status
                    Text(
                        text = st.wire,
                        style = loomType.monoChip.copy(fontSize = 10.sp),
                        color = when (st) {
                            ImageStatus.DONE -> c.pos
                            ImageStatus.DRAFT -> c.primary
                            ImageStatus.UNLABELED -> c.textMuted
                        },
                    )
                }
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
                state.denseRing = null
                state.sculpting = false
                state.undo()
                suppressAuto = sigOf(state.present) != lastSig[0]
            }, enabled = state.canUndo)
            LoomIconButton(IconRedo, "Redo", {
                state.densityDraft = null
                state.denseRing = null
                state.sculpting = false
                state.redo()
                suppressAuto = sigOf(state.present) != lastSig[0]
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
            ToolButton(Tool.BRUSH, IconBrush, "Object brush", state, Modifier.weight(1f))
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
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            key(image.id) {
            CanvasStage(
                scene = scene,
                photo = photo,
                toneHint = scene?.tone ?: c.canvas.toArgbInt(),
                state = state,
                dotSizeWorld = settings.dotSize,
                magnifier = settings.magnifier,
                showHint = hintVisible,
                onHintTap = { dismissHint() },
                onWarn = { msg -> vm.toast(msg, ToastIcon.WARN) },
                labelColors = project.labels.associate {
                    it.id to c.classColor(it.color)
                },
                onCanvasFeedback = {
                    if (settings.haptics) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            }

            // status pill (TOP-CENTER, clear of the corner loupe):
            // animated "Magic Touch…" with elapsed seconds + cancel while
            // segmenting; "N obj · M pts" once a mesh exists
            val meshSize = state.mesh?.size ?: 0
            if (state.sculpting || state.mesh != null || state.objects.isNotEmpty()) {
                val dotAnim = rememberInfiniteTransition(label = "pill")
                val dotF by dotAnim.animateFloat(
                    initialValue = 0f,
                    targetValue = 4f,
                    animationSpec = infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)),
                    label = "pillDots",
                )
                val dots = dotF.toInt().coerceIn(0, 3)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .clip(CircleShape)
                        .background(c.surface1.copy(alpha = 0.9f))
                        .border(1.dp, if (state.sculpting) c.mesh.copy(alpha = 0.55f) else c.hairline, CircleShape)
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (state.sculpting) {
                            "Magic Touch" + ".".repeat(dots) + if (sculptSecs >= 2) " ${sculptSecs}s" else ""
                        } else {
                            buildString {
                                if (state.objects.isNotEmpty()) append("${state.objects.size} obj")
                                if (state.mesh != null) {
                                    if (isNotEmpty()) append(" · ")
                                    append("$meshSize pts")
                                }
                            }
                        },
                        style = loomType.monoPill,
                        color = if (state.sculpting) c.primary else c.text,
                    )
                    if (state.sculpting) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    // semantic cancel: stale guards drop the result
                                    segRequest++
                                    state.sculpting = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(IconClose, contentDescription = "Cancel", tint = c.textMuted, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }

            // Magic Touch failure recovery card (auto-clears on success)
            if (segFailed) {
                LaunchedEffect(segFailed) {
                    delay(8000)
                    segFailed = false
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surface1.copy(alpha = 0.96f))
                        .border(1.dp, c.hairlineStrong, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IconAlert, contentDescription = null, tint = c.primary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Couldn\u2019t find an object — move the + dot onto it, or brush around it.",
                        style = loomType.small.copy(fontSize = 12.sp),
                        color = c.text,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Dismiss",
                        style = loomType.smallStrong,
                        color = c.primary,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { segFailed = false },
                    )
                }
            }

            // zoom chip (bottom-START — Save & next owns the bottom-right)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
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
            // object bar — saved objects (tap to re-edit) + the working one
            androidx.compose.animation.AnimatedVisibility(
                visible = state.mesh != null || state.objects.isNotEmpty(),
                enter = androidx.compose.animation.fadeIn(tween(LoomMotion.DUR_3, easing = LoomMotion.ease)) +
                    androidx.compose.animation.expandVertically(tween(LoomMotion.DUR_3, easing = LoomMotion.ease)),
                exit = androidx.compose.animation.fadeOut(tween(LoomMotion.DUR_2, easing = LoomMotion.ease)) +
                    androidx.compose.animation.shrinkVertically(tween(LoomMotion.DUR_2, easing = LoomMotion.ease)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Objects", style = loomType.small, color = c.textMuted)
                    state.objects.forEach { obj ->
                        val lbl = project.labels.firstOrNull { it.id == obj.labelId }
                        LabelChip(
                            name = lbl?.name ?: "Object",
                            colorToken = lbl?.color ?: "c1",
                            active = false,
                            onClick = {
                                if (state.mesh != null) {
                                    vm.toast("Save or remove the current object first", ToastIcon.WARN)
                                } else {
                                    reopenObject(state, obj.id)
                                }
                            },
                        )
                    }
                    val curLabel = project.labels.firstOrNull { it.id == state.present.labelId }
                    if (state.mesh != null) {
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
                        LoomIconButton(IconSettings, "Configure object", { objOpen = true }, size = 36.dp, iconSize = 16.dp)
                    }
                    if (state.mesh == null && state.objects.isNotEmpty()) {
                        LoomChip(
                            label = "＋ Add another object",
                            icon = IconPlus,
                            onClick = { state.tool = Tool.DOT_POS },
                        )
                    }
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
                    suffix = if (state.present.densityAuto) "auto" else "pts",
                    onChange = { v ->
                        state.densityDraft = v
                        scope.launch {
                            delay(LoomMotion.DENSITY_REMESH_COMMIT_MS)
                            if (state.densityDraft == v) {
                                state.commit { prev ->
                                    prev.copy(
                                        density = v,
                                        densityAuto = false,
                                        mesh = remeshForImage(image, v, prev, state.denseRing),
                                    )
                                }
                                state.densityDraft = null
                                state.meshGen++
                                // manual density becomes the default for
                                // not-yet-annotated images too (owner req)
                                launch {
                                    vm.repository.patchSettings(settings.copy(defaultDensity = v))
                                }
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
                if (state.mesh != null) {
                    LoomButton(
                        onClick = {
                            if (project.labels.isEmpty()) {
                                vm.toast("Add a class on the project screen first", ToastIcon.WARN)
                                return@LoomButton
                            }
                            if (state.present.labelId == null) {
                                // never save with an implicit class — ask
                                objOpen = true
                                return@LoomButton
                            }
                            state.commit { prev ->
                                val m = prev.mesh ?: return@commit prev
                                prev.copy(
                                    objects = prev.objects + ObjAnn(Ids.uid("obj"), prev.labelId, m, prev.density),
                                    mesh = null,
                                    dots = emptyList(),
                                    strokes = emptyList(),
                                    labelId = null,
                                )
                            }
                            state.denseRing = null
                            state.meshGen++
                            state.selection = null
                            state.tool = Tool.DOT_POS
                            pulseNow()
                            vm.toast("Object saved — add the next one", ToastIcon.CHECK)
                        },
                        size = LoomButtonSize.SM,
                        variant = LoomButtonVariant.TONAL,
                        icon = IconCheck,
                        label = "Save object",
                    )
                    Spacer(Modifier.width(8.dp))
                }
                LoomButton(
                    onClick = {
                        scope.launch {
                            vm.repository.saveImageState(project.id, image.id, state.present, embedImage = true)
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
                            vm.repository.saveImageState(project.id, image.id, state.present, embedImage = true)
                            index = i
                        }
                    }
                },
            )
        }
    }

    // ── sheets ───────────────────────────────────────────────────────────
    LoomSheet(open = helpOpen, onClose = { helpOpen = false }, title = "Canvas guide") {
        GuideRow(IconDotPlus, "Prompt dots & Magic Touch", "+ dots mark what to include, − dots what to avoid. On photos the Magic Touch model turns them into a precise mask; the mesh then hugs the object.")
        GuideRow(IconMesh, "Auto mesh & density", "The mesh rebuilds itself ~1s after each change. Density sets its vertex count — the smart mask picks the optimal count automatically, and the stepper re-fits from the full mask shape.")
        GuideRow(IconSettings, "Object label", "Once a mesh exists the object bar rises in with the object it belongs to — tap the object chip or the gear to edit the mesh: pick its class, tune density, check the vertex count, remove it.")
        GuideRow(IconNodes, "Edit vertices", "Drag a vertex — the loupe parks in the corner. Tap an edge midpoint to add one; select a vertex and use Delete to remove it.")
        GuideRow(IconBrush, "Brush & eraser", "On photos, paint around an object to SELECT it (strokes turn into mask). On scene images the brush excludes an area. The eraser removes strokes.")
        GuideRow(IconTag, "Image tags", "Flag the image itself — Blurry, Occluded, Low light or your own. Tags sit in the top bar, ride with the annotations and export with the dataset.")
        GuideRow(IconPan, "Gestures", "Pan with one finger, pinch to zoom 50–1000%, double-tap for 2×. Undo and redo live in the top bar.")
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
                    state.sculpting = false
                    state.denseRing = null
                    state.commit { prev ->
                        prev.copy(dots = emptyList(), mesh = null, strokes = emptyList(), status = ImageStatus.UNLABELED)
                    }
                    suppressAuto = sigOf(state.present) != lastSig[0]
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
                    state.commit { prev ->
                        prev.copy(
                            density = v,
                            densityAuto = false,
                            mesh = remeshForImage(image, v, prev, state.denseRing),
                        )
                    }
                    scope.launch {
                        vm.repository.patchSettings(settings.copy(defaultDensity = v))
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
                suppressAuto = sigOf(state.present) != lastSig[0]
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
        if (state.objects.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("SAVED OBJECTS (${state.objects.size})", style = loomType.sectionLabel, color = c.textMuted)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.objects.forEach { obj ->
                    val lbl = project.labels.firstOrNull { it.id == obj.labelId }
                    LabelChip(
                        name = lbl?.name ?: "Object",
                        colorToken = lbl?.color ?: "c1",
                        active = false,
                        onRemove = { deleteObject(state, obj.id) },
                    )
                }
            }
        }
    }
}

// ── small pieces ──────────────────────────────────────────────────────────

private fun fmt2(n: Int) = n.toString().padStart(2, '0')

/** Pulls a saved object back into the working mesh for re-editing. */
private fun reopenObject(state: AnnotateState, objId: String) {
    val obj = state.present.objects.firstOrNull { it.id == objId } ?: return
    state.commit { prev ->
        prev.copy(
            objects = prev.objects.filter { it.id != objId },
            mesh = obj.mesh,
            density = obj.density,
            labelId = obj.labelId,
            dots = emptyList(),
            strokes = emptyList(),
        )
    }
    state.denseRing = null
    state.meshGen++
    state.selection = null
}

/** Deletes a saved object outright. */
private fun deleteObject(state: AnnotateState, objId: String) {
    state.commit { prev -> prev.copy(objects = prev.objects.filter { it.id != objId }) }
    state.meshGen++
}

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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(LoomMotion.DUR_1), label = "toolScale")
    Box(
        modifier = modifier
            .height(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(if (active) c.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null) {
                state.tool = tool
                state.selection = null
                state.live = null
                state.gestureActive = false // a mid-gesture tool switch must not freeze the engine
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

/**
 * Letterbox layout of a photo inside the 800×600 world — the exact
 * mapping [com.testplaybyte.loom.ui.screens.annotate.CanvasStage] uses in
 * drawPhoto, exposed so prompt points can move between world and image
 * pixel coordinates (the segmenter works in image pixels).
 */
private data class PhotoLayout(val s: Float, val dx: Float, val dy: Float, val pw: Int, val ph: Int) {
    fun imageToWorld(ix: Float, iy: Float) = Pt(ix * s + dx, iy * s + dy)

    /** Image-pixel coords, clamped into the bitmap (prompt dots may sit in
     *  the letterbox margin; the segmenter expects in-bounds points). */
    fun worldToImage(wx: Float, wy: Float) = Pt(
        ((wx - dx) / s).coerceIn(0f, pw - 1f),
        ((wy - dy) / s).coerceIn(0f, ph - 1f),
    )

    companion object {
        fun of(pw: Int, ph: Int): PhotoLayout {
            val s = min(800f / pw, 600f / ph)
            return PhotoLayout(s, (800f - pw * s) / 2f, (600f - ph * s) / 2f, pw, ph)
        }
    }
}

/** Prompt signature: any dot add/move/remove or brush change re-sculpts. */
private fun sigOf(s: com.testplaybyte.loom.domain.model.ImageState): String = buildString {
    s.dots.forEach { append("${it.kind}:${it.x.toInt()},${it.y.toInt()};") }
    append('|')
    s.strokes.forEach { append("${it.pts.size},") }
}

/**
 * Density change while a mesh exists: scene images resculpt from their
 * anchors (prototype semantics); photos resample the CURRENT ring — a
 * photo has no anchor set, and its shape came from the segmentation.
 */
private fun remeshForImage(
    image: com.testplaybyte.loom.domain.model.ProjectImage,
    density: Int,
    prev: com.testplaybyte.loom.domain.model.ImageState,
    denseRing: List<Pt>? = null,
): List<Pt>? {
    if (image.sceneId != null) {
        val anchors = SceneLibrary.byId(image.sceneId).target.anchors
        return MeshMath.buildMesh(anchors.map { a -> Pt(a.first, a.second) }, density, prev.dots)
    }
    // Photos re-fit ADAPTIVELY from the last dense mask contour when we
    // have it (curves get the detail), else from the current ring.
    val source = denseRing ?: prev.mesh ?: return null
    return if (source.size >= 3) MeshMath.fitRing(source, density) else source
}

/**
 * Builds the Magic Touch stroke list from the persisted prompts:
 * + dots → POSITIVE strokes, − dots → NEGATIVE strokes, exclusion brush
 * strokes → NEGATIVE scribbles (thinned, capped). Coordinates convert
 * from world (800×600) into image pixels via [layout].
 */
private fun buildSegSpecs(
    state: com.testplaybyte.loom.domain.model.ImageState,
    layout: PhotoLayout,
): List<SmartSegmenter.Spec> {
    val specs = ArrayList<SmartSegmenter.Spec>()
    state.dots.forEach { d ->
        val ip = layout.worldToImage(d.x, d.y)
        specs.add(SmartSegmenter.Spec(listOf(ip), d.isPos))
    }
    // Brush strokes are POSITIVE on photos: drawing around an object
    // SELECTS it (Edge Gallery semantics). They are consumed on success.
    state.strokes.forEach { st ->
        val pts = st.pts.map { layout.worldToImage(it.x, it.y) }
        if (pts.isNotEmpty()) specs.add(SmartSegmenter.Spec(pts, positive = true))
    }
    return specs
}

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
