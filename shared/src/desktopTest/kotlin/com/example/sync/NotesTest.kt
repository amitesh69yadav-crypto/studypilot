package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.example.data.local.AppDatabase
import com.example.data.model.StudyNote
import com.example.data.notes.QaPairs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotesTest {
  private fun open(file: File) = AppDatabase.configure(
    Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO)
  )

  @Test
  fun conversationBecomesQuestionsAndAnswers() {
    val pairs = QaPairs.fromConversation(
      """
      Intro text with no speaker
      User: What is a mutex?
      Assistant: A lock that lets one thread in at a time.
      It prevents races.
      You: And a semaphore?
      ChatGPT: A counter of permits.
      User: Thanks
      """.trimIndent()
    )
    assertEquals(3, pairs.size)
    assertEquals("What is a mutex?", pairs[0].question)
    assertEquals("A lock that lets one thread in at a time.\nIt prevents races.", pairs[0].answer)
    assertEquals("A counter of permits.", pairs[1].answer)
    assertEquals("", pairs[2].answer)
  }

  @Test
  fun tutorTurnsPairUp() {
    val pairs = QaPairs.fromTurns(listOf(false to "Hi, ask me anything", true to "Q1", false to "A1", false to "more", true to "Q2"))
    assertEquals(listOf("Q1" to "A1\n\nmore", "Q2" to ""), pairs.map { it.question to it.answer })
  }

  @Test
  fun notesSaveSortAndDelete() = runBlocking<Unit> {
    val file = File.createTempFile("notes", ".db").also { it.delete() }
    val db = open(file)
    try {
      val dao = db.studyPilotDao()
      val a = dao.insertNote(StudyNote(userId = "u", title = "Old", body = "x", updatedAt = 1))
      dao.insertNote(StudyNote(userId = "u", title = "New", body = "y", updatedAt = 2))
      dao.insertNote(StudyNote(userId = "u", title = "Pinned", body = "z", pinned = true, updatedAt = 0))
      dao.insertNote(StudyNote(userId = "other", title = "Not mine", body = "w"))
      assertEquals(listOf("Pinned", "New", "Old"), dao.getNotesForUser("u").first().map { it.title })
      dao.deleteNote(a)
      assertEquals(listOf("Pinned", "New"), dao.getNotesForUser("u").first().map { it.title })
    } finally {
      db.close(); file.delete()
    }
  }

  @Test
  fun upgradeFromVersion8KeepsDataAndAddsNotes() = runBlocking<Unit> {
    val file = File.createTempFile("upgrade", ".db").also { it.delete() }
    // Build a version 9 database, then turn it back into version 8: no notes table, old version number.
    open(file).also { db ->
      db.studyPilotDao().insertNote(StudyNote(userId = "u", title = "gone", body = ""))
      db.close()
    }
    BundledSQLiteDriver().open(file.absolutePath).use { c ->
      c.execSQL("CREATE TABLE marker (v TEXT)")
      c.execSQL("INSERT INTO marker VALUES ('kept')")
      c.execSQL("DROP TABLE notes")
      c.execSQL("PRAGMA user_version = 8")
    }
    val db = open(file)
    try {
      // Room checks the migrated schema itself; a wrong table would throw here.
      db.studyPilotDao().insertNote(StudyNote(userId = "u", title = "after", body = ""))
      assertEquals(listOf("after"), db.studyPilotDao().getNotesForUser("u").first().map { it.title })
    } finally {
      db.close()
    }
    BundledSQLiteDriver().open(file.absolutePath).use { c ->
      c.prepare("SELECT v FROM marker").use { assertTrue(it.step()); assertEquals("kept", it.getText(0)) }
    }
    file.delete()
  }
}
