package com.example.ui.screens

import com.example.platform.LocalPlatformActions
import com.example.platform.PlatformBackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.MasteryBadge
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatCard
import com.example.ui.theme.PaletteDeepSage
import com.example.ui.theme.PalettePaleCeladon
import com.example.ui.theme.PaletteDarkText
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
import com.example.ui.viewmodel.StudyPilotViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun ProgressScreen(
  viewModel: StudyPilotViewModel
) {
  val actions = LocalPlatformActions.current
  val topics by viewModel.topics.collectAsState()
  val attempts by viewModel.quizAttempts.collectAsState()
  val subjects by viewModel.subjects.collectAsState()
  val sessions by viewModel.sessions.collectAsState()
  val studyStreak by viewModel.studyStreak.collectAsState()

  var showExportDialog by remember { mutableStateOf(false) }

  val masteredCount = remember(topics) { topics.count { it.masteryLevel == "MASTERED" } }
  val familiarCount = remember(topics) { topics.count { it.masteryLevel == "FAMILIAR" } }
  val strugglingTopics = remember(topics) { topics.filter { it.masteryLevel == "STRUGGLING" } }
  val untestedTopics = remember(topics) { topics.filter { it.timesTested == 0 } }

  val averageScore = remember(attempts) {
    if (attempts.isNotEmpty()) {
      attempts.map { it.scorePercentage }.average().toInt()
    } else 0
  }

  val overallMasteryPercent = remember(masteredCount, topics) {
    if (topics.isNotEmpty()) {
      (masteredCount * 100 / topics.size)
    } else 0
  }

  // 7 Days of the week in standard Monday-Sunday order
  val daysOfWeek = remember { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun") }

  // Real weekly hours calculated strictly from real timetable sessions (0 if no data)
  val weeklyDayHours = remember(sessions) {
    val dayTotals = mutableMapOf<String, Float>()
    daysOfWeek.forEach { dayTotals[it] = 0f }

    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val todayCal = Calendar.getInstance()
    // Current day offset from Monday (0 for Mon, 6 for Sun)
    val dayOfWeekOffset = (todayCal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val mondayCal = Calendar.getInstance().apply {
      add(Calendar.DAY_OF_YEAR, -dayOfWeekOffset)
    }

    val currentWeekDates = mutableMapOf<String, String>()
    daysOfWeek.forEachIndexed { idx, dayKey ->
      val dCal = mondayCal.clone() as Calendar
      dCal.add(Calendar.DAY_OF_YEAR, idx)
      currentWeekDates[dayKey] = sdf.format(dCal.time)
    }

    sessions.forEach { s ->
      if (s.startTime.isBlank() || s.endTime.isBlank()) return@forEach
      try {
        val (sh, sm) = s.startTime.split(":").map { it.toInt() }
        val (eh, em) = s.endTime.split(":").map { it.toInt() }
        val durationHours = ((eh * 60 + em) - (sh * 60 + sm)).coerceAtLeast(0) / 60.0f

        daysOfWeek.forEach { dayKey ->
          val targetDate = currentWeekDates[dayKey]
          val matchesDate = s.date.isNotBlank() && s.date == targetDate
          val matchesRecurring = s.recurringDays.split(",").map { it.trim() }.any { it.equals(dayKey, ignoreCase = true) }

          if (matchesDate || matchesRecurring) {
            dayTotals[dayKey] = (dayTotals[dayKey] ?: 0f) + durationHours
          }
        }
      } catch (_: Exception) {}
    }

    daysOfWeek.map { day ->
      val total = dayTotals[day] ?: 0f
      val rounded = Math.round(total * 10) / 10f
      Pair(day, rounded)
    }
  }

  val totalWeeklyHours = remember(weeklyDayHours) {
    weeklyDayHours.sumOf { it.second.toDouble() }
  }

  val maxDayHours = remember(weeklyDayHours) {
    (weeklyDayHours.maxOfOrNull { it.second } ?: 0f).coerceAtLeast(4f)
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = 32.dp)
  ) {
    // 1. Export Action Banner
    item {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(
          modifier = Modifier
            .weight(1f)
            .padding(end = 12.dp)
        ) {
          Text(
            text = "Overview",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = "This week",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        OutlinedButton(
          onClick = { showExportDialog = true },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.heightIn(min = 44.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
          Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Export", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
      }
    }

    // 2. Metrics 4-Grid (Even heights & centered)
    item {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          StatCard(
            title = "Topics studied",
            value = topics.size.toString(),
            subtitle = "$masteredCount mastered",
            icon = Icons.Default.Book,
            iconTint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
          )
          StatCard(
            title = "Quizzes completed",
            value = attempts.size.toString(),
            subtitle = if (attempts.isEmpty()) "none yet" else "average $averageScore%",
            icon = Icons.Default.Quiz,
            iconTint = EmeraldSuccess,
            modifier = Modifier.weight(1f)
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          StatCard(
            title = "Study streak",
            value = studyStreak.toString(),
            subtitle = if (studyStreak == 1) "day in a row" else "days in a row",
            icon = Icons.Default.LocalFireDepartment,
            iconTint = AmberAccent,
            modifier = Modifier.weight(1f)
          )
          StatCard(
            title = "Needs review",
            value = strugglingTopics.size.toString(),
            subtitle = "${untestedTopics.size} untested",
            icon = Icons.Default.Warning,
            iconTint = RoseError,
            modifier = Modifier.weight(1f)
          )
        }
      }
    }

    // 3. Weekly Hours Logged Bar Chart (7 Days of Week, Zero-based, Real Data)
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
      ) {
        Column(modifier = Modifier.padding(20.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(
              modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
            ) {
              Text(
                text = "Study hours this week",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "Mon to Sun, from your timetable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }

            Text(
              text = String.format(Locale.getDefault(), "%.1f h", totalWeeklyHours),
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface
            )
          }

          Spacer(modifier = Modifier.height(16.dp))


          Spacer(modifier = Modifier.height(16.dp))

          if (totalWeeklyHours > 0f) {
            // 7-Day Bar Chart Canvas
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
            ) {
              val barPrimary = MaterialTheme.colorScheme.primary
              val barSecondary = MaterialTheme.colorScheme.secondary
              val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)

              Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height - 12.dp.toPx()
                val barCount = weeklyDayHours.size
                val slotWidth = w / barCount
                val barWidth = (slotWidth * 0.52f).coerceAtMost(32.dp.toPx())

                // Grid lines: 100%, 50%, baseline
                drawLine(gridColor, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1.5f)

                weeklyDayHours.forEachIndexed { i, (_, hrs) ->
                  val barHeight = if (hrs > 0f) (hrs / maxDayHours) * h else 3.dp.toPx()
                  val left = (i * slotWidth) + (slotWidth - barWidth) / 2
                  val top = h - barHeight

                  val color = if (hrs > 0f) {
                    if (i % 2 == 0) barPrimary else barSecondary
                  } else {
                    gridColor
                  }

                  drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Day Names and Hours under each bar (Mon - Sun)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              weeklyDayHours.forEach { (day, hrs) ->
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  modifier = Modifier.weight(1f)
                ) {
                  Text(
                    text = "${hrs}h",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hrs > 0f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                  )
                  Text(
                    text = day,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                  )
                }
              }
            }
          } else {
            Text(
              text = "Nothing logged yet. Completed timetable sessions count here.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }

    // 4. Quiz Score Improvement Trajectory Chart (Zero if no data, Real Data)
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
      ) {
        Column(modifier = Modifier.padding(20.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(
              modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
            ) {
              Text(
                text = "Score trend",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = if (attempts.isEmpty()) "Target 85%" else "Average of ${attempts.size} quizzes · target 85%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }

            Text(
              text = if (attempts.isEmpty()) "—" else "$averageScore%",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface
            )
          }

          Spacer(modifier = Modifier.height(16.dp))

          val scores = remember(attempts) {
            attempts.takeLast(6).map { it.scorePercentage }
          }

          if (scores.isNotEmpty()) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
            ) {
              val primaryColor = MaterialTheme.colorScheme.primary
              val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
              val targetColor = AmberAccent.copy(alpha = 0.6f)

              Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Grid lines: 100%, 50%, baseline (0%)
                drawLine(gridColor, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1.5f)

                // 85% target line (dashed)
                val targetY = h - (0.85f * h)
                val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                drawLine(
                  color = targetColor,
                  start = Offset(0f, targetY),
                  end = Offset(w, targetY),
                  strokeWidth = 1.5f,
                  pathEffect = dashPathEffect
                )

                if (scores.isEmpty()) {
                  // If there is no data, set to zero cleanly along baseline
                  val slotCount = 5
                  val stepX = w / (slotCount - 1)
                  val baselineY = h - 3.dp.toPx()

                  // Baseline line
                  drawLine(
                    color = primaryColor.copy(alpha = 0.35f),
                    start = Offset(0f, baselineY),
                    end = Offset(w, baselineY),
                    strokeWidth = 2.dp.toPx()
                  )

                  for (idx in 0 until slotCount) {
                    val p = Offset(idx * stepX, baselineY)
                    drawCircle(color = primaryColor.copy(alpha = 0.4f), radius = 4.dp.toPx(), center = p)
                    drawCircle(color = Color.White, radius = 2.dp.toPx(), center = p)
                  }
                } else if (scores.size == 1) {
                  val singleScore = scores.first()
                  val p = Offset(w / 2f, h - (singleScore / 100f * h))
                  drawCircle(color = primaryColor, radius = 6.dp.toPx(), center = p)
                  drawCircle(color = Color.White, radius = 3.dp.toPx(), center = p)
                } else {
                  val stepX = w / (scores.size - 1)
                  val points = scores.mapIndexed { idx, score ->
                    val x = idx * stepX
                    val y = h - (score / 100f * h)
                    Offset(x, y)
                  }

                  val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (p in points.drop(1)) {
                      lineTo(p.x, p.y)
                    }
                  }

                  drawPath(path, color = primaryColor, style = Stroke(width = 3.dp.toPx()))

                  for (p in points) {
                    drawCircle(color = primaryColor, radius = 5.dp.toPx(), center = p)
                    drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = p)
                  }
                }
              }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Labels under trajectory
            if (scores.isEmpty()) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                listOf("Q1: 0%", "Q2: 0%", "Q3: 0%", "Q4: 0%", "Q5: 0%").forEach { label ->
                  Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                  )
                }
              }
            } else {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                scores.forEachIndexed { idx, score ->
                  Text(
                    text = "Q${idx + 1}: $score%",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                  )
                }
              }
            }
          } else {
            Text(
              text = "Take a quiz to see your scores here.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }

    // 5. Topic Mastery Breakdown Progress
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
      ) {
        Column(modifier = Modifier.padding(20.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Topic-wise Mastery Breakdown",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
            )
            Text(
              text = "$overallMasteryPercent% Mastered",
              style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
              color = EmeraldSuccess
            )
          }

          Spacer(modifier = Modifier.height(16.dp))

          MasteryProgressRow(
            label = "Mastered (Interval >= 14d)",
            count = masteredCount,
            total = topics.size,
            color = EmeraldSuccess
          )
          Spacer(modifier = Modifier.height(12.dp))
          MasteryProgressRow(
            label = "Familiar (Interval 3-7d)",
            count = familiarCount,
            total = topics.size,
            color = CyanAccent
          )
          Spacer(modifier = Modifier.height(12.dp))
          MasteryProgressRow(
            label = "Needs Review (Interval 1d)",
            count = strugglingTopics.size,
            total = topics.size,
            color = RoseError
          )
          Spacer(modifier = Modifier.height(12.dp))
          MasteryProgressRow(
            label = "Untested Concepts",
            count = untestedTopics.size,
            total = topics.size,
            color = AmberAccent
          )
        }
      }
    }

    // 6. Weak Topics Requiring Urgent Revision
    item {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp)
      ) {
        SectionHeader(title = "Weak topics (${strugglingTopics.size})")

        if (strugglingTopics.isEmpty()) {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "No weak topics requiring revision (0)",
                  style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                  color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = "Complete weekend revision quizzes to flag struggling concepts",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        } else {
          Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            strugglingTopics.forEach { topic ->
              Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      topic.title,
                      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                      "${topic.subjectName} • ${topic.timesCorrect}/${topic.timesTested} correct",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  Spacer(modifier = Modifier.width(8.dp))
                  MasteryBadge(level = topic.masteryLevel)
                }
              }
            }
          }
        }
      }
    }

    // 7. Topics Not Yet Tested
    item {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp)
      ) {
        SectionHeader(title = "Not tested yet (${untestedTopics.size})")

        if (untestedTopics.isEmpty()) {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "All recorded syllabus topics tested (0 queued)",
                  style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                  color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = "Record new lecture notes in My Topics to prepare them for quizzes",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        } else {
          Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            untestedTopics.forEach { topic ->
              Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      topic.title,
                      style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                      topic.subjectName,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  Spacer(modifier = Modifier.width(8.dp))
                  MasteryBadge(level = "UNTESTED")
                }
              }
            }
          }
        }
      }
    }
  }

  // Export Progress Report Dialog
  if (showExportDialog) {
    val reportText = remember(topics.size, masteredCount, strugglingTopics.size, untestedTopics.size, attempts.size, averageScore, studyStreak, totalWeeklyHours) {
      buildString {
        append("=== StudyPilot AI - Academic Progress Report ===\n")
        append("Total Topics Recorded: ${topics.size}\n")
        append("Mastered Topics: $masteredCount\n")
        append("Needs Review: ${strugglingTopics.size}\n")
        append("Untested Topics: ${untestedTopics.size}\n")
        append("Quizzes Completed: ${attempts.size}\n")
        append("Average Quiz Score: $averageScore%\n")
        append("Current Study Streak: $studyStreak Days\n")
        append("Weekly Hours Logged: ${String.format(Locale.getDefault(), "%.1f", totalWeeklyHours)} Hours\n")
        append("===============================================")
      }
    }

    AlertDialog(
      onDismissRequest = { showExportDialog = false },
      title = { Text("Export report", fontWeight = FontWeight.Bold) },
      text = {
        Column {
          Text(
            "Copy your verified academic learning report to the clipboard or share with tutors:",
            style = MaterialTheme.typography.bodyMedium
          )
          Spacer(modifier = Modifier.height(12.dp))
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
          ) {
            Text(reportText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            actions.copyText(reportText, "Report copied to clipboard!")
            showExportDialog = false
          },
          shape = RoundedCornerShape(10.dp)
        ) {
          Text("Copy to clipboard")
        }
      },
      dismissButton = {
        TextButton(onClick = { showExportDialog = false }) {
          Text("Close")
        }
      }
    )
  }
}

@Composable
private fun MasteryProgressRow(
  label: String,
  count: Int,
  total: Int,
  color: Color
) {
  val fraction = if (total > 0) (count.toFloat() / total.toFloat()) else 0f
  Column {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
          .weight(1f)
          .padding(end = 8.dp)
      )
      Text(
        "$count (${(fraction * 100).toInt()}%)",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = color
      )
    }
    Spacer(modifier = Modifier.height(4.dp))
    LinearProgressIndicator(
      progress = { fraction },
      modifier = Modifier
        .fillMaxWidth()
        .height(6.dp)
        .clip(RoundedCornerShape(3.dp)),
      color = color,
      trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
  }
}
