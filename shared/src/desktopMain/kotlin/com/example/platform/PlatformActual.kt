package com.example.platform

import androidx.compose.runtime.Composable

actual fun platformLog(level: Char, tag: String, message: String, error: Throwable?) {
  if (level == 'D') return
  val out = if (level == 'E' || level == 'W') System.err else System.out
  out.println("$level/$tag: $message")
  error?.printStackTrace(out)
}

/** Desktop windows have no system back gesture. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {}
