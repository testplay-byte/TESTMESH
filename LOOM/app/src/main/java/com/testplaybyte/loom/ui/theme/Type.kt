package com.testplaybyte.loom.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.testplaybyte.loom.R

/**
 * Typography — the defining trait of the Loom design language
 * (docs/02-design-system.md §2):
 *
 *  · Space Grotesk (400/500/600/700) for EVERYTHING UI;
 *  · JetBrains Mono (400/500/600) for every numeric / path / file-name
 *    readout — counts, percentages, zoom %, coordinates, "N pts".
 *
 * Both ship as variable TTFs in res/font; each weight is declared as its
 * own [Font] entry carrying a `FontVariation` weight axis setting
 * (minSdk 26 supports variation settings).
 */

@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: Int) = Font(
    resId = R.font.space_grotesk,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun monoFont(weight: Int) = Font(
    resId = R.font.jetbrains_mono,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** UI family — Space Grotesk (400/500/600/700). */
val LoomDisplay = FontFamily(
    grotesk(400), grotesk(500), grotesk(600), grotesk(700),
)

/** Data-readout family — JetBrains Mono (400/500/600). */
val LoomMono = FontFamily(
    monoFont(400), monoFont(500), monoFont(600),
)

/**
 * The Loom type scale. Sizes come from the prototype CSS
 * (docs/02 §2 "Typical sizes"), mapped to `sp`.
 */
@Immutable
data class LoomTypeScale(
    /** Screen title — 22sp/700. */
    val screenTitle: TextStyle = TextStyle(
        fontFamily = LoomDisplay, fontWeight = FontWeight.W700,
        fontSize = 22.sp, lineHeight = 28.sp,
    ),
    /** Sheet title — 18sp/700. */
    val sheetTitle: TextStyle = TextStyle(
        fontFamily = LoomDisplay, fontWeight = FontWeight.W700,
        fontSize = 18.sp, lineHeight = 24.sp,
    ),
    /** Section eyebrow — 12sp/600, letter-spaced, uppercase, muted. */
    val sectionLabel: TextStyle = TextStyle(
        fontFamily = LoomDisplay, fontWeight = FontWeight.W600,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.96.sp,
    ),
    /** Card/list title — 15sp/600. */
    val title: TextStyle = TextStyle(
        fontFamily = LoomDisplay, fontWeight = FontWeight.W600,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    /** Body — 14sp/400. */
    val body: TextStyle = TextStyle(
        fontFamily = LoomDisplay, fontWeight = FontWeight.W400,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    /** Body emphasis — 14sp/500. */
    val bodyStrong: TextStyle = TextStyle(
        fontFamily = LoomDisplay, fontWeight = FontWeight.W500,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    /** Small copy — 12sp/400. */
    val small: TextStyle = TextStyle(
        fontFamily = LoomDisplay, fontWeight = FontWeight.W400,
        fontSize = 12.sp, lineHeight = 17.sp,
    ),
    /** Small emphasis — 12sp/500. */
    val smallStrong: TextStyle = TextStyle(
        fontFamily = LoomDisplay, fontWeight = FontWeight.W500,
        fontSize = 12.sp, lineHeight = 17.sp,
    ),
    /** Button label — 14sp/600. */
    val button: TextStyle = TextStyle(
        fontFamily = LoomDisplay, fontWeight = FontWeight.W600,
        fontSize = 14.sp, lineHeight = 18.sp,
    ),
    /** Small button label — 13sp/600. */
    val buttonSmall: TextStyle = TextStyle(
        fontFamily = LoomDisplay, fontWeight = FontWeight.W600,
        fontSize = 13.sp, lineHeight = 16.sp,
    ),
    // ── mono readouts ──────────────────────────────────────────────────
    /** Header counters — 12–13sp mono. */
    val monoCounter: TextStyle = TextStyle(
        fontFamily = LoomMono, fontWeight = FontWeight.W600,
        fontSize = 13.sp, lineHeight = 16.sp,
    ),
    /** Path pill / file names — 11sp mono. */
    val monoSmall: TextStyle = TextStyle(
        fontFamily = LoomMono, fontWeight = FontWeight.W400,
        fontSize = 11.sp, lineHeight = 15.sp,
    ),
    /** Small mono chips (format tag) — 10sp mono. */
    val monoChip: TextStyle = TextStyle(
        fontFamily = LoomMono, fontWeight = FontWeight.W500,
        fontSize = 10.sp, lineHeight = 13.sp,
    ),
    /** Big stat numbers — 20sp mono/600. */
    val monoBig: TextStyle = TextStyle(
        fontFamily = LoomMono, fontWeight = FontWeight.W600,
        fontSize = 20.sp, lineHeight = 24.sp,
    ),
    /** Stat numbers — 12sp mono. */
    val monoStat: TextStyle = TextStyle(
        fontFamily = LoomMono, fontWeight = FontWeight.W500,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
    /** Status pill — 11sp mono. */
    val monoPill: TextStyle = TextStyle(
        fontFamily = LoomMono, fontWeight = FontWeight.W500,
        fontSize = 11.sp, lineHeight = 14.sp,
    ),
)

/** Material Typography minimally mapped so M3 defaults inherit Loom type. */
fun loomMaterialTypography(scale: LoomTypeScale = LoomTypeScale()): Typography =
    Typography(
        headlineMedium = scale.screenTitle,
        titleMedium = scale.title,
        bodyMedium = scale.body,
        bodySmall = scale.small,
        labelMedium = scale.smallStrong,
        labelSmall = scale.monoChip,
    )
