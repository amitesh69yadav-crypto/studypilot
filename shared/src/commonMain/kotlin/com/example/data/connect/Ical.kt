package com.example.data.connect

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One occurrence of a calendar event, in this device's time zone. */
data class CalendarEvent(
  val uid: String,
  val summary: String,
  val location: String,
  val description: String,
  /** "yyyy-MM-dd" */
  val date: String,
  /** "HH:mm"; blank for an all-day event. */
  val startTime: String,
  val endTime: String,
  val allDay: Boolean
)

/**
 * Reads iCalendar (.ics) feeds, the "subscribe" links TimeEdit, Canvas, Moodle, Google Calendar and
 * most university schedules offer. Repeating events are expanded into single occurrences.
 */
object Ical {
  private const val DAY_MS = 86_400_000L

  /** Occurrences between [fromMillis] and [toMillis], sorted by time. */
  fun parse(text: String, fromMillis: Long, toMillis: Long, zone: TimeZone = TimeZone.getDefault()): List<CalendarEvent> {
    val out = mutableListOf<CalendarEvent>()
    var props: MutableList<Prop>? = null
    for (line in unfold(text)) {
      when {
        line.equals("BEGIN:VEVENT", ignoreCase = true) -> props = mutableListOf()
        line.equals("END:VEVENT", ignoreCase = true) -> {
          props?.let { runCatching { out += expand(it, fromMillis, toMillis, zone) } }
          props = null
        }
        props != null -> parseProp(line)?.let { props.add(it) }
      }
    }
    // An edited single occurrence (RECURRENCE-ID) shares the uid; keep one per uid and date.
    return out.distinctBy { it.uid + "|" + it.date + "|" + it.startTime }.sortedWith(compareBy({ it.date }, { it.startTime }))
  }

  private data class Prop(val name: String, val params: Map<String, String>, val value: String)

  /** Long lines are folded onto continuation lines that start with a space or tab. */
  private fun unfold(text: String): List<String> {
    val lines = mutableListOf<String>()
    for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
      if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) lines[lines.lastIndex] = lines.last() + raw.substring(1)
      else if (raw.isNotBlank()) lines += raw
    }
    return lines
  }

  private fun parseProp(line: String): Prop? {
    // The value starts at the first colon that isn't inside a quoted parameter.
    var quoted = false
    var colon = -1
    for (i in line.indices) {
      when (line[i]) {
        '"' -> quoted = !quoted
        ':' -> if (!quoted) { colon = i; break }
      }
    }
    if (colon <= 0) return null
    val head = line.substring(0, colon).split(';')
    val params = head.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1].trim('"') } }.toMap()
    return Prop(head[0].uppercase(), params, line.substring(colon + 1))
  }

  private fun text(v: String) = v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()

  /** Parses DATE or DATE-TIME (UTC "Z", TZID, or floating) to epoch millis; second is true for all-day. */
  private fun time(p: Prop?, zone: TimeZone): Pair<Long, Boolean>? {
    p ?: return null
    val v = p.value.trim()
    if (p.params["VALUE"] == "DATE" || v.length == 8) {
      val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone; isLenient = false }
      return runCatching { f.parse(v.take(8))!!.time to true }.getOrNull()
    }
    val utc = v.endsWith("Z")
    val tz = when {
      utc -> TimeZone.getTimeZone("UTC")
      // Unknown names (some feeds use Windows ones like "W. Europe Standard Time") fall back to GMT;
      // this device's zone is the better guess then.
      p.params["TZID"] != null -> TimeZone.getTimeZone(p.params["TZID"]!!).takeIf { it.id != "GMT" || p.params["TZID"]!!.startsWith("GMT") } ?: zone
      else -> zone
    }
    val f = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = tz; isLenient = false }
    return runCatching { f.parse(v.removeSuffix("Z").take(15))!!.time to false }.getOrNull()
  }

  private fun expand(props: List<Prop>, from: Long, to: Long, zone: TimeZone): List<CalendarEvent> {
    fun one(name: String) = props.firstOrNull { it.name == name }
    if (one("STATUS")?.value?.equals("CANCELLED", ignoreCase = true) == true) return emptyList()
    val (start, allDay) = time(one("DTSTART"), zone) ?: return emptyList()
    val end = time(one("DTEND"), zone)?.first
      ?: one("DURATION")?.value?.let { start + durationMs(it) }
      ?: (start + if (allDay) DAY_MS else 3_600_000L)
    val length = (end - start).coerceAtLeast(0L)
    val uid = one("UID")?.value?.trim().orEmpty().ifBlank { "${one("SUMMARY")?.value}|$start" }
    val excluded = props.filter { it.name == "EXDATE" }.flatMap { p -> p.value.split(',').mapNotNull { v -> time(p.copy(value = v), zone)?.first } }.toSet()

    val starts = one("RRULE")?.let { occurrences(start, it.value, from, to, zone) } ?: listOf(start)
    return starts.filter { it in from..to && it !in excluded }.map { s -> event(uid, props, s, s + length, allDay, zone) }
  }

  private fun event(uid: String, props: List<Prop>, start: Long, end: Long, allDay: Boolean, zone: TimeZone): CalendarEvent {
    fun one(name: String) = props.firstOrNull { it.name == name }?.value?.let(::text).orEmpty()
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
    val clock = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }
    return CalendarEvent(
      uid = uid,
      summary = one("SUMMARY").ifBlank { "Calendar event" },
      location = one("LOCATION"),
      description = one("DESCRIPTION"),
      date = day.format(start),
      startTime = if (allDay) "" else clock.format(start),
      endTime = if (allDay) "" else clock.format(end),
      allDay = allDay
    )
  }

  /** "PT1H30M", "P1D" and the like. */
  private fun durationMs(v: String): Long {
    var ms = 0L
    Regex("""(\d+)([WDHMS])""").findAll(v.substringAfter('P')).forEach { m ->
      val n = m.groupValues[1].toLong()
      ms += n * when (m.groupValues[2]) { "W" -> 7 * DAY_MS; "D" -> DAY_MS; "H" -> 3_600_000L; "M" -> 60_000L; else -> 1000L }
    }
    return ms
  }

  private val WEEKDAYS = mapOf("SU" to Calendar.SUNDAY, "MO" to Calendar.MONDAY, "TU" to Calendar.TUESDAY, "WE" to Calendar.WEDNESDAY, "TH" to Calendar.THURSDAY, "FR" to Calendar.FRIDAY, "SA" to Calendar.SATURDAY)

  /** Starts of a DAILY or WEEKLY rule (the kinds timetables use) up to [to]; other rules give the first only. */
  private fun occurrences(start: Long, rule: String, from: Long, to: Long, zone: TimeZone): List<Long> {
    val parts = rule.split(';').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0].uppercase() to p[1] } }.toMap()
    val freq = parts["FREQ"]?.uppercase()
    val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val count = parts["COUNT"]?.toIntOrNull()
    val until = parts["UNTIL"]?.let { u -> time(Prop("UNTIL", emptyMap(), u), zone)?.first?.let { if (u.length == 8) it + DAY_MS - 1 else it } }
    val last = minOf(to, until ?: Long.MAX_VALUE)
    if (freq != "WEEKLY" && freq != "DAILY") return listOf(start)

    val out = mutableListOf<Long>()
    val cal = Calendar.getInstance(zone).apply { timeInMillis = start }
    val days = parts["BYDAY"]?.split(',')?.mapNotNull { WEEKDAYS[it.takeLast(2).uppercase()] }?.toSet()
      ?.takeIf { it.isNotEmpty() && freq == "WEEKLY" } ?: setOf(cal.get(Calendar.DAY_OF_WEEK))
    val weekOfStart = cal.clone() as Calendar
    var produced = 0
    // Walk day by day; long rules are capped by [to], which is months away at most.
    while (cal.timeInMillis <= last && produced < (count ?: Int.MAX_VALUE) && out.size < 2000) {
      val matches = if (freq == "DAILY") {
        ((cal.timeInMillis - start) / DAY_MS) % interval == 0L
      } else {
        cal.get(Calendar.DAY_OF_WEEK) in days && weeksBetween(weekOfStart, cal) % interval == 0
      }
      if (matches) {
        produced++
        if (cal.timeInMillis >= from) out += cal.timeInMillis
      }
      cal.add(Calendar.DAY_OF_MONTH, 1)
    }
    return out
  }

  private fun weeksBetween(a: Calendar, b: Calendar): Int {
    fun weekStart(c: Calendar) = (c.clone() as Calendar).apply {
      set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
      add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7))
    }.timeInMillis
    return ((weekStart(b) - weekStart(a)) / (7 * DAY_MS)).toInt()
  }
}
