package com.jobcrawler.app

import android.content.Context
import androidx.work.WorkInfo
import com.jobcrawler.app.sources.Ats
import com.jobcrawler.app.sources.SerperBudget
import java.time.Instant
import java.time.ZoneId

/**
 * Everything the home screen shows, read in one go off the main thread.
 * Kept apart from MainActivity so it can be built (and tested) without a screen.
 */
internal class HomeState(
    val scheduled: Boolean,
    val running: Boolean,
    val offline: Boolean,
    val settings: Settings,
    val report: RunReport?,
    val stored: Int,
    val fresh: Int,
    val waiting: Int,
    val updates: Int,
    val applied: Map<String, Int>,
    val backupOn: Boolean,
    val slowLastRun: Map<String, Long>,
    /** Sources run by hand that are queued or under way. */
    val sourceRuns: Map<String, WorkInfo.State>,
    val online: Boolean,
    /** CVs the Saudi & UAE sources emailed on their own today. */
    val gulfSentToday: Int,
    val serper: SerperBudget.Usage?,
    val update: Updater.Status,
    /** When LinkedIn may be asked again after a 429, or null. */
    val linkedInRest: Long? = null,
    val linkedInRestReason: String? = null,
    /** Why jobs were dropped over seven days, most first. */
    val dropped: List<Pair<String, Int>> = emptyList(),
) {
    companion object {
        /** Every-3-hours sources, whose next turn the home screen works out from their last run. */
        val SLOW_SOURCES by lazy { Pipeline.SOURCES.filter { it.everyMinutes == Pipeline.SLOW }.map { it.key }.toSet() }
        /** The paid ones: every source on the Serper lane. */
        val PAID_SOURCES by lazy { Pipeline.keysOn(Pipeline.Lane.SERPER) }

        fun load(context: Context, store: Store): HomeState {
            val settings = Settings.load(context)
            val lastLook = JobsActivity.lastSeen(context)
            return HomeState(
                scheduled = Scheduler.isScheduled(context),
                running = Scheduler.isRunning(context),
                offline = Scheduler.isResumePending(context),
                settings = settings,
                report = RunReport.load(context),
                stored = store.stats().values.sum(),
                fresh = lastLook?.let { store.jobsSince(it) } ?: store.stats().values.sum(),
                waiting = store.reviewCount(Mailer.queueSince()),
                updates = Changes.unseen(context),
                applied = store.applications.counts(),
                backupOn = Backup.folder(context) != null,
                // Ats runs twice a day, not on the 3-hour lane, but the home screen still works out its next turn.
                slowLastRun = (SLOW_SOURCES + Ats.KEY + com.jobcrawler.app.sources.Recruitera.KEY).mapNotNull { key -> store.getState("$key.lastRun")?.toLongOrNull()?.let { key to it } }.toMap(),
                sourceRuns = Scheduler.sourceRuns(context),
                online = Net.init(context).let { Net.isOnline() },
                gulfSentToday = store.applications.autoSentSince(
                    Mailer.AUTO_SOURCES,
                    java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant(),
                ),
                serper = if (settings.sources.any { it in PAID_SOURCES }) {
                    runCatching { SerperBudget(store, SerperPrefs.dailyCap(context)).usage() }.getOrNull()
                } else null,
                update = Updater.status(context),
                linkedInRest = LinkedInRest.restingUntil(store),
                linkedInRestReason = LinkedInRest.reason(store),
                dropped = runCatching {
                    val now = Instant.now()
                    Rejections.totals(Rejections.build(store.runs.since(now.minusSeconds(7 * 86_400)), now))
                }.getOrDefault(emptyList()),
            )
        }
    }
}
