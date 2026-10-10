package com.testplaybyte.loom.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.testplaybyte.loom.domain.model.ToastIcon
import com.testplaybyte.loom.domain.model.ToastMsg
import com.testplaybyte.loom.ui.icons.IconAlert
import com.testplaybyte.loom.ui.icons.IconCheck
import com.testplaybyte.loom.ui.icons.IconChevronLeft
import com.testplaybyte.loom.ui.icons.IconImages
import com.testplaybyte.loom.ui.icons.IconInfo
import com.testplaybyte.loom.ui.icons.IconMesh
import com.testplaybyte.loom.ui.icons.IconPlus
import com.testplaybyte.loom.ui.icons.IconSave
import com.testplaybyte.loom.ui.icons.IconSettings
import com.testplaybyte.loom.ui.icons.iconLoom
import com.testplaybyte.loom.ui.theme.LoomMotion
import com.testplaybyte.loom.ui.theme.LoomShape
import com.testplaybyte.loom.ui.theme.loomColors
import com.testplaybyte.loom.ui.theme.loomType

/**
 * Loom surfaces: card, row, sheet, screen header, empty action, toast,
 * FAB and the bottom navigation — the structural half of the design
 * system (docs/02 §6), ported from `ui.tsx` + `flow-screens.css`.
 */

// ── Card ──────────────────────────────────────────────────────────────────

/** `lm-card`: surface-1, radius 14, hairline border; tappable → press tint. */
@Composable
fun LoomCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = loomColors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LoomShape.RADIUS_SURFACE_DP.dp))
            .background(if (pressed && onClick != null) c.surface2 else c.surface1)
            .border(1.dp, c.hairline, RoundedCornerShape(LoomShape.RADIUS_SURFACE_DP.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(contentPadding),
        content = content,
    )
}

// ── Row ───────────────────────────────────────────────────────────────────

/**
 * `lm-row`: leading 32dp icon well (surface-2 circle), title 14/500 +
 * optional sub 12 muted, trailing slot; ≥56dp tall.
 */
@Composable
fun LoomRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = loomColors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).background(c.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, contentDescription = null,
                    tint = iconTint ?: c.textMuted,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = loomType.bodyStrong, color = c.text, maxLines = 1)
            if (sub != null) {
                Text(sub, style = loomType.small, color = c.textMuted, maxLines = 2)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

// ── Sheet ─────────────────────────────────────────────────────────────────

/**
 * `lm-sheet`: opaque surface-1 panel, 14dp top radius, NO drag handle,
 * scrim tap dismisses (docs/02 §3 — keep this behavior). Implemented as a
 * custom overlay (not M3's ModalBottomSheet) so the scrim and the 320ms
 * slide match the prototype exactly.
 */
@Composable
fun LoomSheet(
    open: Boolean,
    onClose: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = loomColors
    // The overlay box is always laid out (full-screen, no pointer input of
    // its own), so the scrim and panel can animate both ways; when closed
    // it composes nothing and touches pass straight through.
    Box(modifier = Modifier.fillMaxSize()) {
        // scrim
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(LoomMotion.DUR_SHEET, easing = LoomMotion.ease)),
            exit = fadeOut(tween(LoomMotion.DUR_2, easing = LoomMotion.ease)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(c.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClose() },
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = slideInVertically(
                animationSpec = tween(LoomMotion.DUR_SHEET, easing = LoomMotion.ease),
                initialOffsetY = { it },
            ),
            exit = slideOutVertically(
                animationSpec = tween(LoomMotion.DUR_2, easing = LoomMotion.ease),
                targetOffsetY = { it },
            ),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .clip(RoundedCornerShape(topStart = LoomShape.RADIUS_SURFACE_DP.dp, topEnd = LoomShape.RADIUS_SURFACE_DP.dp))
                    .background(c.surface1)
                    .border(
                        1.dp, c.hairline,
                        RoundedCornerShape(topStart = LoomShape.RADIUS_SURFACE_DP.dp, topEnd = LoomShape.RADIUS_SURFACE_DP.dp),
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = loomType.sheetTitle,
                        color = c.text,
                        modifier = Modifier.weight(1f),
                    )
                    LoomIconButton(com.testplaybyte.loom.ui.icons.IconClose, "Close", onClose)
                }
                Spacer(Modifier.height(6.dp))
                content()
            }
        }
    }
}

// ── Screen header ─────────────────────────────────────────────────────────

/**
 * `lm-head`: floating header — back (when applicable), title 19–22/600 +
 * sub 11.5 muted, trailing actions. Solid-ish bg (86%) so content scrolling
 * under it stays legible without a blur pass.
 */
@Composable
fun LoomScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val c = loomColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bg.copy(alpha = 0.94f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .defaultMinSize(minHeight = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            LoomIconButton(
                icon = IconChevronLeft,
                contentDescription = "Back",
                onClick = onBack,
                iconSize = 20.dp,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = if (onBack != null) 2.dp else 6.dp)) {
            Text(
                text = title,
                style = loomType.screenTitle,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = loomType.small,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actions != null) {
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

// ── Empty action ──────────────────────────────────────────────────────────

/**
 * `lm-empty`: centered 26dp icon in a 56dp surface-2 circle, title 15/600,
 * sub 13 muted, tonal action label. Tapping anywhere runs the action
 * (matches the prototype's button-as-empty-state).
 */
@Composable
fun LoomEmptyAction(
    title: String,
    sub: String,
    actionLabel: String,
    onAction: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val c = loomColors
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onAction)
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(c.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = loomType.title, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(sub, style = loomType.small.copy(fontSize = 13.sp), color = c.textMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(LoomShape.RADIUS_CONTROL_DP.dp))
                .background(c.surface2)
                .border(1.dp, c.hairline, RoundedCornerShape(LoomShape.RADIUS_CONTROL_DP.dp))
                .padding(horizontal = 18.dp, vertical = 11.dp),
        ) {
            Text(actionLabel, style = loomType.buttonSmall, color = c.text)
        }
    }
}

// ── Toast ─────────────────────────────────────────────────────────────────

/**
 * Single-line toast (docs/04 §10): bottom-center pill on surface-4 with an
 * icon, 2600ms lifetime, fade + rise. Rendered by the app shell above the
 * nav/dock.
 */
@Composable
fun LoomToastHost(toast: ToastMsg?, bottomPadding: androidx.compose.ui.unit.Dp = 96.dp) {
    val c = loomColors
    // Keep the last message while the exit animation runs.
    val shown = remember { androidx.compose.runtime.mutableStateOf<ToastMsg?>(null) }
    if (toast != null) shown.value = toast
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(tween(LoomMotion.DUR_2, easing = LoomMotion.ease)) +
                slideInVertically(tween(LoomMotion.DUR_2, easing = LoomMotion.ease)) { it / 2 },
            exit = fadeOut(tween(LoomMotion.DUR_2, easing = LoomMotion.ease)) +
                slideOutVertically(tween(LoomMotion.DUR_2, easing = LoomMotion.ease)) { it / 2 },
        ) {
            Row(
                modifier = Modifier
                    .padding(bottom = bottomPadding)
                    .fillMaxWidth(0.85f)
                    .clip(CircleShape)
                    .background(c.surface4)
                    .border(1.dp, c.hairlineStrong, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                val icon = when (shown.value?.icon) {
                    ToastIcon.CHECK -> IconCheck
                    ToastIcon.MESH -> IconMesh
                    ToastIcon.SAVE -> IconSave
                    ToastIcon.WARN -> IconAlert
                    else -> IconInfo
                }
                Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = shown.value?.text ?: "",
                    style = loomType.smallStrong,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── FAB ───────────────────────────────────────────────────────────────────

/** `lm-fab`: 56dp amber circle with a 24dp plus, spring pop on press. */
@Composable
fun LoomFab(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val c = loomColors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(LoomMotion.DUR_2, easing = LoomMotion.easeSpring),
        label = "fab",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .size(56.dp)
            .clip(CircleShape)
            .background(c.primary)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(IconPlus, contentDescription = contentDescription, tint = c.primaryFg, modifier = Modifier.size(24.dp))
    }
}

// ── Bottom navigation ─────────────────────────────────────────────────────

/** Which nav tab is active. */
enum class LoomTab { PROJECTS, SETTINGS }

/**
 * `LoomNav`: full-width slab (NOT a floating pill), surface-1, hairline
 * top border, 64dp + safe-area inset; active tab = amber pill behind a
 * 22dp icon with an 11sp label beneath.
 */
@Composable
fun LoomBottomNav(
    active: LoomTab,
    onSelect: (LoomTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = loomColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface1)
            .border(1.dp, c.hairline)
            .navigationBarsPadding()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavTab(IconImages, "Projects", active == LoomTab.PROJECTS, Modifier.weight(1f)) {
            onSelect(LoomTab.PROJECTS)
        }
        NavTab(IconSettings, "Settings", active == LoomTab.SETTINGS, Modifier.weight(1f)) {
            onSelect(LoomTab.SETTINGS)
        }
    }
}

@Composable
private fun NavTab(
    icon: ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = loomColors
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 30.dp)
                    .clip(CircleShape)
                    .background(if (active) c.primaryContainer else Color.Transparent),
            )
            Icon(
                icon, contentDescription = label,
                tint = if (active) c.onPrimaryContainer else c.textMuted,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = loomType.small.copy(fontSize = 11.sp),
            color = if (active) c.text else c.textMuted,
        )
    }
}

// ── shared small helpers ──────────────────────────────────────────────────

/** Brand mark composable used by splash + permission header. */
@Composable
fun LoomMark(size: androidx.compose.ui.unit.Dp, strokeWidth: Float, tint: Color) {
    Icon(
        imageVector = iconLoom(strokeWidth),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size),
    )
}

/** Convenience: fade the whole subtree (screen enter/exit). */
@Composable
fun LoomFade(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(LoomMotion.DUR_3, easing = LoomMotion.ease)),
        exit = fadeOut(tween(LoomMotion.DUR_3, easing = LoomMotion.ease)),
    ) { content() }
}
