package com.example.ui.screens

import com.example.ui.theme.statTile
import com.example.ui.theme.statLabel
import com.example.ui.theme.LocalAppStyle
import com.example.ui.theme.AppStyle
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import com.example.platform.LocalPlatformActions
import com.example.platform.PlatformBackHandler
import com.example.ui.components.formatTimeRange
import com.example.ui.components.formatTo12Hour
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import com.example.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import com.example.ui.theme.Card
import androidx.compose.material3.CardDefaults
import com.example.ui.theme.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.example.ui.theme.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StudyTask
import com.example.data.model.StudyTopic
import com.example.data.model.TimetableSession
import com.example.ui.components.MasteryBadge
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatCard
import com.example.ui.components.WhatShouldIDoNowCard
import com.example.ui.theme.PaletteCreamIvory
import com.example.ui.theme.PaletteDarkText
import com.example.ui.theme.PaletteDeepSage
import com.example.ui.theme.PaletteMediumSage
import com.example.ui.theme.PaletteMintFoam
import com.example.ui.theme.PalettePaleCeladon
import com.example.ui.theme.RoseError
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CyanAccent
import com.example.ui.viewmodel.Screen
import com.example.ui.viewmodel.StudyPilotViewModel

import com.example.ui.components.MobileClockAlarmCard
import com.example.ui.components.QuickMobileAlarmDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun DashboardScreen(
  viewModel: StudyPilotViewModel,
  onNavigate: (Screen) -> Unit,
  onQuickAddTopic: () -> Unit,
  onQuickAddSession: () -> Unit
) {
  val sessions by viewModel.sessions.collectAsState()
  var editingSession by remember { mutableStateOf<TimetableSession?>(null) }
  val topics by viewModel.topics.collectAsState()
  val tasks by viewModel.tasks.collectAsState()
  val attempts by viewModel.quizAttempts.collectAsState()
  val scheduleStatus by viewModel.scheduleStatus.collectAsState()
  val currentUser by viewModel.currentUser.collectAsState()
  val studyStreak by viewModel.studyStreak.collectAsState()
  val importedChats by viewModel.importedChats.collectAsState()
  val extractedConcepts by viewModel.extractedConcepts.collectAsState()
  val actions = LocalPlatformActions.current

  var showQuickAlarmDialog by remember { mutableStateOf(false) }
  val activeAlarmsCount = remember(sessions) { sessions.count { it.isAlarmEnabled } }

  val averageScore = remember(attempts) {
    if (attempts.isNotEmpty()) {
      attempts.map { it.scorePercentage }.average().toInt()
    } else 0
  }

  val masteredCount = remember(topics) { topics.count { it.masteryLevel == "MASTERED" } }
  val strugglingCount = remember(topics) { topics.count { it.masteryLevel == "STRUGGLING" } }
  val pendingTasks = remember(tasks) { tasks.filter { !it.isCompleted } }

  // Each part of the dashboard, so the phone can stack them and the desktop can lay them out in columns.
  val greeting: @Composable () -> Unit = {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      val isGuestMode = !currentUser.isLoggedIn || currentUser.id == com.example.ui.viewmodel.StudyPilotViewModel.GUEST_USER_ID
      val firstName = currentUser.name.trim().split(" ").firstOrNull().orEmpty().ifBlank { "Guest" }
      Column {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = if (isGuestMode) Modifier.clickable { onNavigate(Screen.Login) } else Modifier
        ) {
          Text(
            text = if (isGuestMode) "Welcome" else "Hello, $firstName",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
          )
          if (isGuestMode) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "· Sign in",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary
            )
          }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.getDefault()).format(java.util.Date()),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.LocalFireDepartment,
            contentDescription = "Streak",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "$studyStreak d",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
          )
        }
      }
    }
  }
  val whatNow: @Composable () -> Unit = {
    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
      WhatShouldIDoNowCard(
        status = scheduleStatus,
        onPlanScheduleClick = { onNavigate(Screen.Timetable) },
        onEditSessionClick = { onNavigate(Screen.Timetable) },
        onMarkSessionDone = { session ->
          val next = if (session.status == "COMPLETED") "SCHEDULED" else "COMPLETED"
          viewModel.updateSessionStatus(session, next)
        },
        onAskAiTutor = { session ->
          viewModel.openTutorForSubject(session.subjectId, "Help me prepare notes and key concepts for ${session.topic}")
        },
        onToggleAlarm = { viewModel.toggleSessionAlarm(it) },
        onTestAlarm = { viewModel.triggerTestAlarm(it) },
        onSyncGoogleCalendar = { session ->
          actions.addToCalendar(session)
        }
      )
    }
  }
  val quickActions: @Composable () -> Unit = {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      QuickActionButton(
        icon = Icons.Default.Schedule,
        label = "Schedule",
        modifier = Modifier.weight(1f),
        onClick = onQuickAddSession
      )
      QuickActionButton(
        icon = Icons.Default.Book,
        label = "Topic",
        modifier = Modifier.weight(1f),
        onClick = onQuickAddTopic
      )
      QuickActionButton(
        icon = Icons.Default.Alarm,
        label = "Alarm",
        modifier = Modifier.weight(1f),
        onClick = { showQuickAlarmDialog = true }
      )
      QuickActionButton(
        icon = Icons.Default.Psychology,
        label = "Tutor",
        modifier = Modifier.weight(1f),
        onClick = { onNavigate(Screen.AiTutor) }
      )
    }
  }
  val hub: @Composable () -> Unit = {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Surface(
        modifier = Modifier
          .weight(1f)
          .height(60.dp)
          .clip(RoundedCornerShape(12.dp)).clickable { onNavigate(Screen.WeekendQuizzes) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Row(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = "Quiz",
              style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = if (topics.size == 1) "1 topic" else "${topics.size} topics",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }

      Surface(
        modifier = Modifier
          .weight(1f)
          .height(60.dp)
          .clip(RoundedCornerShape(12.dp)).clickable { onNavigate(Screen.AiChatImport) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Row(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = "Memory",
              style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = if (extractedConcepts.size == 1) "1 fact" else "${extractedConcepts.size} facts",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }
  }
  val stats: @Composable () -> Unit = {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp),
      shape = RoundedCornerShape(12.dp),
      // On the desktop the tiles stand on their own, not inside another card.
      color = com.example.ui.theme.pageBackground(MaterialTheme.colorScheme.surface),
      border = if (com.example.ui.theme.LocalGlass.current != null) null
        else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = if (com.example.ui.theme.LocalGlass.current != null) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(
          modifier = Modifier.weight(1f).statTile(raised = com.example.ui.theme.LocalGlass.current != null) { onNavigate(Screen.MyTopics) },
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = "${topics.size}",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
          )
          Text(
            text = statLabel("Studied"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        com.example.ui.theme.StatDivider()

        Column(
          modifier = Modifier.weight(1f).statTile(raised = com.example.ui.theme.LocalGlass.current != null) { onNavigate(Screen.WeekendQuizzes) },
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = if (attempts.isNotEmpty()) "$averageScore%" else "—",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
          )
          Text(
            text = statLabel("Avg Score"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        com.example.ui.theme.StatDivider()

        Column(
          modifier = Modifier.weight(1f).statTile(raised = com.example.ui.theme.LocalGlass.current != null) { onNavigate(Screen.Progress) },
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = "$masteredCount",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
          )
          Text(
            text = statLabel("Mastered"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
  }
  val todaySessions: @Composable () -> Unit = {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
    ) {
      SectionHeader(
        title = if (com.example.ui.theme.LocalGlass.current != null) "Schedule" else "Today",
        actionText = "All",
        onActionClick = { onNavigate(Screen.Timetable) }
      )

      if (sessions.isEmpty()) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
          Text(
            text = "Nothing scheduled today.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
          )
        }
      } else {
        Column(
          modifier = Modifier.padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          sessions.take(3).forEach { session ->
            SessionItemRow(session = session, onOpen = { editingSession = session }, onToggleStatus = {
              val next = if (session.status == "COMPLETED") "SCHEDULED" else "COMPLETED"
              viewModel.updateSessionStatus(session, next)
            })
          }
        }
      }
    }
  }
  var openTopic by remember { mutableStateOf<StudyTopic?>(null) }
  com.example.ui.orbit.SessionDialogHost(viewModel, editingSession?.let { com.example.ui.orbit.SessionDialog.Edit(it) }) { editingSession = null }
  openTopic?.let { t -> TopicDetailHost(viewModel, t) { openTopic = it } }
  val recentTopics: @Composable () -> Unit = {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
    ) {
      SectionHeader(
        title = "Recent",
        actionText = "Library",
        onActionClick = { onNavigate(Screen.MyTopics) }
      )

      if (topics.isEmpty()) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
          Text(
            text = "No topics recorded yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
          )
        }
      } else {
        Column(
          modifier = Modifier.padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          topics.take(3).forEach { topic ->
            RecentTopicRow(topic = topic, onClick = { openTopic = topic })
          }
        }
      }
    }
  }
  val deadlines: @Composable () -> Unit = {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
    ) {
      SectionHeader(
        title = "Deadlines",
        actionText = "Tasks",
        onActionClick = { onNavigate(Screen.Tasks) }
      )

      Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        pendingTasks.take(2).forEach { task ->
          DashboardTaskRow(task = task)
        }
      }
    }
  }

  if (LocalAppStyle.current == AppStyle.Glass) {
    // Desktop: the page header already shows the date and the streak, so the greeting is left out.
    Row(
      modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Column(
        modifier = Modifier.weight(1.35f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        com.example.ui.orbit.OrbitCard(
          viewModel = viewModel,
          date = com.example.ui.orbit.todayDate(),
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          title = "Today's orbit"
        )
        todaySessions()
        recentTopics()
      }
      Column(
        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        quickActions()
        whatNow()
        hub()
        stats()
        if (pendingTasks.isNotEmpty()) deadlines()
      }
    }
  } else {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = 24.dp)
    ) {
      item { greeting() }
      item { whatNow() }
      item { quickActions() }
      item { hub() }
      item { stats() }
      item { todaySessions() }
      item { recentTopics() }
      if (pendingTasks.isNotEmpty()) item { deadlines() }
    }
  }

  if (showQuickAlarmDialog) {
    QuickMobileAlarmDialog(
      onDismiss = { showQuickAlarmDialog = false },
      onConfirm = { subject, topic, delayMins, customTime ->
        viewModel.setQuickMobileAlarm(subject, topic, delayMins, customTime)
      }
    )
  }
}

@Composable
private fun SessionItemRow(
  session: TimetableSession,
  onOpen: () -> Unit,
  onToggleStatus: () -> Unit
) {
  val isDone = session.status == "COMPLETED"
  // The row opens the session; the circle on the right marks it done.
  com.example.ui.theme.ClickableSurface(
    onClick = onOpen,
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(
        modifier = Modifier.width(48.dp),
        verticalArrangement = Arrangement.Center
      ) {
        Text(
          text = session.startTime.ifBlank { "—" },
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.primary
        )
        if (session.endTime.isNotBlank()) {
          Text(
            text = session.endTime,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      Box(
        modifier = Modifier
          .padding(horizontal = 12.dp)
          .width(1.dp)
          .height(28.dp)
          .background(MaterialTheme.colorScheme.outlineVariant)
      )

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = session.topic,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
          color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        val loc = if (session.location.isNotBlank()) " · ${session.location}" else ""
        Text(
          text = "${session.subjectName}$loc",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = sessionWhen(session, isDone),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
      )
      Spacer(modifier = Modifier.width(6.dp))
      Box(
        modifier = Modifier
          .size(32.dp)
          .clip(CircleShape)
          .clickable(onClick = onToggleStatus),
        contentAlignment = Alignment.Center
      ) {
        if (isDone) {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Mark not done",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
          )
        } else {
          Box(
            modifier = Modifier
              .size(20.dp)
              .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
          )
        }
      }
    }
  }
}

@Composable
private fun RecentTopicRow(topic: StudyTopic, onClick: () -> Unit) {
  com.example.ui.theme.ClickableSurface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = topic.title,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = topic.subjectName,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      Spacer(modifier = Modifier.width(8.dp))
      MasteryBadge(level = topic.masteryLevel)
    }
  }
}

@Composable
private fun DashboardTaskRow(task: StudyTask) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = task.title,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = "${task.subjectName} · Due ${task.dueDate}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      val priorityColor = when (task.priority.uppercase()) {
        "HIGH" -> RoseError
        "MEDIUM" -> AmberAccent
        else -> CyanAccent
      }
      Box(
        modifier = Modifier
          .size(8.dp)
          .clip(CircleShape)
          .background(priorityColor)
      )
    }
  }
}

@Composable
private fun QuickActionButton(
  icon: ImageVector,
  label: String,
  modifier: Modifier = Modifier,
  onClick: () -> Unit
) {
  com.example.ui.theme.ClickableSurface(
    onClick = { onClick() },
    modifier = modifier.height(60.dp),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(vertical = 8.dp, horizontal = 2.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp)
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
      )
    }
  }
}

/** "Done", "Now", "In 40 min", "In 2 h" or "Earlier" for a session today. */
private fun sessionWhen(session: TimetableSession, isDone: Boolean): String {
  if (isDone) return "Done"
  val parts = session.startTime.split(":").mapNotNull { it.trim().take(2).toIntOrNull() }
  if (parts.size < 2) return ""
  val now = java.util.Calendar.getInstance()
  val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
  val diff = parts[0] * 60 + parts[1] - nowMin
  val endParts = session.endTime.split(":").mapNotNull { it.trim().take(2).toIntOrNull() }
  val endDiff = if (endParts.size == 2) endParts[0] * 60 + endParts[1] - nowMin else diff
  return when {
    diff <= 0 && endDiff > 0 -> "Now"
    diff <= 0 -> "Earlier"
    diff < 60 -> "In $diff min"
    else -> "In ${diff / 60} h"
  }
}
