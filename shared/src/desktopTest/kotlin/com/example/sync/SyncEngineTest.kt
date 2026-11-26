package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.local.AppDatabase
import com.example.data.model.Subject
import com.example.data.model.TimetableSession
import com.example.data.sync.SyncEngine
import com.example.platform.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineTest {

  private class MemoryPrefs : KeyValueStore {
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

  private val files = mutableListOf<File>()

  private fun newDevice(): Pair<AppDatabase, SyncEngine> {
    val file = File.createTempFile("sync-test", ".db").also { it.delete(); files += it }
    val db = AppDatabase.configure(
      Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
    )
    return db to SyncEngine(db, MemoryPrefs())
  }

  @AfterTest
  fun cleanUp() {
    files.forEach { f -> File(f.parentFile, f.name + "-wal").delete(); File(f.parentFile, f.name + "-shm").delete(); f.delete() }
  }

  /** One phone-to-PC exchange, the way SyncClient and SyncHost do it. */
  private suspend fun exchange(phone: SyncEngine, pc: SyncEngine, state: LongArray) {
    val phoneSeq = phone.currentSeq()
    val up = phone.changesSince(state[0], phoneSeq, excludeDevice = pc.deviceId)
    pc.apply(up, fromDevice = phone.deviceId)
    val pcSeq = pc.currentSeq()
    val down = pc.changesSince(state[1], pcSeq, excludeDevice = phone.deviceId)
    phone.apply(down, fromDevice = pc.deviceId)
    state[0] = phoneSeq
    state[1] = pcSeq
  }

  @Test
  fun recordsAndReferencesTravelBothWays() = runBlocking {
    val (phoneDb, phone) = newDevice()
    val (pcDb, pc) = newDevice()
    phone.prepare(); pc.prepare()
    val state = LongArray(2)

    val math = phoneDb.studyPilotDao().insertSubject(Subject(userId = "u", name = "Maths", code = "M1", colorHex = "#000000"))
    phoneDb.studyPilotDao().insertSession(
      TimetableSession(userId = "u", subjectId = math, subjectName = "Maths", topic = "Limits", date = "2026-11-26", startTime = "09:00", endTime = "10:00")
    )
    // The PC already has its own subject with the same local id 1.
    pcDb.studyPilotDao().insertSubject(Subject(userId = "u", name = "Physics", code = "P1", colorHex = "#111111"))

    exchange(phone, pc, state)

    val pcSubjects = pcDb.studyPilotDao().getSubjectsForUser("u").first()
    assertEquals(setOf("Maths", "Physics"), pcSubjects.map { it.name }.toSet())
    val pcSession = pcDb.studyPilotDao().getSessionsListForUser("u").single()
    assertEquals(pcSubjects.first { it.name == "Maths" }.id, pcSession.subjectId, "session points at the PC's copy of Maths")
    assertEquals(setOf("Maths", "Physics"), phoneDb.studyPilotDao().getSubjectsForUser("u").first().map { it.name }.toSet())

    // Nothing echoes back.
    assertEquals(0, phone.changesSince(state[0], phone.currentSeq(), pc.deviceId).length())
    assertEquals(0, pc.changesSince(state[1], pc.currentSeq(), phone.deviceId).length())
  }

  @Test
  fun editsAndDeletesFollow() = runBlocking {
    val (phoneDb, phone) = newDevice()
    val (pcDb, pc) = newDevice()
    phone.prepare(); pc.prepare()
    val state = LongArray(2)
    val id = phoneDb.studyPilotDao().insertSession(
      TimetableSession(userId = "u", subjectId = 0, subjectName = "Gym", topic = "Legs", date = "2026-11-26", startTime = "07:00", endTime = "08:00")
    )
    exchange(phone, pc, state)

    val onPc = pcDb.studyPilotDao().getSessionsListForUser("u").single()
    Thread.sleep(5)
    pcDb.studyPilotDao().updateSession(onPc.copy(topic = "Arms"))
    exchange(phone, pc, state)
    assertEquals("Arms", phoneDb.studyPilotDao().getSessionById(id)?.topic)

    Thread.sleep(5)
    phoneDb.studyPilotDao().deleteSessionById(id)
    exchange(phone, pc, state)
    assertTrue(pcDb.studyPilotDao().getSessionsListForUser("u").isEmpty())
  }

  @Test
  fun newerEditWins() = runBlocking {
    val (phoneDb, phone) = newDevice()
    val (pcDb, pc) = newDevice()
    phone.prepare(); pc.prepare()
    val state = LongArray(2)
    val id = phoneDb.studyPilotDao().insertSession(
      TimetableSession(userId = "u", subjectId = 0, subjectName = "Art", topic = "Draft", date = "2026-11-26", startTime = "12:00", endTime = "13:00")
    )
    exchange(phone, pc, state)
    val onPc = pcDb.studyPilotDao().getSessionsListForUser("u").single()

    phoneDb.studyPilotDao().updateSession(phoneDb.studyPilotDao().getSessionById(id)!!.copy(topic = "Phone edit"))
    Thread.sleep(5)
    pcDb.studyPilotDao().updateSession(onPc.copy(topic = "PC edit (later)"))
    exchange(phone, pc, state)

    assertEquals("PC edit (later)", phoneDb.studyPilotDao().getSessionById(id)?.topic)
    assertEquals("PC edit (later)", pcDb.studyPilotDao().getSessionById(onPc.id)?.topic)
  }

  @Test
  fun existingRowsAreTrackedWhenSyncStarts() = runBlocking {
    val (phoneDb, phone) = newDevice()
    val (pcDb, pc) = newDevice()
    // Rows written before the engine ever ran (an existing install).
    phoneDb.studyPilotDao().insertSubject(Subject(userId = "u", name = "History", code = "H1", colorHex = "#222222"))
    phone.prepare(); pc.prepare()
    exchange(phone, pc, LongArray(2))
    assertEquals(listOf("History"), pcDb.studyPilotDao().getSubjectsForUser("u").first().map { it.name })
  }

  @Test
  fun savingOverARecordKeepsItsIdentity() = runBlocking {
    val (phoneDb, phone) = newDevice()
    val (pcDb, pc) = newDevice()
    phone.prepare(); pc.prepare()
    val state = LongArray(2)
    val dao = phoneDb.studyPilotDao()
    val id = dao.insertSubject(Subject(userId = "u", name = "Bio", code = "B1", colorHex = "#333333"))
    exchange(phone, pc, state)
    Thread.sleep(5)
    // insertSubject replaces on conflict; that must count as an edit, not a new record.
    dao.insertSubject(Subject(id = id, userId = "u", name = "Biology", code = "B1", colorHex = "#333333"))
    exchange(phone, pc, state)
    assertEquals(listOf("Biology"), pcDb.studyPilotDao().getSubjectsForUser("u").first().map { it.name })
  }
}
