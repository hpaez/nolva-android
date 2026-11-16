package app.nolva.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalNolvaColors = staticCompositionLocalOf { LightNolvaColors }

/**
 * App theme. Dynamic color (Material You) is intentionally not used: the
 * Arcilla palette is part of the brand.
 */
@Composable
fun NolvaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkNolvaColors else LightNolvaColors
    CompositionLocalProvider(LocalNolvaColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(darkTheme),
            typography = NolvaTypography,
            shapes = NolvaShapes,
            content = content,
        )
    }
}

/** Brand tokens that have no Material 3 slot (sage, ink-muted, borders...). */
object NolvaTheme {
    val colors: NolvaColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNolvaColors.current
}

private fun NolvaColors.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = clay,
        onPrimary = onClay,
        primaryContainer = clayTint,
        onPrimaryContainer = clayStrong,
        inversePrimary = clayStrong,
        secondary = sage,
        onSecondary = onClay,
        secondaryContainer = surface300,
        onSecondaryContainer = sageText,
        tertiary = clayStrong,
        onTertiary = onClay,
        background = surface100,
        onBackground = ink,
        surface = surface100,
        onSurface = ink,
        surfaceVariant = surface300,
        onSurfaceVariant = inkMuted,
        surfaceTint = clay,
        surfaceBright = surface200,
        surfaceDim = surface300,
        surfaceContainerLowest = surface200,
        surfaceContainerLow = surface200,
        surfaceContainer = surface200,
        surfaceContainerHigh = surface300,
        surfaceContainerHighest = surface300,
        outline = borderStrong,
        outlineVariant = border,
        error = danger,
        onError = onDanger,
    )
}
