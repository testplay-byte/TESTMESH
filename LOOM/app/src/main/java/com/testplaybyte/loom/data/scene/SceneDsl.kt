package com.testplaybyte.loom.data.scene

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap as ComposeStrokeCap
import androidx.compose.ui.graphics.StrokeJoin as ComposeStrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.core.graphics.PathParser

/**
 * Scene art DSL — the six demo "photos" as pure data, ported 1:1 from the
 * prototype's `components/scene-art.tsx` (flat SVG illustrations, 800×600
 * world). Keeping the art as DATA (not two hand-written renderers) means
 * one geometry definition drives both outputs:
 *
 *  · the annotate canvas + thumbnails (Compose), and
 *  · the real PNG written into the project folder / dataset (android
 *    .graphics), so exported datasets contain the actual images.
 *
 * A [CompiledScene] parses every path string exactly once (PathParser),
 * then caches both the Android and Compose representations.
 */
sealed interface Op {
    /** Rounded rect; [rotate]/[pivotX]/[pivotY] mirror SVG transform="rotate(a cx cy)". */
    data class RectOp(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val rx: Float = 0f, val color: Int, val alpha: Float = 1f,
        val rotate: Float = 0f, val pivotX: Float = 0f, val pivotY: Float = 0f,
    ) : Op

    data class CircleOp(val cx: Float, val cy: Float, val r: Float, val color: Int, val alpha: Float = 1f) : Op

    data class EllipseOp(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val color: Int, val alpha: Float = 1f) : Op

    /**
     * SVG path; [fill] or [stroke] (or both). StrokeCap is round when
     * [capRound] (the source sets strokeLinecap="round" on those paths).
     */
    data class PathOp(
        val d: String,
        val fill: Int? = null,
        val alpha: Float = 1f,
        val stroke: Int? = null,
        val strokeWidth: Float = 0f,
        val capRound: Boolean = false,
    ) : Op
}

/** One authored anchor ring + suggested prompt dots for a scene target. */
data class SceneTarget(
    val name: String,
    val anchors: List<Pair<Float, Float>>,
    val suggest: List<Pair<Float, Float>>,
    val suggestNeg: List<Pair<Float, Float>>,
)

/** A demo scene: metadata + the art ops in painter's order. */
data class SceneDef(
    val id: String,
    val title: String,
    val file: String,
    /** Flat tone color (grid/filmstrip backgrounds). */
    val tone: Int,
    val target: SceneTarget,
    val ops: List<Op>,
)

/**
 * Compiled renderer for a [SceneDef]: parses paths once, draws to either
 * a Compose [DrawScope] (annotation canvas, thumbnails) or an
 * `android.graphics.Canvas` (PNG export into the dataset folder).
 */
class CompiledScene(private val def: SceneDef) : SceneLike {
    override val id: String get() = def.id
    override val title: String get() = def.title
    override val file: String get() = def.file
    override val tone: Int get() = def.tone
    override val target: SceneTarget get() = def.target

    // ── compiled op lists (parsed once) ───────────────────────────────────

    private class ARect(val rect: RectF, val radius: Float, val paint: Paint, val rotate: Float)
    private class APath(val path: Path, val fill: Paint?, val stroke: Paint?, val rotate: Float)
    private class ACircle(val cx: Float, val cy: Float, val r: Float, val paint: Paint)
    private class AEllipse(val rect: RectF, val paint: Paint)

    private val androidOps: List<Any> by lazy { compileAndroid(def.ops) }
    private val composeOps: List<Any> by lazy { compileCompose(def.ops) }

    private fun fillPaint(color: Int, alpha: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
        this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
    }

    private fun strokePaint(color: Int, alpha: Float, width: Float, capRound: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
            this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            strokeWidth = width
            strokeCap = if (capRound) Paint.Cap.ROUND else Paint.Cap.BUTT
            strokeJoin = Paint.Join.ROUND
        }

    private fun compileAndroid(ops: List<Op>): List<Any> = ops.map { op ->
        when (op) {
            is Op.RectOp -> ARect(
                RectF(op.x, op.y, op.x + op.w, op.y + op.h), op.rx, fillPaint(op.color, op.alpha), op.rotate,
            )
            is Op.CircleOp -> ACircle(op.cx, op.cy, op.r, fillPaint(op.color, op.alpha))
            is Op.EllipseOp -> AEllipse(
                RectF(op.cx - op.rx, op.cy - op.ry, op.cx + op.rx, op.cy + op.ry),
                fillPaint(op.color, op.alpha),
            )
            is Op.PathOp -> APath(
                path = PathParser.createPathFromPathData(op.d) ?: Path(),
                fill = op.fill?.let { fillPaint(it, op.alpha) },
                stroke = op.stroke?.let { strokePaint(it, op.alpha, op.strokeWidth, op.capRound) },
                rotate = 0f,
            )
        }
    }

    private class CRect(val x: Float, val y: Float, val w: Float, val h: Float, val rx: Float, val color: Color, val rotate: Float, val pivotX: Float, val pivotY: Float)
    private class CPath(val path: ComposePath, val fill: Color?, val stroke: Color?, val strokeWidth: Float, val capRound: Boolean)
    private class CCircle(val cx: Float, val cy: Float, val r: Float, val color: Color)
    private class CEllipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val color: Color)

    private fun c(color: Int, alpha: Float): Color = Color(color).copy(alpha = Color(color).alpha * alpha)

    private fun compileCompose(ops: List<Op>): List<Any> = ops.map { op ->
        when (op) {
            is Op.RectOp -> CRect(op.x, op.y, op.w, op.h, op.rx, c(op.color, op.alpha), op.rotate, op.pivotX, op.pivotY)
            is Op.CircleOp -> CCircle(op.cx, op.cy, op.r, c(op.color, op.alpha))
            is Op.EllipseOp -> CEllipse(op.cx, op.cy, op.rx, op.ry, c(op.color, op.alpha))
            is Op.PathOp -> CPath(
                path = (PathParser.createPathFromPathData(op.d) ?: Path()).asComposePath(),
                fill = op.fill?.let { c(it, op.alpha) },
                stroke = op.stroke?.let { c(it, op.alpha) },
                strokeWidth = op.strokeWidth,
                capRound = op.capRound,
            )
        }
    }

    // ── renderers ─────────────────────────────────────────────────────────

    /** Draw into an android.graphics.Canvas at world scale (800×600). */
    fun drawAndroid(canvas: Canvas) {
        for (o in androidOps) {
            when (o) {
                is ARect -> {
                    if (o.rotate != 0f) {
                        val save = canvas.save()
                        canvas.rotate(o.rotate, o.rect.centerX(), o.rect.centerY())
                        canvas.drawRoundRect(o.rect, o.radius, o.radius, o.paint)
                        canvas.restoreToCount(save)
                    } else {
                        canvas.drawRoundRect(o.rect, o.radius, o.radius, o.paint)
                    }
                }
                is ACircle -> canvas.drawCircle(o.cx, o.cy, o.r, o.paint)
                is AEllipse -> canvas.drawOval(o.rect, o.paint)
                is APath -> {
                    o.fill?.let { canvas.drawPath(o.path, it) }
                    o.stroke?.let { canvas.drawPath(o.path, it) }
                }
            }
        }
    }

    /** Draw inside a Compose [DrawScope] (already sized to the world). */
    fun drawCompose(scope: DrawScope) {
        for (o in composeOps) {
            when (o) {
                is CRect -> {
                    val drawIt: DrawScope.() -> Unit = {
                        drawRoundRect(
                            color = o.color,
                            topLeft = Offset(o.x, o.y),
                            size = Size(o.w, o.h),
                            cornerRadius = CornerRadius(o.rx, o.rx),
                        )
                    }
                    if (o.rotate != 0f) {
                        scope.rotate(o.rotate, Offset(o.pivotX, o.pivotY)) { drawIt() }
                    } else {
                        scope.drawIt()
                    }
                }
                is CCircle -> scope.drawCircle(o.color, o.r, Offset(o.cx, o.cy))
                is CEllipse -> scope.drawOval(
                    color = o.color,
                    topLeft = Offset(o.cx - o.rx, o.cy - o.ry),
                    size = Size(o.rx * 2, o.ry * 2),
                )
                is CPath -> {
                    o.fill?.let { scope.drawPath(o.path, color = it) }
                    o.stroke?.let {
                        scope.drawPath(
                            path = o.path,
                            color = it,
                            style = Stroke(
                                width = o.strokeWidth,
                                cap = if (o.capRound) ComposeStrokeCap.Round else ComposeStrokeCap.Butt,
                                join = ComposeStrokeJoin.Round,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Common surface of scene metadata (implemented by [CompiledScene]). */
interface SceneLike {
    val id: String
    val title: String
    val file: String
    val tone: Int
    val target: SceneTarget
}
