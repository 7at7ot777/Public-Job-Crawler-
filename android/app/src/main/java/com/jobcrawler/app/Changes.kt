package com.jobcrawler.app

import android.content.Context
import java.time.Instant
import java.time.OffsetDateTime

/** One update to the app: a commit's subject, and what its message says about it. */
data class Change(val at: Instant, val hash: String, val title: String, val details: String)

/**
 * What changed in the app, newest first. The build writes the git history of
 * android/ into the asset [ASSET] (see app/build.gradle.kts), so the list
 * grows by itself with every commit and nothing is kept by hand.
 */
object Changes {
    const val ASSET = "changes.txt"

    // The build writes each commit as date, hash, message, split by these.
    private const val FIELD = '\u001f'
    private const val RECORD = '\u001e'

    private const val PREFS = "changes"

    /* Commits that only kept the build working say nothing to the person using the app. */
    private val NOISE = Regex("fix(es)? the build", RegexOption.IGNORE_CASE)
    private val PREFIX = Regex("^Android app:\\s*", RegexOption.IGNORE_CASE)
    private val TRAILER = Regex("^(Co-Authored-By|Signed-off-by):", RegexOption.IGNORE_CASE)

    // The asset only changes with a new APK; Home asks every few seconds.
    @Volatile private var cached: List<Change>? = null

    fun load(context: Context): List<Change> = cached ?: runCatching {
        context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }
    }.getOrDefault(emptyList()).also { cached = it }

    fun parse(raw: String): List<Change> = raw.split(RECORD).mapNotNull { record ->
        val fields = record.trim('\n', '\r').split(FIELD, limit = 3)
        if (fields.size < 3) return@mapNotNull null
        val at = runCatching { OffsetDateTime.parse(fields[0].trim()).toInstant() }.getOrNull() ?: return@mapNotNull null
        val lines = fields[2].trim().lines()
        val subject = lines.firstOrNull()?.trim().orEmpty()
        if (subject.isEmpty() || NOISE.containsMatchIn(subject)) return@mapNotNull null
        Change(
            at = at,
            hash = fields[1].trim(),
            title = subject.replace(PREFIX, "").replaceFirstChar { it.uppercaseChar() },
            details = unwrap(lines.drop(1).filterNot { TRAILER.containsMatchIn(it.trim()) }),
        )
    }.sortedByDescending { it.at }

    /** A message is wrapped at 72 columns; on a phone its paragraphs read better as one line each. */
    private fun unwrap(lines: List<String>): String {
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        fun close() {
            if (current.isNotBlank()) paragraphs += current.toString().trim()
            current.clear()
        }
        for (line in lines.map { it.trim() }) {
            when {
                line.isEmpty() -> close()
                // A list item starts its own line.
                line.startsWith("- ") || line.startsWith("* ") -> {
                    close()
                    current.append("• ").append(line.drop(2))
                }
                else -> current.append(if (current.isEmpty()) "" else " ").append(line)
            }
        }
        close()
        return paragraphs.joinToString("\n\n")
    }

    /** When the person last opened What's new; null if never. */
    fun lastSeen(context: Context): Instant? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lastSeen", null)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun markSeen(context: Context, newest: Instant) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lastSeen", newest.toString())
            .apply()
    }

    /** How many updates arrived since What's new was last opened. */
    fun unseen(context: Context): Int {
        val seen = lastSeen(context)
        return load(context).count { seen == null || it.at.isAfter(seen) }
    }
}
