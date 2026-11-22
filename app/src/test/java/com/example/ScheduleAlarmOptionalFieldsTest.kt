package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Subject
import com.example.data.model.TimetableSession
import com.example.data.service.AlarmAndCalendarService
import com.example.data.service.ScheduleState
import com.example.ui.components.formatTimeRange
import com.example.ui.screens.GoogleCalendarEventCard
import com.example.ui.screens.SessionEditDialog
import com.example.ui.theme.StudyPilotTheme
import com.example.ui.viewmodel.StudyPilotViewModel
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScheduleAlarmOptionalFieldsTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var app: Application
  private lateinit var service: AlarmAndCalendarService

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    app.getSharedPreferences("studypilot_auth_prefs", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    service = AlarmAndCalendarService(app)
  }

  @Test
  fun formatTimeRangeOmitsDashWhenEndTimeBlank() {
    assertEquals("7:30 AM", formatTimeRange("07:30", ""))
    assertEquals("10:00 AM", formatTimeRange("10:00", "   "))
    assertEquals("7:30 AM - 8:30 AM", formatTimeRange("07:30", "08:30"))
  }

  @Test
  fun evaluateCurrentStatusTreatsBlankEndTimeAsStartTimeAlarmOnly() {
    val todayCal = Calendar.getInstance()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val todayStr = dateFormat.format(todayCal.time)

    val alarmSession = TimetableSession(
      id = 42L,
      subjectId = 0L,
      subjectName = "Alarm",
      topic = "Morning Wakeup",
      date = todayStr,
      startTime = "07:30",
      endTime = "",
      location = "",
      isAlarmEnabled = true
    )

    // 1. Before startTime (07:00) -> UPCOMING_NEXT
    val beforeCal = (todayCal.clone() as Calendar).apply {
      set(Calendar.HOUR_OF_DAY, 7)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
    }
    val beforeStatus = service.evaluateCurrentStatus(listOf(alarmSession), beforeCal.time)
    assertEquals(ScheduleState.UPCOMING_NEXT, beforeStatus.state)
    assertEquals(30, beforeStatus.minutesUntilNext)

    // 2. Exactly at startTime (07:30) -> ACTIVE_NOW (ringing alarm moment)
    val exactCal = (todayCal.clone() as Calendar).apply {
      set(Calendar.HOUR_OF_DAY, 7)
      set(Calendar.MINUTE, 30)
      set(Calendar.SECOND, 0)
    }
    val activeStatus = service.evaluateCurrentStatus(listOf(alarmSession), exactCal.time)
    assertEquals(ScheduleState.ACTIVE_NOW, activeStatus.state)
    assertEquals(0, activeStatus.minutesRemaining)

    // 3. After startTime (07:31, still before 09:00) -> DAY_COMPLETE (never falsely active up to 9:00 AM)
    val afterCal = (todayCal.clone() as Calendar).apply {
      set(Calendar.HOUR_OF_DAY, 7)
      set(Calendar.MINUTE, 31)
      set(Calendar.SECOND, 0)
    }
    val afterStatus = service.evaluateCurrentStatus(listOf(alarmSession), afterCal.time)
    assertEquals(ScheduleState.DAY_COMPLETE, afterStatus.state)
  }

  @Test
  fun computeSessionTriggerTimeMillisMatchesStartTimeWhenOptionalFieldsBlank() {
    val cal = Calendar.getInstance().apply {
      add(Calendar.DAY_OF_YEAR, 1)
      set(Calendar.HOUR_OF_DAY, 6)
      set(Calendar.MINUTE, 45)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)

    val session = TimetableSession(
      id = 77L,
      subjectId = 0L,
      subjectName = "Alarm",
      topic = "Scheduled Alarm",
      date = dateStr,
      startTime = "06:45",
      endTime = "",
      location = "",
      isAlarmEnabled = true
    )

    val triggerMillis = service.computeSessionTriggerTimeMillis(session)
    assertEquals(cal.timeInMillis, triggerMillis)
  }

  @Test
  fun saveScheduleDialogWorksWithoutSubjectCampusEndTimeOrTopic() {
    var savedSession: TimetableSession? = null
    val sampleSubjects = listOf(
      Subject(id = 1L, name = "Algorithms", code = "CS201", colorHex = "#8C9F85")
    )

    composeTestRule.setContent {
      StudyPilotTheme {
        SessionEditDialog(
          sessionToEdit = null,
          subjects = sampleSubjects,
          todayDate = "2026-11-20",
          onDismiss = {},
          onConfirm = { savedSession = it }
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("save_schedule_button").performClick()
    composeTestRule.waitForIdle()

    assertNotNull(savedSession)
    val result = savedSession!!
    assertEquals(0L, result.subjectId)
    assertEquals("Alarm", result.subjectName)
    assertEquals("", result.location)
    assertEquals("", result.endTime)
    assertEquals("10:00", result.startTime)
    assertEquals("2026-11-20", result.date)
    assertEquals("Scheduled Alarm", result.topic)
    assertTrue(result.isAlarmEnabled)
  }

  @Test
  fun googleCalendarWebUrlDefaultsEndTimeToThirtyMinutesAfterStartWhenBlank() {
    val session = TimetableSession(
      id = 91L,
      subjectId = 0L,
      subjectName = "Alarm",
      topic = "Morning Wakeup Alarm",
      date = "2026-11-21",
      startTime = "06:30",
      endTime = "",
      location = "",
      isAlarmEnabled = true
    )

    // The URL carries UTC times, so pin the device zone to UTC to make 06:30 local == 063000Z.
    val originalZone = java.util.TimeZone.getDefault()
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    try {
      val url = service.getGoogleCalendarWebUrl(session)
      assertTrue(url.contains("Morning+Wakeup+Alarm"))
      assertTrue(url.contains("20261121T063000Z/20261121T070000Z"))
    } finally {
      java.util.TimeZone.setDefault(originalZone)
    }
  }

  @Test
  fun repositoryPersistsScheduleWithNoSubjectEmptyCampusAndEmptyEndTime() = kotlinx.coroutines.runBlocking {
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      app,
      com.example.data.local.AppDatabase::class.java
    ).allowMainThreadQueries().build()
    try {
      val repo = com.example.data.repository.StudyPilotRepository(db.studyPilotDao(), context = app)
      val uniqueTopic = "Early Alarm Only Test"
      val session = TimetableSession(
        userId = StudyPilotViewModel.GUEST_USER_ID,
        subjectId = 0L,
        subjectName = "Alarm",
        topic = uniqueTopic,
        date = "2026-11-21",
        startTime = "06:30",
        endTime = "",
        location = "",
        isAlarmEnabled = true
      )

      val insertedId = repo.addSession(session)
      assertTrue(insertedId > 0L)

      val list = repo.getSessionsForUser(StudyPilotViewModel.GUEST_USER_ID).first()
      val stored = list.firstOrNull { it.id == insertedId }
      assertNotNull(stored)
      assertEquals(uniqueTopic, stored!!.topic)
      assertEquals(0L, stored.subjectId)
      assertEquals("Alarm", stored.subjectName)
      assertEquals("06:30", stored.startTime)
      assertEquals("", stored.endTime)
      assertEquals("", stored.location)
      assertTrue(stored.isAlarmEnabled)
    } finally {
      db.close()
    }
  }

  @Test
  fun eventCardDisplaysAllActionsWithoutClippingOrOverlap() {
    val alarmSession = TimetableSession(
      id = 55L,
      subjectId = 0L,
      subjectName = "Alarm",
      topic = "Scheduled Alarm",
      date = "2026-11-15",
      startTime = "10:00",
      endTime = "",
      location = "",
      isAlarmEnabled = true
    )

    composeTestRule.setContent {
      StudyPilotTheme {
        GoogleCalendarEventCard(
          session = alarmSession,
          onStatusChange = {},
          onEdit = {},
          onToggleAlarm = {},
          onTestAlarm = {},
          onAskAiTutor = {},
          onSyncCalendar = {},
          onDelete = {}
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("schedule_event_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("10:00 AM").assertIsDisplayed()
    composeTestRule.onNodeWithText("Alarm").assertIsDisplayed()
    composeTestRule.onNodeWithText("Scheduled Alarm").assertIsDisplayed()
    composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    composeTestRule.onNodeWithText("AI Tutor").assertIsDisplayed()
    composeTestRule.onNodeWithText("Alarm On").assertIsDisplayed()
    composeTestRule.onNodeWithText("Ring").assertIsDisplayed()
    composeTestRule.onNodeWithText("Calendar").assertIsDisplayed()
  }
}
