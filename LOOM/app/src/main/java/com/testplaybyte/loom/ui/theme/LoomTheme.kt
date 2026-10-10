package com.testplaybyte.loom.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Loom theme — the fidelity layer (docs/06-android-guide.md §2).
 *
 * Provides [LoomColors] through a CompositionLocal and maps a minimal
 * Material 3 color scheme so any M3 widget (sheets, ripples) inherits
 * Loom tones. Everything visible is styled with Loom tokens — M3 defaults
 * are a fallback, never the look.
 *
 * Theme selection is app-scoped and persisted; dark is the default.
 */

/** Which Loom theme is active (persisted in settings). */
enum class LoomThemeMode { DARK, LIGHT }

val LocalLoomType = staticCompositionLocalOf { LoomTypeScale() }

@Composable
fun LoomTheme(
    mode: LoomThemeMode = LoomThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    // `isSystemInDarkTheme` is deliberately unused: Loom's theme is an
    // in-app setting, not a system follow (docs/02 §7).
    @Suppress("UNUSED_VARIABLE")
    val systemDark = isSystemInDarkTheme()

    val colors = if (mode == LoomThemeMode.DARK) LoomDark else LoomLight
    val type = LoomTypeScale()

    val scheme = if (mode == LoomThemeMode.DARK) {
        darkColorScheme(
            background = colors.bg,
            surface = colors.surface1,
            onBackground = colors.text,
            onSurface = colors.text,
            primary = colors.primary,
            onPrimary = colors.primaryFg,
            primaryContainer = colors.primaryContainer,
            onPrimaryContainer = colors.onPrimaryContainer,
            secondary = colors.secondary,
            tertiary = colors.tertiary,
            error = colors.error,
            outline = colors.outline,
            outlineVariant = colors.outlineVariant,
            scrim = colors.scrim,
        )
    } else {
        lightColorScheme(
            background = colors.bg,
            surface = colors.surface1,
            onBackground = colors.text,
            onSurface = colors.text,
            primary = colors.primary,
            onPrimary = colors.primaryFg,
            primaryContainer = colors.primaryContainer,
            onPrimaryContainer = colors.onPrimaryContainer,
            secondary = colors.secondary,
            tertiary = colors.tertiary,
            error = colors.error,
            outline = colors.outline,
            outlineVariant = colors.outlineVariant,
            scrim = colors.scrim,
        )
    }

    // System bars: transparent, icon contrast follows the theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = mode == LoomThemeMode.LIGHT
                isAppearanceLightNavigationBars = mode == LoomThemeMode.LIGHT
            }
        }
    }

    CompositionLocalProvider(
        LocalLoomColors provides colors,
        LocalLoomType provides type,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = loomMaterialTypography(type),
            content = content,
        )
    }
}

/** Current Loom color tokens. */
val loomColors: LoomColors
    @Composable @ReadOnlyComposable get() = LocalLoomColors.current

/** Current Loom type scale. */
val loomType: LoomTypeScale
    @Composable @ReadOnlyComposable get() = LocalLoomType.current

/** Window background helper for the root Scaffold (`bg` token). */
@Suppress("unused")
internal val LoomColors.windowArgb: Int get() = bg.toArgb()
