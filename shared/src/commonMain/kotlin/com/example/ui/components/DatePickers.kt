package com.example.ui.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A Material date picker for "yyyy-MM-dd" dates. Call the returned function with the date to start
 * from; [onPicked] gets the chosen date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberDatePicker(onPicked: (String) -> Unit): (String) -> Unit {
  var startDate by remember { mutableStateOf<String?>(null) }
  startDate?.let { start ->
    // The picker works in UTC milliseconds.
    val format = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
    val initialMillis = remember(start) { runCatching { format.parse(start)?.time }.getOrNull() }
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
      onDismissRequest = { startDate = null },
      confirmButton = {
        TextButton(onClick = {
          state.selectedDateMillis?.let { onPicked(format.format(Date(it))) }
          startDate = null
        }) { Text("OK") }
      },
      dismissButton = { TextButton(onClick = { startDate = null }) { Text("Cancel") } }
    ) {
      DatePicker(state = state)
    }
  }
  return { startDate = it }
}
