package com.example.ui.screens

import com.example.ui.theme.statTile
import com.example.ui.theme.statLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import com.example.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import com.example.ui.theme.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.example.ui.theme.ElevatedCard
import com.example.ui.theme.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.example.ui.theme.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.example.ui.theme.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.QuizAttempt
import com.example.data.model.QuizConfig
import com.example.data.model.StudyTopic
import com.example.ui.components.SectionHeader
import com.example.ui.theme.PaletteDarkText
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
import com.example.ui.viewmodel.StudyPilotViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuizScreen(
  viewModel: StudyPilotViewModel
) {
  val topics by viewModel.topics.collectAsState()
  val quizAttempts by viewModel.quizAttempts.collectAsState()
  val quizConfig by viewModel.quizConfig.collectAsState()
  val isAiLoading by viewModel.isAiLoading.collectAsState()
  val subjects by viewModel.subjects.collectAsState()

  var showConfigSection by remember { mutableStateOf(false) }

  // The subject the quiz is limited to (null: all subjects).
  val subjectGroups = remember(subjects, topics) { groupTopicsBySubject(subjects, topics) }
  val chosenGroup = quizConfig.subjectId?.let { id -> subjectGroups.firstOrNull { it.id == id } }
  val quizTopics = chosenGroup?.topics ?: topics

  val testedTopics = topics.filter { it.timesTested > 0 }
  val untestedTopics = topics.filter { it.timesTested == 0 }
  val strugglingTopics = quizTopics.filter { it.masteryLevel == "STRUGGLING" }

  val coveragePercent = if (topics.isNotEmpty()) (testedTopics.size * 100 / topics.size) else 0

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)
  ) {
    // 1. Primary Quiz Trigger Hero Card
    item {
      ElevatedCard(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
          containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
      ) {
        Column(modifier = Modifier.padding(20.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
              Column {
                Text(
                  text = "Weekend quiz",
                  style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = "${quizConfig.questionCount} questions · ${quizConfig.difficulty.lowercase()}" +
                    (chosenGroup?.let { " · ${it.name}" } ?: ""),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }

            IconButton(onClick = { showConfigSection = !showConfigSection }) {
              Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Quiz Config",
                tint = if (showConfigSection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = if (chosenGroup != null) "Questions from ${chosenGroup.name}, weakest topics first." else "Questions from all your topics, weakest first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          if (subjectGroups.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              FilterChip(
                selected = chosenGroup == null,
                onClick = { viewModel.updateQuizConfig(quizConfig.copy(subjectId = null)) },
                label = { Text("All subjects", maxLines = 1, softWrap = false) },
                shape = RoundedCornerShape(10.dp)
              )
              subjectGroups.forEach { group ->
                FilterChip(
                  selected = chosenGroup?.id == group.id,
                  onClick = { viewModel.updateQuizConfig(quizConfig.copy(subjectId = if (chosenGroup?.id == group.id) null else group.id)) },
                  label = { Text("${group.name} (${group.topics.size})", maxLines = 1, softWrap = false) },
                  enabled = group.topics.isNotEmpty(),
                  shape = RoundedCornerShape(10.dp)
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(16.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Button(
              onClick = { viewModel.startWeekendQuiz(forceWeakOnly = false) },
              enabled = !isAiLoading && quizTopics.isNotEmpty(),
              shape = RoundedCornerShape(12.dp),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
              modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
            ) {
              if (isAiLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Assembling...", maxLines = 1, softWrap = false)
              } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start quiz", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
              }
            }

            if (strugglingTopics.isNotEmpty()) {
              OutlinedButton(
                onClick = { viewModel.startWeekendQuiz(forceWeakOnly = true) },
                enabled = !isAiLoading,
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 48.dp)
              ) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Revise (${strugglingTopics.size})", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
      }
    }

    // 2. Quiz Configuration Panel (Collapsible)
    if (showConfigSection) {
      item {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text("Quiz settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(12.dp))

            // Quiz Days
            Text("Scheduled Days:", style = MaterialTheme.typography.labelSmall)
            FlowRow(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              listOf("Saturday", "Sunday", "Both").forEach { day ->
                FilterChip(
                  selected = quizConfig.quizDays.contains(day, ignoreCase = true),
                  onClick = { viewModel.updateQuizConfig(quizConfig.copy(quizDays = day)) },
                  label = { Text(day, fontSize = 12.sp, maxLines = 1, softWrap = false) },
                  shape = RoundedCornerShape(10.dp)
                )
              }
            }

            Spacer(modifier = Modifier.height(8.dp))
            // Question Count
            Text("Number of Questions:", style = MaterialTheme.typography.labelSmall)
            FlowRow(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              listOf(5, 10, 15, 20).forEach { count ->
                FilterChip(
                  selected = quizConfig.questionCount == count,
                  onClick = { viewModel.updateQuizConfig(quizConfig.copy(questionCount = count)) },
                  label = { Text("$count Qs", fontSize = 12.sp, maxLines = 1, softWrap = false) },
                  shape = RoundedCornerShape(10.dp)
                )
              }
            }

            Spacer(modifier = Modifier.height(8.dp))
            // Difficulty
            Text("Difficulty Level:", style = MaterialTheme.typography.labelSmall)
            FlowRow(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              listOf("MIXED", "EASY", "MEDIUM", "HARD").forEach { diff ->
                FilterChip(
                  selected = quizConfig.difficulty == diff,
                  onClick = { viewModel.updateQuizConfig(quizConfig.copy(difficulty = diff)) },
                  label = { Text(diff, fontSize = 12.sp, maxLines = 1, softWrap = false) },
                  shape = RoundedCornerShape(10.dp)
                )
              }
            }

            Spacer(modifier = Modifier.height(8.dp))
            // Question Source Selection
            Text("Question Source:", style = MaterialTheme.typography.labelSmall)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              val sources = listOf(
                "ALL" to "All Combined",
                "IMPORTED_CHAT" to "Imported AI Chats",
                "TUTOR_CHAT" to "Tutor Chats",
                "MANUAL" to "Recorded Topics",
                "UPLOAD" to "Study Materials"
              )
              sources.forEach { (srcKey, srcLabel) ->
                FilterChip(
                  selected = quizConfig.questionSource == srcKey,
                  onClick = { viewModel.updateQuizConfig(quizConfig.copy(questionSource = srcKey)) },
                  label = { Text(srcLabel, fontSize = 12.sp, maxLines = 1, softWrap = false) },
                  shape = RoundedCornerShape(10.dp)
                )
              }
            }

            Spacer(modifier = Modifier.height(8.dp))
            // Prioritize Weak Switch
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Prioritize weak topics", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                Text("Give overdue and struggling concepts higher weight", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Switch(
                checked = quizConfig.prioritizeWeakTopics,
                onCheckedChange = { viewModel.updateQuizConfig(quizConfig.copy(prioritizeWeakTopics = it)) }
              )
            }
          }
        }
      }
    }

    // 3. Topic Coverage Tracker Card
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Topic coverage",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = "$coveragePercent% covered",
              style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.primary
            )
          }

          Spacer(modifier = Modifier.height(8.dp))
          LinearProgressIndicator(
            progress = { if (topics.isNotEmpty()) (testedTopics.size.toFloat() / topics.size.toFloat()) else 0f },
            modifier = Modifier
              .fillMaxWidth()
              .height(8.dp)
              .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
          )

          Spacer(modifier = Modifier.height(16.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Column(
              modifier = Modifier.weight(1f).statTile { viewModel.navigateTo(com.example.ui.viewmodel.Screen.MyTopics) },
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(text = "${testedTopics.size}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
              Text(text = statLabel("Topics tested"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(
              modifier = Modifier.weight(1f).statTile { viewModel.navigateTo(com.example.ui.viewmodel.Screen.MyTopics) },
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(text = "${untestedTopics.size}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
              Text(text = statLabel("Untested"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(
              modifier = Modifier.weight(1f).statTile { viewModel.navigateTo(com.example.ui.viewmodel.Screen.MyTopics) },
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(text = "${strugglingTopics.size}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
              Text(text = statLabel("Needs review"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }

          if (untestedTopics.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = AmberAccent.copy(alpha = 0.1f)
            ) {
              Text(
                text = "Next up: ${untestedTopics.joinToString(", ") { it.title }.take(100)}",
                style = MaterialTheme.typography.labelSmall,
                color = AmberAccent,
                modifier = Modifier.padding(8.dp)
              )
            }
          }
        }
      }
    }

    // 4. Past Quiz Attempts History
    item {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp)
      ) {
        SectionHeader(title = "History (${quizAttempts.size})")

        if (quizAttempts.isEmpty()) {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
          ) {
            Text(
              text = "No quizzes yet.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
              modifier = Modifier.fillMaxWidth().padding(24.dp)
            )
          }
        } else {
          Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            quizAttempts.forEach { attempt ->
              QuizAttemptItemRow(attempt = attempt)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun QuizAttemptItemRow(attempt: QuizAttempt) {
  val scoreColor = when {
    attempt.scorePercentage >= 80 -> EmeraldSuccess
    attempt.scorePercentage >= 60 -> CyanAccent
    else -> AmberAccent
  }

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
      Box(
        modifier = Modifier
          .size(46.dp)
          .clip(CircleShape)
          .background(scoreColor.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = "${attempt.scorePercentage}%",
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
          color = scoreColor
        )
      }
      Spacer(modifier = Modifier.width(16.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = attempt.title,
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.onSurface
        )
        Text(
          text = "${attempt.correctCount}/${attempt.totalQuestions} correct • ${attempt.dateAttempted}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          text = "${attempt.topicsCoveredCount} topics" + if (attempt.topicsSkippedCount > 0) " · ${attempt.topicsSkippedCount} left for next time" else "",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary
        )
      }
    }
  }
}
