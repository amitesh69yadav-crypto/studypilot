package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

/**
 * Modern Minimal Light Theme
 */
private val ModernLightColorScheme = lightColorScheme(
  primary = MinimalLightPrimary,
  onPrimary = MinimalLightOnPrimary,
  primaryContainer = MinimalLightPrimaryContainer,
  onPrimaryContainer = MinimalLightOnPrimaryContainer,
  secondary = MinimalLightSecondary,
  onSecondary = Color.White,
  secondaryContainer = MinimalLightSecondaryContainer,
  onSecondaryContainer = MinimalLightPrimary,
  tertiary = Color(0xFF2563EB),
  onTertiary = Color.White,
  tertiaryContainer = Color(0xFFDBEAFE),
  onTertiaryContainer = Color(0xFF1E40AF),
  background = MinimalLightBackground,
  onBackground = MinimalLightText,
  surface = MinimalLightSurface,
  onSurface = MinimalLightText,
  surfaceVariant = MinimalLightSurfaceVariant,
  onSurfaceVariant = MinimalLightMutedText,
  surfaceContainerLowest = MinimalLightSurface,
  surfaceContainerLow = Color(0xFFFAFBFA),
  surfaceContainer = Color(0xFFF3F5F4),
  surfaceContainerHigh = Color(0xFFEBEEEC),
  surfaceContainerHighest = Color(0xFFE2E6E3),
  outline = Color(0xFFCCD4CE),
  outlineVariant = MinimalLightBorder,
  error = Color(0xFFDC2626),
  onError = Color.White,
  errorContainer = Color(0xFFFEE2E2),
  onErrorContainer = Color(0xFF991B1B)
)

/**
 * Modern Minimal Dark Theme (True OLED / Deep Midnight)
 */
private val ModernDarkColorScheme = darkColorScheme(
  primary = MinimalDarkPrimary,
  onPrimary = MinimalDarkOnPrimary,
  primaryContainer = MinimalDarkPrimaryContainer,
  onPrimaryContainer = MinimalDarkOnPrimaryContainer,
  secondary = MinimalDarkSecondary,
  onSecondary = Color(0xFF0F172A),
  secondaryContainer = MinimalDarkSecondaryContainer,
  onSecondaryContainer = Color(0xFFE2E8F0),
  tertiary = Color(0xFF38BDF8),
  onTertiary = Color(0xFF082F49),
  tertiaryContainer = Color(0xFF0C4A6E),
  onTertiaryContainer = Color(0xFFBAE6FD),
  background = MinimalDarkBackground,
  onBackground = MinimalDarkText,
  surface = MinimalDarkSurface,
  onSurface = MinimalDarkText,
  surfaceVariant = MinimalDarkSurfaceVariant,
  onSurfaceVariant = MinimalDarkMutedText,
  surfaceContainerLowest = Color(0xFF080B0F),
  surfaceContainerLow = Color(0xFF10141B),
  surfaceContainer = Color(0xFF191E27),
  surfaceContainerHigh = Color(0xFF222934),
  surfaceContainerHighest = Color(0xFF2B3341),
  outline = Color(0xFF30363D),
  outlineVariant = MinimalDarkBorder,
  error = Color(0xFFF87171),
  onError = Color(0xFF450A0A),
  errorContainer = Color(0xFF450A0A),
  onErrorContainer = Color(0xFFFECACA)
)

/** Glass, light: see-through surfaces over the backdrop, opaque frost for popups, near-black primary. */
private fun glassScheme(g: GlassColors, accent: Color) = (if (g.dark) darkColorScheme() else lightColorScheme()).copy(
  primary = g.ink,
  onPrimary = g.onInk,
  primaryContainer = g.selected,
  onPrimaryContainer = g.text,
  secondary = accent,
  onSecondary = g.onInk,
  secondaryContainer = g.selected,
  onSecondaryContainer = g.text,
  tertiary = accent,
  onTertiary = g.onInk,
  tertiaryContainer = g.inset,
  onTertiaryContainer = g.text,
  background = Color.Transparent,
  onBackground = g.text,
  surface = g.fill,
  onSurface = g.text,
  surfaceVariant = g.inset,
  onSurfaceVariant = g.muted,
  surfaceTint = Color.Transparent,
  surfaceContainerLowest = g.fill,
  surfaceContainerLow = g.fill,
  surfaceContainer = g.frost,
  surfaceContainerHigh = g.frost,
  surfaceContainerHighest = g.frost,
  surfaceBright = g.frost,
  surfaceDim = g.frost,
  outline = if (g.dark) Color(0x33F7F7F3) else Color(0x331E281E),
  outlineVariant = if (g.dark) Color(0x1AF7F7F3) else Color(0x1F1E281E),
  error = if (g.dark) Color(0xFFE06A55) else Color(0xFFC7452F),
  onError = Color.White,
  errorContainer = if (g.dark) Color(0x40E06A55) else Color(0x33C7452F),
  onErrorContainer = g.text,
  scrim = Color(0x66000000)
)

/** The glass type scale: serif page titles, tight medium headings, small tracked labels. */
private fun glassTypography(fonts: AppFonts?): androidx.compose.material3.Typography {
  val sans = fonts?.text ?: FontFamily.Default
  val serif = fonts?.display ?: FontFamily.Serif
  fun t(size: Int, line: Int, weight: FontWeight, family: FontFamily = sans, tracking: Double = 0.0) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = (size * tracking).sp)
  return androidx.compose.material3.Typography(
    displayLarge = t(44, 50, FontWeight.Normal, serif, -0.01),
    displayMedium = t(38, 44, FontWeight.Normal, serif, -0.01),
    displaySmall = t(34, 40, FontWeight.Normal, serif, -0.01),
    headlineLarge = t(30, 36, FontWeight.Normal, serif, -0.01),
    headlineMedium = t(26, 32, FontWeight.Medium, sans, -0.045),
    headlineSmall = t(22, 28, FontWeight.Medium, sans, -0.045),
    titleLarge = t(20, 26, FontWeight.Medium, sans, -0.045),
    titleMedium = t(16, 22, FontWeight.SemiBold, sans, -0.01),
    titleSmall = t(14, 20, FontWeight.SemiBold, sans),
    bodyLarge = t(15, 23, FontWeight.Normal, sans),
    bodyMedium = t(14, 20, FontWeight.Normal, sans),
    bodySmall = t(13, 18, FontWeight.Normal, sans),
    labelLarge = t(13, 18, FontWeight.SemiBold, sans),
    labelMedium = t(12, 16, FontWeight.SemiBold, sans),
    labelSmall = t(11, 15, FontWeight.Bold, sans, 0.06)
  )
}

@Composable
fun StudyPilotTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  /** [AppStyle.Glass] on the desktop; the phone keeps [AppStyle.Classic]. */
  style: AppStyle = AppStyle.Classic,
  /** Glass only: the fonts to use (the desktop passes system fonts). */
  fonts: AppFonts? = null,
  content: @Composable () -> Unit,
) {
  if (style == AppStyle.Glass) {
    val glass = if (darkTheme) DarkGlass else LightGlass
    val study = if (darkTheme) DarkGlassStudyColors else LightGlassStudyColors
    CompositionLocalProvider(
      LocalAppStyle provides style,
      LocalGlass provides glass,
      LocalStudyColors provides study
    ) {
      MaterialTheme(
        colorScheme = glassScheme(glass, study.deepSage),
        typography = glassTypography(fonts),
        shapes = GlassShapes
      ) {
        // MaterialTheme sets the ripple as the default; the glass tint replaces it inside.
        CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides GlassIndication(glass.dark), content = content)
      }
    }
    return
  }
  val colorScheme = if (darkTheme) ModernDarkColorScheme else ModernLightColorScheme
  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit
) = StudyPilotTheme(darkTheme = darkTheme, dynamicColor = false, content = content)
