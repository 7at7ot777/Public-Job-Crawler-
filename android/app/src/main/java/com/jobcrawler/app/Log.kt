package com.jobcrawler.app

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A small rolling log the app screen shows, so a run can be checked from the
 * phone without a cable. Capped, so it never grows past a few dozen KB.
 */
object Log {
    private const val MAX_LINES = 300
    private var file: File? = null
    private val stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    fun init(context: Context) {
        file = File(context.filesDir, "crawler.log")
    }

    fun info(message: String) = write("I", message)
    fun warn(message: String) = write("W", message)
    fun error(message: String) = write("E", message)

    @Synchronized
    private fun write(level: String, message: String) {
        android.util.Log.println(
            if (level == "E") android.util.Log.ERROR else if (level == "W") android.util.Log.WARN else android.util.Log.INFO,
            "JobCrawler",
            message,
        )
        val target = file ?: return
        runCatching {
            target.appendText("${LocalDateTime.now().format(stamp)} $level $message\n")
            if (target.length() > 64_000) {
                target.writeText(target.readLines().takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
            }
        }
    }

    @Synchronized
    fun tail(lines: Int = 120): String =
        file?.takeIf { it.exists() }?.readLines()?.takeLast(lines)?.reversed()?.joinToString("\n") ?: ""

    @Synchronized
    fun clear() {
        file?.delete()
    }
}
