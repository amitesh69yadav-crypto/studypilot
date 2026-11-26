package com.example.ui.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.ai.ChatKnowledgeExtractor
import com.example.data.model.ExtractedConcept
import com.example.data.model.ImportedChat
import com.example.data.model.Subject
import com.example.ui.theme.*
import com.example.ui.viewmodel.StudyPilotViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PaletteCreamIvory: Color
  @Composable get() = MaterialTheme.colorScheme.surface

private val PaletteDarkText: Color
  @Composable get() = MaterialTheme.colorScheme.onSurface

private val PaletteDeepSage: Color
  @Composable get() = MaterialTheme.colorScheme.primary

private val PaletteMediumSage: Color
  @Composable get() = MaterialTheme.colorScheme.outlineVariant

private val PaletteMintFoam: Color
  @Composable get() = MaterialTheme.colorScheme.surfaceVariant

private val PalettePaleCeladon: Color
  @Composable get() = MaterialTheme.colorScheme.primaryContainer

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiChatImportScreen(
  viewModel: StudyPilotViewModel,
  modifier: Modifier = Modifier
) {
  var selectedTab by remember { mutableIntStateOf(0) }
  val tabs = listOf("Chat Library", "Learning Memory", "Coverage & Stats", "Import AI Chat")

  val importedChats by viewModel.importedChats.collectAsState()
  val extractedConcepts by viewModel.extractedConcepts.collectAsState()
  val subjects by viewModel.subjects.collectAsState()
  val isProcessing by viewModel.isImportProcessing.collectAsState()
  val progress by viewModel.importProcessingProgress.collectAsState()
  val progressStatus by viewModel.importProcessingStatusText.collectAsState()
  val extractionPreview by viewModel.extractionPreview.collectAsState()
  val previewSubject by viewModel.previewSubject.collectAsState()
  val previewSubjectSuggestion by viewModel.previewSubjectSuggestion.collectAsState()
  val previewSourceUrl by viewModel.previewSourceUrl.collectAsState()

  var inspectingChat by remember { mutableStateOf<ImportedChat?>(null) }
  var editingChat by remember { mutableStateOf<ImportedChat?>(null) }
  var movingChat by remember { mutableStateOf<ImportedChat?>(null) }
  var editingConcept by remember { mutableStateOf<ExtractedConcept?>(null) }
  var pendingManualNewSubjectName by remember { mutableStateOf("") }
  var pendingManualNewSubjectCode by remember { mutableStateOf("") }

  Scaffold(

    // The app's main Scaffold already applies system bar insets.

    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    containerColor = PaletteCreamIvory,
    topBar = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(PaletteCreamIvory)
      ) {
        ScrollableTabRow(
          selectedTabIndex = selectedTab,
          containerColor = PaletteCreamIvory,
          contentColor = PaletteDarkText,
          edgePadding = 16.dp,
          indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
              modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
              color = PaletteDeepSage,
              height = 3.dp
            )
          }
        ) {
          tabs.forEachIndexed { index, title ->
            Tab(
              selected = selectedTab == index,
              onClick = { selectedTab = index },
              text = {
                Text(
                  text = when (index) {
                    0 -> "Chats (${importedChats.size})"
                    1 -> "Memory (${extractedConcepts.size})"
                    2 -> "Coverage"
                    else -> "Import"
                  },
                  fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium,
                  color = if (selectedTab == index) PaletteDarkText else PaletteDarkText.copy(alpha = 0.6f),
                  maxLines = 1,
                  softWrap = false
                )
              },
              icon = {
                when (index) {
                  0 -> Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                  1 -> Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                  2 -> Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(18.dp))
                  else -> Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                }
              }
            )
          }
        }
      }
    }
  ) { paddingValues ->
    Box(
      modifier = modifier
        .fillMaxSize()
        .padding(paddingValues)
    ) {
      when (selectedTab) {
        0 -> ChatLibraryTab(
          chats = importedChats,
          subjects = subjects,
          onInspect = { inspectingChat = it },
          onEdit = { editingChat = it },
          onMoveSubject = { movingChat = it },
          onDelete = { viewModel.deleteImportedChat(it.id) },
          onReprocess = { viewModel.reprocessImportedChat(it) },
          onNavigateToImport = { selectedTab = 3 }
        )
        1 -> LearningMemoryTab(
          concepts = extractedConcepts,
          subjects = subjects,
          onDrillQuiz = { viewModel.startQuizForConcept(it) },
          onInspectSource = { concept ->
            val chat = importedChats.find { it.id == concept.sourceChatId }
            if (chat != null) {
              inspectingChat = chat
            } else {
              inspectingChat = ImportedChat(
                id = concept.sourceChatId,
                userId = "",
                title = concept.sourceChatTitle,
                subjectId = concept.subjectId,
                subjectName = concept.subjectName,
                rawConversation = "Source reference: ${concept.sourceChatTitle}\nTopic: ${concept.topic} - ${concept.subtopic}\nConcept: ${concept.conceptName}\n\nDefinition:\n${concept.definition}\n\nTechnical details:\n${concept.technicalDetails}\n\nCode / formula:\n${concept.formulaOrCode}",
                aiPlatform = "AI Chat",
                notes = concept.definition
              )
            }
          },
          onEdit = { editingConcept = it },
          onDelete = { viewModel.deleteExtractedConcept(it.id) }
        )
        2 -> KnowledgeCoverageDashboardTab(
          chats = importedChats,
          concepts = extractedConcepts,
          subjects = subjects,
          onOpenTopic = { selectedTab = 1 }
        )
        3 -> ImportChatTab(
          subjects = subjects,
          isProcessing = isProcessing,
          progress = progress,
          progressStatus = progressStatus,
          onFetchLink = { url, title, subject, tags, manualNewName, manualNewCode ->
            pendingManualNewSubjectName = manualNewName
            pendingManualNewSubjectCode = manualNewCode
            viewModel.fetchAndAnalyzeChatFromLink(url, title, subject, tags)
          }
        )
      }

      if (extractionPreview != null) {
        ExtractionPreviewDialog(
          preview = extractionPreview!!,
          subjects = subjects,
          initialSubject = previewSubject,
          subjectSuggestion = previewSubjectSuggestion,
          sourceUrl = previewSourceUrl,
          initialManualNewSubjectName = pendingManualNewSubjectName,
          initialManualNewSubjectCode = pendingManualNewSubjectCode,
          onConfirm = { customTitle, customSubject, customTags, newSubName, newSubCode ->
            pendingManualNewSubjectName = ""
            pendingManualNewSubjectCode = ""
            viewModel.confirmSaveExtractedChat(
              customTitle = customTitle,
              customSubject = customSubject,
              customTags = customTags,
              manualNewSubjectName = newSubName,
              manualNewSubjectCode = newSubCode
            )
            selectedTab = 0
          },
          onDiscard = {
            pendingManualNewSubjectName = ""
            pendingManualNewSubjectCode = ""
            viewModel.discardExtractionPreview()
          }
        )
      }

      if (inspectingChat != null) {
        InspectSourceChatDialog(
          chat = inspectingChat!!,
          onDismiss = { inspectingChat = null }
        )
      }

      if (editingChat != null) {
        EditChatDetailsDialog(
          chat = editingChat!!,
          subjects = subjects,
          onSave = { updated ->
            viewModel.updateImportedChat(updated)
            editingChat = null
          },
          onDismiss = { editingChat = null }
        )
      }

      if (movingChat != null) {
        MoveChatToSubjectDialog(
          chat = movingChat!!,
          subjects = subjects,
          onMoveExisting = { targetSubject ->
            viewModel.moveChatToSubject(movingChat!!, targetSubject)
            movingChat = null
          },
          onCreateAndMove = { newName, newCode ->
            viewModel.createSubjectAndMoveChat(movingChat!!, newName, newCode)
            movingChat = null
          },
          onDismiss = { movingChat = null }
        )
      }

      if (editingConcept != null) {
        EditConceptDialog(
          concept = editingConcept!!,
          subjects = subjects,
          onSave = { updated ->
            viewModel.updateExtractedConcept(updated)
            editingConcept = null
          },
          onDismiss = { editingConcept = null }
        )
      }
    }
  }
}

// =========================================================================
// TAB 1: CHAT LIBRARY
// =========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatLibraryTab(
  chats: List<ImportedChat>,
  subjects: List<Subject>,
  onInspect: (ImportedChat) -> Unit,
  onEdit: (ImportedChat) -> Unit,
  onMoveSubject: (ImportedChat) -> Unit,
  onDelete: (ImportedChat) -> Unit,
  onReprocess: (ImportedChat) -> Unit,
  onNavigateToImport: () -> Unit
) {
  var searchQuery by remember { mutableStateOf("") }
  var selectedSubjectFilter by remember { mutableStateOf("All") }
  var selectedPlatformFilter by remember { mutableStateOf("All") }

  val subjectFilters = remember(chats, subjects) {
    val names = (chats.map { it.subjectName.ifBlank { "General Academic" } } + subjects.map { it.name })
      .filter { it.isNotBlank() }
      .distinct()
    listOf("All") + names
  }
  val platforms = listOf("All", "ChatGPT", "Claude", "Gemini", "DeepSeek", "Other")

  val filteredChats = remember(chats, searchQuery, selectedSubjectFilter, selectedPlatformFilter) {
    chats.filter { chat ->
      val chatSubject = chat.subjectName.ifBlank { "General Academic" }
      val matchesSearch = searchQuery.isBlank() ||
        chat.title.contains(searchQuery, ignoreCase = true) ||
        chatSubject.contains(searchQuery, ignoreCase = true) ||
        chat.tags.contains(searchQuery, ignoreCase = true) ||
        chat.notes.contains(searchQuery, ignoreCase = true) ||
        chat.sourceUrl.contains(searchQuery, ignoreCase = true)

      val matchesSubject = selectedSubjectFilter == "All" ||
        chatSubject.equals(selectedSubjectFilter, ignoreCase = true)

      val matchesPlatform = if (selectedPlatformFilter == "All") true
      else chat.aiPlatform.contains(selectedPlatformFilter, ignoreCase = true)

      matchesSearch && matchesSubject && matchesPlatform
    }
  }

  val groupedChats = remember(filteredChats) {
    filteredChats.groupBy { it.subjectName.ifBlank { "General Academic" } }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    OutlinedTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      modifier = Modifier
        .fillMaxWidth()
        .testTag("chat_library_search"),
      placeholder = { Text("Search linked chats, subjects, links...", color = PaletteDarkText.copy(alpha = 0.5f)) },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = PaletteDeepSage) },
      trailingIcon = {
        if (searchQuery.isNotEmpty()) {
          IconButton(onClick = { searchQuery = "" }) {
            Icon(Icons.Default.Close, contentDescription = "Clear search", tint = PaletteDarkText.copy(alpha = 0.6f))
          }
        }
      },
      shape = RoundedCornerShape(14.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = PaletteMintFoam,
        unfocusedContainerColor = PalettePaleCeladon.copy(alpha = 0.35f),
        focusedBorderColor = PaletteDeepSage,
        unfocusedBorderColor = PaletteMediumSage
      ),
      singleLine = true
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Subject grouping filter row (hidden while "All" is the only choice)
    if (subjectFilters.size > 1) Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      subjectFilters.forEach { subj ->
        FilterChip(
          selected = selectedSubjectFilter == subj,
          onClick = { selectedSubjectFilter = subj },
          label = { Text(subj, fontSize = 12.sp, maxLines = 1, softWrap = false) },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = PaletteDeepSage,
            selectedLabelColor = PaletteCreamIvory,
            containerColor = PalettePaleCeladon.copy(alpha = 0.55f),
            labelColor = PaletteDarkText
          ),
          border = null,
          shape = RoundedCornerShape(20.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      platforms.forEach { platform ->
        FilterChip(
          selected = selectedPlatformFilter == platform,
          onClick = { selectedPlatformFilter = platform },
          label = { Text(platform, fontSize = 11.sp, maxLines = 1, softWrap = false) },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = PaletteDeepSage,
            selectedLabelColor = PaletteCreamIvory,
            containerColor = PalettePaleCeladon.copy(alpha = 0.35f),
            labelColor = PaletteDarkText
          ),
          border = null,
          shape = RoundedCornerShape(16.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    if (filteredChats.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(top = 16.dp),
        contentAlignment = Alignment.TopCenter
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
          Icon(
            Icons.Default.Link,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = PaletteMediumSage
          )
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = if (chats.isEmpty()) "No Linked AI Chats Yet" else "No matching linked conversations",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PaletteDarkText,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = if (chats.isEmpty())
              "Import a shared ChatGPT, Claude, Gemini or DeepSeek link. The tutor checks your imported chats first."
            else
              "Try clearing your search query or choosing another subject filter.",
            style = MaterialTheme.typography.bodyMedium,
            color = PaletteDarkText.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
          )
          if (chats.isEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
              onClick = onNavigateToImport,
              colors = ButtonDefaults.buttonColors(
                containerColor = PaletteDeepSage,
                contentColor = PaletteCreamIvory
              ),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("empty_state_import_button")
            ) {
              Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Import first chat link", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
          }
        }
      }
    } else {
      LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
      ) {
        groupedChats.forEach { (subjectGroup, groupChats) ->
          item(key = "header_$subjectGroup") {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 2.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                  .weight(1f)
                  .padding(end = 8.dp)
              ) {
                Icon(
                  Icons.Default.FolderOpen,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                  tint = PaletteDeepSage
                )
                Text(
                  text = subjectGroup,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = PaletteDarkText,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
              Surface(
                color = PalettePaleCeladon,
                shape = RoundedCornerShape(10.dp)
              ) {
                Text(
                  text = "${groupChats.size} chat${if (groupChats.size == 1) "" else "s"}",
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = PaletteDeepSage,
                  maxLines = 1,
                  softWrap = false,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
              }
            }
          }

          items(groupChats, key = { it.id }) { chat ->
            ImportedChatCard(
              chat = chat,
              onInspect = { onInspect(chat) },
              onEdit = { onEdit(chat) },
              onMoveSubject = { onMoveSubject(chat) },
              onDelete = { onDelete(chat) },
              onReprocess = { onReprocess(chat) }
            )
          }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportedChatCard(
  chat: ImportedChat,
  onInspect: () -> Unit,
  onEdit: () -> Unit,
  onMoveSubject: () -> Unit,
  onDelete: () -> Unit,
  onReprocess: () -> Unit
) {
  var showMenu by remember { mutableStateOf(false) }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("chat_card_${chat.id}"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = PaletteMintFoam),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
      ) {
        Column(
          modifier = Modifier
            .weight(1f)
            .padding(end = 8.dp)
        ) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Surface(
              color = PaletteDeepSage,
              shape = RoundedCornerShape(6.dp)
            ) {
              Text(
                text = chat.aiPlatform,
                color = PaletteCreamIvory,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              )
            }

            if (chat.subjectName.isNotBlank()) {
              Surface(
                color = PalettePaleCeladon,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.clickable { onMoveSubject() }
              ) {
                Text(
                  text = chat.subjectName,
                  color = PaletteDarkText,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = chat.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PaletteDarkText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
        }

        Box {
          IconButton(onClick = { showMenu = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = PaletteDarkText.copy(alpha = 0.7f))
          }
          DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
          ) {
            DropdownMenuItem(
              text = { Text("Inspect source log") },
              leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
              onClick = {
                showMenu = false
                onInspect()
              }
            )
            DropdownMenuItem(
              text = { Text("Move to another subject") },
              leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = PaletteDeepSage) },
              onClick = {
                showMenu = false
                onMoveSubject()
              }
            )
            DropdownMenuItem(
              text = { Text("Reprocess knowledge") },
              leadingIcon = { Icon(Icons.Default.Autorenew, contentDescription = null) },
              onClick = {
                showMenu = false
                onReprocess()
              }
            )
            DropdownMenuItem(
              text = { Text("Edit title & tags") },
              leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
              onClick = {
                showMenu = false
                onEdit()
              }
            )
            HorizontalDivider()
            DropdownMenuItem(
              text = { Text("Delete conversation", color = MaterialTheme.colorScheme.error) },
              leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
              onClick = {
                showMenu = false
                onDelete()
              }
            )
          }
        }
      }

      if (chat.sourceUrl.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
          color = PaletteCreamIvory,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              Icons.Default.Link,
              contentDescription = null,
              tint = PaletteDeepSage,
              modifier = Modifier.size(14.dp)
            )
            Text(
              text = chat.sourceUrl,
              fontSize = 11.sp,
              color = PaletteDeepSage,
              fontWeight = FontWeight.Medium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f)
            )
          }
        }
      }

      if (chat.notes.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = chat.notes,
          style = MaterialTheme.typography.bodySmall,
          color = PaletteDarkText.copy(alpha = 0.8f),
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          lineHeight = 18.sp
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        FlowRow(
          modifier = Modifier
            .weight(1f)
            .padding(end = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          StatPill(icon = Icons.Default.Psychology, label = "${chat.extractedConceptsCount} concepts")
          StatPill(icon = Icons.AutoMirrored.Filled.Chat, label = "${chat.messageCount} turns")
          StatPill(icon = Icons.Default.Notes, label = "${chat.wordCount} words")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = onMoveSubject,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PaletteDeepSage),
            border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(PaletteMediumSage)),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.heightIn(min = 44.dp)
          ) {
            Icon(Icons.Default.DriveFileMove, contentDescription = "Move Subject", modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Subject", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          }

          OutlinedButton(
            onClick = onInspect,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PaletteDarkText),
            border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(PaletteMediumSage)),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.heightIn(min = 44.dp)
          ) {
            Icon(Icons.Default.Source, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Source", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

@Composable
private fun StatPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = PaletteDeepSage)
    Text(label, fontSize = 11.sp, color = PaletteDarkText.copy(alpha = 0.7f), fontWeight = FontWeight.Medium)
  }
}

// =========================================================================
// TAB 2: LEARNING MEMORY (CONCEPT BROWSER)
// =========================================================================

@Composable
private fun LearningMemoryTab(
  concepts: List<ExtractedConcept>,
  subjects: List<Subject>,
  onDrillQuiz: (ExtractedConcept) -> Unit,
  onInspectSource: (ExtractedConcept) -> Unit,
  onEdit: (ExtractedConcept) -> Unit,
  onDelete: (ExtractedConcept) -> Unit
) {
  var searchQuery by remember { mutableStateOf("") }
  var selectedSubjectFilter by remember { mutableStateOf("All") }
  var selectedMasteryFilter by remember { mutableStateOf("All") }

  val subjectNames = remember(subjects) { listOf("All") + subjects.map { it.name }.distinct() }
  val masteryFilters = remember { listOf("All", "Untested", "Needs Revision", "Mastered") }

  val filteredConcepts = remember(concepts, searchQuery, selectedSubjectFilter, selectedMasteryFilter) {
    concepts.filter { concept ->
      val matchesSearch = searchQuery.isBlank() ||
        concept.conceptName.contains(searchQuery, ignoreCase = true) ||
        concept.topic.contains(searchQuery, ignoreCase = true) ||
        concept.subtopic.contains(searchQuery, ignoreCase = true) ||
        concept.definition.contains(searchQuery, ignoreCase = true) ||
        concept.technicalDetails.contains(searchQuery, ignoreCase = true)

      val matchesSubject = if (selectedSubjectFilter == "All") true
      else concept.subjectName.equals(selectedSubjectFilter, ignoreCase = true)

      val matchesMastery = when (selectedMasteryFilter) {
        "Untested" -> concept.timesTested == 0
        "Needs Revision" -> concept.masteryLevel == "STRUGGLING" || (concept.timesTested > 0 && concept.timesCorrect < concept.timesTested)
        "Mastered" -> concept.masteryLevel == "MASTERED" || (concept.timesTested > 0 && concept.timesCorrect.toFloat() / concept.timesTested >= 0.8f)
        else -> true
      }

      matchesSearch && matchesSubject && matchesMastery
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    OutlinedTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      modifier = Modifier
        .fillMaxWidth()
        .testTag("learning_memory_search"),
      placeholder = { Text("Search academic concepts, formulas, definitions...", color = PaletteDarkText.copy(alpha = 0.5f)) },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = PaletteDeepSage) },
      trailingIcon = {
        if (searchQuery.isNotEmpty()) {
          IconButton(onClick = { searchQuery = "" }) {
            Icon(Icons.Default.Close, contentDescription = "Clear", tint = PaletteDarkText.copy(alpha = 0.6f))
          }
        }
      },
      shape = RoundedCornerShape(14.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = PaletteMintFoam,
        unfocusedContainerColor = PalettePaleCeladon.copy(alpha = 0.35f),
        focusedBorderColor = PaletteDeepSage,
        unfocusedBorderColor = PaletteMediumSage
      ),
      singleLine = true
    )

    Spacer(modifier = Modifier.height(12.dp))

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      subjectNames.forEach { subj ->
        FilterChip(
          selected = selectedSubjectFilter == subj,
          onClick = { selectedSubjectFilter = subj },
          label = { Text(subj, fontSize = 12.sp) },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = PaletteDeepSage,
            selectedLabelColor = PaletteCreamIvory,
            containerColor = PalettePaleCeladon.copy(alpha = 0.5f),
            labelColor = PaletteDarkText
          ),
          border = null,
          shape = RoundedCornerShape(20.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      masteryFilters.forEach { m ->
        FilterChip(
          selected = selectedMasteryFilter == m,
          onClick = { selectedMasteryFilter = m },
          label = { Text(m, fontSize = 11.sp) },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = PaletteDeepSage,
            selectedLabelColor = PaletteCreamIvory,
            containerColor = PalettePaleCeladon.copy(alpha = 0.35f),
            labelColor = PaletteDarkText
          ),
          border = null,
          shape = RoundedCornerShape(16.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    if (filteredConcepts.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(top = 16.dp),
        contentAlignment = Alignment.TopCenter
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.padding(24.dp)
        ) {
          Icon(
            Icons.Default.Psychology,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = PaletteMediumSage
          )
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = if (concepts.isEmpty()) "No Learning Memory Indexed Yet" else "No matching concepts",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PaletteDarkText
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = if (concepts.isEmpty())
              "Concepts from imported chats appear here."
            else
              "Try adjusting your search keywords or clearing the active filters.",
            style = MaterialTheme.typography.bodyMedium,
            color = PaletteDarkText.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
          )
        }
      }
    } else {
      LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
      ) {
        items(filteredConcepts, key = { it.id }) { concept ->
          ExtractedConceptCard(
            concept = concept,
            onDrillQuiz = { onDrillQuiz(concept) },
            onInspectSource = { onInspectSource(concept) },
            onEdit = { onEdit(concept) },
            onDelete = { onDelete(concept) }
          )
        }
        item { Spacer(modifier = Modifier.height(28.dp)) }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtractedConceptCard(
  concept: ExtractedConcept,
  onDrillQuiz: () -> Unit,
  onInspectSource: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit
) {
  var isExpanded by remember { mutableStateOf(false) }
  var showMenu by remember { mutableStateOf(false) }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("concept_card_${concept.id}"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = PaletteMintFoam),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
      ) {
        Column(
          modifier = Modifier
            .weight(1f)
            .padding(end = 8.dp)
        ) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Surface(
              color = PalettePaleCeladon,
              shape = RoundedCornerShape(6.dp)
            ) {
              Text(
                text = concept.subjectName,
                color = PaletteDarkText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              )
            }

            val (statusText, statusBg, statusFg) = when {
              concept.timesTested == 0 -> Triple("Untested", PalettePaleCeladon, PaletteDarkText.copy(alpha = 0.7f))
              concept.masteryLevel == "STRUGGLING" -> Triple("Needs Revision", Color(0xFFE2B7A8), PaletteDarkText)
              concept.masteryLevel == "MASTERED" -> Triple("Mastered", PaletteDeepSage, PaletteCreamIvory)
              else -> Triple("Practice (${concept.timesCorrect}/${concept.timesTested})", PaletteMediumSage, PaletteDarkText)
            }

            Surface(
              color = statusBg,
              shape = RoundedCornerShape(6.dp)
            ) {
              Text(
                text = statusText,
                color = statusFg,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = concept.conceptName.ifBlank { concept.topic },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PaletteDarkText
          )

          if (concept.subtopic.isNotBlank() || concept.topic != concept.conceptName) {
            Text(
              text = "${concept.topic}${if (concept.subtopic.isNotBlank()) " • " + concept.subtopic else ""}",
              style = MaterialTheme.typography.bodySmall,
              color = PaletteDeepSage,
              fontWeight = FontWeight.SemiBold
            )
          }
        }

        Box {
          IconButton(onClick = { showMenu = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = PaletteDarkText.copy(alpha = 0.7f))
          }
          DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
          ) {
            DropdownMenuItem(
              text = { Text("Drill with 5-Question Quiz") },
              leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = PaletteDeepSage) },
              onClick = {
                showMenu = false
                onDrillQuiz()
              }
            )
            DropdownMenuItem(
              text = { Text("View source AI chat") },
              leadingIcon = { Icon(Icons.Default.Source, contentDescription = null) },
              onClick = {
                showMenu = false
                onInspectSource()
              }
            )
            DropdownMenuItem(
              text = { Text("Edit concept details") },
              leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
              onClick = {
                showMenu = false
                onEdit()
              }
            )
            Divider()
            DropdownMenuItem(
              text = { Text("Delete concept", color = MaterialTheme.colorScheme.error) },
              leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
              onClick = {
                showMenu = false
                onDelete()
              }
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      Surface(
        color = PaletteCreamIvory,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(
            text = "Definition",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = PaletteDeepSage
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = concept.definition,
            style = MaterialTheme.typography.bodyMedium,
            color = PaletteDarkText,
            lineHeight = 20.sp
          )
        }
      }

      if (concept.technicalDetails.isNotBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = concept.technicalDetails,
          style = MaterialTheme.typography.bodySmall,
          color = PaletteDarkText.copy(alpha = 0.85f),
          maxLines = if (isExpanded) Int.MAX_VALUE else 3,
          overflow = TextOverflow.Ellipsis,
          lineHeight = 18.sp
        )
      }

      if (concept.formulaOrCode.isNotBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
          color = PaletteDarkText,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Text(
              text = "Formula or algorithm",
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = PalettePaleCeladon
            )
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
              Text(
                text = concept.formulaOrCode,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = PaletteCreamIvory,
                lineHeight = 18.sp
              )
            }
          }
        }
      }

      if (concept.misconceptionsAndCorrections.isNotBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
          color = PalettePaleCeladon.copy(alpha = 0.6f),
          shape = RoundedCornerShape(10.dp),
          border = BorderStroke(1.dp, PaletteMediumSage),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              Icons.Default.WarningAmber,
              contentDescription = null,
              tint = PaletteDeepSage,
              modifier = Modifier.size(18.dp)
            )
            Column {
              Text(
                text = "Common mistake",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = PaletteDarkText
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = concept.misconceptionsAndCorrections,
                fontSize = 12.sp,
                color = PaletteDarkText.copy(alpha = 0.9f),
                lineHeight = 16.sp
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          modifier = Modifier
            .weight(1f)
            .padding(end = 8.dp)
            .clickable { onInspectSource() }
            .padding(vertical = 8.dp)
        ) {
          Icon(Icons.Default.Source, contentDescription = null, modifier = Modifier.size(14.dp), tint = PaletteDeepSage)
          Text(
            text = concept.sourceChatTitle.ifBlank { "From Linked Chat" },
            fontSize = 11.sp,
            color = PaletteDeepSage,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        Button(
          onClick = onDrillQuiz,
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = PaletteDeepSage,
            contentColor = PaletteCreamIvory
          ),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
          modifier = Modifier
            .heightIn(min = 44.dp)
            .testTag("quiz_drill_button_${concept.id}")
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Quiz drill", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

// =========================================================================
// TAB 3: KNOWLEDGE COVERAGE DASHBOARD
// =========================================================================

private data class CoverageSummaryTuple(
  val subjectsCovered: Int,
  val topicsDiscovered: Int,
  val testedConcepts: List<ExtractedConcept>,
  val untestedConcepts: List<ExtractedConcept>,
  val revisionRequired: List<ExtractedConcept>,
  val masteryPercentage: Int
)

@Composable
private fun KnowledgeCoverageDashboardTab(
  chats: List<ImportedChat>,
  concepts: List<ExtractedConcept>,
  subjects: List<Subject>,
  onOpenTopic: () -> Unit
) {
  val totalChats = chats.size
  val totalConcepts = concepts.size
  val (subjectsCovered, topicsDiscovered, testedConcepts, untestedConcepts, revisionRequired, masteryPercentage) = remember(concepts) {
    val subCov = concepts.asSequence().map { it.subjectName }.filter { it.isNotBlank() }.distinct().count()
    val topDisc = concepts.asSequence().map { it.topic }.distinct().count()
    val tested = concepts.filter { it.timesTested > 0 }
    val untested = concepts.filter { it.timesTested == 0 }
    val revision = concepts.filter { it.masteryLevel == "STRUGGLING" || (it.timesTested > 0 && it.timesCorrect < it.timesTested) }
    val pct = if (concepts.isNotEmpty()) {
      ((tested.count { it.timesCorrect.toFloat() / it.timesTested >= 0.8f }.toFloat() / concepts.size) * 100).toInt()
    } else 0
    CoverageSummaryTuple(subCov, topDisc, tested, untested, revision, pct)
  }
  val bySubject = remember(concepts) { concepts.groupBy { it.subjectName } }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = PaletteDeepSage),
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
              text = "Knowledge base",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = PaletteCreamIvory
            )
            Text(
              text = "Imported chats",
              style = MaterialTheme.typography.bodySmall,
              color = PalettePaleCeladon
            )
          }
          Icon(
            Icons.Default.School,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = PaletteCreamIvory
          )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Mastery",
            fontSize = 12.sp,
            color = PaletteCreamIvory,
            fontWeight = FontWeight.Medium
          )
          Text(
            text = "$masteryPercentage%",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = PaletteCreamIvory
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
          progress = { (masteryPercentage / 100f).coerceIn(0f, 1f) },
          modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
          color = PaletteCreamIvory,
          trackColor = PalettePaleCeladon.copy(alpha = 0.4f)
        )
      }
    }

    Text(
      text = "Coverage metrics",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = PaletteDarkText
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      DashboardMetricCard(
        modifier = Modifier.weight(1f),
        title = "Imported chats",
        value = "$totalChats",
        subtitle = "Across all AI tools",
        icon = Icons.AutoMirrored.Filled.Chat,
        containerColor = PaletteMintFoam
      )
      DashboardMetricCard(
        modifier = Modifier.weight(1f),
        title = "Extracted concepts",
        value = "$totalConcepts",
        subtitle = "Organized & linked",
        icon = Icons.Default.Psychology,
        containerColor = PaletteMintFoam
      )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      DashboardMetricCard(
        modifier = Modifier.weight(1f),
        title = "Subjects active",
        value = "$subjectsCovered",
        subtitle = "Academic courses",
        icon = Icons.Default.MenuBook,
        containerColor = PaletteMintFoam
      )
      DashboardMetricCard(
        modifier = Modifier.weight(1f),
        title = "Topics discovered",
        value = "$topicsDiscovered",
        subtitle = "Curriculum units",
        icon = Icons.Default.Category,
        containerColor = PaletteMintFoam
      )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      DashboardMetricCard(
        modifier = Modifier.weight(1f),
        title = "Tested in quizzes",
        value = "${testedConcepts.size}",
        subtitle = "${untestedConcepts.size} untested",
        icon = Icons.Default.FactCheck,
        containerColor = PaletteMintFoam
      )
      DashboardMetricCard(
        modifier = Modifier.weight(1f),
        title = "Needs revision",
        value = "${revisionRequired.size}",
        subtitle = "Prioritized in quiz",
        icon = Icons.Default.WarningAmber,
        containerColor = if (revisionRequired.isNotEmpty()) Color(0xFFEFE2DB) else PaletteMintFoam
      )
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = PaletteMintFoam)
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
          text = "Concepts by subject",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = PaletteDarkText
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (concepts.isEmpty()) {
          Text(
            text = "No concepts to display yet. Import conversations to populate.",
            fontSize = 12.sp,
            color = PaletteDarkText.copy(alpha = 0.6f)
          )
        } else {
          bySubject.forEach { (subjectName, list) ->
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = subjectName.ifBlank { "General Academic" },
                  fontSize = 13.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = PaletteDarkText,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                )
                Text(
                  text = "${list.size} concepts",
                  fontSize = 12.sp,
                  color = PaletteDeepSage,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  softWrap = false
                )
              }
              Spacer(modifier = Modifier.height(4.dp))
              LinearProgressIndicator(
                progress = { (list.size.toFloat() / concepts.size).coerceIn(0f, 1f) },
                modifier = Modifier
                  .fillMaxWidth()
                  .height(6.dp)
                  .clip(RoundedCornerShape(3.dp)),
                color = PaletteDeepSage,
                trackColor = PalettePaleCeladon
              )
            }
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(24.dp))
  }
}

@Composable
private fun DashboardMetricCard(
  modifier: Modifier = Modifier,
  title: String,
  value: String,
  subtitle: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  containerColor: Color
) {
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = title,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
          color = PaletteDarkText.copy(alpha = 0.8f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier
            .weight(1f)
            .padding(end = 8.dp)
        )
        Icon(
          icon,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = PaletteDeepSage
        )
      }
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = value,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = PaletteDarkText
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = subtitle,
        fontSize = 11.sp,
        color = PaletteDarkText.copy(alpha = 0.6f)
      )
    }
  }
}

// =========================================================================
// TAB 4: IMPORT AI CHAT (LINK-ONLY + AUTO/MANUAL SUBJECT GROUPING)
// =========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportChatTab(
  subjects: List<Subject>,
  isProcessing: Boolean,
  progress: Float,
  progressStatus: String,
  onFetchLink: (url: String, title: String, subject: Subject?, tags: String, manualNewSubjectName: String, manualNewSubjectCode: String) -> Unit
) {
  var chatUrl by remember { mutableStateOf("") }
  var title by remember { mutableStateOf("") }
  var subjectMode by remember { mutableStateOf("AUTO") } // "AUTO", "EXISTING", "CREATE_NEW"
  var selectedSubject by remember { mutableStateOf<Subject?>(null) }
  var newSubjectName by remember { mutableStateOf("") }
  var newSubjectCode by remember { mutableStateOf("") }
  var tags by remember { mutableStateOf("") }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Guidance Card
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = PaletteMintFoam)
    ) {
      Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Icon(
          Icons.Default.Link,
          contentDescription = null,
          tint = PaletteDeepSage,
          modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Import from Shared AI Link",
            fontWeight = FontWeight.Bold,
            color = PaletteDarkText,
            fontSize = 15.sp
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Paste a public share link. The chat is filed under a subject automatically, or pick one.",
            style = MaterialTheme.typography.bodySmall,
            color = PaletteDarkText.copy(alpha = 0.8f),
            lineHeight = 18.sp
          )
        }
      }
    }

    // URL Input Field
    Column(modifier = Modifier.fillMaxWidth()) {
      Text(
        text = "Shared AI chat URL",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = PaletteDarkText
      )
      Spacer(modifier = Modifier.height(8.dp))
      OutlinedTextField(
        value = chatUrl,
        onValueChange = { chatUrl = it },
        modifier = Modifier
          .fillMaxWidth()
          .testTag("import_chat_url_input"),
        placeholder = {
          Text(
            "e.g. https://chatgpt.com/share/6789... or gemini.google.com/share/...",
            fontSize = 13.sp,
            color = PaletteDarkText.copy(alpha = 0.5f)
          )
        },
        leadingIcon = {
          Icon(Icons.Default.Link, contentDescription = null, tint = PaletteDeepSage)
        },
        trailingIcon = {
          if (chatUrl.isNotEmpty()) {
            IconButton(onClick = { chatUrl = "" }) {
              Icon(Icons.Default.Close, contentDescription = "Clear URL", tint = PaletteDarkText.copy(alpha = 0.6f))
            }
          }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedContainerColor = PaletteMintFoam,
          unfocusedContainerColor = PalettePaleCeladon.copy(alpha = 0.35f),
          focusedBorderColor = PaletteDeepSage,
          unfocusedBorderColor = PaletteMediumSage
        )
      )
    }

    // Supported Platforms FlowRow
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(
        "Supported links:",
        fontSize = 11.sp,
        color = PaletteDarkText.copy(alpha = 0.65f),
        fontWeight = FontWeight.Medium,
        modifier = Modifier.align(Alignment.CenterVertically)
      )
      listOf("ChatGPT", "Claude", "Gemini", "DeepSeek", "Poe", "Perplexity").forEach { p ->
        Surface(
          color = PalettePaleCeladon.copy(alpha = 0.55f),
          shape = RoundedCornerShape(6.dp)
        ) {
          Text(
            text = p,
            fontSize = 10.sp,
            color = PaletteDarkText,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }
      }
    }

    // Quick Sample Link Fillers
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text("Try a sample shared link:", fontSize = 11.sp, color = PaletteDarkText.copy(alpha = 0.65f))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedButton(
          onClick = {
            chatUrl = "https://chatgpt.com/share/sample-dijkstra-shortest-path"
            if (title.isBlank()) title = "Dijkstra algorithm session"
          },
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          border = BorderStroke(1.dp, PaletteMediumSage),
          modifier = Modifier.heightIn(min = 44.dp)
        ) {
          Text("Sample ChatGPT Link", fontSize = 12.sp, color = PaletteDarkText, maxLines = 1, softWrap = false)
        }

        OutlinedButton(
          onClick = {
            chatUrl = "https://claude.ai/share/sample-matrix-svd-pca"
            if (title.isBlank()) title = "Matrix SVD & PCA session"
          },
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          border = BorderStroke(1.dp, PaletteMediumSage),
          modifier = Modifier.heightIn(min = 44.dp)
        ) {
          Text("Sample Claude link", fontSize = 12.sp, color = PaletteDarkText, maxLines = 1, softWrap = false)
        }
      }
    }

    // Subject Grouping Selector (Auto + Existing + Manual New)
    var subjectDropdownExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
      OutlinedCard(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { subjectDropdownExpanded = true }
          .testTag("import_subject_dropdown_trigger"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = PaletteMintFoam),
        border = BorderStroke(1.dp, PaletteMediumSage)
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text("Subject grouping", fontSize = 11.sp, color = PaletteDarkText.copy(alpha = 0.65f))
            Text(
              text = when (subjectMode) {
                "CREATE_NEW" -> if (newSubjectName.isNotBlank()) "New subject: $newSubjectName" else "Create New Subject Manually"
                "EXISTING" -> selectedSubject?.let { "${it.code}: ${it.name}" } ?: "Auto-group from linked chat"
                else -> "Auto-group from linked chat (Recommended)"
              },
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
              color = PaletteDarkText,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
          Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = PaletteDarkText)
        }
      }

      DropdownMenu(
        expanded = subjectDropdownExpanded,
        onDismissRequest = { subjectDropdownExpanded = false }
      ) {
        DropdownMenuItem(
          text = { Text("Auto-group from linked chat (Recommended)") },
          onClick = {
            subjectMode = "AUTO"
            selectedSubject = null
            subjectDropdownExpanded = false
          }
        )
        DropdownMenuItem(
          text = { Text("+ Create New Subject Manually", fontWeight = FontWeight.Bold, color = PaletteDeepSage) },
          onClick = {
            subjectMode = "CREATE_NEW"
            selectedSubject = null
            subjectDropdownExpanded = false
          }
        )
        if (subjects.isNotEmpty()) {
          HorizontalDivider()
          subjects.forEach { s ->
            DropdownMenuItem(
              text = { Text("${s.code}: ${s.name}") },
              onClick = {
                subjectMode = "EXISTING"
                selectedSubject = s
                subjectDropdownExpanded = false
              }
            )
          }
        }
      }
    }

    if (subjectMode == "CREATE_NEW") {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedTextField(
          value = newSubjectName,
          onValueChange = { newSubjectName = it },
          label = { Text("New Subject Name *") },
          placeholder = { Text("e.g. Algorithms") },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .weight(2f)
            .testTag("import_new_subject_name_input"),
          colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = PaletteMintFoam,
            unfocusedContainerColor = PalettePaleCeladon.copy(alpha = 0.3f),
            focusedBorderColor = PaletteDeepSage,
            unfocusedBorderColor = PaletteMediumSage
          )
        )
        OutlinedTextField(
          value = newSubjectCode,
          onValueChange = { newSubjectCode = it },
          label = { Text("Code") },
          placeholder = { Text("CS301") },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .weight(1f)
            .testTag("import_new_subject_code_input"),
          colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = PaletteMintFoam,
            unfocusedContainerColor = PalettePaleCeladon.copy(alpha = 0.3f),
            focusedBorderColor = PaletteDeepSage,
            unfocusedBorderColor = PaletteMediumSage
          )
        )
      }
    }

    OutlinedTextField(
      value = title,
      onValueChange = { title = it },
      modifier = Modifier
        .fillMaxWidth()
        .testTag("import_chat_title_input"),
      label = { Text("Conversation Title (Optional)") },
      placeholder = { Text("Auto-extracted from link or enter custom title") },
      singleLine = true,
      shape = RoundedCornerShape(12.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = PaletteMintFoam,
        unfocusedContainerColor = PalettePaleCeladon.copy(alpha = 0.3f),
        focusedBorderColor = PaletteDeepSage,
        unfocusedBorderColor = PaletteMediumSage
      )
    )

    OutlinedTextField(
      value = tags,
      onValueChange = { tags = it },
      modifier = Modifier
        .fillMaxWidth()
        .testTag("import_tags_input"),
      label = { Text("Tags (Optional)") },
      placeholder = { Text("e.g. #midterm, #graphs, #revision") },
      singleLine = true,
      shape = RoundedCornerShape(12.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = PaletteMintFoam,
        unfocusedContainerColor = PalettePaleCeladon.copy(alpha = 0.3f),
        focusedBorderColor = PaletteDeepSage,
        unfocusedBorderColor = PaletteMediumSage
      )
    )

    // Progress Status Card
    if (isProcessing) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PaletteMintFoam)
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Importing...",
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              color = PaletteDarkText,
              modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = PaletteDeepSage
            )
          }
          Spacer(modifier = Modifier.height(8.dp))
          LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
              .fillMaxWidth()
              .height(6.dp)
              .clip(RoundedCornerShape(3.dp)),
            color = PaletteDeepSage,
            trackColor = PalettePaleCeladon
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = progressStatus,
            fontSize = 11.sp,
            color = PaletteDarkText.copy(alpha = 0.8f)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(4.dp))

    val isButtonEnabled = chatUrl.isNotBlank() && !isProcessing &&
      (subjectMode != "CREATE_NEW" || newSubjectName.isNotBlank())

    Button(
      onClick = {
        onFetchLink(
          chatUrl.trim(),
          title.trim(),
          if (subjectMode == "EXISTING") selectedSubject else null,
          tags.trim(),
          if (subjectMode == "CREATE_NEW") newSubjectName.trim() else "",
          if (subjectMode == "CREATE_NEW") newSubjectCode.trim() else ""
        )
      },
      enabled = isButtonEnabled,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 52.dp)
        .testTag("analyze_and_extract_button"),
      shape = RoundedCornerShape(14.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = PaletteDeepSage,
        contentColor = PaletteCreamIvory,
        disabledContainerColor = PaletteMediumSage.copy(alpha = 0.5f),
        disabledContentColor = PaletteCreamIvory.copy(alpha = 0.6f)
      )
    ) {
      if (isProcessing) {
        CircularProgressIndicator(
          modifier = Modifier.size(20.dp),
          color = PaletteCreamIvory,
          strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Fetching & Grouping...", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
      } else {
        Icon(
          Icons.Default.Link,
          contentDescription = null,
          modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Import",
          fontWeight = FontWeight.Bold,
          fontSize = 15.sp,
          maxLines = 1,
          softWrap = false
        )
      }
    }

    Spacer(modifier = Modifier.height(24.dp))
  }
}

// =========================================================================
// PREVIEW, SUBJECT GROUPING & EDIT DIALOGS
// =========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtractionPreviewDialog(
  preview: ChatKnowledgeExtractor.ChatExtractionResult,
  subjects: List<Subject>,
  initialSubject: Subject?,
  subjectSuggestion: ChatKnowledgeExtractor.SubjectSuggestion?,
  sourceUrl: String,
  initialManualNewSubjectName: String,
  initialManualNewSubjectCode: String,
  onConfirm: (
    customTitle: String?,
    customSubject: Subject?,
    customTags: String?,
    manualNewSubjectName: String?,
    manualNewSubjectCode: String?
  ) -> Unit,
  onDiscard: () -> Unit
) {
  var editableTitle by remember {
    mutableStateOf("Discussion: ${preview.discoveredTopics.firstOrNull() ?: preview.discoveredSubject}")
  }
  var subjectMode by remember {
    mutableStateOf(
      when {
        initialManualNewSubjectName.isNotBlank() -> "CREATE_NEW"
        initialSubject != null -> "EXISTING"
        subjectSuggestion?.matchedSubject != null -> "EXISTING"
        else -> "AUTO"
      }
    )
  }
  var selectedSubject by remember {
    mutableStateOf(
      initialSubject
        ?: subjectSuggestion?.matchedSubject
        ?: subjects.find { it.name.equals(preview.discoveredSubject, ignoreCase = true) }
    )
  }
  var newSubjectName by remember {
    mutableStateOf(
      initialManualNewSubjectName.ifBlank { subjectSuggestion?.suggestedName ?: preview.discoveredSubject }
    )
  }
  var newSubjectCode by remember {
    mutableStateOf(
      initialManualNewSubjectCode.ifBlank { subjectSuggestion?.suggestedCode ?: "" }
    )
  }
  var editableTags by remember { mutableStateOf(preview.discoveredTopics.take(3).joinToString(", ") { "#$it" }) }
  var subjectDropdownExpanded by remember { mutableStateOf(false) }

  Dialog(
    onDismissRequest = onDiscard,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 20.dp)
        .widthIn(max = 560.dp)
        .fillMaxHeight(0.92f)
        .testTag("extraction_preview_dialog"),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = PaletteCreamIvory)
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(20.dp)
      ) {
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
              text = "Preview",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = PaletteDarkText
            )
            Text(
              text = "${preview.concepts.size} concepts extracted from ${preview.platform}",
              style = MaterialTheme.typography.bodySmall,
              color = PaletteDeepSage
            )
          }

          IconButton(onClick = onDiscard, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = PaletteDarkText)
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(
          modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          if (sourceUrl.isNotBlank()) {
            Surface(
              color = PalettePaleCeladon.copy(alpha = 0.55f),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(14.dp), tint = PaletteDeepSage)
                Text(
                  text = sourceUrl,
                  fontSize = 11.sp,
                  color = PaletteDeepSage,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f)
                )
              }
            }
          }

          OutlinedTextField(
            value = editableTitle,
            onValueChange = { editableTitle = it },
            label = { Text("Conversation title") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedContainerColor = PaletteMintFoam,
              unfocusedContainerColor = PaletteMintFoam,
              focusedBorderColor = PaletteDeepSage
            )
          )

          // Subject Grouping Card (Auto or Manual Selection/Creation)
          Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedCard(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { subjectDropdownExpanded = true }
                .testTag("preview_subject_selector"),
              shape = RoundedCornerShape(12.dp),
              colors = CardDefaults.outlinedCardColors(containerColor = PaletteMintFoam),
              border = BorderStroke(1.dp, PaletteMediumSage)
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                  Text(
                    text = if (subjectMode == "AUTO") "Auto-Detected Subject Group" else "Subject Group",
                    fontSize = 11.sp,
                    color = PaletteDeepSage,
                    fontWeight = FontWeight.SemiBold
                  )
                  Text(
                    text = when (subjectMode) {
                      "CREATE_NEW" -> "New: ${newSubjectName.ifBlank { preview.discoveredSubject }}"
                      "EXISTING" -> selectedSubject?.let { "${it.code}: ${it.name}" } ?: preview.discoveredSubject
                      else -> {
                        val sName = subjectSuggestion?.suggestedName ?: preview.discoveredSubject
                        val sCode = subjectSuggestion?.suggestedCode.orEmpty()
                        if (sCode.isNotBlank()) "$sCode: $sName (Auto-create)" else "$sName (Auto-group)"
                      }
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = PaletteDarkText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Change subject group", tint = PaletteDarkText)
              }
            }

            DropdownMenu(
              expanded = subjectDropdownExpanded,
              onDismissRequest = { subjectDropdownExpanded = false }
            ) {
              DropdownMenuItem(
                text = { Text("Auto-group (${subjectSuggestion?.suggestedName ?: preview.discoveredSubject})") },
                onClick = {
                  subjectMode = "AUTO"
                  subjectDropdownExpanded = false
                }
              )
              DropdownMenuItem(
                text = { Text("+ Create New Subject Manually", fontWeight = FontWeight.Bold, color = PaletteDeepSage) },
                onClick = {
                  subjectMode = "CREATE_NEW"
                  subjectDropdownExpanded = false
                }
              )
              if (subjects.isNotEmpty()) {
                HorizontalDivider()
                subjects.forEach { s ->
                  DropdownMenuItem(
                    text = { Text("${s.code}: ${s.name}") },
                    onClick = {
                      subjectMode = "EXISTING"
                      selectedSubject = s
                      subjectDropdownExpanded = false
                    }
                  )
                }
              }
            }
          }

          if (subjectMode == "CREATE_NEW") {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedTextField(
                value = newSubjectName,
                onValueChange = { newSubjectName = it },
                label = { Text("Subject Name *") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(2f)
              )
              OutlinedTextField(
                value = newSubjectCode,
                onValueChange = { newSubjectCode = it },
                label = { Text("Code") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
              )
            }
          }

          OutlinedTextField(
            value = editableTags,
            onValueChange = { editableTags = it },
            label = { Text("Tags") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedContainerColor = PaletteMintFoam,
              unfocusedContainerColor = PaletteMintFoam,
              focusedBorderColor = PaletteDeepSage
            )
          )

          Surface(
            color = PaletteMintFoam,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text(
                text = "Topics found",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = PaletteDeepSage
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = preview.discoveredTopics.joinToString(" • ").ifBlank { preview.discoveredSubject },
                style = MaterialTheme.typography.bodySmall,
                color = PaletteDarkText,
                lineHeight = 18.sp
              )
            }
          }

          Text(
            text = "Concepts (${preview.concepts.size})",
            fontWeight = FontWeight.Bold,
            color = PaletteDarkText,
            fontSize = 14.sp
          )

          preview.concepts.forEachIndexed { index, draft ->
            Surface(
              color = PaletteMintFoam,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text(
                  text = "${index + 1}. ${draft.conceptName.ifBlank { draft.topic }}",
                  fontWeight = FontWeight.Bold,
                  color = PaletteDarkText,
                  fontSize = 14.sp,
                  modifier = Modifier.fillMaxWidth()
                )
                if (draft.subtopic.isNotBlank()) {
                  Spacer(modifier = Modifier.height(8.dp))
                  Surface(
                    color = PalettePaleCeladon,
                    shape = RoundedCornerShape(6.dp)
                  ) {
                    Text(
                      text = draft.subtopic,
                      fontSize = 11.sp,
                      color = PaletteDeepSage,
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                  }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                  text = draft.definition,
                  style = MaterialTheme.typography.bodySmall,
                  color = PaletteDarkText.copy(alpha = 0.85f),
                  lineHeight = 17.sp
                )
                if (draft.misconceptionsAndCorrections.isNotBlank()) {
                  Spacer(modifier = Modifier.height(8.dp))
                  Text(
                    text = "Trap: ${draft.misconceptionsAndCorrections}",
                    fontSize = 11.sp,
                    color = PaletteDarkText.copy(alpha = 0.72f),
                    fontStyle = FontStyle.Italic,
                    lineHeight = 16.sp
                  )
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          OutlinedButton(
            onClick = onDiscard,
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PaletteDarkText)
          ) {
            Text("Discard", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
          }

          Button(
            onClick = {
              onConfirm(
                editableTitle.trim(),
                if (subjectMode == "EXISTING") selectedSubject else null,
                editableTags.trim(),
                if (subjectMode == "CREATE_NEW") newSubjectName.trim() else null,
                if (subjectMode == "CREATE_NEW") newSubjectCode.trim() else null
              )
            },
            enabled = subjectMode != "CREATE_NEW" || newSubjectName.isNotBlank(),
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .testTag("confirm_save_concepts_button"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = PaletteDeepSage,
              contentColor = PaletteCreamIvory
            )
          ) {
            Text("Save", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

@Composable
private fun MoveChatToSubjectDialog(
  chat: ImportedChat,
  subjects: List<Subject>,
  onMoveExisting: (Subject) -> Unit,
  onCreateAndMove: (newName: String, newCode: String) -> Unit,
  onDismiss: () -> Unit
) {
  var createNew by remember { mutableStateOf(false) }
  var selected by remember { mutableStateOf(subjects.find { it.id == chat.subjectId } ?: subjects.firstOrNull()) }
  var newName by remember { mutableStateOf("") }
  var newCode by remember { mutableStateOf("") }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .widthIn(max = 520.dp)
        .testTag("move_chat_subject_dialog"),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = PaletteCreamIvory)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Move chat to subject",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = PaletteDarkText
        )
        Text(
          text = "Choose an existing academic subject or create a new group for \"${chat.title}\".",
          fontSize = 12.sp,
          color = PaletteDarkText.copy(alpha = 0.75f)
        )

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          subjects.forEach { subject ->
            val isChosen = !createNew && selected?.id == subject.id
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = if (isChosen) PaletteDeepSage else PaletteMintFoam,
              border = BorderStroke(1.dp, if (isChosen) PaletteDeepSage else PaletteMediumSage),
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  createNew = false
                  selected = subject
                }
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                  Text(
                    text = subject.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isChosen) PaletteCreamIvory else PaletteDarkText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = subject.code,
                    fontSize = 11.sp,
                    color = if (isChosen) PaletteCreamIvory.copy(alpha = 0.85f) else PaletteDeepSage
                  )
                }
                if (isChosen) {
                  Icon(Icons.Default.Check, contentDescription = "Selected", tint = PaletteCreamIvory, modifier = Modifier.size(18.dp))
                }
              }
            }
          }

          Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (createNew) PaletteDeepSage else PalettePaleCeladon,
            border = BorderStroke(1.dp, PaletteMediumSage),
            modifier = Modifier
              .fillMaxWidth()
              .clickable { createNew = true }
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = if (createNew) PaletteCreamIvory else PaletteDarkText,
                modifier = Modifier.size(18.dp)
              )
              Text(
                text = "+ Create New Subject",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (createNew) PaletteCreamIvory else PaletteDarkText
              )
            }
          }
        }

        if (createNew) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedTextField(
              value = newName,
              onValueChange = { newName = it },
              label = { Text("New Subject Name *") },
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(2f)
            )
            OutlinedTextField(
              value = newCode,
              onValueChange = { newCode = it },
              label = { Text("Code") },
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)
          ) {
            Text("Cancel", maxLines = 1, softWrap = false)
          }
          Button(
            onClick = {
              if (createNew) {
                if (newName.isNotBlank()) {
                  onCreateAndMove(newName.trim(), newCode.trim())
                }
              } else if (selected != null) {
                onMoveExisting(selected!!)
              }
            },
            enabled = if (createNew) newName.isNotBlank() else selected != null,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = PaletteDeepSage,
              contentColor = PaletteCreamIvory
            )
          ) {
            Text("Move", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

@Composable
private fun InspectSourceChatDialog(
  chat: ImportedChat,
  onDismiss: () -> Unit
) {
  val dateFormatted = remember(chat.importedAt) {
    SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(chat.importedAt))
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 20.dp)
        .widthIn(max = 560.dp)
        .fillMaxHeight(0.9f)
        .testTag("inspect_source_dialog"),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = PaletteCreamIvory)
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(20.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
              text = chat.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = PaletteDarkText,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = "${chat.aiPlatform} • ${chat.wordCount} words • $dateFormatted",
              style = MaterialTheme.typography.bodySmall,
              color = PaletteDeepSage,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }

          IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = PaletteDarkText)
          }
        }

        if (chat.sourceUrl.isNotBlank()) {
          Spacer(modifier = Modifier.height(8.dp))
          Surface(
            color = PalettePaleCeladon.copy(alpha = 0.55f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(14.dp), tint = PaletteDeepSage)
              Text(
                text = chat.sourceUrl,
                fontSize = 11.sp,
                color = PaletteDeepSage,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
          color = PaletteMintFoam,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
        ) {
          SelectionContainer {
            Column(
              modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
            ) {
              Text(
                text = chat.rawConversation,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                color = PaletteDarkText,
                lineHeight = 20.sp
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = PaletteDeepSage,
            contentColor = PaletteCreamIvory
          )
        ) {
          Text("Done", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
      }
    }
  }
}

@Composable
private fun EditChatDetailsDialog(
  chat: ImportedChat,
  subjects: List<Subject>,
  onSave: (ImportedChat) -> Unit,
  onDismiss: () -> Unit
) {
  var title by remember { mutableStateOf(chat.title) }
  var tags by remember { mutableStateOf(chat.tags) }
  var selectedSubjectName by remember { mutableStateOf(chat.subjectName) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .widthIn(max = 520.dp),
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = PaletteCreamIvory)
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Edit conversation details",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = PaletteDarkText
        )

        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Title") },
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp)
        )

        OutlinedTextField(
          value = selectedSubjectName,
          onValueChange = { selectedSubjectName = it },
          label = { Text("Subject / Course Name") },
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp)
        )

        OutlinedTextField(
          value = tags,
          onValueChange = { tags = it },
          label = { Text("Tags") },
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp)
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)
          ) {
            Text("Cancel", maxLines = 1, softWrap = false)
          }
          Button(
            onClick = {
              val subjId = subjects.find { it.name.equals(selectedSubjectName, ignoreCase = true) }?.id ?: chat.subjectId
              onSave(chat.copy(title = title, subjectName = selectedSubjectName, subjectId = subjId, tags = tags))
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = PaletteDeepSage,
              contentColor = PaletteCreamIvory
            )
          ) {
            Text("Save", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

@Composable
private fun EditConceptDialog(
  concept: ExtractedConcept,
  subjects: List<Subject>,
  onSave: (ExtractedConcept) -> Unit,
  onDismiss: () -> Unit
) {
  var conceptName by remember { mutableStateOf(concept.conceptName) }
  var topic by remember { mutableStateOf(concept.topic) }
  var subtopic by remember { mutableStateOf(concept.subtopic) }
  var definition by remember { mutableStateOf(concept.definition) }
  var technicalDetails by remember { mutableStateOf(concept.technicalDetails) }
  var misconceptions by remember { mutableStateOf(concept.misconceptionsAndCorrections) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 20.dp)
        .widthIn(max = 560.dp)
        .fillMaxHeight(0.85f),
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = PaletteCreamIvory)
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(20.dp)
      ) {
        Text(
          text = "Edit extracted concept",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = PaletteDarkText
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(
          modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          OutlinedTextField(
            value = conceptName,
            onValueChange = { conceptName = it },
            label = { Text("Concept name") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
          )
          OutlinedTextField(
            value = topic,
            onValueChange = { topic = it },
            label = { Text("Topic name") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
          )
          OutlinedTextField(
            value = subtopic,
            onValueChange = { subtopic = it },
            label = { Text("Subtopic") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
          )
          OutlinedTextField(
            value = definition,
            onValueChange = { definition = it },
            label = { Text("Definition") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            minLines = 2
          )
          OutlinedTextField(
            value = technicalDetails,
            onValueChange = { technicalDetails = it },
            label = { Text("Detailed Explanation / Mechanics") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            minLines = 3
          )
          OutlinedTextField(
            value = misconceptions,
            onValueChange = { misconceptions = it },
            label = { Text("Common Exam Trap / Misconception") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            minLines = 2
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)
          ) {
            Text("Cancel", maxLines = 1, softWrap = false)
          }
          Button(
            onClick = {
              onSave(
                concept.copy(
                  conceptName = conceptName,
                  topic = topic,
                  subtopic = subtopic,
                  definition = definition,
                  technicalDetails = technicalDetails,
                  misconceptionsAndCorrections = misconceptions
                )
              )
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = PaletteDeepSage,
              contentColor = PaletteCreamIvory
            )
          ) {
            Text("Save", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}
