package com.jobcrawler.app

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * What Android is doing to the app's background work, in a line for the log:
 * the reasons a schedule goes quiet without the app doing anything wrong.
 */
object Power {
    fun describe(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java)
        val parts = mutableListOf<String>()

        parts += if (power.isIgnoringBatteryOptimizations(context.packageName)) "battery: unrestricted"
        else "battery: optimised (Settings > Battery > allow background use)"
        if (power.isPowerSaveMode) parts += "battery saver on"
        if (power.isDeviceIdleMode) parts += "phone in Doze"

        if (Build.VERSION.SDK_INT >= 28) {
            val activity = context.getSystemService(ActivityManager::class.java)
            if (activity.isBackgroundRestricted) parts += "background use restricted for this app"
            val bucket = context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
            parts += "standby bucket: " + when (bucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "rare (runs held back for hours)"
                45 -> "restricted (about one run a day)" // STANDBY_BUCKET_RESTRICTED, API 30
                else -> bucket.toString()
            }
        }
        return parts.joinToString(", ")
    }
}
