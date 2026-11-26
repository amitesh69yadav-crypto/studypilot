package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.local.AppDatabase
import com.example.data.model.ExtractedConcept
import com.example.data.model.ImportedChat
import com.example.data.model.StudyTopic
import com.example.data.model.TutorMessageEntity
import com.example.data.model.TutorSession
import com.example.data.repository.QuizSources
import com.example.platform.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuizSourcesTest {

  private val file = File.createTempFile("quiz-sources", ".db").also { it.delete() }
  private val db = AppDatabase.configure(
    Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO)
  )
  private val dao = db.studyPilotDao()
  private val prefs = object : KeyValueStore {
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
  private var uids: Map<Long, String> = emptyMap()
  private val sources = QuizSources(dao, prefs) { ids -> uids.filterKeys { it in ids } }
  private val answer = "A binary heap is a complete binary tree where every parent is ordered relative to its children, " +
    "so the minimum (or maximum) is always at the root and insert or extract take O(log n)."

  @AfterTest
  fun cleanUp() {
    db.close()
    listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
  }

  private suspend fun topics() = dao.getTopicsForUser("u").first()

  private suspend fun tutorChat(title: String, createdAt: Long, reply: String = answer): Long {
    val id = dao.insertTutorSession(TutorSession(userId = "u", title = title, previewSnippet = "", createdAt = createdAt))
    dao.insertTutorMessage(TutorMessageEntity(sessionId = id, role = "user", text = "What is a binary heap?"))
    dao.insertTutorMessage(TutorMessageEntity(sessionId = id, role = "model", text = reply))
    return id
  }

  @Test
  fun tutorConversationsBecomeQuizTopics() = runBlocking {
    tutorChat("Heaps", 1_000L)
    // Small talk with no real answer isn't quiz material.
    val hello = dao.insertTutorSession(TutorSession(userId = "u", title = "Hi", previewSnippet = "", createdAt = 2_000L))
    dao.insertTutorMessage(TutorMessageEntity(sessionId = hello, role = "model", text = "Hello! How can I help?"))

    sources.refresh("u")

    val topic = topics().single()
    assertEquals("Heaps", topic.title)
    assertEquals(QuizSources.TUTOR_CHAT, topic.sourceType)
    assertTrue("What is a binary heap?" in topic.descriptionNotes && "complete binary tree" in topic.descriptionNotes)

    // Running again changes nothing.
    sources.refresh("u")
    assertEquals(1, topics().size)
  }

  @Test
  fun topicFollowsItsConversation() = runBlocking {
    val session = tutorChat("Heaps", 1_000L)
    sources.refresh("u")
    val topicId = topics().single().id
    dao.insertQuestion(
      com.example.data.model.QuizQuestion(topicId = topicId, subjectName = "", topicTitle = "Heaps", questionType = "TRUE_FALSE", questionText = "q", correctAnswer = "True", explanation = "")
    )

    dao.insertTutorMessage(TutorMessageEntity(sessionId = session, role = "user", text = "And a Fibonacci heap?"))
    sources.refresh("u")
    assertTrue("Fibonacci" in topics().single().descriptionNotes)
    assertTrue(dao.getQuestionsForTopicIds(listOf(topicId)).isEmpty(), "old questions are dropped when the material changes")

    dao.deleteTutorSessionAtomic(session)
    sources.refresh("u")
    assertTrue(topics().isEmpty())
  }

  @Test
  fun importedChatsAndConceptsAreCovered() = runBlocking {
    // Nothing was extracted from this chat, so the chat itself is the material.
    dao.insertImportedChat(ImportedChat(userId = "u", title = "OS scheduling chat", rawConversation = "User: explain round robin. ".repeat(10), importedAt = 5_000L))
    // A concept whose topic was deleted or never made.
    val other = dao.insertImportedChat(ImportedChat(userId = "u", title = "Graphs chat", rawConversation = "x".repeat(200), importedAt = 6_000L))
    dao.insertExtractedConcept(
      ExtractedConcept(userId = "u", sourceChatId = other, sourceChatTitle = "Graphs chat", topic = "Graphs", conceptName = "Dijkstra", definition = "Shortest paths with non-negative weights.", extractedAt = 7_000L)
    )
    // A concept that already has the student's own topic is left alone.
    dao.insertTopic(StudyTopic(userId = "u", subjectId = 0, subjectName = "CS", title = "BFS", dateStudied = "2026-11-26", descriptionNotes = "mine"))
    dao.insertExtractedConcept(
      ExtractedConcept(userId = "u", sourceChatId = other, sourceChatTitle = "Graphs chat", topic = "Graphs", conceptName = "BFS", definition = "Level order.", extractedAt = 7_001L)
    )

    sources.refresh("u")

    val byTitle = topics().associateBy { it.title }
    assertEquals(setOf("OS scheduling chat", "Dijkstra", "BFS"), byTitle.keys, "the Graphs chat is covered by its concepts")
    assertEquals("mine", byTitle.getValue("BFS").descriptionNotes)
    assertTrue("non-negative" in byTitle.getValue("Dijkstra").descriptionNotes)
  }

  @Test
  fun deletedTopicStaysDeleted() = runBlocking {
    tutorChat("Heaps", 1_000L)
    sources.refresh("u")
    val topic = topics().single()
    sources.onTopicDeleted(topic)
    dao.deleteTopicById(topic.id)

    sources.refresh("u")
    assertTrue(topics().isEmpty())
  }

  @Test
  fun duplicatesFromTwoDevicesSettleOnTheSameCopy() = runBlocking {
    tutorChat("Heaps", 1_000L)
    sources.refresh("u")
    val first = topics().single()
    // The PC made its own copy before syncing; it arrives with a smaller sync id.
    val second = dao.insertTopic(first.copy(id = 0))
    uids = mapOf(first.id to "bbbb", second to "aaaa")

    sources.refresh("u")
    assertEquals(second, topics().single().id)
    assertNull(dao.getTopicById(first.id))
  }

  @Test
  fun quizUsesTutorChatsAndFilesConceptQuestionsUnderTheirTopic() = runBlocking {
    val heaps = tutorChat("Heaps", 1_000L)
    dao.insertTutorMessage(TutorMessageEntity(sessionId = heaps, role = "user", text = "How fast is extract min?"))
    dao.insertTutorMessage(
      TutorMessageEntity(sessionId = heaps, role = "model", text = "Extract-min takes O(log n) because the last element moves to the root and is sifted down level by level.")
    )
    val chat = dao.insertImportedChat(ImportedChat(userId = "u", title = "Graphs chat", rawConversation = "x".repeat(200), importedAt = 6_000L))
    dao.insertExtractedConcept(
      ExtractedConcept(userId = "u", sourceChatId = chat, sourceChatTitle = "Graphs chat", topic = "Graphs", conceptName = "Dijkstra", definition = "Shortest paths with non-negative weights.", extractedAt = 7_000L)
    )
    val repo = com.example.data.repository.StudyPilotRepository(dao)

    val quiz = repo.assembleWeekendQuiz(com.example.data.model.QuizConfig(questionCount = 4), userId = "u")

    val topicIds = topics().associate { it.title to it.id }
    assertEquals(setOf("Heaps", "Dijkstra"), quiz.testedTopics.map { it.title }.toSet())
    assertTrue(quiz.questions.all { it.topicId in topicIds.values }, "every question belongs to a quiz topic")
    assertTrue(quiz.questions.any { it.topicId == topicIds.getValue("Dijkstra") })
    assertEquals(4, quiz.questions.size, "the question count is spread over the topics, not rounded down")
  }
}
