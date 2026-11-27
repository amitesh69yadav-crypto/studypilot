package com.example.data.service

import android.content.Context
import com.example.data.model.TimetableSession
import com.example.data.sync.alarmKey
import com.example.platform.AlarmEvent
import com.example.platform.AlarmEventBus
import com.example.platform.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps a ringing alarm in step with the paired PC: a stop or snooze here is sent to the PC, and
 * while the phone rings it checks whether the PC stopped the same alarm.
 */
object AlarmRelay {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private const val CHECK_EVERY_MS = 2_000L
  /** A stop counts if the PC heard of it no earlier than this before the phone started ringing. */
  private const val CLOCK_SLACK_MS = 30_000L

  /** Tells the PC this alarm was stopped (snoozeMinutes 0) or snoozed. Fire and forget. */
  fun send(context: Context, session: TimetableSession, snoozeMinutes: Int) {
    val app = context.applicationContext
    scope.launch {
      runCatching { AndroidPlatform.get(app).syncClient.sendAlarmStop(key(session), snoozeMinutes) }
    }
  }

  /** While [session] rings, stops it here once the PC reports it stopped. Cancel the job when ringing ends. */
  fun watch(context: Context, session: TimetableSession): Job {
    val app = context.applicationContext
    val key = key(session)
    val startedAt = System.currentTimeMillis()
    return scope.launch {
      val client = AndroidPlatform.get(app).syncClient
      while (isActive) {
        val stop = runCatching { client.latestAlarmStop() }.getOrNull()
        if (stop != null && stop.key == key && stop.at >= startedAt - CLOCK_SLACK_MS) {
          withContext(Dispatchers.Main) {
            AlarmRingingService.stopRinging(app)
            if (stop.snoozeMinutes > 0) {
              AlarmAndCalendarService(app).scheduleSnoozeAlarm(session, stop.snoozeMinutes)
              AlarmEventBus.emit(AlarmEvent.Snoozed(session.id, stop.snoozeMinutes))
            } else {
              AlarmEventBus.emit(AlarmEvent.Dismissed(session.id))
            }
          }
          break
        }
        delay(CHECK_EVERY_MS)
      }
    }
  }

  private fun key(session: TimetableSession) = alarmKey(session.subjectName, session.topic, session.startTime)
}
