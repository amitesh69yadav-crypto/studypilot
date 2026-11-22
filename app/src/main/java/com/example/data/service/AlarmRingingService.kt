package com.example.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.data.model.TimetableSession
import com.example.ui.components.formatTimeRange

/**
 * Foreground service that rings an alarm: one notification, looping sound and vibration,
 * stopped by Dismiss/Snooze or automatically after [RING_TIMEOUT_MS].
 */
class AlarmRingingService : Service() {

  companion object {
    private const val CHANNEL_ID = "study_alarms_v2"
    private val LEGACY_CHANNEL_IDS = listOf("studypilot_foreground_alarm_channel", "studypilot_alarms_channel")
    const val NOTIFICATION_ID = 8881
    private const val RING_TIMEOUT_MS = 5 * 60_000L

    private const val ACTION_START_RINGING = "com.example.data.service.ACTION_START_RINGING"

    /** Id of the session whose alarm is ringing right now, or null. */
    @Volatile
    var ringingSessionId: Long? = null
      private set

    fun startRinging(context: Context, session: TimetableSession) {
      val intent = Intent(context, AlarmRingingService::class.java).apply {
        action = ACTION_START_RINGING
        AlarmReceiver.putSessionExtras(this, session)
      }
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (e: Exception) {
        // Background start can be refused (e.g. app in background without an alarm exemption).
        android.util.Log.w("AlarmRingingService", "Could not start ringing service", e)
        AlarmSoundManager.startSoundAndVibration(context)
        ringingSessionId = session.id
      }
    }

    fun stopRinging(context: Context) {
      ringingSessionId = null
      AlarmSoundManager.stopSoundAndVibration()
      context.stopService(Intent(context, AlarmRingingService::class.java))
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null
  private val handler = Handler(Looper.getMainLooper())
  private val timeout = Runnable { stopRinging(applicationContext) }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action != ACTION_START_RINGING) {
      stopSelf()
      return START_NOT_STICKY
    }
    val session = AlarmReceiver.sessionFromExtras(intent)
    ringingSessionId = session.id

    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(session), type)

    acquireWakeLock()
    AlarmSoundManager.startSoundAndVibration(applicationContext)
    handler.removeCallbacks(timeout)
    handler.postDelayed(timeout, RING_TIMEOUT_MS)

    AlarmEventBus.emit(
      AlarmEvent.Triggered(
        sessionId = session.id,
        subjectName = session.subjectName,
        topic = session.topic,
        startTime = session.startTime,
        endTime = session.endTime,
        location = session.location,
        notes = session.notes
      )
    )
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    handler.removeCallbacks(timeout)
    AlarmSoundManager.stopSoundAndVibration()
    releaseWakeLock()
    ringingSessionId = null
    super.onDestroy()
  }

  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StudyPilot:AlarmWakeLock").apply {
      acquire(RING_TIMEOUT_MS + 10_000L)
    }
  }

  private fun releaseWakeLock() {
    wakeLock?.takeIf { it.isHeld }?.release()
    wakeLock = null
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    LEGACY_CHANNEL_IDS.forEach { manager.deleteNotificationChannel(it) }
    // The service plays the alarm sound itself, so the channel stays silent to avoid a second sound.
    val channel = NotificationChannel(CHANNEL_ID, "Study alarms", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Rings when a scheduled class or study session starts"
      setSound(null, null)
      enableVibration(false)
    }
    manager.createNotificationChannel(channel)
  }

  private fun buildNotification(session: TimetableSession): Notification {
    val showIntent = Intent(applicationContext, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra(MainActivity.EXTRA_RINGING_SESSION_ID, session.id)
    }
    val showPendingIntent = PendingIntent.getActivity(
      applicationContext,
      AlarmAndCalendarService.requestCode(session.id, AlarmAndCalendarService.RequestCode.TRIGGER),
      showIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun receiverIntent(action: String, kind: AlarmAndCalendarService.RequestCode): PendingIntent {
      val intent = Intent(applicationContext, AlarmReceiver::class.java).apply {
        this.action = action
        AlarmReceiver.putSessionExtras(this, session)
      }
      return PendingIntent.getBroadcast(
        applicationContext,
        AlarmAndCalendarService.requestCode(session.id, kind),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
    }

    val title = session.subjectName.ifBlank { session.topic }
    val time = formatTimeRange(session.startTime, session.endTime)
    val details = buildString {
      append(session.topic)
      if (time.isNotBlank()) append(" · ").append(time)
      if (session.location.isNotBlank()) append("\n").append(session.location)
    }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle(title)
      .setContentText(details.lineSequence().first())
      .setStyle(NotificationCompat.BigTextStyle().bigText(details))
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setOngoing(true)
      .setFullScreenIntent(showPendingIntent, true)
      .setContentIntent(showPendingIntent)
      .addAction(
        android.R.drawable.ic_menu_close_clear_cancel, "Dismiss",
        receiverIntent(AlarmReceiver.ACTION_DISMISS_ALARM, AlarmAndCalendarService.RequestCode.DISMISS)
      )
      .addAction(
        android.R.drawable.ic_popup_reminder, "Snooze ${AlarmReceiver.SNOOZE_MINUTES} min",
        receiverIntent(AlarmReceiver.ACTION_SNOOZE_ALARM, AlarmAndCalendarService.RequestCode.SNOOZE_ACTION)
      )
      .build()
  }
}
