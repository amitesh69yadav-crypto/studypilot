package com.example.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings > Appearance: the painted backdrops, each light or dark. Scrolls sideways on a phone. */
@Composable
fun BackdropPicker(current: Backdrop, onPick: (Backdrop) -> Unit) {
  val glass = LocalGlass.current ?: return
  Spacer(Modifier.height(16.dp))
  Text("Backdrop", style = MaterialTheme.typography.labelLarge, color = glass.text)
  Text("Behind the glass. Picking a dark one switches to dark mode.", style = MaterialTheme.typography.bodySmall, color = glass.muted)
  Spacer(Modifier.height(10.dp))
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Backdrop.entries.forEach { option ->
      val selected = option == current
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onPick(option) }.padding(4.dp)
      ) {
        Box(
          Modifier
            .size(width = 92.dp, height = 56.dp)
            .glass(RoundedCornerShape(14.dp), glass, Color.Transparent),
          contentAlignment = Alignment.TopEnd
        ) {
          MeshBackground(option, Modifier.fillMaxSize())
          if (selected) {
            Box(
              Modifier.padding(6.dp).size(20.dp).clip(CircleShape).glass(CircleShape, glass, glass.selected),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Default.Check, contentDescription = "Selected", tint = glass.text, modifier = Modifier.size(13.dp))
            }
          }
        }
        Spacer(Modifier.height(6.dp))
        Text(
          option.label,
          fontSize = 12.sp,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
          color = if (selected) glass.text else glass.muted
        )
      }
    }
  }
}
