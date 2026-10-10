package com.testplaybyte.loom.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType

/**
 * The signature element: ONE stepped blob behind the meter row + stats
 * foot (docs/02 §6, docs/03 §3 geometry, ported from `home-screen.tsx`
 * `blobPath()`).
 *
 * ```
 * · Top edge spans the full width with 14dp top corners.
 * · Below the meter row the right side steps DOWN to the stats foot. The
 *   foot extends only as far as the last AVAILABLE stat, ends in a convex
 *   14dp corner, and a CONCAVE 9dp inverted corner joins the foot's right
 *   edge back up to the meter section, which closes at the far right.
 * · The bottom-left corner mirrors the top-left.
 * · "Edited …" sits in the empty area right of the foot, outside the blob.
 * ```
 *
 * The path is drawn against measured content (meter-row height `h1` and
 * stats-group width `step`), re-measured on layout — not a 9-patch.
 */
private const val BLOB_R = 14f   // outer corner radius (px/units)
private const val BLOB_RI = 9f  // concave junction radius

@Composable
fun StatBlobPanel(
    totalImages: Int,
    doneCount: Int,
    draftCount: Int,
    pct: Float,
    editedText: String,
    modifier: Modifier = Modifier,
) {
    val c = loomColors
    var panelW by remember { mutableFloatStateOf(0f) }
    var panelH by remember { mutableFloatStateOf(0f) }
    var meterBottom by remember { mutableFloatStateOf(0f) }
    var statsWidth by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged {
                panelW = it.width.toFloat()
                panelH = it.height.toFloat()
            },
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
            if (panelW > 0f && panelH > 0f) {
                val path = blobPath(panelW, panelH, meterBottom, statsWidth)
                drawPath(path, color = c.surface3.copy(alpha = 0.40f))
                drawPath(path, color = c.hairline, style = Stroke(width = 1f))
            }
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            // meter line: 11px 13px 9px padding, meter + mono % right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { meterBottom = it.height.toFloat() }
                    .padding(start = 13.dp, end = 13.dp, top = 11.dp, bottom = 9.dp),
            ) {
                LoomMeter(
                    value = pct,
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                    tone = if (pct >= 100f) LoomMeterTone.GOOD else LoomMeterTone.ACCENT,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${Math.round(pct)}%",
                    style = loomType.monoSmall.copy(fontWeight = FontWeight.W600),
                    color = c.textMuted,
                )
            }
            // bottom row: stat foot (defines the step) + Edited stamp outside
            Row(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .onSizeChanged { statsWidth = it.width.toFloat() }
                        .padding(start = 13.dp, end = 13.dp, top = 1.dp, bottom = 11.dp),
                ) {
                    BlobStat(totalImages, "images", null)
                    if (doneCount > 0) {
                        Spacer(Modifier.width(12.dp))
                        BlobStat(doneCount, "done", c.pos)
                    }
                    if (draftCount > 0) {
                        Spacer(Modifier.width(12.dp))
                        BlobStat(draftCount, "drafts", c.primary)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = editedText,
                    style = loomType.monoSmall.copy(fontSize = 10.5.sp),
                    color = c.textMuted.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 13.dp, top = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun BlobStat(number: Int, label: String, numberColor: androidx.compose.ui.graphics.Color?) {
    val c = loomColors
    Row {
        Text(
            text = "$number",
            style = loomType.monoStat.copy(fontWeight = FontWeight.W600, fontSize = 12.5.sp),
            color = numberColor ?: c.text,
        )
        Spacer(Modifier.width(5.dp))
        Text(text = label, style = loomType.monoSmall.copy(fontSize = 11.sp), color = c.textMuted)
    }
}

/**
 * Exact port of the prototype's `blobPath(w, h, h1, wb)` — SVG arc
 * geometry mapped to Compose `arcTo` (y-down screen space: positive sweep
 * = clockwise; the junction is counter-clockwise, i.e. a negative sweep).
 */
fun blobPath(w: Float, h: Float, h1: Float, wb: Float): Path {
    val W = maxOf(w, 120f)
    val H = maxOf(h, h1 + BLOB_R * 2)
    val top = h1.coerceIn(BLOB_R, H - BLOB_R * 2)
    val step = wb.coerceIn(BLOB_R * 2 + 10, W - BLOB_R - BLOB_RI - 6)
    val R = BLOB_R
    val RI = BLOB_RI
    return Path().apply {
        moveTo(R, 0f)
        lineTo(W - R, 0f)
        arcTo(Rect(W - 2 * R, 0f, W, 2 * R), -90f, 90f, false)      // top-right
        lineTo(W, top - R)
        arcTo(Rect(W - 2 * R, top - 2 * R, W, top), 0f, 90f, false) // step-out corner
        lineTo(step + RI, top)
        arcTo(Rect(step, top, step + 2 * RI, top + 2 * RI), -90f, -90f, false) // INVERTED corner
        lineTo(step, H - R)
        arcTo(Rect(step - 2 * R, H - 2 * R, step, H), 0f, 90f, false)  // foot bottom-right
        lineTo(R, H)
        arcTo(Rect(0f, H - 2 * R, 2 * R, H), 90f, 90f, false)         // bottom-left (mirrors TL)
        lineTo(0f, R)
        arcTo(Rect(0f, 0f, 2 * R, 2 * R), 180f, 90f, false)           // top-left
        close()
    }
}
