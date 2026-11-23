package net.tileringer.boop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.Manifest
import androidx.core.content.edit

/** Foreground service that keeps [Watcher] alive. Android requires the ongoing notification. */
class WatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FOUND_IT -> {
                FindPhoneAlarm.stop(this)
                if (!Watcher.running.value) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                Watcher.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        createChannels(this)
        startForeground(NOTIFICATION_ID, watchNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        Watcher.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        Watcher.stop()
        super.onDestroy()
    }

    private fun watchNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, WATCH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cat)
            .setContentTitle("Kitty watch is on")
            .setContentText("Press a Tile twice to make this phone yowl")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_FOUND_IT = "net.tileringer.boop.FOUND_IT"
        const val ACTION_STOP = "net.tileringer.boop.STOP_WATCH"
        private const val WATCH_CHANNEL_ID = "kitty_watch"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "boop"
        private const val KEY_WATCH = "watch_enabled"

        val requiredPermissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_WATCH, false)

        fun hasPermissions(context: Context) = requiredPermissions.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit { putBoolean(KEY_WATCH, enabled) }
            if (enabled) context.startForegroundService(Intent(context, WatchService::class.java))
            else context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP))
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(WATCH_CHANNEL_ID, "Kitty watch", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Ongoing while Boop listens for Tile double-presses"
                },
            )
            FindPhoneAlarm.createChannel(context)
        }
    }
}

/** Restarts Kitty watch after a reboot or app update, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for the system's broadcasts, so ignore anything else another app might send.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (WatchService.isEnabled(context) && WatchService.hasPermissions(context)) {
            context.startForegroundService(Intent(context, WatchService::class.java))
        }
    }
}
