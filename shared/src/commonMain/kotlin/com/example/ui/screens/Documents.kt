package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.platform.LocalPlatformActions
import com.example.platform.PlatformActions
import com.example.ui.theme.Button
import com.example.ui.theme.OutlinedButton
import com.example.ui.theme.dialogBackground
import com.example.ui.viewmodel.StudyPilotViewModel
import kotlinx.coroutines.launch

/**
 * "Upload document": pick a PDF, PowerPoint or Word file, choose its subject, and the AI reads it
 * into topics with study notes (knowledge for the tutor, material for quizzes).
 */
@Composable
fun UploadDocumentButton(viewModel: StudyPilotViewModel, presetSubject: String? = null, modifier: Modifier = Modifier, outlined: Boolean = false) {
  val actions = LocalPlatformActions.current
  val scope = rememberCoroutineScope()
  var picked by remember { mutableStateOf<PlatformActions.PickedFile?>(null) }
  val pick = actions.pickDocument
  val onClick: () -> Unit = {
    scope.launch {
      runCatching { pick?.invoke() }
        .onSuccess { if (it != null) picked = it }
        .onFailure { actions.showMessage(it.message ?: "Couldn't open that file") }
    }
  }
  if (pick == null) return
  if (outlined) OutlinedButton(onClick = onClick, modifier = modifier) { UploadLabel() }
  else Button(onClick = onClick, modifier = modifier) { UploadLabel() }

  picked?.let { file ->
    ChooseSubjectDialog(
      viewModel = viewModel,
      fileName = file.name,
      preset = presetSubject,
      onDismiss = { picked = null },
      onConfirm = { subject ->
        viewModel.uploadDocument(file.name, file.bytes, subject)
        picked = null
      }
    )
  }
}

@Composable
private fun UploadLabel() {
  Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
  Spacer(Modifier.width(6.dp))
  Text("Upload document", maxLines = 1)
}

/** Which subject an uploaded document belongs to: an existing one, General, or a new name. */
@Composable
private fun ChooseSubjectDialog(
  viewModel: StudyPilotViewModel,
  fileName: String,
  preset: String?,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit
) {
  val subjects by viewModel.subjects.collectAsState()
  val names = listOf("General") + subjects.map { it.name }.filter { it.isNotBlank() && !it.equals("General", true) }
  var chosen by remember { mutableStateOf(preset ?: "General") }
  var newName by remember { mutableStateOf("") }
  var creating by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = dialogBackground(MaterialTheme.colorScheme.surface),
    title = { Text("Add to which subject?", fontWeight = FontWeight.Bold) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(fileName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
          "The AI reads it into topics with study notes. The tutor uses them, and you can make a quiz from the subject or this file.",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Dropdown(
          label = "Subject",
          options = names.map { it to it } + (NEW_SUBJECT to "New subject…"),
          selected = if (creating) NEW_SUBJECT else chosen,
          onSelect = { key -> if (key == NEW_SUBJECT) creating = true else { creating = false; chosen = key; newName = "" } }
        )
        if (creating) {
          OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, label = { Text("New subject name") }, modifier = Modifier.fillMaxWidth())
        }
      }
    },
    confirmButton = { Button(onClick = { onConfirm(if (creating) newName.trim().ifBlank { chosen } else chosen) }, enabled = !creating || newName.isNotBlank()) { Text("Add") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

/** The loading screen while a quiz's questions are written: what it's for, how far, and a way out. */
@Composable
fun QuizPrepOverlay(viewModel: StudyPilotViewModel) {
  val prep by viewModel.quizPrep.collectAsState()
  val cloudFirst by viewModel.quizCloudFirst.collectAsState()
  val p = prep ?: return
  Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = dialogBackground(MaterialTheme.colorScheme.surface)),
      modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
    ) {
      Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Making your quiz", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Text(p.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (p.total > 0) {
          LinearProgressIndicator(progress = { p.done / p.total.toFloat() }, modifier = Modifier.fillMaxWidth())
          Text(
            "${p.done} of ${p.total} topics ready" + if (p.current.isNotBlank() && p.done < p.total) " · ${p.current}" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
          )
        } else {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          Text("Choosing topics…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
          if (cloudFirst) "Questions are written by your API key's model when it's set." else "Questions are written by the AI chosen in Settings.",
          style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
          TextButton(onClick = { viewModel.cancelQuizPrep() }) { Text("Cancel") }
        }
      }
    }
  }
}

/** Beside "import a link": add a lecture PDF, slides or a Word file to a subject's knowledge. */
@Composable
fun UploadDocumentCard(viewModel: StudyPilotViewModel) {
  if (LocalPlatformActions.current.pickDocument == null) return
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Upload a document", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
      Text(
        "A lecture PDF, slides (.pptx) or a Word file. Choose its subject; the AI reads it into topics with notes for the tutor, and you can make a quiz from it.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      UploadDocumentButton(viewModel)
    }
  }
}

private const val NEW_SUBJECT = "\u0000new"

/**
 * A dropdown list: shows the chosen option in a field and opens a menu of [options] (key to label).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun Dropdown(label: String, options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
  var open by remember { mutableStateOf(false) }
  androidx.compose.material3.ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
    OutlinedTextField(
      value = options.firstOrNull { it.first == selected }?.second ?: "",
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      label = { Text(label) },
      trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
      modifier = Modifier.fillMaxWidth().menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable)
    )
    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      options.forEach { (key, text) ->
        androidx.compose.material3.DropdownMenuItem(
          text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          onClick = { onSelect(key); open = false }
        )
      }
    }
  }
}
