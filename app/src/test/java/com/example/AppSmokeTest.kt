package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.StudyPilotTheme
import com.example.ui.viewmodel.Screen
import com.example.ui.viewmodel.StudyPilotViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders the whole app and visits every screen, so a crash in any of them fails the build. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class AppSmokeTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var app: Application

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    app.getSharedPreferences("studypilot_auth_prefs", Context.MODE_PRIVATE).edit().clear().commit()
  }

  private val allScreens = listOf(
    Screen.Dashboard, Screen.Timetable, Screen.MyTopics, Screen.WeekendQuizzes, Screen.Progress,
    Screen.AiTutor, Screen.Tasks, Screen.Settings, Screen.AiChatImport, Screen.Login, Screen.ActiveQuiz
  )

  @Test
  fun everyScreenRendersOnAPhone() = visitAllScreens()

  @Test
  @Config(sdk = [35], qualifiers = "w1280dp-h800dp")
  fun everyScreenRendersOnATablet() = visitAllScreens()

  private fun visitAllScreens() {
    val vm = StudyPilotViewModel(app)
    composeTestRule.setContent { StudyPilotTheme { StudyPilotApp(viewModel = vm) } }

    for (screen in allScreens) {
      composeTestRule.runOnIdle { vm.navigateTo(screen) }
      composeTestRule.waitForIdle()
      assertEquals(screen, vm.currentScreen.value)
    }
  }

  @Test
  fun ringingAlarmShowsBannerAndStops() {
    val vm = StudyPilotViewModel(app)
    composeTestRule.setContent { StudyPilotTheme { StudyPilotApp(viewModel = vm) } }

    val session = com.example.data.model.TimetableSession(
      id = 4242L, subjectId = 0L, subjectName = "Physics", topic = "Optics lab",
      date = "", startTime = "09:00", endTime = ""
    )
    composeTestRule.runOnIdle { vm.triggerTestAlarm(session) }
    composeTestRule.waitForIdle()
    assertTrue(composeTestRule.onAllNodesWithText("Alarm: Physics").fetchSemanticsNodes().isNotEmpty())

    composeTestRule.runOnIdle { vm.dismissAlarm() }
    composeTestRule.waitForIdle()
    assertTrue(composeTestRule.onAllNodesWithText("Alarm: Physics").fetchSemanticsNodes().isEmpty())
  }
}
