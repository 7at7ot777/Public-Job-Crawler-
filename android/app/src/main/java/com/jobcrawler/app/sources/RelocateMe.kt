package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.Filters
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store
import org.json.JSONObject

/**
 * relocate.me: tech jobs abroad (Europe, Japan) whose employer pays for the
 * move and the visa. Every job there is a relocation, so there is no lane to
 * decide: what is checked is the CV, the same way as for jobright.
 *
 * The board is small (about 25 open jobs over two pages), server-rendered,
 * 20 to a page. A card carries title, company and place (also in its URL,
 * /<country>/<city>/<company>/<slug>-<id>); the job page carries a JSON-LD
 * JobPosting with the date and the full description. So: read every list
 * page, open only the jobs never opened before whose title could pass, and
 * remember each one opened (checked_urls), so a job is fetched once.
 */
class RelocateMe(
    override val client: GuestClient = GuestClient("Relocate.me"),
    private val cadence: Cadence = Cadence(KEY, EVERY_MS),
) : Source {
    override val key = KEY

    /** Posted, and a job that gives an address is emailed the CV on its own. */
    override val autoEmail: Boolean = true

    override fun isDue(store: Store) = cadence.isDue(store)

    /** What the remote listed this run; see [Source.listed]. */
    override val listed = ListedCount()

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        cadence.started(store)
        val checked = store.checked.ids(KEY)
        val kept = mutableListOf<Item>()
        val rejected = sortedMapOf<String, Int>()
        var listed = 0
        var seen = 0

        try {
            val cards = LinkedHashMap<String, Card>()
            for (page in 1..MAX_PAGES) {
                val found = parseList(client.get(LIST_URL, mapOf("page" to page.takeIf { it > 1 })))
                val fresh = found.filter { cards.putIfAbsent(it.id, it) == null }
                if (fresh.isEmpty()) break
            }
            listed = cards.size

            for (card in cards.values) {
                if (card.id in checked) { seen += 1; continue }
                val reason = when {
                    card.sponsored -> "advert"
                    !Filters.couldPass(card.item()) -> "title"
                    else -> null
                }
                if (reason != null) {
                    rejected.merge(reason, 1, Int::plus)
                    store.checked.mark(KEY, card.id)
                    continue
                }

                val job = parseDetail(client.get(card.url), card)
                store.checked.mark(KEY, card.id)
                val why = job.rejected ?: if (Dates.isOlderThan(job.item.postedAt, MAX_AGE_DAYS)) "too-old" else null
                if (why != null) rejected.merge(why, 1, Int::plus) else kept += job.item
            }
        } catch (error: BlockedException) {
            error.partialItems = kept.toList()
            throw error
        } finally {
            this.listed.add(listed)
            Log.info("relocate.me: $listed listed, $seen seen before, ${kept.size} new that fit the CV | dropped $rejected")
        }
        return kept
    }

    /** One card of the list page. */
    data class Card(
        val id: String,
        val url: String,
        val title: String,
        val company: String?,
        val country: String,
        val city: String?,
        val sponsored: Boolean,
    ) {
        val remote get() = country.equals("Remote", ignoreCase = true)

        /** Enough for Filters.couldPass, before the job page is spent on it. */
        fun item() = Item(
            source = KEY,
            externalId = id,
            title = title,
            url = url,
            company = company,
            location = place(city, country),
            market = laneOf(country),
            remote = remote,
        )
    }

    data class Parsed(val item: Item, val rejected: String?)

    companion object {
        const val KEY = "relocateme"
        private const val BASE = "https://relocate.me"
        private const val LIST_URL = "$BASE/international-jobs"
        private const val MAX_PAGES = 5
        private const val EVERY_MS = 3 * 60 * 60_000L

        /* Relocation jobs stay open for months; older than this, likely filled. */
        private const val MAX_AGE_DAYS = 60

        private val CARD_SPLIT = Regex("""class="jobs-list__job"""")
        private val LINK = Regex("""href="(/([a-z0-9-]+)/([a-z0-9-]+)/([a-z0-9-]+)/([a-z0-9-]+)-(\d+))"""")
        private val BOLD = Regex("""<b>([\s\S]*?)</b>""")
        private val COMPANY = Regex("""job__company[\s\S]*?<p>([\s\S]*?)</p>""")
        private val LD = Regex("""<script type="application/ld\+json">([\s\S]*?)</script>""")
        private val CONTROL = Regex("[\\u0000-\\u001f]+")
        private val INTERN = Regex("""\bintern(ship)?s?\b""", RegexOption.IGNORE_CASE)

        /** The market a relocation job is in, as the channel and the cover note show it. */
        fun laneOf(country: String): String =
            if (country.equals("Remote", ignoreCase = true)) "🌍 Remote (via Relocate.me)" else "✈️ Relocation to $country, visa paid"

        /** The country a lane names, for the cover note; null for a remote job. */
        fun countryOf(market: String?): String? =
            market?.removePrefix("✈️ Relocation to ")?.takeIf { it != market }?.substringBefore(',')

        /** Every job card on a list page, in page order. A page without cards is no jobs. */
        fun parseList(page: String): List<Card> {
            val chunks = page.split(CARD_SPLIT).drop(1)
            return chunks.mapNotNull { chunk ->
                val link = LINK.find(chunk) ?: return@mapNotNull null
                val (path, country, city, _, slug, id) = link.destructured
                val title = Html.detag(BOLD.find(chunk)?.groupValues?.get(1)) ?: return@mapNotNull null
                Card(
                    id = id,
                    url = BASE + path,
                    title = title,
                    company = Html.detag(COMPANY.find(chunk)?.groupValues?.get(1)),
                    country = words(country),
                    city = words(city).takeIf { !city.equals(country, ignoreCase = true) },
                    // The board's own paid listing, pinned to the top of every search.
                    sponsored = "paid-option" in slug,
                )
            }
        }

        /** The job page: its JSON-LD JobPosting, judged against the CV. */
        fun parseDetail(page: String, card: Card): Parsed {
            val posting = LD.findAll(page).mapNotNull { match ->
                // The descriptions carry raw line breaks, which strict JSON refuses.
                runCatching { JSONObject(match.groupValues[1].replace(CONTROL, " ")) }.getOrNull()
                    ?.takeIf { it.optString("@type") == "JobPosting" }
            }.firstOrNull()

            val title = Html.clean(Html.decode(posting?.optString("title")))?.replace(Regex("""\s*\|\s*Relocation Offered\s*$""", RegexOption.IGNORE_CASE), "")
                ?: card.title
            val address = posting?.optJSONObject("jobLocation")?.optJSONObject("address")
            val city = Html.clean(address?.optString("addressLocality")) ?: card.city
            val country = Html.clean(address?.optString("addressCountry")) ?: card.country
            val description = Html.strip(Html.decode(posting?.optString("description")))
            val type = Html.clean(posting?.optString("employmentType"))?.replace('_', ' ')?.lowercase()?.replaceFirstChar(Char::uppercaseChar)

            val text = "$title\n$description"
            val stack = CvStack.of(text)
            val years = CvStack.minYears(description)
            val market = if (card.remote) laneOf("Remote") else laneOf(country)

            val rejected = when {
                posting == null -> "no-posting"
                stack.isEmpty() -> "stack"
                INTERN.containsMatchIn(title) || type?.contains("intern", ignoreCase = true) == true -> "internship"
                type?.contains("part", ignoreCase = true) == true -> "part-time"
                years != null && years > CvStack.MAX_YEARS -> "years"
                // Another language, or only for people already there.
                else -> AbroadFit.blocked(text)
            }

            val excerpt = buildList {
                add(market)
                years?.let { add("Needs $it+ years") }
                if (description.isNotEmpty()) add(description)
            }.joinToString("\n").take(4000)

            val item = Item(
                source = KEY,
                externalId = card.id,
                title = title,
                url = card.url,
                company = Html.clean(posting?.optJSONObject("hiringOrganization")?.optString("name")) ?: card.company,
                location = place(city, country),
                market = market,
                postedAt = Dates.absoluteToIso(posting?.optString("datePosted")),
                keyword = stack.joinToString(", ").ifEmpty { null },
                excerpt = excerpt,
                employmentType = type,
                workplace = if (card.remote) "Remote" else "On-site, relocation",
                remote = card.remote,
            )
            return Parsed(item, rejected)
        }

        private fun place(city: String?, country: String) =
            if (city == null || country.equals("Remote", ignoreCase = true)) country else "$city, $country"

        /** "san-sebastian" → "San Sebastian". */
        private fun words(slug: String) =
            slug.split('-').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }
}
