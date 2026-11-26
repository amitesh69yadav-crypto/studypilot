package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.ai.LocalAiEngine
import com.example.data.local.AppDatabase
import com.example.data.model.QuizConfig
import com.example.data.model.StudyTopic
import com.example.data.repository.StudyPilotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Quizzes without a model: multiple choice, and a new set each time while the material lasts. */
class FreshQuizTest {

  private val file = File.createTempFile("fresh-quiz", ".db").also { it.delete() }
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

  private fun chat(title: String, facts: List<Pair<String, String>>) = StudyTopic(
    userId = "u", subjectId = 0, subjectName = "General", title = title, dateStudied = "2026-11-26", sourceType = "TUTOR_CHAT",
    descriptionNotes = facts.joinToString("\n\n") { (q, a) -> "Q: $q\nA: $a" }
  )

  private val heaps = chat(
    "Heaps", listOf(
      "What is a binary heap" to "A binary heap is a complete binary tree where each parent is ordered relative to its children.",
      "How fast is insert into a heap" to "Insert into a binary heap takes O(log n) because the new item sifts up one level at a time.",
      "How does extract min work" to "Extract-min moves the last element to the root and sifts it down, which takes O(log n) time.",
      "How is a heap stored" to "A heap is usually stored in an array where the children of index i are at 2i+1 and 2i+2.",
      "What is heapify" to "Heapify builds a heap from an unordered array in O(n) time by sifting down from the middle.",
      "Where are heaps used" to "Heaps implement priority queues, which schedulers and Dijkstra's algorithm rely on heavily."
    )
  )
  private val recursion = chat(
    "Recursion", listOf(
      "What is recursion" to "Recursion is when a function solves a problem by calling itself on a smaller version of it.",
      "What is a base case" to "A base case is the input a recursive function answers directly, which stops further calls.",
      "What causes stack overflow" to "Recursing too deeply without reaching a base case uses up the call stack and overflows it.",
      "What is tail recursion" to "Tail recursion makes the recursive call the very last step, so some compilers reuse the frame.",
      "Recursion versus iteration" to "Any recursive algorithm can be written with a loop and an explicit stack instead of calls.",
      "What is memoisation" to "Memoisation caches results of recursive calls so repeated subproblems are solved only once."
    )
  )

  @Test
  fun eachQuizAsksNewMultipleChoiceQuestions() = runBlocking {
    dao.insertTopic(heaps)
    dao.insertTopic(recursion)
    val config = QuizConfig(questionCount = 4) // multiple choice by default
    val seen = HashSet<String>()

    repeat(3) { round ->
      val quiz = repo.assembleWeekendQuiz(config, userId = "u")
      assertEquals(4, quiz.questions.size)
      assertTrue(quiz.questions.all { it.questionType == "MULTIPLE_CHOICE" }, "round $round: all multiple choice")
      for (q in quiz.questions) {
        val options = q.optionsJson.split(if ('|' in q.optionsJson) "|" else ",")
        assertEquals(4, options.size, "four options: ${q.optionsJson}")
        assertTrue(q.correctAnswer in options, "the answer is one of the options")
      }
      val ids = quiz.questions.map { it.id }
      assertTrue(ids.all { it > 0 } && ids.toSet().size == ids.size, "every question has its own id, so answers don't overwrite each other")
      val keys = quiz.questions.map { LocalAiEngine.questionKey(it) }
      assertTrue(keys.none { it in seen }, "round $round repeats nothing from earlier quizzes")
      seen += keys

      val evaluations = quiz.questions.associate { it.id to repo.evaluateUserAnswer(it, it.correctAnswer) }
      repo.recordQuizResults("Quiz $round", quiz.questions, quiz.questions.associate { it.id to it.correctAnswer }, evaluations, 2, 0, "u")
    }
    assertEquals(12, seen.size)
  }

  @Test
  fun whenEverythingWasAskedTheOldestComeBack() = runBlocking {
    val small = chat(
      "Tiny", listOf(
        "What is a stack" to "A stack is a last-in first-out collection where you push and pop at the same end.",
        "What is a queue" to "A queue is a first-in first-out collection where items leave in the order they arrived."
      )
    )
    dao.insertTopic(small)
    dao.insertTopic(recursion)
    repeat(4) {
      val quiz = repo.assembleWeekendQuiz(QuizConfig(questionCount = 4), userId = "u")
      assertEquals(4, quiz.questions.size, "a quiz is never short just because the material ran out")
      val evaluations = quiz.questions.associate { it.id to repo.evaluateUserAnswer(it, "") }
      repo.recordQuizResults("Quiz", quiz.questions, emptyMap(), evaluations, 2, 0, "u")
    }
  }

  @Test
  fun nextQuestionsAreWrittenAhead() = runBlocking {
    val id = dao.insertTopic(heaps)
    dao.insertTopic(recursion)
    val topic = heaps.copy(id = id)
    repo.prepareNextQuestions(listOf(topic), perTopic = 3, config = QuizConfig(), userId = "u")
    val saved = dao.getQuestionsForTopicIds(listOf(id))
    assertEquals(3, saved.size)
    assertTrue(saved.all { it.questionType == "MULTIPLE_CHOICE" })

    // Already enough unasked questions: nothing more is written.
    repo.prepareNextQuestions(listOf(topic), perTopic = 3, config = QuizConfig(), userId = "u")
    assertEquals(3, dao.getQuestionsForTopicIds(listOf(id)).size)
  }

  @Test
  fun builtInMultipleChoiceComesFromTheMaterial() {
    val topic = heaps.copy(id = 1)
    val pool = LocalAiEngine.distractorPool(listOf(topic, recursion.copy(id = 2)))
    val questions = LocalAiEngine.mcqFromNotes(topic, 6, "MEDIUM", pool)
    assertEquals(6, questions.size)
    val q = questions.first { it.questionText == "What is a binary heap?" }
    assertTrue(q.correctAnswer.startsWith("A binary heap is a complete binary tree"))
    val skip = questions.take(3).map { LocalAiEngine.questionKey(it) }.toSet()
    assertTrue(LocalAiEngine.mcqFromNotes(topic, 6, "MEDIUM", pool, skip).none { LocalAiEngine.questionKey(it) in skip })
  }
}
