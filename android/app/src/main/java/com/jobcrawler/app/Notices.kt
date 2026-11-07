package com.jobcrawler.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Notifications that are not a run in progress: a source gone quiet, the weekly summary. */
object Notices {
    private const val HEALTH = "health"
    private const val WEEKLY = "weekly"
    private const val WEEKLY_ID = 7_200

    /** A source that parses nothing any more; one notification per source, replaced, not stacked. */
    fun breakage(context: Context, key: String, text: String) =
        post(context, HEALTH, "Source health", 7_100 + Math.floorMod(key.hashCode(), 100), "$key stopped finding jobs", text,
            Intent(context, LogActivity::class.java))

    /** A source the network keeps cutting short, or its recovery; one per source, replaced. */
    fun outage(context: Context, key: String, title: String, text: String) =
        post(context, HEALTH, "Source health", 7_300 + Math.floorMod(key.hashCode(), 100), title, text,
            Intent(context, LogActivity::class.java))

    fun weekly(context: Context, text: String) =
        post(context, WEEKLY, "Weekly summary", WEEKLY_ID, "Your week of job hunting", text, Intent(context, MainActivity::class.java))

    private fun post(context: Context, channel: String, channelName: String, id: Int, title: String, text: String, open: Intent) {
        runCatching {
            val notifications = context.getSystemService(NotificationManager::class.java)
            if (!notifications.areNotificationsEnabled()) return
            notifications.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT))
            val tap = PendingIntent.getActivity(
                context, id, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            notifications.notify(id, notification)
        }.onFailure { Log.warn("Notification failed: ${it.javaClass.simpleName}: ${it.message}") }
    }
}
