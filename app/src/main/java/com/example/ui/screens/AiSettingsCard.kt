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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.ai.AiSource
import com.example.data.ai.llm.DownloadState
import com.example.data.ai.llm.PhoneModel
import com.example.data.ai.llm.PhoneModels
import com.example.ui.theme.PaletteDeepSage
import com.example.ui.viewmodel.StudyPilotViewModel

/** Settings for where AI answers come from: a model on the phone, Ollama on a PC, or built-in answers. */
@Composable
fun AiSettingsCard(viewModel: StudyPilotViewModel) {
  val source by viewModel.aiSource.collectAsState()
  val activeLabel by viewModel.activeAiLabel.collectAsState()

  LaunchedEffect(Unit) { viewModel.refreshActiveAi() }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp)
      .testTag("ai_settings_card"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Column(modifier = Modifier.padding(18.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center
        ) {
          Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
          Text("AI", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
          Text("Answering with: $activeLabel", style = MaterialTheme.typography.bodySmall, color = PaletteDeepSage, fontWeight = FontWeight.SemiBold)
        }
      }

      Spacer(Modifier.height(12.dp))
      Text("Use", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
      AiSource.entries.forEach { option ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        ) {
          RadioButton(selected = source == option, onClick = { viewModel.setAiSource(option) })
          Column(Modifier.weight(1f)) {
            Text(option.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }

      HorizontalDivider(Modifier.padding(vertical = 14.dp))
      PhoneModelSection(viewModel)

      HorizontalDivider(Modifier.padding(vertical = 14.dp))
      PcSection(viewModel)
    }
  }
}

@Composable
private fun PhoneModelSection(viewModel: StudyPilotViewModel) {
  val download by viewModel.phoneDownload.collectAsState()
  val installed by viewModel.phoneInstalled.collectAsState()
  val selected by viewModel.phoneSelected.collectAsState()
  val ram = remember { viewModel.deviceRamGb() }

  SectionTitle(Icons.Default.Smartphone, "On this phone", "Works offline. Downloaded once over Wi-Fi.")
  if (ram > 0) {
    Text("This phone has about $ram GB of RAM.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Spacer(Modifier.height(8.dp))

  PhoneModel.entries.forEach { model ->
    val isInstalled = model in installed
    val running = (download as? DownloadState.Running)?.takeIf { it.model == model }
    val failed = (download as? DownloadState.Failed)?.takeIf { it.model == model }
    val tooBig = ram in 1 until model.minRamGb

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        .padding(12.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(model.displayName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
          Text(
            "${PhoneModels.formatSize(model.sizeBytes)} · ${model.summary}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          if (tooBig) {
            Text("May be too large for this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          }
        }
        if (isInstalled) {
          FilterChip(
            selected = selected == model,
            onClick = { viewModel.selectPhoneModel(model) },
            label = { Text(if (selected == model) "In use" else "Use") },
            colors = FilterChipDefaults.filterChipColors()
          )
        }
      }

      when {
        running != null -> {
          Spacer(Modifier.height(8.dp))
          LinearProgressIndicator(progress = { running.fraction }, modifier = Modifier.fillMaxWidth())
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              "${PhoneModels.formatSize(running.bytes)} of ${PhoneModels.formatSize(model.sizeBytes)}. Keep the app open.",
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { viewModel.cancelPhoneDownload() }) { Text("Pause") }
          }
        }
        isInstalled -> {
          TextButton(onClick = { viewModel.deletePhoneModel(model) }) { Text("Delete") }
        }
        else -> {
          failed?.let {
            Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          }
          Spacer(Modifier.height(6.dp))
          OutlinedButton(
            onClick = { viewModel.downloadPhoneModel(model) },
            enabled = download !is DownloadState.Running
          ) { Text(if (failed != null) "Resume download" else "Download") }
        }
      }
    }
  }
}

@Composable
private fun PcSection(viewModel: StudyPilotViewModel) {
  val pcUrl by viewModel.pcUrl.collectAsState()
  val pcModel by viewModel.pcModel.collectAsState()
  val status by viewModel.pcStatus.collectAsState()
  val models by viewModel.pcModels.collectAsState()
  val checking by viewModel.isCheckingPc.collectAsState()

  var urlInput by remember(pcUrl) { mutableStateOf(pcUrl) }
  var modelInput by remember(pcModel) { mutableStateOf(pcModel) }

  LaunchedEffect(pcUrl) { if (pcUrl.isNotBlank()) viewModel.checkPc() }

  SectionTitle(Icons.Default.Computer, "On your PC", "Much smarter answers when the phone is on the same Wi-Fi as your PC.")
  Text(
    "Install Ollama on the PC, run \"ollama pull qwen3:8b\", and start it with OLLAMA_HOST=0.0.0.0 so the phone can reach it.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
  )
  Spacer(Modifier.height(8.dp))
  OutlinedTextField(
    value = urlInput,
    onValueChange = { urlInput = it },
    label = { Text("PC address") },
    placeholder = { Text("192.168.1.60") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth()
  )
  Spacer(Modifier.height(6.dp))
  OutlinedTextField(
    value = modelInput,
    onValueChange = { modelInput = it },
    label = { Text("Model (blank = first installed)") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth()
  )
  if (models.isNotEmpty()) {
    Text("On the PC: ${models.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Spacer(Modifier.height(8.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Button(
      onClick = { viewModel.setPcConfig(urlInput, modelInput) },
      enabled = !checking,
      colors = ButtonDefaults.buttonColors(containerColor = PaletteDeepSage)
    ) { Text("Save and test") }
    OutlinedButton(onClick = { viewModel.findPc() }, enabled = !checking) { Text("Find") }
    if (checking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
  }
  status?.let {
    Spacer(Modifier.height(6.dp))
    Text(it, style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun SectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, contentDescription = null, tint = PaletteDeepSage, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
  }
  Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
