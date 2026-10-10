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
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.text.font.FontWeight
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
import com.testplaybyte.loom.domain.model.Pt
import com.testplaybyte.loom.domain.model.Stroke as LoomStroke
import com.testplaybyte.loom.ui.icons.IconChevronDown
import com.testplaybyte.loom.ui.icons.IconClose
import com.testplaybyte.loom.ui.icons.IconTrash
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType
import kotlin.math.abs
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
 *   open, pinch zoom 50–500% anchored at the gesture midpoint, double-tap
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
private const val MAX_SCALE = 5f
private const val HIT_PX = 22f          // dots + vertices
private const val SLOP_PX = 7f          // press → drag
private const val EDGE_SNAP_PX = 14f    // edge tap → insert vertex
private const val VERTEX_EPS = 1.5f     // world units
private const val ERASER_HIT_WORLD = 24f
private const val LOUPE_PX = 112f
private const val LOUPE_MAG = 3.2f
private const val LOUPE_GAP = 72f
private const val LOUPE_HDL_PX = 2.5f
private const val LOUPE_DOT_PX = 4.4f
private const val NUDGE_STEP = 8f
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
    modifier: Modifier = Modifier,
) {
    val c = loomColors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val gestures = remember { Gestures(state, scope) }

    // Fit-contain once per image; re-clamp when the viewport changes.
    LaunchedEffect(size, state.present.hashCode()) {
        if (size != IntSize.Zero) gestures.fitIfNeeded(size, density.density)
    }

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
                .pointerInput(state.tool) {
                    gestures.handlePointers(this)
                },
        ) {
            val cam = state.cam
            val liveMesh = state.liveMesh()
            val liveDots = state.liveDots()

            // ── world transform ─────────────────────────────────────────
            translate(cam.tx, cam.ty) {
                scale(cam.scale, cam.scale, pivot = Offset.Zero) {
                    // art (photo letterboxed, or demo vector art)
                    if (photo != null) {
                        drawPhoto(photo, toneHint)
                    } else if (scene != null) {
                        scene.drawCompose(this)
                    }
                    // world grid + frame, fixed to the world
                    drawGrid(c.grid, cam.scale)
                    drawRect(
                        color = c.hairlineStrong,
                        topLeft = Offset(0.5f, 0.5f),
                        size = Size(WORLD_W - 1f, WORLD_H - 1f),
                        style = Stroke(width = 1f / cam.scale),
                    )

                    // exclusion strokes (below the mesh)
                    for (s in state.strokes) drawStrokePath(s, c.neg)
                    (state.live as? Live.BrushLive)?.let { brush ->
                        drawStrokePolyline(brush.pts, c.neg)
                    }

                    // mesh: soft fill + amber line + marching ants + handles
                    if (liveMesh != null && liveMesh.size >= 3) {
                        val quads = MeshMath.ringQuads(liveMesh)
                        if (quads != null) {
                            val path = Path().apply {
                                moveTo(quads.start.x, quads.start.y)
                                for (seg in quads.segments) {
                                    quadraticBezierTo(seg.control.x, seg.control.y, seg.end.x, seg.end.y)
                                }
                                close()
                            }
                            drawPath(path, color = c.meshSoft)
                            drawPath(
                                path, color = c.mesh,
                                style = Stroke(width = 2f / cam.scale, join = StrokeJoin.Round),
                            )
                            // marching ants: dash period and offset in world units
                            val dash = 6f / cam.scale
                            val gap = 5f / cam.scale
                            drawPath(
                                path, color = c.canvas,
                                style = Stroke(
                                    width = 1.3f / cam.scale,
                                    join = StrokeJoin.Round,
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(dash, gap),
                                        phase = -antsPhase * (11f / cam.scale),
                                    ),
                                ),
                            )
                            if (state.tool == Tool.EDIT) {
                                for (i in liveMesh.indices) {
                                    val p = liveMesh[i]
                                    val q = liveMesh[(i + 1) % liveMesh.size]
                                    drawCircle(
                                        color = c.canvas,
                                        radius = 2.6f,
                                        center = Offset((p.x + q.x) / 2f, (p.y + q.y) / 2f),
                                    )
                                    drawCircle(
                                        color = c.mesh,
                                        radius = 2.6f,
                                        center = Offset((p.x + q.x) / 2f, (p.y + q.y) / 2f),
                                        style = Stroke(width = 1.4f),
                                    )
                                }
                                for (i in liveMesh.indices) {
                                    val p = liveMesh[i]
                                    val selected = (state.selection as? Selection.Vertex)?.index == i
                                    drawCircle(
                                        color = Color(0xFFFFFDF6),
                                        radius = 5f,
                                        center = Offset(p.x, p.y),
                                    )
                                    drawCircle(
                                        color = if (selected) c.primary else c.mesh,
                                        radius = 5f,
                                        center = Offset(p.x, p.y),
                                        style = Stroke(width = if (selected) 3f else 2.4f),
                                    )
                                }
                            }
                        }
                    }

                    // prompt dots
                    for ((i, d) in liveDots.withIndex()) {
                        val selected = (state.selection as? Selection.DotSel)?.id == d.id
                        // staggered pop: later dots lag slightly
                        val stagger = (i * 0.06f)
                        val s = clamp((dotPop.value - stagger) / (1f - stagger).coerceAtLeast(0.01f), 0.4f, 1f)
                        scale(s, s, pivot = Offset(d.x, d.y)) {
                            drawDot(d, dotSizeWorld.toFloat(), selected, c, cam.scale)
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

        // ── nudge arrows (edit + vertex selected) ────────────────────────
        NudgeArrows(state = state, viewport = size)

        // ── delete chip (edit + selection) ───────────────────────────────
        DeleteChip(
            state = state,
            onDelete = { gestures.deleteSelection() },
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
        )

        // ── empty hint ──────────────────────────────────────────────────
        val hasContent = state.dots.isNotEmpty() || state.mesh != null || state.strokes.isNotEmpty()
        if (!hasContent) {
            Column3Hint(modifier = Modifier.align(Alignment.Center))
        }
    }
}

// ── drawing helpers ───────────────────────────────────────────────────────

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

/** Drafting grid, world-fixed (24-unit cells) — drawn with the art. */
private fun DrawScope.drawGrid(color: Color, camScale: Float) {
    val stroke = 1f / camScale
    var x = -GRID_CELL * 8
    while (x <= WORLD_W + GRID_CELL * 8) {
        drawLine(color, Offset(x, -GRID_CELL * 8), Offset(x, WORLD_H + GRID_CELL * 8), strokeWidth = stroke)
        x += GRID_CELL
    }
    var y = -GRID_CELL * 8
    while (y <= WORLD_H + GRID_CELL * 8) {
        drawLine(color, Offset(-GRID_CELL * 8, y), Offset(WORLD_W + GRID_CELL * 8, y), strokeWidth = stroke)
        y += GRID_CELL
    }
}

private fun DrawScope.drawStrokePath(s: LoomStroke, color: Color) =
    drawStrokePolyline(s.pts, color)

private fun DrawScope.drawStrokePolyline(pts: List<Pt>, color: Color) {
    if (pts.isEmpty()) return
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    drawPath(
        path = path,
        color = color.copy(alpha = 0.55f),
        style = Stroke(width = 26f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * Dot glyphs (canvas.css): positive = mint disc + hairline canvas ring +
 * white core; negative = coral dashed ring + minus. The rings are world
 * units, matching the SVG source.
 */
private fun DrawScope.drawDot(d: Dot, dotSize: Float, selected: Boolean, c: com.testplaybyte.loom.ui.theme.LoomColors, camScale: Float) {
    val center = Offset(d.x, d.y)
    if (selected) {
        drawCircle(
            color = c.primary,
            radius = dotSize + 6f,
            center = center,
            style = Stroke(
                width = 2f / camScale,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)),
            ),
        )
    }
    if (d.kind == DotKind.POS) {
        drawCircle(color = c.pos, radius = dotSize, center = center)
        drawCircle(
            color = c.canvas, radius = dotSize, center = center,
            style = Stroke(width = 2f),
        )
        drawCircle(color = Color(0xFFFFFDF6), radius = dotSize * 0.42f, center = center)
    } else {
        drawCircle(
            color = c.neg, radius = dotSize, center = center,
            style = Stroke(
                width = 2.4f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.5f, 3.4f)),
            ),
        )
        drawLine(
            color = c.neg,
            start = Offset(d.x - dotSize * 0.55f, d.y),
            end = Offset(d.x + dotSize * 0.55f, d.y),
            strokeWidth = 2.6f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun Column3Hint(modifier: Modifier = Modifier) {
    val c = loomColors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface1.copy(alpha = 0.86f))
            .border(1.dp, c.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, c.hairlineStrong, CircleShape),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Tap + dots on the object — the mesh sculpts itself.\n− dots push it away.",
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
    val drag: Live? = when (live) {
        is Live.VertexLive -> live
        is Live.DotLive -> live
        else -> null
    }
    val pos = when (drag) {
        is Live.VertexLive -> drag.pos
        is Live.DotLive -> drag.pos
        else -> null
    } ?: return
    val cam = state.cam
    val half = LOUPE_PX / 2f
    val sx = pos.x * cam.scale + cam.tx
    val sy = pos.y * cam.scale + cam.ty
    val cx = clamp(sx, half, viewport.width - half)
    val cy = clamp(sy - LOUPE_GAP - half, half, max(half, viewport.height - LOUPE_PX - 30f))
    val lvw = LOUPE_PX / LOUPE_MAG
    val vx = pos.x - lvw / 2f
    val vy = pos.y - lvw / 2f
    val k = clamp(cam.scale, 0.5f, 1f)
    val hdlR = LOUPE_HDL_PX * k
    val dotR = LOUPE_DOT_PX * k
    val liveMesh = state.liveMesh()
    val dots = state.liveDots()

    Box(
        modifier = Modifier
            .offset { IntOffset((cx - half).toInt(), (cy - half).toInt()) }
            .size(LOUPE_PX.dp)
            .clip(CircleShape)
            .background(c.canvas)
            .border(1.dp, c.hairlineStrong, CircleShape),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // magnified art + geometry, centered on the dragged point
            val scaleK = LOUPE_MAG
            translate(size.width / 2f, size.height / 2f) {
                scale(scaleK, scaleK, pivot = Offset.Zero) {
                    translate(-pos.x, -pos.y) {
                        if (photo != null) drawPhoto(photo, toneHint) else scene?.drawCompose(this)
                        liveMesh?.let { m ->
                            val quads = MeshMath.ringQuads(m)
                            if (quads != null) {
                                val path = Path().apply {
                                    moveTo(quads.start.x, quads.start.y)
                                    for (seg in quads.segments) {
                                        quadraticBezierTo(seg.control.x, seg.control.y, seg.end.x, seg.end.y)
                                    }
                                    close()
                                }
                                drawPath(path, color = c.meshSoft)
                                drawPath(path, color = c.mesh, style = Stroke(width = 1f / scaleK))
                            }
                        }
                    }
                }
            }
            // markers in screen px (de-emphasized, per the prototype)
            liveMesh?.let { m ->
                val toL = { p: Pt -> Offset((p.x - vx) * LOUPE_MAG, (p.y - vy) * LOUPE_MAG) }
                val path = MeshMath.ringQuads(m)?.let { q ->
                    Path().apply {
                        val st = toL(q.start)
                        moveTo(st.x, st.y)
                        for (seg in q.segments) {
                            val ctrl = toL(seg.control)
                            val end = toL(seg.end)
                            quadraticBezierTo(ctrl.x, ctrl.y, end.x, end.y)
                        }
                        close()
                    }
                }
                path?.let { drawPath(it, color = c.mesh, style = Stroke(width = 1f)) }
                for (p in m) {
                    val o = toL(p)
                    drawCircle(color = Color(0xFFFFFDF6), radius = hdlR, center = o)
                    drawCircle(color = c.mesh, radius = hdlR, center = o, style = Stroke(width = 1f))
                }
            }
            for (d in dots) {
                val o = Offset((d.x - vx) * LOUPE_MAG, (d.y - vy) * LOUPE_MAG)
                val col = if (d.kind == DotKind.POS) c.pos else c.neg
                drawCircle(color = col, radius = dotR, center = o)
            }
            // crosshair + center dot + selection ring
            drawLine(c.primary.copy(alpha = 0.9f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 1f)
            drawLine(c.primary.copy(alpha = 0.9f), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 1f)
            drawCircle(color = c.primary, radius = 1.4f, center = Offset(size.width / 2f, size.height / 2f))
            drawCircle(
                color = c.primary,
                radius = (if (drag is Live.VertexLive) hdlR else dotR) + 2.6f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = 1.1f),
            )
        }
    }
    // coordinate readout under the loupe
    Text(
        text = "x ${Math.round(pos.x)} · y ${Math.round(pos.y)}",
        style = loomType.monoSmall.copy(fontWeight = FontWeight.W500),
        color = c.text,
        modifier = Modifier
            .offset { IntOffset(cx.toInt() - 40, min(viewport.height - 22f, cy + half + 6f).toInt()) }
            .background(c.surface1.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ── nudge arrows + delete chip ────────────────────────────────────────────

@Composable
private fun NudgeArrows(
    state: AnnotateState,
    viewport: IntSize,
) {
    val c = loomColors
    val sel = state.selection as? Selection.Vertex ?: return
    if (state.tool != Tool.EDIT) return
    val mesh = state.liveMesh() ?: return
    val p = mesh.getOrNull(sel.index) ?: return
    val cam = state.cam
    val sx = clamp(p.x * cam.scale + cam.tx, 18f, viewport.width - 18f)
    val sy = clamp(p.y * cam.scale + cam.ty, 18f, viewport.height - 18f)
    val off = 32f
    val step = { v: Float -> if (v == 0f) 0f else (v / abs(v)) * NUDGE_STEP }

    @Composable
    fun arrow(dx: Float, dy: Float, rotation: Float, label: String) {
        Box(
            modifier = Modifier
                .offset { IntOffset((sx + dx - 14f).toInt(), (sy + dy - 14f).toInt()) }
                .size(28.dp)
                .clip(CircleShape)
                .background(c.surface2)
                .border(1.dp, c.hairlineStrong, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    state.commit { prev ->
                        prev.mesh?.let { m ->
                            prev.copy(
                                mesh = m.mapIndexed { i, v ->
                                    if (i != sel.index) v else Pt(
                                        clamp(v.x + step(dx), 0f, WORLD_W),
                                        clamp(v.y + step(dy), 0f, WORLD_H),
                                    )
                                },
                            )
                        } ?: prev
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.rotateDeg(rotation)) {
                Icon(IconChevronDown, contentDescription = label, tint = c.text, modifier = Modifier.size(13.dp))
            }
        }
    }

    arrow(0f, -off, 180f, "Nudge vertex up")
    arrow(off, 0f, -90f, "Nudge vertex right")
    arrow(0f, off, 0f, "Nudge vertex down")
    arrow(-off, 0f, 90f, "Nudge vertex left")
}

private fun Modifier.rotateDeg(deg: Float): Modifier = this.rotate(deg)

@Composable
private fun DeleteChip(state: AnnotateState, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val c = loomColors
    val sel = state.selection ?: return
    if (state.tool != Tool.EDIT) return
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
    private var fitted = false
    private var userTouched = false

    private enum class Kind { NONE, PAN, PINCH, VERTEX, DOT, BRUSH }
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
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

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
        val tx = if (ww <= size.width) (size.width - ww) / 2f else clamp(cam.tx, MIN_VISIBLE - ww, size.width - MIN_VISIBLE)
        val ty = if (wh <= size.height) (size.height - wh) / 2f else clamp(cam.ty, MIN_VISIBLE - wh, size.height - MIN_VISIBLE)
        state.setCamera(Cam(cam.scale, tx, ty))
    }

    private fun toWorld(o: Offset): Pt {
        val cam = state.cam
        return Pt(clamp((o.x - cam.tx) / cam.scale, 0f, WORLD_W), clamp((o.y - cam.ty) / cam.scale, 0f, WORLD_H))
    }

    private fun worldToScreen(p: Pt): Offset {
        val cam = state.cam
        return Offset(p.x * cam.scale + cam.tx, p.y * cam.scale + cam.ty)
    }

    // ── hit tests ────────────────────────────────────────────────────────

    private fun hitDot(w: Pt): Dot? {
        val r = HIT_PX / state.cam.scale
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

    private fun addDot(kind: DotKind, w: Pt) {
        val dot = Dot(Ids.uid(if (kind == DotKind.POS) "dot" else "dotn"), kind, w.x, w.y)
        state.commit { prev -> prev.copy(dots = prev.dots + dot) }
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

    // ── pointer loop ─────────────────────────────────────────────────────

    suspend fun handlePointers(scope: androidx.compose.ui.input.pointer.PointerInputScope) {
        scope.awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                val downPos = down.position
                val downType = down.type
                down.consume()
                beginGesture(downPos, downType)
                var pointerCount = 1
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        if (kind != Kind.PINCH) beginPinch(pressed.map { it.position })
                        else updatePinch(pressed.map { it.position })
                        pressed.forEach { it.consume() }
                        pointerCount = pressed.size
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
                    @Suppress("UNUSED_EXPRESSION") pointerCount
                }
                endGesture()
            }
        }
    }

    private fun beginGesture(pos: Offset, type: PointerType) {
        state.gestureActive = true
        state.live = null
        val w = toWorld(pos)
        pressStage = pos
        pressWorld = w
        moved = false
        tapIntent = TapIntent.NONE
        when (state.tool) {
            Tool.PAN -> {
                kind = Kind.PAN
                startTx = state.cam.tx
                startTy = state.cam.ty
            }
            Tool.DOT_POS -> addDot(DotKind.POS, w)
            Tool.DOT_NEG -> addDot(DotKind.NEG, w)
            Tool.BRUSH -> {
                kind = Kind.BRUSH
                brushId = Ids.uid("str")
                brushPts.clear()
                brushPts.add(w)
                state.live = Live.BrushLive(brushPts.toList())
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
                val vHit = vDist <= HIT_PX / state.cam.scale
                val eDist = mesh?.let { MeshMath.edgeDistance(it, w) } ?: Float.MAX_VALUE
                if (nv != null && nv.index >= 0 && vHit && vDist <= eDist + VERTEX_EPS) {
                    kind = Kind.VERTEX
                    vertexIndex = nv.index
                    state.selection = Selection.Vertex(nv.index)
                    scheduleLongPress(nv.index)
                    return
                }
                val dot = hitDot(w)
                if (dot != null) {
                    kind = Kind.DOT
                    dotId = dot.id
                    state.selection = Selection.DotSel(dot.id)
                    return
                }
                val nearEdge = mesh != null && mesh.size >= 3 &&
                    eDist <= EDGE_SNAP_PX / state.cam.scale && eDist < vDist
                kind = Kind.PAN
                startTx = state.cam.tx
                startTy = state.cam.ty
                tapIntent = if (nearEdge) TapIntent.INSERT else TapIntent.DESELECT
            }
        }
        @Suppress("UNUSED_EXPRESSION") type
    }

    private var lpJob: Job? = null

    private fun scheduleLongPress(index: Int) {
        lpJob?.cancel()
        lpJob = scope.launch {
            delay(com.testplaybyte.loom.ui.theme.LoomMotion.VERTEX_LONG_PRESS_MS)
            if (kind == Kind.VERTEX && vertexIndex == index && !moved) {
                kind = Kind.NONE
                state.live = null
                deleteVertex(index)
                onLongPressDelete?.invoke()
            }
        }
    }

    var onStrokeRemoved: (() -> Unit)? = null
    var onLongPressDelete: (() -> Unit)? = null

    private fun beginPinch(points: List<Offset>) {
        // commit whatever single-finger gesture was in flight
        finishSingleGesture(commit = true)
        val a = points[0]
        val b = points[1]
        val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        pinchStartDist = hypot(a.x - b.x, a.y - b.y).coerceAtLeast(1f)
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
                if (!moved && hypot(pos.x - pressStage.x, pos.y - pressStage.y) > SLOP_PX) moved = true
                if (moved) {
                    userTouched = true
                    applyCam(
                        Cam(state.cam.scale, startTx + (pos.x - pressStage.x), startTy + (pos.y - pressStage.y)),
                    )
                }
            }
            Kind.VERTEX -> {
                val w = toWorld(pos)
                if (!moved && hypot(w.x - pressWorld.x, w.y - pressWorld.y) * state.cam.scale > SLOP_PX) {
                    moved = true
                    lpJob?.cancel()
                }
                pressWorld = w
                state.live = Live.VertexLive(vertexIndex, w)
            }
            Kind.DOT -> {
                val w = toWorld(pos)
                if (!moved && hypot(w.x - pressWorld.x, w.y - pressWorld.y) * state.cam.scale > SLOP_PX) moved = true
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
            Kind.PINCH, Kind.NONE -> Unit
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
                        TapIntent.INSERT -> insertAt(toWorld(pressStage))
                        TapIntent.DESELECT -> state.selection = null
                        TapIntent.NONE -> {
                            if (state.tool == Tool.PAN) handleDoubleTap()
                        }
                    }
                }
            }
            Kind.VERTEX -> {
                if (moved) commitVertex(vertexIndex, (state.live as? Live.VertexLive)?.pos)
            }
            Kind.DOT -> {
                if (moved) commitDot(dotId, (state.live as? Live.DotLive)?.pos)
            }
            Kind.BRUSH -> commitBrush()
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
}
