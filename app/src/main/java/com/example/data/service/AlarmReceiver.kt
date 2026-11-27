package com.example.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.platform.AlarmEvent
import com.example.platform.AlarmEventBus
import com.example.platform.AndroidPlatform
import com.example.data.model.TimetableSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the app's own alarm PendingIntents. Not exported. */
class AlarmReceiver : BroadcastReceiver() {

  companion object {
    const val ACTION_TRIGGER_STUDY_ALARM = "com.example.ACTION_TRIGGER_STUDY_ALARM"
    const val ACTION_DISMISS_ALARM = "com.example.ACTION_DISMISS_ALARM"
    const val ACTION_SNOOZE_ALARM = "com.example.ACTION_SNOOZE_ALARM"

    const val EXTRA_SESSION_ID = "extra_session_id"
    const val EXTRA_SUBJECT_NAME = "extra_subject_name"
    const val EXTRA_TOPIC = "extra_topic"
    const val EXTRA_START_TIME = "extra_start_time"
    const val EXTRA_END_TIME = "extra_end_time"
    const val EXTRA_LOCATION = "extra_location"
    const val EXTRA_NOTES = "extra_notes"

    const val SNOOZE_MINUTES = 5

    fun putSessionExtras(intent: Intent, session: TimetableSession) {
      intent.putExtra(EXTRA_SESSION_ID, session.id)
      intent.putExtra(EXTRA_SUBJECT_NAME, session.subjectName)
      intent.putExtra(EXTRA_TOPIC, session.topic)
      intent.putExtra(EXTRA_START_TIME, session.startTime)
      intent.putExtra(EXTRA_END_TIME, session.endTime)
      intent.putExtra(EXTRA_LOCATION, session.location)
      intent.putExtra(EXTRA_NOTES, session.notes)
    }

    fun sessionFromExtras(intent: Intent): TimetableSession = TimetableSession(
      id = intent.getLongExtra(EXTRA_SESSION_ID, 0L),
      subjectId = 0L,
      subjectName = intent.getStringExtra(EXTRA_SUBJECT_NAME) ?: "Alarm",
      topic = intent.getStringExtra(EXTRA_TOPIC) ?: "Scheduled alarm",
      date = "",
      startTime = intent.getStringExtra(EXTRA_START_TIME) ?: "",
      endTime = intent.getStringExtra(EXTRA_END_TIME) ?: "",
      location = intent.getStringExtra(EXTRA_LOCATION) ?: "",
      notes = intent.getStringExtra(EXTRA_NOTES) ?: "",
      isAlarmEnabled = true
    )
  }

  override fun onReceive(context: Context, intent: Intent) {
    val session = sessionFromExtras(intent)
    when (intent.action) {
      ACTION_TRIGGER_STUDY_ALARM -> {
        AlarmRingingService.startRinging(context, session)
        rescheduleNextOccurrence(context, session.id)
      }

      ACTION_DISMISS_ALARM -> {
        AlarmRingingService.stopRinging(context)
        AlarmRelay.send(context, session, 0)
        AlarmEventBus.emit(AlarmEvent.Dismissed(session.id))
      }

      ACTION_SNOOZE_ALARM -> {
        AlarmRingingService.stopRinging(context)
        AlarmAndCalendarService(context).scheduleSnoozeAlarm(session, SNOOZE_MINUTES)
        AlarmRelay.send(context, session, SNOOZE_MINUTES)
        AlarmEventBus.emit(AlarmEvent.Snoozed(session.id, SNOOZE_MINUTES))
      }
    }
  }

  /** Recurring sessions need their next weekly alarm set once this one has fired. */
  private fun rescheduleNextOccurrence(context: Context, sessionId: Long) {
    if (sessionId <= 0L) return
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val stored = AndroidPlatform.get(context).database.studyPilotDao().getSessionById(sessionId)
        if (stored != null && stored.isAlarmEnabled && stored.recurringDays.isNotBlank()) {
          AlarmAndCalendarService(context).scheduleSessionAlarm(stored)
        }
      } catch (e: Exception) {
        android.util.Log.w("AlarmReceiver", "Could not reschedule session $sessionId", e)
      } finally {
        pending.finish()
      }
    }
  }
}

/** Alarms are cleared on reboot and can drift on clock changes; restore them for the active account. */
class AlarmRescheduleReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    // The receiver is exported for system broadcasts, so ignore anything else another app sends.
    if (intent.action !in RESCHEDULE_ACTIONS) return
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val activeUserId = context.getSharedPreferences("studypilot_auth_prefs", Context.MODE_PRIVATE)
          .getString("active_user_id", null) ?: "guest_user"
        val service = AlarmAndCalendarService(context)
        AndroidPlatform.get(context).database.studyPilotDao()
          .getSessionsListForUser(activeUserId)
          .filter { it.isAlarmEnabled }
          .forEach { service.scheduleSessionAlarm(it) }
      } catch (e: Exception) {
        android.util.Log.w("AlarmRescheduleReceiver", "Could not restore alarms", e)
      } finally {
        pending.finish()
      }
    }
  }
}

private val RESCHEDULE_ACTIONS = setOf(
  Intent.ACTION_BOOT_COMPLETED,
  Intent.ACTION_MY_PACKAGE_REPLACED,
  Intent.ACTION_TIME_CHANGED,
  Intent.ACTION_TIMEZONE_CHANGED,
  "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
)
