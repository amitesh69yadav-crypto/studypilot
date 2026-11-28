package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.platform.LocalPlatformActions
import com.example.ui.theme.Button
import com.example.ui.theme.OutlinedButton
import com.example.ui.viewmodel.StudyPilotViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings > Storage: keep the app's data only on the phone and PC, or also in the student's Google
 * Drive (data backup plus the course files, in a folder per subject). Managed on the PC.
 */
@Composable
fun StorageCard(viewModel: StudyPilotViewModel) {
  Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
          Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
          Text("Storage", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
          Text("Where your study data and course files are kept", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      if (!viewModel.canUseGoogle) {
        Text(
          "Storage is chosen on your PC. Your data is always on this phone and synced with the PC; if the PC backs up to Google Drive, what you do here is included.",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      } else {
        PcStorage(viewModel)
      }
    }
  }
}

@Composable
private fun PcStorage(viewModel: StudyPilotViewModel) {
  val mode by viewModel.storageMode.collectAsState()
  val email by viewModel.googleEmail.collectAsState()
  val last by viewModel.lastDriveBackup.collectAsState()
  val c by viewModel.connections.collectAsState()
  val actions = LocalPlatformActions.current

  Option(
    selected = mode == "device",
    title = "On your devices",
    text = "Kept on this PC and your phone, synced over Wi-Fi. Nothing goes online.",
    onClick = { viewModel.setStorageMode("device") }
  )
  Option(
    selected = mode == "drive",
    title = "Google Drive",
    text = "Also kept in a StudyPilot folder in your Google Drive: a backup of all your data, and the course files in a folder per subject. The app only sees files it created there.",
    onClick = { viewModel.setStorageMode("drive") }
  )

  HorizontalDivider()
  if (email != null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Google", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Text(
          "Signed in as $email" + if (last > 0) " · last backup ${SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(last))}" else "",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      TextButton(onClick = { viewModel.signOutGoogle() }) { Text("Sign out") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (mode == "drive") Button(onClick = { viewModel.backupToDrive() }, enabled = !c.busy) { Text("Back up now") }
      OutlinedButton(onClick = { viewModel.restoreFromDrive() }, enabled = !c.busy) { Text("Restore from Drive") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Google Classroom", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Text("Import your classes, coursework, materials and announcements (read-only).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      androidx.compose.material3.Switch(checked = c.classroomEnabled, onCheckedChange = { viewModel.setClassroomEnabled(it) })
    }
  } else {
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    Text("Google", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
    Text(
      "For Google Drive and Classroom, sign in once with your own Google Cloud project's desktop sign-in client: " +
        "in Google Cloud Console turn on the Drive and Classroom APIs, add yourself as a test user on the OAuth consent screen, " +
        "and create an OAuth client of type \"Desktop app\". Paste its ID and secret here.",
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    TextButton(onClick = { actions.openUrl("https://console.cloud.google.com/apis/credentials") }) { Text("Open Google Cloud Console") }
    OutlinedTextField(value = clientId, onValueChange = { clientId = it }, singleLine = true, label = { Text("Client ID") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
      value = secret, onValueChange = { secret = it }, singleLine = true, label = { Text("Client secret") },
      visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = { viewModel.signInGoogle(clientId, secret) }, enabled = clientId.isNotBlank() && secret.isNotBlank() && !c.busy) { Text("Sign in with Google") }
  }
  c.results["Google Drive"]?.let { Text("Google Drive: $it", style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Couldn't")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
  if (c.busy && c.progress != null) Text(c.progress!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Option(selected: Boolean, title: String, text: String, onClick: () -> Unit) {
  Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
    RadioButton(selected = selected, onClick = onClick)
    Column(Modifier.padding(top = 10.dp)) {
      Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
      Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
