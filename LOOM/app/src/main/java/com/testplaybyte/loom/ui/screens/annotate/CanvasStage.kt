package com.testplaybyte.loom.ui.screens.annotate

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.testplaybyte.loom.data.scene.CompiledScene
import com.testplaybyte.loom.data.scene.SceneLibrary
import com.testplaybyte.loom.domain.mesh.MeshMath
import com.testplaybyte.loom.domain.model.Dot
import com.testplaybyte.loom.domain.model.DotKind
import com.testplaybyte.loom.domain.model.Ids
import com.testplaybyte.loom.domain.model.LoomRules
import com.testplaybyte.loom.domain.model.Pt
import com.testplaybyte.loom.domain.model.Stroke as LoomStroke
import com.testplaybyte.loom.ui.icons.IconClose
import com.testplaybyte.loom.ui.icons.IconSparkles
import com.testplaybyte.loom.ui.icons.IconTrash
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The interactive annotation world (docs/03 §6 + docs/04 §3–§6) — port of
 * the prototype's `canvas-stage.tsx`.
 *
 * · fixed 800×600 world under a camera {scale, tx, ty}: fit-contain on
 *   open, pinch zoom 50–1000% anchored at the gesture midpoint, double-tap
 *   2×/1×, one-finger pan with edge clamping, world-fixed 24-unit grid;
 * · tools: pan · +dot · −dot · exclusion brush · mesh vertex edit (loupe,
 *   edge-insert, long-press delete, nudge arrows) · eraser;
 * · every COMPLETED gesture is exactly one `commit` (one undo unit).
 *
 * All constants (hit radii, slops, loupe geometry, nudge step) are the
 * exact values from reference/tokens.json `canvas`.
 */
private const val WORLD_W = 800f
private const val WORLD_H = 600f
private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 20f
private const val HIT_DP = 26f          // dots + vertices (density-independent)
private const val SLOP_DP = 10f         // press → drag
private const val EDGE_SNAP_DP = 14f    // edge tap → insert vertex
private const val VERTEX_EPS = 1.5f     // world units
private const val ERASER_HIT_WORLD = 24f
private const val LOUPE_PX = 112f
private const val GRID_CELL = 24f
private const val MIN_VISIBLE = 72f     // world px that must stay on screen
private const val FIT_PADDING = 28f

private fun clamp(v: Float, a: Float, b: Float) = min(b, max(a, v))

@Composable
fun CanvasStage(
    scene: CompiledScene?,
    photo: ImageBitmap?,
    toneHint: Int,
    state: AnnotateState,
    dotSizeWorld: Int,
    magnifier: Boolean,
    meshHighlight: Int = 1,
    showHint: Boolean = false,
    onHintTap: () -> Unit = {},
    onWarn: (String) -> Unit = {},
    onCanvasFeedback: () -> Unit = {},
    labelColors: Map<String, androidx.compose.ui.graphics.Color> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val c = loomColors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val gestures = remember { Gestures(state, scope) }.apply {
        densityPx = density.density
        this.onWarn = onWarn
        this.onStrokeRemoved = onCanvasFeedback
        this.onDotPlaced = onCanvasFeedback
    }

    // Fit-contain once per image; re-clamp when the viewport changes.
    LaunchedEffect(size, state.present.hashCode()) {
        if (size != IntSize.Zero) gestures.fitIfNeeded(size, density.density)
    }

    // leaving the DRAW tool disarms it
    LaunchedEffect(state.tool) {
        if (state.tool != Tool.DRAW) state.drawArmed = false
    }

    // mesh highlight strength (Settings): fill / saved-stroke alphas
    val hl = meshHighlight.coerceIn(0, 2)
    val meshFillAlpha = listOf(0.08f, 0.16f, 0.26f)[hl]
    val savedStrokeAlpha = listOf(0.35f, 0.6f, 0.85f)[hl]

    // Camera requests from the zoom chip / fit button.
    LaunchedEffect(state.pendingFit) {
        if (state.pendingFit) {
            gestures.fitNow()
            state.pendingFit = false
        }
    }
    LaunchedEffect(state.pendingZoomFactor) {
        state.pendingZoomFactor?.let { f ->
            gestures.zoomBy(f)
            state.pendingZoomFactor = null
        }
    }

    // marching-ants phase (seamless at any zoom: period fed per-zoom)
    val ants = rememberInfiniteTransition(label = "ants")
    val antsPhase by ants.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "antsPhase",
    )

    // dot pop-in: a short spring scale when the dot count changes
    val dotPop = remember { Animatable(1f) }
    LaunchedEffect(state.present.dots.size) {
        dotPop.snapTo(0.4f)
        dotPop.animateTo(1f, tween(170, easing = com.testplaybyte.loom.ui.theme.LoomMotion.easeSpring))
    }

    Box(
        modifier = modifier
            .onSizeChanged { size = it; gestures.viewport = it }
            .background(c.canvas),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(state.tool) {
                    gestures.handlePointers(this)
                },
        ) {
            val cam = state.cam
            val liveMesh = state.liveMesh()
            val liveDots = state.liveDots()
            // screen→world factor: multiply a screen-px size by this to
            // draw it zoom-invariant (constant ON-SCREEN size) inside the
            // world transform — dots and handles must NOT grow with zoom.
            val px = 1f / cam.scale
            // prompt dots only exist while a dot tool is active — in mesh/
            // edit/brush view they disappear (owner req)
            val dotsVisible = state.tool == Tool.DOT_POS || state.tool == Tool.DOT_NEG
            // while a NEW object is being annotated, saved objects dim to
            // almost nothing so their borders never compete for attention
            val workingNow = state.mesh != null

            // ── world transform ─────────────────────────────────────────
            translate(cam.tx, cam.ty) {
                scale(cam.scale, cam.scale, pivot = Offset.Zero) {
                    // world grid FIRST (behind the art) and only INSIDE the
                    // world frame — the image sits on one clean grid, never
                    // a second one painted over it
                    drawGrid(c.grid, cam.scale)
                    // art (photo letterboxed, or demo vector art)
                    if (photo != null) {
                        drawPhoto(photo, toneHint)
                    } else if (scene != null) {
                        scene.drawCompose(this)
                    }
                    drawRect(
                        color = c.hairlineStrong,
                        topLeft = Offset(0.5f, 0.5f),
                        size = Size(WORLD_W - 1f, WORLD_H - 1f),
                        style = Stroke(width = 1f * px),
                    )

                    // prompt strokes (below the mesh): on real photos the
                    // brush SELECTS (green, like + dots); on scenes it
                    // EXCLUDES (coral) — matching what the segmenter does
                    val strokeColor = if (scene == null && photo != null) c.pos else c.neg
                    for (s in state.strokes) drawStrokePolyline(s.pts, strokeColor, 3.5f * px)
                    (state.live as? Live.BrushLive)?.let { brush ->
                        drawStrokePolyline(brush.pts, strokeColor, 3.5f * px)
                    }
                    // freehand DRAW-mesh path in flight — mesh-colored, with
                    // the adaptive vertices previewing ON the path while the
                    // finger moves (owner req: dots appear while drawing)
                    (state.live as? Live.DrawLive)?.let { drawLive ->
                        drawStrokePolyline(
                            drawLive.pts,
                            labelColors[state.present.labelId] ?: c.mesh,
                            2.5f * px,
                        )
                        drawLive.preview?.forEach { p ->
                            drawCircle(
                                color = labelColors[state.present.labelId] ?: c.mesh,
                                radius = 2.6.dp.toPx() * px,
                                center = Offset(p.x, p.y),
                            )
                            drawCircle(
                                color = Color(0xFFFFFDF6),
                                radius = 2.6.dp.toPx() * px,
                                center = Offset(p.x, p.y),
                                style = Stroke(width = 1.1f * px),
                            )
                        }
                    }

                    // saved objects sit under the working mesh, dimmer,
                    // each in its CLASS color (fill follows the class too)
                    for (obj in state.present.objects) {
                        if (obj.mesh.size < 3) continue
                        drawObjectMesh(
                            obj.mesh,
                            labelColors[obj.labelId] ?: c.mesh,
                            c,
                            dim = workingNow,
                            px = px,
                            strokeAlpha = savedStrokeAlpha,
                        )
                    }

                    // mesh: soft fill + CLASS-colored line + ants + handles
                    val meshColor = labelColors[state.present.labelId] ?: c.mesh
                    if (liveMesh != null && liveMesh.size >= 3) {
                        val path = polygonPath(liveMesh)
                        run {
                            drawPath(path, color = meshColor.copy(alpha = meshFillAlpha))
                            drawPath(
                                path, color = meshColor,
                                style = Stroke(width = 2f * px, join = StrokeJoin.Round),
                            )
                            // marching ants: dash period and offset in world units
                            drawPath(
                                path, color = c.canvas,
                                style = Stroke(
                                    width = 1.3f * px,
                                    join = StrokeJoin.Round,
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(6f * px, 5f * px),
                                        phase = -antsPhase * 11f * px,
                                    ),
                                ),
                            )
                            if (state.tool == Tool.EDIT) {
                                for (i in liveMesh.indices) {
                                    val p = liveMesh[i]
                                    val q = liveMesh[(i + 1) % liveMesh.size]
                                    val armed = state.pendingMid == i
                                    val m = Offset((p.x + q.x) / 2f, (p.y + q.y) / 2f)
                                    val mr = (if (armed) 4.dp else 3.dp).toPx() * px
                                    drawCircle(
                                        color = if (armed) meshColor else c.canvas,
                                        radius = mr,
                                        center = m,
                                    )
                                    drawCircle(
                                        color = if (armed) c.canvas else meshColor,
                                        radius = mr,
                                        center = m,
                                        style = Stroke(width = 1.2f * px),
                                    )
                                }
                                val vR = 3.dp.toPx() * px
                                val vSelR = 5.dp.toPx() * px
                                val vHaloR = 14.dp.toPx() * px
                                for (i in liveMesh.indices) {
                                    val p = liveMesh[i]
                                    val selected = (state.selection as? Selection.Vertex)?.index == i
                                    if (selected) {
                                        // prominent selection halo (the grab
                                        // target must be obvious)
                                        drawCircle(
                                            color = meshColor.copy(alpha = 0.35f),
                                            radius = vHaloR,
                                            center = Offset(p.x, p.y),
                                        )
                                    }
                                    drawCircle(
                                        color = Color(0xFFFFFDF6),
                                        radius = if (selected) vSelR else vR,
                                        center = Offset(p.x, p.y),
                                    )
                                    drawCircle(
                                        color = if (selected) c.primary else meshColor,
                                        radius = if (selected) vSelR else vR,
                                        center = Offset(p.x, p.y),
                                        style = Stroke(width = (if (selected) 2f else 1.6f) * px),
                                    )
                                }
                            }
                        }
                    }

            // prompt dots (dot tools only — hidden in mesh view)
            if (dotsVisible) {
                // zoom-out guard: dots hold their size until the image gets
                // small, then shrink with it so they never bury the photo
                val dotZoomK = clamp(cam.scale / 0.9f, 0.4f, 1f)
                val dotScreenR = (dotSizeWorld * 0.9f).dp.toPx() * px * dotZoomK
                for ((i, d) in liveDots.withIndex()) {
                    val selected = (state.selection as? Selection.DotSel)?.id == d.id
                    // staggered pop: later dots lag slightly
                    val stagger = (i % 16) * 0.06f
                    val s = clamp((dotPop.value - stagger) / (1f - stagger).coerceAtLeast(0.01f), 0.4f, 1f)
                    scale(s, s, pivot = Offset(d.x, d.y)) {
                        drawDot(d, dotScreenR, selected, c, px)
                    }
                }
            }
                }
            }
        }

        // ── loupe ───────────────────────────────────────────────────────
        LoupeOverlay(
            state = state,
            scene = scene,
            photo = photo,
            toneHint = toneHint,
            magnifier = magnifier,
            viewport = size,
        )

        // ── delete chip (edit + selection) ───────────────────────────────
        DeleteChip(
            state = state,
            onDelete = { gestures.deleteSelection() },
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
        )

        // ── first-run hint: square-ish, 2s, tap-dismiss, once ever ──────
        val hasContent = state.dots.isNotEmpty() || state.mesh != null ||
            state.strokes.isNotEmpty() || state.objects.isNotEmpty()
        if (!hasContent && showHint) {
            Column3Hint(
                modifier = Modifier.align(Alignment.Center),
                onTap = onHintTap,
            )
        }
    }
}

// ── drawing helpers ───────────────────────────────────────────────────────

/** The polygon EXACTLY as the points define it — straight edges, no
 *  smoothing, so the annotation is what you see (and what exports). */
private fun polygonPath(pts: List<Pt>): Path = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    close()
}

/**
 * A saved object in its class color: class-tinted fill + hairline, no
 * handles. [dim] = a NEW object is being annotated: the saved ones fade
 * to a whisper so their borders never compete with the work in progress.
 */
private fun DrawScope.drawObjectMesh(
    mesh: List<Pt>,
    color: Color,
    c: com.testplaybyte.loom.ui.theme.LoomColors,
    dim: Boolean,
    px: Float,
    strokeAlpha: Float,
) {
    val path = polygonPath(mesh)
    if (!dim) drawPath(path, color = color.copy(alpha = strokeAlpha * 0.16f))
    drawPath(
        path,
        color = color.copy(alpha = if (dim) strokeAlpha * 0.35f else strokeAlpha),
        style = Stroke(width = 1.4f * px, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawPhoto(photo: ImageBitmap, toneHint: Int) {
    drawRect(Color(toneHint))
    val sx = WORLD_W / photo.width
    val sy = WORLD_H / photo.height
    val s = min(sx, sy)
    val dw = photo.width * s
    val dh = photo.height * s
    drawImage(
        image = photo,
        dstOffset = IntOffset(((WORLD_W - dw) / 2f).toInt(), ((WORLD_H - dh) / 2f).toInt()),
        dstSize = IntSize(dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1)),
    )
}

/** Drafting grid, world-fixed (24-unit cells), clipped to the world frame. */
private fun DrawScope.drawGrid(color: Color, camScale: Float) {
    val stroke = 1f / camScale
    clipRect(left = 0f, top = 0f, right = WORLD_W, bottom = WORLD_H) {
        var x = 0f
        while (x <= WORLD_W) {
            drawLine(color, Offset(x, 0f), Offset(x, WORLD_H), strokeWidth = stroke)
            x += GRID_CELL
        }
        var y = 0f
        while (y <= WORLD_H) {
            drawLine(color, Offset(0f, y), Offset(WORLD_W, y), strokeWidth = stroke)
            y += GRID_CELL
        }
    }
}

private fun DrawScope.drawStrokePolyline(pts: List<Pt>, color: Color, widthWorld: Float) {
    if (pts.isEmpty()) return
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    drawPath(
        path = path,
        color = color.copy(alpha = 0.6f),
        style = Stroke(width = widthWorld, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * Dot glyphs — a MATCHED pair (owner req): filled disc (mint/coral),
 * canvas ring, and the actual +/− glyph inside. [r] is the screen radius
 * already converted to world units by the caller; [px] keeps ring/glyph
 * strokes zoom-invariant too.
 */
private fun DrawScope.drawDot(
    d: Dot,
    r: Float,
    selected: Boolean,
    c: com.testplaybyte.loom.ui.theme.LoomColors,
    px: Float,
) {
    val center = Offset(d.x, d.y)
    if (selected) {
        // contrast-first selection: a paper-white halo UNDER the signal
        // ring keeps it visible on any background (incl. light photos,
        // where amber alone disappeared)
        drawCircle(
            color = Color(0xFFFFFDF6),
            radius = r + 5.5f * px,
            center = center,
            style = Stroke(width = 3.2f * px),
        )
        drawCircle(
            color = c.primary,
            radius = r + 5.5f * px,
            center = center,
            style = Stroke(
                width = 1.9f * px,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * px, 3f * px)),
            ),
        )
    }
    val col = if (d.isPos) c.pos else c.neg
    drawCircle(color = col, radius = r, center = center)
    drawCircle(
        color = c.canvas, radius = r, center = center,
        style = Stroke(width = 1.6f * px),
    )
    // the +/− glyph, in the canvas ink (contrasts on both polarity colors)
    val arm = r * 0.52f
    val w = 2f * px
    drawLine(c.canvas, Offset(d.x - arm, d.y), Offset(d.x + arm, d.y), strokeWidth = w, cap = StrokeCap.Round)
    if (d.isPos) {
        drawLine(c.canvas, Offset(d.x, d.y - arm), Offset(d.x, d.y + arm), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
private fun Column3Hint(modifier: Modifier = Modifier, onTap: () -> Unit) {
    val c = loomColors
    Box(
        modifier = modifier
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface1.copy(alpha = 0.9f))
            .border(1.dp, c.hairline, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(horizontal = 18.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                IconSparkles,
                contentDescription = null,
                tint = c.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Tap + dots on the object — Magic Touch masks it.\n− dots remove areas.",
                style = loomType.small.copy(fontSize = 12.5.sp),
                color = c.text,
            )
        }
    }
}

// ── loupe ─────────────────────────────────────────────────────────────────

@Composable
private fun LoupeOverlay(
    state: AnnotateState,
    scene: CompiledScene?,
    photo: ImageBitmap?,
    toneHint: Int,
    magnifier: Boolean,
    viewport: IntSize,
) {
    val c = loomColors
    val live = state.live
    if (!magnifier || viewport == IntSize.Zero) return
    // loupe works for vertex drags, dot drags AND brush/draw strokes
    val pos = when (live) {
        is Live.VertexLive -> live.pos
        is Live.DotLive -> live.pos
        is Live.BrushLive -> live.pts.lastOrNull() ?: return
        is Live.DrawLive -> live.pts.lastOrNull() ?: return
        else -> return
    }
    val cam = state.cam
    // The loupe Box is sized in dp but positions/clamps are px math —
    // convert ONCE. (Using the raw constant mixed units: on a 3x-density
    // screen the 336px circle was clamped as if it were 112px, pushing it
    // off the right/top edge — the "loupe outside the display" bug.)
    val loupePx = with(LocalDensity.current) { LOUPE_PX.dp.toPx() }
    val half = loupePx / 2f
    // Parked in a BOTTOM corner, far from the finger (the top corners hold
    // the status pill / delete chip). Flips to the bottom-right when the
    // drag happens in the bottom-left region so it never covers the work.
    val dragSx = pos.x * cam.scale + cam.tx
    val dragSy = pos.y * cam.scale + cam.ty
    val nearBottomLeft = dragSy > viewport.height - loupePx - 60f && dragSx < loupePx + 60f
    val cx = if (nearBottomLeft) viewport.width - half - 16f else half + 16f
    val cy = viewport.height - 16f - half
    // Magnification FOLLOWS the camera zoom (owner req): the loupe is the
    // current view magnified further, not a fixed lens.
    val loupeMag = (cam.scale * 3.2f).coerceIn(2.5f, 16f)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    clamp(cx - half, 0f, max(0f, viewport.width - loupePx)).toInt(),
                    clamp(cy - half, 0f, max(0f, viewport.height - loupePx)).toInt(),
                )
            }
            .size(LOUPE_PX.dp)
            .clip(CircleShape)
            .background(c.canvas)
            .border(1.5.dp, c.primary.copy(alpha = 0.85f), CircleShape),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // ONE mapping for everything: screen = center + (world - pos) * mag.
            // Art, mesh outline, vertices and dots all use it, so markers sit
            // exactly ON the magnified image (the old double-render was off).
            val k = loupeMag
            val toL: (Pt) -> Offset = { p ->
                Offset(size.width / 2f + (p.x - pos.x) * k, size.height / 2f + (p.y - pos.y) * k)
            }
            translate(size.width / 2f, size.height / 2f) {
                scale(k, k, pivot = Offset.Zero) {
                    translate(-pos.x, -pos.y) {
                        if (photo != null) drawPhoto(photo, toneHint) else scene?.drawCompose(this)
                        state.liveMesh()?.let { m ->
                            if (m.size >= 3) {
                                drawPath(polygonPath(m), color = c.meshSoft)
                                drawPath(
                                    polygonPath(m), color = c.mesh,
                                    style = Stroke(width = 1.2f / k),
                                )
                            }
                        }
                    }
                }
            }
            // markers via the SAME mapping (screen px)
            state.liveMesh()?.let { m ->
                for (p in m) {
                    val o = toL(p)
                    drawCircle(color = Color(0xFFFFFDF6), radius = 2.4f, center = o)
                    drawCircle(color = c.mesh, radius = 2.4f, center = o, style = Stroke(width = 1f))
                }
            }
            for (d in state.liveDots()) {
                val o = toL(Pt(d.x, d.y))
                val col = if (d.isPos) c.pos else c.neg
                drawCircle(color = col, radius = 3.4f, center = o)
                drawCircle(color = Color(0xFFFFFDF6), radius = 3.4f, center = o, style = Stroke(width = 0.9f))
            }
            // crosshair + center dot + selection ring
            drawLine(c.primary.copy(alpha = 0.9f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 1f)
            drawLine(c.primary.copy(alpha = 0.9f), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 1f)
            drawCircle(color = c.primary, radius = 1.4f, center = Offset(size.width / 2f, size.height / 2f))
            drawCircle(
                color = c.primary,
                radius = if (live is Live.VertexLive) 5f else 4f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = 1.1f),
            )
        }
    }
}

// ── delete chip ───────────────────────────────────────────────────────────

@Composable
private fun DeleteChip(state: AnnotateState, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val c = loomColors
    val sel = state.selection ?: return
    // Vertices are edited with the EDIT tool; a selected dot can be
    // removed from ANY tool (otherwise DOT mode has no way to delete).
    if (state.tool != Tool.EDIT && sel !is Selection.DotSel) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.surface1.copy(alpha = 0.94f))
            .border(1.dp, c.hairline, RoundedCornerShape(999.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDelete() }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(IconTrash, contentDescription = null, tint = c.text, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (sel is Selection.Vertex) "Delete vertex" else "Delete dot",
                style = loomType.smallStrong,
                color = c.text,
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { state.selection = null },
            contentAlignment = Alignment.Center,
        ) {
            Icon(IconClose, contentDescription = "Deselect", tint = c.textMuted, modifier = Modifier.size(13.dp))
        }
    }
}

// ── gesture engine ────────────────────────────────────────────────────────

/**
 * Gesture engine — the Compose port of `canvas-stage.tsx`'s pointer flow.
 * Kept as a plain object with mutable fields (the prototype's ref style):
 * the pointer loop is a suspend coroutine, not a recomposition, so state
 * that must survive between events lives here.
 */
private class Gestures(
    private val state: AnnotateState,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    var viewport: IntSize = IntSize.Zero
    /** densityPx set once from the composition; hit radii in real pixels. */
    var densityPx: Float = 3f
    private var fitted = false
    private var userTouched = false
    private val hitPx get() = HIT_DP * densityPx
    private val slopPx get() = SLOP_DP * densityPx
    /** Vertex/dot drag threshold — generous slop made slight nudges
     *  "not a move" and snap the handle back on release. */
    private val dragSlopPx get() = 6f * densityPx
    private val edgeSnapPx get() = EDGE_SNAP_DP * densityPx

    private enum class Kind { NONE, PAN, PINCH, VERTEX, DOT, BRUSH, DRAW }
    private var kind = Kind.NONE
    private var vertexIndex = -1
    private var dotId: String? = null
    private var moved = false
    private var pressStage = Offset.Zero
    private var pressWorld = Pt(0f, 0f)
    private var startTx = 0f
    private var startTy = 0f
    private var tapIntent = TapIntent.NONE
    private var pinchStartDist = 1f
    private var pinchStartScale = 1f
    private var pinchWorldMid = Pt(0f, 0f)
    private var brushId: String? = null
    private val brushPts = ArrayList<Pt>()
    private val drawPts = ArrayList<Pt>()
    private var lastPreviewMs = 0L
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    /** The press position in WORLD coords, frozen at gesture start — the
     *  moved-check must measure CUMULATIVE distance (measuring per-event
     *  deltas made slow drags never cross the slop → snap-back). */
    private var pressWorld0 = Pt(0f, 0f)

    private enum class TapIntent { NONE, INSERT, DESELECT }

    // ── transforms ───────────────────────────────────────────────────────

    fun fitIfNeeded(size: IntSize, density: Float) {
        if (size == IntSize.Zero) return
        if (!fitted) {
            fit(size)
            fitted = true
            return
        }
        if (!userTouched) fit(size) else applyCam(state.cam)
        @Suppress("UNUSED_EXPRESSION") density
    }

    fun fit(size: IntSize) {
        val s = max(
            0.05f,
            min((size.width - FIT_PADDING) / WORLD_W, (size.height - FIT_PADDING) / WORLD_H),
        )
        state.setCamera(
            Cam(s, (size.width - WORLD_W * s) / 2f, (size.height - WORLD_H * s) / 2f),
        )
    }

    fun fitNow() {
        if (viewport != IntSize.Zero) fit(viewport)
    }

    fun zoomBy(factor: Float) {
        zoomAt(viewport.width / 2f, viewport.height / 2f, state.cam.scale * factor)
    }

    private fun zoomAt(cx: Float, cy: Float, target: Float) {
        val cam = state.cam
        val s = clamp(target, MIN_SCALE, MAX_SCALE)
        val wx = (cx - cam.tx) / cam.scale
        val wy = (cy - cam.ty) / cam.scale
        userTouched = true
        applyCam(Cam(s, cx - wx * s, cy - wy * s))
    }

    private fun applyCam(cam: Cam) {
        val size = viewport
        if (size == IntSize.Zero) {
            state.setCamera(cam)
            return
        }
        val ww = WORLD_W * cam.scale
        val wh = WORLD_H * cam.scale
        // Free pan on BOTH axes: when the world is smaller than the
        // viewport it is still draggable (≥ MIN_VISIBLE px stays visible).
        // The old "centered when smaller" branch locked vertical panning
        // at fit zoom (portrait phones: wide-but-short world).
        val minVisW = min(ww, MIN_VISIBLE)
        val minVisH = min(wh, MIN_VISIBLE)
        val tx = clamp(cam.tx, minVisW - ww, size.width - minVisW)
        val ty = clamp(cam.ty, minVisH - wh, size.height - minVisH)
        state.setCamera(Cam(cam.scale, tx, ty))
    }

    private fun toWorld(o: Offset): Pt {
        val cam = state.cam
        return Pt(clamp((o.x - cam.tx) / cam.scale, 0f, WORLD_W), clamp((o.y - cam.ty) / cam.scale, 0f, WORLD_H))
    }

    /** Unclamped — the image frame is [WORLD_W]×[WORLD_H]; taps OUTSIDE it
     *  must never become dots (they deselect instead). */
    private fun toWorldRaw(o: Offset): Pt {
        val cam = state.cam
        return Pt((o.x - cam.tx) / cam.scale, (o.y - cam.ty) / cam.scale)
    }

    private fun inWorld(w: Pt): Boolean =
        w.x >= 0f && w.x <= WORLD_W && w.y >= 0f && w.y <= WORLD_H

    private fun worldToScreen(p: Pt): Offset {
        val cam = state.cam
        return Offset(p.x * cam.scale + cam.tx, p.y * cam.scale + cam.ty)
    }

    // ── hit tests ────────────────────────────────────────────────────────

    private fun hitDot(w: Pt): Dot? {
        val r = hitPx / state.cam.scale
        var best: Dot? = null
        var bd = Float.MAX_VALUE
        for (d in state.dots) {
            val dist = hypot(d.x - w.x, d.y - w.y)
            if (dist < bd) {
                bd = dist
                best = d
            }
        }
        return if (best != null && bd <= r) best else null
    }

    private fun hitStroke(w: Pt): LoomStroke? {
        for (i in state.strokes.indices.reversed()) {
            for (p in state.strokes[i].pts) {
                if (hypot(p.x - w.x, p.y - w.y) <= ERASER_HIT_WORLD) return state.strokes[i]
            }
        }
        return null
    }

    // ── commits ──────────────────────────────────────────────────────────

    private fun addDot(kind: DotKind, w: Pt): Dot? {
        val dot = Dot(Ids.uid(if (kind == DotKind.POS) "dot" else "dotn"), kind, w.x, w.y)
        state.commit { prev -> prev.copy(dots = prev.dots + dot) }
        // the sculpt/segmentation that follows this dot is FUSED into the
        // same undo unit — one Undo removes the dot AND its mesh
        state.armFusion()
        onDotPlaced?.invoke()
        return dot
    }

    /** Starts a dot MOVE gesture for the dot nearest [w] (must be a hit). */
    private fun beginDotMove(w: Pt) {
        val dot = hitDot(w) ?: return
        kind = Kind.DOT
        dotId = dot.id
        state.selection = Selection.DotSel(dot.id)
    }

    fun insertAt(w: Pt) {
        state.commit { prev -> prev.mesh?.let { prev.copy(mesh = MeshMath.insertVertex(it, w)) } ?: prev }
    }

    fun deleteSelection() {
        when (val sel = state.selection) {
            is Selection.Vertex -> deleteVertex(sel.index)
            is Selection.DotSel -> {
                state.commit { prev -> prev.copy(dots = prev.dots.filter { it.id != sel.id }) }
                state.selection = null
            }
            null -> Unit
        }
    }

    private fun deleteVertex(index: Int) {
        val mesh = state.mesh
        if (mesh == null || mesh.size <= 3) {
            onWarn?.invoke("Mesh needs at least 3 points")
            return
        }
        state.commit { prev -> prev.mesh?.let { prev.copy(mesh = it.filterIndexed { i, _ -> i != index }) } ?: prev }
        state.selection = null
    }

    var onWarn: ((String) -> Unit)? = null
    var onDotPlaced: (() -> Unit)? = null
    private var lpJob: kotlinx.coroutines.Job? = null

    // deferred dot placement (tap on release / long-press select)
    private var pendingDotKind: DotKind? = null
    private var pendingDotPos = Pt(0f, 0f)

    private fun scheduleDotTap() {
        lpJob?.cancel()
        lpJob = scope.launch {
            delay(500)
            pendingDotKind ?: return@launch
            // long-press = grab the nearest dot within a GENEROUS radius
            // (the press itself already checked the normal hit radius —
            // the long-press is the "I meant that dot just outside it")
            val hit = nearestDot(pendingDotPos, hitPx * 2.5f)
            if (hit != null) {
                pendingDotKind = null
                kind = Kind.DOT
                dotId = hit.id
                state.selection = Selection.DotSel(hit.id)
                onDotPlaced?.invoke()
            }
            // nothing near → keep the pending add armed: a later move pans,
            // a clean release still places the dot
        }
    }

    private fun nearestDot(w: Pt, radiusPx: Float): Dot? {
        val r = radiusPx / state.cam.scale
        var best: Dot? = null
        var bd = Float.MAX_VALUE
        for (d in state.dots) {
            val dist = hypot(d.x - w.x, d.y - w.y)
            if (dist < bd) { bd = dist; best = d }
        }
        return if (best != null && bd <= r) best else null
    }

    // ── pointer loop ─────────────────────────────────────────────────────

    suspend fun handlePointers(scope: androidx.compose.ui.input.pointer.PointerInputScope) {
        scope.awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                val downPos = down.position
                val downType = down.type
                down.consume()
                // a tool switch restarts pointerInput and cancels this
                // coroutine mid-gesture — the finally guarantees the
                // auto-suggest engine is never left frozen on gestureActive
                try {
                    beginGesture(downPos, downType)
                    runGesture()
                } finally {
                    lpJob?.cancel()
                    pendingDotKind = null
                    state.gestureActive = false
                    state.live = null
                    kind = Kind.NONE
                }
            }
        }
    }

    private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.runGesture() {
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        if (kind != Kind.PINCH) beginPinch(pressed.map { it.position })
                        else updatePinch(pressed.map { it.position })
                        pressed.forEach { it.consume() }
                        continue
                    }
                    if (kind == Kind.PINCH && pressed.size == 1) {
                        // dropped to one finger: restart as pan from here
                        helpRestartPan(pressed[0].position)
                        pressed.forEach { it.consume() }
                        continue
                    }
                    moveGesture(pressed[0].position, pressed[0].positionChange())
                    pressed.forEach { it.consume() }
                }
                endGesture()
    }

    private fun beginGesture(pos: Offset, type: PointerType) {
        state.gestureActive = true
        state.live = null
        val w = toWorld(pos)
        pressStage = pos
        pressWorld = w
        pressWorld0 = w
        moved = false
        tapIntent = TapIntent.NONE
        when (state.tool) {
            Tool.PAN -> {
                kind = Kind.PAN
                startTx = state.cam.tx
                startTy = state.cam.ty
            }
            Tool.DOT_POS, Tool.DOT_NEG -> {
                if (!inWorld(toWorldRaw(pos))) {
                    // outside the image border: no dot, ever — a clean tap
                    // just clears the selection
                    kind = Kind.PAN
                    startTx = state.cam.tx
                    startTy = state.cam.ty
                    tapIntent = TapIntent.DESELECT
                    return
                }
                val dotKind = if (state.tool == Tool.DOT_POS) DotKind.POS else DotKind.NEG
                val hit = hitDot(w)
                if (hit != null) {
                    // Press ON an existing dot = move it.
                    beginDotMove(w)
                } else {
                    // Defer the add to RELEASE: a two-finger pinch or a
                    // one-finger pan starting here must NOT place a dot.
                    // Long-press (500ms) selects the nearest dot instead.
                    kind = Kind.NONE
                    pendingDotKind = dotKind
                    pendingDotPos = w
                    scheduleDotTap()
                }
            }
            Tool.BRUSH -> {
                kind = Kind.BRUSH
                brushId = Ids.uid("str")
                brushPts.clear()
                brushPts.add(w)
                state.live = Live.BrushLive(brushPts.toList())
            }
            Tool.DRAW -> {
                if (!state.drawArmed) {
                    // the user must press the canvas button first — an
                    // accidental touch never starts a mesh
                    kind = Kind.PAN
                    startTx = state.cam.tx
                    startTy = state.cam.ty
                    tapIntent = TapIntent.DESELECT
                    return
                }
                kind = Kind.DRAW
                drawPts.clear()
                drawPts.add(w)
                lastPreviewMs = 0L
                state.live = Live.DrawLive(drawPts.toList())
            }
            Tool.ERASER -> {
                val hit = hitStroke(w)
                if (hit != null) {
                    state.commit { prev -> prev.copy(strokes = prev.strokes.filter { it.id != hit.id }) }
                    onStrokeRemoved?.invoke()
                }
            }
            Tool.EDIT -> {
                val mesh = state.mesh
                val nv = if (mesh != null && mesh.size >= 3) MeshMath.nearestVertex(mesh, w) else null
                val vDist = nv?.dist ?: Float.MAX_VALUE
                val vHit = vDist <= hitPx / state.cam.scale
                val eDist = mesh?.let { MeshMath.edgeDistance(it, w) } ?: Float.MAX_VALUE
                if (nv != null && nv.index >= 0 && vHit && vDist <= eDist + VERTEX_EPS) {
                    kind = Kind.VERTEX
                    vertexIndex = nv.index
                    state.selection = Selection.Vertex(nv.index)
                    return
                }
                // dots are NOT editable in the mesh view (owner req) —
                // they live only under the dot tools
                val nearEdge = mesh != null && mesh.size >= 3 &&
                    eDist <= edgeSnapPx / state.cam.scale
                kind = Kind.PAN
                startTx = state.cam.tx
                startTy = state.cam.ty
                tapIntent = if (nearEdge) TapIntent.INSERT else TapIntent.DESELECT
            }
        }
        @Suppress("UNUSED_EXPRESSION") type
    }

    var onStrokeRemoved: (() -> Unit)? = null

    private fun beginPinch(points: List<Offset>) {
        // commit whatever single-finger gesture was in flight
        finishSingleGesture(commit = true)
        pendingDotKind = null
        lpJob?.cancel()
        val a = points[0]
        val b = points[1]
        val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        val dist = hypot(a.x - b.x, a.y - b.y)
        if (dist < 24f * densityPx) {
            // stylus/palm landing in one spot — treat as a pan, not a zoom
            helpRestartPan(mid)
            return
        }
        pinchStartDist = dist
        pinchStartScale = state.cam.scale
        pinchWorldMid = toWorld(mid)
        kind = Kind.PINCH
        userTouched = true
    }

    private fun updatePinch(points: List<Offset>) {
        val a = points[0]
        val b = points[1]
        val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        val dist = hypot(a.x - b.x, a.y - b.y).coerceAtLeast(1f)
        val s = clamp(pinchStartScale * dist / pinchStartDist, MIN_SCALE, MAX_SCALE)
        applyCam(Cam(s, mid.x - pinchWorldMid.x * s, mid.y - pinchWorldMid.y * s))
    }

    private fun helpRestartPan(pos: Offset) {
        kind = Kind.PAN
        startTx = state.cam.tx
        startTy = state.cam.ty
        pressStage = pos
        moved = true
    }

    private fun moveGesture(pos: Offset, change: Offset) {
        when (kind) {
            Kind.PAN -> {
                if (!moved && hypot(pos.x - pressStage.x, pos.y - pressStage.y) > slopPx) moved = true
                if (moved) {
                    userTouched = true
                    applyCam(
                        Cam(state.cam.scale, startTx + (pos.x - pressStage.x), startTy + (pos.y - pressStage.y)),
                    )
                }
            }
            Kind.VERTEX -> {
                val w = toWorld(pos)
                if (!moved && hypot(w.x - pressWorld0.x, w.y - pressWorld0.y) * state.cam.scale > dragSlopPx) {
                    moved = true
                }
                pressWorld = w
                state.live = Live.VertexLive(vertexIndex, w)
            }
            Kind.DOT -> {
                val w = toWorld(pos)
                if (!moved && hypot(w.x - pressWorld0.x, w.y - pressWorld0.y) * state.cam.scale > dragSlopPx) moved = true
                pressWorld = w
                state.live = Live.DotLive(dotId ?: "", w)
            }
            Kind.BRUSH -> {
                val w = toWorld(pos)
                val last = brushPts.lastOrNull()
                if (last == null || hypot(w.x - last.x, w.y - last.y) >= 1.5f) {
                    brushPts.add(w)
                    state.live = Live.BrushLive(brushPts.toList())
                }
            }
            Kind.DRAW -> {
                val w = toWorld(pos)
                val last = drawPts.lastOrNull()
                if (last == null || hypot(w.x - last.x, w.y - last.y) >= 1.5f) {
                    drawPts.add(w)
                    // live adaptive preview: refit the path every ~70ms so
                    // the vertices ride along with the finger
                    val now = System.currentTimeMillis()
                    if (now - lastPreviewMs >= 70) {
                        lastPreviewMs = now
                        state.live = Live.DrawLive(drawPts.toList(), drawPreview(drawPts))
                    } else {
                        state.live = Live.DrawLive(drawPts.toList(), (state.live as? Live.DrawLive)?.preview)
                    }
                }
            }
            Kind.NONE -> {
                if (pendingDotKind != null) {
                    if (!moved && hypot(pos.x - pressStage.x, pos.y - pressStage.y) > slopPx) {
                        moved = true
                        pendingDotKind = null
                        lpJob?.cancel()
                        // one-finger pan with a dot tool
                        kind = Kind.PAN
                        startTx = state.cam.tx
                        startTy = state.cam.ty
                        pressStage = pos
                    }
                }
            }
            Kind.PINCH -> Unit
        }
        @Suppress("UNUSED_EXPRESSION") change
    }

    /** Applies the pending single-finger commit (used when a pinch starts). */
    private fun finishSingleGesture(commit: Boolean) {
        if (!commit) return
        when (kind) {
            Kind.VERTEX -> if (moved) commitVertex(vertexIndex, (state.live as? Live.VertexLive)?.pos)
            Kind.DOT -> if (moved) commitDot(dotId, (state.live as? Live.DotLive)?.pos)
            Kind.BRUSH -> commitBrush()
            Kind.DRAW -> finishDraw()
            else -> Unit
        }
        state.live = null
        kind = Kind.NONE
    }

    private fun endGesture() {
        lpJob?.cancel()
        state.gestureActive = false
        when (kind) {
            Kind.PAN -> {
                if (!moved) {
                    when (tapIntent) {
                        TapIntent.INSERT -> {
                            // two-tap midpoint confirm: arm first, insert second
                            val meshNow = state.mesh
                            val w = toWorld(pressStage)
                            var midIdx = -1
                            var bestD = Float.MAX_VALUE
                            if (meshNow != null && meshNow.size >= 3) {
                                for (i in meshNow.indices) {
                                    val p = meshNow[i]
                                    val q = meshNow[(i + 1) % meshNow.size]
                                    val d = MeshMath.distToSegment(w, p, q)
                                    if (d < bestD) { bestD = d; midIdx = i }
                                }
                            }
                            if (bestD <= edgeSnapPx / state.cam.scale) {
                                if (state.pendingMid == midIdx) {
                                    state.pendingMid = null
                                    insertAt(toWorld(pressStage))
                                    onDotPlaced?.invoke()
                                } else {
                                    state.pendingMid = midIdx
                                    state.selection = null
                                    onDotPlaced?.invoke()
                                }
                            } else {
                                state.pendingMid = null
                                state.selection = null
                            }
                        }
                        TapIntent.DESELECT -> {
                            state.selection = null
                            state.pendingMid = null
                        }
                        TapIntent.NONE -> {
                            if (state.tool == Tool.PAN) handleDoubleTap()
                        }
                    }
                }
            }
            Kind.NONE -> {
                // deferred dot add fires on release of a clean tap
                val k = pendingDotKind
                if (k != null && !moved) {
                    pendingDotKind = null
                    addDot(k, pendingDotPos)
                } else {
                    pendingDotKind = null
                }
            }
            Kind.VERTEX -> {
                if (moved) commitVertex(vertexIndex, (state.live as? Live.VertexLive)?.pos)
            }
            Kind.DOT -> {
                if (moved) commitDot(dotId, (state.live as? Live.DotLive)?.pos)
            }
            Kind.BRUSH -> commitBrush()
            Kind.DRAW -> finishDraw()
            else -> Unit
        }
        state.live = null
        kind = Kind.NONE
    }

    private fun handleDoubleTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 320 && hypot(pressStage.x - lastTapX, pressStage.y - lastTapY) < 28f) {
            lastTapTime = 0L
            val target = if (state.cam.scale > 1.8f) 1f else state.cam.scale * 2f
            zoomAt(pressStage.x, pressStage.y, target)
        } else {
            lastTapTime = now
            lastTapX = pressStage.x
            lastTapY = pressStage.y
        }
    }

    private fun commitVertex(index: Int, pos: Pt?) {
        if (index < 0 || pos == null) return
        state.commit { prev ->
            prev.mesh?.let { prev.copy(mesh = it.mapIndexed { i, p -> if (i == index) pos else p }) } ?: prev
        }
    }

    private fun commitDot(id: String?, pos: Pt?) {
        if (id == null || pos == null) return
        state.commit { prev ->
            prev.copy(dots = prev.dots.map { if (it.id == id) it.copy(x = pos.x, y = pos.y) else it })
        }
    }

    private fun commitBrush() {
        val thinned = MeshMath.thinStroke(brushPts.toList())
        if (thinned.size >= 2) {
            val id = brushId ?: Ids.uid("str")
            state.commit { prev -> prev.copy(strokes = prev.strokes + LoomStroke(id, thinned)) }
        }
        brushPts.clear()
        brushId = null
    }

    /** Adaptive preview fit of the in-flight draw path (closed ring). */
    private fun drawPreview(raw: List<Pt>): List<Pt>? {
        if (raw.size < 3) return null
        val closed = if (hypot(raw.first().x - raw.last().x, raw.first().y - raw.last().y) > 4f) {
            raw + raw.first()
        } else {
            raw
        }
        return try {
            if (state.present.densityAuto) {
                MeshMath.autoFitRing(closed, LoomRules.DENSITY_MIN, LoomRules.DENSITY_MAX).first
            } else {
                MeshMath.fitRing(closed, LoomRules.clampDensity(state.present.density))
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * DRAW-mesh release: the freehand path closes into a ring and the
     * adaptive fitter places the vertices — dense where the finger curved,
     * sparse on straight runs. Auto density → the optimal minimal fit;
     * manual → refit at the user's vertex count. The drawn shape is kept
     * as [AnnotateState.denseRing] so the density stepper can re-fit it.
     */
    private fun finishDraw() {
        val raw = drawPts.toList()
        drawPts.clear()
        if (raw.size < 3) return
        // close the loop the finger implied
        val closed = if (hypot(raw.first().x - raw.last().x, raw.first().y - raw.last().y) > 4f) {
            raw + raw.first()
        } else {
            raw
        }
        state.commit { prev ->
            if (prev.densityAuto) {
                val (ring, n) = MeshMath.autoFitRing(closed, LoomRules.DENSITY_MIN, LoomRules.DENSITY_MAX)
                prev.copy(mesh = ring, density = n)
            } else {
                prev.copy(mesh = MeshMath.fitRing(closed, LoomRules.clampDensity(prev.density)))
            }
        }
        state.denseRing = closed
        state.meshGen++
        // one drawn mesh per arming — the button must be pressed again
        state.drawArmed = false
    }
}
