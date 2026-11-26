package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

@Composable
fun StudyPilotTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
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
