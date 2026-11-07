package com.jobcrawler.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ages written for people ("3 days ago") and the dates boards print, as ISO 8601. */
object Dates {
    private val ageRe = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)

    private val unitMs = mapOf(
        "second" to 1_000L,
        "minute" to 60_000L,
        "hour" to 3_600_000L,
        "day" to 86_400_000L,
        "week" to 604_800_000L,
        "month" to 2_592_000_000L,
        "year" to 31_536_000_000L,
    )

    // "13 August 2026", as Tanqeeb prints it.
    private val longDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    fun relativeToIso(text: String?, now: Long = System.currentTimeMillis()): String? {
        val match = ageRe.find(text ?: return null) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unit = unitMs[match.groupValues[2].lowercase()] ?: return null
        return Instant.ofEpochMilli(now - amount * unit).toString()
    }

    /** ISO instants, plain ISO dates, and "13 August 2026". */
    fun absoluteToIso(value: String?): String? {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return null

        runCatching { return Instant.parse(text).toString() }
        runCatching { return java.time.OffsetDateTime.parse(text).toInstant().toString() }
        runCatching { return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC).toString() }
        runCatching { return LocalDate.parse(text, longDate).atStartOfDay().toInstant(ZoneOffset.UTC).toString() }
        return null
    }

    /** Whichever of the two the value turns out to be; relative first. */
    fun toIso(value: String?, now: Long = System.currentTimeMillis()): String? =
        relativeToIso(value, now) ?: absoluteToIso(value)

    /** A posting with no date is kept: an unknown age is not an old one. */
    fun isOlderThan(postedAt: String?, maxAgeDays: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (postedAt == null || maxAgeDays <= 0) return false
        val posted = runCatching { Instant.parse(postedAt).toEpochMilli() }.getOrNull() ?: return false
        return now - posted > maxAgeDays * 86_400_000L
    }
}
