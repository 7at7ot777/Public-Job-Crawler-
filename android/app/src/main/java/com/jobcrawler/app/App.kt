package com.jobcrawler.app

/**
 * One process-wide setup point, run for every process the app starts — the UI
 * and the crawl workers alike.
 *
 * It opens the rolling [Log] and installs a last-resort handler for uncaught
 * exceptions. Without it a crash that only happens sometimes dies with the
 * process and leaves nothing behind: the Log screen stays empty and there is
 * nothing to act on. With it, the stack trace is written to the Log before the
 * process goes down, and [ExitWatch] recovers the system's own copy on the next
 * start, so the next crash can be read from the phone instead of guessed at.
 */
class App : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        Log.init(this)
        installCrashLogging()
    }

    private fun installCrashLogging() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // The Log write is best-effort: never let the reporter itself swallow
            // the crash, so the previous handler still shows the system dialog and
            // records the exit for ExitWatch to find.
            runCatching {
                Log.error("app crashed on thread \"${thread.name}\": ${android.util.Log.getStackTraceString(error).trim()}")
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
