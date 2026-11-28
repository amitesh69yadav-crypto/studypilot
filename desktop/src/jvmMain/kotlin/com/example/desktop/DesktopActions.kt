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

  override fun openFile(path: String) {
    val file = java.io.File(path)
    if (!file.isFile) {
      showMessage("That file isn't on this PC any more. Import from Canvas again to get it back.")
      return
    }
    try {
      Desktop.getDesktop().open(file)
    } catch (e: Exception) {
      showMessage("Couldn't open it: ${e.message ?: "no app for this file type"}")
    }
  }

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

  /** Asks where to save with the Windows Save dialog, writes the file, then opens it. */
  override fun saveFile(fileName: String, mimeType: String, bytes: ByteArray) {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Save report", java.awt.FileDialog.SAVE).apply {
      directory = System.getProperty("user.home") + java.io.File.separator + "Documents"
      file = fileName
      isVisible = true
    }
    val name = dialog.file ?: return
    val ext = fileName.substringAfterLast('.', "")
    val target = java.io.File(dialog.directory, if (ext.isNotEmpty() && !name.endsWith(".$ext", ignoreCase = true)) "$name.$ext" else name)
    try {
      target.writeBytes(bytes)
      showMessage("Saved ${target.name}")
      runCatching { Desktop.getDesktop().open(target) }
    } catch (e: Exception) {
      showMessage("Couldn't save the file: ${e.message ?: "unknown error"}")
    }
  }

  override val googleSignIn: (suspend () -> PlatformActions.GoogleAccount?)? = null
}
