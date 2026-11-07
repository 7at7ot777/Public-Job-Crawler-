package com.jobcrawler.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Why the app's process ended, read back from Android (11 and later).
 *
 * A force stop — Settings > Force stop, or a phone maker's cleaner or
 * swipe-away doing the same — cancels every scheduled run, and nothing of the
 * app may run again until it is opened by hand. No code runs at that moment,
 * so the first chance to notice is the next start: here.
 */
object ExitWatch {
    private const val PREFS = "status"
    private const val SEEN = "exitSeenAt"
    private const val FORCE_STOPPED = "forceStoppedAt"
    private const val RUNNING_SINCE = "runningSince"
    private val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

    /** A run is under way: an exit from now on cut it short. */
    fun runStarted(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(RUNNING_SINCE, System.currentTimeMillis()).apply()

    fun runEnded(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(RUNNING_SINCE).apply()

    /**
     * Logs, once each, the force stops since the last check and any exit that
     * cut a run short. The process ending between runs is normal — Android
     * reclaims idle apps all the time — and is not logged.
     *
     * Call it before [runStarted], never during a run in this process.
     *
     * @return when the app was last force-stopped, if that is among them.
     */
    fun check(context: Context): Instant? {
        if (Build.VERSION.SDK_INT < 30) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, 0L)
        val runningSince = prefs.getLong(RUNNING_SINCE, 0L)

        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 16)
        }.getOrNull().orEmpty()
            .filter { it.timestamp > seen }
            .sortedBy { it.timestamp }
        if (exits.isEmpty()) return null
        prefs.edit().putLong(SEEN, exits.last().timestamp).apply()

        var stopped: Instant? = null
        for (exit in exits) {
            val detail = exit.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            val at = clock.format(Instant.ofEpochMilli(exit.timestamp))
            val midRun = runningSince > 0 && exit.timestamp >= runningSince
            when {
                isForceStop(exit.reason) -> {
                    stopped = Instant.ofEpochMilli(exit.timestamp)
                    Log.warn(
                        "The app was force-stopped at $at$detail${if (midRun) ", mid-run" else ""}: " +
                            "no runs happen after that until the app is opened",
                    )
                }
                // A crash or an ANR, whether or not a run was under way — a UI
                // crash is not mid-run — carries the trace the Log screen needs.
                isCrash(exit.reason) -> {
                    val trace = traceOf(exit)?.let { ":\n$it" } ?: ""
                    Log.error("The app ${reasonOf(exit.reason)} at $at$detail${if (midRun) ", mid-run" else ""}$trace")
                }
                midRun && exit.reason != ApplicationExitInfo.REASON_PACKAGE_UPDATED ->
                    Log.warn("The run started at ${clock.format(Instant.ofEpochMilli(runningSince))} was cut short at $at: ${reasonOf(exit.reason)}$detail")
            }
        }
        /*
         * An exit after the run began means that run died with the process,
         * now told; cleared, so later idle exits are not blamed on it. A run
         * alive in this process has no exits after its start, so it keeps its mark.
         */
        if (runningSince > 0 && exits.any { it.timestamp >= runningSince }) prefs.edit().remove(RUNNING_SINCE).apply()
        // Left by the builds that showed a Home banner for it.
        prefs.edit().remove(FORCE_STOPPED).apply()
        return stopped
    }

    /*
     * USER_REQUESTED is Settings > Force stop, and what most phone makers'
     * cleaners report too; USER_STOPPED (Android 14) is the task manager's
     * Stop. Both leave the app's runs cancelled.
     */
    private fun isForceStop(reason: Int) =
        reason == ApplicationExitInfo.REASON_USER_REQUESTED || reason == REASON_USER_STOPPED

    /** ApplicationExitInfo.REASON_USER_STOPPED, Android 14 (API 34). */
    private const val REASON_USER_STOPPED = 11

    private fun isCrash(reason: Int) =
        reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_ANR

    /**
     * The system's own stack trace for a crash or an ANR, first lines only, so
     * one bad exit cannot flood the small rolling Log. Best-effort: not every
     * reason or Android build keeps a trace, and a null means none was there.
     */
    private fun traceOf(exit: ApplicationExitInfo): String? = runCatching {
        exit.traceInputStream?.bufferedReader()?.use { reader ->
            reader.lineSequence().take(TRACE_LINES).joinToString("\n").trim().ifBlank { null }
        }
    }.getOrNull()

    private const val TRACE_LINES = 60

    private fun reasonOf(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for memory"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by the system (often the phone maker's battery manager)"
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed"
        ApplicationExitInfo.REASON_ANR -> "stopped responding (ANR)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "stopped for using too much battery"
        ApplicationExitInfo.REASON_FREEZER -> "frozen in the background"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it depends on died"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_OTHER -> "ended by the system"
        ApplicationExitInfo.REASON_EXIT_SELF -> "the app ended itself"
        else -> "reason $reason"
    }
}
