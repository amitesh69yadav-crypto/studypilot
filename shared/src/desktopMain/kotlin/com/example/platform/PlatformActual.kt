package com.example.platform

import androidx.compose.runtime.Composable

actual fun platformLog(level: Char, tag: String, message: String, error: Throwable?) {
  if (level == 'D') return
  val out = if (level == 'E' || level == 'W') System.err else System.out
  out.println("$level/$tag: $message")
  error?.printStackTrace(out)
  // The installed app has no console: warnings and errors also go to a small file next to the data.
  if ((level == 'W' || level == 'E') && System.getProperty("studypilot.log") != "off") runCatching {
    val dir = java.io.File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "StudyPilot")
    val file = java.io.File(dir, "studypilot.log")
    if (file.length() > 512 * 1024) file.renameTo(java.io.File(dir, "studypilot.old.log"))
    val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
    val cause = error?.let { e -> " | ${e.javaClass.simpleName}: ${e.message}" + (e.cause?.let { " <- ${it.javaClass.simpleName}: ${it.message}" } ?: "") }.orEmpty()
    synchronized(LOG_LOCK) { file.appendText("$time $level/$tag: $message$cause\n") }
  }
}

private val LOG_LOCK = Any()

/** Desktop windows have no system back gesture. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {}
