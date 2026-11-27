package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.local.AppDatabase
import com.example.data.sync.AlarmStop
import com.example.data.sync.SyncEngine
import com.example.data.sync.SyncHost
import com.example.data.sync.alarmKey
import com.example.platform.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

import kotlin.test.assertTrue

/** Stopping an alarm on one device reaches the other through the PC's /alarm endpoint. */
class AlarmRelayTest {
  private val file = File.createTempFile("alarm-relay", ".db").also { it.delete() }
  private val db = AppDatabase.configure(
    Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO)
  )
  private fun store() = object : KeyValueStore {
    val map = HashMap<String, Any?>()
    override fun getString(key: String, default: String?) = map[key] as? String ?: default
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
    override fun contains(key: String) = key in map
    override fun edit(): KeyValueStore.Editor = object : KeyValueStore.Editor {
      override fun putString(key: String, value: String?) = apply { map[key] = value }
      override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
      override fun putLong(key: String, value: Long) = apply { map[key] = value }
      override fun remove(key: String) = apply { map.remove(key) }
      override fun apply() {}
    }
  }
  private val host = SyncHost(SyncEngine(db, store()), store(), "Test PC")

  @AfterTest
  fun cleanUp() { db.close(); file.delete() }

  private fun pairedToken(): String = runBlocking {
    val code = host.startPairing()
    host.handlePair(JSONObject().put("code", code).put("name", "Phone")).second.getString("token")
  }

  @Test
  fun keyIgnoresCaseAndSpacing() {
    assertEquals(alarmKey("Maths", "Limits", "09:00"), alarmKey(" maths", "LIMITS ", "09:00"))
    assertTrue(alarmKey("Maths", "Limits", "09:00") != alarmKey("Maths", "Limits", "10:00"))
  }

  @Test
  fun phoneStopReachesThePc() {
    val token = pairedToken()
    var heard: Pair<String, Int>? = null
    host.onRemoteAlarmStop = { key, snooze -> heard = key to snooze }
    val key = alarmKey("Maths", "Limits", "09:00")
    val (status, reply) = host.handleAlarm(token, JSONObject().put("stop", AlarmStop(key, 5, 0L).toJson()))
    assertEquals(200, status)
    assertEquals(key to 5, heard)
    assertEquals(key, AlarmStop.fromJson(reply.getJSONObject("stop"))!!.key)
  }

  @Test
  fun pcStopIsWhatARingingPhoneSees() {
    val token = pairedToken()
    assertTrue(host.handleAlarm(token, JSONObject()).second.isNull("stop"))
    val key = alarmKey("Physics", "Optics", "14:30")
    val before = System.currentTimeMillis()
    host.announceAlarmStop(key, 0)
    val stop = AlarmStop.fromJson(host.handleAlarm(token, JSONObject()).second.getJSONObject("stop"))!!
    assertEquals(key, stop.key)
    assertEquals(0, stop.snoozeMinutes)
    assertTrue(stop.at >= before)
  }

  @Test
  fun unpairedDevicesAreRefused() {
    var heard = false
    host.onRemoteAlarmStop = { _, _ -> heard = true }
    val (status, _) = host.handleAlarm("not-a-token", JSONObject().put("stop", AlarmStop("x|y|09:00", 0, 0L).toJson()))
    assertEquals(401, status)
    assertTrue(!heard)
  }
}
