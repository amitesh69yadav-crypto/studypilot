package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Intent
import com.example.ui.components.ActiveAlarmAlertDialog
import com.example.ui.screens.LoginScreen
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.ActiveQuizScreen
import com.example.ui.screens.AiTutorScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ProgressScreen
import com.example.ui.screens.QuizScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TasksScreen
import com.example.ui.screens.TimetableScreen
import com.example.ui.screens.TopicsScreen
import com.example.ui.screens.AiChatImportScreen
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.StudyPilotTheme
import com.example.ui.viewmodel.Screen
import com.example.ui.viewmodel.StudyPilotViewModel
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

  private val viewModel: StudyPilotViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setupWindowFlagsForRingingAlarm()
    handleAlarmIntent(intent)
    setContent {
      StudyPilotTheme {
        StudyPilotApp(viewModel = viewModel)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleAlarmIntent(intent)
  }

  private fun setupWindowFlagsForRingingAlarm() {
    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
      } else {
        @Suppress("DEPRECATION")
        window.addFlags(
          android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
          android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
          android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
      }
    } catch (e: Exception) {
      // Ignore
    }
  }

  private fun handleAlarmIntent(intent: Intent?) {
    val ringingSessionId = intent?.getLongExtra("extra_ringing_session_id", 0L) ?: 0L
    if (ringingSessionId > 0) {
      viewModel.triggerSessionAlarmById(ringingSessionId)
    }
  }
}

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

  // Handle system back navigation to return to Dashboard if on a subscreen or Login
  BackHandler(enabled = currentScreen != Screen.Dashboard && currentScreen != Screen.ActiveQuiz) {
    viewModel.navigateTo(Screen.Dashboard)
  }

  // 5 Balanced primary destinations for the bottom navigation bar
  val navItems = listOf(
    Triple(Screen.Dashboard, "Dashboard", Icons.Default.Dashboard),
    Triple(Screen.Timetable, "Timetable", Icons.Default.Schedule),
    Triple(Screen.MyTopics, "Topics", Icons.Default.Book),
    Triple(Screen.WeekendQuizzes, "Quizzes", Icons.Default.AutoAwesome),
    Triple(Screen.Progress, "Analytics", Icons.AutoMirrored.Filled.TrendingUp)
  )

  val isSubScreen = currentScreen == Screen.AiTutor ||
    currentScreen == Screen.Tasks ||
    currentScreen == Screen.Settings ||
    currentScreen == Screen.AiChatImport

  val screenTitle = when (currentScreen) {
    Screen.Login -> "Authentication"
    Screen.Dashboard -> "StudyPilot AI"
    Screen.Timetable -> "Timetable & Schedule"
    Screen.MyTopics -> "My Topics Library"
    Screen.WeekendQuizzes -> "Weekend Quizzes"
    Screen.Progress -> "Academic Analytics"
    Screen.AiTutor -> "AI Study Tutor"
    Screen.Tasks -> "Tasks & Deadlines"
    Screen.Settings -> "Settings & Courses"
    Screen.ActiveQuiz -> "Interactive Quiz"
    Screen.AiChatImport -> "AI Chat Import & Memory"
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isWideScreen = maxWidth >= 600.dp

    Row(modifier = Modifier.fillMaxSize()) {
      // Left Navigation Rail for Laptop / Tablet / Foldable mode
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
                  painter = painterResource(id = R.drawable.studypilot_logo),
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
      if (currentScreen != Screen.ActiveQuiz && currentScreen != Screen.Login) {
        TopAppBar(
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
          colors = TopAppBarDefaults.topAppBarColors(
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
            // Symmetrical, evenly-spaced quick action icons for secondary features
            IconButton(
              onClick = { viewModel.navigateTo(Screen.AiChatImport) }
            ) {
              Box(
                modifier = Modifier
                  .size(36.dp)
                  .clip(CircleShape)
                  .background(if (currentScreen == Screen.AiChatImport) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.Chat,
                  contentDescription = "AI Chat Import",
                  tint = if (currentScreen == Screen.AiChatImport) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(20.dp)
                )
              }
            }

            IconButton(
              onClick = { viewModel.navigateTo(Screen.AiTutor) }
            ) {
              Box(
                modifier = Modifier
                  .size(36.dp)
                  .clip(CircleShape)
                  .background(if (currentScreen == Screen.AiTutor) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.Psychology,
                  contentDescription = "AI Tutor",
                  tint = if (currentScreen == Screen.AiTutor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(20.dp)
                )
              }
            }

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
                        containerColor = com.example.ui.theme.PaletteDeepSage,
                        contentColor = MaterialTheme.colorScheme.surface
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

            // Academic User Profile Avatar Button (Navigates to Profile, Settings & Account)
            IconButton(
              onClick = { viewModel.navigateTo(Screen.Settings) }
            ) {
              Box(
                modifier = Modifier
                  .size(36.dp)
                  .clip(CircleShape)
                  .background(if (currentScreen == Screen.Settings) MaterialTheme.colorScheme.primaryContainer else com.example.ui.theme.PaletteDeepSage),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = currentUser.avatarInitials,
                  fontWeight = FontWeight.ExtraBold,
                  color = if (currentScreen == Screen.Settings) MaterialTheme.colorScheme.primary else com.example.ui.theme.PaletteCreamIvory,
                  fontSize = 12.sp
                )
              }
            }
          }
        )
      }
    },
    bottomBar = {
      // Show bottom bar on mobile screens, hide on laptop (wide), active quiz, or login
      if (!isWideScreen && currentScreen != Screen.ActiveQuiz && currentScreen != Screen.Login) {
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
        Column(modifier = Modifier.fillMaxSize()) {
          // Persistent Floating/Top Alarm Ringing Banner: Always visible on ANY screen when an alarm is actively ringing!
          ringingAlarmSession?.let { ringingSession ->
            Surface(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.errorContainer,
              shadowElevation = 4.dp,
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
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "ALARM RINGING: ${ringingSession.subjectName}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
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
                    contentDescription = "Turn Off Alarm",
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Turn Off", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
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
                val trigger = quickAddSessionTrigger
                if (trigger) quickAddSessionTrigger = false
                TimetableScreen(viewModel = viewModel, showAddDialogOnInit = trigger)
              }
              Screen.MyTopics -> {
                val trigger = quickAddTopicTrigger
                if (trigger) quickAddTopicTrigger = false
                TopicsScreen(viewModel = viewModel, showAddDialogOnInit = trigger)
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
              Screen.AiChatImport -> {
                AiChatImportScreen(viewModel = viewModel)
              }
            }
          }
        }
      }
    }
  }
}

  // Global Active Alarm Ringing Dialog: triggers across the entire app
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

