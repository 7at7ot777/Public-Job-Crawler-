package com.jobcrawler.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.WorkManager

/** The notice a run shows while it works in the foreground. */
object RunNotice {
    private const val CHANNEL = "run"
    private const val ID = 7_001
    private const val GROUP = "searching"

    /**
     * One notice per source, grouped: WorkManager ties a foreground notice to
     * its worker, so a shared one would vanish when the first of several
     * workers ends while the others still run.
     */
    fun info(context: Context, key: String): ForegroundInfo {
        val index = Pipeline.SOURCES.indexOfFirst { it.key == key }.coerceAtLeast(0)
        val id = ID + 1 + index
        val name = SettingsActivity.SOURCE_LABELS[key]?.first ?: key
        val notifications = context.getSystemService(NotificationManager::class.java)
        // Low: no sound, no peek; it is there so Android keeps the run, not to be read.
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Search in progress", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            context,
            ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Searching $name…")
            .setContentText("Gone when the run ends")
            .setContentIntent(open)
            .setGroup(GROUP)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        // Special use from Android 14: no daily cap, where dataSync stops at 6 h a day on Android 15.
        return when {
            Build.VERSION.SDK_INT >= 34 -> ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(id, notification)
        }
    }
}
