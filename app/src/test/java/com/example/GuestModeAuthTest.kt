package com.example

import com.example.platform.AndroidPlatform
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.UserProfile
import com.example.ui.viewmodel.Screen
import com.example.ui.viewmodel.StudyPilotViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuestModeAuthTest {

  private lateinit var app: Application

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    app.getSharedPreferences("studypilot_auth_prefs", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
  }

  @Test
  fun freshLaunchOpensInGuestModeWithNoDefaultPersonalLogin() {
    val vm = StudyPilotViewModel(AndroidPlatform.get(app))

    assertEquals(Screen.Dashboard, vm.currentScreen.value)
    assertFalse(vm.isAuthenticated.value)
    assertEquals(StudyPilotViewModel.GUEST_USER_ID, vm.currentUser.value.id)
    assertEquals("Guest", vm.currentUser.value.name)
    assertEquals("", vm.currentUser.value.email)
    assertFalse(vm.currentUser.value.isLoggedIn)
    assertTrue(vm.savedAccounts.value.isEmpty())
    assertEquals("", vm.getRememberedEmail())
  }

  @Test
  fun userProfileDefaultHasNoHardcodedPersonalInfo() {
    val defaultProfile = UserProfile()
    assertEquals("guest_user", defaultProfile.id)
    assertEquals("Guest", defaultProfile.name)
    assertEquals("", defaultProfile.email)
    assertFalse(defaultProfile.isLoggedIn)
  }

  @Test
  fun registeringAndLoggingInStoresUserInfoAcrossSessions() {
    val vm = StudyPilotViewModel(AndroidPlatform.get(app))

    val registered = vm.register(
      name = "Alice Johnson",
      email = "alice@example.edu",
      pass = "password123",
      university = "State University",
      studentId = "ALU-2026",
      rememberMe = true
    )
    assertTrue(registered)
    assertTrue(vm.isAuthenticated.value)
    assertEquals("Alice Johnson", vm.currentUser.value.name)
    assertEquals("alice@example.edu", vm.currentUser.value.email)
    assertEquals("State University", vm.currentUser.value.university)
    assertEquals("ALU-2026", vm.currentUser.value.studentId)
    assertEquals(1, vm.savedAccounts.value.size)
    assertEquals(Screen.Dashboard, vm.currentScreen.value)

    // Re-create ViewModel to verify user info stays stored across restarts
    val restartedVm = StudyPilotViewModel(AndroidPlatform.get(app))
    assertTrue(restartedVm.isAuthenticated.value)
    assertEquals("Alice Johnson", restartedVm.currentUser.value.name)
    assertEquals("alice@example.edu", restartedVm.currentUser.value.email)
    assertEquals("State University", restartedVm.currentUser.value.university)
    assertEquals("ALU-2026", restartedVm.currentUser.value.studentId)
    assertEquals(1, restartedVm.savedAccounts.value.size)
    assertEquals("alice@example.edu", restartedVm.getRememberedEmail())
  }

  @Test
  fun logoutReturnsToGuestModeWhileKeepingStoredAccount() {
    val vm = StudyPilotViewModel(AndroidPlatform.get(app))
    assertTrue(vm.login(email = "bob.smith@college.edu", pass = "secret123", rememberMe = true))
    assertTrue(vm.isAuthenticated.value)

    vm.logout()
    assertFalse(vm.isAuthenticated.value)
    assertEquals(StudyPilotViewModel.GUEST_USER_ID, vm.currentUser.value.id)
    assertEquals(Screen.Dashboard, vm.currentScreen.value)
    // Stored account info remains available in savedAccounts
    assertEquals(1, vm.savedAccounts.value.size)
    assertEquals("bob.smith@college.edu", vm.savedAccounts.value.first().email)

    // Re-opening after logout still starts cleanly in Guest Mode
    val reopened = StudyPilotViewModel(AndroidPlatform.get(app))
    assertFalse(reopened.isAuthenticated.value)
    assertEquals(StudyPilotViewModel.GUEST_USER_ID, reopened.currentUser.value.id)
    assertEquals(1, reopened.savedAccounts.value.size)

    // Switching back to an email profile asks for its password first
    reopened.switchAccount(reopened.savedAccounts.value.first())
    assertFalse(reopened.isAuthenticated.value)
    assertEquals(Screen.Login, reopened.currentScreen.value)
    assertEquals("bob.smith@college.edu", reopened.getRememberedEmail())

    assertTrue(reopened.login(email = "bob.smith@college.edu", pass = "secret123"))
    assertTrue(reopened.isAuthenticated.value)
    assertEquals("bob.smith@college.edu", reopened.currentUser.value.email)
  }

  @Test
  fun signingInRequiresTheProfilePassword() {
    val vm = StudyPilotViewModel(AndroidPlatform.get(app))
    assertTrue(vm.register(name = "Cara", email = "cara@uni.edu", pass = "correct-horse", university = ""))
    vm.logout()

    assertFalse(vm.login(email = "cara@uni.edu", pass = "wrong-password"))
    assertEquals("Incorrect password for cara@uni.edu.", vm.lastAuthError)
    assertFalse(vm.isAuthenticated.value)
    assertEquals(StudyPilotViewModel.GUEST_USER_ID, vm.currentUser.value.id)

    assertTrue(vm.login(email = "CARA@uni.edu", pass = "correct-horse"))
    assertEquals("cara@uni.edu", vm.currentUser.value.email)
  }

  @Test
  fun registeringAnExistingEmailIsRejected() {
    val vm = StudyPilotViewModel(AndroidPlatform.get(app))
    assertTrue(vm.register(name = "Dan", email = "dan@uni.edu", pass = "password1", university = ""))
    vm.logout()
    assertFalse(vm.register(name = "Impostor", email = "dan@uni.edu", pass = "password2", university = ""))
    assertEquals(1, vm.savedAccounts.value.size)
    assertEquals("Dan", vm.savedAccounts.value.first().name)
  }

  @Test
  fun legacySeededDefaultAccountsAreClearedOnMigration() {
    val prefs = app.getSharedPreferences("studypilot_auth_prefs", Context.MODE_PRIVATE)
    val legacyArr = JSONArray().apply {
      put(JSONObject().apply {
        put("id", "student_amitesh")
        put("name", "Legacy Default")
        put("email", "legacy@example.com")
      })
      put(JSONObject().apply {
        put("id", "student_sarah")
        put("name", "Legacy Two")
        put("email", "legacy2@example.com")
      })
    }
    prefs.edit()
      .putString("active_user_id", "student_amitesh")
      .putBoolean("is_logged_in", true)
      .putString("registered_students_json", legacyArr.toString())
      .remove("cleared_default_login_v1")
      .commit()

    val vm = StudyPilotViewModel(AndroidPlatform.get(app))
    assertFalse(vm.isAuthenticated.value)
    assertEquals(StudyPilotViewModel.GUEST_USER_ID, vm.currentUser.value.id)
    assertTrue(vm.savedAccounts.value.isEmpty())
    assertEquals(Screen.Dashboard, vm.currentScreen.value)
  }
}
