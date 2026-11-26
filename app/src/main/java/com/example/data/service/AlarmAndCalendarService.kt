package com.example.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.example.MainActivity
import com.example.data.model.TimetableSession
import com.example.data.schedule.CurrentScheduleStatus
import com.example.data.schedule.ScheduleLogic
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AlarmAndCalendarService(private val context: Context) {

  private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

  fun evaluateCurrentStatus(sessions: List<TimetableSession>, currentTime: Date = Date()): CurrentScheduleStatus =
    ScheduleLogic.evaluateCurrentStatus(sessions, currentTime)

  // --- Alarm scheduling ---
  private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

  fun computeSessionTriggerTimeMillis(session: TimetableSession, now: Long = System.currentTimeMillis()): Long =
    ScheduleLogic.computeSessionTriggerTimeMillis(session, now)

  fun scheduleSessionAlarm(session: TimetableSession) {
    if (!session.isAlarmEnabled) return
    val triggerTime = computeSessionTriggerTimeMillis(session)
    if (triggerTime <= System.currentTimeMillis()) return
    setAlarm(triggerTime, alarmIntent(session, RequestCode.TRIGGER), showIntent(session.id))
  }

  fun cancelSessionAlarm(session: TimetableSession) = cancelSessionAlarm(session.id)

  fun cancelSessionAlarm(sessionId: Long) {
    val intent = Intent(context, AlarmReceiver::class.java).apply { action = AlarmReceiver.ACTION_TRIGGER_STUDY_ALARM }
    for (kind in listOf(RequestCode.TRIGGER, RequestCode.SNOOZE)) {
      PendingIntent.getBroadcast(
        context, requestCode(sessionId, kind), intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
      )?.let {
        alarmManager?.cancel(it)
        it.cancel()
      }
    }
  }

  fun scheduleSnoozeAlarm(session: TimetableSession, minutes: Int = 5) {
    val triggerTime = System.currentTimeMillis() + minutes * 60_000L
    setAlarm(triggerTime, alarmIntent(session, RequestCode.SNOOZE), showIntent(session.id))
  }

  private fun alarmIntent(session: TimetableSession, kind: RequestCode): PendingIntent {
    val intent = Intent(context, AlarmReceiver::class.java).apply {
      action = AlarmReceiver.ACTION_TRIGGER_STUDY_ALARM
      AlarmReceiver.putSessionExtras(this, session)
    }
    return PendingIntent.getBroadcast(
      context, requestCode(session.id, kind), intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun showIntent(sessionId: Long): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra(MainActivity.EXTRA_RINGING_SESSION_ID, sessionId)
    }
    return PendingIntent.getActivity(
      context, requestCode(sessionId, RequestCode.TRIGGER), intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun setAlarm(triggerTime: Long, operation: PendingIntent, show: PendingIntent) {
    val manager = alarmManager ?: return
    try {
      // AlarmClock alarms are exact, survive Doze and show in the status bar.
      manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerTime, show), operation)
    } catch (_: SecurityException) {
      // Exact alarms not permitted: fall back to an inexact alarm rather than none.
      manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, operation)
    }
  }

  fun stopAlarmSound() {
    AlarmRingingService.stopRinging(context)
  }

  // --- Google Calendar Sync Helpers ---

  /**
   * Generates a Google Calendar web & app template intent or URL.
   * This guarantees seamless synchronization and opening in Google Calendar.
   */
  fun createGoogleCalendarInsertIntent(session: TimetableSession): Intent {
    val (startH, startM) = ScheduleLogic.parseTime(session.startTime)

    val dateParts = session.date.split("-")
    val year = dateParts.getOrNull(0)?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
    val month = (dateParts.getOrNull(1)?.toIntOrNull() ?: 1) - 1
    val day = dateParts.getOrNull(2)?.toIntOrNull() ?: 1

    val startCal = Calendar.getInstance().apply {
      set(year, month, day, startH, startM, 0)
    }
    val endCal = Calendar.getInstance().apply {
      if (session.endTime.isNotBlank()) {
        val (endH, endM) = ScheduleLogic.parseTime(session.endTime)
        set(year, month, day, endH, endM, 0)
      } else {
        timeInMillis = startCal.timeInMillis
        add(Calendar.MINUTE, 30)
      }
    }

    return Intent(Intent.ACTION_INSERT).apply {
      data = CalendarContract.Events.CONTENT_URI
      putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startCal.timeInMillis)
      putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endCal.timeInMillis)
      putExtra(CalendarContract.Events.TITLE, if (session.subjectName.isNotBlank()) "${session.subjectName}: ${session.topic}" else session.topic)
      putExtra(CalendarContract.Events.DESCRIPTION, "StudyPilot Session\nNotes: ${session.notes}")
      putExtra(CalendarContract.Events.EVENT_LOCATION, session.location)
      putExtra(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
      putExtra(CalendarContract.Reminders.MINUTES, 0)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  fun getGoogleCalendarWebUrl(session: TimetableSession): String = ScheduleLogic.getGoogleCalendarWebUrl(session)

  enum class RequestCode { TRIGGER, SNOOZE, DISMISS, SNOOZE_ACTION }

  companion object {
    /** Distinct PendingIntent request codes per session and purpose. */
    fun requestCode(sessionId: Long, kind: RequestCode): Int =
      ((sessionId and 0x0FFFFFFF) * 4 + kind.ordinal).toInt()
  }
}
