package com.jobcrawler.app

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Friday's look back at the week: what was found and where, the best of it,
 * what was applied to, what the paid search cost, why jobs were dropped,
 * and any source gone quiet. Sent once, after 10:00 Cairo time.
 */
object Weekly {
    val CAIRO: ZoneId = ZoneId.of("Africa/Cairo")
    const val STATE = "weekly.lastSent"
    const val HOUR = 10
    /** Under Telegram's 4096. */
    const val BUDGET = 3900
    /** Later than this after Friday 10:00, the week is skipped rather than sent late. */
    private val LATE = Duration.ofDays(2)

    data class Data(
        val from: LocalDate,
        val to: LocalDate,
        val facts: List<ItemFact>,
        val top: List<Row>,
        val runs: List<RunRow>,
        val applications: Map<String, Int>,
        val serperWeek: Int,
        /** Sources parsing nothing, with their streak of silent runs. */
        val quiet: Map<String, Int> = emptyMap(),
    )

    /** The most recent Friday whose 10:00 has passed. */
    fun slot(now: ZonedDateTime): LocalDate {
        val local = now.withZoneSameInstant(CAIRO)
        var day = local.toLocalDate()
        while (day.dayOfWeek != DayOfWeek.FRIDAY) day = day.minusDays(1)
        if (day == local.toLocalDate() && local.hour < HOUR) day = day.minusWeeks(1)
        return day
    }

    private fun sinceSlot(now: ZonedDateTime): Duration =
        Duration.between(slot(now).atTime(HOUR, 0).atZone(CAIRO), now)

    fun isDue(now: ZonedDateTime, lastSent: String?): Boolean =
        slot(now).toString() != lastSent && sinceSlot(now) < LATE

    /** Not sent, and too late now: the caller marks the week done without sending. */
    fun isMissed(now: ZonedDateTime, lastSent: String?): Boolean =
        slot(now).toString() != lastSent && sinceSlot(now) >= LATE

    private val day = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /** The notification's line: the two numbers that say how the week went. */
    fun headline(data: Data): String {
        val found = data.facts.size
        val sent = data.facts.count { it.status == Status.SENT }
        return "$found new, $sent sent this week"
    }

    fun render(data: Data): String {
        val facts = data.facts
        val sections = mutableListOf<String>()

        sections += "📊 <b>Your week</b> · ${day.format(data.from)}–${day.format(data.to)}"
        val sent = facts.count { it.status == Status.SENT }
        val digest = facts.count { it.status == Status.DIGEST }
        sections += buildString {
            append("<b>Jobs:</b> ${facts.size} new · $sent sent")
            if (digest > 0) append(" · $digest in digest")
        }
        if (facts.isNotEmpty()) {
            sections += Region.SHOWN.joinToString(" · ") { region -> "${region.flag} ${escape(region.label)} ${facts.count { it.region == region }}" }
            val bySource = facts.groupingBy { it.source }.eachCount().entries.sortedByDescending { it.value }
            sections += "<b>By source:</b> " + bySource.take(5).joinToString(" · ") { "${it.key} ${it.value}" } +
                if (bySource.size > 5) " · +${bySource.size - 5} more" else ""
        }
        if (data.top.isNotEmpty()) {
            sections += "<b>Best matches</b>\n" + data.top.joinToString("\n") { row ->
                "⭐ ${row.item.score ?: "–"} ${link(row.item.url, row.item.title.take(80))}" +
                    (row.item.company?.let { " · ${escape(it.take(40))}" } ?: "")
            }
        }
        val opened = data.applications["link"] ?: 0
        val emailed = data.applications["email"] ?: 0
        val refused = data.applications["email-failed"] ?: 0
        if (opened + emailed + refused > 0) {
            sections += "<b>Applications:</b> $opened opened · $emailed CVs emailed" + if (refused > 0) " · $refused refused" else ""
        }
        if (data.serperWeek > 0) sections += "<b>Serper:</b> ${data.serperWeek} searches"
        val dropped = data.runs.fold(mutableMapOf<String, Int>()) { acc, run ->
            run.rejected.forEach { (reason, count) -> acc.merge(reason, count, Int::plus) }
            acc
        }.entries.sortedByDescending { it.value }
        if (dropped.isNotEmpty()) {
            sections += "<b>Dropped most for:</b> " + dropped.take(3).joinToString(" · ") { "${escape(it.key)} ${it.value}" }
        }
        if (data.quiet.isNotEmpty()) {
            sections += "⚠️ <b>Gone quiet:</b> " + data.quiet.entries.joinToString(", ") { "${escape(it.key)} (${it.value} empty runs in a row)" }
        }

        // Within Telegram's limit, dropping whole sections from the bottom, never cutting a tag in half.
        val out = StringBuilder()
        for (section in sections) {
            if (out.length + section.length + 1 > BUDGET) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(section)
        }
        return out.toString()
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun link(url: String, label: String): String =
        if (url.startsWith("https://")) "<a href=\"${escape(url).replace("\"", "&quot;")}\">${escape(label)}</a>" else escape(label)
}
