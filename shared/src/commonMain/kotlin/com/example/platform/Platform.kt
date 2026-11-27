package com.example.platform

import com.example.data.ai.llm.OnDeviceModels
import com.example.data.local.AppDatabase
import com.example.data.model.TimetableSession
import com.example.data.sync.SyncEngine
import kotlinx.coroutines.flow.SharedFlow

/**
 * Everything the shared app needs from the device it runs on. The Android app and the desktop app
 * each provide one implementation; the screens, viewmodel and data code only see this interface.
 */
interface Platform {
  val database: AppDatabase

  /** A small named key-value store (SharedPreferences on Android, a file on desktop). */
  fun prefs(name: String): KeyValueStore

  val alarms: AlarmPlatform

  /** Firebase on Android when configured; [CloudStore.None] elsewhere. */
  val cloud: CloudStore

  /** Models that run on this device, or null where there are none. */
  val onDeviceModels: OnDeviceModels?

  /** First three parts of this device's Wi-Fi IPv4 address ("192.168.1"), for finding a PC on the network. */
  fun localSubnetPrefix(): String?

  /** "Android" or "Desktop", for labels. */
  val name: String

  /** Phone-PC sync for [database]; create it once per process. */
  val syncEngine: SyncEngine
}

interface KeyValueStore {
  fun getString(key: String, default: String? = null): String?
  fun getBoolean(key: String, default: Boolean = false): Boolean
  fun getLong(key: String, default: Long = 0L): Long
  fun contains(key: String): Boolean
  fun edit(): Editor

  /** Applies [block] and saves. */
  fun edit(block: Editor.() -> Unit) {
    val editor = edit()
    editor.block()
    editor.apply()
  }

  interface Editor {
    fun putString(key: String, value: String?): Editor
    fun putBoolean(key: String, value: Boolean): Editor
    fun putLong(key: String, value: Long): Editor
    fun remove(key: String): Editor
    fun apply()
  }
}

/** Alarms for timetable sessions. */
interface AlarmPlatform {
  fun schedule(session: TimetableSession)
  fun cancel(sessionId: Long)
  fun snooze(session: TimetableSession, minutes: Int)
  /** Ring now (also used by the "Ring"/test buttons). */
  fun startRinging(session: TimetableSession)
  fun stopRinging()
  /** Session whose alarm is ringing right now, or null. */
  val ringingSessionId: Long?
  /** Tells the other device this alarm was stopped (snoozeMinutes 0) or snoozed, so it stops too. */
  fun announceStop(session: TimetableSession, snoozeMinutes: Int) {}
  /** Plays a few seconds of [sound], for choosing one in Settings. */
  fun previewSound(sound: AlarmSound) {}
}

/**
 * What an alarm plays. [DEFAULT] is the device's own (the phone's alarm tone, beeps on the PC); the
 * others are bundled public-domain (CC0) recordings from BigSoundBank.
 */
enum class AlarmSound(val label: String, val fileName: String?) {
  DEFAULT("Device default", null),
  CLASSIC_BELL("Classic bell", "alarm_classic_bell"),
  DIGITAL("Digital alarm", "alarm_digital"),
  WATCH_BEEP("Watch beeps", "alarm_watch_beep"),
  BUZZER("Buzzer", "alarm_buzzer");

  companion object {
    /** Where the choice is kept: [STORE] prefs, [KEY]. */
    const val STORE = "studypilot_ui_prefs"
    const val KEY = "alarm_sound"
    fun byName(name: String?): AlarmSound = entries.firstOrNull { it.name == name } ?: DEFAULT
  }
}

/** Alarm events from outside the app's UI (notification buttons, a scheduled alarm firing). */
sealed class AlarmEvent {
  data class Triggered(
    val sessionId: Long,
    val subjectName: String,
    val topic: String,
    val startTime: String,
    val endTime: String,
    val location: String,
    val notes: String
  ) : AlarmEvent()

  data class Dismissed(val sessionId: Long) : AlarmEvent()
  data class Snoozed(val sessionId: Long, val minutes: Int) : AlarmEvent()
}

object AlarmEventBus {
  private val _events = kotlinx.coroutines.flow.MutableSharedFlow<AlarmEvent>(extraBufferCapacity = 10)
  val events: SharedFlow<AlarmEvent> = _events

  fun emit(event: AlarmEvent) {
    _events.tryEmit(event)
  }
}

/** Optional cloud backup (Firebase/Firestore on Android). */
interface CloudStore {
  /** Id of the signed-in cloud user, or null. */
  fun currentUserId(): String?
  fun set(path: String, data: Map<String, Any?>)
  fun delete(path: String)
  fun deleteCollection(path: String)
  /** Documents in a collection; throws if the cloud can't be reached. */
  suspend fun list(path: String): List<Map<String, Any?>>
  suspend fun signInWithGoogleIdToken(idToken: String): CloudUser
  fun signOut()

  data class CloudUser(val id: String, val email: String?, val displayName: String?)

  object None : CloudStore {
    override fun currentUserId(): String? = null
    override fun set(path: String, data: Map<String, Any?>) {}
    override fun delete(path: String) {}
    override fun deleteCollection(path: String) {}
    override suspend fun list(path: String): List<Map<String, Any?>> = emptyList()
    override suspend fun signInWithGoogleIdToken(idToken: String): CloudUser =
      throw UnsupportedOperationException("Cloud sign-in isn't available here")
    override fun signOut() {}
  }
}

/** Logging that works on both platforms. */
object Log {
  fun d(tag: String, message: String, error: Throwable? = null) = platformLog('D', tag, message, error)
  fun i(tag: String, message: String, error: Throwable? = null) = platformLog('I', tag, message, error)
  fun w(tag: String, message: String, error: Throwable? = null) = platformLog('W', tag, message, error)
  fun e(tag: String, message: String, error: Throwable? = null) = platformLog('E', tag, message, error)
}

expect fun platformLog(level: Char, tag: String, message: String, error: Throwable?)
