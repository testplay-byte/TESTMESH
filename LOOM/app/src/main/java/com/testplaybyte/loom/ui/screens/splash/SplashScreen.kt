package com.testplaybyte.loom.ui.screens.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import android.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.testplaybyte.loom.ui.theme.LoomMotion
import com.testplaybyte.loom.ui.theme.loomColors
import kotlinx.coroutines.delay

/**
 * §1 Splash — the brand mark IS the splash (docs/03 §1, v0.6 redesign).
 *
 * A single large woven-knot mark, centered, nothing else — no bar, no
 * tagline, no version. The animation lives entirely on the logo, in three
 * overlapping phases driven by one 0→1 timeline (~1700ms):
 *
 *   · 0.00–0.55  the vesica outline draws itself on (PathMeasure trim);
 *   · 0.45–0.80  the two inner arcs WEAVE on — each arc trims along its
 *                own length, from opposite ends, crossing as they go;
 *   · 0.75–1.00  the four node dots pop in with the spring ease while the
 *                whole mark settles 0.9→1.0 in scale.
 *
 * Behavior: advances ~250ms after the mark settles; tap anywhere skips;
 * reduced motion (animator scale 0) shows the settled mark for 50ms.
 */
@Composable
fun SplashScreen(
    onDone: () -> Unit,
    hydrated: Boolean,
) {
    val c = loomColors
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    // LaunchedEffect(Unit) never restarts — poll the LIVE hydration flag,
    // not the composition-time snapshot.
    val currentHydrated by rememberUpdatedState(hydrated)
    // Shared across the effect AND the tap-to-skip so onDone can never fire
    // twice (a second navigate would push a duplicate destination).
    val advanced = remember { arrayOf(false) }

    fun advance() {
        if (!advanced[0]) {
            advanced[0] = true
            onDone()
        }
    }

    val progress = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val markScale = remember { Animatable(if (reducedMotion) 1f else 0.9f) }

    LaunchedEffect(Unit) {
        if (reducedMotion) {
            delay(50)
            advance()
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        launch { markScale.animateTo(1f, tween(1150, easing = LoomMotion.easeSpring)) }
        // Advance shortly after the mark settles — no dead wait. The scale
        // settle runs CONCURRENTLY with the draw-on (no blank lead-in).
        progress.animateTo(1f, tween(1700, easing = androidx.compose.animation.core.LinearEasing))
        while (!currentHydrated && System.currentTimeMillis() - start < 3000) delay(20)
        delay(250)
        advance()
        // Safety net: never hold the splash longer than 3.2s under any path.
        while (!advanced[0] && System.currentTimeMillis() - start < 3200) delay(30)
        advance()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { advance() },
        contentAlignment = Alignment.Center,
    ) {
        BrandMarkDrawOn(
            progress = progress.value,
            size = 132.dp,
            strokeWidth = 1.3f,
            modifier = Modifier.scale(markScale.value),
        )
    }
}

/**
 * The brand mark with the full draw-on: vesica outline trims 0→1 along its
 * length (PathMeasure), each inner arc trims from its own start (weaving
 * through each other), the four node dots pop at 75% with the spring ease.
 */
@Composable
private fun BrandMarkDrawOn(progress: Float, size: Dp, strokeWidth: Float, modifier: Modifier = Modifier) {
    val c = loomColors
    val vesica = remember { com.testplaybyte.loom.ui.icons.loomVesicaPath() }
    val arc1 = remember { com.testplaybyte.loom.ui.icons.loomArcPath() }
    // The arc path data holds both arcs in one Path ("M..M.."); split them
    // so each can weave on independently.
    val arc2 = remember {
        android.graphics.Path().apply {
            androidx.core.graphics.PathParser.createPathFromPathData(
                "M5.2 14.6c4.4-1.9 9.2-1.9 13.6 0",
            )?.let { addPath(it) }
        }
    }
    val arc1Only = remember {
        androidx.core.graphics.PathParser.createPathFromPathData(
            "M5.2 9.4c4.4 1.9 9.2 1.9 13.6 0",
        ) ?: android.graphics.Path()
    }
    val measure = remember { PathMeasure() }
    Canvas(modifier = modifier.size(size)) {
        val k = this.size.minDimension / 24f
        translate((this.size.width - 24f * k) / 2f, (this.size.height - 24f * k) / 2f) {
            scale(k, k, pivot = Offset.Zero) {
                fun trimmed(path: android.graphics.Path, from: Float, to: Float): android.graphics.Path {
                    measure.setPath(path, false)
                    val len = measure.length
                    val part = android.graphics.Path()
                    measure.getSegment(len * from, len * to, part, true)
                    return part
                }
                val p = progress.coerceIn(0f, 1f)
                // vesica outline: draws on over the first 55%
                val vesicaT = (p / 0.55f).coerceIn(0f, 1f)
                drawPath(
                    path = trimmed(vesica, 0f, vesicaT).asComposePath(),
                    color = c.primary,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                // inner arcs: arc 1 weaves on 45→75%, arc 2 on 55→85%
                val a1 = ((p - 0.45f) / 0.30f).coerceIn(0f, 1f)
                val a2 = ((p - 0.55f) / 0.30f).coerceIn(0f, 1f)
                if (a1 > 0f) {
                    drawPath(
                        path = trimmed(arc1Only, 0f, a1).asComposePath(),
                        color = c.primary.copy(alpha = 0.8f),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
                if (a2 > 0f) {
                    drawPath(
                        path = trimmed(arc2, 0f, a2).asComposePath(),
                        color = c.primary.copy(alpha = 0.8f),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
                // node dots pop at 75% (spring-ish overshoot feel)
                val dotT = ((p - 0.75f) / 0.25f).coerceIn(0f, 1f)
                if (dotT > 0f) {
                    val overshoot = if (dotT < 0.4f) dotT / 0.4f * 1.25f else 1.25f - (dotT - 0.4f) / 0.6f * 0.25f
                    val r = 1.5f * overshoot
                    for (pos in NODE_DOTS) {
                        drawCircle(color = c.primary, radius = r, center = pos)
                    }
                }
            }
        }
    }
}

private val NODE_DOTS = listOf(
    Offset(12f, 3.6f), Offset(19f, 12f), Offset(12f, 20.4f), Offset(5f, 12f),
)
