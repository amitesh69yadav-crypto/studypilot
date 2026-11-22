package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ChatMessage
import com.example.data.model.QuizQuestion
import com.example.data.model.TimetableSession
import com.example.data.remote.GeminiStudyService
import com.example.data.service.AlarmAndCalendarService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmSchedulingTest {

  private lateinit var service: AlarmAndCalendarService

  @Before
  fun setUp() {
    service = AlarmAndCalendarService(ApplicationProvider.getApplicationContext<Application>())
  }

  private fun session(date: String, start: String, recurring: String = "") = TimetableSession(
    id = 1L, subjectId = 0L, subjectName = "Maths", topic = "Lecture",
    date = date, startTime = start, endTime = "", recurringDays = recurring
  )

  /** Wednesday 2026-11-18 at 12:00. */
  private val wednesdayNoon = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 18, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
    Calendar.getInstance().apply { set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

  @Test
  fun oneOffSessionRingsAtItsDateAndTime() {
    val trigger = service.computeSessionTriggerTimeMillis(session("2026-11-20", "09:30"), now = wednesdayNoon)
    assertEquals(at(2026, Calendar.NOVEMBER, 20, 9, 30), trigger)
  }

  @Test
  fun pastOneOffSessionDoesNotRollForward() {
    val trigger = service.computeSessionTriggerTimeMillis(session("2026-11-16", "09:30"), now = wednesdayNoon)
    assertTrue(trigger < wednesdayNoon)
  }

  @Test
  fun recurringSessionRingsOnTheNextMatchingWeekday() {
    // Created on Monday the 16th for Mon/Fri. It's Wednesday noon, so the next one is Friday the 20th.
    val trigger = service.computeSessionTriggerTimeMillis(session("2026-11-16", "09:00", "Mon,Fri"), now = wednesdayNoon)
    assertEquals(at(2026, Calendar.NOVEMBER, 20, 9, 0), trigger)
  }

  @Test
  fun recurringSessionLaterTodayRingsToday() {
    val trigger = service.computeSessionTriggerTimeMillis(session("2026-11-16", "15:00", "Mon,Wed"), now = wednesdayNoon)
    assertEquals(at(2026, Calendar.NOVEMBER, 18, 15, 0), trigger)
  }

  @Test
  fun recurringSessionAlreadyPastTodayWaitsAWeek() {
    val trigger = service.computeSessionTriggerTimeMillis(session("2026-11-16", "08:00", "Wed"), now = wednesdayNoon)
    assertEquals(at(2026, Calendar.NOVEMBER, 25, 8, 0), trigger)
  }

  @Test
  fun recurringSessionDoesNotStartBeforeItsFirstDate() {
    val trigger = service.computeSessionTriggerTimeMillis(session("2026-11-27", "10:00", "Mon,Fri"), now = wednesdayNoon)
    assertEquals(at(2026, Calendar.NOVEMBER, 27, 10, 0), trigger)
  }

  @Test
  fun requestCodesDoNotCollideAcrossSessionsOrPurposes() {
    val codes = (1L..500L).flatMap { id ->
      AlarmAndCalendarService.RequestCode.values().map { AlarmAndCalendarService.requestCode(id, it) }
    }
    assertEquals(codes.size, codes.toSet().size)
  }

  @Test
  fun geminiChatHistoryStartsWithUserAndAlternates() {
    val history = listOf(
      ChatMessage(role = "model", text = "Ask me anything"),
      ChatMessage(role = "user", text = "What is a heap?"),
      ChatMessage(role = "user", text = "And a min-heap?"),
      ChatMessage(role = "model", text = "Network error", isError = true),
      ChatMessage(role = "model", text = "A heap is a tree..."),
      ChatMessage(role = "user", text = "Thanks")
    )
    val contents = GeminiStudyService().buildChatContents(history)
    val roles = (0 until contents.length()).map { contents.getJSONObject(it).getString("role") }
    assertEquals(listOf("user", "model", "user"), roles)
    val firstText = contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
    assertEquals("What is a heap?\n\nAnd a min-heap?", firstText)
  }

  @Test
  fun choiceQuestionsAreGradedWithoutTheNetwork() = runBlocking {
    val question = QuizQuestion(
      topicId = 1L, subjectName = "CS", topicTitle = "Trees", questionType = "MULTIPLE_CHOICE",
      questionText = "AVL search complexity?", optionsJson = "O(log N),O(N)", correctAnswer = "O(log N)", explanation = ""
    )
    val gemini = GeminiStudyService().apply { customGeminiKey = "fake-key-that-would-fail" }
    assertTrue(gemini.evaluateAnswer(question, "O(log N)").isCorrect)
    assertFalse(gemini.evaluateAnswer(question, "O(N)").isCorrect)
  }

  @Test
  fun recurringDaysAcceptFullNamesAndSpaces() {
    val a = service.computeSessionTriggerTimeMillis(session("2026-11-16", "09:00", "Friday"), now = wednesdayNoon)
    val b = service.computeSessionTriggerTimeMillis(session("2026-11-16", "09:00", " fri "), now = wednesdayNoon)
    assertEquals(a, b)
    assertNotEquals(0L, a)
  }
}
