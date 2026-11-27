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

    // A sound chosen in Settings plays on loop instead of the phone's own alarm tone.
    val chosen = chosenSound(appContext)
    if (chosen != com.example.platform.AlarmSound.DEFAULT) {
      player = createPlayer(appContext, chosen, looping = true)
      if (player != null) return
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

  private var player: android.media.MediaPlayer? = null
  private var previewJob: Job? = null

  private fun chosenSound(context: Context): com.example.platform.AlarmSound =
    com.example.platform.AlarmSound.byName(
      context.getSharedPreferences(com.example.platform.AlarmSound.STORE, Context.MODE_PRIVATE)
        .getString(com.example.platform.AlarmSound.KEY, null)
    )

  /** A player for a bundled sound on the alarm stream, or null if it can't be loaded. */
  private fun createPlayer(context: Context, sound: com.example.platform.AlarmSound, looping: Boolean): android.media.MediaPlayer? {
    val name = sound.fileName ?: return null
    val id = context.resources.getIdentifier(name, "raw", context.packageName)
    if (id == 0) return null
    return try {
      android.media.MediaPlayer().apply {
        setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        setDataSource(context, android.net.Uri.parse("android.resource://${context.packageName}/$id"))
        isLooping = looping
        prepare()
        start()
      }
    } catch (_: Exception) {
      null
    }
  }

  /** Plays about four seconds of [sound] so it can be chosen in Settings. */
  @Synchronized
  fun preview(context: Context, sound: com.example.platform.AlarmSound) {
    previewJob?.cancel()
    player?.let { runCatching { it.stop(); it.release() } }
    player = null
    val appContext = context.applicationContext
    if (sound == com.example.platform.AlarmSound.DEFAULT) {
      val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return
      val tone = runCatching { RingtoneManager.getRingtone(appContext, uri) }.getOrNull() ?: return
      runCatching { tone.play() }
      previewJob = soundScope.launch { delay(4000); runCatching { tone.stop() } }
      return
    }
    val p = createPlayer(appContext, sound, looping = true) ?: return
    player = p
    previewJob = soundScope.launch {
      delay(4000)
      synchronized(this@AlarmSoundManager) {
        if (player === p) {
          runCatching { p.stop(); p.release() }
          player = null
        }
      }
    }
  }

  @Synchronized
  fun stopSoundAndVibration() {
    previewJob?.cancel()
    player?.let { runCatching { it.stop(); it.release() } }
    player = null
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
