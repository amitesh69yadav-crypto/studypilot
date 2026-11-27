package com.example.ui.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.data.model.TimetableSession
import com.example.data.schedule.ScheduleLogic
import com.example.ui.theme.GlassColors
import com.example.ui.theme.LocalStudyColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val HourHeight = 52.dp
private val GutterWidth = 58.dp
private val MinBlockHeight = 22.dp

private data class GridDrag(val block: DayBlock, val day: Int, val mode: DragMode, val dx: Float = 0f, val dy: Float = 0f)

private fun hourLabel(h: Int): String = when {
  h == 0 -> "12 AM"
  h < 12 -> "$h AM"
  h == 12 -> "12 PM"
  else -> "${h - 12} PM"
}

internal fun repeats(session: TimetableSession) = ScheduleLogic.parseRecurringDays(session.recurringDays).isNotEmpty()

/** "MON / Nov 23 / 7h 30m planned" over each day of the grid. */
@Composable
fun WeekGridHeader(sessions: List<TimetableSession>, week: List<String>, glass: GlassColors) {
  val today = todayDate()
  val accent = LocalStudyColors.current.deepSage
  Row(Modifier.fillMaxWidth().padding(start = GutterWidth)) {
    week.forEach { date ->
      val planned = remember(sessions, date) { coveredMinutes(blocksOn(sessions, date)) }
      val isToday = date == today
      Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(
          if (isToday) "${weekdayShort(date)} · TODAY" else weekdayShort(date),
          fontSize = 10.5.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.1.sp,
          color = if (isToday) accent else glass.muted,
          maxLines = 1
        )
        Text(monthDay(date), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = glass.text, maxLines = 1)
        Text(
          if (planned == 0) "Nothing planned" else "${formatDuration(planned)} planned",
          fontSize = 11.sp,
          color = glass.muted,
          maxLines = 1
        )
      }
    }
  }
}

/**
 * The week as columns of hours, with each session a pastel block. Drag a block to another time or
 * day, drag its bottom edge to change when it ends, click it to open it, or click an empty slot to
 * add a session there. A repeating session moves in time only, since it belongs to several days.
 */
@Composable
fun WeekGrid(
  sessions: List<TimetableSession>,
  week: List<String>,
  glass: GlassColors,
  onMove: (block: DayBlock, toDate: String, start: Int, end: Int) -> Unit,
  onOpen: (DayBlock) -> Unit,
  onAdd: (date: String, minute: Int) -> Unit,
  modifier: Modifier = Modifier
) {
  val today = todayDate()
  val accent = LocalStudyColors.current.deepSage
  val scroll = rememberScrollState()
  val density = LocalDensity.current
  LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (HourHeight * 7).roundToPx() }) }
  val now by produceState(nowMinutes()) {
    while (true) {
      delay(30_000)
      value = nowMinutes()
    }
  }
  val currentAdd by rememberUpdatedState(onAdd)
  var drag by remember { mutableStateOf<GridDrag?>(null) }
  val columns = remember(sessions, week) { week.map { columnBlocksOn(sessions, it) } }

  Row(modifier.verticalScroll(scroll)) {
    // Hour labels down the side.
    Box(Modifier.width(GutterWidth).height(HourHeight * 24)) {
      for (h in 1 until 24) {
        Text(
          hourLabel(h),
          fontSize = 10.5.sp,
          color = glass.muted,
          modifier = Modifier.align(Alignment.TopEnd).offset(y = HourHeight * h - 7.dp).padding(end = 10.dp)
        )
      }
    }
    BoxWithConstraints(Modifier.weight(1f).height(HourHeight * 24)) {
      val colWidth: Dp = maxWidth / 7
      val minute: Dp = HourHeight / 60
      val colPx = with(density) { colWidth.toPx() }
      val minutePx = with(density) { minute.toPx() }

      /** Where [d] would put its block: the day, start and end. */
      fun preview(d: GridDrag): Triple<Int, Int, Int> {
        val delta = (d.dy / minutePx).roundToInt()
        val (s, e) = dragged(d.block, d.mode, delta, step = 15)
        val day = if (d.mode == DragMode.Move && !repeats(d.block.session)) (d.day + (d.dx / colPx).roundToInt()).coerceIn(0, 6) else d.day
        return Triple(day, s, e)
      }

      // Grid lines, today's column and the time now.
      val todayIndex = week.indexOf(today)
      val line = glass.muted.copy(alpha = 0.16f)
      val halfLine = glass.muted.copy(alpha = 0.08f)
      Canvas(
        Modifier
          .fillMaxSize()
          .pointerInput(week) {
            detectTapGestures { pos ->
              val day = (pos.x / colPx).toInt().coerceIn(0, 6)
              val slot = ((pos.y / minutePx).toInt() / 30 * 30).coerceIn(0, DAY_MINUTES - 30)
              currentAdd(week[day], slot)
            }
          }
      ) {
        if (todayIndex >= 0) {
          drawRect(glass.inset, topLeft = Offset(colPx * todayIndex, 0f), size = Size(colPx, size.height))
        }
        val hourPx = minutePx * 60
        for (h in 0..24) {
          drawLine(line, Offset(0f, hourPx * h), Offset(size.width, hourPx * h), strokeWidth = 1f)
          if (h < 24) {
            drawLine(
              halfLine,
              Offset(0f, hourPx * h + hourPx / 2),
              Offset(size.width, hourPx * h + hourPx / 2),
              strokeWidth = 1f,
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f))
            )
          }
        }
        for (i in 0..7) drawLine(line, Offset(colPx * i, 0f), Offset(colPx * i, size.height), strokeWidth = 1f)
        if (todayIndex >= 0) {
          val y = now * minutePx
          drawLine(accent, Offset(colPx * todayIndex, y), Offset(colPx * (todayIndex + 1), y), strokeWidth = 2f * density.density)
          drawCircle(accent, radius = 4.5f * density.density, center = Offset(colPx * todayIndex + 1f, y))
        }
      }

      columns.forEachIndexed { dayIndex, blocks ->
        val lanes = remember(blocks) { lanesFor(blocks) }
        blocks.forEach { b ->
          key(b.session.id, b.fromYesterday, dayIndex) {
            val (lane, laneCount) = lanes[b] ?: (0 to 1)
            val width = colWidth / laneCount
            val visibleEnd = b.end.coerceAtMost(DAY_MINUTES)
            val height = maxOf(minute * (visibleEnd - b.start), MinBlockHeight)
            val isDragged = drag?.block == b && drag?.day == dayIndex
            val canDrag = !b.fromYesterday
            val canResize = canDrag && !b.isMarker && b.end <= DAY_MINUTES
            Box(
              Modifier
                .offset(x = colWidth * dayIndex + width * lane, y = minute * b.start)
                .size(width, height)
                .padding(horizontal = 2.dp, vertical = 1.dp)
                .graphicsLayer { alpha = if (isDragged) 0.35f else 1f }
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(b) { detectTapGestures { onOpen(b) } }
                .then(
                  if (canDrag) Modifier.pointerInput(b, dayIndex) {
                    detectDragGestures(
                      onDragStart = { drag = GridDrag(b, dayIndex, DragMode.Move) },
                      onDrag = { change, amount ->
                        change.consume()
                        drag = drag?.let { it.copy(dx = it.dx + amount.x, dy = it.dy + amount.y) }
                      },
                      onDragEnd = {
                        drag?.let { d ->
                          val (day, s, e) = preview(d)
                          if (day != d.day || s != d.block.start || e != d.block.end) onMove(d.block, week[day], s, e)
                        }
                        drag = null
                      },
                      onDragCancel = { drag = null }
                    )
                  } else Modifier
                )
            ) {
              BlockFace(b, glass, height, b.start, b.end)
              if (canResize) {
                Box(
                  Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(9.dp)
                    .pointerHoverIcon(PointerIcon.Crosshair)
                    .pointerInput(b, dayIndex) {
                      detectVerticalDragGestures(
                        onDragStart = { drag = GridDrag(b, dayIndex, DragMode.ResizeEnd) },
                        onVerticalDrag = { change, amount ->
                          change.consume()
                          drag = drag?.let { it.copy(dy = it.dy + amount) }
                        },
                        onDragEnd = {
                          drag?.let { d ->
                            val (_, s, e) = preview(d)
                            if (e != d.block.end) onMove(d.block, week[d.day], s, e)
                          }
                          drag = null
                        },
                        onDragCancel = { drag = null }
                      )
                    },
                  contentAlignment = Alignment.Center
                ) {
                  Box(
                    Modifier
                      .size(width = 18.dp, height = 3.dp)
                      .clip(RoundedCornerShape(2.dp))
                      .background(pastelFor(b.session).second.copy(alpha = 0.45f))
                  )
                }
              }
            }
          }
        }
      }

      // The block being dragged, drawn where it would land.
      drag?.let { d ->
        val (day, s, e) = preview(d)
        val visibleEnd = e.coerceAtMost(DAY_MINUTES)
        val height = maxOf(minute * (visibleEnd - s), MinBlockHeight)
        Box(
          Modifier
            .zIndex(2f)
            .offset(x = colWidth * day, y = minute * s)
            .size(colWidth, height)
            .padding(horizontal = 3.dp, vertical = 1.dp)
            .shadow(10.dp, RoundedCornerShape(10.dp), clip = false)
        ) {
          BlockFace(d.block, glass, height, s, e, highlighted = true)
        }
      }
    }
  }
}

/** A session's block: pastel, with its title and time; solid with a tick once done. */
@Composable
private fun BlockFace(block: DayBlock, glass: GlassColors, height: Dp, start: Int, end: Int, highlighted: Boolean = false) {
  val (tint, ink) = pastelFor(block.session)
  val shape = RoundedCornerShape(10.dp)
  val fill = when {
    block.isMarker -> glass.selected
    block.done || highlighted -> tint
    glass.dark -> tint.copy(alpha = 0.82f)
    else -> tint.copy(alpha = 0.58f)
  }
  Box(
    Modifier
      .fillMaxSize()
      .clip(shape)
      .background(fill)
      .border(if (highlighted) 1.5.dp else 1.dp, if (highlighted) Color.White else if (block.isMarker) glass.insetRim else tint, shape)
  ) {
    Column(Modifier.padding(horizontal = 7.dp, vertical = if (height < 34.dp) 2.dp else 5.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (block.isMarker) Icon(Icons.Default.Alarm, contentDescription = "Alarm", tint = glass.text, modifier = Modifier.size(12.dp))
        if (block.done) Icon(Icons.Default.Check, contentDescription = "Done", tint = ink, modifier = Modifier.size(12.dp))
        if (repeats(block.session)) Icon(Icons.Default.Repeat, contentDescription = "Repeats", tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(11.dp))
        Text(
          block.session.topic.ifBlank { groupLabel(block.session) },
          fontSize = 11.5.sp,
          fontWeight = FontWeight.SemiBold,
          color = if (block.isMarker) glass.text else ink,
          maxLines = if (height > 60.dp) 2 else 1,
          overflow = TextOverflow.Ellipsis,
          lineHeight = 14.sp
        )
      }
      if (height >= 40.dp) {
        Spacer(Modifier.height(1.dp))
        Text(
          if (block.isMarker) clockLabel(start) else "${clockLabel(start)} – ${clockLabel(end)}",
          fontSize = 10.5.sp,
          color = ink.copy(alpha = 0.78f),
          maxLines = 1
        )
      }
    }
  }
}
