package net.tileringer.boop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Double-press on a Tile -> this phone yowls (alarm stream, so it plays on silent) until found. */
object FindPhoneAlarm {
    const val CHANNEL_ID = "found_me"
    private const val NOTIFICATION_ID = 2
    private const val MAX_YOWL_MS = 90_000L

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedAlarmVolume: Int? = null

    private val _alarmingTile = MutableStateFlow<String?>(null)
    /** Name of the Tile that triggered the current alarm, or null. */
    val alarmingTile: StateFlow<String?> = _alarmingTile
    val isAlarming get() = _alarmingTile.value != null

    fun start(context: Context, tileName: String) = main.post {
        if (isAlarming) return@post
        val ctx = context.applicationContext
        _alarmingTile.value = tileName
        val audio = ctx.getSystemService(AudioManager::class.java)
        runCatching {
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }.onFailure { Log.w("Boop", "could not raise alarm volume", it) }
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.w("Boop", "alarm sound failed", it) }
        ctx.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(ctx, tileName))
        main.postDelayed({ stop(ctx) }, MAX_YOWL_MS)
    }

    fun stop(context: Context) = main.post {
        if (!isAlarming) return@post
        val ctx = context.applicationContext
        main.removeCallbacksAndMessages(null)
        runCatching { player?.stop(); player?.release() }
        player = null
        savedAlarmVolume?.let { v -> runCatching { ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        savedAlarmVolume = null
        ctx.getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        _alarmingTile.value = null
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Phone finder", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shows when a Tile double-press is looking for this phone"
            setSound(null, null) // the alarm plays its own sound
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(ctx: Context, tileName: String): Notification {
        val found = PendingIntent.getService(
            ctx, 1, Intent(ctx, WatchService::class.java).setAction(WatchService.ACTION_FOUND_IT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cat)
            .setContentTitle("I'm right here!!")
            .setContentText("$tileName is looking for this phone")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Found it!", found).build())
            .build()
    }
}
