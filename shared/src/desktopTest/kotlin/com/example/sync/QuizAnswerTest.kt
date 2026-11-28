package com.example.sync

import com.example.data.ai.LocalAiEngine
import com.example.data.ai.StudyAi
import com.example.data.model.QuizQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Choice questions: only the right option is right, and code options show as code. */
class QuizAnswerTest {

  private val right = "public class Car {\n    private String color;\n}"
  private val question = QuizQuestion(
    topicId = 1, subjectName = "Algo", topicTitle = "Naming", questionType = "MULTIPLE_CHOICE", difficulty = "MEDIUM",
    questionText = "Which follows the naming conventions?",
    optionsJson = StudyAi.encodeOptions(listOf(right, "public class car {\n    private String color;\n}", "public class Car {\n    private String Color;\n}")),
    correctAnswer = right, explanation = ""
  )

  @Test
  fun anOptionThatDiffersOnlyInCaseIsWrong() {
    assertTrue(LocalAiEngine.evaluateAnswer(question, right).isCorrect)
    assertFalse(LocalAiEngine.evaluateAnswer(question, "public class car {\n    private String color;\n}").isCorrect)
    assertFalse(LocalAiEngine.evaluateAnswer(question, "public class Car {\n    private String Color;\n}").isCorrect)
    // Code with newlines is stored with "|" so it splits back into the same options.
    assertEquals(3, question.optionsJson.split("|").size)
  }

  @Test
  fun aLiteralBackslashNIsALineBreak() {
    assertEquals("class A {\n    int x;\n}", StudyAi.unescape("class A {\\n\\tint x;\\n}"))
  }
}
