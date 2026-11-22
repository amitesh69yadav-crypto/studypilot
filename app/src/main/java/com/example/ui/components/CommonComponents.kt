package com.example.ui.components
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.PaletteCreamIvory
import com.example.ui.theme.PaletteDarkText
import com.example.ui.theme.PaletteDeepSage
import com.example.ui.theme.PaletteMediumSage
import com.example.ui.theme.PalettePaleCeladon
import com.example.ui.theme.PaletteMintFoam
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeUp

@Composable
fun AcademicHeader(
  title: String,
  subtitle: String? = null,
  modifier: Modifier = Modifier,
  action: @Composable (() -> Unit)? = null
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium.copy(
          fontWeight = FontWeight.Bold,
          letterSpacing = (-0.5).sp
        ),
        color = MaterialTheme.colorScheme.onBackground
      )
      if (!subtitle.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
    if (action != null) {
      Spacer(modifier = Modifier.width(12.dp))
      Box(contentAlignment = Alignment.CenterEnd) {
        action()
      }
    }
  }
}

@Composable
fun SectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  actionText: String? = null,
  onActionClick: (() -> Unit)? = null
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onBackground,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .weight(1f)
        .padding(end = 8.dp)
    )
    if (!actionText.isNullOrBlank() && onActionClick != null) {
      Text(
        text = actionText,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .clickable { onActionClick() }
          .padding(horizontal = 6.dp, vertical = 2.dp)
      )
    }
  }
}

@Composable
fun StatCard(
  title: String,
  value: String,
  subtitle: String? = null,
  icon: ImageVector,
  iconTint: Color = MaterialTheme.colorScheme.primary,
  modifier: Modifier = Modifier
) {
  Card(
    modifier = modifier.defaultMinSize(minHeight = 110.dp),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .background(iconTint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = title,
          tint = iconTint,
          modifier = Modifier.size(20.dp)
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge.copy(
          fontWeight = FontWeight.Bold
        ),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
      )
      Text(
        text = title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
      )
      if (!subtitle.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
          color = iconTint,
          maxLines = 1,
          overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
      } else {
        Spacer(modifier = Modifier.height(14.dp))
      }
    }
  }
}

@Composable
fun MasteryBadge(
  level: String,
  modifier: Modifier = Modifier
) {
  val (bgColor, textColor, label) = when (level.uppercase()) {
    "MASTERED" -> Triple(EmeraldSuccess.copy(alpha = 0.15f), EmeraldSuccess, "Mastered")
    "FAMILIAR" -> Triple(CyanAccent.copy(alpha = 0.15f), CyanAccent, "Familiar")
    "STRUGGLING" -> Triple(RoseError.copy(alpha = 0.15f), RoseError, "Needs Review")
    else -> Triple(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f), MaterialTheme.colorScheme.onSurfaceVariant, "Untested")
  }

  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(20.dp),
    color = bgColor
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
      color = textColor,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
  }
}

@Composable
fun EmptyState(
  icon: ImageVector,
  title: String,
  description: String,
  actionButton: @Composable (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp, vertical = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Box(
      modifier = Modifier
        .size(64.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = icon,
        contentDescription = title,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(32.dp)
      )
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = description,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp)
    )
    if (actionButton != null) {
      Spacer(modifier = Modifier.height(16.dp))
      actionButton()
    }
  }
}

@Composable
fun MobileClockAlarmCard(
  activeAlarmsCount: Int,
  onQuickAlarmClick: () -> Unit,
  onTestAlarmSoundClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  var currentTimeText by remember { mutableStateOf("") }
  var timeZoneText by remember { mutableStateOf("") }

  LaunchedEffect(Unit) {
    val timeFormat = java.text.SimpleDateFormat("hh:mm:ss a z", java.util.Locale.getDefault())
    val tz = java.util.TimeZone.getDefault()
    timeZoneText = "${tz.id} (${tz.getDisplayName(false, java.util.TimeZone.SHORT)})"
    while (true) {
      currentTimeText = timeFormat.format(java.util.Date())
      kotlinx.coroutines.delay(1000)
    }
  }

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = PaletteCreamIvory
    ),
    border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(PaletteDeepSage),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.AccessTime,
              contentDescription = "Mobile Clock",
              tint = PaletteCreamIvory,
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "MOBILE DEVICE CLOCK & ALARM",
              style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp
              ),
              color = PaletteDarkText
            )
            Text(
              text = currentTimeText.ifBlank { "Syncing local time..." },
              style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
              ),
              color = PaletteDarkText
            )
          }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
          shape = RoundedCornerShape(20.dp),
          color = PalettePaleCeladon
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.AlarmOn,
              contentDescription = "Active Alarms",
              tint = PaletteDarkText,
              modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "$activeAlarmsCount Active",
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = PaletteDarkText
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "📍 Timezone: $timeZoneText • System Alarm Clock Sync Active",
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = PaletteDarkText.copy(alpha = 0.8f)
      )

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        androidx.compose.material3.Button(
          onClick = onQuickAlarmClick,
          colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = PaletteDeepSage,
            contentColor = PaletteCreamIvory
          ),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 44.dp),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
        ) {
          Icon(
            imageVector = Icons.Default.AlarmAdd,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            "Set Quick Alarm",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
          )
        }

        androidx.compose.material3.OutlinedButton(
          onClick = onTestAlarmSoundClick,
          colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = PaletteDarkText
          ),
          border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 44.dp),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
        ) {
          Icon(
            imageVector = Icons.Filled.VolumeUp,
            contentDescription = null,
            tint = PaletteDarkText,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            "Test Sound Now",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickMobileAlarmDialog(
  onDismiss: () -> Unit,
  onConfirm: (subjectName: String, topic: String, delayMinutes: Int, customTime: String?) -> Unit
) {
  var subjectName by remember { mutableStateOf("Quick Study") }
  var topic by remember { mutableStateOf("Mobile Study Session") }
  var selectedPresetMinutes by remember { mutableStateOf(1) }
  var isCustomTime by remember { mutableStateOf(false) }
  var customTimeInput by remember { mutableStateOf("") }

  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(PaletteDeepSage),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.AlarmAdd,
            contentDescription = "Set Quick Alarm",
            tint = PaletteCreamIvory,
            modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
          text = "Set Mobile Device Alarm",
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          color = PaletteDarkText
        )
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(
          text = "Select when your alarm should ring based on your mobile device clock.",
          style = MaterialTheme.typography.bodySmall,
          color = PaletteDarkText
        )

        Text(
          text = "Quick Offset Preset:",
          style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
          color = PaletteDarkText
        )

        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          listOf(1 to "+1 Min", 2 to "+2 Min", 5 to "+5 Min", 10 to "+10 Min", 30 to "+30 Min").forEach { (mins, label) ->
            val selected = !isCustomTime && selectedPresetMinutes == mins
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = if (selected) PaletteDeepSage else PalettePaleCeladon,
              border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
              modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .clickable {
                  isCustomTime = false
                  selectedPresetMinutes = mins
                }
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                  text = label,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (selected) PaletteCreamIvory else PaletteDarkText,
                  maxLines = 1,
                  softWrap = false,
                  textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
              }
            }
          }
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .fillMaxWidth()
            .clickable { isCustomTime = !isCustomTime }
        ) {
          androidx.compose.material3.RadioButton(
            selected = isCustomTime,
            onClick = { isCustomTime = true }
          )
          Text(
            text = "Or Set Exact Mobile Time (Time Scroller)",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = PaletteDarkText
          )
        }

        if (isCustomTime) {
          TimeScrollPickerField(
            label = "Exact Alarm Time",
            timeValue = customTimeInput.ifBlank { "09:00" },
            onTimeChange = { customTimeInput = it },
            modifier = Modifier.fillMaxWidth()
          )
        }

        androidx.compose.material3.OutlinedTextField(
          value = subjectName,
          onValueChange = { subjectName = it },
          label = { Text("Subject / Class Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        androidx.compose.material3.OutlinedTextField(
          value = topic,
          onValueChange = { topic = it },
          label = { Text("Session Topic / Note") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
      }
    },
    confirmButton = {
      androidx.compose.material3.Button(
        onClick = {
          onConfirm(
            subjectName,
            topic,
            selectedPresetMinutes,
            if (isCustomTime) customTimeInput else null
          )
          onDismiss()
        },
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
          containerColor = PaletteDeepSage,
          contentColor = PaletteCreamIvory
        )
      ) {
        Text("Set Alarm Now", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      androidx.compose.material3.TextButton(onClick = onDismiss) {
        Text("Cancel", color = PaletteDarkText)
      }
    }
  )
}


fun formatTo12Hour(timeStr: String): String {
  if (timeStr.isBlank()) return ""
  val trimmed = timeStr.trim()
  if (trimmed.equals("now", ignoreCase = true)) return "Now"
  if (trimmed.uppercase().contains("AM") || trimmed.uppercase().contains("PM")) {
    val isPm = trimmed.uppercase().contains("PM")
    val cleanDigits = trimmed.replace("AM", "", true).replace("PM", "", true).trim()
    val parts = cleanDigits.split(":")
    if (parts.size >= 2) {
      val h = parts[0].toIntOrNull() ?: return trimmed
      val m = parts[1].take(2).toIntOrNull() ?: 0
      val h12 = if (h == 0) 12 else if (h > 12) h - 12 else h
      return String.format("%d:%02d %s", h12, m, if (isPm) "PM" else "AM")
    }
    return trimmed
  }
  val parts = trimmed.split(":")
  if (parts.size >= 2) {
    val h = parts[0].toIntOrNull() ?: return trimmed
    val m = parts[1].take(2).toIntOrNull() ?: 0
    val isPm = h >= 12
    val h12 = when {
      h == 0 -> 12
      h > 12 -> h - 12
      else -> h
    }
    return String.format("%d:%02d %s", h12, m, if (isPm) "PM" else "AM")
  }
  return trimmed
}

fun formatTimeRange(startTime: String, endTime: String): String {
  val startFormatted = formatTo12Hour(startTime)
  val endFormatted = formatTo12Hour(endTime)
  return if (endFormatted.isBlank()) startFormatted else "$startFormatted - $endFormatted"
}

@Composable
fun TimeScrollPickerField(
  label: String,
  timeValue: String,
  onTimeChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  emptyText: String = "Select Time",
  allowClear: Boolean = false
) {
  var showPicker by remember { mutableStateOf(false) }

  androidx.compose.material3.Surface(
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
        color = PaletteDarkText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Spacer(modifier = Modifier.height(2.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = if (timeValue.isNotBlank()) formatTo12Hour(timeValue) else emptyText,
          fontSize = if (timeValue.isNotBlank()) 15.sp else 12.sp,
          fontWeight = if (timeValue.isNotBlank()) FontWeight.ExtraBold else FontWeight.Medium,
          color = if (timeValue.isNotBlank()) PaletteDarkText else PaletteDarkText.copy(alpha = 0.65f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        if (allowClear && timeValue.isNotBlank()) {
          Spacer(modifier = Modifier.width(4.dp))
          Box(
            modifier = Modifier
              .size(28.dp)
              .clip(CircleShape)
              .clickable { onTimeChange("") },
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Clear $label",
              tint = PaletteDeepSage,
              modifier = Modifier.size(16.dp)
            )
          }
        } else {
          Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Schedule,
            contentDescription = "Scroll Time Picker",
            tint = PaletteDeepSage,
            modifier = Modifier.size(18.dp)
          )
        }
      }
    }
  }

  if (showPicker) {
    TimeWheelScrollerDialog(
      title = "Scroll to Select $label",
      initialTimeStr = timeValue,
      allowClear = allowClear,
      onClear = {
        onTimeChange("")
        showPicker = false
      },
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
  onConfirm: (String) -> Unit,
  allowClear: Boolean = false,
  onClear: (() -> Unit)? = null
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

  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = androidx.compose.material.icons.Icons.Default.Schedule,
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
        androidx.compose.material3.Surface(
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

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(PaletteMintFoam, RoundedCornerShape(12.dp))
            .padding(8.dp),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
          ) {
            Text("HOUR", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PaletteDeepSage)
            Spacer(modifier = Modifier.height(4.dp))
            androidx.compose.foundation.lazy.LazyColumn(
              modifier = Modifier.fillMaxSize(),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              items(hoursList.size) { idx ->
                val h = hoursList[idx]
                val isSelected = h == selectedHour
                androidx.compose.material3.Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = if (isSelected) PaletteDeepSage else androidx.compose.ui.graphics.Color.Transparent,
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
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp)
                  )
                }
              }
            }
          }

          Text(":", fontSize = 20.sp, fontWeight = FontWeight.Black, color = PaletteDeepSage)

          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
          ) {
            Text("MINUTE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PaletteDeepSage)
            Spacer(modifier = Modifier.height(4.dp))
            androidx.compose.foundation.lazy.LazyColumn(
              modifier = Modifier.fillMaxSize(),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              items(minutesList.size) { idx ->
                val m = minutesList[idx]
                val isSelected = m == selectedMinute
                androidx.compose.material3.Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = if (isSelected) PaletteDeepSage else androidx.compose.ui.graphics.Color.Transparent,
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
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp)
                  )
                }
              }
            }
          }

          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f)
          ) {
            Text("PERIOD", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PaletteDeepSage)
            Spacer(modifier = Modifier.height(8.dp))
            listOf("AM", "PM").forEach { p ->
              val isSelected = p == selectedPeriod
              androidx.compose.material3.Surface(
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
                  textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                  modifier = Modifier.padding(vertical = 8.dp)
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      androidx.compose.material3.Button(
        onClick = {
          var h24 = selectedHour % 12
          if (selectedPeriod == "PM") h24 += 12
          val formatted = String.format("%02d:%02d", h24, selectedMinute)
          onConfirm(formatted)
        },
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
          containerColor = PaletteDeepSage,
          contentColor = PaletteCreamIvory
        ),
        shape = RoundedCornerShape(10.dp)
      ) {
        Text("Confirm Time", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (allowClear && onClear != null) {
          androidx.compose.material3.TextButton(onClick = onClear) {
            Text("Clear", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
          }
        }
        androidx.compose.material3.TextButton(onClick = onDismiss) {
          Text("Cancel", color = PaletteDarkText)
        }
      }
    },
    containerColor = PaletteCreamIvory,
    shape = RoundedCornerShape(16.dp)
  )
}
