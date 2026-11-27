package com.example.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TimetableSession
import com.example.ui.theme.CategoryPastels
import com.example.ui.theme.GlassColors
import com.example.ui.theme.LocalGlass

/**
 * Pastels for subjects, in hues the routines don't use (meals yellow, sleep blue, exercise green,
 * personal teal), so a class never looks like a meal or a night's sleep.
 */
private val SubjectPastels = listOf(
  Color(0xFFC2B5F4) to Color(0xFF4B3A97), // lavender
  Color(0xFFF1A9A0) to Color(0xFF8C3428), // coral
  Color(0xFFF6C49B) to Color(0xFF8A4B16), // peach
  Color(0xFFEDB3D3) to Color(0xFF85305F), // rose
  Color(0xFFD9B8EE) to Color(0xFF63308A), // orchid
  Color(0xFFDDC1AE) to Color(0xFF6E4434) // clay
)

/** A session's pastel and the deeper ink for its icon: routines by kind, study by subject. */
fun pastelFor(session: TimetableSession): Pair<Color, Color> = when (session.sessionCategory) {
  "FOOD" -> CategoryPastels[0]
  "SLEEP" -> CategoryPastels[4]
  "EXERCISE" -> CategoryPastels[5]
  "PERSONAL" -> CategoryPastels[2]
  else -> {
    val key = session.subjectName.ifBlank { "study" }.lowercase()
    SubjectPastels[Math.floorMod(key.hashCode(), SubjectPastels.size)]
  }
}

fun iconFor(session: TimetableSession): ImageVector = when {
  session.endTime.isBlank() -> Icons.Default.Alarm
  session.sessionCategory == "FOOD" -> Icons.Default.Restaurant
  session.sessionCategory == "SLEEP" -> Icons.Default.Bedtime
  session.sessionCategory == "EXERCISE" -> Icons.Default.FitnessCenter
  session.sessionCategory == "PERSONAL" -> Icons.Default.SelfImprovement
  else -> Icons.AutoMirrored.Filled.MenuBook
}

/** Every icon [iconFor] can return. */
internal val OrbitIconSet: List<ImageVector> = listOf(
  Icons.Default.Alarm,
  Icons.Default.Restaurant,
  Icons.Default.Bedtime,
  Icons.Default.FitnessCenter,
  Icons.Default.SelfImprovement,
  Icons.AutoMirrored.Filled.MenuBook
)

/** The rounded pastel square with an icon, used in the legend and on blocks. */
@Composable
fun CategoryChip(session: TimetableSession, size: Dp = 28.dp) {
  val glass = LocalGlass.current
  // Alarms are frosted, like their markers on the ring.
  val (tint, ink) = if (session.endTime.isBlank() && glass != null) glass.frost to glass.text else pastelFor(session)
  Box(
    Modifier
      .size(size)
      .clip(RoundedCornerShape(size * 0.3f))
      .background(tint)
      .border(0.8.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(size * 0.3f)),
    contentAlignment = Alignment.Center
  ) {
    Icon(iconFor(session), contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.52f))
  }
}

/** A row of pill choices on a recessed glass track, like "Planned | Actual". */
@Composable
fun SegmentedPills(options: List<String>, selected: Int, glass: GlassColors, onSelect: (Int) -> Unit) {
  Row(
    Modifier
      .clip(CircleShape)
      .background(glass.inset)
      .background(glass.text.copy(alpha = 0.05f))
      .border(0.8.dp, glass.insetRim, CircleShape)
      .padding(3.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    options.forEachIndexed { i, label ->
      val isSelected = i == selected
      Box(
        Modifier
          .clip(CircleShape)
          .then(
            if (isSelected) {
              Modifier
                .shadow(1.5.dp, CircleShape)
                .background(if (glass.dark) glass.selected else Color.White)
                .border(0.8.dp, glass.rimTop, CircleShape)
            } else Modifier
          )
          .clickable { onSelect(i) }
          .padding(horizontal = 13.dp, vertical = 6.dp)
      ) {
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (isSelected) glass.text else glass.muted)
      }
    }
  }
}
