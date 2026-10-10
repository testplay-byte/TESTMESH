package com.testplaybyte.loom.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Loom color tokens — the fidelity contract (docs/02-design-system.md,
 * reference/tokens.json). Values are exact; dark is the default theme and
 * light is tuned per-theme (never derived programmatically).
 *
 * Naming mirrors the docs' token names so lookups stay direct:
 * bg / surface1..5 / text / textMuted / primary / … plus the Loom extras
 * (pos, neg, mesh, meshSoft, hairline, hairlineStrong, scrim, sheen,
 * grid, canvas) and the categorical class palette c1..c8.
 */
@Immutable
data class LoomColors(
    // ── Material-ish roles ─────────────────────────────────────────────
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surface4: Color,
    val surface5: Color,
    val text: Color,
    val textMuted: Color,
    val primary: Color,
    val primaryFg: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val tertiary: Color,
    val error: Color,
    val success: Color,
    val outline: Color,
    val outlineVariant: Color,
    // ── Loom extras ────────────────────────────────────────────────────
    /** Positive prompt dots + "done" status. */
    val pos: Color,
    /** Negative prompt dots + danger accents. */
    val neg: Color,
    /** Mesh stroke + vertex handles. */
    val mesh: Color,
    /** Mesh fill wash. */
    val meshSoft: Color,
    /** 1dp borders everywhere. */
    val hairline: Color,
    /** Emphasized hairline. */
    val hairlineStrong: Color,
    /** Sheet/dialog scrim. */
    val scrim: Color,
    /** Subtle top sheen on raised surfaces. */
    val sheen: Color,
    /** Canvas drafting grid. */
    val grid: Color,
    /** Canvas background (darker than bg in dark theme). */
    val canvas: Color,
    /** Categorical class colors c1..c8 (index 0 = c1). */
    val classColors: List<Color>,
) {
    /** Class color by token name ("c1".."c8"); falls back to c1. */
    fun classColor(token: String): Color {
        val idx = token.removePrefix("c").toIntOrNull()?.minus(1) ?: 0
        return classColors[idx.coerceIn(0, classColors.lastIndex)]
    }
}

/** Dark theme — the default and primary language (docs/02 §1). */
val LoomDark = LoomColors(
    bg = Color(0xFF191613),
    surface1 = Color(0xFF211D18),
    surface2 = Color(0xFF292420),
    surface3 = Color(0xFF322C25),
    surface4 = Color(0xFF3C352C),
    surface5 = Color(0xFF474034),
    text = Color(0xFFF0EBE1),
    textMuted = Color(0xFFABA396),
    primary = Color(0xFFFFB224),
    primaryFg = Color(0xFF241A05),
    primaryContainer = Color(0xFF5C4310),
    onPrimaryContainer = Color(0xFFFFD9A0),
    secondary = Color(0xFFC9BFA8),
    tertiary = Color(0xFF8FAF7C),
    error = Color(0xFFFF6B5E),
    success = Color(0xFF5FD977),
    outline = Color(0xFF8F8677),
    outlineVariant = Color(0xFF4A4438),
    pos = Color(0xFF5FD977),
    neg = Color(0xFFFF6B5E),
    mesh = Color(0xFFFFB224),
    meshSoft = Color(0xFFFFB224).copy(alpha = 0.12f),
    hairline = Color(0xFFF0EBE1).copy(alpha = 0.09f),
    hairlineStrong = Color(0xFFF0EBE1).copy(alpha = 0.16f),
    scrim = Color(0xFF0C0A07).copy(alpha = 0.58f),
    sheen = Color(0xFFFFFFFF).copy(alpha = 0.05f),
    grid = Color(0xFFF0EBE1).copy(alpha = 0.055f),
    canvas = Color(0xFF100E0B),
    classColors = listOf(
        Color(0xFFFFB224), // c1 amber
        Color(0xFF5FD977), // c2 mint
        Color(0xFFFF8A7A), // c3 coral
        Color(0xFFD9C08A), // c4 sand
        Color(0xFF8FBF6F), // c5 leaf
        Color(0xFFC9935B), // c6 copper
        Color(0xFFA8A06B), // c7 olive
        Color(0xFFE0A9C2), // c8 rose
    ),
)

/** Light theme — "warm paper, deep amber" (docs/02 §1). */
val LoomLight = LoomColors(
    bg = Color(0xFFF2EDE2),
    surface1 = Color(0xFFFAF6EC),
    surface2 = Color(0xFFEDE6D6),
    surface3 = Color(0xFFE2D9C4),
    surface4 = Color(0xFFD5CBB2),
    surface5 = Color(0xFFC6BA9E),
    text = Color(0xFF26211A),
    textMuted = Color(0xFF6E6555),
    primary = Color(0xFF8A5B08),
    primaryFg = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF0DDB4),
    onPrimaryContainer = Color(0xFF4E3606),
    secondary = Color(0xFF6E6555),
    tertiary = Color(0xFF587A47),
    error = Color(0xFFB3342A),
    success = Color(0xFF1E7C3A),
    outline = Color(0xFF857B69),
    outlineVariant = Color(0xFFCDC4AE),
    pos = Color(0xFF1E7C3A),
    neg = Color(0xFFB3342A),
    mesh = Color(0xFFB97E00),
    meshSoft = Color(0xFFB97E00).copy(alpha = 0.12f),
    hairline = Color(0xFF26211A).copy(alpha = 0.11f),
    hairlineStrong = Color(0xFF26211A).copy(alpha = 0.20f),
    scrim = Color(0xFF2E271C).copy(alpha = 0.42f),
    sheen = Color(0xFFFFFFFF).copy(alpha = 0.5f),
    grid = Color(0xFF26211A).copy(alpha = 0.06f),
    canvas = Color(0xFFDCD5C2),
    classColors = listOf(
        Color(0xFF8A5B08), // c1 amber
        Color(0xFF1E7C3A), // c2 mint
        Color(0xFFB3342A), // c3 coral
        Color(0xFF7A683D), // c4 sand
        Color(0xFF4E7A33), // c5 leaf
        Color(0xFF8F5B26), // c6 copper
        Color(0xFF6B6636), // c7 olive
        Color(0xFFA84E74), // c8 rose
    ),
)

/** Stable dark screenshot-safe preview colors when no theme is provided. */
val LocalLoomColors = staticCompositionLocalOf { LoomDark }
