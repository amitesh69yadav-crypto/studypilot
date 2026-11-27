package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Palette values preserved for unit testing backward-compatibility:
 * Band 1 (Deepest Sage): #8C9F85
 * Band 2 (Medium Sage): #B3C2AC
 * Band 3 (Pale Celadon): #D3DDCF
 * Band 4 (Soft Mint Foam): #DFEAE0
 * Band 5 (Warm Cream Ivory): #FDF8EB
 * Text & High-Contrast: Deep Forest Olive (#212C1E)
 */
val PaletteDeepSage: Color @Composable @ReadOnlyComposable get() = LocalStudyColors.current.deepSage     // Band 1
val PaletteMediumSage: Color @Composable @ReadOnlyComposable get() = LocalStudyColors.current.mediumSage   // Band 2
val PalettePaleCeladon: Color @Composable @ReadOnlyComposable get() = LocalStudyColors.current.paleCeladon  // Band 3
val PaletteMintFoam: Color @Composable @ReadOnlyComposable get() = LocalStudyColors.current.mintFoam     // Band 4
val PaletteCreamIvory: Color @Composable @ReadOnlyComposable get() = LocalStudyColors.current.creamIvory   // Band 5
val PaletteDarkText: Color @Composable @ReadOnlyComposable get() = LocalStudyColors.current.darkText     // Deep Forest Olive

val PaletteMutedText: Color @Composable @ReadOnlyComposable get() = LocalStudyColors.current.mutedText
val PalettePaper: Color @Composable @ReadOnlyComposable get() = LocalStudyColors.current.paper
val PaletteError: Color @Composable @ReadOnlyComposable get() = LocalStudyColors.current.error

// Modern Minimal Light Tokens
val MinimalLightBackground = Color(0xFFF8F9FA)
val MinimalLightSurface = Color(0xFFFFFFFF)
val MinimalLightSurfaceVariant = Color(0xFFF1F4F2)
val MinimalLightPrimary = Color(0xFF1E3A2F)
val MinimalLightOnPrimary = Color(0xFFFFFFFF)
val MinimalLightPrimaryContainer = Color(0xFFE2EFE7)
val MinimalLightOnPrimaryContainer = Color(0xFF0D2E22)
val MinimalLightSecondary = Color(0xFF486355)
val MinimalLightSecondaryContainer = Color(0xFFDCE8DF)
val MinimalLightText = Color(0xFF111827)
val MinimalLightMutedText = Color(0xFF5A6660)
val MinimalLightBorder = Color(0xFFE5EAE6)

// Modern Minimal Dark Tokens (OLED / Deep Midnight)
val MinimalDarkBackground = Color(0xFF0D1117)
val MinimalDarkSurface = Color(0xFF161B22)
val MinimalDarkSurfaceVariant = Color(0xFF21262D)
val MinimalDarkPrimary = Color(0xFF4ADE80)
val MinimalDarkOnPrimary = Color(0xFF052E16)
val MinimalDarkPrimaryContainer = Color(0xFF143825)
val MinimalDarkOnPrimaryContainer = Color(0xFF86EFAC)
val MinimalDarkSecondary = Color(0xFF94A3B8)
val MinimalDarkSecondaryContainer = Color(0xFF1E293B)
val MinimalDarkText = Color(0xFFF1F5F9)
val MinimalDarkMutedText = Color(0xFF94A3B8)
val MinimalDarkBorder = Color(0xFF30363D)

// Semantic status colors with crisp modern vibrancy
val CyanAccent = Color(0xFF0EA5E9)
val AmberAccent = Color(0xFFF59E0B)
val EmeraldSuccess = Color(0xFF10B981)
val RoseError = Color(0xFFEF4444)
val PurpleAccent = Color(0xFF8B5CF6)

// Semantic UI aliases
val PaletteMint: Color @Composable @ReadOnlyComposable get() = PaletteMintFoam
val PaletteParchment: Color @Composable @ReadOnlyComposable get() = PalettePaleCeladon
val PalettePeachTan: Color @Composable @ReadOnlyComposable get() = PaletteMediumSage
val PaletteCoral: Color @Composable @ReadOnlyComposable get() = PaletteDeepSage
val PaletteWarmTerracotta: Color @Composable @ReadOnlyComposable get() = PaletteDeepSage
val PaletteCreamOffWhite: Color @Composable @ReadOnlyComposable get() = PaletteCreamIvory
val PaletteWarmDark: Color @Composable @ReadOnlyComposable get() = PaletteDarkText
val PaletteDeepNavy: Color @Composable @ReadOnlyComposable get() = PaletteDarkText
val PrimarySage: Color @Composable @ReadOnlyComposable get() = PaletteDeepSage
val SecondarySage: Color @Composable @ReadOnlyComposable get() = PaletteMediumSage
val TertiaryCeladon: Color @Composable @ReadOnlyComposable get() = PalettePaleCeladon
val SurfaceMint: Color @Composable @ReadOnlyComposable get() = PaletteMintFoam
val BackgroundIvory: Color @Composable @ReadOnlyComposable get() = PaletteCreamIvory
val BluePrimaryLight: Color @Composable @ReadOnlyComposable get() = PaletteDeepSage
val BluePrimaryDark: Color @Composable @ReadOnlyComposable get() = PaletteDeepSage
val BlueContainerLight: Color @Composable @ReadOnlyComposable get() = PaletteMintFoam
val BlueContainerDark: Color @Composable @ReadOnlyComposable get() = PalettePaleCeladon
val Navy900: Color @Composable @ReadOnlyComposable get() = PaletteDarkText
val Navy800: Color @Composable @ReadOnlyComposable get() = PaletteDarkText
val Navy700: Color @Composable @ReadOnlyComposable get() = PaletteDarkText
val Navy50: Color @Composable @ReadOnlyComposable get() = PaletteCreamIvory
val Navy100: Color @Composable @ReadOnlyComposable get() = PaletteMintFoam