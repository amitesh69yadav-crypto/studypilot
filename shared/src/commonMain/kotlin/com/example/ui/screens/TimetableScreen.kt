package com.example.ui.screens

import com.example.ui.components.rememberDatePicker
import com.example.platform.LocalPlatformActions
import com.example.platform.PlatformBackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import com.example.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import com.example.ui.theme.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import com.example.ui.theme.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import com.example.ui.theme.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ScheduleFormat
import com.example.data.model.Subject
import com.example.data.model.TimetableSession
import com.example.ui.components.formatTimeRange
import com.example.ui.components.formatTo12Hour
import com.example.ui.components.MobileClockAlarmCard
import com.example.ui.components.QuickMobileAlarmDialog
import com.example.ui.components.ActiveAlarmAlertDialog
import com.example.ui.components.EmptyState
import com.example.ui.components.WhatShouldIDoNowCard
import com.example.ui.viewmodel.StudyPilotViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val PaletteCreamIvory: Color
  @Composable get() = MaterialTheme.colorScheme.surface

private val PaletteDarkText: Color
  @Composable get() = MaterialTheme.colorScheme.onSurface

private val PaletteDeepSage: Color
  @Composable get() = MaterialTheme.colorScheme.primary

private val PaletteMediumSage: Color
  @Composable get() = MaterialTheme.colorScheme.outlineVariant

private val PaletteMintFoam: Color
  @Composable get() = MaterialTheme.colorScheme.surfaceVariant

private val PalettePaleCeladon: Color
  @Composable get() = MaterialTheme.colorScheme.primaryContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
  viewModel: StudyPilotViewModel,
  showAddDialogOnInit: Boolean = false
) {
  val actions = LocalPlatformActions.current
  val sessions by viewModel.sessions.collectAsState()
  val subjects by viewModel.subjects.collectAsState()
  val scheduleFormats by viewModel.scheduleFormats.collectAsState()
  val scheduleStatus by viewModel.scheduleStatus.collectAsState()
  val ringingAlarmSession by viewModel.ringingAlarmSession.collectAsState()

  val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
  val displayFormat = remember { SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()) }
  val monthYearFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
  val dayNameFormat = remember { SimpleDateFormat("EEEE", Locale.getDefault()) }
  val pillDayNameFormat = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
  val pillDayNumFormat = remember { SimpleDateFormat("d", Locale.getDefault()) }

  val realToday = remember { dateFormat.format(Date()) }
  // Selected day to manage (defaults to today)
  var selectedDate by remember { mutableStateOf(realToday) }
  val pickSelectedDate = rememberDatePicker { selectedDate = it }

  // Tabs: 0: Selected Day, 1: Schedule Formats (Templates), 2: Full Agenda (All)
  var selectedTab by remember { mutableIntStateOf(0) }

  var showAddDialog by remember { mutableStateOf(showAddDialogOnInit) }
  var sessionToEdit by remember { mutableStateOf<TimetableSession?>(null) }
  var showCopyDayDialog by remember { mutableStateOf(false) }
  var showApplyFormatDialog by remember { mutableStateOf(false) }
  var showSaveCurrentDayAsFormatDialog by remember { mutableStateOf(false) }
  var showCreateNewFormatDialog by remember { mutableStateOf(false) }
  var formatToInspect by remember { mutableStateOf<ScheduleFormat?>(null) }
  var showQuickAlarmDialog by remember { mutableStateOf(false) }
  var showHeaderCards by remember { mutableStateOf(true) }
  val activeAlarmsCount = remember(sessions) { sessions.count { it.isAlarmEnabled } }
  val sessionCountsByDate = remember(sessions) { sessions.groupingBy { it.date }.eachCount() }

  // Helpers to shift days, months, and years
  fun shiftSelectedDate(deltaDays: Int) {
    try {
      val parsed = dateFormat.parse(selectedDate) ?: Date()
      val cal = Calendar.getInstance().apply {
        time = parsed
        add(Calendar.DAY_OF_YEAR, deltaDays)
      }
      selectedDate = dateFormat.format(cal.time)
    } catch (_: Exception) {}
  }

  fun shiftSelectedMonth(deltaMonths: Int) {
    try {
      val parsed = dateFormat.parse(selectedDate) ?: Date()
      val cal = Calendar.getInstance().apply {
        time = parsed
        add(Calendar.MONTH, deltaMonths)
      }
      selectedDate = dateFormat.format(cal.time)
    } catch (_: Exception) {}
  }

  fun shiftSelectedYear(deltaYears: Int) {
    try {
      val parsed = dateFormat.parse(selectedDate) ?: Date()
      val cal = Calendar.getInstance().apply {
        time = parsed
        add(Calendar.YEAR, deltaYears)
      }
      selectedDate = dateFormat.format(cal.time)
    } catch (_: Exception) {}
  }

  // Selected date's sessions
  val selectedDaySessions = remember(sessions, selectedDate) {
    sessions.filter { it.date == selectedDate }.sortedBy { it.startTime }
  }

  // Full agenda list
  val agendaSessions = remember(sessions) {
    sessions.sortedBy { it.date + it.startTime }
  }

  // Parsed title for current selected date
  val selectedDateTitle = remember(selectedDate) {
    try {
      val d = dateFormat.parse(selectedDate) ?: Date()
      displayFormat.format(d)
    } catch (_: Exception) {
      selectedDate
    }
  }

  val isSelectedDateToday = selectedDate == realToday

  Scaffold(

    // The app's main Scaffold already applies system bar insets.

    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    containerColor = PaletteCreamIvory
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(PaletteCreamIvory)
        .padding(padding)
    ) {
      // Header bar
      Surface(
        color = PaletteCreamIvory,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, PaletteMediumSage.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
          // Top title row + Non-overlapping primary "+ Add" action
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(
              modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
            ) {
              Text(
                text = if (isSelectedDateToday) "Today · $selectedDateTitle" else selectedDateTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = PaletteDarkText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }

            Button(
              onClick = { showAddDialog = true },
              colors = ButtonDefaults.buttonColors(
                containerColor = PaletteDeepSage,
                contentColor = PaletteCreamIvory
              ),
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
              modifier = Modifier
                .heightIn(min = 42.dp)
                .testTag("add_event_button")
            ) {
              Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add Event Slot",
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "Add",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          // Secondary tools row (never crowds the title or overlaps cards)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            // Slide Hide / Unhide Toggle
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = PalettePaleCeladon,
              border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
              modifier = Modifier
                .weight(1f)
                .heightIn(min = 38.dp)
                .clickable { showHeaderCards = !showHeaderCards }
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
              ) {
                Icon(
                  imageVector = if (showHeaderCards) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                  contentDescription = null,
                  tint = PaletteDarkText,
                  modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = if (showHeaderCards) "Hide calendar" else "Show calendar",
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = PaletteDarkText,
                  maxLines = 1,
                  softWrap = false
                )
              }
            }

          }

          Spacer(modifier = Modifier.height(8.dp))

          // 2. Comprehensive Calendar Navigation & Selector (Day, Month, Year & Future/Past)
          AnimatedVisibility(
            visible = showHeaderCards,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
          ) {
            Surface(
            shape = RoundedCornerShape(12.dp),
            color = PaletteMintFoam,
            border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              // Row A: Year & Month Fast-Navigation Bar (e.g. October 2026, jump months and years freely)
              val monthYearStr = remember(selectedDate) {
                val parsedDateForHeader = try {
                  dateFormat.parse(selectedDate) ?: Date()
                } catch (_: Exception) {
                  Date()
                }
                monthYearFormat.format(parsedDateForHeader)
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                // Left controls: Fast Year / Month jump back
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = PalettePaleCeladon,
                    border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
                    modifier = Modifier
                      .height(28.dp)
                      .clickable { shiftSelectedYear(-1) }
                  ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                      Text(
                        text = "-1Y",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PaletteDarkText
                      )
                    }
                  }
                  IconButton(
                    onClick = { shiftSelectedMonth(-1) },
                    modifier = Modifier.size(28.dp)
                  ) {
                    Icon(
                      Icons.AutoMirrored.Filled.ArrowBack,
                      contentDescription = "Previous Month",
                      tint = PaletteDarkText,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                }

                // Center: Month & Year Label + Quick Today Button
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Text(
                    text = monthYearStr,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = PaletteDarkText
                  )
                  if (!isSelectedDateToday) {
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = PaletteDeepSage,
                      modifier = Modifier
                        .height(22.dp)
                        .clickable { selectedDate = realToday }
                    ) {
                      Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(
                          text = "Today",
                          fontSize = 9.sp,
                          fontWeight = FontWeight.Bold,
                          color = PaletteCreamIvory
                        )
                      }
                    }
                  }
                }

                // Right controls: Fast Month / Year jump forward
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  IconButton(
                    onClick = { shiftSelectedMonth(1) },
                    modifier = Modifier.size(28.dp)
                  ) {
                    Icon(
                      Icons.AutoMirrored.Filled.ArrowForward,
                      contentDescription = "Next Month",
                      tint = PaletteDarkText,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = PalettePaleCeladon,
                    border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
                    modifier = Modifier
                      .height(28.dp)
                      .clickable { shiftSelectedYear(1) }
                  ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                      Text(
                        text = "+1Y",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PaletteDarkText
                      )
                    }
                  }
                }
              }

              Spacer(modifier = Modifier.height(8.dp))

              // Row B: Direct Selected Date Button with full Android calendar picker dialog
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = PaletteCreamIvory,
                border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable {
                    pickSelectedDate(selectedDate)
                  }
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(
                    modifier = Modifier
                      .weight(1f)
                      .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Icon(
                      Icons.Default.CalendarMonth,
                      contentDescription = "Pick Date",
                      tint = PaletteDeepSage,
                      modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        text = selectedDateTitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = PaletteDarkText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                      )
                    }
                  }

                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isSelectedDateToday) {
                      Surface(
                        color = PaletteDeepSage,
                        shape = RoundedCornerShape(4.dp)
                      ) {
                        Text(
                          text = "Today",
                          fontWeight = FontWeight.Bold,
                          fontSize = 10.sp,
                          color = PaletteCreamIvory,
                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                      }
                    } else {
                      val daysDiff = remember(selectedDate, realToday) {
                        try {
                          val d1 = dateFormat.parse(realToday)?.time ?: 0L
                          val d2 = dateFormat.parse(selectedDate)?.time ?: 0L
                          ((d2 - d1) / (1000 * 60 * 60 * 24)).toInt()
                        } catch (_: Exception) { 0 }
                      }

                      val diffLabel = when {
                        daysDiff == 1 -> "Tomorrow"
                        daysDiff == -1 -> "Yesterday"
                        daysDiff > 0 -> "+${daysDiff}d ahead"
                        else -> "${daysDiff}d ago"
                      }

                      Surface(
                        color = PalettePaleCeladon,
                        shape = RoundedCornerShape(4.dp)
                      ) {
                        Text(
                          text = diffLabel,
                          fontWeight = FontWeight.Bold,
                          fontSize = 10.sp,
                          color = PaletteDarkText,
                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                      }
                    }
                  }
                }
              }

              Spacer(modifier = Modifier.height(8.dp))

              // Row C: Rolling 7-Day Window centered on the currently selected date (-3 to +3 days)
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                IconButton(
                  onClick = { shiftSelectedDate(-1) },
                  modifier = Modifier.size(28.dp)
                ) {
                  Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Previous Day",
                    tint = PaletteDarkText,
                    modifier = Modifier.size(16.dp)
                  )
                }

                val dayPills = remember(selectedDate) {
                  val baseCal = Calendar.getInstance().apply {
                    try {
                      time = dateFormat.parse(selectedDate) ?: Date()
                    } catch (_: Exception) {}
                  }
                  (-3..3).map { offset ->
                    val c = Calendar.getInstance().apply {
                      time = baseCal.time
                      add(Calendar.DAY_OF_YEAR, offset)
                    }
                    val dStr = dateFormat.format(c.time)
                    val dayName = pillDayNameFormat.format(c.time)
                    val dayNum = pillDayNumFormat.format(c.time)
                    Triple(dStr, dayName, dayNum)
                  }
                }

                Row(
                  modifier = Modifier.weight(1f),
                  horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  dayPills.forEach { (dStr, dayName, dayNum) ->
                    val isSelected = dStr == selectedDate
                    val isTodayPill = dStr == realToday
                    val sessionCount = sessionCountsByDate[dStr] ?: 0

                    Surface(
                      shape = RoundedCornerShape(8.dp),
                      color = if (isSelected) PaletteDeepSage else if (isTodayPill) PalettePaleCeladon else PaletteCreamIvory,
                      border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) PaletteDeepSage else PaletteMediumSage
                      ),
                      modifier = Modifier
                        .weight(1f)
                        .clickable { selectedDate = dStr }
                    ) {
                      Column(
                        modifier = Modifier.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                      ) {
                        Text(
                          text = dayName,
                          fontSize = 10.sp,
                          fontWeight = FontWeight.Bold,
                          color = if (isSelected) PaletteCreamIvory else PaletteDarkText,
                          maxLines = 1
                        )
                        Text(
                          text = dayNum,
                          fontSize = 13.sp,
                          fontWeight = FontWeight.Bold,
                          color = if (isSelected) PaletteCreamIvory else PaletteDarkText,
                          maxLines = 1
                        )
                        if (sessionCount > 0) {
                          Box(
                            modifier = Modifier
                              .size(4.dp)
                              .clip(CircleShape)
                              .background(if (isSelected) PaletteCreamIvory else PaletteDeepSage)
                          )
                        } else {
                          Spacer(modifier = Modifier.height(4.dp))
                        }
                      }
                    }
                  }
                }

                IconButton(
                  onClick = { shiftSelectedDate(1) },
                  modifier = Modifier.size(28.dp)
                ) {
                  Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Next Day",
                    tint = PaletteDarkText,
                    modifier = Modifier.size(16.dp)
                  )
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

          // 3. Clean Segmented Navigation Tabs:
          // [Day Schedule] | [Format Templates] | [Agenda (All)]
          TabRow(
            selectedTabIndex = selectedTab,
            containerColor = PaletteMintFoam,
            contentColor = PaletteDarkText,
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
          ) {
            Tab(
              selected = selectedTab == 0,
              onClick = { selectedTab = 0 },
              text = {
                Text(
                  "Day (${selectedDaySessions.size})",
                  fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                  fontSize = 11.sp,
                  color = PaletteDarkText,
                  maxLines = 1,
                  overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
              }
            )
            Tab(
              selected = selectedTab == 1,
              onClick = { selectedTab = 1 },
              text = {
                Text(
                  "Templates (${scheduleFormats.size})",
                  fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                  fontSize = 11.sp,
                  color = PaletteDarkText,
                  maxLines = 1,
                  overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
              }
            )
            Tab(
              selected = selectedTab == 2,
              onClick = { selectedTab = 2 },
              text = {
                Text(
                  "All (${sessions.size})",
                  fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Medium,
                  fontSize = 11.sp,
                  color = PaletteDarkText,
                  maxLines = 1,
                  overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
              }
            )
          }
        }
      }

      // TAB CONTENT
      when (selectedTab) {
        0 -> {
          // --- TAB 0: SELECTED DAY TIMETABLE ---
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            // Animated Header Cards (What Should I Do Now, Alarms, Quick Format Bar)
            if (showHeaderCards) {
              // Live "What Should I Do Now?" smart alarm card (when viewing today)
              if (isSelectedDateToday) {
                item {
                  WhatShouldIDoNowCard(
                    status = scheduleStatus,
                    onPlanScheduleClick = { showApplyFormatDialog = true },
                    onEditSessionClick = { sessionToEdit = it },
                    onMarkSessionDone = { session ->
                      val next = if (session.status == "COMPLETED") "SCHEDULED" else "COMPLETED"
                      viewModel.updateSessionStatus(session, next)
                    },
                    onAskAiTutor = { session ->
                      viewModel.openTutorForSubject(session.subjectId, "Can you explain key concepts for ${session.topic}?")
                    },
                    onToggleAlarm = { viewModel.toggleSessionAlarm(it) },
                    onTestAlarm = { viewModel.triggerTestAlarm(it) },
                    onSyncGoogleCalendar = { session ->
                      actions.addToCalendar(session)
                    }
                  )
                }

                item {
                  MobileClockAlarmCard(
                    activeAlarmsCount = activeAlarmsCount,
                    onQuickAlarmClick = { showQuickAlarmDialog = true },
                    onTestAlarmSoundClick = {
                      val sample = sessions.firstOrNull() ?: TimetableSession(
                        id = 999L,
                        subjectId = 0L,
                        subjectName = "Alarm System Test",
                        topic = "Mobile Ringtone & Beeps Test",
                        date = "",
                        startTime = "Now",
                        endTime = "",
                        location = "",
                        notes = ""
                      )
                      viewModel.triggerTestAlarm(sample)
                    }
                  )
                }
              }

              // Quick Format & Day Management Action Row: Symmetrical and even
              item {
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = PaletteMintFoam,
                  border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                      text = "$selectedDateTitle",
                      fontWeight = FontWeight.Bold,
                      fontSize = 12.sp,
                      color = PaletteDarkText
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      item {
                        Button(
                          onClick = { showApplyFormatDialog = true },
                          colors = ButtonDefaults.buttonColors(containerColor = PalettePaleCeladon, contentColor = PaletteDarkText),
                          border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
                          shape = RoundedCornerShape(8.dp),
                          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                          modifier = Modifier.heightIn(min = 44.dp)
                        ) {
                          Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = PaletteDarkText, modifier = Modifier.size(14.dp))
                          Spacer(modifier = Modifier.width(4.dp))
                          Text("Apply format", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                      }
                      item {
                        Button(
                          onClick = { showCopyDayDialog = true },
                          colors = ButtonDefaults.buttonColors(containerColor = PalettePaleCeladon, contentColor = PaletteDarkText),
                          border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
                          shape = RoundedCornerShape(8.dp),
                          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                          modifier = Modifier.heightIn(min = 44.dp)
                        ) {
                          Icon(Icons.Default.ContentCopy, contentDescription = null, tint = PaletteDarkText, modifier = Modifier.size(14.dp))
                          Spacer(modifier = Modifier.width(4.dp))
                          Text("Copy day", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                      }
                      item {
                        Button(
                          onClick = { showSaveCurrentDayAsFormatDialog = true },
                          colors = ButtonDefaults.buttonColors(containerColor = PaletteDeepSage, contentColor = PaletteCreamIvory),
                          shape = RoundedCornerShape(8.dp),
                          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                          modifier = Modifier.heightIn(min = 44.dp)
                        ) {
                          Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(14.dp))
                          Spacer(modifier = Modifier.width(4.dp))
                          Text("Save format", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                      }
                      item {
                        Button(
                          onClick = { viewModel.addFoodAndSleepRoutineBatch(selectedDate) },
                          colors = ButtonDefaults.buttonColors(containerColor = PalettePaleCeladon, contentColor = PaletteDarkText),
                          border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
                          shape = RoundedCornerShape(8.dp),
                          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                          modifier = Modifier.heightIn(min = 44.dp)
                        ) {
                          Icon(Icons.Default.Restaurant, contentDescription = null, tint = PaletteDarkText, modifier = Modifier.size(14.dp))
                          Spacer(modifier = Modifier.width(4.dp))
                          Text("+ Food & Sleep", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                      }
                      item {
                        Button(
                          onClick = { showAddDialog = true },
                          colors = ButtonDefaults.buttonColors(containerColor = PaletteDeepSage, contentColor = PaletteCreamIvory),
                          shape = RoundedCornerShape(8.dp),
                          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                          modifier = Modifier.heightIn(min = 44.dp)
                        ) {
                          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                          Spacer(modifier = Modifier.width(4.dp))
                          Text("+ Custom slot", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                      }
                    }
                  }
                }
              }
            }

            // Session List header
            item {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(
                  modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                ) {
                  Text(
                    text = "Time-Slots",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = PaletteDarkText
                  )
                  Text(
                    text = selectedDateTitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = PaletteDarkText.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  if (selectedDaySessions.isNotEmpty()) {
                    TextButton(
                      onClick = { viewModel.clearDaySchedule(selectedDate) },
                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                      Text("Clear day", fontSize = 12.sp, color = PaletteDarkText, fontWeight = FontWeight.Bold)
                    }
                  }
                  Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PalettePaleCeladon,
                    border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage)
                  ) {
                    Text(
                      text = "${selectedDaySessions.size} session(s)",
                      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                      color = PaletteDarkText,
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                  }
                }
              }
            }

            // Time slots
            if (selectedDaySessions.isEmpty()) {
              item {
                EmptyState(
                  icon = Icons.Default.CalendarMonth,
                  title = "Nothing on $selectedDateTitle",
                  description = "Add a session, copy another day, or apply a template.",
                  actionButton = {
                    Row(
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                      Button(
                        onClick = { showApplyFormatDialog = true },
                        colors = ButtonDefaults.buttonColors(
                          containerColor = PaletteDeepSage,
                          contentColor = PaletteCreamIvory
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                      ) {
                        Text("Apply template", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                      }
                      OutlinedButton(
                        onClick = { showAddDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                      ) {
                        Text("Add slot", color = PaletteDarkText, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                      }
                    }
                  }
                )
              }
            } else {
              items(selectedDaySessions, key = { it.id }) { session ->
                GoogleCalendarEventCard(
                  session = session,
                  onStatusChange = { newStatus -> viewModel.updateSessionStatus(session, newStatus) },
                  onEdit = { sessionToEdit = session },
                  onToggleAlarm = { viewModel.toggleSessionAlarm(session) },
                  onTestAlarm = { viewModel.triggerTestAlarm(session) },
                  onAskAiTutor = {
                    viewModel.openTutorForSubject(session.subjectId, "Help me prepare notes for ${session.topic}")
                  },
                  onSyncCalendar = {
                    actions.addToCalendar(session)
                  },
                  onDelete = { viewModel.deleteSession(session.id) }
                )
              }
            }
          }
        }

        1 -> {
          // --- TAB 1: SCHEDULE FORMATS / TEMPLATES ---
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            item {
              Surface(
                modifier = Modifier.fillMaxWidth(),
                color = PaletteMintFoam,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, PaletteMediumSage)
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(
                    modifier = Modifier
                      .weight(1f)
                      .padding(end = 12.dp)
                  ) {
                    Text(
                      text = "Templates",
                      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                      color = PaletteDarkText
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                      text = "Save daily templates and reuse them on any day in one tap.",
                      style = MaterialTheme.typography.bodySmall,
                      color = PaletteDarkText.copy(alpha = 0.85f)
                    )
                  }

                  Button(
                    onClick = { showCreateNewFormatDialog = true },
                    colors = ButtonDefaults.buttonColors(
                      containerColor = PaletteDeepSage,
                      contentColor = PaletteCreamIvory
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 44.dp)
                  ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                      text = "New format",
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Bold,
                      maxLines = 1,
                      softWrap = false
                    )
                  }
                }
              }
            }

            if (scheduleFormats.isEmpty()) {
              item {
                EmptyState(
                  icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                  title = "No templates yet",
                  description = "Save a day's layout, like a lab day or a revision day, and reuse it.",
                  actionButton = {
                    Button(
                      onClick = { showCreateNewFormatDialog = true },
                      colors = ButtonDefaults.buttonColors(
                        containerColor = PaletteDeepSage,
                        contentColor = PaletteCreamIvory
                      ),
                      shape = RoundedCornerShape(10.dp)
                    ) {
                      Text("Create template", fontWeight = FontWeight.Bold)
                    }
                  }
                )
              }
            } else {
              items(scheduleFormats, key = { it.id }) { format ->
                ScheduleFormatCard(
                  format = format,
                  selectedDate = selectedDate,
                  onApplyToSelectedDate = { overwrite ->
                    viewModel.applyFormatToDate(format, selectedDate, overwrite)
                    selectedTab = 0
                  },
                  onDeleteFormat = { viewModel.deleteScheduleFormat(format.id) }
                )
              }
            }
          }
        }

        2 -> {
          // --- TAB 2: ALL EVENTS AGENDA ---
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            item {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "Full agenda",
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                  color = PaletteDarkText,
                  modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                )
                Text(
                  text = "${agendaSessions.size} total events",
                  style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                  color = PaletteDarkText
                )
              }
            }

            if (agendaSessions.isEmpty()) {
              item {
                EmptyState(
                  icon = Icons.Default.CalendarMonth,
                  title = "No events scheduled",
                  description = "Add a session or apply a template.",
                  actionButton = {
                    Button(
                      onClick = { showAddDialog = true },
                      colors = ButtonDefaults.buttonColors(
                        containerColor = PaletteDeepSage,
                        contentColor = PaletteCreamIvory
                      ),
                      shape = RoundedCornerShape(10.dp)
                    ) {
                      Text("Add first session", fontWeight = FontWeight.Bold)
                    }
                  }
                )
              }
            } else {
              items(agendaSessions, key = { it.id }) { session ->
                GoogleCalendarEventCard(
                  session = session,
                  onStatusChange = { newStatus -> viewModel.updateSessionStatus(session, newStatus) },
                  onEdit = { sessionToEdit = session },
                  onToggleAlarm = { viewModel.toggleSessionAlarm(session) },
                  onTestAlarm = { viewModel.triggerTestAlarm(session) },
                  onAskAiTutor = {
                    viewModel.openTutorForSubject(session.subjectId, "Help me prepare notes for ${session.topic}")
                  },
                  onSyncCalendar = {
                    actions.addToCalendar(session)
                  },
                  onDelete = { viewModel.deleteSession(session.id) }
                )
              }
            }
          }
        }
      }
    }
  }

  // --- DIALOGS ---

  // 1. Add / Edit Session Dialog
  if (showAddDialog) {
    SessionEditDialog(
      sessionToEdit = null,
      subjects = subjects,
      todayDate = selectedDate,
      onDismiss = { showAddDialog = false },
      onConfirm = { newSession ->
        viewModel.addSession(newSession)
        showAddDialog = false
      }
    )
  }

  sessionToEdit?.let { existing ->
    SessionEditDialog(
      sessionToEdit = existing,
      subjects = subjects,
      todayDate = selectedDate,
      onDismiss = { sessionToEdit = null },
      onConfirm = { updatedSession ->
        viewModel.updateSession(updatedSession)
        sessionToEdit = null
      }
    )
  }

  // 2. Copy Day Schedule Dialog
  if (showCopyDayDialog) {
    CopyDayScheduleDialog(
      sourceDate = selectedDate,
      availableDates = sessions.map { it.date }.distinct().sorted(),
      onDismiss = { showCopyDayDialog = false },
      onConfirmCopy = { srcDate, targetDate, overwrite ->
        viewModel.copyDaySchedule(srcDate, targetDate, overwrite)
        showCopyDayDialog = false
        selectedDate = targetDate
        selectedTab = 0
      }
    )
  }

  // 3. Apply Format / Template Dialog
  if (showApplyFormatDialog) {
    ApplyFormatDialog(
      targetDate = selectedDate,
      formats = scheduleFormats,
      onDismiss = { showApplyFormatDialog = false },
      onApply = { format, overwrite ->
        viewModel.applyFormatToDate(format, selectedDate, overwrite)
        showApplyFormatDialog = false
      },
      onGoToManageFormats = {
        showApplyFormatDialog = false
        selectedTab = 1
      }
    )
  }

  // 4. Save Current Day Sessions as a Format
  if (showSaveCurrentDayAsFormatDialog) {
    SaveDayAsFormatDialog(
      daySessions = selectedDaySessions,
      dayDate = selectedDate,
      onDismiss = { showSaveCurrentDayAsFormatDialog = false },
      onConfirmSave = { name, desc ->
        viewModel.saveScheduleAsFormat(name, desc, selectedDaySessions)
        showSaveCurrentDayAsFormatDialog = false
      }
    )
  }

  // 5. Create Brand New Format from Scratch Dialog
  if (showCreateNewFormatDialog) {
    CreateNewFormatDialog(
      subjects = subjects,
      onDismiss = { showCreateNewFormatDialog = false },
      onSaveFormat = { name, desc, templateSessions ->
        viewModel.saveScheduleAsFormat(name, desc, templateSessions)
        showCreateNewFormatDialog = false
      }
    )
  }

  // 6. Quick Mobile Device Alarm Dialog
  if (showQuickAlarmDialog) {
    QuickMobileAlarmDialog(
      onDismiss = { showQuickAlarmDialog = false },
      onConfirm = { subject, topic, delayMins, customTime ->
        viewModel.setQuickMobileAlarm(subject, topic, delayMins, customTime)
      }
    )
  }
}

/**
 * Format Card in Formats Tab
 */
@Composable
fun ScheduleFormatCard(
  format: ScheduleFormat,
  selectedDate: String,
  onApplyToSelectedDate: (Boolean) -> Unit,
  onDeleteFormat: () -> Unit
) {
  val (sessionCount, sessionsList) = remember<Pair<Int, List<Pair<String, String>>>>(format.formatSessionsJson) {
    try {
      val arr = org.json.JSONArray(format.formatSessionsJson)
      val list = ArrayList<Pair<String, String>>(arr.length())
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val time = formatTimeRange(obj.optString("startTime"), obj.optString("endTime"))
        val topic = obj.optString("topic").ifBlank { obj.optString("subjectName", "Session") }
        list.add(time to topic)
      }
      arr.length() to list
    } catch (_: Exception) {
      0 to emptyList()
    }
  }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    color = PaletteMintFoam,
    border = androidx.compose.foundation.BorderStroke(1.2.dp, PaletteMediumSage)
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      // Header row: Icon + Format Name + Badge + Delete
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          modifier = Modifier
            .weight(1f)
            .padding(end = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = PaletteDeepSage,
            modifier = Modifier.size(32.dp)
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                Icons.Default.Bookmark,
                contentDescription = null,
                tint = PaletteCreamIvory,
                modifier = Modifier.size(16.dp)
              )
            }
          }

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = format.name,
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = PaletteDarkText,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = if (format.description.isNotBlank()) format.description else "Reusable Daily Routine",
              style = MaterialTheme.typography.bodySmall,
              color = PaletteDarkText.copy(alpha = 0.8f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        IconButton(
          onClick = onDeleteFormat,
          modifier = Modifier
            .size(44.dp)
            .background(PalettePaleCeladon, RoundedCornerShape(8.dp))
        ) {
          Icon(
            Icons.Default.Delete,
            contentDescription = "Delete Format",
            tint = PaletteDarkText,
            modifier = Modifier.size(18.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Session count pill badge
      Surface(
        color = PalettePaleCeladon,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, PaletteMediumSage),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text(
            text = "Sessions",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = PaletteDarkText
          )
          Text(
            text = "$sessionCount Sessions",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = PaletteDarkText
          )
        }
      }

      // Preview blocks list: clean, evenly aligned rows
      if (sessionsList.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PaletteCreamIvory)
            .border(0.8.dp, PaletteMediumSage.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          sessionsList.take(3).forEach { (time, topic) ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "• $topic",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = PaletteDarkText,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                text = time,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = PaletteDarkText.copy(alpha = 0.7f)
              )
            }
          }
          if (sessionsList.size > 3) {
            Text(
              text = "+ ${sessionsList.size - 3} more block(s)",
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = PaletteDeepSage,
              modifier = Modifier.padding(top = 2.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Two evenly sized, symmetrical action buttons (height 40dp, equal weight 1f)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Button(
          onClick = { onApplyToSelectedDate(false) },
          colors = ButtonDefaults.buttonColors(
            containerColor = PaletteDeepSage,
            contentColor = PaletteCreamIvory
          ),
          shape = RoundedCornerShape(10.dp),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 44.dp)
        ) {
          Text(
            text = "Add to $selectedDate",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
          )
        }

        OutlinedButton(
          onClick = { onApplyToSelectedDate(true) },
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = PaletteDarkText),
          border = androidx.compose.foundation.BorderStroke(1.2.dp, PaletteMediumSage),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 44.dp)
        ) {
          Text(
            text = "Replace day schedule",
            fontSize = 11.sp,
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

/**
 * Dialog to Copy one day's schedule to another date
 */
@Composable
fun CopyDayScheduleDialog(
  sourceDate: String,
  availableDates: List<String>,
  onDismiss: () -> Unit,
  onConfirmCopy: (source: String, target: String, overwrite: Boolean) -> Unit
) {
  val actions = LocalPlatformActions.current
  val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

  var currentSourceDate by remember { mutableStateOf(sourceDate) }
  var targetDate by remember {
    val cal = Calendar.getInstance()
    try {
      cal.time = dateFormat.parse(sourceDate) ?: Date()
      cal.add(Calendar.DAY_OF_YEAR, 1)
    } catch (_: Exception) {}
    mutableStateOf(dateFormat.format(cal.time))
  }
  var overwriteExisting by remember { mutableStateOf(true) }
  val pickTargetDate = rememberDatePicker { targetDate = it }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text("Copy to another day", fontWeight = FontWeight.Bold, color = PaletteDarkText)
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Copy every session and alarm from one day to another.",
          fontSize = 12.sp,
          color = PaletteDarkText
        )

        // Source Date input
        OutlinedTextField(
          value = currentSourceDate,
          onValueChange = { currentSourceDate = it },
          label = { Text("From", color = PaletteDarkText) },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        )

        // Target Date picker / field
        OutlinedTextField(
          value = targetDate,
          onValueChange = { targetDate = it },
          label = { Text("To", color = PaletteDarkText) },
          trailingIcon = {
            IconButton(onClick = {
              pickTargetDate(targetDate)
            }) {
              Icon(Icons.Default.CalendarMonth, contentDescription = "Pick Date", tint = PaletteDarkText)
            }
          },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        )

        // Overwrite checkbox
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.clickable { overwriteExisting = !overwriteExisting }
        ) {
          Checkbox(
            checked = overwriteExisting,
            onCheckedChange = { overwriteExisting = it }
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "Clear the other day first",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = PaletteDarkText
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (currentSourceDate.isNotBlank() && targetDate.isNotBlank()) {
            onConfirmCopy(currentSourceDate.trim(), targetDate.trim(), overwriteExisting)
          }
        },
        colors = ButtonDefaults.buttonColors(
          containerColor = PaletteDeepSage,
          contentColor = PaletteCreamIvory
        ),
        shape = RoundedCornerShape(10.dp)
      ) {
        Text("Copy schedule", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", color = PaletteDarkText, fontWeight = FontWeight.Bold)
      }
    },
    containerColor = com.example.ui.theme.dialogBackground(PaletteCreamIvory),
    shape = RoundedCornerShape(16.dp)
  )
}

/**
 * Apply Format to Target Date Dialog
 */
@Composable
fun ApplyFormatDialog(
  targetDate: String,
  formats: List<ScheduleFormat>,
  onDismiss: () -> Unit,
  onApply: (ScheduleFormat, Boolean) -> Unit,
  onGoToManageFormats: () -> Unit
) {
  var selectedFormat by remember { mutableStateOf<ScheduleFormat?>(formats.firstOrNull()) }
  var overwrite by remember { mutableStateOf(true) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text("Apply a template to $targetDate", fontWeight = FontWeight.Bold, color = PaletteDarkText)
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Select a saved routine format to apply to $targetDate:",
          fontSize = 12.sp,
          color = PaletteDarkText
        )

        if (formats.isEmpty()) {
          Text("No templates yet. Make one first.", color = PaletteDarkText, fontWeight = FontWeight.Bold)
        } else {
          formats.forEach { format ->
            val isSelected = selectedFormat?.id == format.id
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = if (isSelected) PalettePaleCeladon else PaletteMintFoam,
              border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isSelected) PaletteDeepSage else PaletteMediumSage
              ),
              modifier = Modifier
                .fillMaxWidth()
                .clickable { selectedFormat = format }
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text(format.name, fontWeight = FontWeight.Bold, color = PaletteDarkText)
                if (format.description.isNotBlank()) {
                  Text(format.description, fontSize = 11.sp, color = PaletteDarkText)
                }
              }
            }
          }
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.clickable { overwrite = !overwrite }
        ) {
          Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
          Spacer(modifier = Modifier.width(4.dp))
          Text("Replace any existing sessions on $targetDate", fontSize = 12.sp, color = PaletteDarkText)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          selectedFormat?.let { onApply(it, overwrite) }
        },
        enabled = selectedFormat != null,
        colors = ButtonDefaults.buttonColors(
          containerColor = PaletteDeepSage,
          contentColor = PaletteCreamIvory
        ),
        shape = RoundedCornerShape(10.dp)
      ) {
        Text("Apply to $targetDate", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", color = PaletteDarkText, fontWeight = FontWeight.Bold)
      }
    },
    containerColor = com.example.ui.theme.dialogBackground(PaletteCreamIvory),
    shape = RoundedCornerShape(16.dp)
  )
}

/**
 * Save Current Day as Format Dialog
 */
@Composable
fun SaveDayAsFormatDialog(
  daySessions: List<TimetableSession>,
  dayDate: String,
  onDismiss: () -> Unit,
  onConfirmSave: (name: String, description: String) -> Unit
) {
  var name by remember { mutableStateOf("Format from $dayDate") }
  var description by remember { mutableStateOf("${daySessions.size} scheduled blocks") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text("Save day as template", fontWeight = FontWeight.Bold, color = PaletteDarkText)
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Save the ${daySessions.size} sessions on $dayDate as a template you can apply to any day.",
          fontSize = 12.sp,
          color = PaletteDarkText
        )

        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Format name", color = PaletteDarkText) },
          placeholder = { Text("e.g. Wednesday Heavy Lectures") },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
          value = description,
          onValueChange = { description = it },
          label = { Text("Description (optional)", color = PaletteDarkText) },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (name.isNotBlank()) {
            onConfirmSave(name.trim(), description.trim())
          }
        },
        enabled = daySessions.isNotEmpty() && name.isNotBlank(),
        colors = ButtonDefaults.buttonColors(
          containerColor = PaletteDeepSage,
          contentColor = PaletteCreamIvory
        ),
        shape = RoundedCornerShape(10.dp)
      ) {
        Text("Save format", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", color = PaletteDarkText, fontWeight = FontWeight.Bold)
      }
    },
    containerColor = com.example.ui.theme.dialogBackground(PaletteCreamIvory),
    shape = RoundedCornerShape(16.dp)
  )
}

/**
 * Create Brand New Format Dialog
 */
@Composable
fun CreateNewFormatDialog(
  subjects: List<Subject>,
  onDismiss: () -> Unit,
  onSaveFormat: (name: String, description: String, sessions: List<TimetableSession>) -> Unit
) {
  var formatName by remember { mutableStateOf("") }
  var formatDesc by remember { mutableStateOf("") }

  // Quick preset templates or custom
  var selectedPreset by remember { mutableIntStateOf(0) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text("New template", fontWeight = FontWeight.Bold, color = PaletteDarkText)
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        OutlinedTextField(
          value = formatName,
          onValueChange = { formatName = it },
          label = { Text("Format name", color = PaletteDarkText) },
          placeholder = { Text("e.g. Friday Lab & Review") },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
          value = formatDesc,
          onValueChange = { formatDesc = it },
          label = { Text("Description", color = PaletteDarkText) },
          placeholder = { Text("e.g. 3 sessions with evening revision alarm") },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        )

        Text("Start from", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PaletteDarkText)

        // Preset 0: Academic 3-Lecture
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (selectedPreset == 0) PalettePaleCeladon else PaletteMintFoam,
          border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
          modifier = Modifier
            .fillMaxWidth()
            .clickable {
              selectedPreset = 0
              if (formatName.isBlank()) formatName = "Standard 3-Session Day"
            }
        ) {
          Column(modifier = Modifier.padding(8.dp)) {
            Text("Standard 3-Session Day", fontWeight = FontWeight.Bold, color = PaletteDarkText, fontSize = 12.sp)
            Text("09:00 Lecture, 11:30 Tutorial, 14:00 Lab", fontSize = 11.sp, color = PaletteDarkText)
          }
        }

        // Preset 1: Intensive Exam Study Sprints
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (selectedPreset == 1) PalettePaleCeladon else PaletteMintFoam,
          border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
          modifier = Modifier
            .fillMaxWidth()
            .clickable {
              selectedPreset = 1
              if (formatName.isBlank()) formatName = "Intensive 4-Sprint Revision"
            }
        ) {
          Column(modifier = Modifier.padding(8.dp)) {
            Text("Intensive 4-Sprint Revision", fontWeight = FontWeight.Bold, color = PaletteDarkText, fontSize = 12.sp)
            Text("09:00 Sprint 1, 11:00 Sprint 2, 14:00 Sprint 3, 16:30 Sprint 4", fontSize = 11.sp, color = PaletteDarkText)
          }
        }

        // Preset 2: Half Day
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (selectedPreset == 2) PalettePaleCeladon else PaletteMintFoam,
          border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
          modifier = Modifier
            .fillMaxWidth()
            .clickable {
              selectedPreset = 2
              if (formatName.isBlank()) formatName = "Half Day Focus"
            }
        ) {
          Column(modifier = Modifier.padding(8.dp)) {
            Text("Morning-Only Half Day (2 Sessions)", fontWeight = FontWeight.Bold, color = PaletteDarkText, fontSize = 12.sp)
            Text("09:30 Deep Lecture, 11:30 Problem Set", fontSize = 11.sp, color = PaletteDarkText)
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (formatName.isNotBlank()) {
            val sessionsToSave = when (selectedPreset) {
              0 -> listOf(
                TimetableSession(
                  subjectId = subjects.getOrNull(0)?.id ?: 1L,
                  subjectName = subjects.getOrNull(0)?.name ?: "Computer Science",
                  topic = "Morning Core Lecture",
                  date = "",
                  startTime = "09:00",
                  endTime = "10:30",
                  location = "Hall C",
                  isAlarmEnabled = true
                ),
                TimetableSession(
                  subjectId = subjects.getOrNull(1)?.id ?: 2L,
                  subjectName = subjects.getOrNull(1)?.name ?: "Mathematics",
                  topic = "Theoretical Proofs & Exercises",
                  date = "",
                  startTime = "11:30",
                  endTime = "13:00",
                  location = "Hall 2",
                  isAlarmEnabled = true
                ),
                TimetableSession(
                  subjectId = subjects.getOrNull(2)?.id ?: 3L,
                  subjectName = subjects.getOrNull(2)?.name ?: "Physics",
                  topic = "Afternoon Practical Lab",
                  date = "",
                  startTime = "14:00",
                  endTime = "16:00",
                  location = "Lab 4",
                  isAlarmEnabled = true
                )
              )
              1 -> listOf(
                TimetableSession(
                  subjectId = subjects.getOrNull(0)?.id ?: 1L,
                  subjectName = subjects.getOrNull(0)?.name ?: "Revision",
                  topic = "Deep Sprint 1: Formula Review & Quizzes",
                  date = "",
                  startTime = "09:00",
                  endTime = "10:30",
                  location = "Study Desk",
                  isAlarmEnabled = true
                ),
                TimetableSession(
                  subjectId = subjects.getOrNull(1)?.id ?: 2L,
                  subjectName = subjects.getOrNull(1)?.name ?: "Revision",
                  topic = "Deep Sprint 2: Hard Problem Sets",
                  date = "",
                  startTime = "11:00",
                  endTime = "12:30",
                  location = "Study Desk",
                  isAlarmEnabled = true
                ),
                TimetableSession(
                  subjectId = subjects.getOrNull(2)?.id ?: 3L,
                  subjectName = subjects.getOrNull(2)?.name ?: "Revision",
                  topic = "Deep Sprint 3: Past Papers Practice",
                  date = "",
                  startTime = "14:00",
                  endTime = "15:30",
                  location = "Library",
                  isAlarmEnabled = true
                ),
                TimetableSession(
                  subjectId = subjects.getOrNull(0)?.id ?: 1L,
                  subjectName = subjects.getOrNull(0)?.name ?: "Revision",
                  topic = "Deep Sprint 4: AI Tutor Doubt Clearing",
                  date = "",
                  startTime = "16:30",
                  endTime = "18:00",
                  location = "Home Desk",
                  isAlarmEnabled = true
                )
              )
              else -> listOf(
                TimetableSession(
                  subjectId = subjects.getOrNull(0)?.id ?: 1L,
                  subjectName = subjects.getOrNull(0)?.name ?: "Study",
                  topic = "Morning Deep Concept Reading",
                  date = "",
                  startTime = "09:30",
                  endTime = "11:00",
                  location = "Library",
                  isAlarmEnabled = true
                ),
                TimetableSession(
                  subjectId = subjects.getOrNull(1)?.id ?: 2L,
                  subjectName = subjects.getOrNull(1)?.name ?: "Study",
                  topic = "Problem Set Practice & Flashcards",
                  date = "",
                  startTime = "11:30",
                  endTime = "13:00",
                  location = "Study Room",
                  isAlarmEnabled = true
                )
              )
            }
            onSaveFormat(formatName.trim(), formatDesc.trim(), sessionsToSave)
          }
        },
        enabled = formatName.isNotBlank(),
        colors = ButtonDefaults.buttonColors(
          containerColor = PaletteDeepSage,
          contentColor = PaletteCreamIvory
        ),
        shape = RoundedCornerShape(10.dp)
      ) {
        Text("Save template", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", color = PaletteDarkText, fontWeight = FontWeight.Bold)
      }
    },
    containerColor = com.example.ui.theme.dialogBackground(PaletteCreamIvory),
    shape = RoundedCornerShape(16.dp)
  )
}

/**
 * Google Calendar-style clean event row card:
 * Left column: Time and duration
 * Center: Clean colored strip, Subject badge, Topic, location
 * Bottom / Right: Aligned actions (Complete, Alarm, AI Tutor, Sync)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoogleCalendarEventCard(
  session: TimetableSession,
  onStatusChange: (String) -> Unit,
  onEdit: () -> Unit,
  onToggleAlarm: () -> Unit,
  onTestAlarm: () -> Unit,
  onAskAiTutor: () -> Unit,
  onSyncCalendar: () -> Unit,
  onDelete: () -> Unit
) {
  val isCompleted = session.status == "COMPLETED"
  val categoryTag = when (session.sessionCategory) {
    "FOOD" -> "Food & Meal"
    "SLEEP" -> "Sleep & Rest"
    "EXERCISE" -> "Fitness & Exercise"
    "PERSONAL" -> "Personal Break"
    else -> session.subjectName.ifBlank { "Alarm" }
  }
  val categoryBarColor = when (session.sessionCategory) {
    "FOOD" -> androidx.compose.ui.graphics.Color(0xFFB7844B)
    "SLEEP" -> androidx.compose.ui.graphics.Color(0xFF6B7F8E)
    "EXERCISE" -> androidx.compose.ui.graphics.Color(0xFFA86F68)
    "PERSONAL" -> androidx.compose.ui.graphics.Color(0xFF688E7F)
    else -> PaletteDeepSage
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("schedule_event_card"),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (isCompleted) PalettePaleCeladon.copy(alpha = 0.6f) else PaletteMintFoam
    ),
    border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .drawBehind {
          drawRect(
            color = categoryBarColor,
            size = Size(width = 6.dp.toPx(), height = size.height)
          )
        }
        .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    ) {
        // Top row: Time + Subject Tag + Actions
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
          ) {
            Text(
              text = formatTimeRange(session.startTime, session.endTime),
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = PaletteDarkText,
              maxLines = 1
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = PalettePaleCeladon
            ) {
              Text(
                text = categoryTag,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = PaletteDarkText,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
              )
            }
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onEdit, modifier = Modifier.size(44.dp)) {
              Icon(Icons.Default.Edit, contentDescription = "Edit", tint = PaletteDarkText, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
              Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))
        // Event Topic / Title
        Text(
          text = session.topic,
          style = MaterialTheme.typography.bodyLarge.copy(
            fontWeight = FontWeight.Bold,
            textDecoration = if (isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
          ),
          color = PaletteDarkText
        )

        Spacer(modifier = Modifier.height(4.dp))
        // Location & Date
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (session.location.isNotBlank()) {
            Icon(Icons.Default.LocationOn, contentDescription = null, tint = PaletteDarkText, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = session.location,
              style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
              color = PaletteDarkText
            )
            Spacer(modifier = Modifier.width(12.dp))
          }
          Text(
            text = "${session.date}",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = PaletteDarkText
          )
        }

        if (session.notes.isNotBlank()) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Notes: ${session.notes}",
            style = MaterialTheme.typography.bodySmall,
            color = PaletteDarkText,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // One even row: every action gets the same width.
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          SessionAction(
            icon = if (isCompleted) Icons.Default.CheckCircle else Icons.Default.Check,
            label = if (isCompleted) "Completed" else "Done",
            emphasized = !isCompleted,
            modifier = Modifier.weight(1f)
          ) { onStatusChange(if (isCompleted) "SCHEDULED" else "COMPLETED") }
          SessionAction(Icons.Default.Psychology, "AI Tutor", modifier = Modifier.weight(1f)) { onAskAiTutor() }
          SessionAction(
            icon = if (session.isAlarmEnabled) Icons.Default.AlarmOn else Icons.Default.AlarmOff,
            label = if (session.isAlarmEnabled) "Alarm On" else "Alarm Off",
            modifier = Modifier.weight(1f)
          ) { onToggleAlarm() }
          if (session.isAlarmEnabled) {
            SessionAction(Icons.Default.NotificationsActive, "Ring", modifier = Modifier.weight(1f)) { onTestAlarm() }
          }
          SessionAction(Icons.Default.CalendarMonth, "Calendar", modifier = Modifier.weight(1f)) { onSyncCalendar() }
        }
      }
    }
  }

/**
 * Dialog for both Adding and Editing a Session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionEditDialog(
  sessionToEdit: TimetableSession?,
  subjects: List<Subject>,
  todayDate: String,
  onDismiss: () -> Unit,
  /** Start and end for a new session, e.g. from clicking an empty slot in the week grid. */
  newStartTime: String = "10:00",
  newEndTime: String = "",
  onConfirm: (TimetableSession) -> Unit
) {
  var selectedCategory by remember {
    mutableStateOf(sessionToEdit?.sessionCategory ?: "ACADEMIC")
  }

  var selectedSubject by remember {
    mutableStateOf(
      if (sessionToEdit != null && sessionToEdit.subjectId > 0L) {
        subjects.firstOrNull { it.id == sessionToEdit.subjectId }
      } else {
        null
      }
    )
  }
  var topic by remember { mutableStateOf(sessionToEdit?.topic ?: "") }
  var date by remember { mutableStateOf(sessionToEdit?.date ?: todayDate) }
  var startTime by remember { mutableStateOf(sessionToEdit?.startTime ?: newStartTime) }
  var endTime by remember { mutableStateOf(sessionToEdit?.endTime ?: newEndTime) }
  var location by remember { mutableStateOf(sessionToEdit?.location ?: "") }
  var notes by remember { mutableStateOf(sessionToEdit?.notes ?: "") }
  var recurringDays by remember { mutableStateOf(sessionToEdit?.recurringDays ?: "") }
  var reminderMinutes by remember { mutableIntStateOf(sessionToEdit?.reminderMinutes ?: 0) }
  var isAlarmEnabled by remember { mutableStateOf(sessionToEdit?.isAlarmEnabled ?: true) }

  var expandedDropdown by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = if (sessionToEdit != null) "Edit Schedule Session" else "Schedule Class or Alarm",
        fontWeight = FontWeight.Bold,
        color = PaletteDarkText
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "Type",
          style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
          color = PaletteDarkText
        )

        // Category Selector Row
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          listOf(
            "ACADEMIC" to "Study",
            "FOOD" to "Food",
            "SLEEP" to "Sleep",
            "PERSONAL" to "Break"
          ).forEach { (cat, label) ->
            val isSelected = selectedCategory == cat
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = if (isSelected) PaletteDeepSage else PalettePaleCeladon,
              border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
              modifier = Modifier
                .weight(1f)
                .heightIn(min = 42.dp)
                .clickable {
                  selectedCategory = cat
                  if (cat != "ACADEMIC" && topic.isBlank()) {
                    topic = when (cat) {
                      "FOOD" -> "Lunch Break & Energy Meal"
                      "SLEEP" -> "Night Sleep & Rest (8h)"
                      else -> "Personal Relaxation Break"
                    }
                  }
                }
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                Text(
                  text = label,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (isSelected) PaletteCreamIvory else PaletteDarkText,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis,
                  textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
              }
            }
          }
        }

        // Subject dropdown (Optional, Only for Academic / General Sessions)
        if (selectedCategory == "ACADEMIC") {
          ExposedDropdownMenuBox(
            expanded = expandedDropdown,
            onExpandedChange = { expandedDropdown = !expandedDropdown }
          ) {
            OutlinedTextField(
              value = selectedSubject?.name ?: "No Subject (Optional)",
              onValueChange = {},
              readOnly = true,
              label = { Text("Subject (optional)", color = PaletteDarkText) },
              trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .testTag("schedule_subject_dropdown")
            )
            ExposedDropdownMenu(
              expanded = expandedDropdown,
              onDismissRequest = { expandedDropdown = false }
            ) {
              DropdownMenuItem(
                text = { Text("No subject", color = PaletteDarkText, fontWeight = FontWeight.SemiBold) },
                onClick = {
                  selectedSubject = null
                  expandedDropdown = false
                }
              )
              subjects.forEach { sub ->
                DropdownMenuItem(
                  text = { Text("${sub.code} - ${sub.name}", color = PaletteDarkText) },
                  onClick = {
                    selectedSubject = sub
                    expandedDropdown = false
                  }
                )
              }
            }
          }
        } else {
          // Routine Chip Quick Preset Selectors
          Text(
            text = "Suggestions",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = PaletteDarkText
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            val presets = when (selectedCategory) {
              "FOOD" -> listOf("Breakfast", "Lunch", "Dinner", "Snack")
              "SLEEP" -> listOf("Night Sleep", "Power Nap", "Wind-Down")
              else -> listOf("Workout", "Walk", "Coffee Break", "Relax")
            }
            presets.forEach { chipText ->
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = PaletteMintFoam,
                border = androidx.compose.foundation.BorderStroke(0.8.dp, PaletteMediumSage),
                modifier = Modifier.clickable {
                  topic = chipText
                  if (chipText.contains("Breakfast")) { startTime = "08:00"; endTime = "08:30" }
                  if (chipText.contains("Lunch")) { startTime = "13:00"; endTime = "14:00" }
                  if (chipText.contains("Dinner")) { startTime = "19:30"; endTime = "20:30" }
                  if (chipText.contains("Night")) { startTime = "22:30"; endTime = "06:30" }
                  if (chipText.contains("Nap")) { startTime = "14:30"; endTime = "15:00" }
                }
              ) {
                Text(
                  text = chipText,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = PaletteDarkText,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
              }
            }
          }
        }

        OutlinedTextField(
          value = topic,
          onValueChange = { topic = it },
          label = {
            Text(
              if (selectedCategory == "ACADEMIC") "Topic / Reminder Title (Optional)" else "Routine Title / Note (Optional)",
              color = PaletteDarkText
            )
          },
          placeholder = { Text("e.g. Morning Alarm or Lecture Review") },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("schedule_topic_input")
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          com.example.ui.components.TimeScrollPickerField(
            label = "Start Time",
            timeValue = startTime,
            onTimeChange = { startTime = it },
            modifier = Modifier
              .weight(1f)
              .testTag("schedule_start_time_picker")
          )
          com.example.ui.components.TimeScrollPickerField(
            label = "End time (optional)",
            timeValue = endTime,
            onTimeChange = { endTime = it },
            emptyText = "Alarm Only",
            allowClear = true,
            modifier = Modifier
              .weight(1f)
              .testTag("schedule_end_time_picker")
          )
        }

        OutlinedTextField(
          value = date,
          onValueChange = { date = it },
          label = { Text("Date (YYYY-MM-DD)", color = PaletteDarkText) },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
          value = location,
          onValueChange = { location = it },
          label = { Text("Location (optional)", color = PaletteDarkText) },
          placeholder = { Text("Optional (e.g. Room 101, Home)") },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("schedule_location_input")
        )

        // Alarm toggle
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = if (isAlarmEnabled) PalettePaleCeladon else PaletteMintFoam,
          border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage),
          modifier = Modifier
            .fillMaxWidth()
            .clickable { isAlarmEnabled = !isAlarmEnabled }
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = if (isAlarmEnabled) Icons.Default.AlarmOn else Icons.Default.AlarmOff,
              contentDescription = null,
              tint = PaletteDarkText
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
              Text(
                text = if (isAlarmEnabled) "Sound Alarm Enabled" else "Sound Alarm Disabled",
                fontWeight = FontWeight.Bold,
                color = PaletteDarkText
              )
              Text(
                text = if (endTime.isBlank()) {
                  "Rings once at ${formatTo12Hour(startTime)} as an alarm (no end time)"
                } else {
                  "Mobile alarm will ring exactly at ${formatTo12Hour(startTime)} when event starts"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = PaletteDarkText
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val trimmedEnd = endTime.trim()
          val isRoutine = selectedCategory != "ACADEMIC"
          val finalSubjectId = if (isRoutine) -1L else (selectedSubject?.id ?: 0L)
          val finalSubjectName = if (isRoutine) {
            when (selectedCategory) {
              "FOOD" -> "Food & Meals"
              "SLEEP" -> "Sleep & Rest"
              "EXERCISE" -> "Exercise & Fitness"
              else -> "Personal Break"
            }
          } else {
            selectedSubject?.name?.takeIf { it.isNotBlank() }
              ?: if (trimmedEnd.isBlank()) "Alarm" else "General"
          }
          val finalTopic = topic.trim().ifBlank {
            when {
              isRoutine -> finalSubjectName
              selectedSubject != null -> selectedSubject!!.name
              trimmedEnd.isBlank() -> "Scheduled Alarm"
              else -> "Scheduled Session"
            }
          }

          val session = TimetableSession(
            id = sessionToEdit?.id ?: 0L,
            subjectId = finalSubjectId,
            subjectName = finalSubjectName,
            topic = finalTopic,
            date = date.trim(),
            startTime = startTime.trim().ifBlank { "09:00" },
            endTime = trimmedEnd,
            location = location.trim(),
            notes = notes.trim(),
            recurringDays = recurringDays.trim(),
            reminderMinutes = reminderMinutes,
            status = sessionToEdit?.status ?: "SCHEDULED",
            isAlarmEnabled = isAlarmEnabled,
            googleCalendarEventId = sessionToEdit?.googleCalendarEventId,
            sessionCategory = selectedCategory,
            isRoutineOnly = isRoutine
          )
          onConfirm(session)
        },
        colors = ButtonDefaults.buttonColors(
          containerColor = PaletteDeepSage,
          contentColor = PaletteCreamIvory
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.testTag("save_schedule_button")
      ) {
        Text("Save schedule", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", color = PaletteDarkText, fontWeight = FontWeight.Bold)
      }
    },
    containerColor = com.example.ui.theme.dialogBackground(PaletteCreamIvory),
    shape = RoundedCornerShape(16.dp)
  )
}

/** Icon over a short label; equal-width cells keep a row of card actions even. */
@Composable
private fun SessionAction(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  modifier: Modifier = Modifier,
  emphasized: Boolean = false,
  onClick: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    modifier = modifier.heightIn(min = 56.dp).clickable(onClick = onClick)
  ) {
    Column(
      modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Icon(
        icon,
        contentDescription = null,
        tint = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(18.dp)
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
      )
    }
  }
}
