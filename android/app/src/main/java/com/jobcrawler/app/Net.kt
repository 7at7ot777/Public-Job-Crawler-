package com.jobcrawler.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the phone has a way out, read off the system rather than left to a
 * WorkManager constraint.
 *
 * The constraint stops a running worker the moment the default network goes
 * away, and a Wi-Fi ↔ mobile data switch is exactly that for a second or two:
 * the run was killed mid-source and started over. Here a run instead waits
 * out the gap and carries on where it was.
 *
 * A gap longer than a handover is an outage: [lost] is set, every request
 * after it fails at once instead of waiting again, and the run hands over
 * to a resume run that WorkManager starts once the phone is back online.
 *
 * Not initialised (the JVM unit tests) means online: nothing ever waits.
 */
object Net {
    private var connectivity: ConnectivityManager? = null

    fun init(context: Context) {
        connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    }

    fun isOnline(): Boolean {
        val manager = connectivity ?: return true
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Blocks until online, [timeoutMs] passes, [until] passes, or the run is stopped.
     * @return whether the phone is online now
     */
    fun awaitOnline(
        timeoutMs: Long = Limits.HANDOVER_WAIT_MS,
        until: Long = Long.MAX_VALUE,
        isStopped: () -> Boolean = { false },
    ): Boolean {
        if (isOnline()) return true

        val giveUp = minOf(System.currentTimeMillis() + timeoutMs, until)
        while (System.currentTimeMillis() < giveUp) {
            if (isStopped()) return false
            Thread.sleep(minOf(1_000L, giveUp - System.currentTimeMillis()).coerceAtLeast(1))
            if (isOnline()) return true
        }
        return false
    }
}
