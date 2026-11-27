package com.example.ui.screens

import com.example.platform.parseHexColor
import com.example.platform.LocalPlatformActions
import com.example.platform.PlatformBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import com.example.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import com.example.ui.theme.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.example.ui.theme.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.example.ui.theme.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.model.ChatMessage
import com.example.data.model.Subject
import com.example.data.ai.AiSource
import com.example.data.model.TutorSession
import com.example.ui.components.FormattedTutorResponse
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
import com.example.ui.viewmodel.StudyPilotViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SESSION_DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
  override fun initialValue(): SimpleDateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
}

private val CHAT_TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
  override fun initialValue(): SimpleDateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
}

private enum class TutorTab {
  CHAT,
  HISTORY
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiTutorScreen(
  viewModel: StudyPilotViewModel
) {
  val actions = LocalPlatformActions.current
  val messages by viewModel.chatMessages.collectAsState()
  val aiSource by viewModel.aiSource.collectAsState()
  val activeAiLabel by viewModel.activeAiLabel.collectAsState()
  val systemInstruction by viewModel.systemInstruction.collectAsState()
  val isChatLoading by viewModel.isChatLoading.collectAsState()

  val subjects by viewModel.subjects.collectAsState()
  val importedChats by viewModel.importedChats.collectAsState()
  val extractedConcepts by viewModel.extractedConcepts.collectAsState()
  val selectedSubject by viewModel.selectedTutorSubject.collectAsState()
  val tutorSessions by viewModel.tutorSessions.collectAsState()
  val activeSessionId by viewModel.activeSessionId.collectAsState()

  val linkedChatsCountForScope = remember(importedChats, selectedSubject) {
    val linked = importedChats.filter { it.sourceUrl.isNotBlank() }
    if (selectedSubject == null) {
      linked.size
    } else {
      linked.count {
        it.subjectId == selectedSubject?.id ||
          it.subjectName.equals(selectedSubject?.name, ignoreCase = true)
      }
    }
  }

  var selectedTab by remember { mutableStateOf(TutorTab.CHAT) }
  var isHeaderExpanded by remember { mutableStateOf(true) }
  var inputText by remember { mutableStateOf("") }
  var showSystemInstructionDialog by remember { mutableStateOf(false) }
  var showClearHistoryDialog by remember { mutableStateOf(false) }

  val listState = rememberLazyListState()

  // The PC may have come online or gone away since the tutor was last opened.
  LaunchedEffect(Unit) { viewModel.refreshActiveAi() }

  // Auto scroll to bottom when new messages arrive in Chat tab
  LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length?.div(200)) {
    if (messages.isNotEmpty() && selectedTab == TutorTab.CHAT) {
      listState.animateScrollToItem(messages.size - 1)
    }
  }

  // Chat / History and the tutor's buttons. On the desktop they sit in the page header.
  val tutorTopBar: @Composable (inHeader: Boolean) -> Unit = { inHeader ->
    Row(
      modifier = if (inHeader) Modifier else Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
      horizontalArrangement = if (inHeader) Arrangement.spacedBy(12.dp) else Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
          // Tab Switcher Buttons (Symmetrical Pill Switcher)
          Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = 4.dp)
          ) {
            Row(modifier = Modifier.padding(4.dp)) {
              Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (selectedTab == TutorTab.CHAT) MaterialTheme.colorScheme.primary else Color.Transparent,
                modifier = Modifier
                  .clip(RoundedCornerShape(20.dp)).clickable { selectedTab = TutorTab.CHAT }
                  .clip(RoundedCornerShape(20.dp))
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = if (selectedTab == TutorTab.CHAT) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = "Active chat",
                    fontSize = 12.sp,
                    fontWeight = if (selectedTab == TutorTab.CHAT) FontWeight.Bold else FontWeight.Medium,
                    color = if (selectedTab == TutorTab.CHAT) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }

              Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (selectedTab == TutorTab.HISTORY) MaterialTheme.colorScheme.primary else Color.Transparent,
                modifier = Modifier
                  .clip(RoundedCornerShape(20.dp)).clickable { selectedTab = TutorTab.HISTORY }
                  .clip(RoundedCornerShape(20.dp))
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = if (selectedTab == TutorTab.HISTORY) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = "History (${tutorSessions.size})",
                    fontSize = 12.sp,
                    fontWeight = if (selectedTab == TutorTab.HISTORY) FontWeight.Bold else FontWeight.Medium,
                    color = if (selectedTab == TutorTab.HISTORY) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }
          }

          // Top Right Quick Actions + Hide/Show Elements Toggle
          Row(verticalAlignment = Alignment.CenterVertically) {
            // Hide/Show controls button for maximizing chat area
            if (selectedTab == TutorTab.CHAT) {
              IconButton(
                onClick = { isHeaderExpanded = !isHeaderExpanded },
                modifier = Modifier.size(36.dp)
              ) {
                Icon(
                  imageVector = if (isHeaderExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                  contentDescription = if (isHeaderExpanded) "Collapse Toolbar" else "Expand Toolbar",
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(22.dp)
                )
              }
            }

            IconButton(
              onClick = {
                viewModel.startNewTutorChat()
                selectedTab = TutorTab.CHAT
              },
              modifier = Modifier.size(36.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New Chat Session",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
              )
            }

            IconButton(
              onClick = { showSystemInstructionDialog = true },
              modifier = Modifier.size(36.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Tutor Persona",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
              )
            }
          }
        }
  }
  val topBarInHeader = com.example.ui.HeaderControls { tutorTopBar(true) }

  Column(
    modifier = Modifier.fillMaxSize()
  ) {
    // 1. Top Mode Selector Bar (Chat vs History tabs)
    Surface(
      color = com.example.ui.theme.pageBackground(MaterialTheme.colorScheme.surface),
      shadowElevation = 0.dp,
      modifier = Modifier.fillMaxWidth()
    ) {
      Column {
        if (!topBarInHeader) tutorTopBar(false)

        // 2. Subject Scoping Selector (collapsible to maximize chat space)
        AnimatedVisibility(visible = isHeaderExpanded || selectedTab == TutorTab.HISTORY) {
          LazyRow(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
          ) {
          item {
            FilterChip(
              selected = selectedSubject == null,
              onClick = { viewModel.selectTutorSubject(null) },
              label = {
                Text(
                  text = "All subjects",
                  fontSize = 12.sp,
                  fontWeight = if (selectedSubject == null) FontWeight.Bold else FontWeight.Normal
                )
              },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
              )
            )
          }

          items(subjects, key = { it.id }) { subject ->
            val isSelected = selectedSubject?.id == subject.id
            val primaryColor = MaterialTheme.colorScheme.primary
            val subjectColor = remember(subject.colorHex, primaryColor) {
              parseHexColor(subject.colorHex) ?: primaryColor
            }

            FilterChip(
              selected = isSelected,
              onClick = { viewModel.selectTutorSubject(subject) },
              leadingIcon = {
                Box(
                  modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(subjectColor)
                )
              },
              label = {
                Text(
                  text = "${subject.code} • ${subject.name}",
                  fontSize = 12.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = subjectColor.copy(alpha = 0.2f),
                selectedLabelColor = MaterialTheme.colorScheme.onSurface
              )
            )
          }
        }
      }
    }
  }

    // 3. Tab Contents
    if (selectedTab == TutorTab.CHAT) {
      ChatTabContent(
        viewModel = viewModel,
        messages = messages,
        selectedSubject = selectedSubject,
        importedChats = importedChats,
        extractedConcepts = extractedConcepts,
        linkedChatsCount = linkedChatsCountForScope,
        aiSource = aiSource,
        activeAiLabel = activeAiLabel,
        isChatLoading = isChatLoading,
        isHeaderExpanded = isHeaderExpanded,
        onToggleHeader = { isHeaderExpanded = !isHeaderExpanded },
        inputText = inputText,
        onInputTextChange = { inputText = it },
        listState = listState,
        onClearChat = { viewModel.clearChat() }
      )
    } else {
      HistoryTabContent(
        tutorSessions = tutorSessions,
        selectedSubject = selectedSubject,
        activeSessionId = activeSessionId,
        onSelectSession = { session ->
          viewModel.loadTutorSession(session)
          selectedTab = TutorTab.CHAT
        },
        onDeleteSession = { sessionId ->
          viewModel.deleteTutorSession(sessionId)
        },
        onClearAllHistory = { showClearHistoryDialog = true },
        onStartNewChat = {
          viewModel.startNewTutorChat()
          selectedTab = TutorTab.CHAT
        }
      )
    }
  }

  // System Instruction Dialog
  if (showSystemInstructionDialog) {
    var editedInstruction by remember { mutableStateOf(systemInstruction) }
    AlertDialog(
      onDismissRequest = { showSystemInstructionDialog = false },
      title = { Text("Tutor style", fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = "How should the tutor explain things?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          // Persona Presets
          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            val profText = "You are StudyPilot Academic Tutor, an expert professor explaining university topics clearly, breaking down proofs and derivations into digestible steps."
            val resText = "You are an Elite Academic Research Guide specializing in STEM literature, citing recent arXiv and IEEE papers with precision."
            val coderText = "You are a Principal Software Engineer & Algorithms Coach. Provide clean, well-tested Kotlin code and asymptotic runtime analysis."

            val isProf = editedInstruction.contains("professor", ignoreCase = true)
            val isRes = editedInstruction.contains("Research Guide", ignoreCase = true)
            val isCoder = editedInstruction.contains("Software Engineer", ignoreCase = true)

            Button(
              onClick = { editedInstruction = profText },
              shape = RoundedCornerShape(10.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = if (isProf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isProf) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
              ),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
              modifier = Modifier.heightIn(min = 44.dp)
            ) {
              Text("Professor", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }

            Button(
              onClick = { editedInstruction = resText },
              shape = RoundedCornerShape(10.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = if (isRes) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isRes) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
              ),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
              modifier = Modifier.heightIn(min = 44.dp)
            ) {
              Text("Researcher", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }

            Button(
              onClick = { editedInstruction = coderText },
              shape = RoundedCornerShape(10.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = if (isCoder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isCoder) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
              ),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
              modifier = Modifier.heightIn(min = 44.dp)
            ) {
              Text("Coder", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
          }

          OutlinedTextField(
            value = editedInstruction,
            onValueChange = { editedInstruction = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 6
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.updateSystemInstruction(editedInstruction)
            showSystemInstructionDialog = false
          }
        ) {
          Text("Save style")
        }
      },
      dismissButton = {
        TextButton(onClick = { showSystemInstructionDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }

  // Clear History Confirmation Dialog
  if (showClearHistoryDialog) {
    AlertDialog(
      onDismissRequest = { showClearHistoryDialog = false },
      title = { Text("Clear tutor history?", fontWeight = FontWeight.Bold) },
      text = {
        Text("This deletes every saved tutor chat for all subjects. It can't be undone.")
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.clearAllTutorHistory()
            showClearHistoryDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = RoseError)
        ) {
          Text("Clear all")
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearHistoryDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }
}

@Composable
private fun ChatTabContent(
  viewModel: StudyPilotViewModel,
  messages: List<ChatMessage>,
  selectedSubject: Subject?,
  importedChats: List<com.example.data.model.ImportedChat>,
  extractedConcepts: List<com.example.data.model.ExtractedConcept>,
  linkedChatsCount: Int,
  aiSource: AiSource,
  activeAiLabel: String,
  isChatLoading: Boolean,
  isHeaderExpanded: Boolean,
  onToggleHeader: () -> Unit,
  inputText: String,
  onInputTextChange: (String) -> Unit,
  listState: androidx.compose.foundation.lazy.LazyListState,
  onClearChat: () -> Unit
) {
  val actions = LocalPlatformActions.current

  val questionForAnswer = remember(messages) {
    buildMap {
      var question: String? = null
      for (m in messages) {
        if (m.role == "user") {
          if (!m.text.contains(FOLLOW_UP_MARKER)) question = m.text
        } else if (question != null) {
          put(m.id, question)
        }
      }
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    // Subject Active Banner (if a subject is selected)
    if (selectedSubject != null) {
      val primaryColor = MaterialTheme.colorScheme.primary
      val subColor = remember(selectedSubject.colorHex, primaryColor) {
        parseHexColor(selectedSubject.colorHex) ?: primaryColor
      }

      Surface(
        color = subColor.copy(alpha = 0.1f),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .weight(1f)
              .padding(end = 8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.School,
              contentDescription = null,
              tint = subColor,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "${selectedSubject.code}: ${selectedSubject.name}",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                text = if (linkedChatsCount > 0) "Checks $linkedChatsCount linked chat(s) first" else "No linked chats for this subject yet",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }

          TextButton(
            onClick = { viewModel.selectTutorSubject(null) },
            modifier = Modifier.heightIn(min = 44.dp)
          ) {
            Text("Reset", fontSize = 11.sp, color = subColor, maxLines = 1, softWrap = false)
          }
        }
      }
    }

    // Compact banner when header is collapsed with quick expand tap
    AnimatedVisibility(visible = !isHeaderExpanded) {
      Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onToggleHeader() }
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            Text(
              text = activeAiLabel +
                (if (selectedSubject != null) " • ${selectedSubject.code}" else "") +
                " • $linkedChatsCount linked",
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show controls", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
            Icon(Icons.Default.ExpandMore, contentDescription = "Expand", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
          }
        }
      }
    }

    // Answer source picker (collapsible)
    AnimatedVisibility(visible = isHeaderExpanded) {
      Surface(
        color = com.example.ui.theme.pageBackground(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
      ) {
      Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        // Where answers come from (also in Settings > AI)
        LazyRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
          verticalAlignment = Alignment.CenterVertically
        ) {
          items(AiSource.entries) { option ->
            val isSelected = aiSource == option
            FilterChip(
              selected = isSelected,
              onClick = { viewModel.setAiSource(option) },
              label = {
                Text(
                  text = option.label,
                  fontSize = 11.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
              },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
              )
            )
          }
        }

        Text(
          text = "Answering with: $activeAiLabel" +
            (if (linkedChatsCount > 0) " • checks $linkedChatsCount linked chat(s) first" else ""),
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          textAlign = androidx.compose.ui.text.style.TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)
        )
      }
    }
  }

    // Contextual Quick Prompts: prioritized from provided chat links first!
    val linkPromptChips = remember(importedChats, extractedConcepts, selectedSubject) {
      com.example.data.ai.ChatHistoryAssistant.buildQuickPromptsFromLinkedChats(
        importedChats = importedChats,
        extractedConcepts = extractedConcepts,
        selectedSubject = selectedSubject
      )
    }
    val subjectPrompts = remember(selectedSubject) {
      getPromptsForSubject(selectedSubject)
    }
    AnimatedVisibility(visible = isHeaderExpanded && (linkPromptChips.isNotEmpty() || subjectPrompts.isNotEmpty())) {
      LazyRow(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
      ) {
        if (linkPromptChips.isNotEmpty()) {
          items(linkPromptChips, key = { "${it.label}|${it.sourceUrl}|${it.prompt}" }) { linkChip ->
            PromptChip(
              text = linkChip.label,
              onClick = {
                viewModel.sendChatMessage(linkChip.prompt)
              }
            )
          }
        } else {
          items(subjectPrompts, key = { it.first }) { prompt ->
            PromptChip(
              text = prompt.first,
              onClick = { viewModel.sendChatMessage(prompt.fourth) }
            )
          }
        }
      }
    }

    // Scrollable Chat Thread
    LazyColumn(
      state = listState,
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      items(messages, key = { it.id }) { msg ->
        ChatMessageBubble(
          message = msg,
          sourceQuestion = questionForAnswer[msg.id],
          importedChats = importedChats,
          extractedConcepts = extractedConcepts,
          selectedSubject = selectedSubject,
          onCopy = {
            actions.copyText(msg.text, "Copied response to clipboard")
          },
          onOpenUrl = { url -> actions.openUrl(url) },
          onTopicClick = { topic ->
            viewModel.sendChatMessage(topic)
          },
          onFollowUp = { request ->
            // Answer about this message's question, not whatever was asked last.
            viewModel.sendChatMessage(request, replyingTo = msg.id)
          }
        )
      }

      if (isChatLoading && messages.lastOrNull()?.role == "user") {
        item {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Thinking...",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }

    // Input Bar
    Surface(
      color = com.example.ui.theme.pageBackground(MaterialTheme.colorScheme.surface),
      shadowElevation = 0.dp,
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          OutlinedTextField(
            value = inputText,
            onValueChange = onInputTextChange,
            placeholder = {
              Text(
                text = if (selectedSubject != null) "Ask a concept in ${selectedSubject.code}..." else "Ask any academic concept...",
                fontSize = 13.sp
              )
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
              unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            ),
            maxLines = 3
          )

          Spacer(modifier = Modifier.width(8.dp))

          IconButton(
            onClick = {
              if (inputText.isNotBlank() && !isChatLoading) {
                viewModel.sendChatMessage(inputText)
                onInputTextChange("")
              }
            },
            enabled = inputText.isNotBlank() && !isChatLoading,
            modifier = Modifier
              .size(44.dp)
              .clip(CircleShape)
              .background(if (inputText.isNotBlank() && !isChatLoading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Send,
              contentDescription = "Send",
              tint = if (inputText.isNotBlank() && !isChatLoading) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
              modifier = Modifier.size(20.dp)
            )
          }
        }
      }
    }
  }
}

@Composable
private fun HistoryTabContent(
  tutorSessions: List<TutorSession>,
  selectedSubject: Subject?,
  activeSessionId: Long?,
  onSelectSession: (TutorSession) -> Unit,
  onDeleteSession: (Long) -> Unit,
  onClearAllHistory: () -> Unit,
  onStartNewChat: () -> Unit
) {
  val actions = LocalPlatformActions.current
  var searchQuery by remember { mutableStateOf("") }

  val filteredSessions = remember(tutorSessions, selectedSubject, searchQuery) {
    tutorSessions.filter { session ->
      val matchesSubject = selectedSubject == null || session.subjectId == selectedSubject.id
      val matchesSearch = searchQuery.isBlank() ||
        session.title.contains(searchQuery, ignoreCase = true) ||
        session.previewSnippet.contains(searchQuery, ignoreCase = true) ||
        session.subjectName.contains(searchQuery, ignoreCase = true)
      matchesSubject && matchesSearch
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 8.dp)
  ) {
    // History Search Bar
    OutlinedTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      placeholder = { Text("Search past questions, topics, and answers...", fontSize = 13.sp) },
      leadingIcon = {
        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
      },
      trailingIcon = {
        if (searchQuery.isNotEmpty()) {
          IconButton(onClick = { searchQuery = "" }) {
            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
          }
        }
      },
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(12.dp),
      colors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
      ),
      singleLine = true
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Top status row with Clear History action
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = if (selectedSubject != null) "Sessions for ${selectedSubject.name} (${filteredSessions.size})" else "Past chats (${filteredSessions.size})",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .weight(1f)
          .padding(end = 8.dp)
      )

      if (tutorSessions.isNotEmpty()) {
        TextButton(onClick = onClearAllHistory, modifier = Modifier.heightIn(min = 44.dp)) {
          Text("Clear all", fontSize = 12.sp, color = RoseError, maxLines = 1, softWrap = false)
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (filteredSessions.isEmpty()) {
      // Empty state
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.padding(24.dp)
        ) {
          Box(
            modifier = Modifier
              .size(56.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.History,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(28.dp)
            )
          }

          Text(
            text = if (searchQuery.isNotBlank()) "No sessions matching '$searchQuery'" else "No past tutor sessions yet",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
          )

          Text(
            text = "Your past chats appear here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp)
          )

          Spacer(modifier = Modifier.height(8.dp))

          Button(
            onClick = onStartNewChat,
            shape = RoundedCornerShape(12.dp)
          ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start a new chat")
          }
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        items(filteredSessions, key = { it.id }) { session ->
          TutorSessionHistoryCard(
            session = session,
            isActive = session.id == activeSessionId,
            onClick = { onSelectSession(session) },
            onCopy = {
              actions.copyText(session.previewSnippet, "Snippet copied to clipboard")
            },
            onDelete = { onDeleteSession(session.id) }
          )
        }
      }
    }
  }
}

@Composable
private fun TutorSessionHistoryCard(
  session: TutorSession,
  isActive: Boolean,
  onClick: () -> Unit,
  onCopy: () -> Unit,
  onDelete: () -> Unit
) {
  val dateStr = remember(session.lastUpdatedAt) {
    SESSION_DATE_FORMAT.get()?.format(Date(session.lastUpdatedAt)).orEmpty()
  }

  val cleanSnippet = remember(session.previewSnippet) {
    session.previewSnippet
      .replace(Regex("""#+\s*"""), "")
      .replace("**", "")
      .replace("```", "")
      .trim()
  }

  Card(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface
    ),
    border = androidx.compose.foundation.BorderStroke(
      1.dp,
      if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Subject Badge
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.surfaceVariant
        ) {
          Text(
            text = session.subjectName,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          if (session.searchGroundingUsed) {
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = CyanAccent.copy(alpha = 0.15f),
              modifier = Modifier.padding(end = 8.dp)
            ) {
              Text(
                text = "Web search",
                style = MaterialTheme.typography.labelSmall,
                color = CyanAccent,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
              )
            }
          }

          Text(
            text = dateStr,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Session Title
      Text(
        text = session.title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )

      Spacer(modifier = Modifier.height(4.dp))

      // Answer Snippet
      Text(
        text = cleanSnippet,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )

      Spacer(modifier = Modifier.height(12.dp))

      // Footer: Actions & Model Pill
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          modifier = Modifier
            .weight(1f, fill = false)
            .padding(end = 8.dp)
        ) {
          Text(
            text = "${session.modelUsed} • ${session.messageCount} turns",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = onCopy, modifier = Modifier.size(44.dp)) {
            Icon(
              imageVector = Icons.Default.ContentCopy,
              contentDescription = "Copy Snippet",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(16.dp)
            )
          }

          IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
            Icon(
              imageVector = Icons.Default.Delete,
              contentDescription = "Delete Session",
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(16.dp)
            )
          }

          Spacer(modifier = Modifier.width(4.dp))

          Button(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.heightIn(min = 40.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
          ) {
            Text("Open chat", fontSize = 11.sp, maxLines = 1, softWrap = false)
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageBubble(
  message: ChatMessage,
  importedChats: List<com.example.data.model.ImportedChat>,
  extractedConcepts: List<com.example.data.model.ExtractedConcept>,
  selectedSubject: Subject?,
  onCopy: () -> Unit,
  onOpenUrl: (String) -> Unit,
  onTopicClick: (String) -> Unit,
  sourceQuestion: String? = null,
  onFollowUp: (String) -> Unit = onTopicClick
) {
  val isUser = message.role == "user"
  val timeStr = remember(message.timestamp) {
    CHAT_TIME_FORMAT.get()?.format(Date(message.timestamp)).orEmpty()
  }

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
  ) {
    if (!isUser) {
      Box(
        modifier = Modifier
          .size(32.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
      ) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
      }
      Spacer(modifier = Modifier.width(8.dp))
    }

    Column(
      modifier = if (isUser) Modifier.widthIn(max = 280.dp) else Modifier.fillMaxWidth().weight(1f),
      horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
      // Model & Source Badge (Linked Chat History, Gemini, or Local AI)
      if (!isUser) {
        // Labels are already short ("PC · qwen3:8b", "Phone · Gemma 4 E2B", "Built-in answer").
        val cleanModelName = message.modelName.ifBlank { "Tutor" }.take(32)

        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
          modifier = Modifier.padding(bottom = 8.dp)
        ) {
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
          ) {
            Text(
              text = cleanModelName,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
          }

          if ((message.searchGroundingUsed || message.groundingSources.isNotEmpty()) &&
            !cleanModelName.contains("Academic") &&
            !cleanModelName.contains("Local") &&
            !cleanModelName.contains("Solver") &&
            !cleanModelName.contains("Engine")
          ) {
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = CyanAccent.copy(alpha = 0.15f)
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
              ) {
                Icon(Icons.Default.Public, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(10.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "Sources",
                  style = MaterialTheme.typography.labelSmall,
                  color = CyanAccent,
                  maxLines = 1,
                  softWrap = false
                )
              }
            }
          }
        }
      }

      // Message Card with Formatted Markdown Rendering
      Card(
        shape = RoundedCornerShape(
          topStart = 16.dp,
          topEnd = 16.dp,
          bottomStart = if (isUser) 16.dp else 4.dp,
          bottomEnd = if (isUser) 4.dp else 16.dp
        ),
        colors = CardDefaults.cardColors(
          containerColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isUser) 0.dp else 2.dp)
      ) {
        val linkScopedTopics = remember(message.text, message.groundingSources, importedChats, extractedConcepts, selectedSubject) {
          if (isUser) {
            emptyList()
          } else {
            com.example.data.ai.ChatHistoryAssistant.getMiniTopicsForLinkedScope(
              queryOrText = message.text,
              groundingUrls = message.groundingSources.map { it.url },
              importedChats = importedChats,
              extractedConcepts = extractedConcepts,
              selectedSubject = selectedSubject
            )
          }
        }
        val (displayContent, dynamicTopics) = remember(
          message.text,
          message.modelName,
          message.relatedTopics,
          linkScopedTopics
        ) {
          if (isUser) {
            Pair(message.text, emptyList())
          } else {
            val (cleaned, extracted) = com.example.data.ai.AcademicPedagogySynthesizer.extractAndCleanRelatedTopics(
              rawContent = message.text,
              fallbackQuery = message.text
            )
            val topics = when {
              linkScopedTopics.isNotEmpty() -> {
                if (message.modelName.startsWith("Linked Chat", ignoreCase = true) && message.relatedTopics.isNotEmpty()) {
                  message.relatedTopics
                } else {
                  linkScopedTopics
                }
              }
              else -> message.relatedTopics.ifEmpty { extracted }
            }
            Pair(cleaned, topics)
          }
        }

        Column(modifier = Modifier.padding(16.dp)) {
          // Render rich formatted response (Code blocks, headings, lists, callouts)
          FormattedTutorResponse(
            content = displayContent,
            isUser = isUser
          )

          // Grounding Sources & Citations Section
          if (!isUser && message.groundingSources.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = "Sources",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                  )
                }
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  message.groundingSources.take(4).forEach { source ->
                    val opensLink = source.url.startsWith("http")
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = MaterialTheme.colorScheme.surface,
                      modifier = if (opensLink) Modifier.clickable { onOpenUrl(source.url) } else Modifier
                    ) {
                      Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Text(
                          text = source.title.take(48),
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.primary
                        )
                        if (opensLink) {
                          Spacer(modifier = Modifier.width(2.dp))
                          Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(10.dp))
                        }
                      }
                    }
                  }
                }
              }
            }
          }

          // 1. Quick Action Follow-Up Buttons ("Another Example", "Example in Code", "Summarize", "Explain Example")
          if (!isUser && !message.isWelcome) {
            Spacer(modifier = Modifier.height(12.dp))

            val linkedChatTitle = remember(message.groundingSources, importedChats) {
              val urls = message.groundingSources.map { it.url.trim() }.filter { it.isNotBlank() }
              importedChats.firstOrNull { chat ->
                chat.sourceUrl.isNotBlank() && urls.any { it.equals(chat.sourceUrl.trim(), ignoreCase = true) }
              }?.title
            }
            val detectedTopic = remember(message.text, linkedChatTitle, linkScopedTopics, sourceQuestion) {
              linkedChatTitle?.takeIf { it.isNotBlank() }
                ?: com.example.data.ai.LocalAiEngine.extractTopicFromMessage(message.text)
                ?: sourceQuestion?.let { "\"${questionLabel(it)}\"" }
                ?: linkScopedTopics.firstOrNull()
            }

            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              // Action 1: Another Example
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.clickable {
                  val query = if (detectedTopic != null) "Give another example of $detectedTopic. Answer only with the alternative example and its walkthrough."
                              else "Give another example. Answer only with the new example and walkthrough."
                  onFollowUp(query)
                }
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = "Another example",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                  )
                }
              }

              // Action 2: Example in Code
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.clickable {
                  val query = if (detectedTopic != null) "Show an example in code for $detectedTopic. Answer only with the code implementation and explanation."
                              else "Show an example in code. Answer only with the code and explanation."
                  onFollowUp(query)
                }
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = "Example in code",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                  )
                }
              }

              // Action 2b: Another Example in Code
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.clickable {
                  val query = if (detectedTopic != null) "Show another example in code for $detectedTopic. Answer only with the alternative code implementation and explanation."
                              else "Show another example in code. Answer only with the alternative code and explanation."
                  onFollowUp(query)
                }
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = "Another example in code",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                  )
                }
              }

              // Action 3: Summarize
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.clickable {
                  val query = if (detectedTopic != null) "Summarize $detectedTopic. Answer only with a concise summary and key takeaways."
                              else "Summarize this. Answer only with a concise summary."
                  onFollowUp(query)
                }
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = "Summarize",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                  )
                }
              }

              // Action 4: Explain Example
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.clickable {
                  val query = if (detectedTopic != null) "Explain the example of $detectedTopic. Answer only with a step-by-step walkthrough of the example."
                              else "Explain the example. Answer only with a step-by-step walkthrough."
                  onFollowUp(query)
                }
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = "Explain example",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                  )
                }
              }
            }
          }

          // 2. Related Domain-Specific Subtopic Buttons Section (Only Buttons, Domain-Specific)
          if (!isUser && !message.isWelcome && dynamicTopics.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              dynamicTopics.forEach { topic ->
                Surface(
                  shape = RoundedCornerShape(16.dp),
                  color = MaterialTheme.colorScheme.primaryContainer,
                  modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onTopicClick(topic) }
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Icon(
                      imageVector = Icons.Default.AutoAwesome,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                      text = topic,
                      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                      color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                  }
                }
              }
            }
          }
        }
      }

      // Timestamp & Copy
      Row(
        modifier = Modifier.padding(top = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = timeStr,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
        if (!isUser) {
          Spacer(modifier = Modifier.width(8.dp))
          Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy message",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .size(13.dp)
              .clickable { onCopy() }
          )
        }
      }
    }
  }
}

@Composable
private fun PromptChip(
  text: String,
  onClick: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    shadowElevation = 0.dp,
    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onClick() }
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
  }
}

/** Suggested questions for the selected subject: chip label and the prompt it sends. */
private fun getPromptsForSubject(subject: Subject?): List<PromptOption> {
  if (subject == null) {
    return listOf(
      PromptOption("Java Objects & Memory", "Explain Java Objects, reference semantics, and heap/stack memory architecture with code examples."),
      PromptOption("Quantum Physics & Superposition", "Explain Quantum Superposition, wave-particle duality, and mathematical formulation."),
      PromptOption("Aerodynamics: How Planes Fly", "Explain how airplanes fly with Bernoulli's principle, angle of attack, and lift mechanics."),
      PromptOption("Bayesian Inference & MCMC", "Explain Bayes' Theorem, prior/posterior distributions, and Markov Chain Monte Carlo sampling."),
      PromptOption("Inflation & Monetary Policy", "Explain inflation, currency supply, interest rates, and central bank monetary policy mechanisms.")
    )
  }

  val name = subject.name.lowercase()
  val code = subject.code.lowercase()

  return when {
    name.contains("data structure") || name.contains("algorithm") || code.contains("cs2") -> listOf(
      PromptOption("AVL Double Rotations in Kotlin", "Explain how Left-Right and Right-Left double rotations work in AVL trees, and provide clean Kotlin code."),
      PromptOption("Dijkstra vs Bellman-Ford", "Compare Dijkstra and Bellman-Ford algorithms regarding time complexity, negative edges, and cycle detection."),
      PromptOption("Prove Master Theorem case 2", "Derive and prove Case 2 of the Master Theorem for divide-and-conquer algorithmic recurrences."),
      PromptOption("Self-balancing trees research", "What are the modern variants of self-balancing search trees (e.g. WAVL, Zip trees) used in database indexing?")
    )
    name.contains("linear algebra") || name.contains("calculus") || code.contains("math") -> listOf(
      PromptOption("Matrix Diagonalization Proof", "Prove the necessary and sufficient conditions for matrix diagonalizability with eigenvalues and eigenspaces."),
      PromptOption("Gram-Schmidt Step-by-Step", "Walk me through the Gram-Schmidt orthogonalization algorithm with an example step-by-step derivation."),
      PromptOption("Eigenvalues of Orthogonal Matrix", "Explain why all eigenvalues of a real orthogonal matrix must have absolute value equal to 1."),
      PromptOption("Spectral Theorem application", "Explain the Spectral Theorem and its modern applications in machine learning SVD and PCA.")
    )
    name.contains("physics") || code.contains("phys") -> listOf(
      PromptOption("Davisson-Germer electron diffraction", "Explain the Davisson-Germer experiment that verified de Broglie's wave hypothesis,."),
      PromptOption("Schrödinger 1D Infinite Well", "Derive the energy eigenstates and wavefunctions for a particle in a 1D infinite square well."),
      PromptOption("Heisenberg Uncertainty Derivation", "Derive Heisenberg's uncertainty principle using operators and the Cauchy-Schwarz inequality.")
    )
    name.contains("software") || name.contains("engineering") || code.contains("cs3") -> listOf(
      PromptOption("SOLID Principles in Kotlin", "Explain all 5 SOLID principles with concrete, clean Kotlin code examples."),
      PromptOption("Dependency Injection vs Factory", "Explain the key differences and design trade-offs between Dependency Injection and Factory patterns."),
      PromptOption("Thread-safe Singleton in Kotlin", "How is the thread-safe Singleton pattern implemented idiomatically in Kotlin?")
    )
    else -> listOf(
      PromptOption("Key theorems in ${subject.code}", "What are the most crucial core theorems and exam questions in ${subject.name}? "),
      PromptOption("Step-by-step concept breakdown", "Break down the core principles of ${subject.name} into clear conceptual pillars with exam tips."),
      PromptOption("5-minute summary", "Give me a high-yield 5-minute study summary for ${subject.name}.")
    )
  }
}

private data class PromptOption(
  val first: String,
  val fourth: String
)

private fun extractRelatedTopics(content: String): List<String> {
  val lines = content.lines()
  val topics = mutableListOf<String>()
  var capturing = false
  for (line in lines) {
    val trimmed = line.trim()
    if (trimmed.contains("Connecting") || trimmed.contains("Related Topics") || trimmed.contains("Next Topics")) {
      capturing = true
      continue
    }
    if (capturing) {
      if (trimmed.startsWith("###") || trimmed.startsWith("##") || trimmed.startsWith("#") || trimmed.startsWith("🔗 **Source")) {
        break
      }
      if (trimmed.startsWith("-") || trimmed.startsWith("*") || trimmed.startsWith("•")) {
        val cleanTopic = trimmed.replace(Regex("^[-*•#]+\\s*"), "").replace("**", "").trim()
        if (cleanTopic.isNotBlank()) {
          topics.add(cleanTopic)
        }
      }
    }
  }
  return topics.distinct().take(6)
}

/** Every follow-up button request ends with this phrase; used to find the question it refers to. */
private const val FOLLOW_UP_MARKER = "Answer only with"

/** The question part of a message: up to the first "?" and at most 80 characters. */
private fun questionLabel(text: String): String {
  val trimmed = text.trim()
  val end = trimmed.indexOf('?')
  val question = if (end in 1..79) trimmed.substring(0, end) else trimmed.take(80)
  return question.trim().trimEnd('.', '!', '?')
}
