package com.example.ui.orbit

import com.example.data.model.TimetableSession
import com.example.data.schedule.ScheduleLogic
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Minutes in a day; the orbit is one turn of this. */
const val DAY_MINUTES = 24 * 60

/** Shortest a block can be made by dragging. */
const val MIN_BLOCK_MINUTES = 15

/**
 * A session placed on one day: its minutes from midnight, and whether it was done. A session that runs
 * past midnight (sleep, 23:00 to 07:00) ends after [DAY_MINUTES].
 */
data class DayBlock(
  val session: TimetableSession,
  val start: Int,
  val end: Int,
  /** An alarm with no end time: drawn as a short marker. */
  val isMarker: Boolean,
  /** The morning part of a session that started the day before and ran past midnight. */
  val fromYesterday: Boolean = false
) {
  val done: Boolean get() = session.status == "COMPLETED"
  val minutes: Int get() = end - start
}

private val dayFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/** Whether [session] happens on [date] ("yyyy-MM-dd"): on its own date, or on a weekday it repeats on. */
fun occursOn(session: TimetableSession, date: String): Boolean {
  if (session.date == date) return true
  val days = ScheduleLogic.parseRecurringDays(session.recurringDays)
  if (days.isEmpty()) return false
  // A repeating session starts on its date (or always, when it has none).
  if (session.date.isNotBlank() && date < session.date) return false
  val weekday = runCatching { Calendar.getInstance().apply { time = dayFormat.parse(date)!! }.get(Calendar.DAY_OF_WEEK) }.getOrNull()
  return weekday != null && weekday in days
}

/** "09:30" to 570; null when it isn't a time. */
fun minutesOf(time: String): Int? {
  if (time.isBlank()) return null
  val (h, m) = ScheduleLogic.parseTime(time)
  return (h * 60 + m).coerceIn(0, DAY_MINUTES)
}

/** 570 to "09:30". */
fun timeOf(minutes: Int): String {
  val m = minutes.coerceIn(0, DAY_MINUTES - 1)
  return "%02d:%02d".format(m / 60, m % 60)
}

/** The day's sessions as blocks, earliest first. */
fun blocksOn(sessions: List<TimetableSession>, date: String): List<DayBlock> =
  sessions.filter { occursOn(it, date) }.mapNotNull { s ->
    val start = (minutesOf(s.startTime) ?: return@mapNotNull null).coerceAtMost(DAY_MINUTES - 1)
    val endRaw = minutesOf(s.endTime)
    when {
      // No end (an alarm), or an end equal to the start: a short marker.
      endRaw == null || endRaw == start -> DayBlock(s, start, start + MIN_BLOCK_MINUTES, isMarker = true)
      endRaw < start -> DayBlock(s, start, endRaw + DAY_MINUTES, isMarker = false)
      else -> DayBlock(s, start, endRaw, isMarker = false)
    }
  }.sortedBy { it.start }

/**
 * Blocks for a day column in the week grid: the day's own, plus the morning part of anything that ran
 * past midnight the day before. Blocks keep their real end, so draw them cut at [DAY_MINUTES].
 */
fun columnBlocksOn(sessions: List<TimetableSession>, date: String): List<DayBlock> {
  val carried = blocksOn(sessions, shiftDate(date, -1))
    .filter { it.end > DAY_MINUTES }
    .map { it.copy(start = 0, end = it.end - DAY_MINUTES, fromYesterday = true) }
  return (carried + blocksOn(sessions, date)).sortedBy { it.start }
}

/** The minute of the day it is now. */
fun nowMinutes(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

/** The block happening at [minute], the shortest when several are. */
fun blockAt(blocks: List<DayBlock>, minute: Int): DayBlock? =
  blocks.filter { b -> !b.isMarker && (minute in b.start until b.end || minute + DAY_MINUTES in b.start until b.end) }
    .minByOrNull { it.minutes }

/** "7h 30m", "45m", "2h". */
fun formatDuration(minutes: Int): String {
  val h = minutes / 60
  val m = minutes % 60
  return when {
    h == 0 -> "${m}m"
    m == 0 -> "${h}h"
    else -> "${h}h ${m}m"
  }
}

/** 570 to "9:30 AM". */
fun clockLabel(minutes: Int): String {
  val m = ((minutes % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES
  val h = m / 60
  val hour12 = if (h % 12 == 0) 12 else h % 12
  return "%d:%02d %s".format(hour12, m % 60, if (h < 12) "AM" else "PM")
}

/** What a block is called in the legend and on the ring: its subject, or its kind of routine. */
fun groupLabel(session: TimetableSession): String = when (session.sessionCategory) {
  "FOOD" -> "Meals"
  "SLEEP" -> "Sleep"
  "EXERCISE" -> "Exercise"
  "PERSONAL" -> "Personal"
  else -> session.subjectName.ifBlank { if (session.endTime.isBlank()) "Alarms" else "Study" }
}

/** One legend line: a subject or routine, how long it takes up, and its share of the day. */
data class LegendRow(val label: String, val sample: TimetableSession, val minutes: Int) {
  val percentOfDay: Double get() = minutes * 100.0 / DAY_MINUTES
}

/** Legend for [blocks], largest first; markers (alarms) count as nothing. */
fun legendFor(blocks: List<DayBlock>): List<LegendRow> =
  blocks.groupBy { groupLabel(it.session) }
    .map { (label, group) -> LegendRow(label, group.first().session, group.filterNot { it.isMarker }.sumOf { it.minutes }) }
    .sortedByDescending { it.minutes }

/** Total time planned in [blocks], overlaps counted once, on the 24-hour circle. */
fun coveredMinutes(blocks: List<DayBlock>): Int {
  // Anything past midnight is split into its two parts on the same day.
  val spans = blocks.filterNot { it.isMarker }.flatMap { b ->
    if (b.end <= DAY_MINUTES) listOf(b.start to b.end)
    else listOf(b.start to DAY_MINUTES, 0 to (b.end - DAY_MINUTES).coerceAtMost(b.start))
  }.sortedBy { it.first }
  var total = 0
  var reach = 0
  for ((start, end) in spans) {
    val from = maxOf(start, reach)
    if (end > from) {
      total += end - from
      reach = end
    }
  }
  return total
}

/** Rounds to the nearest [step] minutes. */
fun snap(minutes: Int, step: Int = 5): Int = Math.floorDiv(minutes + step / 2, step) * step

/** How a drag changes a block. */
enum class DragMode { Move, ResizeStart, ResizeEnd }

/**
 * The block's new start and end after dragging by [delta] minutes, at least [MIN_BLOCK_MINUTES] long
 * and snapped to [step] minutes. With [wrap] (the ring) a block moves round the clock and can stretch to
 * almost a full day; without it (a day column) it starts within the day and may run past midnight.
 */
fun dragged(block: DayBlock, mode: DragMode, delta: Int, step: Int = 5, wrap: Boolean = false): Pair<Int, Int> {
  val length = block.end - block.start
  return when (mode) {
    DragMode.Move -> {
      val moved = snap(block.start + delta, step)
      // On the ring a block can go round past midnight; in a day column it can start no later than the last slot.
      val start = if (wrap) Math.floorMod(moved, DAY_MINUTES) else moved.coerceIn(0, DAY_MINUTES - MIN_BLOCK_MINUTES)
      start to start + length
    }
    DragMode.ResizeStart ->
      snap(block.start + delta, step).coerceIn(maxOf(0, block.end - DAY_MINUTES + MIN_BLOCK_MINUTES), block.end - MIN_BLOCK_MINUTES) to block.end
    DragMode.ResizeEnd -> {
      val latest = if (wrap) block.start + DAY_MINUTES - MIN_BLOCK_MINUTES else maxOf(DAY_MINUTES, block.end)
      block.start to snap(block.end + delta, step).coerceIn(block.start + MIN_BLOCK_MINUTES, latest)
    }
  }
}

/** The block's session with its times set to [start] and [end] minutes; an end past midnight wraps. */
fun retimed(block: DayBlock, start: Int, end: Int): TimetableSession = block.session.copy(
  startTime = timeOf(Math.floorMod(start, DAY_MINUTES)),
  endTime = if (block.isMarker) block.session.endTime else timeOf(Math.floorMod(end, DAY_MINUTES))
)

/** Columns for blocks that overlap in a day column: each block's lane and how many lanes its group uses. */
fun lanesFor(blocks: List<DayBlock>): Map<DayBlock, Pair<Int, Int>> {
  val result = LinkedHashMap<DayBlock, Pair<Int, Int>>()
  val sorted = blocks.sortedWith(compareBy<DayBlock> { it.start }.thenByDescending { it.end })
  var group = mutableListOf<Pair<DayBlock, Int>>()
  var laneEnds = mutableListOf<Int>()
  var groupEnd = -1
  fun flush() {
    group.forEach { (b, lane) -> result[b] = lane to laneEnds.size }
    group = mutableListOf()
    laneEnds = mutableListOf()
  }
  for (b in sorted) {
    val end = b.end.coerceAtMost(DAY_MINUTES)
    if (b.start >= groupEnd) {
      flush()
      groupEnd = -1
    }
    val lane = laneEnds.indexOfFirst { it <= b.start }.let { if (it >= 0) it else laneEnds.size }
    if (lane == laneEnds.size) laneEnds.add(end) else laneEnds[lane] = end
    group.add(b to lane)
    groupEnd = maxOf(groupEnd, end)
  }
  flush()
  return result
}

/** The seven dates (Monday first) of the week containing [date]. */
fun weekOf(date: String): List<String> {
  val cal = Calendar.getInstance().apply {
    firstDayOfWeek = Calendar.MONDAY
    time = runCatching { dayFormat.parse(date) }.getOrNull() ?: Date()
    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
  }
  // set(DAY_OF_WEEK) can land on the following Monday for a Sunday; step back a week if so.
  if (dayFormat.format(cal.time) > date && date.isNotBlank()) cal.add(Calendar.DAY_OF_YEAR, -7)
  return (0 until 7).map { i -> dayFormat.format((cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }.time) }
}

/** [date] moved by [days]. */
fun shiftDate(date: String, days: Int): String {
  val cal = Calendar.getInstance().apply { time = runCatching { dayFormat.parse(date) }.getOrNull() ?: Date() }
  cal.add(Calendar.DAY_OF_YEAR, days)
  return dayFormat.format(cal.time)
}

fun todayDate(): String = dayFormat.format(Date())

/** "MON", "Nov 23" style labels for a date. */
fun weekdayShort(date: String): String = format(date, "EEE").uppercase(Locale.getDefault())
fun monthDay(date: String): String = format(date, "MMM d")
fun longDate(date: String): String = format(date, "EEEE, MMMM d")

private fun format(date: String, pattern: String): String =
  runCatching { SimpleDateFormat(pattern, Locale.getDefault()).format(dayFormat.parse(date)!!) }.getOrDefault(date)

/** "Nov 23 – Nov 29, 2026" for a week. */
fun weekRangeLabel(week: List<String>): String =
  "${monthDay(week.first())} – ${monthDay(week.last())}, ${week.last().take(4)}"
