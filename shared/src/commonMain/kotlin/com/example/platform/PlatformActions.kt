package com.example.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.data.model.TimetableSession

/** Things screens do that depend on the platform: links, clipboard, short messages, calendar, sign-in. */
interface PlatformActions {
  fun openUrl(url: String)
  fun copyText(text: String, confirmation: String? = null)
  fun showMessage(message: String)
  /** Opens the calendar app's (or web calendar's) "new event" screen for this session. */
  fun addToCalendar(session: TimetableSession)
  /** Saves a file the user names and places (a Save dialog on the PC, Downloads on the phone). */
  fun saveFile(fileName: String, mimeType: String, bytes: ByteArray) {
    showMessage("Saving files isn't available here.")
  }
  /** Google sign-in returning an ID token for Firebase, or null where unsupported. */
  val googleSignIn: (suspend () -> GoogleAccount?)?

  data class GoogleAccount(val idToken: String, val displayName: String?, val email: String?)

  /**
   * Asks Google for read-only access to the student's Classroom courses and returns an access
   * token, or null if they said no. Null where unsupported (the PC).
   */
  val googleClassroomToken: (suspend () -> String?)? get() = null

  /** Does nothing; used in previews and tests. */
  object None : PlatformActions {
    override fun openUrl(url: String) {}
    override fun copyText(text: String, confirmation: String?) {}
    override fun showMessage(message: String) {}
    override fun addToCalendar(session: TimetableSession) {}
    override val googleSignIn: (suspend () -> GoogleAccount?)? = null
  }
}

val LocalPlatformActions = staticCompositionLocalOf<PlatformActions> { PlatformActions.None }

/** System back gesture; a no-op on desktop. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** "#8C9F85" or "8C9F85" to a Color, or null if it isn't a hex colour. */
fun parseHexColor(hex: String): Color? {
  val clean = hex.trim().removePrefix("#")
  val value = clean.toLongOrNull(16) ?: return null
  return when (clean.length) {
    6 -> Color(0xFF000000 or value)
    8 -> Color(value)
    else -> null
  }
}

private val EMAIL = Regex("""^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$""")

fun isValidEmail(text: String): Boolean = EMAIL.matches(text.trim())

/** The "Phone" (on the PC) or "PC" (on the phone) card shown at the top of Settings. */
val LocalDeviceSyncCard = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/** Extra appearance settings shown under the theme choice (the desktop's backdrops). */
val LocalAppearanceExtras = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }
