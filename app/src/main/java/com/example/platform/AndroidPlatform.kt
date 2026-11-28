package com.example.platform

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import androidx.room.Room
import com.example.data.ai.llm.OnDeviceModels
import com.example.data.ai.llm.PhoneModels
import com.example.data.local.AppDatabase
import com.example.data.model.TimetableSession
import com.example.data.sync.SyncClient
import com.example.data.sync.SyncEngine
import com.example.data.service.AlarmAndCalendarService
import com.example.data.service.AlarmRingingService
import java.net.Inet4Address

/** The phone side of [Platform]. One per process, so the database and model downloads are shared. */
class AndroidPlatform private constructor(private val context: Context) : Platform {

  override val name = "Android"

  init {
    // Course PDFs are read on the phone for "College notes".
    com.example.data.docs.PdfSupport.init(context)
  }

  override val database: AppDatabase by lazy {
    AppDatabase.configure(Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.FILE_NAME))
  }

  override fun prefs(name: String): KeyValueStore =
    SharedPreferencesStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))

  override val alarms: AlarmPlatform by lazy { AndroidAlarms(context) }

  override val cloud: CloudStore by lazy { FirestoreCloudStore() }

  override val onDeviceModels: OnDeviceModels by lazy { PhoneModels(context) }

  override val syncEngine: SyncEngine by lazy { SyncEngine(database, prefs("studypilot_auth_prefs")) }

  /** Talks to the paired PC. */
  val syncClient: SyncClient by lazy {
    // The name the owner gave the phone ("Galaxy S25"), else the model code.
    val name = runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
    SyncClient(syncEngine, prefs("studypilot_sync"), name?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL ?: "Android phone")
  }

  override fun localSubnetPrefix(): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    val ip = props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
      ?.hostAddress ?: return null
    return ip.substringBeforeLast('.')
  }

  companion object {
    @Volatile private var instance: AndroidPlatform? = null

    /** Tests get a fresh Application each time, so a new one replaces the old instance. */
    fun get(context: Context): AndroidPlatform {
      val app = context.applicationContext
      instance?.takeIf { it.context === app }?.let { return it }
      return synchronized(this) {
        instance?.takeIf { it.context === app } ?: AndroidPlatform(app).also { instance = it }
      }
    }
  }
}

private class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
  override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
  override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
  override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
  override fun contains(key: String): Boolean = prefs.contains(key)
  override fun edit(): KeyValueStore.Editor = object : KeyValueStore.Editor {
    private val editor = prefs.edit()
    override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
    override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
    override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }
    override fun remove(key: String) = apply { editor.remove(key) }
    override fun apply() = editor.apply()
  }
}

private class AndroidAlarms(private val context: Context) : AlarmPlatform {
  private val service = AlarmAndCalendarService(context)
  override fun schedule(session: TimetableSession) = service.scheduleSessionAlarm(session)
  override fun cancel(sessionId: Long) = service.cancelSessionAlarm(sessionId)
  override fun snooze(session: TimetableSession, minutes: Int) = service.scheduleSnoozeAlarm(session, minutes)
  override fun startRinging(session: TimetableSession) = AlarmRingingService.startRinging(context, session)
  override fun stopRinging() = service.stopAlarmSound()
  override fun announceStop(session: TimetableSession, snoozeMinutes: Int) =
    com.example.data.service.AlarmRelay.send(context, session, snoozeMinutes)
  override val ringingSessionId: Long? get() = AlarmRingingService.ringingSessionId
  override fun previewSound(sound: com.example.platform.AlarmSound) =
    com.example.data.service.AlarmSoundManager.preview(context, sound)
}
