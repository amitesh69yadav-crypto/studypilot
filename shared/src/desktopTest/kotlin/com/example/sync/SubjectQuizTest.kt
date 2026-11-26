package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.local.AppDatabase
import com.example.data.model.QuizConfig
import com.example.data.model.StudyTopic
import com.example.data.model.Subject
import com.example.data.model.TutorMessageEntity
import com.example.data.model.TutorSession
import com.example.data.repository.StudyPilotRepository
import com.example.ui.screens.SubjectGroup
import com.example.ui.screens.groupTopicsBySubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubjectQuizTest {

  private val file = File.createTempFile("subject-quiz", ".db").also { it.delete() }
  private val db = AppDatabase.configure(
    Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO)
  )
  private val dao = db.studyPilotDao()
  private val repo = StudyPilotRepository(dao)

  @AfterTest
  fun cleanUp() {
    db.close()
    listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
  }

  private fun topic(subjectId: Long, subjectName: String, title: String) = StudyTopic(
    userId = "u", subjectId = subjectId, subjectName = subjectName, title = title, dateStudied = "2026-11-26",
    descriptionNotes = "Notes on $title. This sentence is long enough to become a question about $title for the quiz."
  )

  @Test
  fun topicsAreGroupedUnderTheirSubjects() {
    val maths = Subject(id = 1, userId = "u", name = "Maths", code = "M1", colorHex = "#000000")
    val physics = Subject(id = 2, userId = "u", name = "Physics", code = "P1", colorHex = "#111111")
    val topics = listOf(
      topic(1, "Maths", "Limits").copy(id = 10),
      topic(1, "Maths", "Series").copy(id = 11, masteryLevel = "MASTERED"),
      topic(0, "General", "Heaps").copy(id = 12),
      topic(9, "Deleted subject", "Old notes").copy(id = 13)
    )
    val groups = groupTopicsBySubject(listOf(maths, physics), topics)

    assertEquals(listOf("Maths", "Physics", "General"), groups.map { it.name })
    assertEquals(listOf("Limits", "Series"), groups[0].topics.map { it.title })
    assertEquals(1, groups[0].mastered)
    assertTrue(groups[1].topics.isEmpty(), "an empty subject still shows")
    assertEquals(SubjectGroup.GENERAL_ID, groups[2].id)
    assertEquals(setOf("Heaps", "Old notes"), groups[2].topics.map { it.title }.toSet(), "no subject, or a deleted one, is General")
    assertEquals(listOf("Maths"), groupTopicsBySubject(listOf(maths), topics.take(2)).map { it.name }, "no General card when every topic has a subject")
  }

  @Test
  fun subjectQuizOnlyAsksAboutThatSubject() = runBlocking {
    val maths = dao.insertSubject(Subject(userId = "u", name = "Maths", code = "M1", colorHex = "#000000"))
    val physics = dao.insertSubject(Subject(userId = "u", name = "Physics", code = "P1", colorHex = "#111111"))
    dao.insertTopic(topic(maths, "Maths", "Limits"))
    dao.insertTopic(topic(maths, "Maths", "Series"))
    dao.insertTopic(topic(physics, "Physics", "Momentum"))
    dao.insertTopic(topic(0, "General", "Heaps"))

    val mathsQuiz = repo.assembleWeekendQuiz(QuizConfig(questionCount = 4), filterSubjectId = maths, userId = "u")
    assertEquals(setOf("Limits", "Series"), mathsQuiz.testedTopics.map { it.title }.toSet())
    assertTrue(mathsQuiz.questions.isNotEmpty() && mathsQuiz.questions.all { it.topicTitle in setOf("Limits", "Series") })

    val generalQuiz = repo.assembleWeekendQuiz(QuizConfig(questionCount = 2), filterSubjectId = SubjectGroup.GENERAL_ID, userId = "u")
    assertEquals(listOf("Heaps"), generalQuiz.testedTopics.map { it.title })

    val everything = repo.assembleWeekendQuiz(QuizConfig(questionCount = 4), userId = "u")
    assertEquals(4, everything.testedTopics.size)

    // A topic moved to another subject: its saved questions show the new subject.
    val limits = dao.getTopicsForUser("u").first().first { it.title == "Limits" }
    repo.updateTopic(limits.copy(subjectId = physics, subjectName = "Physics"))
    val physicsQuiz = repo.assembleWeekendQuiz(QuizConfig(questionCount = 4), filterSubjectId = physics, userId = "u")
    assertTrue(physicsQuiz.questions.any { it.topicTitle == "Limits" })
    assertTrue(physicsQuiz.questions.all { it.subjectName == "Physics" })

    // Deleting a subject keeps its topics, under General.
    repo.deleteSubject(dao.getSubjectsForUser("u").first().first { it.id == physics })
    val orphaned = dao.getTopicsForUser("u").first().filter { it.title in setOf("Limits", "Momentum") }
    assertTrue(orphaned.size == 2 && orphaned.all { it.subjectId == 0L && it.subjectName == "General" })
  }

  @Test
  fun tutorChatTopicKeepsTheSubjectItWasMovedTo() = runBlocking {
    val maths = dao.insertSubject(Subject(userId = "u", name = "Maths", code = "M1", colorHex = "#000000"))
    val session = dao.insertTutorSession(TutorSession(userId = "u", title = "Limits help", previewSnippet = "", createdAt = 1_000L))
    dao.insertTutorMessage(TutorMessageEntity(sessionId = session, role = "user", text = "What is a limit?"))
    dao.insertTutorMessage(
      TutorMessageEntity(sessionId = session, role = "model", text = "A limit is the value a function approaches as its input approaches some point, even if it never reaches it.")
    )
    repo.quizSources.refresh("u")
    val fromChat = dao.getTopicsForUser("u").first().single()
    assertEquals(0L, fromChat.subjectId)

    repo.updateTopic(fromChat.copy(subjectId = maths, subjectName = "Maths"))
    dao.insertTutorMessage(TutorMessageEntity(sessionId = session, role = "user", text = "And one-sided limits?"))
    repo.quizSources.refresh("u")

    val after = dao.getTopicsForUser("u").first().single()
    assertTrue("one-sided" in after.descriptionNotes, "content still refreshes")
    assertEquals(maths, after.subjectId, "the move sticks")
  }
}
