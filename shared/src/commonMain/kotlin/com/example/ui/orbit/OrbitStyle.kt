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
import androidx.compose.ui.graphics.lerp
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

/** Every colour a session can have: the four routines first, then the subject colours. */
private val Palette: List<Pair<Color, Color>> = listOf(
  CategoryPastels[0], // meals
  CategoryPastels[4], // sleep
  CategoryPastels[5], // exercise
  CategoryPastels[2] // personal
) + SubjectPastels

private const val FIRST_SUBJECT = 4

/** Subjects in the timetable, alphabetical, so each one keeps the same colour everywhere. */
@Volatile
private var knownSubjects: List<String> = emptyList()

private fun subjectKey(session: TimetableSession) = session.subjectName.ifBlank { "study" }.lowercase()

private fun isRoutine(session: TimetableSession) = session.sessionCategory in setOf("FOOD", "SLEEP", "EXERCISE", "PERSONAL")

/** Tells the colour picker which subjects exist, so they get distinct colours in a stable order. */
fun registerSubjects(sessions: List<TimetableSession>) {
  knownSubjects = sessions.filterNot { isRoutine(it) || it.endTime.isBlank() }.map { subjectKey(it) }.distinct().sorted()
}

private fun baseIndex(session: TimetableSession): Int = when (session.sessionCategory) {
  "FOOD" -> 0
  "SLEEP" -> 1
  "EXERCISE" -> 2
  "PERSONAL" -> 3
  else -> {
    val key = subjectKey(session)
    val known = knownSubjects.indexOf(key)
    FIRST_SUBJECT + Math.floorMod(if (known >= 0) known else key.hashCode(), SubjectPastels.size)
  }
}

/** A session's usual pastel and the deeper ink for its icon: routines by kind, study by subject. */
fun pastelFor(session: TimetableSession): Pair<Color, Color> = Palette[baseIndex(session)]

/** A slightly deeper shade, for a session right next to another of the same subject. */
private fun shade(c: Pair<Color, Color>): Pair<Color, Color> = lerp(c.first, c.second, 0.22f) to c.second

/**
 * Colours for one day's sessions, so that sessions which overlap or sit next to each other never
 * share a colour: a subject that would clash with a neighbouring subject takes a free colour for the
 * day, and back-to-back sessions of the same subject alternate with a deeper shade.
 */
class ColorPlan internal constructor(private val byGroup: Map<String, Int>, private val shaded: Set<DayBlock>) {
  fun of(block: DayBlock): Pair<Color, Color> {
    val c = ofSession(block.session)
    return if (block in shaded) shade(c) else c
  }

  fun ofSession(session: TimetableSession): Pair<Color, Color> = Palette[byGroup[groupLabel(session)] ?: baseIndex(session)]
}

/** Whether two blocks overlap or are within [gap] minutes of each other, round the clock. */
private fun near(a: DayBlock, b: DayBlock, gap: Int = 10): Boolean =
  listOf(-DAY_MINUTES, 0, DAY_MINUTES).any { shift -> a.start < b.end + shift + gap && b.start + shift < a.end + gap }

fun colorPlanFor(blocks: List<DayBlock>): ColorPlan {
  val real = blocks.filterNot { it.isMarker }.sortedBy { it.start }
  val groups = real.map { groupLabel(it.session) }.distinct()
  val sample = real.associateBy { groupLabel(it.session) }
  val neighbours = groups.associateWith { g ->
    real.filter { groupLabel(it.session) == g }.flatMap { a ->
      real.filter { b -> groupLabel(b.session) != g && near(a, b) }.map { groupLabel(it.session) }
    }.toSet()
  }
  val assigned = LinkedHashMap<String, Int>()
  for (g in groups) {
    val base = baseIndex(sample.getValue(g).session)
    val taken = neighbours.getValue(g).mapNotNull { assigned[it] }.toSet()
    assigned[g] = if (base < FIRST_SUBJECT || base !in taken) base else {
      val subjects = (FIRST_SUBJECT until Palette.size)
      subjects.firstOrNull { it !in taken && it !in assigned.values } ?: subjects.firstOrNull { it !in taken } ?: base
    }
  }
  val shaded = HashSet<DayBlock>()
  for ((i, b) in real.withIndex()) {
    val previous = real.subList(0, i).lastOrNull { groupLabel(it.session) == groupLabel(b.session) && near(it, b) }
    if (previous != null && previous !in shaded) shaded.add(b)
  }
  return ColorPlan(assigned, shaded)
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
fun CategoryChip(session: TimetableSession, size: Dp = 28.dp, colors: Pair<Color, Color>? = null) {
  val glass = LocalGlass.current
  // Alarms are frosted, like their markers on the ring.
  val (tint, ink) = if (session.endTime.isBlank() && glass != null) glass.frost to glass.text else colors ?: pastelFor(session)
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
