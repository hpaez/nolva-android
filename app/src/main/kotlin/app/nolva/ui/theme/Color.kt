package app.nolva.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Nolva "Arcilla" palette. Source of truth: the brand design system
 * (claude/identidad-nolva.md in the Nolva project). Keep both in sync.
 */
@Immutable
data class NolvaColors(
    val clay: Color,
    val clayStrong: Color,
    val clayTint: Color,
    val onClay: Color,
    val sage: Color,
    val sageText: Color,
    val surface100: Color,
    val surface200: Color,
    val surface300: Color,
    val border: Color,
    val borderStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    val danger: Color,
    val onDanger: Color,
)

internal val LightNolvaColors = NolvaColors(
    clay = Color(0xFFB84E2A),
    clayStrong = Color(0xFF9C3F20),
    clayTint = Color(0xFFF6E3D8),
    onClay = Color(0xFFFFFFFF),
    sage = Color(0xFF5E7F62),
    sageText = Color(0xFF4A6A4E),
    surface100 = Color(0xFFFBF6EE),
    surface200 = Color(0xFFFFFFFF),
    surface300 = Color(0xFFF2E9DC),
    border = Color(0xFFE6D9C6),
    borderStrong = Color(0xFF8C7666),
    ink = Color(0xFF3A2A22),
    inkMuted = Color(0xFF6E5A4E),
    danger = Color(0xFFB3261E),
    onDanger = Color(0xFFFFFFFF),
)

internal val DarkNolvaColors = NolvaColors(
    clay = Color(0xFFE07A52),
    clayStrong = Color(0xFFEB9474),
    clayTint = Color(0xFF3A2620),
    onClay = Color(0xFF1E1714),
    sage = Color(0xFF94B897),
    sageText = Color(0xFF94B897),
    surface100 = Color(0xFF1E1714),
    surface200 = Color(0xFF2A201C),
    surface300 = Color(0xFF352923),
    border = Color(0xFF3E302A),
    borderStrong = Color(0xFF8A7466),
    ink = Color(0xFFF5ECE1),
    inkMuted = Color(0xFFC2B2A3),
    danger = Color(0xFFF2867E),
    onDanger = Color(0xFF1E1714),
)
