package com.example

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import com.example.ui.theme.MeshBackground
import com.example.ui.theme.BackdropPicker
import com.example.ui.theme.Backdrop
import com.example.ui.theme.AppStyle
import com.example.platform.phoneFonts
import com.example.platform.LocalAppearanceExtras
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.platform.AndroidActions
import com.example.platform.AndroidPlatform
import com.example.platform.LocalDeviceSyncCard
import com.example.platform.LocalPlatformActions
import com.example.platform.PcSyncCard
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.StudyPilotTheme
import com.example.ui.viewmodel.Screen
import com.example.ui.viewmodel.StudyPilotViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  companion object {
    const val EXTRA_RINGING_SESSION_ID = "extra_ringing_session_id"
  }

  private val viewModel: StudyPilotViewModel by viewModels {
    viewModelFactory { initializer { StudyPilotViewModel(AndroidPlatform.get(application)) } }
  }

  private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    handleAlarmIntent(intent)
    requestNotificationPermissionIfNeeded()

    keepSyncedWithPc()

    // Show over the lock screen only while an alarm is ringing, never for normal use.
    lifecycleScope.launch {
      viewModel.ringingAlarmSession.collect { showOverLockScreen(it != null) }
    }

    setContent {
      val themeMode by viewModel.themeMode.collectAsState()
      val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
      val isDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemDark
      }
      val actions = remember { AndroidActions(this) }
      val platform = AndroidPlatform.get(application)

      // The same painted backdrops as the PC: one for light mode, one for dark, both remembered.
      val uiPrefs = remember { platform.prefs("studypilot_ui_prefs") }
      var lightBackdrop by remember { mutableStateOf(Backdrop.byName(uiPrefs.getString("backdrop_light", null), Backdrop.OliveMist)) }
      var darkBackdrop by remember { mutableStateOf(Backdrop.byName(uiPrefs.getString("backdrop_dark", null), Backdrop.DarkForest)) }
      val backdrop = if (isDark) darkBackdrop else lightBackdrop
      val pickBackdrop: (Backdrop) -> Unit = { picked ->
        if (picked.dark) darkBackdrop = picked else lightBackdrop = picked
        uiPrefs.edit { putString(if (picked.dark) "backdrop_dark" else "backdrop_light", picked.name) }
        viewModel.setThemeMode(if (picked.dark) "dark" else "light")
      }

      CompositionLocalProvider(
        LocalPlatformActions provides actions,
        LocalDeviceSyncCard provides { PcSyncCard(platform.syncClient) },
        LocalAppearanceExtras provides { BackdropPicker(current = backdrop, onPick = pickBackdrop) }
      ) {
        // The phone uses the same glass design as the PC, laid out for a phone.
        StudyPilotTheme(darkTheme = isDark, style = AppStyle.Glass, fonts = phoneFonts()) {
          Box(Modifier.fillMaxSize()) {
            MeshBackground(backdrop)
            StudyPilotApp(viewModel = viewModel)
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleAlarmIntent(intent)
  }

  private fun handleAlarmIntent(intent: Intent?) {
    val ringingSessionId = intent?.getLongExtra(EXTRA_RINGING_SESSION_ID, 0L) ?: 0L
    if (ringingSessionId > 0) {
      showOverLockScreen(true)
      viewModel.showRingingAlarm(ringingSessionId)
    }
  }

  /** While the app is on screen: sync with the paired PC every 15 s and shortly after each local change. */
  @OptIn(FlowPreview::class)
  private fun keepSyncedWithPc() {
    val platform = AndroidPlatform.get(application)
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          while (true) {
            platform.syncClient.syncNow()
            delay(15_000)
          }
        }
        launch {
          platform.syncEngine.prepare()
          platform.database.invalidationTracker.createFlow(*platform.syncEngine.tableNames)
            .debounce(1_500)
            .collect { platform.syncClient.syncNow() }
        }
      }
    }
  }

  /** Alarms can't post their notification on Android 13+ without this permission. */
  private fun requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  private fun showOverLockScreen(show: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(show)
      setTurnScreenOn(show)
    } else {
      @Suppress("DEPRECATION")
      val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
      if (show) window.addFlags(flags) else window.clearFlags(flags)
    }
  }
}
