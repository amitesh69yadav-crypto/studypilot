package com.example.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.TimetableSession
import com.example.ui.GlassCircleButton
import com.example.ui.screens.SessionEditDialog
import com.example.ui.theme.GlassColors
import com.example.ui.theme.LocalGlass
import com.example.ui.theme.glass
import com.example.ui.viewmodel.StudyPilotViewModel

/**
 * Sessions with edits the user just made by dragging, shown straight away rather than waiting for
 * the database to send them back.
 */
@Composable
internal fun rememberEditableSessions(viewModel: StudyPilotViewModel): Pair<List<TimetableSession>, (TimetableSession) -> Unit> {
  val stored by viewModel.sessions.collectAsState()
  var pending by remember { mutableStateOf<Map<Long, TimetableSession>>(emptyMap()) }
  LaunchedEffect(stored) {
    // Drop edits the database has caught up with.
    pending = pending.filterValues { p -> stored.none { it.id == p.id && it.startTime == p.startTime && it.endTime == p.endTime && it.date == p.date } }
  }
  val shown = if (pending.isEmpty()) stored else stored.map { pending[it.id] ?: it }
  return shown to { updated ->
    pending = pending + (updated.id to updated)
    viewModel.updateSession(updated)
  }
}

/** Where the edit dialog is open: an existing session, or a new one at a date and minute. */
internal sealed interface SessionDialog {
  data class Edit(val session: TimetableSession) : SessionDialog
  data class New(val date: String, val minute: Int) : SessionDialog
}

@Composable
internal fun SessionDialogHost(viewModel: StudyPilotViewModel, dialog: SessionDialog?, onClose: () -> Unit) {
  val subjects by viewModel.subjects.collectAsState()
  when (dialog) {
    null -> Unit
    is SessionDialog.Edit -> SessionEditDialog(
      sessionToEdit = dialog.session,
      subjects = subjects,
      todayDate = dialog.session.date.ifBlank { todayDate() },
      onDismiss = onClose,
      onConfirm = {
        viewModel.updateSession(it)
        onClose()
      }
    )
    is SessionDialog.New -> SessionEditDialog(
      sessionToEdit = null,
      subjects = subjects,
      todayDate = dialog.date,
      onDismiss = onClose,
      newStartTime = timeOf(dialog.minute),
      newEndTime = timeOf((dialog.minute + 60).coerceAtMost(DAY_MINUTES - 1)),
      onConfirm = {
        viewModel.addSession(it)
        onClose()
      }
    )
  }
}

/**
 * The day as an orbit: a 24-hour ring of sessions with a legend of where the time goes. Sessions can
 * be moved and stretched on the ring; the expand button opens it large.
 */
@Composable
fun OrbitCard(viewModel: StudyPilotViewModel, date: String, modifier: Modifier = Modifier, title: String = "Orbit") {
  val glass = LocalGlass.current ?: return
  val (sessions, save) = rememberEditableSessions(viewModel)
  var view by remember { mutableStateOf(OrbitView.Planned) }
  var layers by remember { mutableStateOf(OrbitLayers.Unified) }
  var expanded by remember { mutableStateOf(false) }
  var dialog by remember { mutableStateOf<SessionDialog?>(null) }
  val blocks = remember(sessions, date) { blocksOn(sessions, date) }

  val panel: @Composable (Boolean) -> Unit = { large ->
    OrbitPanel(
      title = title,
      date = date,
      blocks = blocks,
      view = view,
      layers = layers,
      glass = glass,
      large = large,
      onView = { view = it },
      onLayers = { layers = it },
      onExpand = if (large) ({ expanded = false }) else ({ expanded = true }),
      onRetime = { block, s, e -> save(retimed(block, s, e)) },
      onOpen = { dialog = SessionDialog.Edit(it.session) },
      onAddAt = { dialog = SessionDialog.New(date, it) }
    )
  }

  Box(modifier.glass(RoundedCornerShape(26.dp), glass).padding(20.dp)) { panel(false) }

  if (expanded) {
    Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      Box(
        Modifier
          .widthIn(max = 1080.dp)
          .fillMaxWidth(0.9f)
          .glass(RoundedCornerShape(30.dp), glass, glass.frost)
          .padding(28.dp)
      ) { panel(true) }
    }
  }
  SessionDialogHost(viewModel, dialog) { dialog = null }
}

@Composable
private fun OrbitPanel(
  title: String,
  date: String,
  blocks: List<DayBlock>,
  view: OrbitView,
  layers: OrbitLayers,
  glass: GlassColors,
  large: Boolean,
  onView: (OrbitView) -> Unit,
  onLayers: (OrbitLayers) -> Unit,
  onExpand: () -> Unit,
  onRetime: (DayBlock, Int, Int) -> Unit,
  onOpen: (DayBlock) -> Unit,
  onAddAt: (Int) -> Unit
) {
  val today = todayDate()
  val isToday = date == today
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          title,
          style = MaterialTheme.typography.headlineLarge.copy(fontSize = if (large) 34.sp else 26.sp),
          color = glass.text,
          maxLines = 1
        )
        Text(
          (if (isToday) "Today · " else "") + longDate(date),
          fontSize = 12.5.sp,
          color = glass.muted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      GlassCircleButton(
        icon = if (large) Icons.Default.Close else Icons.Default.OpenInFull,
        description = if (large) "Close" else "Expand",
        glass = glass,
        onClick = onExpand
      )
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      SegmentedPills(listOf("Planned", "Actual"), view.ordinal, glass) { onView(OrbitView.entries[it]) }
      Spacer(Modifier.width(8.dp))
      SegmentedPills(listOf("Unified", "Layers"), layers.ordinal, glass) { onLayers(OrbitLayers.entries[it]) }
    }
    Spacer(Modifier.height(6.dp))
    Text(
      if (view == OrbitView.Planned) "Drag a plan to move it; drag its coloured ends to resize. Click an empty part of the ring to add one."
      else "Done sessions in colour; the rest of the plan is faded.",
      fontSize = 12.sp,
      color = glass.muted
    )
    Spacer(Modifier.height(10.dp))

    val ring: @Composable (Modifier) -> Unit = { m ->
      OrbitRing(
        blocks = blocks,
        view = view,
        layers = layers,
        glass = glass,
        showNow = isToday,
        dayLabel = if (isToday) "Today" else weekdayShort(date).lowercase().replaceFirstChar { it.uppercase() } + " " + monthDay(date),
        modifier = m,
        onRetime = onRetime,
        onOpen = onOpen,
        onAddAt = onAddAt
      )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val maxRing: Dp = if (large) 560.dp else 380.dp
      if (maxWidth >= 520.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.weight(1.3f), contentAlignment = Alignment.Center) { ring(Modifier.widthIn(max = maxRing).fillMaxWidth()) }
          OrbitLegend(blocks, view, glass, Modifier.weight(1f), large)
        }
      } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
          ring(Modifier.widthIn(max = maxRing).fillMaxWidth())
          OrbitLegend(blocks, view, glass, Modifier.fillMaxWidth(), large)
        }
      }
    }
  }
}

/** "PLANNED 7h of 24h", a bar of the day, and a line for each subject or routine. */
@Composable
fun OrbitLegend(blocks: List<DayBlock>, view: OrbitView, glass: GlassColors, modifier: Modifier = Modifier, large: Boolean = false) {
  val counted = if (view == OrbitView.Actual) blocks.filter { it.done } else blocks
  val rows = legendFor(counted)
  val covered = coveredMinutes(counted)
  val maxRows = if (large) 10 else 6
  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(verticalAlignment = Alignment.Bottom) {
      Text(
        if (view == OrbitView.Actual) "DONE" else "PLANNED",
        style = MaterialTheme.typography.labelSmall,
        color = glass.muted,
        letterSpacing = 1.4.sp
      )
      Spacer(Modifier.weight(1f))
      Text(
        if (view == OrbitView.Actual) "${formatDuration(covered)} of ${formatDuration(coveredMinutes(blocks))}"
        else "${formatDuration(covered)} of 24h",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = glass.text
      )
    }
    // The day as one bar, each part in its colour.
    Row(
      Modifier
        .fillMaxWidth()
        .height(6.dp)
        .clip(CircleShape)
        .background(glass.inset)
    ) {
      val total = DAY_MINUTES.toFloat()
      var used = 0f
      rows.filter { it.minutes > 0 }.forEach { row ->
        val share = row.minutes.coerceAtMost((total - used).toInt()).toFloat()
        if (share > 0f) {
          Box(Modifier.weight(share).fillMaxHeight().background(pastelFor(row.sample).first))
          used += share
        }
      }
      if (total - used > 0f) Spacer(Modifier.weight(total - used))
    }
    if (rows.isEmpty()) {
      Text(
        if (view == OrbitView.Actual) "Nothing marked done on this day yet."
        else "Nothing planned for this day. Click the ring to add a session.",
        fontSize = 13.sp,
        color = glass.muted
      )
    }
    val listModifier = if (large) Modifier.verticalScroll(rememberScrollState()) else Modifier
    Column(listModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
      rows.take(maxRows).forEach { row ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          CategoryChip(row.sample, if (large) 34.dp else 30.dp)
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text(row.label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = glass.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
              if (row.minutes == 0) "Alarm at ${clockLabel(minutesOf(row.sample.startTime) ?: 0)}"
              else "${formatDuration(row.minutes)} · ${"%.1f".format(row.percentOfDay)}%",
              fontSize = 12.sp,
              color = glass.muted
            )
          }
        }
      }
      if (rows.size > maxRows) {
        Text("+${rows.size - maxRows} more", fontSize = 12.sp, color = glass.muted, modifier = Modifier.padding(start = 2.dp))
      }
    }
  }
}
