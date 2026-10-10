package com.testplaybyte.loom.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Masonry image grid — two equal columns, every tile at its OWN aspect
 * ratio, shortest-column-first placement (docs/02 §6 + docs/06 §7).
 *
 * Deliberately NOT `LazyVerticalStaggeredGrid`: the prototype's exact
 * algorithm is deterministic —
 *   · each item's aspect is `[4/3, 5/4, 1, 4/5, 3/4]` picked by an FNV-1a
 *     hash of the image id ([tileAspect]);
 *   · each new tile goes into the currently SHORTER column, where column
 *     heights are tracked with the prototype's WEIGHT estimate
 *     (`170/aspect + 40`, docs/06 §7); actual y positions use measured
 *     heights so nothing overlaps or leaves bands.
 *
 * The tile lambda must apply its own aspect (e.g. `Modifier.aspectRatio(a)`).
 */
private val ASPECTS = floatArrayOf(4f / 3f, 5f / 4f, 1f, 4f / 5f, 3f / 4f)

/** Placement weights — exact values from docs/06 §7 / tokens.json. */
private const val COLUMN_WIDTH_ESTIMATE = 170f
private const val META_HEIGHT_ESTIMATE = 40f

/**
 * Exact FNV-1a 32-bit port from docs/06 §7 (wrapping 32-bit multiply like
 * the prototype's JS; unit-tested so the six seed ids keep their natural
 * landscape/portrait/square mix).
 */
fun tileAspect(id: String): Float {
    var h = 2166136261L
    id.forEach { c -> h = ((h.toInt() xor c.code) * 16777619).toLong() }
    return ASPECTS[(h.toInt() and 0x7fffffff) % ASPECTS.size]
}

@Composable
fun <T> MasonryGrid(
    items: List<T>,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    columnGap: Dp = 10.dp,
    rowGap: Dp = 12.dp,
    keyOf: (T) -> Any,
    aspectOf: (T) -> Float,
    tile: @Composable (T) -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            items.forEach { item ->
                key(keyOf(item)) { tile(item) }
            }
        },
    ) { measurables, constraints ->
        val maxWidth = constraints.maxWidth
        val gapPx = columnGap.roundToPx().toFloat()
        val rowGapPx = rowGap.roundToPx()
        val colWidth = ((maxWidth - gapPx * (columns - 1)) / columns).coerceAtLeast(1f).toInt()
        val colX = IntArray(columns) { (it * (colWidth + gapPx)).roundToInt() }
        val colWeights = FloatArray(columns)
        val colHeights = IntArray(columns)
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val xs = IntArray(measurables.size)
        val ys = IntArray(measurables.size)

        measurables.forEachIndexed { index, measurable ->
            // Column choice follows the prototype exactly: shortest WEIGHT.
            var col = 0
            for (k in 1 until columns) if (colWeights[k] < colWeights[col]) col = k
            val aspect = aspectOf(items[index]).coerceAtLeast(0.2f)
            colWeights[col] += COLUMN_WIDTH_ESTIMATE / aspect + META_HEIGHT_ESTIMATE

            val placeable = measurable.measure(
                Constraints(minWidth = colWidth, maxWidth = colWidth),
            )
            placeables[index] = placeable
            xs[index] = colX[col]
            ys[index] = colHeights[col]
            colHeights[col] += placeable.height + rowGapPx
        }

        val totalHeight = (colHeights.maxOrNull() ?: 0).let { if (it > 0) it - rowGapPx else 0 }
        layout(maxWidth, totalHeight) {
            placeables.forEachIndexed { index, placeable ->
                placeable?.placeRelative(xs[index], ys[index])
            }
        }
    }
}
