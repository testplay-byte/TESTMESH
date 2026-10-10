package com.testplaybyte.loom.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.testplaybyte.loom.data.image.ImageStore
import com.testplaybyte.loom.data.scene.CompiledScene
import com.testplaybyte.loom.data.scene.SceneLibrary
import com.testplaybyte.loom.data.workspace.Workspace
import com.testplaybyte.loom.domain.model.ImageStatus
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Image presentation helpers shared by every screen:
 *
 *  · [SceneArt]      — draws a demo [CompiledScene]'s vector art into any
 *    box (fit or aspect-fill), so the demo "photos" stay crisp at every
 *    zoom level (no bitmap upscaling);
 *  · [WorkspaceImage] — draws a project image: vector art for demo scenes,
 *    a workspace-decoded bitmap for real photos (bounds-capped, LRU-cached);
 *  · status dots/chips — the mint/amber progress language (docs/02 §1).
 */

enum class ImageScaleMode { FIT, FILL }

@Composable
fun SceneArt(
    scene: CompiledScene,
    modifier: Modifier = Modifier,
    mode: ImageScaleMode = ImageScaleMode.FIT,
    background: Color? = null,
) {
    val tone = Color(scene.tone)
    Canvas(modifier = modifier) {
        background?.let { drawRect(it) }
        // letterbox tone behind fit mode so edges read intentional
        drawRect(tone)
        val sx = size.width / SceneLibrary.WORLD_W
        val sy = size.height / SceneLibrary.WORLD_H
        val s = if (mode == ImageScaleMode.FIT) minOf(sx, sy) else maxOf(sx, sy)
        val dx = (size.width - SceneLibrary.WORLD_W * s) / 2f
        val dy = (size.height - SceneLibrary.WORLD_H * s) / 2f
        clipRect {
            translate(dx, dy) {
                scale(s, s, pivot = Offset.Zero) {
                    scene.drawCompose(this)
                }
            }
        }
    }
}

/**
 * A project image: demo scene art when the entry carries a sceneId,
 * otherwise the real photo decoded from the workspace (bounded, cached).
 * Missing files fall back to a flat tone plate instead of crashing.
 */
@Composable
fun WorkspaceImage(
    ws: Workspace,
    projectSlug: String,
    fileName: String,
    sceneId: String?,
    modifier: Modifier = Modifier,
    mode: ImageScaleMode = ImageScaleMode.FILL,
    maxDim: Int = ImageStore.MAX_CANVAS_DIM,
    toneHint: Int = 0xFF292420.toInt(),
) {
    if (sceneId != null) {
        SceneArt(SceneLibrary.byId(sceneId), modifier, mode)
        return
    }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, fileName, projectSlug) {
        value = withContext(Dispatchers.IO) {
            ImageStore.load(ws, projectSlug, fileName, maxDim, cacheKey = "$projectSlug/$fileName")
                ?.asImageBitmap()
        }
    }
    val bmp = bitmap
    Canvas(modifier = modifier) {
        drawRect(Color(toneHint))
        if (bmp != null) {
            val sx = size.width / bmp.width
            val sy = size.height / bmp.height
            val s = if (mode == ImageScaleMode.FIT) minOf(sx, sy) else maxOf(sx, sy)
            val dw = bmp.width * s
            val dh = bmp.height * s
            drawImage(
                image = bmp,
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    ((size.width - dw) / 2f).toInt(),
                    ((size.height - dh) / 2f).toInt(),
                ),
                dstSize = androidx.compose.ui.unit.IntSize(dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1)),
            )
        }
    }
}

/**
 * One project image drawn into any box — the shared entry point for home
 * thumbs, masonry tiles and the filmstrip. Demo scenes render their vector
 * art (crisp at every size); real photos decode from the workspace.
 */
@Composable
fun ProjectImageArt(
    ws: Workspace,
    projectSlug: String,
    image: com.testplaybyte.loom.domain.model.ProjectImage,
    modifier: Modifier = Modifier,
    mode: ImageScaleMode = ImageScaleMode.FILL,
    maxDim: Int = 512,
) {
    WorkspaceImage(
        ws = ws,
        projectSlug = projectSlug,
        fileName = image.file,
        sceneId = image.sceneId,
        modifier = modifier,
        mode = mode,
        maxDim = maxDim,
        toneHint = image.sceneId
            ?.let { SceneLibrary.byId(it).tone }
            ?: 0xFF292420.toInt(),
    )
}

/** 8dp status dot: done = mint, draft = amber, unlabeled = hairline ring. */
@Composable
fun StatusDot(status: ImageStatus, modifier: Modifier = Modifier, punchOutColor: Color? = null) {
    val c = loomColors
    val dotColor = when (status) {
        ImageStatus.DONE -> c.pos
        ImageStatus.DRAFT -> c.primary
        ImageStatus.UNLABELED -> Color.Transparent
    }
    Box(
        modifier = modifier.size(8.dp).clip(CircleShape).background(dotColor).then(
            if (status == ImageStatus.UNLABELED) Modifier.border(1.dp, c.hairlineStrong, CircleShape)
            else if (punchOutColor != null) Modifier.border(2.dp, punchOutColor, CircleShape) else Modifier,
        ),
    )
}

/**
 * Status chip overlay for masonry tiles (docs/03 §5): done = mint dot +
 * check, draft = amber dot, unlabeled = hairline dot + "—".
 */
@Composable
fun StatusChip(status: ImageStatus, modifier: Modifier = Modifier) {
    val c = loomColors
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(c.surface1.copy(alpha = 0.92f))
            .border(1.dp, c.hairline, CircleShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (status) {
            ImageStatus.DONE -> {
                StatusDot(status)
                Spacer(Modifier.width(5.dp))
                Icon(
                    imageVector = com.testplaybyte.loom.ui.icons.IconCheck,
                    contentDescription = null,
                    tint = c.pos,
                    modifier = Modifier.size(12.dp),
                )
            }
            ImageStatus.DRAFT -> {
                StatusDot(status)
            }
            ImageStatus.UNLABELED -> {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).border(1.dp, c.hairlineStrong, CircleShape),
                )
                Spacer(Modifier.width(5.dp))
                Text("—", style = loomType.small, color = c.textMuted)
            }
        }
    }
}

/** 64×48 filmstrip/home thumb frame with the hairline ring + shadow lift. */
@Composable
fun ThumbFrame(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.DpSize = androidx.compose.ui.unit.DpSize(64.dp, 48.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val c = loomColors
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(c.surface2)
            .border(1.dp, c.hairlineStrong, RoundedCornerShape(8.dp)),
    ) {
        content()
    }
}

/** Convenience wrapper: a rounded, hairlined art frame. */
@Composable
fun ArtFrame(
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = loomColors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(c.surface2)
            .border(1.dp, c.hairline, RoundedCornerShape(radius)),
    ) {
        content()
    }
}
