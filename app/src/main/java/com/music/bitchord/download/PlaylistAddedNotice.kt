package com.music.bitchord.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog as Log

/**
 * The note that songs added to a downloaded playlist are being downloaded too,
 * see [Downloads.autoDownload].
 *
 * Silent on purpose: the channel has no sound or vibration and the notification
 * never alerts, so it is a line to read in the shade, not something that calls
 * for attention. Songs added one after another within [WINDOW_MS] update the
 * same notification's count instead of stacking a new one each.
 */
internal object PlaylistAddedNotice {

    private const val TAG = "BitChord"
    private const val CHANNEL_ID = "playlist_updates"
    private const val WINDOW_MS = 60_000L

    private class Tally(var count: Int, var at: Long)

    private val tallies = HashMap<String, Tally>()

    fun post(context: Context, playlistId: String, playlistTitle: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        val count = synchronized(tallies) {
            val now = SystemClock.elapsedRealtime()
            val tally = tallies.getOrPut(playlistId) { Tally(0, now) }
            tally.count = if (now - tally.at <= WINDOW_MS) tally.count + 1 else 1
            tally.at = now
            tally.count
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.playlist_updates_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(context.getString(R.string.auto_download_notice_title, playlistTitle, count))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager.notify(playlistId.hashCode(), notification) }
            .onFailure { Log.w(TAG, "playlist update not posted: ${it.message}") }
    }
}
