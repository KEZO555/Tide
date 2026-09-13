package com.thelightphone.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Foreground media service that keeps a background-audio tool's process alive
 * while it plays. It is driven entirely by [LightBackgroundAudio]: the
 * notification mirrors the current [LightBackgroundAudio.NowPlaying], and its
 * action buttons forward to the tool's transport [LightBackgroundAudio.Controller].
 *
 * The service holds no player itself — its job is only to be a foreground
 * service so Android/LightOS won't freeze the process, letting the tool's own
 * player keep advancing tracks in the background.
 */
class LightPlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        observing = scope.launch {
            // onStartCommand already shows the value present at start; react only
            // to later changes — refresh the notification, or stop when cleared.
            LightBackgroundAudio.nowPlaying.drop(1).collect { np ->
                if (np == null) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(np))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> LightBackgroundAudio.dispatchTogglePlay()
            ACTION_NEXT -> LightBackgroundAudio.dispatchNext()
            ACTION_PREVIOUS -> LightBackgroundAudio.dispatchPrevious()
            ACTION_STOP -> LightBackgroundAudio.stop()
        }
        val np = LightBackgroundAudio.nowPlaying.value
        if (np == null) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        // Must call startForeground promptly after startForegroundService().
        startForegroundCompat(buildNotification(np))
        return START_STICKY
    }

    override fun onDestroy() {
        observing?.cancel()
        LightBackgroundAudio.markServiceDestroyed()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(np: LightBackgroundAudio.NowPlaying): Notification {
        val playPauseIcon = if (np.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseLabel = if (np.isPlaying) "Pause" else "Play"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(np.title.ifBlank { "Playing" })
            .setContentText(np.artist)
            .setContentIntent(launchIntent())
            .setOngoing(np.isPlaying)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", serviceAction(ACTION_PREVIOUS))
            .addAction(playPauseIcon, playPauseLabel, serviceAction(ACTION_TOGGLE))
            .addAction(android.R.drawable.ic_media_next, "Next", serviceAction(ACTION_NEXT))
            .build()
    }

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, intent, pendingIntentFlags())
    }

    private fun serviceAction(action: String): PendingIntent {
        val intent = Intent(this, LightPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Media playback controls"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private companion object {
        const val CHANNEL_ID = "light_playback"
        const val NOTIFICATION_ID = 0x11D3
        const val ACTION_TOGGLE = "com.thelightphone.sdk.playback.TOGGLE"
        const val ACTION_NEXT = "com.thelightphone.sdk.playback.NEXT"
        const val ACTION_PREVIOUS = "com.thelightphone.sdk.playback.PREVIOUS"
        const val ACTION_STOP = "com.thelightphone.sdk.playback.STOP"
    }
}
