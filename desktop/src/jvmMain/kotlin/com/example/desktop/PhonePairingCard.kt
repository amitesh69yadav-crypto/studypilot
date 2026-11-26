package com.example.desktop

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.sync.SyncHost
import com.example.ui.components.SettingsCard
import com.example.ui.components.timeAgo
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay

/** Settings > Phone on the PC: pair a phone by QR code and see paired phones. */
@Composable
fun PhonePairingCard(host: SyncHost, serverRunning: Boolean) {
  val code by host.pairingCode.collectAsState()
  val peers by host.peers.collectAsState()
  // Re-read the clock so "last synced" stays current.
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(15_000)
      now = System.currentTimeMillis()
    }
  }

  val status = when {
    !serverRunning -> "Not available: another copy of StudyPilot may be open"
    peers.isEmpty() -> "No phone paired"
    else -> peers.joinToString { "${it.name}, synced ${timeAgo(it.lastSyncAt, now)}" }
  }

  SettingsCard(icon = Icons.Default.PhoneAndroid, title = "Phone", status = status) {
    Text(
      "Pair your phone once while both are on the same Wi-Fi. Changes then sync both ways whenever the phone app is open.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    val current = code
    if (current == null) {
      Button(onClick = { host.startPairing() }, enabled = serverRunning, shape = RoundedCornerShape(10.dp)) {
        Text(if (peers.isEmpty()) "Pair a phone" else "Pair another phone")
      }
    } else {
      val addresses = remember { DesktopPlatform.lanAddresses() }
      if (addresses.isEmpty()) {
        Text("This PC isn't on a network. Connect it to the same Wi-Fi as your phone.", color = MaterialTheme.colorScheme.error)
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
          QrCode(host.pairingUri(addresses, current), Modifier.size(180.dp))
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("On your phone: Settings > PC > Scan code", fontWeight = FontWeight.SemiBold)
            Text("Or type these in:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Address  ${addresses.first()}", fontFamily = FontFamily.Monospace)
            Text("Code     $current", fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
              "If Windows asks, allow StudyPilot on private networks.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { host.stopPairing() }) { Text("Cancel") }
          }
        }
      }
    }

    if (peers.isNotEmpty()) {
      Spacer(Modifier.height(12.dp))
      peers.forEach { peer ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(peer.name, fontWeight = FontWeight.SemiBold)
            Text(
              "Last synced ${timeAgo(peer.lastSyncAt, now)}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          Spacer(Modifier.width(8.dp))
          OutlinedButton(onClick = { host.forget(peer.token) }, shape = RoundedCornerShape(10.dp)) { Text("Forget") }
        }
      }
    }
  }
}

/** Black-on-white QR code with a quiet zone, so phones read it in dark mode too. */
@Composable
private fun QrCode(text: String, modifier: Modifier) {
  val matrix = remember(text) {
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
  }
  Box(modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp)) {
    Canvas(Modifier.size(168.dp)) {
      val cell = size.minDimension / matrix.width
      for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
          if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
      }
    }
  }
}
