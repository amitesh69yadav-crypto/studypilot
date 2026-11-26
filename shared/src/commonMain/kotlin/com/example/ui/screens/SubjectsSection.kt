package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.StudyTopic
import com.example.data.model.Subject
import com.example.platform.parseHexColor

/** A subject and its topics. [id] 0 is "General": topics that don't belong to any subject. */
data class SubjectGroup(
  val id: Long,
  val name: String,
  val code: String,
  val colorHex: String,
  val topics: List<StudyTopic>
) {
  val mastered: Int get() = topics.count { it.masteryLevel == "MASTERED" }
  val needReview: Int get() = topics.count { it.masteryLevel == "STRUGGLING" }
  val untested: Int get() = topics.count { it.timesTested == 0 }

  companion object {
    const val GENERAL_ID = 0L
    fun general(topics: List<StudyTopic>) = SubjectGroup(GENERAL_ID, "General", "No subject", "", topics)
  }
}

/** Every subject (even empty ones), then "General" if any topic has no subject. */
fun groupTopicsBySubject(subjects: List<Subject>, topics: List<StudyTopic>): List<SubjectGroup> {
  val byId = topics.groupBy { it.subjectId }
  val known = subjects.mapTo(HashSet()) { it.id }
  val groups = subjects.map { SubjectGroup(it.id, it.name, it.code, it.colorHex, byId[it.id].orEmpty()) }
  val unassigned = topics.filter { it.subjectId !in known }
  return if (unassigned.isEmpty()) groups else groups + SubjectGroup.general(unassigned)
}

/** Subject cards; one column on a phone, more on wider windows. */
@Composable
fun SubjectsGrid(
  groups: List<SubjectGroup>,
  quizBusy: Boolean,
  onOpen: (SubjectGroup) -> Unit,
  onQuiz: (SubjectGroup) -> Unit,
  onAddSubject: () -> Unit
) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 300.dp),
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    if (quizBusy) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
          CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(10.dp))
          Text(
            "Writing your quiz. With a model on this device or your PC this can take a minute.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
    if (groups.isEmpty()) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        Text(
          "Add a subject for each course, then file your topics under it. You can quiz yourself on one subject at a time.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 8.dp)
        )
      }
    }
    items(groups, key = { it.id }) { group ->
      SubjectCard(group, quizBusy, onClick = { onOpen(group) }, onQuiz = { onQuiz(group) })
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
      Row {
        OutlinedButton(onClick = onAddSubject, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("New subject")
        }
      }
    }
  }
}

@Composable
private fun SubjectCard(group: SubjectGroup, quizBusy: Boolean, onClick: () -> Unit, onQuiz: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        SubjectDot(group)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(
            "${group.code} · ${topicCount(group.topics.size)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Spacer(Modifier.height(12.dp))
      MasteryBar(group)
      Spacer(Modifier.height(12.dp))
      OutlinedButton(
        onClick = onQuiz,
        enabled = group.topics.isNotEmpty() && !quizBusy,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
      ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Quiz")
      }
    }
  }
}

/** Top of an opened subject: back, name, progress, and its quiz. */
@Composable
fun SubjectHeader(group: SubjectGroup, quizBusy: Boolean, onBack: () -> Unit, onQuiz: () -> Unit, onAddTopic: () -> Unit) {
  Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All subjects") }
      SubjectDot(group)
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        Text(group.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${group.code} · ${topicCount(group.topics.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Column(Modifier.padding(start = 12.dp)) {
      if (group.topics.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        MasteryBar(group)
      }
      Spacer(Modifier.height(12.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
          onClick = onQuiz,
          enabled = group.topics.isNotEmpty() && !quizBusy,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f).heightIn(min = 48.dp)
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text(if (quizBusy) "Assembling..." else "Quiz this subject", fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        OutlinedButton(onClick = onAddTopic, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Add topic", maxLines = 1)
        }
      }
    }
  }
}

@Composable
private fun SubjectDot(group: SubjectGroup) {
  val color = parseHexColor(group.colorHex) ?: MaterialTheme.colorScheme.outline
  Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun MasteryBar(group: SubjectGroup) {
  val total = group.topics.size
  LinearProgressIndicator(
    progress = { if (total == 0) 0f else group.mastered.toFloat() / total },
    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
    trackColor = MaterialTheme.colorScheme.surfaceVariant
  )
  Spacer(Modifier.height(6.dp))
  Text(
    if (total == 0) "No topics yet" else "${group.mastered} mastered · ${group.needReview} to review · ${group.untested} untested",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
  )
}

private fun topicCount(n: Int) = if (n == 1) "1 topic" else "$n topics"

/** Distinct colours for new subjects, picked in turn. */
val SUBJECT_COLORS = listOf("#2563EB", "#16A34A", "#DB2777", "#EA580C", "#7C3AED", "#0891B2", "#CA8A04", "#DC2626")
