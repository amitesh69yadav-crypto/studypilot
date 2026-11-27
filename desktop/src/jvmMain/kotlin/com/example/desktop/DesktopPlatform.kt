package com.example.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.ai.llm.OnDeviceModels
import com.example.data.local.AppDatabase
import com.example.data.model.TimetableSession
import com.example.data.schedule.ScheduleLogic
import com.example.data.sync.SyncEngine
import com.example.data.sync.SyncHost
import com.example.platform.AlarmEvent
import com.example.platform.AlarmEventBus
import com.example.platform.AlarmPlatform
import com.example.platform.CloudStore
import com.example.platform.KeyValueStore
import com.example.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

/** The PC side of [Platform]: files under %APPDATA%\StudyPilot, desktop alarms, no cloud. */
class DesktopPlatform : Platform {

  override val name = "Desktop"

  val dataDir: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "StudyPilot").apply { mkdirs() }

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override val database: AppDatabase by lazy {
    AppDatabase.configure(
      Room.databaseBuilder<AppDatabase>(name = File(dataDir, AppDatabase.FILE_NAME).absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
    )
  }

  private val stores = ConcurrentHashMap<String, KeyValueStore>()
  override fun prefs(name: String): KeyValueStore = stores.getOrPut(name) { PropertiesStore(File(dataDir, "$name.properties")) }

  val desktopAlarms = DesktopAlarms(scope) {
    com.example.platform.AlarmSound.byName(prefs(com.example.platform.AlarmSound.STORE).getString(com.example.platform.AlarmSound.KEY, null))
  }
  override val alarms: AlarmPlatform get() = desktopAlarms

  override val cloud: CloudStore = CloudStore.None

  override val onDeviceModels: OnDeviceModels? = null

  override fun localSubnetPrefix(): String? = lanAddresses().firstOrNull()?.substringBeforeLast('.')

  override val syncEngine: SyncEngine by lazy { SyncEngine(database, prefs("studypilot_auth_prefs")) }

  val syncHost: SyncHost by lazy {
    val hostName = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "PC"
    SyncHost(syncEngine, prefs("studypilot_sync"), hostName)
  }

  init {
    // The AI runs on this PC, so point the tutor at the local Ollama by default.
    val ai = prefs("studypilot_ai_config")
    if (!ai.contains("pc_ollama_url")) ai.edit { putString("pc_ollama_url", "http://127.0.0.1:11434") }
    // Guest data comes from the phone; demo data here would show up twice after syncing.
    val seeding = prefs("studypilot_seeding_prefs")
    if (!seeding.contains("has_seeded_sample_data_guest_user")) seeding.edit { putBoolean("has_seeded_sample_data_guest_user", true) }
  }

  companion object {
    /** This PC's IPv4 addresses on the local network, home Wi-Fi/Ethernet ranges first. */
    fun lanAddresses(): List<String> {
      val all = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
          .filter { it.isUp && !it.isLoopback && !it.isVirtual }
          .filterNot { iface -> listOf("vEthernet", "VirtualBox", "VMware", "WSL", "Hyper-V").any { iface.displayName.contains(it, true) } }
          .flatMap { it.inetAddresses.toList() }
          .filterIsInstance<Inet4Address>()
          .filter { it.isSiteLocalAddress }
          .map { it.hostAddress }
      }.getOrDefault(emptyList())
      return all.distinct().sortedBy { ip ->
        when {
          ip.startsWith("192.168.") -> 0
          ip.startsWith("10.") -> 1
          else -> 2
        }
      }
    }
  }
}

/** A [KeyValueStore] kept in a .properties file. */
private class PropertiesStore(private val file: File) : KeyValueStore {
  private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

  override fun getString(key: String, default: String?): String? = synchronized(props) { props.getProperty(key) ?: default }
  override fun getBoolean(key: String, default: Boolean): Boolean = getString(key, null)?.toBooleanStrictOrNull() ?: default
  override fun getLong(key: String, default: Long): Long = getString(key, null)?.toLongOrNull() ?: default
  override fun contains(key: String): Boolean = synchronized(props) { props.containsKey(key) }

  override fun edit(): KeyValueStore.Editor = object : KeyValueStore.Editor {
    private val changes = LinkedHashMap<String, String?>()
    override fun putString(key: String, value: String?) = apply { changes[key] = value }
    override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
    override fun putLong(key: String, value: Long) = apply { changes[key] = value.toString() }
    override fun remove(key: String) = apply { changes[key] = null }
    override fun apply() = synchronized(props) {
      changes.forEach { (k, v) -> if (v == null) props.remove(k) else props.setProperty(k, v) }
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.outputStream().use { props.store(it, null) }
      Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      Unit
    }
  }
}

/** Session alarms while the desktop app is open: a repeating tone plus [onRing] (window and tray). */
class DesktopAlarms(
  private val scope: CoroutineScope,
  /** The sound chosen in Settings. */
  private val chosenSound: () -> com.example.platform.AlarmSound = { com.example.platform.AlarmSound.DEFAULT }
) : AlarmPlatform {

  /** Set by the window: bring it forward and show a notification. */
  var onRing: (TimetableSession) -> Unit = {}

  /** Set by the app: lets paired phones know this alarm was stopped or snoozed here. */
  var onStopped: (TimetableSession, Int) -> Unit = { _, _ -> }

  override fun announceStop(session: TimetableSession, snoozeMinutes: Int) = onStopped(session, snoozeMinutes)

  private val scheduled = ConcurrentHashMap<Long, Job>()
  private var tone: Job? = null

  @Volatile override var ringingSessionId: Long? = null
    private set

  override fun schedule(session: TimetableSession) {
    cancel(session.id)
    val at = ScheduleLogic.computeSessionTriggerTimeMillis(session)
    if (at <= System.currentTimeMillis()) return
    scheduled[session.id] = scope.launch {
      delay(at - System.currentTimeMillis())
      startRinging(session)
      if (session.recurringDays.isNotBlank()) {
        delay(61_000)
        schedule(session)
      }
    }
  }

  override fun cancel(sessionId: Long) {
    scheduled.remove(sessionId)?.cancel()
  }

  override fun snooze(session: TimetableSession, minutes: Int) {
    scheduled[-session.id]?.cancel()
    scheduled[-session.id] = scope.launch {
      delay(minutes * 60_000L)
      startRinging(session)
    }
  }

  override fun startRinging(session: TimetableSession) {
    ringingSessionId = session.id
    AlarmEventBus.emit(
      AlarmEvent.Triggered(session.id, session.subjectName, session.topic, session.startTime, session.endTime, session.location, session.notes)
    )
    onRing(session)
    tone?.cancel()
    tone = scope.launch(Dispatchers.IO) {
      val end = System.currentTimeMillis() + 5 * 60_000L
      val clip = openClip(chosenSound(), loop = true)
      try {
        while (isActive && System.currentTimeMillis() < end) {
          if (clip == null) playBeeps()
          delay(1200)
        }
      } finally {
        clip?.let { runCatching { it.stop(); it.close() } }
      }
      ringingSessionId = null
    }
  }

  private var preview: kotlinx.coroutines.Job? = null

  /** Plays about four seconds of [sound], for choosing it in Settings. */
  override fun previewSound(sound: com.example.platform.AlarmSound) {
    preview?.cancel()
    preview = scope.launch(Dispatchers.IO) {
      val clip = openClip(sound, loop = true)
      try {
        if (clip == null) repeat(3) { playBeeps(); delay(400) } else delay(4000)
      } finally {
        clip?.let { runCatching { it.stop(); it.close() } }
      }
    }
  }

  /** A playing clip of a bundled sound, or null for the default beeps or if it can't be loaded. */
  private suspend fun openClip(sound: com.example.platform.AlarmSound, loop: Boolean): javax.sound.sampled.Clip? {
    val name = sound.fileName ?: return null
    return runCatching {
      val bytes = com.example.desktop.resources.Res.readBytes("files/sounds/$name.wav")
      val stream = AudioSystem.getAudioInputStream(java.io.ByteArrayInputStream(bytes))
      AudioSystem.getClip().apply {
        open(stream)
        if (loop) loop(javax.sound.sampled.Clip.LOOP_CONTINUOUSLY) else start()
      }
    }.getOrNull()
  }

  override fun stopRinging() {
    tone?.cancel()
    tone = null
    ringingSessionId = null
  }

  /** Two short 880 Hz beeps. */
  private fun playBeeps() {
    runCatching {
      val rate = 44_100f
      val beep = ByteArray((rate * 0.18).toInt()) { i -> (sin(2 * PI * i * 880 / rate) * 90).toInt().toByte() }
      val gap = ByteArray((rate * 0.12).toInt())
      val line = AudioSystem.getSourceDataLine(AudioFormat(rate, 8, 1, true, false))
      line.open()
      line.start()
      line.write(beep, 0, beep.size); line.write(gap, 0, gap.size); line.write(beep, 0, beep.size)
      line.drain()
      line.close()
    }
  }
}
