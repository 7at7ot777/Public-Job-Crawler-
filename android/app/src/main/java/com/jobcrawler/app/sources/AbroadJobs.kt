package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Criteria
import com.jobcrawler.app.Filters
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Limits
import com.jobcrawler.app.LinkedInRest
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store

/**
 * Europe, the UK, Canada and Australia through LinkedIn's guest search: each
 * CV keyword with "visa sponsorship" in each place. Remote jobs come from the
 * free remote feeds instead — LinkedIn throttles a busy IP, so this source
 * asks it as little as it can, shares its rest and hourly cap
 * ([LinkedInRest]) with the Egypt search, and opens only a few new cards.
 * [AbroadFit] keeps those with a sponsored visa, in English, at the CV's level.
 * Posted, and emailed the CV on its own when an address shows.
 */
class AbroadJobs(
    override val client: GuestClient = LinkedInJobs.linkedInClient("LinkedIn abroad"),
    private val cadence: Cadence = Cadence(KEY, Limits.SLOW_SOURCE_EVERY_MS),
) : Source {
    override val key = KEY

    override val autoEmail: Boolean = true

    override fun isDue(store: Store) = LinkedInRest.restingUntil(store) == null && cadence.isDue(store)

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        LinkedInRest.restingUntil(store)?.let {
            Log.info("Abroad (LinkedIn): LinkedIn rests ${LinkedInRest.describe(store, it)}; skipped, no request sent")
            return emptyList()
        }
        cadence.started(store)
        val cards = LinkedHashMap<String, Pair<Item, Boolean>>()
        val kept = mutableListOf<Item>()
        val rejected = sortedMapOf<String, Int>()
        var seen = 0

        fun get(url: String, query: Map<String, Any?> = emptyMap()): String {
            if (!LinkedInRest.take(store)) {
                throw BlockedException("LinkedIn abroad: this hour's ${LinkedInRest.HOURLY_CAP} LinkedIn requests are used; the rest waits")
            }
            return client.get(url, query)
        }

        try {
            for (place in Criteria[Criteria.Kind.ABROAD_PLACES]) for (keyword in KEYWORDS) {
                val html = get(LinkedInJobs.SEARCH_URL, params("$keyword visa sponsorship", place, remote = false))
                for (item in LinkedInJobs.parseCards(html, keyword, null, KEY)) cards.putIfAbsent(item.externalId, item to false)
            }

            val checked = store.checked.ids(KEY)
            seen = cards.keys.count { it in checked }
            val wanted = cards.values.filter { (item, _) ->
                item.externalId !in checked && store.findByExternalId(KEY, item.externalId) == null && titleFits(item)
            }.take(MAX_DETAILS)

            for ((item, remote) in wanted) {
                val (description, criteria) = LinkedInJobs.parseDetail(get(LinkedInJobs.DETAIL_URL + item.externalId))
                store.checked.mark(KEY, item.externalId)
                RemoteFeeds.tally(judge(item, description, criteria, remote), kept, rejected)
            }
        } catch (error: BlockedException) {
            if (client.wasThrottled) LinkedInRest.after(client, store, KEY, jobSearch = true)
            error.partialItems = kept.toList()
            throw error
        } finally {
            Log.info("Abroad (LinkedIn): ${cards.size} listed, $seen seen before, ${kept.size} kept | dropped $rejected")
        }
        if (client.wasThrottled) LinkedInRest.after(client, store, KEY, jobSearch = true)
        return kept
    }

    /** A job filled in from its page, and why it is dropped, if it is. Shared with the feed sources. */
    data class Judged(val item: Item, val rejected: String?)

    companion object {
        const val KEY = "abroad_jobs"

        /* The CV's stack, shorter than the Egypt keywords: one search each is many requests already. */
        val KEYWORDS = listOf("Laravel", "PHP", "Node.js", "Backend Developer")

        val DEFAULT_PLACES = listOf("European Union", "United Kingdom", "Canada", "Australia")

        // A day, searched every three hours: dedup drops the overlap.
        private const val TIME_WINDOW = "r86400"

        // Job pages opened a run; the rest wait for the next.
        private const val MAX_DETAILS = 8

        fun params(keyword: String, place: String, remote: Boolean): Map<String, Any?> = mapOf(
            "keywords" to keyword,
            "location" to place,
            "f_TPR" to TIME_WINDOW,
            // 1 internship, 2 entry, 3 associate, 4 mid-senior.
            "f_E" to "2,3,4",
            "f_WT" to if (remote) "2" else null,
            "start" to 0,
        )

        /** A card worth opening: a backend title, not too senior, nowhere blocked. */
        fun titleFits(item: Item): Boolean {
            val title = Filters.fold(item.title)
            val placed = Filters.fold(listOfNotNull(item.title, item.location).joinToString(" "))
            return Filters.containsAny(title, Filters.titleTerms) &&
                !Filters.containsAny(Filters.withoutLevelRange(title), Filters.seniorityExclude) &&
                !Filters.containsAny(placed, Filters.blockedLocations)
        }


        /**
         * The job filled in from its page, and why it is dropped, if it is.
         * The lane becomes its market, so Egypt's location rule lets it through.
         */
        fun judge(card: Item, description: String?, criteria: Map<String, String>, remote: Boolean): Judged {
            val body = description.orEmpty()
            val text = "${card.title}\n${card.location.orEmpty()}\n$body"
            val stack = CvStack.of("${card.title}\n$body")
            val years = CvStack.minYears(body)
            val type = criteria["employment type"]
            val lane = AbroadFit.laneOf(text, remote)
            val region = AbroadFit.regionOf(card.location, body)

            val item = card.copy(
                market = lane,
                keyword = stack.joinToString(", ").ifEmpty { card.keyword },
                seniority = criteria["seniority level"],
                employmentType = type,
                workplace = if (lane == AbroadFit.REMOTE_LANE) "Remote" else "On-site, visa sponsored",
                remote = lane == AbroadFit.REMOTE_LANE,
                excerpt = listOfNotNull(lane, region, years?.let { "Needs $it+ years" }, body.ifEmpty { null })
                    .joinToString("\n").take(4000),
            )

            val rejected = when {
                description.isNullOrBlank() -> "no-description"
                stack.isEmpty() -> "stack"
                lane == null -> "no-lane"
                // A sponsored visa is for a place: one in the regions searched.
                lane == AbroadFit.VISA_LANE && region == null -> "region"
                type?.contains("intern", ignoreCase = true) == true -> "internship"
                type?.contains("part", ignoreCase = true) == true -> "part-time"
                years != null && years > CvStack.MAX_YEARS -> "years"
                else -> AbroadFit.blocked(text)
            }
            return Judged(item, rejected)
        }
    }
}
