package com.example.ui.screens

import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Check
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.example.platform.parseHexColor
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

private const val GENERAL = "General"
private const val NO_CHAPTER = "Other notes"
private fun subjectOf(name: String) = name.trim().ifBlank { GENERAL }
private fun sameSubject(a: String, b: String) = subjectOf(a).equals(subjectOf(b), ignoreCase = true)

/** One subject on the Notes front page, with how much is filed under it. */
private data class SubjectShelf(val name: String, val colorHex: String?, val notes: Int, val tutor: Int, val links: Int) {
  val isEmpty get() = notes + tutor + links == 0
}

/**
 * Notes, filed by subject. Each subject holds the user's own notes (chapter > topic > text), their
 * tutor chats as questions and answers, and what was pulled from imported chat links.
 */
@Composable
fun NotesScreen(viewModel: StudyPilotViewModel) {
  val notes by viewModel.notes.collectAsState()
  val chats by viewModel.importedChats.collectAsState()
  val concepts by viewModel.extractedConcepts.collectAsState()
  val subjects by viewModel.subjects.collectAsState()
  var openSubject by rememberSaveable { mutableStateOf<String?>(null) }
  var editing by remember { mutableStateOf<StudyNote?>(null) }

  // Tutor chats are read whenever the page or a subject opens, so new answers show up.
  val tutorThreads by produceState(emptyList<QaThread>(), openSubject) { value = viewModel.tutorQaThreads() }
  val linkThreads = remember(chats) {
    chats.sortedByDescending { it.importedAt }.map { chat ->
      chat.id to QaThread(chat.title, subjectOf(chat.subjectName), chat.aiPlatform, chat.importedAt, QaPairs.fromConversation(chat.rawConversation), chat.sourceUrl)
    }
  }
  val conceptsByChat = remember(concepts) { concepts.groupBy { it.sourceChatId } }

  val shelves = remember(subjects, notes, tutorThreads, linkThreads) {
    val names = LinkedHashMap<String, String>() // lowercase -> display name, in the subjects' own order
    val colors = HashMap<String, String>()
    subjects.filter { it.name.isNotBlank() }.forEach {
      names.putIfAbsent(it.name.trim().lowercase(), it.name.trim())
      colors[it.name.trim().lowercase()] = it.colorHex
    }
    (notes.map { it.subjectName } + tutorThreads.map { it.subjectName } + linkThreads.map { it.second.subjectName } + GENERAL)
      .map(::subjectOf).forEach { names.putIfAbsent(it.lowercase(), it) }
    names.values.map { name ->
      SubjectShelf(
        name = name,
        colorHex = colors[name.lowercase()],
        notes = notes.count { sameSubject(it.subjectName, name) },
        tutor = tutorThreads.count { sameSubject(it.subjectName, name) },
        links = linkThreads.count { sameSubject(it.second.subjectName, name) }
      )
    }
  }

  val current = openSubject
  if (current == null) {
    SubjectShelves(
      shelves = shelves,
      onOpen = { openSubject = it },
      onNewNote = { editing = StudyNote(title = "", body = "", subjectName = GENERAL) }
    )
  } else {
    SubjectNotes(
      subject = current,
      notes = notes.filter { sameSubject(it.subjectName, current) },
      tutorThreads = tutorThreads.filter { sameSubject(it.subjectName, current) },
      linkThreads = linkThreads.filter { sameSubject(it.second.subjectName, current) },
      conceptsByChat = conceptsByChat,
      onBack = { openSubject = null },
      onOpenNote = { editing = it },
      onNewNote = { chapter -> editing = StudyNote(title = chapter, body = "", subjectName = current) },
      onSaveAsNote = { thread, pair -> viewModel.saveNote(noteFrom(thread, pair)) }
    )
  }

  editing?.let { note ->
    NoteEditor(
      note = note,
      subjectNames = shelves.map { it.name },
      chaptersIn = { subject -> notes.filter { sameSubject(it.subjectName, subject) }.map { it.title.trim() }.filter { it.isNotBlank() }.distinct() },
      onSave = { viewModel.saveNote(it); editing = null },
      onDelete = { viewModel.deleteNote(note.id); editing = null },
      onDismiss = { editing = null }
    )
  }
}

/** A tutor or imported answer kept as a note: the conversation is the chapter, the question the topic. */
private fun noteFrom(thread: QaThread, pair: QaPair) = StudyNote(
  title = thread.title.lineSequence().first().take(80),
  heading = pair.question.lineSequence().first().take(140),
  body = LocalAiEngine.plainNotes(pair.answer).joinToString("\n"),
  subjectName = subjectOf(thread.subjectName)
)

@Composable
private fun NewNoteButton(onClick: () -> Unit) {
  Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text("New note", maxLines = 1)
  }
}

/** The front page: one card per subject. */
@Composable
private fun SubjectShelves(shelves: List<SubjectShelf>, onOpen: (String) -> Unit, onNewNote: () -> Unit) {
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Subjects", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
      NewNoteButton(onNewNote)
    }
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 260.dp),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize()
    ) {
      items(shelves, key = { it.name.lowercase() }) { shelf ->
        Card(onClick = { onOpen(shelf.name) }, modifier = Modifier.fillMaxWidth()) {
          Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val tint = shelf.colorHex?.let(::parseHexColor) ?: MaterialTheme.colorScheme.primary
            Box(
              Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.18f)),
              contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Folder, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Text(shelf.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(
                if (shelf.isEmpty) "Nothing here yet"
                else listOfNotNull(
                  shelf.notes.takeIf { it > 0 }?.let { countOf(it, "note") },
                  shelf.tutor.takeIf { it > 0 }?.let { countOf(it, "tutor chat") },
                  shelf.links.takeIf { it > 0 }?.let { countOf(it, "link") }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
              )
            }
          }
        }
      }
    }
  }
}

/** Inside a subject: the user's notes, tutor Q&A and imported chats, each in a tab. */
@Composable
private fun SubjectNotes(
  subject: String,
  notes: List<StudyNote>,
  tutorThreads: List<QaThread>,
  linkThreads: List<Pair<Long, QaThread>>,
  conceptsByChat: Map<Long, List<ExtractedConcept>>,
  onBack: () -> Unit,
  onOpenNote: (StudyNote) -> Unit,
  onNewNote: (chapter: String) -> Unit,
  onSaveAsNote: (QaThread, QaPair) -> Unit
) {
  var tab by rememberSaveable(subject) { mutableIntStateOf(0) }
  var query by rememberSaveable(subject) { mutableStateOf("") }
  val q = query.trim()
  fun matches(text: String) = q.isEmpty() || text.contains(q, ignoreCase = true)
  fun threadMatches(t: QaThread) = matches(t.title) || t.pairs.any { matches(it.question) || matches(it.answer) }

  val tabs = listOf("My notes", "Tutor Q&A", "From links")
  val tabRow: @Composable () -> Unit = {
    TabRow(selectedTabIndex = tab, modifier = Modifier.padding(horizontal = 16.dp)) {
      tabs.forEachIndexed { i, label ->
        Tab(
          selected = tab == i,
          onClick = { tab = i },
          text = { Text(label, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
        )
      }
    }
  }

  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All subjects") }
      Text(subject, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (!HeaderControls { Box(Modifier.widthIn(max = 560.dp)) { tabRow() } }) tabRow()

    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(if (tab == 0) "Search chapters, topics, notes…" else "Search questions and answers…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.weight(1f)
      )
      if (tab == 0) NewNoteButton { onNewNote("") }
    }

    when (tab) {
      0 -> ChapterNotes(
        notes = notes.filter { matches(it.title) || matches(it.heading) || matches(it.body) },
        empty = notes.isEmpty(),
        onOpen = onOpenNote,
        onNewNote = onNewNote
      )
      1 -> ThreadList(
        threads = tutorThreads.filter(::threadMatches),
        emptyTitle = "No tutor chats in $subject",
        emptyText = "Questions you ask the tutor about $subject are kept here as questions and answers.",
        onSaveAsNote = onSaveAsNote
      )
      2 -> ThreadList(
        threads = linkThreads.map { it.second }.filter(::threadMatches),
        emptyTitle = "No imported chats in $subject",
        emptyText = "Import a shared ChatGPT, Claude, Gemini or DeepSeek link in Chats and file it under $subject. Its questions, answers and key concepts show up here.",
        onSaveAsNote = onSaveAsNote,
        conceptsFor = { thread -> linkThreads.firstOrNull { it.second == thread }?.first?.let { conceptsByChat[it] }.orEmpty() }
      )
    }
  }
}

/** The user's notes in a subject, grouped by chapter, each topic a card. */
@Composable
private fun ChapterNotes(notes: List<StudyNote>, empty: Boolean, onOpen: (StudyNote) -> Unit, onNewNote: (String) -> Unit) {
  if (empty) {
    EmptyState(
      icon = Icons.Default.EditNote,
      title = "No notes yet",
      description = "Give it a chapter as the title, a topic as the heading, then write what you learned in your own words.",
      actionButton = { Button(onClick = { onNewNote("") }) { Text("Write a note") } },
      modifier = Modifier.fillMaxSize()
    )
    return
  }
  if (notes.isEmpty()) {
    EmptyState(icon = Icons.Default.Search, title = "Nothing matches", description = "Try another word.", modifier = Modifier.fillMaxSize())
    return
  }
  // Chapters by when they were last worked on; pinned topics first inside each.
  val chapters = notes.groupBy { it.title.trim().ifBlank { NO_CHAPTER } }
    .entries.sortedByDescending { (_, list) -> list.maxOf { it.updatedAt } }
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 280.dp),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxSize()
  ) {
    chapters.forEachIndexed { i, (chapter, list) ->
      item(key = "chapter:$chapter", span = { GridItemSpan(maxLineSpan) }) {
        Row(Modifier.fillMaxWidth().padding(top = if (i == 0) 0.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(
              if (chapter == NO_CHAPTER) "NO CHAPTER" else "CHAPTER · ${countOf(list.size, "topic").uppercase()}",
              style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(chapter, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
          TextButton(onClick = { onNewNote(if (chapter == NO_CHAPTER) "" else chapter) }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add topic")
          }
        }
      }
      items(list.sortedWith(compareByDescending<StudyNote> { it.pinned }.thenByDescending { it.updatedAt }), key = { it.id }) { note ->
        TopicCard(note) { onOpen(note) }
      }
    }
  }
}

@Composable
private fun TopicCard(note: StudyNote, onOpen: () -> Unit) {
  Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        Text(
          note.heading.ifBlank { "Untitled topic" },
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        if (note.pinned) Icon(Icons.Default.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).size(16.dp))
      }
      if (note.body.isNotBlank()) {
        Text(note.body.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
      }
      Text("Edited ${friendlyTime(note.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun SmallTag(name: String) {
  Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
    Text(
      name,
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
  // Answers saved from this card while it's on screen, so the button can say so.
  var saved by remember(thread) { mutableStateOf(emptySet<Int>()) }
  Card(onClick = onToggle, modifier = Modifier.fillMaxWidth().animateContentSize()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          SmallTag(thread.source)
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
            Spacer(Modifier.height(2.dp))
            if (i in saved) {
              OutlinedButton(onClick = {}, enabled = false, contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.heightIn(min = 40.dp)) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Saved to my notes", fontWeight = FontWeight.SemiBold)
              }
            } else {
              Button(
                onClick = { onSaveAsNote(pair); saved = saved + i },
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.heightIn(min = 40.dp)
              ) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save to my notes", fontWeight = FontWeight.SemiBold)
              }
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

/** Writing or editing a note: subject, then title (chapter), heading (topic) and the text itself. */
@Composable
private fun NoteEditor(
  note: StudyNote,
  subjectNames: List<String>,
  chaptersIn: (String) -> List<String>,
  onSave: (StudyNote) -> Unit,
  onDelete: () -> Unit,
  onDismiss: () -> Unit
) {
  var chapter by remember { mutableStateOf(note.title) }
  var topic by remember { mutableStateOf(note.heading) }
  var body by remember { mutableStateOf(note.body) }
  var subject by remember { mutableStateOf(subjectOf(note.subjectName)) }
  var pinned by remember { mutableStateOf(note.pinned) }
  // A new note starts in the first empty field: the title, or the heading when the chapter is known.
  val chapterFocus = remember { FocusRequester() }
  val topicFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    if (note.id == 0L) runCatching { if (note.title.isBlank()) chapterFocus.requestFocus() else topicFocus.requestFocus() }
  }
  val otherChapters = chaptersIn(subject).filter { !it.equals(chapter.trim(), ignoreCase = true) }
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = dialogBackground(MaterialTheme.colorScheme.surface),
    title = { Text(if (note.id == 0L) "New note" else "Edit note", fontWeight = FontWeight.Bold) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          (subjectNames + listOfNotNull(subject.takeIf { s -> subjectNames.none { sameSubject(it, s) } })).forEach { name ->
            FilterChip(selected = sameSubject(subject, name), onClick = { subject = name }, label = { Text(name, maxLines = 1) })
          }
        }
        OutlinedTextField(
          value = chapter,
          onValueChange = { chapter = it },
          label = { Text("Title · chapter") },
          placeholder = { Text("e.g. Thermodynamics") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().focusRequester(chapterFocus)
        )
        if (otherChapters.isNotEmpty()) {
          Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            otherChapters.forEach { c -> FilterChip(selected = false, onClick = { chapter = c }, label = { Text(c, maxLines = 1) }) }
          }
        }
        OutlinedTextField(
          value = topic,
          onValueChange = { topic = it },
          label = { Text("Heading · topic") },
          placeholder = { Text("e.g. First law") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().focusRequester(topicFocus)
        )
        OutlinedTextField(
          value = body,
          onValueChange = { body = it },
          label = { Text("Notes") },
          placeholder = { Text("Write it in your own words.") },
          minLines = 8,
          maxLines = 18,
          modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(CircleShape)) {
          Checkbox(checked = pinned, onCheckedChange = { pinned = it })
          Text("Pin to the top of the chapter", style = MaterialTheme.typography.bodyMedium)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(note.copy(title = chapter.trim(), heading = topic.trim(), body = body, subjectName = subject, pinned = pinned)) },
        enabled = chapter.isNotBlank() || topic.isNotBlank() || body.isNotBlank()
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
