package com.freease.music.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@Immutable
data class AccentPalette(val name: String, val primary: Color, val secondary: Color) {
    val brush: Brush get() = Brush.linearGradient(listOf(primary, secondary))
}

val Accents = listOf(
    AccentPalette("Aurora", Color(0xFF8B5CF6), Color(0xFFEC4899)),
    AccentPalette("Mint", Color(0xFF10D9A0), Color(0xFF22B8EE)),
    AccentPalette("Sunset", Color(0xFFFF8A3D), Color(0xFFFF3D71)),
    AccentPalette("Ocean", Color(0xFF3B82F6), Color(0xFF06D6E0)),
    AccentPalette("Lime", Color(0xFFA3E635), Color(0xFF22C55E)),
    AccentPalette("Rose", Color(0xFFF43F5E), Color(0xFFF59E0B)),
)

/** Warna khas Freease di luar ColorScheme Material. */
@Immutable
data class FreeaseColors(
    val accent: AccentPalette,
    val background: Color,
    val elevated: Color,
    val card: Color,
    val muted: Color,
    val divider: Color,
)

val LocalFreease = staticCompositionLocalOf {
    FreeaseColors(Accents[0], Color(0xFF0A0A10), Color(0xFF15151E), Color(0xFF1C1C28), Color(0xFF9C9CB0), Color(0xFF262634))
}

object Freease {
    val colors: FreeaseColors @Composable get() = LocalFreease.current
    val accent: AccentPalette @Composable get() = LocalFreease.current.accent
}

private val Base = Typography()

private val FreeaseTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.03).em),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.03).em),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

val SectionTitleStyle = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em)

@Composable
fun FreeaseTheme(accentIndex: Int, amoled: Boolean, content: @Composable () -> Unit) {
    val accent = Accents.getOrElse(accentIndex) { Accents[0] }
    val primary by animateColorAsState(accent.primary, tween(500), label = "primary")
    val secondary by animateColorAsState(accent.secondary, tween(500), label = "secondary")
    val bg = if (amoled) Color.Black else Color(0xFF0A0A10)
    val elevated = if (amoled) Color(0xFF0E0E12) else Color(0xFF15151E)
    val card = if (amoled) Color(0xFF15151A) else Color(0xFF1C1C28)

    val scheme = darkColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = primary.copy(alpha = 0.22f),
        onPrimaryContainer = Color.White,
        secondary = secondary,
        onSecondary = Color.White,
        tertiary = secondary,
        background = bg,
        onBackground = Color(0xFFF4F4F8),
        surface = bg,
        onSurface = Color(0xFFF4F4F8),
        surfaceVariant = card,
        onSurfaceVariant = Color(0xFFA4A4B8),
        surfaceContainerLowest = bg,
        surfaceContainerLow = elevated,
        surfaceContainer = elevated,
        surfaceContainerHigh = card,
        surfaceContainerHighest = Color(0xFF262634),
        outline = Color(0xFF3A3A4C),
        outlineVariant = Color(0xFF262634),
        error = Color(0xFFFF5370),
    )
    val colors = FreeaseColors(
        accent = AccentPalette(accent.name, primary, secondary),
        background = bg,
        elevated = elevated,
        card = card,
        muted = Color(0xFF9C9CB0),
        divider = Color(0xFF262634),
    )
    CompositionLocalProvider(LocalFreease provides colors) {
        MaterialTheme(colorScheme = scheme, typography = FreeaseTypography, content = content)
    }
}
