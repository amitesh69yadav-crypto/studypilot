package com.example.ui.screens

import com.example.platform.PlatformBackHandler
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import com.example.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import com.example.ui.theme.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import com.example.ui.theme.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import com.example.ui.theme.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import com.example.ui.theme.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.data.model.StudyTopic
import com.example.data.model.Subject
import com.example.ui.components.EmptyState
import com.example.ui.components.MasteryBadge
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
import com.example.ui.viewmodel.StudyPilotViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicsScreen(
  viewModel: StudyPilotViewModel,
  showAddDialogOnInit: Boolean = false
) {
  val topics by viewModel.topics.collectAsState()
  val subjects by viewModel.subjects.collectAsState()
  val isAiLoading by viewModel.isAiLoading.collectAsState()

  var searchQuery by remember { mutableStateOf("") }
  var selectedSubjectFilter by remember { mutableStateOf<Long?>(null) }
  var selectedMasteryFilter by remember { mutableStateOf<String?>(null) }
  // Subjects (the default) or every topic in one list; a subject opens to show its topics.
  var showAllTopics by remember { mutableStateOf(false) }
  var openSubjectId by remember { mutableStateOf<Long?>(null) }
  var showAddSubjectDialog by remember { mutableStateOf(false) }

  var showAddDialog by remember { mutableStateOf(showAddDialogOnInit) }
  var detailTopic by remember { mutableStateOf<StudyTopic?>(null) }

  val filteredTopics = topics.filter { topic ->
    val matchesSearch = searchQuery.isBlank() ||
      topic.title.contains(searchQuery, ignoreCase = true) ||
      topic.subjectName.contains(searchQuery, ignoreCase = true) ||
      topic.descriptionNotes.contains(searchQuery, ignoreCase = true)

    val matchesSubject = selectedSubjectFilter == null || topic.subjectId == selectedSubjectFilter
    val matchesMastery = selectedMasteryFilter == null || topic.masteryLevel.equals(selectedMasteryFilter, ignoreCase = true)

    matchesSearch && matchesSubject && matchesMastery
  }

  Scaffold(

    // The app's main Scaffold already applies system bar insets.

    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    floatingActionButton = {
      FloatingActionButton(
        onClick = { showAddDialog = true },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape
      ) {
        Icon(Icons.Default.Add, contentDescription = "Record Topic")
      }
    }
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
    ) {
      val groups = remember(subjects, topics) { groupTopicsBySubject(subjects, topics) }
      val openGroup = openSubjectId?.let { id ->
        groups.firstOrNull { it.id == id } ?: if (id == SubjectGroup.GENERAL_ID) SubjectGroup.general(emptyList()) else null
      }
      PlatformBackHandler(enabled = openGroup != null) { openSubjectId = null }

      if (openGroup == null) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
          listOf(false to "Subjects", true to "All topics").forEachIndexed { index, (all, label) ->
            SegmentedButton(
              selected = showAllTopics == all,
              onClick = { showAllTopics = all },
              shape = SegmentedButtonDefaults.itemShape(index, 2)
            ) { Text(label) }
          }
        }
      }

      when {
        openGroup != null -> {
          SubjectHeader(
            group = openGroup,
            quizBusy = isAiLoading,
            onBack = { openSubjectId = null },
            onQuiz = { viewModel.startSubjectQuiz(openGroup.id, openGroup.name) },
            onAddTopic = { showAddDialog = true }
          )
          if (openGroup.topics.isEmpty()) {
            EmptyState(
              icon = Icons.Default.Book,
              title = "No topics in ${openGroup.name} yet",
              description = "Add what you covered in this course, or move topics here from their details.",
              actionButton = {
                Button(onClick = { showAddDialog = true }, shape = RoundedCornerShape(12.dp)) { Text("Add topic") }
              }
            )
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              items(openGroup.topics, key = { it.id }) { topic ->
                TopicCard(topic = topic, onClick = { detailTopic = topic }, onDelete = { viewModel.deleteTopic(topic.id) })
              }
            }
          }
        }
        !showAllTopics -> SubjectsGrid(
          groups = groups,
          quizBusy = isAiLoading,
          onOpen = { openSubjectId = it.id },
          onQuiz = { viewModel.startSubjectQuiz(it.id, it.name) },
          onAddSubject = { showAddSubjectDialog = true }
        )
        else -> {
          // Search Bar
          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
              if (searchQuery.isNotBlank()) {
                IconButton(onClick = { searchQuery = "" }) {
                  Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
              }
            },
            placeholder = { Text("Search topics, keywords, notes...") },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true
          )

          // Smooth horizontal scrolling Subject Filter Chips
          LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            item {
              FilterChip(
                selected = selectedSubjectFilter == null,
                onClick = { selectedSubjectFilter = null },
                label = { Text("All (${topics.size})") },
                shape = RoundedCornerShape(10.dp)
              )
            }
            items(subjects, key = { it.id }) { sub ->
              val count = topics.count { it.subjectId == sub.id }
              FilterChip(
                selected = selectedSubjectFilter == sub.id,
                onClick = {
                  selectedSubjectFilter = if (selectedSubjectFilter == sub.id) null else sub.id
                },
                label = { Text("${sub.code} ($count)") },
                shape = RoundedCornerShape(10.dp)
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          if (filteredTopics.isEmpty()) {
            EmptyState(
              icon = Icons.Default.Book,
              title = "No topics found",
              description = "Add what you studied after each lecture.",
              actionButton = {
                Button(onClick = { showAddDialog = true }, shape = RoundedCornerShape(12.dp)) {
                  Text("Add topic")
                }
              }
            )
          } else {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              items(filteredTopics, key = { it.id }) { topic ->
                TopicCard(
                  topic = topic,
                  onClick = { detailTopic = topic },
                  onDelete = { viewModel.deleteTopic(topic.id) }
                )
              }
            }
          }
        }
      }
    }
  }

  // Add Topic Dialog (3 input methods: Manual, Upload, Paste)
  if (showAddSubjectDialog) {
    AddSubjectDialog(
      onDismiss = { showAddSubjectDialog = false },
      onConfirm = { name, code ->
        viewModel.addSubject(name, code, SUBJECT_COLORS[subjects.size % SUBJECT_COLORS.size])
        showAddSubjectDialog = false
      }
    )
  }

  if (showAddDialog) {
    AddTopicDialog(
      subjects = subjects,
      initialSubjectId = openSubjectId,
      onDismiss = { showAddDialog = false },
      onConfirm = { subjectId, subjectName, title, notes, sourceType, fileName, generateQuestions ->
        viewModel.addTopic(
          subjectId = subjectId,
          subjectName = subjectName,
          title = title,
          descriptionNotes = notes,
          sourceType = sourceType,
          uploadedFileName = fileName,
          generateQuestionsImmediately = generateQuestions
        )
        showAddDialog = false
      }
    )
  }

  // Topic Details & Spaced Repetition Dialog
  detailTopic?.let { topic -> TopicDetailHost(viewModel, topic) { detailTopic = it } }

}

@Composable
fun TopicCard(
  topic: StudyTopic,
  onClick: () -> Unit,
  onDelete: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onClick() },
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.Top
    ) {

      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            modifier = Modifier
              .weight(1f, fill = false)
              .padding(end = 8.dp)
          ) {
            Text(
              text = topic.subjectName,
              style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            MasteryBadge(level = topic.masteryLevel)
            IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
              Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = topic.title,
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = topic.descriptionNotes.take(120) + if (topic.descriptionNotes.length > 120) "..." else "",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Studied: ${topic.dateStudied} • Interval: ${topic.repetitionIntervalDays}d",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
              .weight(1f)
              .padding(end = 8.dp)
          )
          Text(
            text = "Next: ${topic.nextRevisionDate}",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTopicDialog(
  subjects: List<Subject>,
  /** Subject to start with: an opened subject, [SubjectGroup.GENERAL_ID] for none, or null for the first. */
  initialSubjectId: Long? = null,
  onDismiss: () -> Unit,
  onConfirm: (Long, String, String, String, String, String?, Boolean) -> Unit
) {
  var selectedTab by remember { mutableIntStateOf(0) } // 0: Manual, 1: Upload Materials, 2: Paste Lecture Notes
  var selectedSubject by remember {
    mutableStateOf(if (initialSubjectId == null) subjects.firstOrNull() else subjects.firstOrNull { it.id == initialSubjectId })
  }
  var title by remember { mutableStateOf("") }
  var manualNotes by remember { mutableStateOf("") }
  var pastedNotes by remember { mutableStateOf("") }
  var uploadedFileName by remember { mutableStateOf<String?>(null) }
  var uploadedFileContent by remember { mutableStateOf("") }
  var generateQuestionsImmediate by remember { mutableStateOf(true) }

  var expandedDropdown by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add topic", fontWeight = FontWeight.Bold) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        // 3 Input Methods Tabs
        TabRow(
          selectedTabIndex = selectedTab,
          containerColor = MaterialTheme.colorScheme.surfaceVariant,
          contentColor = MaterialTheme.colorScheme.primary
        ) {
          Tab(
            selected = selectedTab == 0,
            onClick = { selectedTab = 0 },
            text = { Text("Manual", fontSize = 12.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
          )
          Tab(
            selected = selectedTab == 1,
            onClick = { selectedTab = 1 },
            text = { Text("Upload", fontSize = 12.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
          )
          Tab(
            selected = selectedTab == 2,
            onClick = { selectedTab = 2 },
            text = { Text("Paste", fontSize = 12.sp, fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) }
          )
        }

        // Subject selector
        ExposedDropdownMenuBox(
          expanded = expandedDropdown,
          onExpandedChange = { expandedDropdown = !expandedDropdown }
        ) {
          OutlinedTextField(
            value = selectedSubject?.name ?: "General (no subject)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Subject") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .menuAnchor(MenuAnchorType.PrimaryNotEditable)
          )
          ExposedDropdownMenu(
            expanded = expandedDropdown,
            onDismissRequest = { expandedDropdown = false }
          ) {
            subjects.forEach { sub ->
              DropdownMenuItem(
                text = { Text("${sub.code}: ${sub.name}") },
                onClick = {
                  selectedSubject = sub
                  expandedDropdown = false
                }
              )
            }
            DropdownMenuItem(
              text = { Text("General (no subject)") },
              onClick = {
                selectedSubject = null
                expandedDropdown = false
              }
            )
          }
        }

        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Topic Title (e.g. Graph Algorithms)") },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )

        when (selectedTab) {
          0 -> {
            OutlinedTextField(
              value = manualNotes,
              onValueChange = { manualNotes = it },
              label = { Text("Notes") },
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth(),
              minLines = 3,
              maxLines = 5
            )
          }
          1 -> {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(16.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Icon(Icons.Default.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = uploadedFileName ?: "Attach slides or lecture PDF",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
              )
              Spacer(modifier = Modifier.height(8.dp))
              Button(
                onClick = {
                  uploadedFileName = "lecture_${title.ifBlank { "notes" }}.pdf"
                  uploadedFileContent = "Key definitions and theorem slides on $title with theoretical proofs, algorithms, and application problems."
                },
                shape = RoundedCornerShape(10.dp)
              ) {
                Text(if (uploadedFileName == null) "Select Study Material" else "File Attached")
              }
            }
          }
          2 -> {
            OutlinedTextField(
              value = pastedNotes,
              onValueChange = { pastedNotes = it },
              label = { Text("Paste Lecture Slides or Text Here") },
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth(),
              minLines = 4,
              maxLines = 6
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (title.isNotBlank()) {
            val subName = selectedSubject?.name ?: "General"
            val subId = selectedSubject?.id ?: SubjectGroup.GENERAL_ID
            val (notes, type, fileName) = when (selectedTab) {
              0 -> Triple(manualNotes.ifBlank { "Studied core concepts of $title." }, "MANUAL", null)
              1 -> Triple(uploadedFileContent.ifBlank { "Uploaded slides on $title." }, "UPLOAD", uploadedFileName)
              else -> Triple(pastedNotes.ifBlank { "Pasted notes on $title." }, "PASTED", null)
            }
            onConfirm(subId, subName, title, notes, type, fileName, generateQuestionsImmediate)
          }
        },
        shape = RoundedCornerShape(10.dp)
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicDetailDialog(
  topic: StudyTopic,
  subjects: List<Subject> = emptyList(),
  onMoveToSubject: (Subject?) -> Unit = {},
  isAiLoading: Boolean,
  onDismiss: () -> Unit,
  onSummarizeAi: () -> Unit,
  onUpdateMastery: (String) -> Unit,
  onAskTutor: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(topic.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        MasteryBadge(level = topic.masteryLevel)
      }
    },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        item {
          Text(
            text = "Recorded ${topic.dateStudied}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        item {
          var expanded by remember { mutableStateOf(false) }
          val current = subjects.firstOrNull { it.id == topic.subjectId }
          ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
              value = current?.name ?: "General (no subject)",
              onValueChange = {},
              readOnly = true,
              label = { Text("Subject") },
              trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
              subjects.forEach { sub ->
                DropdownMenuItem(
                  text = { Text(sub.name) },
                  onClick = {
                    expanded = false
                    if (sub.id != topic.subjectId) onMoveToSubject(sub)
                  }
                )
              }
              DropdownMenuItem(
                text = { Text("General (no subject)") },
                onClick = {
                  expanded = false
                  if (current != null) onMoveToSubject(null)
                }
              )
            }
          }
        }

        item {
          Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text("Lecture notes", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
              Spacer(modifier = Modifier.height(4.dp))
              Text(topic.descriptionNotes, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }

        // Spaced Repetition Stats Card
        item {
          Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text("Spaced repetition schedule", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.height(4.dp))
              Text("• Current Interval: ${topic.repetitionIntervalDays} days", style = MaterialTheme.typography.bodySmall)
              Text("• Next Revision Due: ${topic.nextRevisionDate}", style = MaterialTheme.typography.bodySmall)
              Text("• Times Tested: ${topic.timesTested} (${topic.timesCorrect} correct)", style = MaterialTheme.typography.bodySmall)
            }
          }
        }

        // AI Summary if available
        if (!topic.aiSummary.isNullOrBlank()) {
          item {
            Card(
              shape = RoundedCornerShape(12.dp),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(8.dp))
                  Text("AI Concept Summary", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(topic.aiSummary, style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }

        // AI Summarize Action Button
        item {
          Button(
            onClick = onSummarizeAi,
            enabled = !isAiLoading,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            if (isAiLoading) {
              CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
              Spacer(modifier = Modifier.width(8.dp))
              Text("Analyzing Material...")
            } else {
              Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Summarize with AI")
            }
          }
        }

        // Ask AI Tutor Action Button
        item {
          OutlinedButton(
            onClick = onAskTutor,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Ask AI Tutor about this Topic")
          }
        }

        // Manual Spaced Repetition Overrides
        item {
          Column {
            Text("Manual Mastery Adjustment:", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedButton(
                onClick = { onUpdateMastery("MASTERED") },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
              ) {
                Text("Understood", fontSize = 11.sp, color = EmeraldSuccess, maxLines = 1, softWrap = false)
              }
              OutlinedButton(
                onClick = { onUpdateMastery("STRUGGLING") },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
              ) {
                Text("Needs review", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Done")
      }
    }
  )
}

/** The topic details dialog, shared by the Topics page and the Today page's Recent list. */
@Composable
fun TopicDetailHost(viewModel: StudyPilotViewModel, topic: StudyTopic, onTopic: (StudyTopic?) -> Unit) {
  val subjects by viewModel.subjects.collectAsState()
  val isAiLoading by viewModel.isAiLoading.collectAsState()

    TopicDetailDialog(
      topic = topic,
      subjects = subjects,
      onMoveToSubject = { subject ->
        viewModel.moveTopicToSubject(topic, subject)
        onTopic(topic.copy(subjectId = subject?.id ?: SubjectGroup.GENERAL_ID, subjectName = subject?.name ?: "General"))
      },
      isAiLoading = isAiLoading,
      onDismiss = { onTopic(null) },
      onSummarizeAi = {
        viewModel.summarizeTopicWithAi(topic.title, topic.descriptionNotes) { summary ->
          onTopic(topic.copy(aiSummary = summary))
        }
      },
      onUpdateMastery = { newMastery ->
        viewModel.updateTopicMastery(topic, newMastery)
        onTopic(topic.copy(masteryLevel = newMastery))
      },
      onAskTutor = {
        onTopic(null)
        viewModel.openTutorForSubject(topic.subjectId, "Can you explain '${topic.title}' from ${topic.subjectName} in detail?")
      }
    )
}
