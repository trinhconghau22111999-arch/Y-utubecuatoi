package com.ytbrowser.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground Service tối giản - chỉ dùng để giữ tiến trình sống khi tắt màn hình.
 * Notification ẩn hoàn toàn (IMPORTANCE_MIN, không icon, không hiện trên màn hình khoá).
 * Không có nút điều khiển, không có MediaSession.
 */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "playback_silent_channel"
        private const val NOTIF_ID = 1001
        const val ACTION_UPDATE_STATE = "com.ytbrowser.app.action.UPDATE_STATE"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val ACTION_CONTROL = "com.ytbrowser.app.action.CONTROL_PLAYBACK"
        const val EXTRA_COMMAND = "command"
        const val COMMAND_PLAY = "play"
        const val COMMAND_PAUSE = "pause"
        const val COMMAND_SEEK_FORWARD = "seek_forward"
        const val COMMAND_SEEK_BACKWARD = "seek_backward"
    }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        postForegroundNotification()
        return START_STICKY
    }

    private fun postForegroundNotification() {
        val notification = buildSilentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Phát nền",
                    NotificationManager.IMPORTANCE_MIN  // ẩn hoàn toàn, không icon, không sound
                ).apply {
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildSilentNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Đang phát nền")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // ẩn trên màn hình khoá
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
