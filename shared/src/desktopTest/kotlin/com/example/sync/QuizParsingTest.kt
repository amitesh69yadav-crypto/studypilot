package com.example.sync

import com.example.data.ai.LocalAiEngine
import com.example.data.ai.StudyAi
import com.example.data.model.StudyTopic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuizParsingTest {

  private val ai = StudyAi(null)

  @Test
  fun singleQuestionObjectFromOllamaJsonMode() {
    // What qwen3:8b returned with format=json: one object whose only list is optionsJson.
    val raw = """
      {"questionType": "MULTIPLE_CHOICE", "difficulty": "MIXED", "questionText": "What stops a recursive function?",
       "optionsJson": ["A base case", "A loop", "A global flag", "Nothing"], "correctAnswer": "A base case",
       "explanation": "Without one it never ends.", "codeStarter": null}
    """.trimIndent()
    val questions = ai.parseQuestions(raw, "MIXED") { it }
    assertEquals(1, questions.size)
    assertEquals("MULTIPLE_CHOICE", questions[0].questionType)
    assertEquals("A base case", questions[0].correctAnswer)
  }

  @Test
  fun emptyOptionsListIsNotMistakenForTheQuestions() {
    val raw = """{"questionType": "SHORT_ANSWER", "questionText": "Explain recursion.", "optionsJson": [], "correctAnswer": "A function calling itself on a smaller input."}"""
    assertEquals(1, ai.parseQuestions(raw, "EASY") { it }.size)
  }

  @Test
  fun wrappedQuestionList() {
    val raw = """{"questions": [
      {"questionType": "TRUE_FALSE", "questionText": "Heaps are complete binary trees.", "optionsJson": ["True", "False"], "correctAnswer": "True"},
      {"questionType": "SHORT_ANSWER", "questionText": "Cost of heap insert?", "optionsJson": [], "correctAnswer": "O(log n)"}
    ]}"""
    assertEquals(2, ai.parseQuestions(raw, "MEDIUM") { it }.size)
  }

  @Test
  fun fallbackQuestionsComeFromTheConversation() {
    val topic = StudyTopic(
      id = 7, userId = "u", subjectId = 0, subjectName = "General", title = "Heaps", dateStudied = "2026-11-26",
      sourceType = "TUTOR_CHAT",
      descriptionNotes = "Q: What is a binary heap?\nA: A binary heap is a complete binary tree. Every parent is ordered relative to its children. Insert is O(log n).\n\n" +
        "Q: how fast is extract min\nA: Extract-min takes O(log n) because the last element is moved to the root and sifted down."
    )
    val questions = LocalAiEngine.questionsFromNotes(topic, 3, "EASY")
    assertEquals(3, questions.size)
    // The student's own questions first, then points from the answers.
    assertEquals(listOf("What is a binary heap?", "How fast is extract min?"), questions.take(2).map { it.questionText })
    assertTrue("Extract-min" in questions[2].questionText)
    assertTrue(questions[0].correctAnswer.startsWith("A binary heap is a complete binary tree."))
    assertTrue(questions.all { it.topicId == 7L && it.questionType == "SHORT_ANSWER" })
  }
}
