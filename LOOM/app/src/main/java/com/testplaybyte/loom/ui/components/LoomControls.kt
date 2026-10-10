package com.testplaybyte.loom.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.testplaybyte.loom.ui.icons.IconCheck
import com.testplaybyte.loom.ui.icons.IconClose
import com.testplaybyte.loom.ui.icons.IconFolder
import com.testplaybyte.loom.ui.icons.IconMinus
import com.testplaybyte.loom.ui.icons.IconPlus
import com.testplaybyte.loom.ui.theme.LoomMotion
import com.testplaybyte.loom.ui.theme.LoomShape
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType

/**
 * The Loom shared control kit — Compose port of `components/ui.tsx` +
 * `loom.css` §3 (docs/02 §6 "Component inventory with exact geometry").
 *
 * Hard rules kept from the source:
 *  · ≥44dp touch targets (36dp only for dense canvas console rows);
 *  · switches legible in all four states (on/off × dark/light);
 *  · segmented controls = distinct cells with per-cell hairline borders
 *    (never a sliding-thumb pill);
 *  · sheets: opaque surface + scrim, no drag handle (tap scrim to close);
 *  · controls size to their content, never the reverse.
 */

// ── Buttons ───────────────────────────────────────────────────────────────

enum class LoomButtonVariant { PRIMARY, TONAL, GHOST, DANGER }

enum class LoomButtonSize { MD, SM }

/**
 * `lm-btn`: radius 10, min-height 44 (md) / 36 (sm), weight 600.
 * Variants: primary (amber), tonal (surface-2 + hairline), ghost
 * (transparent), danger (error outline + error text).
 */
@Composable
fun LoomButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: LoomButtonVariant = LoomButtonVariant.PRIMARY,
    size: LoomButtonSize = LoomButtonSize.MD,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
    icon: ImageVector? = null,
    label: String? = null,
) {
    val c = loomColors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = tween(LoomMotion.DUR_1, easing = LoomMotion.ease),
        label = "btnScale",
    )
    val height = if (size == LoomButtonSize.SM) 36.dp else 44.dp
    val hPad = if (size == LoomButtonSize.SM) 13.dp else 18.dp
    val bg: Color = when (variant) {
        LoomButtonVariant.PRIMARY -> c.primary
        LoomButtonVariant.TONAL -> c.surface2
        LoomButtonVariant.GHOST -> Color.Transparent
        LoomButtonVariant.DANGER -> Color.Transparent
    }
    val border: Color? = when (variant) {
        LoomButtonVariant.TONAL -> c.hairline
        LoomButtonVariant.DANGER -> c.error.copy(alpha = 0.45f)
        else -> null
    }
    val fg: Color = when (variant) {
        LoomButtonVariant.PRIMARY -> c.primaryFg
        LoomButtonVariant.DANGER -> c.error
        else -> c.text
    }
    val textStyle: TextStyle =
        if (size == LoomButtonSize.SM) loomType.buttonSmall else loomType.button

    Row(
        modifier = modifier
            .scale(scale)
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = height)
            .clip(RoundedCornerShape(LoomShape.RADIUS_CONTROL_DP.dp))
            .background(bg)
            .then(
                if (border != null) {
                    Modifier.border(1.dp, border, RoundedCornerShape(LoomShape.RADIUS_CONTROL_DP.dp))
                } else {
                    Modifier
                },
            )
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = hPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (size == LoomButtonSize.SM) 6.dp else 8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(if (size == LoomButtonSize.SM) 15.dp else 17.dp),
            )
        }
        if (label != null) {
            Text(text = label, style = textStyle, color = fg, maxLines = 1)
        }
    }
}

/** `lm-iconbtn`: 44dp square, radius 10, tint = text. */
@Composable
fun LoomIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    size: Dp = 44.dp,
    iconSize: Dp = 19.dp,
) {
    val c = loomColors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(LoomShape.RADIUS_CONTROL_DP.dp))
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: c.text,
            modifier = Modifier.size(iconSize),
        )
    }
}

// ── Switch ────────────────────────────────────────────────────────────────

/**
 * `lm-switch` (CSS §3): 50×30 track, 22dp thumb; ON = primary track with a
 * 2dp background-colored ring and the thumb slid 20dp right, carrying a
 * dark check. Legible in all four states by construction (the thumb plate
 * is always #FFFDF6).
 */
@Composable
fun LoomSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val c = loomColors
    val track by animateColorAsState(
        targetValue = if (checked) c.primary else c.surface4,
        animationSpec = tween(LoomMotion.DUR_2, easing = LoomMotion.ease),
        label = "switchTrack",
    )
    val thumbOffset by animateFloatAsState(
        targetValue = if (checked) 20f else 0f,
        animationSpec = tween(LoomMotion.DUR_2, easing = LoomMotion.ease),
        label = "switchThumb",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(width = 50.dp, height = 30.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Switch
            }
            .clickable(interactionSource = interaction, indication = null) { onCheckedChange(!checked) },
    ) {
        // Track (with the 2dp bg ring drawn as an outer stroke when ON).
        Box(
            modifier = Modifier
                .size(width = 50.dp, height = 30.dp)
                .drawBehind {
                    val full = Size(size.width, size.height)
                    if (checked) {
                        // ring: background-colored 2dp band around the track
                        drawRoundRect(
                            color = c.bg,
                            topLeft = Offset.Zero,
                            size = full,
                            cornerRadius = CornerRadius(size.height / 2),
                        )
                        val inset = 2.dp.toPx()
                        drawRoundRect(
                            color = track,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - inset * 2, size.height - inset * 2),
                            cornerRadius = CornerRadius((size.height - inset * 2) / 2),
                        )
                    } else {
                        drawRoundRect(
                            color = track,
                            topLeft = Offset.Zero,
                            size = full,
                            cornerRadius = CornerRadius(size.height / 2),
                        )
                        val hair = 1.dp.toPx()
                        drawRoundRect(
                            color = c.hairlineStrong,
                            topLeft = Offset(hair / 2, hair / 2),
                            size = Size(size.width - hair, size.height - hair),
                            cornerRadius = CornerRadius(size.height / 2),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = hair),
                        )
                    }
                },
        )
        // Thumb.
        Box(
            modifier = Modifier
                .offset(x = 3.dp + thumbOffset.dp)
                .align(Alignment.CenterStart)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color(0xFFFFFDF6)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = IconCheck,
                    contentDescription = null,
                    tint = Color(0xFF26211A),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

// ── Segmented control ─────────────────────────────────────────────────────

/** One option of [LoomSegmented]. */
data class SegOption<T>(val id: T, val label: String, val icon: ImageVector? = null)

/**
 * `lm-seg`: distinct cells (gap 6) with per-cell hairline borders; the
 * active cell fills with primary-container. Height 40dp.
 */
@Composable
fun <T> LoomSegmented(
    value: T,
    options: List<SegOption<T>>,
    onSelect: (T) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val c = loomColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (option in options) {
            val active = option.id == value
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(LoomShape.RADIUS_CONTROL_DP.dp))
                    .background(if (active) c.primaryContainer else c.surface2)
                    .border(
                        1.dp,
                        if (active) c.primaryContainer else c.hairline,
                        RoundedCornerShape(LoomShape.RADIUS_CONTROL_DP.dp),
                    )
                    .clickable(interactionSource = interaction, indication = null) { onSelect(option.id) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (option.icon != null) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        tint = if (active) c.onPrimaryContainer else c.textMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = option.label,
                    style = loomType.smallStrong,
                    color = if (active) c.onPrimaryContainer else c.text,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── Stepper ───────────────────────────────────────────────────────────────

/**
 * `lm-step`: two 30dp round hairline buttons around a mono value with an
 * optional suffix ("pts", "%"). The readout keeps a stable minimum width
 * so the row never jitters while stepping.
 */
@Composable
fun LoomStepper(
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    step: Int = 1,
    suffix: String? = null,
) {
    val c = loomColors
    Row(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepperButton(IconMinus, enabled = value > min, description = "Decrease") {
            onChange((value - step).coerceIn(min, max))
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(text = "$value", style = loomType.monoCounter, color = c.text, maxLines = 1)
            if (suffix != null) {
                Text(text = " $suffix", style = loomType.monoSmall, color = c.textMuted, maxLines = 1)
            }
        }
        StepperButton(IconPlus, enabled = value < max, description = "Increase") {
            onChange((value + step).coerceIn(min, max))
        }
    }
}

@Composable
private fun StepperButton(
    icon: ImageVector,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val c = loomColors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(c.surface2)
            .border(1.dp, c.hairlineStrong, CircleShape)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = c.text, modifier = Modifier.size(15.dp))
    }
}

// ── Chips ─────────────────────────────────────────────────────────────────

/**
 * `lm-chip`: height 28, radius 8, surface-2 + hairline; optional 7dp class
 * dot / leading icon, removable ×, active fill = primary-container.
 */
@Composable
fun LoomChip(
    label: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    val c = loomColors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(LoomShape.RADIUS_SMALL_DP.dp))
            .background(if (active) c.primaryContainer else c.surface2)
            .border(
                1.dp,
                if (active) c.primaryContainer else c.hairline,
                RoundedCornerShape(LoomShape.RADIUS_SMALL_DP.dp),
            )
            .clickable(interactionSource = interaction, indication = null, enabled = onClick != null) {
                onClick?.invoke()
            }
            .padding(start = 9.dp, end = if (onRemove != null) 4.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(6.dp))
        }
        if (icon != null) {
            Icon(
                icon, contentDescription = null,
                tint = if (active) c.onPrimaryContainer else c.textMuted,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            style = loomType.smallStrong,
            color = if (active) c.onPrimaryContainer else c.text,
            maxLines = 1,
        )
        if (onRemove != null) {
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    IconClose, contentDescription = "Remove $label",
                    tint = if (active) c.onPrimaryContainer else c.textMuted,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/** Label chip shorthand carrying the project's class-color token. */
@Composable
fun LabelChip(
    name: String,
    colorToken: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    LoomChip(
        label = name,
        modifier = modifier,
        dotColor = loomColors.classColor(colorToken),
        active = active,
        onClick = onClick,
        onRemove = onRemove,
    )
}

// ── Meter ─────────────────────────────────────────────────────────────────

enum class LoomMeterTone { ACCENT, GOOD }

/** `lm-meter`: 4dp track (surface-3), rounded fill, animated 240ms. */
@Composable
fun LoomMeter(
    value: Float,
    modifier: Modifier = Modifier,
    tone: LoomMeterTone = LoomMeterTone.ACCENT,
) {
    val c = loomColors
    val animated by animateFloatAsState(
        targetValue = value.coerceIn(0f, 100f),
        animationSpec = tween(LoomMotion.DUR_3, easing = LoomMotion.ease),
        label = "meter",
    )
    val fill = if (tone == LoomMeterTone.GOOD) c.success else c.primary
    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxWidth().height(4.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(color = c.surface3, topLeft = Offset.Zero, size = size, cornerRadius = radius)
        val w = size.width * (animated / 100f)
        if (w > 0f) {
            drawRoundRect(
                color = fill,
                topLeft = Offset.Zero,
                size = Size(maxOf(w, size.height), size.height),
                cornerRadius = radius,
            )
        }
    }
}

// ── Path pill ─────────────────────────────────────────────────────────────

/** `lm-path`: one-line mono readout with a 12dp folder icon, ellipsized. */
@Composable
fun LoomPathPill(path: String, modifier: Modifier = Modifier, iconTint: Color? = null) {
    val c = loomColors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            IconFolder, contentDescription = null,
            tint = iconTint ?: c.textMuted,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = path,
            style = loomType.monoSmall,
            color = c.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Input ─────────────────────────────────────────────────────────────────

/** `lm-input`: surface-2 field, radius 10, hairline, 14sp. */
@Composable
fun LoomInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    maxLength: Int? = null,
    singleLine: Boolean = true,
    numeric: Boolean = false,
) {
    val c = loomColors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(LoomShape.RADIUS_CONTROL_DP.dp))
            .background(c.surface2)
            .border(1.dp, c.hairline, RoundedCornerShape(LoomShape.RADIUS_CONTROL_DP.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, style = loomType.body, color = c.textMuted)
        }
        BasicTextField(
            value = value,
            onValueChange = { next ->
                onValueChange(if (maxLength != null) next.take(maxLength) else next)
            },
            textStyle = loomType.body.copy(color = c.text),
            cursorBrush = SolidColor(c.primary),
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Section label ─────────────────────────────────────────────────────────

/** `lm-seclabel`: 12sp/600 uppercase, letter-spaced, muted. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = loomColors
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text.uppercase(),
            style = loomType.sectionLabel.copy(letterSpacing = 0.96.sp),
            color = c.textMuted,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}
