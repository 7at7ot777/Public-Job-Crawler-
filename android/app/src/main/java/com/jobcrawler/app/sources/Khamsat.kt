package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Store
import org.jsoup.Jsoup

/**
 * Khamsat's requests (khamsat.com/community/requests): buyers asking for a
 * service that is not on the market yet. robots.txt allows the listing.
 *
 * The listing only: a request's own page answers bots with an empty 202
 * challenge, so it is never opened. A request is judged on its title, the
 * one thing listed (Freelance.fits, applied by Filters), so only titles
 * that name the stack get through — few, by design.
 */
class Khamsat(
    override val client: GuestClient = GuestClient("Khamsat"),
    private val cadence: Cadence = Cadence(KEY, EVERY_MS),
) : Source {
    override val key = KEY

    /** Posted for the person to answer; there is no address to send a CV to. */
    override val autoEmail: Boolean = false

    override val listed = ListedCount()

    override fun isDue(store: Store) = cadence.isDue(store)

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        cadence.started(store)
        val kept = LinkedHashMap<String, Item>()

        try {
            for (item in parseList(client.get(LIST_URL))) {
                if (!Dates.isOlderThan(item.postedAt, MAX_AGE_DAYS)) kept.putIfAbsent(item.externalId, item)
            }
        } catch (error: BlockedException) {
            error.partialItems = kept.values.toList()
            throw error
        } finally {
            listed.add(kept.size)
        }

        return kept.values.toList()
    }

    companion object {
        const val KEY = "khamsat"

        private const val HOST = "https://khamsat.com"
        private const val LIST_URL = "$HOST/community/requests"

        /** Every 30 minutes, like Mostaql: a request is answered within the hour. */
        const val EVERY_MS = 30 * 60_000L

        private const val MAX_AGE_DAYS = 3

        // "05/11/2026 17:26:45 GMT": day first.
        private val GMT_RE = Regex("""(\d{2})/(\d{2})/(\d{4})\s+(\d{2}):(\d{2}):(\d{2})""")

        /** Every request row on the listing, newest first. A page without rows is no requests. */
        fun parseList(page: String): List<Item> {
            val items = LinkedHashMap<String, Item>()

            for (chunk in page.split("<tr id=\"forum_post-").drop(1)) {
                val id = chunk.takeWhile(Char::isDigit).ifEmpty { null } ?: continue
                val row = Jsoup.parseBodyFragment("<table><tr id=\"forum_post-" + chunk.substringBefore("</tr>") + "</tr></table>")
                val link = row.selectFirst("h3.details-head a[href]") ?: continue
                val href = link.attr("href").ifEmpty { null } ?: continue
                val title = Html.clean(link.text()) ?: continue

                items.putIfAbsent(
                    id,
                    Item(
                        source = KEY,
                        externalId = id,
                        title = title,
                        url = if (href.startsWith("http")) href else HOST + href,
                        company = Html.clean(row.selectFirst("a.user")?.text())?.removePrefix(".")?.trim(),
                        board = "Khamsat",
                        market = Freelance.market("Khamsat"),
                        workplace = "Remote",
                        remote = true,
                        // The first time on the row is when it was posted; the next is the last reply.
                        postedAt = posted(row.selectFirst("span[title]")?.attr("title")),
                        keyword = Freelance.stack(title).joinToString(", ").ifEmpty { null },
                        excerpt = title,
                    ),
                )
            }

            return items.values.toList()
        }

        /** Khamsat's "05/11/2026 17:26:45 GMT" as ISO. */
        fun posted(value: String?): String? {
            val (day, month, year, hour, minute, second) = GMT_RE.find(value ?: return null)?.destructured ?: return null
            return Dates.absoluteToIso("$year-$month-${day}T$hour:$minute:${second}Z")
        }
    }
}
