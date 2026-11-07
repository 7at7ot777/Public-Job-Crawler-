package com.jobcrawler.app

/**
 * Digest mode: the best matches go out at once, the rest wait and arrive as
 * one message every few hours, so the channel is not a wall of near-misses.
 * Off by default; Settings turns it on and sets the score that skips it.
 */
object Digest {
    const val EVERY_MS = 3 * 3_600_000L
    const val TOP = 20
    const val STATE = "digest.lastSent"
    /** Under Telegram's 4096, with room for the header. */
    const val BUDGET = 3900
    /** A row waiting longer than this without making the top [TOP] is let go. */
    const val MAX_WAIT_MS = 24 * 3_600_000L

    /** (send now, keep for the digest). Off, everything goes now. A row with no score waits. */
    fun split(rows: List<Row>, on: Boolean, threshold: Int): Pair<List<Row>, List<Row>> =
        if (!on) rows to emptyList() else rows.partition { (it.item.score ?: 0) >= threshold }

    /** Due every [EVERY_MS] while on; once turned off, the leftovers still go out on the next tick. */
    fun isDue(now: Long, lastSent: Long?, pending: Int, on: Boolean): Boolean =
        pending > 0 && (!on || lastSent == null || now - lastSent >= EVERY_MS || now < lastSent)

    /**
     * The message, Egypt then the Gulf then worldwide, best first in each,
     * and the ids it actually holds: only those are marked sent.
     */
    fun render(rows: List<Row>): Pair<String, List<Long>> {
        // The best [TOP] by score, whatever their region; then shown region by region.
        val ordered = rows
            .sortedByDescending { it.item.score ?: 0 }
            .take(TOP)
            .sortedWith(compareBy<Row> { it.region.ordinal }.thenByDescending { it.item.score ?: 0 })
        val included = mutableListOf<Long>()
        val text = StringBuilder("🗞 <b>Digest</b> · ${rows.size} more ${if (rows.size == 1) "match" else "matches"}\n")
        var region: Region? = null
        for (row in ordered) {
            val head = if (row.region != region) "\n${row.region.flag} <b>${escape(row.region.label)}</b>\n" else ""
            val line = buildString {
                append(row.item.score?.let { "⭐ $it " } ?: "• ")
                append(link(row.item.url, row.item.title.take(90)))
                row.item.company?.let { append(" · ").append(escape(it.take(50))) }
                append("\n")
            }
            if (text.length + head.length + line.length > BUDGET) break
            text.append(head).append(line)
            region = row.region
            included += row.id
        }
        val left = rows.size - included.size
        if (left > 0) text.append("\n…and $left more on the Jobs screen")
        return text.toString().trimEnd() to included
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun link(url: String, label: String): String =
        if (url.startsWith("https://")) "<a href=\"${escape(url).replace("\"", "&quot;")}\">${escape(label)}</a>" else escape(label)
}
