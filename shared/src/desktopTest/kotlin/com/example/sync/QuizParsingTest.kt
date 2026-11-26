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

  @Test
  fun aRewordedAnswerIsStillTheSameQuestion() {
    val a = com.example.data.model.QuizQuestion(topicId = 1, subjectName = "", topicTitle = "", questionType = "MULTIPLE_CHOICE",
      questionText = "Which of the following best describes the base case in recursion?", correctAnswer = "The condition that stops the recursion.", explanation = "")
    val b = a.copy(questionText = "Which of the following best describes the base case in recursion ?", correctAnswer = "The case that ends the recursive calls.")
    assertEquals(LocalAiEngine.questionKey(a), LocalAiEngine.questionKey(b))
    // The built-in "from your notes" form has one wording for every statement, so there the answer counts.
    val c = a.copy(questionText = "Which of these is from your notes on Heaps?", correctAnswer = "Insert is O(log n).")
    assertTrue(LocalAiEngine.questionKey(c) != LocalAiEngine.questionKey(c.copy(correctAnswer = "Heaps are trees.")))
  }

  @Test
  fun rewordedQuestionsCountAsRepeats() {
    // Pairs the model produced in back-to-back quizzes on the same chats.
    val asked = listOf(
      "What is the time complexity of inserting into a binary heap? One sentence?",
      "Consider a scenario where you are managing a list of tasks with varying priorities. Which data structure would best suit this scenario, and why?",
      "Which of the following best describes the base case in recursion?"
    ).map { LocalAiEngine.keyWords(it) }
    assertTrue(LocalAiEngine.isNearDuplicate("Which of the following best describes the time complexity of inserting into a binary heap?", asked))
    assertTrue(LocalAiEngine.isNearDuplicate("Which data structure would best suit a scenario where you are managing a list of tasks with varying priorities, and you need to efficiently retrieve the task with the highest priority?", asked))
    assertTrue(LocalAiEngine.isNearDuplicate("Why is the base case important in recursion?", asked))
    // Different questions on the same topics are new.
    assertTrue(!LocalAiEngine.isNearDuplicate("Which of the following is a common misconception about recursion?", asked))
    assertTrue(!LocalAiEngine.isNearDuplicate("When a vector grows beyond its current capacity, how does it typically handle the expansion?", asked))
    assertTrue(!LocalAiEngine.isNearDuplicate("Which of these is from your notes on Heaps?", asked + listOf(LocalAiEngine.keyWords("Which of these is from your notes on Heaps?"))))
  }

  @Test
  fun builtInQuestionsReadLikeQuestions() {
    val notes = listOf(
      "network layer" to "The network layer routes packets between networks using IP addresses and routers along the path.",
      "can you explain me application layer in ip protocol" to "The application layer is where programs like browsers use protocols such as HTTP to exchange data.",
      "What is a heap data structure? Answer in two sentences." to "A heap is a tree-based structure where each parent is ordered relative to its children nodes.",
      "Explain the example. Answer only with a step-by-step walkthrough." to "First we push five, then ten, then pop which removes the ten from the top of the stack.",
      "explain transport layer" to "The transport layer gives end-to-end delivery between processes, with TCP and UDP as examples."
    ).joinToString("\n\n") { (q, a) -> "Q: $q\nA: $a" }
    val topic = StudyTopic(id = 1, userId = "u", subjectId = 0, subjectName = "", title = "Networks", dateStudied = "", descriptionNotes = notes, sourceType = "TUTOR_CHAT")
    val other = StudyTopic(id = 2, userId = "u", subjectId = 0, subjectName = "", title = "Other", dateStudied = "", descriptionNotes =
      "Recursion calls the same function on a smaller input until a base case is reached. A queue serves items in the order they arrived, first in first out. Binary search halves the search range on every step of the loop.")
    val texts = LocalAiEngine.mcqFromNotes(topic, 10, "EASY", LocalAiEngine.distractorPool(listOf(topic, other)))
      .map { it.questionText }.filterNot { it.startsWith("Which of these is from your") }.toSet()
    assertEquals(
      setOf(
        "Which of these is true about network layer?",
        "Which of these explains application layer in ip protocol?",
        "What is a heap data structure?",
        "Which of these explains transport layer?"
      ),
      texts
    )
  }
}
