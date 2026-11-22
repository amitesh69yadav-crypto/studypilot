package com.example.data.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Plays the alarm: the system alarm ringtone on loop plus vibration, or beeps if no ringtone exists. */
object AlarmSoundManager {
  private val VIBRATION_PATTERN = longArrayOf(0, 600, 300, 600, 300, 600)

  private var ringtone: Ringtone? = null
  private var toneGenerator: ToneGenerator? = null
  private var toneJob: Job? = null
  private var vibrator: Vibrator? = null
  private val soundScope = CoroutineScope(Dispatchers.Default)

  @Synchronized
  fun startSoundAndVibration(context: Context) {
    stopSoundAndVibration()
    val appContext = context.applicationContext

    vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
      } else {
        @Suppress("DEPRECATION")
        vibrator?.vibrate(VIBRATION_PATTERN, 0)
      }
    } catch (_: Exception) {
      // Some devices have no vibrator.
    }

    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    ringtone = try {
      uri?.let { RingtoneManager.getRingtone(appContext, it) }?.apply {
        audioAttributes = AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ALARM)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
        play()
      }
    } catch (_: Exception) {
      null
    }

    // Beep on the alarm stream when there is no ringtone, or when it can't loop on its own.
    if (ringtone == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      startBeeping(onlyWhenRingtoneStops = ringtone != null)
    }
  }

  private fun startBeeping(onlyWhenRingtoneStops: Boolean) {
    val generator = try {
      ToneGenerator(AudioManager.STREAM_ALARM, 100)
    } catch (_: Exception) {
      return
    }
    toneGenerator = generator
    toneJob = soundScope.launch {
      while (isActive) {
        if (!onlyWhenRingtoneStops || ringtone?.isPlaying != true) {
          generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
        }
        delay(1400)
      }
    }
  }

  @Synchronized
  fun stopSoundAndVibration() {
    try {
      ringtone?.stop()
    } catch (_: Exception) {
    }
    ringtone = null

    toneJob?.cancel()
    toneJob = null
    try {
      toneGenerator?.release()
    } catch (_: Exception) {
    }
    toneGenerator = null

    try {
      vibrator?.cancel()
    } catch (_: Exception) {
    }
    vibrator = null
  }
}
