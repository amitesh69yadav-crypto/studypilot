package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shared.resources.Res
import com.example.shared.resources.studypilot_logo
import com.example.ui.theme.GlassColors
import com.example.ui.theme.LocalGlass
import com.example.ui.theme.glass
import com.example.ui.viewmodel.Screen
import com.example.ui.viewmodel.StudyPilotViewModel
import org.jetbrains.compose.resources.painterResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The desktop layout: a frosted sidebar with the wordmark, the pages and a small streak card, and a
 * page header with a serif title, its one-line purpose and round glass buttons.
 */
@Composable
fun GlassShell(
  viewModel: StudyPilotViewModel,
  currentScreen: Screen,
  pendingTaskCount: Int,
  snackbarHostState: SnackbarHostState,
  content: @Composable () -> Unit
) {
  val glass = LocalGlass.current ?: return
  Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
      Sidebar(viewModel, currentScreen, pendingTaskCount, glass)
      // Header and page share one centred column, so their edges line up at any window width.
      Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        Column(
          Modifier
            .widthIn(max = 1240.dp)
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(start = 28.dp, end = 28.dp, top = 22.dp)
        ) {
          // Pages inset their content by 16dp; the header matches.
          Box(Modifier.padding(horizontal = 16.dp)) { PageHeader(viewModel, currentScreen, glass) }
          Spacer(Modifier.height(18.dp))
          Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
      }
    }
    SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
  }
}

private class NavEntry(val screen: Screen, val label: String, val icon: ImageVector)

private val mainPages = listOf(
  NavEntry(Screen.Dashboard, "Today", Icons.Default.WbSunny),
  NavEntry(Screen.Timetable, "Timetable", Icons.Default.Schedule),
  NavEntry(Screen.MyTopics, "Topics", Icons.Default.Book),
  NavEntry(Screen.WeekendQuizzes, "Quizzes", Icons.Default.AutoAwesome),
  NavEntry(Screen.Progress, "Progress", Icons.AutoMirrored.Filled.TrendingUp)
)

private val toolPages = listOf(
  NavEntry(Screen.AiTutor, "Tutor", Icons.Default.Psychology),
  NavEntry(Screen.AiChatImport, "Chats", Icons.Default.Forum),
  NavEntry(Screen.Tasks, "Tasks", Icons.AutoMirrored.Filled.Assignment),
  NavEntry(Screen.Settings, "Settings", Icons.Default.Settings)
)

@Composable
private fun Sidebar(viewModel: StudyPilotViewModel, current: Screen, pendingTasks: Int, glass: GlassColors) {
  val streak by viewModel.studyStreak.collectAsState()
  val display = MaterialTheme.typography.displaySmall.fontFamily
  Column(
    Modifier
      .width(224.dp)
      .fillMaxHeight()
      .background(glass.fill.copy(alpha = glass.fill.alpha * 0.6f))
      .padding(horizontal = 14.dp, vertical = 20.dp)
  ) {
    // Wordmark: the logo, the name in the serif, and a small tracked tagline.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
      Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF17253B))) {
        Image(painterResource(Res.drawable.studypilot_logo), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
      }
      Spacer(Modifier.width(10.dp))
      Column {
        Text("StudyPilot", fontFamily = display, fontSize = 22.sp, color = glass.text, letterSpacing = (-0.2).sp, lineHeight = 24.sp)
        Text(
          "PLAN · STUDY · RECALL",
          fontSize = 9.5.sp,
          fontWeight = FontWeight.SemiBold,
          letterSpacing = 1.4.sp,
          color = glass.muted,
          modifier = Modifier.padding(top = 3.dp)
        )
      }
    }

    Spacer(Modifier.height(26.dp))
    mainPages.forEach { NavItem(it, current == it.screen, glass) { viewModel.navigateTo(it.screen) } }
    Text(
      "TOOLS",
      fontSize = 10.5.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 1.5.sp,
      color = glass.muted,
      modifier = Modifier.padding(start = 14.dp, top = 20.dp, bottom = 6.dp)
    )
    toolPages.forEach { entry ->
      NavItem(entry, current == entry.screen, glass, badge = if (entry.screen == Screen.Tasks && pendingTasks > 0) pendingTasks.toString() else null) {
        viewModel.navigateTo(entry.screen)
      }
    }

    Spacer(Modifier.weight(1f))

    // A quiet card at the bottom: the study streak, in the serif.
    Column(
      Modifier
        .fillMaxWidth()
        .glass(RoundedCornerShape(20.dp), glass)
        .clickable { viewModel.navigateTo(Screen.Progress) }
        .padding(14.dp)
    ) {
      Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = glass.text, modifier = Modifier.size(18.dp))
      Spacer(Modifier.height(10.dp))
      Text("Study a little, every day.", fontFamily = display, fontSize = 16.sp, lineHeight = 20.sp, color = glass.text)
      Spacer(Modifier.height(6.dp))
      Text(
        if (streak > 0) "$streak-day streak" else "Start your streak today",
        fontSize = 12.sp,
        color = glass.muted
      )
      Spacer(Modifier.height(10.dp))
      Box(Modifier.width(28.dp).height(1.5.dp).background(glass.text.copy(alpha = 0.5f)))
    }
  }
}

@Composable
private fun NavItem(entry: NavEntry, selected: Boolean, glass: GlassColors, badge: String? = null, onClick: () -> Unit) {
  val shape = RoundedCornerShape(16.dp)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp)
      .height(42.dp)
      .then(if (selected) Modifier.glass(shape, glass, glass.selected) else Modifier.clip(shape))
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp)
  ) {
    Icon(entry.icon, contentDescription = null, tint = glass.text, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(12.dp))
    Text(entry.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = glass.text, modifier = Modifier.weight(1f))
    if (badge != null) {
      Box(
        Modifier.clip(CircleShape).background(glass.ink).padding(horizontal = 7.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(badge, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = glass.onInk)
      }
    }
  }
}

@Composable
private fun PageHeader(viewModel: StudyPilotViewModel, current: Screen, glass: GlassColors) {
  val user by viewModel.currentUser.collectAsState()
  val (title, subtitle) = pageTitle(current)
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.displaySmall, color = glass.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = glass.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      // Today's date, as a glass pill.
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .height(40.dp)
          .glass(CircleShape, glass)
          .padding(horizontal = 14.dp)
      ) {
        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = glass.text, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(Date()), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = glass.text)
      }
      GlassCircleButton(
        icon = if (glass.dark) Icons.Default.LightMode else Icons.Default.DarkMode,
        description = if (glass.dark) "Light mode" else "Dark mode",
        glass = glass
      ) { viewModel.setThemeMode(if (glass.dark) "light" else "dark") }
      Box(
        Modifier
          .size(40.dp)
          .glass(CircleShape, glass, if (current == Screen.Settings) glass.selected else glass.fill)
          .clickable { viewModel.navigateTo(Screen.Settings) },
        contentAlignment = Alignment.Center
      ) {
        Text(user.avatarInitials, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = glass.text)
      }
    }
  }
}

/** A round frosted icon button, like the ones along the top of the page. */
@Composable
fun GlassCircleButton(icon: ImageVector, description: String, glass: GlassColors, onClick: () -> Unit) {
  Box(
    Modifier
      .size(40.dp)
      .glass(CircleShape, glass)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Icon(icon, contentDescription = description, tint = glass.text, modifier = Modifier.size(18.dp))
  }
}

private fun pageTitle(screen: Screen): Pair<String, String> = when (screen) {
  Screen.Dashboard -> "Today" to "What's on now, what's next, and what you've learned lately."
  Screen.Timetable -> "Timetable" to "Classes, study blocks and alarms, day by day."
  Screen.MyTopics -> "Topics" to "What you've studied, filed under your subjects."
  Screen.WeekendQuizzes -> "Quizzes" to "Multiple choice from everything you've stored, new questions each time."
  Screen.Progress -> "Progress" to "What you've covered, how it's going, and what needs review."
  Screen.AiTutor -> "Tutor" to "Ask anything; answers draw on your own notes and chats."
  Screen.AiChatImport -> "Chats" to "AI conversations you've imported, and what they taught you."
  Screen.Tasks -> "Tasks" to "Assignments, exams and to-dos."
  Screen.Settings -> "Settings" to "Profile, appearance, AI, and your phone."
  Screen.ActiveQuiz -> "Quiz" to ""
  Screen.Login -> "Sign in" to ""
}
