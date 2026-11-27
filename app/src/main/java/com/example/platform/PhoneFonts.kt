package com.example.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.example.R
import com.example.ui.theme.AppFonts

/**
 * The same fonts as the PC app, bundled with the phone app: Manrope for text and Fraunces for page
 * titles. Both are variable fonts; Android 8 and later use the exact weights, older phones the nearest.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun phoneFonts(): AppFonts = remember {
  val text = listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)
    .map { w -> Font(R.font.manrope, weight = w, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight))) }
  // Fraunces at a display optical size: the sharper cut meant for large titles.
  val display = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)
    .map { w -> Font(R.font.fraunces, weight = w, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight), FontVariation.Setting("opsz", 36f))) }
  AppFonts(display = FontFamily(display), text = FontFamily(text))
}
