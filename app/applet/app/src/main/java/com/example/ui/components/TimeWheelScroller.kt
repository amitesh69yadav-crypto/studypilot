package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.PaletteCreamIvory
import com.example.ui.theme.PaletteDarkText
import com.example.ui.theme.PaletteDeepSage
import com.example.ui.theme.PaletteMediumSage
import com.example.ui.theme.PaletteMintFoam
import com.example.ui.theme.PalettePaleCeladon

@Composable
fun TimeScrollPickerField(
  label: String,
  timeValue: String,
  onTimeChange: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  var showPicker by remember { mutableStateOf(false) }

  Surface(
    shape = RoundedCornerShape(10.dp),
    color = PaletteMintFoam,
    border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
    modifier = modifier.clickable { showPicker = true }
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
      Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = PaletteDarkText
      )
      Spacer(modifier = Modifier.height(2.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = if (timeValue.isNotBlank()) timeValue else "Select Time",
          fontSize = 15.sp,
          fontWeight = FontWeight.ExtraBold,
          color = PaletteDarkText
        )
        Icon(
          imageVector = Icons.Default.Schedule,
          contentDescription = "Scroll Time Picker",
          tint = PaletteDeepSage,
          modifier = Modifier.size(18.dp)
        )
      }
    }
  }

  if (showPicker) {
    TimeWheelScrollerDialog(
      title = "Scroll to Select $label",
      initialTimeStr = timeValue,
      onDismiss = { showPicker = false },
      onConfirm = { newTime ->
        onTimeChange(newTime)
        showPicker = false
      }
    )
  }
}

@Composable
fun TimeWheelScrollerDialog(
  title: String,
  initialTimeStr: String,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit
) {
  var initH = 10
  var initM = 0
  var initPeriod = "AM"

  try {
    val cleaned = initialTimeStr.trim()
    if (cleaned.contains(":")) {
      val parts = cleaned.split(":")
      var h = parts[0].toIntOrNull() ?: 10
      val m = parts[1].take(2).toIntOrNull() ?: 0
      initM = m
      if (cleaned.uppercase().contains("PM") || h >= 12) {
        initPeriod = "PM"
        if (h > 12) h -= 12
      } else {
        initPeriod = "AM"
      }
      if (h == 0) h = 12
      initH = h
    }
  } catch (_: Exception) {}

  var selectedHour by remember { mutableStateOf(initH) }
  var selectedMinute by remember { mutableStateOf((initM / 5) * 5 % 60) }
  var selectedPeriod by remember { mutableStateOf(initPeriod) }

  val hoursList = (1..12).toList()
  val minutesList = listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Schedule,
          contentDescription = null,
          tint = PaletteDeepSage,
          modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = title,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = PaletteDarkText
        )
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Live selected time badge
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = PalettePaleCeladon,
          border = androidx.compose.foundation.BorderStroke(1.dp, PaletteDeepSage),
          modifier = Modifier.padding(vertical = 8.dp)
        ) {
          Text(
            text = "${String.format("%02d", selectedHour)}:${String.format("%02d", selectedMinute)} $selectedPeriod",
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            color = PaletteDarkText,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3 Vertical Scroll Wheels
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(PaletteMintFoam, RoundedCornerShape(12.dp))
            .padding(8.dp),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Hour Wheel
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
          ) {
            Text("HOUR", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PaletteDeepSage)
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              items(hoursList.size) { idx ->
                val h = hoursList[idx]
                val isSelected = h == selectedHour
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = if (isSelected) PaletteDeepSage else Color.Transparent,
                  modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(vertical = 2.dp)
                    .clickable { selectedHour = h }
                ) {
                  Text(
                    text = String.format("%02d", h),
                    fontSize = 16.sp,
                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                    color = if (isSelected) PaletteCreamIvory else PaletteDarkText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp)
                  )
                }
              }
            }
          }

          Text(":", fontSize = 20.sp, fontWeight = FontWeight.Black, color = PaletteDeepSage)

          // Minute Wheel
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
          ) {
            Text("MINUTE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PaletteDeepSage)
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              items(minutesList.size) { idx ->
                val m = minutesList[idx]
                val isSelected = m == selectedMinute
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = if (isSelected) PaletteDeepSage else Color.Transparent,
                  modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(vertical = 2.dp)
                    .clickable { selectedMinute = m }
                ) {
                  Text(
                    text = String.format("%02d", m),
                    fontSize = 16.sp,
                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                    color = if (isSelected) PaletteCreamIvory else PaletteDarkText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp)
                  )
                }
              }
            }
          }

          // AM / PM Column
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f)
          ) {
            Text("PERIOD", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PaletteDeepSage)
            Spacer(modifier = Modifier.height(8.dp))
            listOf("AM", "PM").forEach { p ->
              val isSelected = p == selectedPeriod
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) PaletteDeepSage else PalettePaleCeladon,
                modifier = Modifier
                  .fillMaxWidth(0.85f)
                  .padding(vertical = 4.dp)
                  .clickable { selectedPeriod = p }
              ) {
                Text(
                  text = p,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (isSelected) PaletteCreamIvory else PaletteDarkText,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.padding(vertical = 8.dp)
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          var h24 = selectedHour % 12
          if (selectedPeriod == "PM") h24 += 12
          val formatted = String.format("%02d:%02d", h24, selectedMinute)
          onConfirm(formatted)
        },
        colors = ButtonDefaults.buttonColors(
          containerColor = PaletteDeepSage,
          contentColor = PaletteCreamIvory
        ),
        shape = RoundedCornerShape(10.dp)
      ) {
        Text("Confirm Time", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", color = PaletteDarkText)
      }
    },
    containerColor = PaletteCreamIvory,
    shape = RoundedCornerShape(16.dp)
  )
}
