package com.example.ui.screens

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ai.LocalAiEngine
import com.example.data.model.ExtractedConcept
import com.example.data.model.StudyNote
import com.example.data.notes.QaPair
import com.example.data.notes.QaPairs
import com.example.data.notes.QaThread
import com.example.platform.LocalPlatformActions
import com.example.ui.HeaderControls
import com.example.ui.components.EmptyState
import com.example.ui.components.FormattedTutorResponse
import com.example.ui.components.countOf
import com.example.ui.theme.Button
import com.example.ui.theme.Card
import com.example.ui.theme.OutlinedButton
import com.example.ui.theme.TabRow
import com.example.ui.theme.dialogBackground
import com.example.ui.viewmodel.StudyPilotViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun friendlyTime(at: Long): String = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(at))

/**
 * Notes: what the user wrote themselves, their tutor chats as questions and answers, and what was
 * pulled from imported chat links (the conversation as Q&A plus the concepts found in it).
 */
@Composable
fun NotesScreen(viewModel: StudyPilotViewModel) {
  val notes by viewModel.notes.collectAsState()
  val chats by viewModel.importedChats.collectAsState()
  val concepts by viewModel.extractedConcepts.collectAsState()
  val subjects by viewModel.subjects.collectAsState()
  var tab by rememberSaveable { mutableIntStateOf(0) }
  var query by rememberSaveable { mutableStateOf("") }
  var editing by remember { mutableStateOf<StudyNote?>(null) }

  // Tutor chats are read when the tab opens, so new answers show up.
  val tutorThreads by produceState(emptyList<QaThread>(), tab) { if (tab == 1) value = viewModel.tutorQaThreads() }
  val linkThreads = remember(chats) {
    chats.sortedByDescending { it.importedAt }.map { chat ->
      chat.id to QaThread(chat.title, chat.subjectName, chat.aiPlatform, chat.importedAt, QaPairs.fromConversation(chat.rawConversation), chat.sourceUrl)
    }
  }
  val conceptsByChat = remember(concepts) { concepts.groupBy { it.sourceChatId } }

  val q = query.trim()
  fun matches(text: String) = q.isEmpty() || text.contains(q, ignoreCase = true)
  val shownNotes = notes.filter { matches(it.title) || matches(it.body) || matches(it.subjectName) }
  fun threadMatches(t: QaThread) = matches(t.title) || matches(t.subjectName) || t.pairs.any { matches(it.question) || matches(it.answer) }

  val newNote = { editing = StudyNote(title = "", body = "") }
  val tabs = listOf("My notes", "Tutor Q&A", "From links")
  val tabRow: @Composable () -> Unit = {
    TabRow(selectedTabIndex = tab, modifier = Modifier.padding(horizontal = 16.dp)) {
      tabs.forEachIndexed { i, label ->
        Tab(
          selected = tab == i,
          onClick = { tab = i },
          text = {
            Text(
              label,
              fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium,
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis
            )
          }
        )
      }
    }
  }

  Column(Modifier.fillMaxSize()) {
    if (!HeaderControls { Box(Modifier.widthIn(max = 560.dp)) { tabRow() } }) tabRow()

    // Search across the open tab, and a new note on the first.
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(if (tab == 0) "Search your notes…" else "Search questions and answers…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.weight(1f)
      )
      if (tab == 0) {
        Button(onClick = newNote, modifier = Modifier.heightIn(min = 48.dp)) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("New note", maxLines = 1)
        }
      }
    }

    when (tab) {
      0 -> if (notes.isEmpty()) {
        EmptyState(
          icon = Icons.Default.EditNote,
          title = "No notes yet",
          description = "Write down what you learned, in your own words. Notes sync between your phone and PC.",
          actionButton = { Button(onClick = newNote) { Text("Write a note") } },
          modifier = Modifier.fillMaxSize()
        )
      } else {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 300.dp),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.fillMaxSize()
        ) {
          items(shownNotes, key = { it.id }) { note -> NoteCard(note) { editing = note } }
        }
      }
      1 -> ThreadList(
        threads = tutorThreads.filter(::threadMatches),
        emptyTitle = "No tutor chats yet",
        emptyText = "Questions you ask the tutor are kept here as questions and answers.",
        onSaveAsNote = { thread, pair -> viewModel.saveNote(noteFrom(thread, pair)) }
      )
      2 -> ThreadList(
        threads = linkThreads.map { it.second }.filter(::threadMatches),
        emptyTitle = "No imported chats yet",
        emptyText = "Import a shared ChatGPT, Claude, Gemini or DeepSeek link in Chats. Its questions, answers and key concepts show up here.",
        onSaveAsNote = { thread, pair -> viewModel.saveNote(noteFrom(thread, pair)) },
        conceptsFor = { thread -> linkThreads.firstOrNull { it.second == thread }?.first?.let { conceptsByChat[it] }.orEmpty() }
      )
    }
  }

  editing?.let { note ->
    NoteEditor(
      note = note,
      subjectNames = listOf("General") + subjects.map { it.name }.filter { it.isNotBlank() && it != "General" },
      onSave = { viewModel.saveNote(it); editing = null },
      onDelete = { viewModel.deleteNote(note.id); editing = null },
      onDismiss = { editing = null }
    )
  }
}

/** A question and answer kept as a note: the question as its title, both in the body. */
private fun noteFrom(thread: QaThread, pair: QaPair) = StudyNote(
  title = pair.question.lineSequence().first().take(80),
  body = "**Q:** ${pair.question}\n\n${pair.answer}",
  subjectName = thread.subjectName.ifBlank { "General" }
)

@Composable
private fun NoteCard(note: StudyNote, onOpen: () -> Unit) {
  Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        SubjectTag(note.subjectName)
        Spacer(Modifier.weight(1f))
        if (note.pinned) Icon(Icons.Default.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
      }
      Text(
        note.title.ifBlank { "Untitled" },
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
      val preview = LocalAiEngine.plainNotes(note.body).joinToString(" · ")
      if (preview.isNotBlank()) {
        Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
      }
      Text("Edited ${friendlyTime(note.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun SubjectTag(name: String) {
  Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
    Text(
      name.ifBlank { "General" },
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      maxLines = 1
    )
  }
}

/** Conversations as cards; opening one shows each question with its formatted answer. */
@Composable
private fun ThreadList(
  threads: List<QaThread>,
  emptyTitle: String,
  emptyText: String,
  onSaveAsNote: (QaThread, QaPair) -> Unit,
  conceptsFor: (QaThread) -> List<ExtractedConcept> = { emptyList() }
) {
  if (threads.isEmpty()) {
    EmptyState(icon = Icons.Default.EditNote, title = emptyTitle, description = emptyText, modifier = Modifier.fillMaxSize())
    return
  }
  var open by rememberSaveable { mutableStateOf<String?>(null) }
  LazyColumn(
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxSize()
  ) {
    items(threads, key = { "${it.source}|${it.title}|${it.at}" }) { thread ->
      val id = "${thread.source}|${thread.title}|${thread.at}"
      ThreadCard(
        thread = thread,
        expanded = open == id,
        concepts = conceptsFor(thread),
        onToggle = { open = if (open == id) null else id },
        onSaveAsNote = { onSaveAsNote(thread, it) }
      )
    }
  }
}

@Composable
private fun ThreadCard(
  thread: QaThread,
  expanded: Boolean,
  concepts: List<ExtractedConcept>,
  onToggle: () -> Unit,
  onSaveAsNote: (QaPair) -> Unit
) {
  val actions = LocalPlatformActions.current
  Card(onClick = onToggle, modifier = Modifier.fillMaxWidth().animateContentSize()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubjectTag(thread.source)
            SubjectTag(thread.subjectName)
          }
          Text(thread.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
          Text(
            "${countOf(thread.pairs.size, "question")} · ${friendlyTime(thread.at)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Close" else "Open")
      }
      if (expanded) {
        if (thread.url.isNotBlank()) {
          TextButton(onClick = { actions.openUrl(thread.url) }) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open the original chat")
          }
        }
        thread.pairs.forEachIndexed { i, pair ->
          if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pair.question, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            if (pair.answer.isNotBlank()) FormattedTutorResponse(pair.answer)
            else Text("No answer saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onSaveAsNote(pair) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
              Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Save to my notes")
            }
          }
        }
        if (concepts.isNotEmpty()) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
          Text("Key concepts", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
          concepts.forEach { c ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(c.conceptName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
              if (c.definition.isNotBlank()) Text(c.definition, style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }
    }
  }
}

/** Writing or editing a note: title, subject, the text (Markdown works), and pinning. */
@Composable
private fun NoteEditor(
  note: StudyNote,
  subjectNames: List<String>,
  onSave: (StudyNote) -> Unit,
  onDelete: () -> Unit,
  onDismiss: () -> Unit
) {
  var title by remember { mutableStateOf(note.title) }
  var body by remember { mutableStateOf(note.body) }
  var subject by remember { mutableStateOf(note.subjectName.ifBlank { "General" }) }
  var pinned by remember { mutableStateOf(note.pinned) }
  var preview by remember { mutableStateOf(false) }
  val titleFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { if (note.id == 0L) runCatching { titleFocus.requestFocus() } }
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = dialogBackground(MaterialTheme.colorScheme.surface),
    title = { Text(if (note.id == 0L) "New note" else "Edit note", fontWeight = FontWeight.Bold) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(titleFocus))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          (subjectNames + listOfNotNull(subject.takeIf { it !in subjectNames })).forEach { name ->
            FilterChip(selected = subject == name, onClick = { subject = name }, label = { Text(name, maxLines = 1) })
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(selected = !preview, onClick = { preview = false }, label = { Text("Write") })
          FilterChip(selected = preview, onClick = { preview = true }, label = { Text("Preview") })
        }
        if (preview) {
          Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)) {
            Box(Modifier.padding(12.dp)) {
              if (body.isBlank()) Text("Nothing to preview yet.", style = MaterialTheme.typography.bodySmall)
              else FormattedTutorResponse(body)
            }
          }
        } else {
          OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            placeholder = { Text("Write it in your own words. Markdown works: # heading, - list, **bold**.") },
            minLines = 8,
            maxLines = 18,
            modifier = Modifier.fillMaxWidth()
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(CircleShape)) {
          Checkbox(checked = pinned, onCheckedChange = { pinned = it })
          Text("Pin to the top", style = MaterialTheme.typography.bodyMedium)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(note.copy(title = title, body = body, subjectName = subject, pinned = pinned)) },
        enabled = title.isNotBlank() || body.isNotBlank()
      ) { Text("Save") }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (note.id != 0L) OutlinedButton(onClick = onDelete) { Text("Delete") }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    }
  )
}
