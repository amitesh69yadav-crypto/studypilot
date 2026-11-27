package com.example

import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.filled.EditNote
import com.example.ui.theme.AppStyle
import com.example.ui.theme.LocalAppStyle
import com.example.ui.GlassShell
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import com.example.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.platform.PlatformBackHandler
import com.example.shared.resources.Res
import com.example.shared.resources.studypilot_logo
import com.example.ui.components.ActiveAlarmAlertDialog
import com.example.ui.screens.ActiveQuizScreen
import com.example.ui.screens.AiChatImportScreen
import com.example.ui.screens.AiTutorScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.ProgressScreen
import com.example.ui.screens.QuizScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TasksScreen
import com.example.ui.screens.TimetableScreen
import com.example.ui.screens.TopicsScreen
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.StudyPilotTheme
import com.example.ui.viewmodel.Screen
import com.example.ui.viewmodel.StudyPilotViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyPilotApp(viewModel: StudyPilotViewModel) {
  val currentScreen by viewModel.currentScreen.collectAsState()
  val currentUser by viewModel.currentUser.collectAsState()
  val isAuthenticated by viewModel.isAuthenticated.collectAsState()
  val tasks by viewModel.tasks.collectAsState()
  val ringingAlarmSession by viewModel.ringingAlarmSession.collectAsState()
  val pendingTaskCount = tasks.count { !it.isCompleted }

  val snackbarHostState = remember { SnackbarHostState() }

  var quickAddTopicTrigger by remember { mutableStateOf(false) }
  var quickAddSessionTrigger by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    viewModel.snackbarMessage.collectLatest { msg ->
      snackbarHostState.showSnackbar(msg)
    }
  }

  // System back returns to the Dashboard from any other screen (the quiz has its own exit flow).
  PlatformBackHandler(enabled = currentScreen != Screen.Dashboard && currentScreen != Screen.ActiveQuiz) {
    viewModel.navigateTo(Screen.Dashboard)
  }

  val navItems = listOf(
    // One outlined set, so every icon has the same stroke and visual size.
    Triple(Screen.Dashboard, "Dashboard", Icons.Outlined.Dashboard),
    Triple(Screen.Timetable, "Timetable", Icons.Outlined.Schedule),
    Triple(Screen.Notes, "Notes", Icons.Outlined.EditNote),
    Triple(Screen.MyTopics, "Topics", Icons.Outlined.Book),
    Triple(Screen.WeekendQuizzes, "Quizzes", Icons.Outlined.AutoAwesome),
    Triple(Screen.Progress, "Progress", Icons.AutoMirrored.Outlined.TrendingUp)
  )

  val isSubScreen = currentScreen == Screen.AiTutor ||
    currentScreen == Screen.Tasks ||
    currentScreen == Screen.Settings ||
    currentScreen == Screen.AiChatImport

  val screenTitle = when (currentScreen) {
    Screen.Login -> "Sign in"
    Screen.Dashboard -> "StudyPilot"
    Screen.Timetable -> "Timetable"
    Screen.MyTopics -> "Topics"
    Screen.WeekendQuizzes -> "Quizzes"
    Screen.Progress -> "Progress"
    Screen.AiTutor -> "AI Tutor"
    Screen.Tasks -> "Tasks"
    Screen.Settings -> "Settings"
    Screen.ActiveQuiz -> "Quiz"
    Screen.AiChatImport -> "Chats"
    Screen.Notes -> "Notes"
  }

  // The alarm banner and the current screen; the same in every layout.
  val screenBody: @Composable () -> Unit = {
    Column(modifier = Modifier.fillMaxSize()) {
      // Banner shown on every screen while an alarm rings.
      ringingAlarmSession?.let { ringingSession ->
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.errorContainer,
          shadowElevation = 0.dp,
          border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.NotificationsActive,
              contentDescription = "Alarm Ringing",
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Alarm: ${ringingSession.subjectName}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                text = "${ringingSession.topic} (${ringingSession.startTime})",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = { viewModel.dismissAlarm() },
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
              ),
              shape = RoundedCornerShape(8.dp),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              modifier = Modifier.heightIn(min = 40.dp)
            ) {
              Icon(
                imageVector = Icons.Default.AlarmOff,
                contentDescription = "Stop alarm",
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text("Stop", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
          }
        }
      }

      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
      ) {
        when (currentScreen) {
          Screen.Login -> {
            LoginScreen(
              viewModel = viewModel,
              onLoginSuccess = { viewModel.navigateTo(Screen.Dashboard) }
            )
          }
          Screen.Dashboard -> {
            DashboardScreen(
              viewModel = viewModel,
              onNavigate = { screen -> viewModel.navigateTo(screen) },
              onQuickAddTopic = {
                quickAddTopicTrigger = true
                viewModel.navigateTo(Screen.MyTopics)
              },
              onQuickAddSession = {
                quickAddSessionTrigger = true
                viewModel.navigateTo(Screen.Timetable)
              }
            )
          }
          Screen.Timetable -> {
            val openAddDialog = quickAddSessionTrigger
            LaunchedEffect(Unit) { quickAddSessionTrigger = false }
            if (LocalAppStyle.current == AppStyle.Glass) {
              com.example.ui.orbit.DesktopTimetable(viewModel = viewModel, showAddDialogOnInit = openAddDialog)
            } else {
              TimetableScreen(viewModel = viewModel, showAddDialogOnInit = openAddDialog)
            }
          }
          Screen.MyTopics -> {
            val openAddDialog = quickAddTopicTrigger
            LaunchedEffect(Unit) { quickAddTopicTrigger = false }
            TopicsScreen(viewModel = viewModel, showAddDialogOnInit = openAddDialog)
          }
          Screen.WeekendQuizzes -> {
            QuizScreen(viewModel = viewModel)
          }
          Screen.ActiveQuiz -> {
            ActiveQuizScreen(viewModel = viewModel)
          }
          Screen.AiTutor -> {
            AiTutorScreen(viewModel = viewModel)
          }
          Screen.Progress -> {
            ProgressScreen(viewModel = viewModel)
          }
          Screen.Tasks -> {
            TasksScreen(viewModel = viewModel)
          }
          Screen.Settings -> {
            SettingsScreen(viewModel = viewModel)
          }
          Screen.Notes -> {
            com.example.ui.screens.NotesScreen(viewModel = viewModel)
          }
          Screen.AiChatImport -> {
            AiChatImportScreen(viewModel = viewModel)
          }
        }
      }
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isWideScreen = maxWidth >= 600.dp

    // Desktop: the glass layout with a sidebar and a page header.
    if (LocalAppStyle.current == AppStyle.Glass && isWideScreen && currentScreen != Screen.Login && currentScreen != Screen.ActiveQuiz) {
      GlassShell(
        viewModel = viewModel,
        currentScreen = currentScreen,
        pendingTaskCount = pendingTaskCount,
        snackbarHostState = snackbarHostState,
        content = screenBody
      )
      return@BoxWithConstraints
    }

    Row(modifier = Modifier.fillMaxSize()) {
      // Navigation rail on tablets and foldables, bottom bar on phones.
      if (isWideScreen && currentScreen != Screen.ActiveQuiz && currentScreen != Screen.Login) {
        NavigationRail(
          containerColor = MaterialTheme.colorScheme.surface,
          contentColor = MaterialTheme.colorScheme.primary,
          header = {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
            ) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF17253B),
                modifier = Modifier
                  .size(42.dp)
                  .testTag("nav_rail_logo")
              ) {
                Image(
                  painter = painterResource(Res.drawable.studypilot_logo),
                  contentDescription = "StudyPilot Logo",
                  contentScale = ContentScale.Fit,
                  modifier = Modifier.fillMaxSize()
                )
              }
            }
          }
        ) {
          navItems.forEach { (screen, label, icon) ->
            val isSelected = currentScreen == screen
            NavigationRailItem(
              selected = isSelected,
              onClick = { viewModel.navigateTo(screen) },
              icon = {
                Icon(
                  imageVector = icon,
                  contentDescription = label
                )
              },
              label = {
                Text(
                  text = label,
                  fontSize = 11.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              },
              colors = NavigationRailItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer
              )
            )
          }
        }
      }

      Scaffold(
        modifier = Modifier.weight(1f),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
      if (LocalAppStyle.current == AppStyle.Glass) {
        // Phone, glass style: the serif page title and round glass buttons over the backdrop.
        if (currentScreen != Screen.ActiveQuiz && currentScreen != Screen.Login) {
          com.example.ui.GlassPhoneTopBar(viewModel, currentScreen, showBack = isSubScreen, pendingTaskCount = pendingTaskCount)
        }
      } else if (currentScreen != Screen.ActiveQuiz && currentScreen != Screen.Login) {
        CenterAlignedTopAppBar(
          title = {
            Text(
              text = screenTitle,
              style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp
              ),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          },
          colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
          ),
          navigationIcon = {
            if (isSubScreen) {
              IconButton(onClick = { viewModel.navigateTo(Screen.Dashboard) }) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                  contentDescription = "Back to Dashboard",
                  tint = MaterialTheme.colorScheme.onSurface
                )
              }
            }
          },
          actions = {
            IconButton(
              onClick = { viewModel.navigateTo(Screen.Tasks) }
            ) {
              Box(
                modifier = Modifier
                  .size(36.dp)
                  .clip(CircleShape)
                  .background(if (currentScreen == Screen.Tasks) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                contentAlignment = Alignment.Center
              ) {
                if (pendingTaskCount > 0 && currentScreen != Screen.Tasks) {
                  BadgedBox(
                    badge = {
                      Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                      ) {
                        Text(pendingTaskCount.toString(), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                      }
                    }
                  ) {
                    Icon(
                      imageVector = Icons.AutoMirrored.Filled.Assignment,
                      contentDescription = "Tasks",
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(20.dp)
                    )
                  }
                } else {
                  Icon(
                    imageVector = Icons.AutoMirrored.Filled.Assignment,
                    contentDescription = "Tasks",
                    tint = if (currentScreen == Screen.Tasks) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                  )
                }
              }
            }

            IconButton(
              onClick = { viewModel.navigateTo(Screen.Settings) }
            ) {
              Surface(
                shape = CircleShape,
                color = if (currentScreen == Screen.Settings) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (currentScreen == Screen.Settings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(34.dp)
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Text(
                    text = currentUser.avatarInitials,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp
                  )
                }
              }
            }
          }
        )
      }
    },
    bottomBar = {
      if (LocalAppStyle.current == AppStyle.Glass) {
        if (!isWideScreen && currentScreen != Screen.ActiveQuiz && currentScreen != Screen.Login) {
          com.example.ui.GlassPhoneNavBar(
            items = navItems.map { (screen, label, icon) -> Triple(screen, label, icon) },
            current = currentScreen,
            onSelect = { viewModel.navigateTo(it) }
          )
        }
      } else if (!isWideScreen && currentScreen != Screen.ActiveQuiz && currentScreen != Screen.Login) {
        NavigationBar(
          containerColor = MaterialTheme.colorScheme.surface,
          contentColor = MaterialTheme.colorScheme.primary,
          tonalElevation = 4.dp
        ) {
          navItems.forEach { (screen, label, icon) ->
            val isSelected = currentScreen == screen
            NavigationBarItem(
              selected = isSelected,
              onClick = { viewModel.navigateTo(screen) },
              icon = {
                Icon(
                  imageVector = icon,
                  contentDescription = label
                )
              },
              label = {
                Text(
                  text = label,
                  fontSize = 11.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              },
              colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer
              )
            )
          }
        }
      }
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentAlignment = Alignment.TopCenter
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .widthIn(max = 860.dp),
        contentAlignment = Alignment.TopCenter
      ) {
        screenBody()
      }
    }
  }
}

  ringingAlarmSession?.let { ringingSession ->
    ActiveAlarmAlertDialog(
      session = ringingSession,
      onDismiss = { viewModel.dismissAlarm() },
      onSnooze = { viewModel.snoozeAlarm(ringingSession, 5) },
      onStartSession = {
        viewModel.dismissAlarm()
        viewModel.openTutorForSubject(ringingSession.subjectId, "I'm starting my scheduled session on ${ringingSession.topic}.")
        viewModel.navigateTo(Screen.AiTutor)
      }
    )
  }
}
}

