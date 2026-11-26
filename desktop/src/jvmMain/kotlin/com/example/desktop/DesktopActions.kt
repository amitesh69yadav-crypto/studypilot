package com.example.desktop

import com.example.data.model.TimetableSession
import com.example.data.schedule.ScheduleLogic
import com.example.platform.PlatformActions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

/** Browser, clipboard and messages on the PC. Google sign-in stays on the phone. */
class DesktopActions : PlatformActions {

  private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
  /** Short messages for the window's snackbar. */
  val messages: SharedFlow<String> = _messages

  override fun openUrl(url: String) {
    try {
      Desktop.getDesktop().browse(URI(url))
    } catch (_: Exception) {
      showMessage("Could not open $url")
    }
  }

  override fun copyText(text: String, confirmation: String?) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    confirmation?.let { showMessage(it) }
  }

  override fun showMessage(message: String) {
    _messages.tryEmit(message)
  }

  override fun addToCalendar(session: TimetableSession) = openUrl(ScheduleLogic.getGoogleCalendarWebUrl(session))

  override val googleSignIn: (suspend () -> PlatformActions.GoogleAccount?)? = null
}
