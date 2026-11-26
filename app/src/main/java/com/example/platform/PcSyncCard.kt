package com.example.platform

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.sync.SyncClient
import com.example.ui.components.SettingsCard
import com.example.ui.components.timeAgo
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings > PC on the phone: pair with the desktop app by scanning its code, then sync. */
@Composable
fun PcSyncCard(client: SyncClient) {
  val state by client.state.collectAsState()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var manual by remember { mutableStateOf(false) }
  var address by remember { mutableStateOf("") }
  var code by remember { mutableStateOf("") }
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(15_000)
      now = System.currentTimeMillis()
    }
  }

  val status = when {
    state.syncing && !state.paired -> "Pairing..."
    state.paired && state.syncing -> "Syncing with ${state.pcName}..."
    state.paired -> "${state.pcName ?: "PC"}, synced ${timeAgo(state.lastSyncAt, now)}"
    else -> "Not paired"
  }

  SettingsCard(icon = Icons.Default.Computer, title = "PC", status = status) {
    Text(
      if (state.paired) {
        "Your timetable, topics, tasks, quizzes and tutor chats sync with the StudyPilot desktop app while both are on the same Wi-Fi."
      } else {
        "Use StudyPilot on your computer with the same data. Open the desktop app, go to Settings > Phone > Pair a phone, then scan the code."
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    state.error?.let {
      Spacer(Modifier.height(8.dp))
      Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(12.dp))

    if (state.paired) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { scope.launch { client.syncNow() } }, enabled = !state.syncing, shape = RoundedCornerShape(10.dp)) {
          if (state.syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Sync now")
        }
        OutlinedButton(onClick = { client.unpair() }, shape = RoundedCornerShape(10.dp)) { Text("Unpair") }
      }
    } else if (!manual) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = {
            scanPairingCode(
              context,
              onText = { text -> scope.launch { client.pairFromQr(text) } },
              onError = { manual = true }
            )
          },
          enabled = !state.syncing,
          shape = RoundedCornerShape(10.dp)
        ) { Text("Scan code") }
        TextButton(onClick = { manual = true }) { Text("Type it in") }
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = address,
          onValueChange = { address = it.trim() },
          label = { Text("PC address") },
          placeholder = { Text("192.168.1.60") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
          value = code,
          onValueChange = { code = it.filter(Char::isDigit).take(6) },
          label = { Text("Code") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = { scope.launch { if (client.pair(address, code)) manual = false } },
            enabled = address.isNotBlank() && code.length == 6 && !state.syncing,
            shape = RoundedCornerShape(10.dp)
          ) { Text("Pair") }
          TextButton(onClick = { manual = false }) { Text("Cancel") }
        }
      }
    }
  }
}

/** Google's code scanner: no camera permission needed, the scanning UI comes from Play services. */
private fun scanPairingCode(context: Context, onText: (String) -> Unit, onError: () -> Unit) {
  val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
  GmsBarcodeScanning.getClient(context, options).startScan()
    .addOnSuccessListener { barcode -> barcode.rawValue?.let(onText) }
    .addOnFailureListener {
      // Scanner module missing or no camera: fall back to typing the address and code.
      Log.w("PcSyncCard", "Code scanner unavailable", it)
      onError()
    }
}
