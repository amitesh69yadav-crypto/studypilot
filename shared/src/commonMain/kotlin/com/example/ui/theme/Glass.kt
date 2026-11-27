package com.example.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * How the app looks. [Classic] is the phone's look. [Glass] is the desktop's: frosted surfaces over a
 * soft painted backdrop, a serif for page titles, pill controls and near-black primary buttons.
 */
enum class AppStyle { Classic, Glass }

val LocalAppStyle = staticCompositionLocalOf { AppStyle.Classic }

/** The painted background behind the glass. Each one is light or dark. */
enum class Backdrop(val label: String, val dark: Boolean) {
  OliveMist("Olive Mist", false),
  SoftNeutral("Soft Neutral", false),
  MinimalSolid("Minimal Solid", false),
  DarkForest("Dark Forest", true),
  Midnight("Midnight", true);

  companion object {
    fun byName(name: String?, fallback: Backdrop): Backdrop = entries.firstOrNull { it.name == name } ?: fallback
  }
}

/** Fonts for the glass style: a serif for page titles, a clean sans for everything else. */
@Immutable
data class AppFonts(val display: FontFamily, val text: FontFamily)

/** Colours of glass surfaces; null in the classic style. */
@Immutable
data class GlassColors(
  val dark: Boolean,
  /** Frosted card fill. */
  val fill: Color,
  /** Top and bottom of the light rim around a card. */
  val rimTop: Color,
  val rimBottom: Color,
  /** Recessed fill for inputs, tracks and secondary buttons. */
  val inset: Color,
  val insetRim: Color,
  /** Opaque frost for popups and dialogs, which can't be see-through. */
  val frost: Color,
  /** The selected pill in a segmented control or the sidebar. */
  val selected: Color,
  val text: Color,
  val muted: Color,
  /** Near-black (or near-white) pill for the main action. */
  val ink: Color,
  val onInk: Color
)

val LightGlass = GlassColors(
  dark = false,
  fill = Color(0x8FF9FAF5),
  rimTop = Color(0xB3FFFFFF),
  rimBottom = Color(0x4DFFFFFF),
  inset = Color(0x6BFFFFFF),
  insetRim = Color(0x99FFFFFF),
  frost = Color(0xFFF4F5EF),
  selected = Color(0xD9FBFBF7),
  text = Color(0xFF1D2521),
  muted = Color(0xFF46534A),
  ink = Color(0xFF141917),
  onInk = Color(0xFFF7F8F3)
)

val DarkGlass = GlassColors(
  dark = true,
  fill = Color(0x8F0E1612),
  rimTop = Color(0x2EF7F7F3),
  rimBottom = Color(0x0FF7F7F3),
  inset = Color(0x0FF7F7F3),
  insetRim = Color(0x1AF7F7F3),
  frost = Color(0xFF121A16),
  selected = Color(0x2EF7F7F3),
  text = Color(0xFFEDF2F0),
  muted = Color(0xFFA9BDB3),
  ink = Color(0xFFEDF2F0),
  onInk = Color(0xFF0B1210)
)

val LocalGlass = staticCompositionLocalOf<GlassColors?> { null }

/**
 * The sage palette the timetable and chat screens are drawn with. On the phone these are the original
 * fixed colours; on the desktop they follow the glass theme, light or dark.
 */
@Immutable
data class StudyColors(
  val deepSage: Color,
  val mediumSage: Color,
  val paleCeladon: Color,
  val mintFoam: Color,
  val creamIvory: Color,
  val darkText: Color,
  val mutedText: Color,
  val paper: Color,
  val error: Color
)

val ClassicStudyColors = StudyColors(
  deepSage = Color(0xFF8C9F85),
  mediumSage = Color(0xFFB3C2AC),
  paleCeladon = Color(0xFFD3DDCF),
  mintFoam = Color(0xFFDFEAE0),
  creamIvory = Color(0xFFFDF8EB),
  darkText = Color(0xFF212C1E),
  mutedText = Color(0xFF5E6858),
  paper = Color(0xFFFFFDF7),
  error = Color(0xFFB3261E)
)

val LightGlassStudyColors = StudyColors(
  deepSage = Color(0xFF2C5A45),
  mediumSage = Color(0x8CFFFFFF),
  paleCeladon = Color(0x6BFFFFFF),
  mintFoam = Color(0x99F4F5EF),
  creamIvory = Color(0x8FF9FAF5),
  darkText = Color(0xFF1D2521),
  mutedText = Color(0xFF46534A),
  paper = Color(0xB8FDFDFB),
  error = Color(0xFFC7452F)
)

val DarkGlassStudyColors = StudyColors(
  deepSage = Color(0xFF73D3A7),
  mediumSage = Color(0x24F7F7F3),
  paleCeladon = Color(0x14F7F7F3),
  mintFoam = Color(0x1FF7F7F3),
  creamIvory = Color(0x8F0E1612),
  darkText = Color(0xFFEDF2F0),
  mutedText = Color(0xFFA9BDB3),
  paper = Color(0xA60E1612),
  error = Color(0xFFE06A55)
)

val LocalStudyColors = staticCompositionLocalOf { ClassicStudyColors }

/** Pastel category colours: background tint and the deeper ink for icons on it. */
val CategoryPastels = listOf(
  Color(0xFFF3D98A) to Color(0xFF7A5B0E),
  Color(0xFFC2B5F4) to Color(0xFF4B3A97),
  Color(0xFF96DCC7) to Color(0xFF1F6B55),
  Color(0xFFF1A9A0) to Color(0xFF8C3428),
  Color(0xFFA9C8F2) to Color(0xFF28508A),
  Color(0xFFB9E3A0) to Color(0xFF3D6B22)
)

val GlassShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(22.dp),
  extraLarge = RoundedCornerShape(26.dp)
)

/** A frosted glass panel: translucent fill and a light rim, brighter at the top like the edge of glass. */
fun Modifier.glass(shape: Shape, colors: GlassColors, fill: Color = colors.fill): Modifier =
  this
    .clip(shape)
    .background(fill, shape)
    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (colors.dark) 0.03f else 0.10f), 0.5f to Color.Transparent), shape)
    .border(0.8.dp, Brush.verticalGradient(listOf(colors.rimTop, colors.rimBottom)), shape)

/** A recessed glass well: inputs, tracks and secondary buttons. */
fun Modifier.glassInset(shape: Shape, colors: GlassColors): Modifier =
  this
    .clip(shape)
    .background(colors.inset, shape)
    .border(0.8.dp, colors.insetRim, shape)

private class Bloom(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val color: Color, val stop: Float = 0.7f)

private class Mesh(val angle: Float, val from: Color, val to: Color, val blooms: List<Bloom>)

private fun meshFor(backdrop: Backdrop): Mesh = when (backdrop) {
  Backdrop.OliveMist -> Mesh(
    160f, Color(0xFFBFC7A8), Color(0xFF7E9871),
    listOf(
      Bloom(0.18f, 0.22f, 0.60f, 0.55f, Color(0xF2D7DFC3)),
      Bloom(0.82f, 0.18f, 0.50f, 0.60f, Color(0xD9AFC3A2)),
      Bloom(0.70f, 0.85f, 0.70f, 0.60f, Color(0xBF61875A)),
      Bloom(0.10f, 0.90f, 0.55f, 0.50f, Color(0xB3687A48)),
      Bloom(0.50f, 0.50f, 0.40f, 0.40f, Color(0x99EBEBD6)),
      Bloom(0.70f, 1.10f, 0.80f, 0.60f, Color(0x2ECDD8B6)),
      Bloom(0.50f, -0.10f, 0.70f, 0.50f, Color(0x47FCFCF8))
    )
  )
  Backdrop.SoftNeutral -> Mesh(
    150f, Color(0xFFEAE7E1), Color(0xFFD6D2CD),
    listOf(
      Bloom(0.20f, 0.15f, 0.60f, 0.60f, Color(0xFFF8F6F2)),
      Bloom(0.85f, 0.85f, 0.60f, 0.60f, Color(0xFFCEC7C0))
    )
  )
  Backdrop.MinimalSolid -> Mesh(180f, Color(0xFFDDDFD8), Color(0xFFDDDFD8), emptyList())
  Backdrop.DarkForest -> Mesh(
    170f, Color(0xFF1E2922), Color(0xFF121714),
    listOf(
      Bloom(0.25f, 0.20f, 0.50f, 0.45f, Color(0xE63C5D3C)),
      Bloom(0.80f, 0.75f, 0.45f, 0.50f, Color(0xE62A463D)),
      Bloom(0.70f, 0.15f, 0.35f, 0.35f, Color(0x597A8547))
    )
  )
  Backdrop.Midnight -> Mesh(
    155f, Color(0xFF090A0C), Color(0xFF020304),
    listOf(Bloom(0.22f, 0.12f, 0.65f, 0.55f, Color(0x8018191C), stop = 0.75f))
  )
}

/** The painted backdrop: a diagonal wash with soft elliptical blooms, like light through leaves. */
@Composable
fun MeshBackground(backdrop: Backdrop, modifier: Modifier = Modifier.fillMaxSize()) {
  val mesh = meshFor(backdrop)
  Canvas(modifier) {
    drawRect(linearBrush(mesh.angle, mesh.from, mesh.to))
    mesh.blooms.forEach { drawBloom(it) }
  }
}

/** A CSS-style "linear-gradient(<angle>deg, from, to)" across the whole canvas. */
private fun DrawScope.linearBrush(angleDeg: Float, from: Color, to: Color): Brush {
  val rad = (angleDeg - 90f) * PI.toFloat() / 180f
  val dx = cos(rad)
  val dy = sin(rad)
  val half = (kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)) / 2f
  val c = center
  return Brush.linearGradient(listOf(from, to), start = Offset(c.x - dx * half, c.y - dy * half), end = Offset(c.x + dx * half, c.y + dy * half))
}

/** "radial-gradient(rx ry at cx cy, color, transparent stop)": drawn as a circle squashed to an ellipse. */
private fun DrawScope.drawBloom(b: Bloom) {
  val center = Offset(size.width * b.cx, size.height * b.cy)
  val rx = size.width * b.rx
  val ry = size.height * b.ry
  if (rx <= 0f || ry <= 0f) return
  scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
    drawCircle(
      brush = Brush.radialGradient(0f to b.color, b.stop to b.color.copy(alpha = 0f), center = center, radius = rx),
      radius = rx,
      center = center
    )
  }
}
