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
  /** Google sign-in returning an ID token for Firebase, or null where unsupported. */
  val googleSignIn: (suspend () -> GoogleAccount?)?

  data class GoogleAccount(val idToken: String, val displayName: String?, val email: String?)

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
