package com.example.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.platform.LocalAppearanceExtras
import com.example.ui.theme.AppStyle
import com.example.ui.theme.Backdrop
import com.example.ui.theme.MeshBackground
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.example.StudyPilotApp
import com.example.platform.LocalDeviceSyncCard
import com.example.platform.LocalPlatformActions
import com.example.shared.resources.Res
import com.example.shared.resources.studypilot_logo
import com.example.ui.components.formatTimeRange
import com.example.ui.theme.StudyPilotTheme
import com.example.ui.viewmodel.StudyPilotViewModel
import org.jetbrains.compose.resources.painterResource

fun main() {
  val platform = DesktopPlatform()
  val server = startSyncServer(platform.syncHost)
  val viewModel = StudyPilotViewModel(platform)
  val actions = DesktopActions()

  application {
    val windowState = rememberWindowState(size = DpSize(1240.dp, 820.dp))
    val trayState = rememberTrayState()
    val logo = painterResource(Res.drawable.studypilot_logo)

    Tray(icon = logo, state = trayState, tooltip = "StudyPilot", onAction = { windowState.isMinimized = false })

    Window(
      onCloseRequest = {
        server?.stop(500, 1000)
        exitApplication()
      },
      state = windowState,
      title = "StudyPilot",
      icon = logo
    ) {
      LaunchedEffect(Unit) {
        platform.desktopAlarms.onRing = { session ->
          windowState.isMinimized = false
          window.toFront()
          trayState.sendNotification(
            Notification("${session.subjectName}: ${session.topic}", formatTimeRange(session.startTime, session.endTime), Notification.Type.Info)
          )
        }
      }

      val themeMode by viewModel.themeMode.collectAsState()
      val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
      }
      val snackbar = remember { SnackbarHostState() }
      LaunchedEffect(Unit) { actions.messages.collect { snackbar.showSnackbar(it) } }

      // The painted backdrop: one for light mode, one for dark, both remembered.
      val uiPrefs = remember { platform.prefs("studypilot_ui_prefs") }
      var lightBackdrop by remember { mutableStateOf(Backdrop.byName(uiPrefs.getString("backdrop_light", null), Backdrop.OliveMist)) }
      var darkBackdrop by remember { mutableStateOf(Backdrop.byName(uiPrefs.getString("backdrop_dark", null), Backdrop.DarkForest)) }
      val backdrop = if (dark) darkBackdrop else lightBackdrop
      val pickBackdrop: (Backdrop) -> Unit = { picked ->
        if (picked.dark) darkBackdrop = picked else lightBackdrop = picked
        uiPrefs.edit { putString(if (picked.dark) "backdrop_dark" else "backdrop_light", picked.name) }
        viewModel.setThemeMode(if (picked.dark) "dark" else "light")
      }

      CompositionLocalProvider(
        LocalPlatformActions provides actions,
        LocalDeviceSyncCard provides { PhonePairingCard(platform.syncHost, serverRunning = server != null) },
        LocalAppearanceExtras provides { BackdropPicker(current = backdrop, onPick = pickBackdrop) }
      ) {
        StudyPilotTheme(darkTheme = dark, style = AppStyle.Glass, fonts = desktopFonts()) {
          Box(Modifier.fillMaxSize()) {
            MeshBackground(backdrop)
            StudyPilotApp(viewModel = viewModel)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
          }
        }
      }
    }
  }
}
