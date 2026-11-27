package com.example.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.GlassCircleButton
import com.example.ui.screens.TimetableScreen
import com.example.ui.theme.GlassColors
import com.example.ui.theme.LocalGlass
import com.example.ui.theme.glass
import com.example.ui.viewmodel.StudyPilotViewModel

private enum class TimetableMode(val label: String) { Week("Week"), Day("Day"), Planner("Planner") }

/** The next half hour from now, for a new session's start. */
private fun nextSlot(): Int = ((nowMinutes() / 30 + 1) * 30).coerceAtMost(DAY_MINUTES - 60)

/**
 * The desktop timetable: a week grid to drag sessions around in, a day view with the week's orbits and
 * the day's ring, and the original planner with its routines and tools.
 */
@Composable
fun DesktopTimetable(viewModel: StudyPilotViewModel, showAddDialogOnInit: Boolean = false) {
  val glass = LocalGlass.current
  if (glass == null) {
    TimetableScreen(viewModel, showAddDialogOnInit)
    return
  }
  val (sessions, save) = rememberEditableSessions(viewModel)
  var mode by rememberSaveable { mutableStateOf(TimetableMode.Week) }
  var selected by rememberSaveable { mutableStateOf(todayDate()) }
  var dialog by remember { mutableStateOf<SessionDialog?>(if (showAddDialogOnInit) SessionDialog.New(todayDate(), nextSlot()) else null) }
  val today = todayDate()
  val week = remember(selected) { weekOf(selected) }

  // View switch, week navigation and Add. On the desktop they sit in the page header beside the title.
  val toolbar: @Composable () -> Unit = {
    Row(verticalAlignment = Alignment.CenterVertically) {
      SegmentedPills(TimetableMode.entries.map { it.label }, mode.ordinal, glass) { mode = TimetableMode.entries[it] }
      Spacer(Modifier.weight(1f))
      if (mode != TimetableMode.Planner) {
        val step = if (mode == TimetableMode.Week) 7 else 1
        GlassCircleButton(Icons.Default.ChevronLeft, if (step == 7) "Previous week" else "Previous day", glass) {
          selected = shiftDate(selected, -step)
        }
        Text(
          if (mode == TimetableMode.Week) weekRangeLabel(week) else longDate(selected),
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          color = glass.text,
          modifier = Modifier.padding(horizontal = 14.dp).widthIn(min = 170.dp),
          maxLines = 1
        )
        GlassCircleButton(Icons.Default.ChevronRight, if (step == 7) "Next week" else "Next day", glass) {
          selected = shiftDate(selected, step)
        }
        val away = if (mode == TimetableMode.Week) today !in week else selected != today
        if (away) {
          Spacer(Modifier.width(8.dp))
          GlassPill(if (mode == TimetableMode.Week) "This week" else "Today", glass) { selected = today }
        }
        Spacer(Modifier.width(10.dp))
      }
      // The planner has its own Add button in its header.
      if (mode != TimetableMode.Planner) {
        InkPill("Add", glass) { dialog = SessionDialog.New(selected, if (selected == today) nextSlot() else 9 * 60) }
      }
  }
  }
  Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    if (!com.example.ui.HeaderControls { toolbar() }) toolbar()

    when (mode) {
      TimetableMode.Week -> Column(
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(bottom = 16.dp)
          .glass(RoundedCornerShape(26.dp), glass)
      ) {
        WeekGridHeader(sessions, week, glass)
        HorizontalDivider(color = glass.insetRim)
        WeekGrid(
          sessions = sessions,
          week = week,
          glass = glass,
          onMove = { block, toDate, s, e ->
            val moved = retimed(block, s, e)
            save(if (repeats(block.session)) moved else moved.copy(date = toDate))
          },
          onOpen = { dialog = SessionDialog.Edit(it.session) },
          onAdd = { date, minute -> dialog = SessionDialog.New(date, minute) },
          modifier = Modifier.weight(1f).padding(top = 4.dp)
        )
      }
      TimetableMode.Day -> Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        WeekOrbitStrip(sessions, week, selected, glass, onSelect = { selected = it })
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          OrbitCard(viewModel, selected, Modifier.weight(1.55f))
          DayAgenda(viewModel, sessions, selected, glass, Modifier.weight(1f)) { dialog = SessionDialog.Edit(it) }
        }
      }
      TimetableMode.Planner -> Box(Modifier.weight(1f)) { TimetableScreen(viewModel) }
    }
  }
  SessionDialogHost(viewModel, dialog) { dialog = null }
}

/** The day's sessions in order, each with a button to mark it done. */
@Composable
private fun DayAgenda(
  viewModel: StudyPilotViewModel,
  sessions: List<com.example.data.model.TimetableSession>,
  date: String,
  glass: GlassColors,
  modifier: Modifier,
  onOpen: (com.example.data.model.TimetableSession) -> Unit
) {
  val blocks = remember(sessions, date) { blocksOn(sessions, date) }
  val plan = remember(blocks) { colorPlanFor(blocks) }
  Column(
    modifier.glass(RoundedCornerShape(26.dp), glass).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Agenda", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 26.sp), color = glass.text, modifier = Modifier.weight(1f))
      val done = blocks.count { it.done }
      if (blocks.isNotEmpty()) Text("$done of ${blocks.size} done", fontSize = 12.5.sp, color = glass.muted)
    }
    if (blocks.isEmpty()) {
      Text("Nothing on this day. Add a session, or click the ring.", fontSize = 13.sp, color = glass.muted)
    }
    blocks.forEach { b ->
      Row(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(16.dp))
          .clickable { onOpen(b.session) }
          .pointerHoverIcon(PointerIcon.Hand)
          .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        CategoryChip(b.session, 32.dp, plan.of(b))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(
            b.session.topic.ifBlank { groupLabel(b.session) },
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = glass.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            (if (b.isMarker) clockLabel(b.start) else "${clockLabel(b.start)} – ${clockLabel(b.end)}") + " · " + groupLabel(b.session),
            fontSize = 12.sp,
            color = glass.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        Box(
          Modifier
            .size(28.dp)
            .clip(CircleShape)
            .then(
              if (b.done) Modifier.background(glass.ink)
              else Modifier.border(1.5.dp, glass.muted.copy(alpha = 0.5f), CircleShape)
            )
            .clickable { viewModel.updateSessionStatus(b.session, if (b.done) "SCHEDULED" else "COMPLETED") },
          contentAlignment = Alignment.Center
        ) {
          if (b.done) Icon(Icons.Default.Check, contentDescription = "Done", tint = glass.onInk, modifier = Modifier.size(16.dp))
        }
      }
    }
  }
}

@Composable
private fun GlassPill(label: String, glass: GlassColors, onClick: () -> Unit) {
  Box(
    Modifier
      .height(40.dp)
      .glass(CircleShape, glass)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = glass.text)
  }
}

/** The near-black pill for the page's main action. */
@Composable
private fun InkPill(label: String, glass: GlassColors, onClick: () -> Unit) {
  Row(
    Modifier
      .height(40.dp)
      .clip(CircleShape)
      .background(glass.ink)
      .clickable(onClick = onClick)
      .padding(start = 14.dp, end = 18.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(Icons.Default.Add, contentDescription = null, tint = glass.onInk, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = glass.onInk)
  }
}
