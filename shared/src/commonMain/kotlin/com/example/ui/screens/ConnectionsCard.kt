package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.platform.LocalPlatformActions
import com.example.ui.theme.Button
import com.example.ui.theme.OutlinedButton
import com.example.ui.viewmodel.StudyPilotViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings > Connections: calendar feeds, Canvas, Moodle and Google Classroom. Imported classes,
 * subjects, topics and tasks sync to the paired device like anything else; the links and tokens
 * stay on the device they were entered on.
 */
@Composable
fun ConnectionsCard(viewModel: StudyPilotViewModel) {
  val c by viewModel.connections.collectAsState()
  val docs by viewModel.courseDocuments.collectAsState()
  if (!viewModel.connectsHere) {
    PhoneConnectionsNote(docs)
    return
  }
  val actions = LocalPlatformActions.current
  val scope = rememberCoroutineScope()
  var feed by remember { mutableStateOf("") }
  var openLms by remember { mutableStateOf<String?>(null) }

  Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
          Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text("Connections", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
          Text(
            if (c.lastImportAt > 0) "Last imported ${SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(c.lastImportAt))}"
            else "Bring in your timetable, courses, syllabus and deadlines",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        if (c.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else if (c.hasAny) TextButton(onClick = { viewModel.importConnections() }) { Text("Import now") }
      }

      // Calendar feeds
      Section("Calendar feed", "Your university timetable or any calendar's subscribe link (TimeEdit, Canvas, Moodle, Google or Outlook calendar). Classes go into Timetable without alarms; exams and deadlines become tasks.")
      c.calendarFeeds.forEach { url ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
          IconButton(onClick = { viewModel.removeCalendarFeed(url) }) { Icon(Icons.Default.Close, contentDescription = "Remove feed", modifier = Modifier.size(18.dp)) }
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = feed, onValueChange = { feed = it }, singleLine = true,
          placeholder = { Text("https://… .ics or webcal://…") },
          modifier = Modifier.weight(1f)
        )
        Button(onClick = { viewModel.addCalendarFeed(feed); feed = "" }, enabled = feed.isNotBlank() && !c.busy) { Text("Add") }
      }

      HorizontalDivider()
      LmsRow(
        title = "Canvas",
        help = "Canvas address (e.g. canvas.youruni.edu) and an access token from Account > Settings > New access token.",
        connected = c.canvasConnected,
        savedUrl = c.canvasUrl,
        open = openLms == "canvas",
        onOpen = { openLms = if (openLms == "canvas") null else "canvas" },
        onSave = { url, token -> viewModel.setLms("canvas", url, token); openLms = null },
        onDisconnect = { viewModel.setLms("canvas", c.canvasUrl, "") }
      )
      HorizontalDivider()
      LmsRow(
        title = "Moodle",
        help = "Moodle address and your key from Profile > Preferences > Security keys (Moodle mobile web service).",
        connected = c.moodleConnected,
        savedUrl = c.moodleUrl,
        open = openLms == "moodle",
        onOpen = { openLms = if (openLms == "moodle") null else "moodle" },
        onSave = { url, token -> viewModel.setLms("moodle", url, token); openLms = null },
        onDisconnect = { viewModel.setLms("moodle", c.moodleUrl, "") }
      )
      c.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
      HorizontalDivider()
      GoogleConnectionRow(viewModel, c.busy)

      if (c.results.isNotEmpty()) {
        HorizontalDivider()
        c.results.forEach { (source, result) ->
          Text(
            "$source: $result",
            style = MaterialTheme.typography.bodySmall,
            color = if (result.startsWith("Couldn't")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
  }
}

@Composable
private fun Section(title: String, help: String) {
  Column {
    Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
    Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun LmsRow(
  title: String,
  help: String,
  connected: Boolean,
  savedUrl: String,
  open: Boolean,
  onOpen: () -> Unit,
  onSave: (String, String) -> Unit,
  onDisconnect: () -> Unit
) {
  var url by remember(savedUrl) { mutableStateOf(savedUrl) }
  var token by remember { mutableStateOf("") }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Text(
          if (connected) "Connected · ${savedUrl.ifBlank { "saved" }}" else "Courses, syllabus, pages and assignments.",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1, overflow = TextOverflow.Ellipsis
        )
      }
      if (connected) TextButton(onClick = onDisconnect) { Text("Disconnect") }
      else OutlinedButton(onClick = onOpen) { Text(if (open) "Cancel" else "Connect") }
    }
    if (open && !connected) {
      Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(
        value = token, onValueChange = { token = it }, singleLine = true, label = { Text("Token") },
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
      )
      Button(onClick = { onSave(url, token); token = "" }, enabled = url.isNotBlank() && token.isNotBlank()) { Text("Connect and import") }
      Spacer(Modifier.height(2.dp))
    }
  }
}

/** On the phone: connections live on the PC; this says what has come over from it. */
@Composable
private fun PhoneConnectionsNote(docs: List<com.example.data.model.CourseDocument>) {
  Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
      }
      Spacer(Modifier.width(12.dp))
      Column {
        Text("Connections", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
          "Canvas, Moodle, calendars and Google are connected on your PC. Their subjects, syllabus, timetable and deadlines come here through sync.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

/** Google Classroom is imported through the Google account signed in under Storage. */
@Composable
internal fun GoogleConnectionRow(viewModel: StudyPilotViewModel, busy: Boolean) {
  val email by viewModel.googleEmail.collectAsState()
  val c by viewModel.connections.collectAsState()
  Column {
    Text("Google Classroom", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
    Text(
      when {
        email == null -> "Sign in with Google under Storage below, then turn Classroom on there."
        c.classroomEnabled -> "Importing through $email."
        else -> "Turn it on under Storage below ($email)."
      },
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}
