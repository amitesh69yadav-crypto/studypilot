package com.example.platform

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

actual fun platformLog(level: Char, tag: String, message: String, error: Throwable?) {
  when (level) {
    'D' -> android.util.Log.d(tag, message, error)
    'I' -> android.util.Log.i(tag, message, error)
    'W' -> android.util.Log.w(tag, message, error)
    else -> android.util.Log.e(tag, message, error)
  }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
