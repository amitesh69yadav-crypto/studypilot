package com.example.data.schedule

import com.example.data.model.TimetableSession
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CurrentScheduleStatus(
  val state: ScheduleState,
  val activeSession: TimetableSession? = null,
  val nextSession: TimetableSession? = null,
  val minutesRemaining: Int = 0,
  val minutesUntilNext: Int = 0,
  val progressFraction: Float = 0f,
  val formattedStatusText: String = "",
  val suggestionText: String = ""
)

enum class ScheduleState {
  ACTIVE_NOW,
  UPCOMING_NEXT,
  FREE_TIME,
  DAY_COMPLETE
}

/** Pure schedule calculations shared by the phone and desktop apps. */
object ScheduleLogic {

  private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

  // --- Real-Time "What Should I Do Now" Calculation ---
  fun evaluateCurrentStatus(sessions: List<TimetableSession>, currentTime: Date = Date()): CurrentScheduleStatus {
    val todayStr = dateFormat.format(currentTime)
    val todaySessions = sessions.filter { it.date == todayStr }
      .sortedBy { it.startTime }

    if (todaySessions.isEmpty()) {
      return CurrentScheduleStatus(
        state = ScheduleState.FREE_TIME,
        formattedStatusText = "No classes scheduled for today",
        suggestionText = "Tap 'Plan Today' to create your daily timetable or start a self-directed study sprint with the AI Tutor."
      )
    }

    val cal = Calendar.getInstance().apply { time = currentTime }
    val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

    // Check for currently active session or start-time alarm
    for (session in todaySessions) {
      val (startH, startM) = parseTime(session.startTime)
      val startTotal = startH * 60 + startM

      if (session.endTime.isBlank()) {
        if (currentMinutes == startTotal) {
          return CurrentScheduleStatus(
            state = ScheduleState.ACTIVE_NOW,
            activeSession = session,
            minutesRemaining = 0,
            progressFraction = 1f,
            formattedStatusText = "${session.subjectName.ifBlank { "Alarm" }}: ${session.topic}",
            suggestionText = "Alarm scheduled for ${session.startTime}."
          )
        }
        continue
      }

      val (endH, endM) = parseTime(session.endTime)
      val endTotal = endH * 60 + endM

      if (currentMinutes in startTotal until endTotal) {
        val remaining = endTotal - currentMinutes
        val elapsed = currentMinutes - startTotal
        val totalDuration = (endTotal - startTotal).coerceAtLeast(1)
        val progress = (elapsed.toFloat() / totalDuration).coerceIn(0f, 1f)

        return CurrentScheduleStatus(
          state = ScheduleState.ACTIVE_NOW,
          activeSession = session,
          minutesRemaining = remaining,
          progressFraction = progress,
          formattedStatusText = "${session.subjectName.ifBlank { "Alarm" }}: ${session.topic}",
          suggestionText = "Currently in progress ($remaining min left). Stay focused or ask AI Tutor for rapid concept explanations!"
        )
      }
    }

    // Check for upcoming next session today
    val nextSession = todaySessions.firstOrNull { session ->
      val (startH, startM) = parseTime(session.startTime)
      val startTotal = startH * 60 + startM
      startTotal > currentMinutes
    }

    if (nextSession != null) {
      val (startH, startM) = parseTime(nextSession.startTime)
      val startTotal = startH * 60 + startM
      val untilNext = startTotal - currentMinutes

      return CurrentScheduleStatus(
        state = ScheduleState.UPCOMING_NEXT,
        nextSession = nextSession,
        minutesUntilNext = untilNext,
        formattedStatusText = "Next up at ${nextSession.startTime}: ${nextSession.subjectName.ifBlank { nextSession.topic }}",
        suggestionText = if (untilNext <= 15) {
          val locationHint = if (nextSession.location.isNotBlank()) " Room: ${nextSession.location}." else ""
          "Starts in $untilNext minutes!$locationHint"
        } else {
          "$untilNext minutes until scheduled time."
        }
      )
    }

    return CurrentScheduleStatus(
      state = ScheduleState.DAY_COMPLETE,
      formattedStatusText = "All scheduled sessions for today are finished!",
      suggestionText = "Great effort today! Take a break, review flash topics in analytics, or plan tomorrow's schedule."
    )
  }

  fun parseTime(timeStr: String): Pair<Int, Int> {
    return try {
      val trimmed = timeStr.trim().uppercase(Locale.getDefault())
      val isPm = trimmed.contains("PM")
      val isAm = trimmed.contains("AM")
      val cleanTime = trimmed.replace("AM", "").replace("PM", "").trim()
      val delimiter = if (cleanTime.contains(":")) ":" else if (cleanTime.contains(".")) "." else " "
      val parts = cleanTime.split(delimiter).filter { it.isNotBlank() }
      var hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
      val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
      if (isPm && hour < 12) hour += 12
      if (isAm && hour == 12) hour = 0
      Pair(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    } catch (e: Exception) {
      Pair(9, 0)
    }
  }

  /**
   * Next time this session's alarm should ring, at or after [now].
   *
   * One-off sessions ring at their date + start time (or today/tomorrow when undated).
   * Sessions with [TimetableSession.recurringDays] ("Mon,Wed,Fri") ring on the next matching
   * weekday on or after their date. Returns a time in the past when a one-off session is over.
   */
  fun computeSessionTriggerTimeMillis(session: TimetableSession, now: Long = System.currentTimeMillis()): Long {
    val (startH, startM) = parseTime(session.startTime)
    val cal = Calendar.getInstance().apply {
      timeInMillis = now
      set(Calendar.HOUR_OF_DAY, startH)
      set(Calendar.MINUTE, startM)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val dateParts = session.date.trim().split("-").mapNotNull { it.toIntOrNull() }
    val hasDate = dateParts.size == 3
    if (hasDate) cal.set(dateParts[0], dateParts[1] - 1, dateParts[2])

    val days = parseRecurringDays(session.recurringDays)
    if (days.isEmpty()) {
      if (!hasDate && cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
      return cal.timeInMillis
    }

    // Recurring: start from the later of the session date and today, then walk forward a week.
    if (cal.timeInMillis < now) {
      val today = Calendar.getInstance().apply { timeInMillis = now }
      cal.set(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH))
    }
    repeat(8) {
      if (cal.get(Calendar.DAY_OF_WEEK) in days && cal.timeInMillis > now) return cal.timeInMillis
      cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    return cal.timeInMillis
  }

  fun parseRecurringDays(raw: String): Set<Int> {
    if (raw.isBlank()) return emptySet()
    val byPrefix = mapOf(
      "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY,
      "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY,
      "sat" to Calendar.SATURDAY
    )
    return raw.split(",", " ", "/").mapNotNull { byPrefix[it.trim().lowercase(Locale.ROOT).take(3)] }.toSet()
  }

  /**
   * Generates a Google Calendar URL that directly creates the event in Google Calendar online/app.
   */
  fun getGoogleCalendarWebUrl(session: TimetableSession): String {
    return try {
      val (startH, startM) = parseTime(session.startTime)

      val dateParts = session.date.split("-")
      val year = dateParts.getOrNull(0)?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
      val month = (dateParts.getOrNull(1)?.toIntOrNull() ?: 1) - 1
      val day = dateParts.getOrNull(2)?.toIntOrNull() ?: 1

      val utcFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      }

      val startCal = Calendar.getInstance().apply {
        set(year, month, day, startH, startM, 0)
      }
      val endCal = Calendar.getInstance().apply {
        if (session.endTime.isNotBlank()) {
          val (endH, endM) = parseTime(session.endTime)
          set(year, month, day, endH, endM, 0)
        } else {
          timeInMillis = startCal.timeInMillis
          add(Calendar.MINUTE, 30)
        }
      }

      val startStr = utcFormat.format(startCal.time)
      val endStr = utcFormat.format(endCal.time)

      val rawTitle = if (session.subjectName.isNotBlank()) "${session.subjectName}: ${session.topic}" else session.topic
      val title = URLEncoder.encode(rawTitle, "UTF-8")
      val details = URLEncoder.encode("StudyPilot AI Session\nNotes: ${session.notes}", "UTF-8")
      val location = URLEncoder.encode(session.location, "UTF-8")

      "https://calendar.google.com/calendar/render?action=TEMPLATE&text=$title&dates=$startStr/$endStr&details=$details&location=$location"
    } catch (e: Exception) {
      "https://calendar.google.com/calendar"
    }
  }
}
