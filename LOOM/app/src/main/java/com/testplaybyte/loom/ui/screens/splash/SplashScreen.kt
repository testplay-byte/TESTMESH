package com.testplaybyte.loom.ui.screens.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import android.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.testplaybyte.loom.ui.theme.LoomMotion
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * §1 Splash — brand intro, auto-advances (docs/03 §1).
 *
 * Layout: full-bleed bg + faint 24dp drafting grid; centered 72dp
 * woven-knot mark with a stroke draw-on (~900ms, implemented with
 * PathMeasure trim), wordmark `Loom` (30sp/700), tagline `annotation
 * workbench` (mono 12, muted), then a 2dp × 120dp bar whose amber fill
 * sweeps 0→100%.
 *
 * Behavior: advances the moment the bar completes (~1950ms) — no dead time
 * after the fill; hydration of persisted state is awaited with a 3s bound
 * so a slow read can never hang the splash; tap anywhere skips; when the
 * system animator scale is 0 (reduced motion) it advances after 50ms.
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

    // draw-on progress 0..1 over 900ms
    var drawOn by remember { mutableFloatStateOf(if (reducedMotion) 1f else 0f) }
    var barSweep by remember { mutableFloatStateOf(if (reducedMotion) 1f else 0f) }

    LaunchedEffect(Unit) {
        if (reducedMotion) {
            delay(50)
            onDone()
            return@LaunchedEffect
        }
        var advanced = false
        val start = System.currentTimeMillis()
        fun advance() {
            if (!advanced) {
                advanced = true
                onDone()
            }
        }
        launch {
            val anim = Animatable(0f)
            anim.animateTo(1f, tween(900, easing = LoomMotion.ease)) {
                drawOn = value
            }
        }
        launch {
            delay(700)
            val anim = Animatable(0f)
            // Advance the instant the bar finishes filling (docs: no linger).
            anim.animateTo(1f, tween(1250, easing = LinearEasing)) {
                barSweep = value
            }
            while (!hydrated && System.currentTimeMillis() - start < 3000) delay(20)
            advance()
        }
        // Safety net: never hold the splash longer than 3.2s under any path.
        while (!advanced && System.currentTimeMillis() - start < 3200) delay(30)
        advance()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDone() },
    ) {
        // drafting-grid backdrop (24dp cells)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cell = 24.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(c.grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += cell
            }
            var y = 0f
            while (y < size.height) {
                drawLine(c.grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += cell
            }
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandMarkDrawOn(progress = drawOn, size = 72.dp, strokeWidth = 1.4f)
            Spacer(Modifier.height(22.dp))
            Text(
                text = "Loom",
                style = loomType.screenTitle.copy(
                    fontSize = 30.sp,
                    fontWeight = FontWeight.W700,
                ),
                color = c.text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "annotation workbench",
                style = loomType.monoSmall.copy(fontSize = 12.sp),
                color = c.textMuted,
            )
            Spacer(Modifier.height(30.dp))
            // 2dp × 120dp sweep bar
            Canvas(modifier = Modifier.width(120.dp).height(2.dp)) {
                drawRect(color = c.hairlineStrong)
                drawRect(
                    color = c.primary,
                    size = Size(size.width * barSweep, size.height),
                )
            }
        }

        Text(
            text = "v0.4.0 · prototype",
            style = loomType.monoSmall.copy(fontSize = 11.sp),
            color = c.textMuted,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
        )
    }
}

/**
 * The brand mark with the stroke draw-on: the vesica outline trims 0→1
 * along its length (PathMeasure), the two inner arcs fade in at 55%, and
 * the four node dots pop at 75% with the spring ease.
 */
@Composable
private fun BrandMarkDrawOn(progress: Float, size: androidx.compose.ui.unit.Dp, strokeWidth: Float) {
    val c = loomColors
    val vesica = remember {
        com.testplaybyte.loom.ui.icons.loomVesicaPath()
    }
    val arcs = remember { com.testplaybyte.loom.ui.icons.loomArcPath() }
    val measure = remember { PathMeasure() }
    Canvas(modifier = Modifier.size(size)) {
        val k = this.size.minDimension / 24f
        val sw = strokeWidth * k
        translate((this.size.width - 24f * k) / 2f, (this.size.height - 24f * k) / 2f) {
            scale(k, k, pivot = Offset.Zero) {
                // trimmed vesica outline
                measure.setPath(vesica, false)
                val len = measure.length
                val part = android.graphics.Path()
                measure.getSegment(0f, len * progress.coerceIn(0f, 1f), part, true)
                drawPath(
                    path = part.asComposePath(),
                    color = c.primary,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                // inner arcs at 55%
                val arcAlpha = ((progress - 0.55f) / 0.45f).coerceIn(0f, 1f) * 0.75f
                if (arcAlpha > 0f) {
                    drawPath(
                        path = arcs.asComposePath(),
                        color = c.primary.copy(alpha = arcAlpha),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
                // node dots at 75% (spring pop)
                val dotT = ((progress - 0.75f) / 0.25f).coerceIn(0f, 1f)
                if (dotT > 0f) {
                    val r = 1.5f * dotT
                    for (p in NODE_DOTS) {
                        drawCircle(color = c.primary, radius = r, center = p)
                    }
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION") sw
    }
}

private val NODE_DOTS = listOf(
    Offset(12f, 3.6f), Offset(19f, 12f), Offset(12f, 20.4f), Offset(5f, 12f),
)
