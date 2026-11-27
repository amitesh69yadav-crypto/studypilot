package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.local.AppDatabase
import com.example.data.sync.KeyShare
import com.example.data.sync.SyncEngine
import com.example.data.sync.SyncHost
import com.example.platform.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

import kotlin.test.assertTrue

/** Cloud keys cross the phone-PC sync encrypted with the pairing token, newest change winning. */
class KeyShareTest {
  private val file = File.createTempFile("key-share", ".db").also { it.delete() }
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

  @AfterTest
  fun resetHooks() { KeyShare.export = { null }; KeyShare.import = { false } }

  @Test
  fun sealedKeysOpenOnlyWithTheSamePairing() {
    val sealed = KeyShare.seal("""{"keys":{"GROQ":"gsk_test"}}""", "token-a")
    assertTrue("gsk_test" !in sealed)
    assertEquals("""{"keys":{"GROQ":"gsk_test"}}""", KeyShare.open(sealed, "token-a"))
    assertTrue(runCatching { KeyShare.open(sealed, "token-b") }.isFailure)
  }

  @Test
  fun keysTravelBothWaysThroughTheSync() = runBlocking<Unit> {
    val token = pairedToken()
    // The PC has an older key; the phone sends a newer one.
    var pcKeys = JSONObject().put("updatedAt", 100L).put("keys", JSONObject().put("GROQ", "old"))
    KeyShare.export = { pcKeys }
    KeyShare.import = { incoming ->
      if (incoming.optLong("updatedAt") > pcKeys.optLong("updatedAt")) { pcKeys = incoming; true } else false
    }
    val phoneKeys = JSONObject().put("updatedAt", 200L).put("keys", JSONObject().put("GROQ", "new"))
    val body = JSONObject().put("deviceId", "phone-1").put("name", "Phone").put("keys", KeyShare.seal(phoneKeys.toString(), token))
    val (status, reply) = host.handleSync(token, body)
    assertEquals(200, status)
    assertEquals("new", pcKeys.getJSONObject("keys").getString("GROQ"))
    // The reply carries the PC's (now newest) keys back, readable with the same pairing.
    val back = JSONObject(KeyShare.open(reply.getString("keys"), token))
    assertEquals("new", back.getJSONObject("keys").getString("GROQ"))
  }
}
