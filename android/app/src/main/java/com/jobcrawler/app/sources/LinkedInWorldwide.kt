package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Filters
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.LinkedInRest
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store

/**
 * Remote LinkedIn jobs open to someone anywhere — or at least to EMEA,
 * Africa, Egypt or UTC+2/+3 — searched as "Worldwide" with LinkedIn's remote
 * filter (f_WT=2), every hour, with the Egypt search's keywords.
 *
 * LinkedIn has no "accepts any country" filter, and most "Worldwide, remote"
 * results are remote inside one country. So each opened job passes two checks
 * ([judge]): nothing that limits who may apply (US only, EU residents, right
 * to work, another language), and something in the description that says it
 * takes people from anywhere.
 *
 * Kept apart from the Egypt search on purpose: its own hourly request budget
 * ([LinkedInRest.takeWorldwide]) so it can never use Egypt's share — the old
 * abroad search was retired for doing exactly that. Both share LinkedIn's
 * rest after a 429, because LinkedIn sees one phone.
 */
class LinkedInWorldwide(
    override val client: GuestClient = LinkedInJobs.linkedInClient("LinkedIn worldwide"),
    private val cadence: Cadence = Cadence(KEY, EVERY_MS),
) : Source {
    override val key = KEY

    // Like the other worldwide sources: a job that gives an address gets the CV.
    override val autoEmail: Boolean = true

    override fun isDue(store: Store) = LinkedInRest.restingUntil(store) == null && cadence.isDue(store)

    /** What the remote listed this run; see [Source.listed]. */
    override val listed = ListedCount()

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        LinkedInRest.restingUntil(store)?.let {
            Log.info("$key: LinkedIn rests ${LinkedInRest.describe(store, it)}; skipped, no request sent")
            return emptyList()
        }
        cadence.started(store)
        val window = LinkedInJobs.windowSince(store.getState("$key.lastOk")?.toLongOrNull())

        val cards = LinkedHashMap<String, Item>()
        val kept = mutableListOf<Item>()
        val rejected = sortedMapOf<String, Int>()
        var seen = 0

        fun get(url: String, query: Map<String, Any?> = emptyMap()): String {
            if (!LinkedInRest.takeWorldwide(store)) {
                throw BlockedException("$key: this hour's ${LinkedInRest.WORLDWIDE_HOURLY_CAP} requests are used; the rest waits for the next run")
            }
            return client.get(url, query)
        }

        try {
            for (keyword in keywords) {
                val html = get(LinkedInJobs.SEARCH_URL, params(keyword, window))
                for (item in LinkedInJobs.parseCards(html, keyword, null, KEY)) cards.putIfAbsent(item.externalId, item)
            }

            val checked = store.checked.ids(KEY)
            seen = cards.keys.count { it in checked }
            val wanted = cards.values.filter { item ->
                item.externalId !in checked && store.findByExternalId(KEY, item.externalId) == null && AbroadJobs.titleFits(item)
            }.take(MAX_DETAILS)

            for (card in wanted) {
                val (description, criteria) = LinkedInJobs.parseDetail(get(LinkedInJobs.DETAIL_URL + card.externalId))
                store.checked.mark(KEY, card.externalId)
                RemoteFeeds.tally(judge(card, description, criteria), kept, rejected)
            }
        } catch (error: BlockedException) {
            if (client.wasThrottled) LinkedInRest.after(client, store, key, jobSearch = true)
            error.partialItems = kept.toList()
            throw error
        } finally {
            listed.add(cards.size)
            Log.info("$key: ${cards.size} listed, $seen seen before, ${kept.size} kept | dropped $rejected")
        }

        if (client.wasThrottled) LinkedInRest.after(client, store, key, jobSearch = true) else store.setState("$key.lastOk", System.currentTimeMillis().toString())
        return kept
    }

    companion object {
        const val KEY = "linkedin_worldwide"

        const val PLACE = "Worldwide"

        /** Hourly: 9 searches and up to [MAX_DETAILS] job pages, inside [LinkedInRest.WORLDWIDE_HOURLY_CAP]. */
        const val EVERY_MS = 60 * 60_000L

        /** Job pages opened a run; the rest are opened by the next one. */
        const val MAX_DETAILS = 8

        /** Where a kept job is filed: a 🌍 market is Worldwide to Region and settled for Filters' location rule. */
        const val LANE = "🌍 Remote, worldwide"

        fun params(keyword: String, window: String): Map<String, Any?> = mapOf(
            "keywords" to keyword,
            "location" to PLACE,
            "start" to 0,
            "f_TPR" to window,
            // 1 internship, 2 entry, 3 associate, 4 mid-senior: the Egypt search's levels.
            "f_E" to "1,2,3,4",
            // Remote only.
            "f_WT" to "2",
        )

        /**
         * The job filled in from its page, and why it is dropped, if it is.
         *
         * Check one, on everything the job says: nothing that limits who may
         * apply. Check two, on the description alone: something that says it
         * takes people from anywhere, or from a region that includes Egypt —
         * the card's "Worldwide" is only the search, so it does not count.
         */
        fun judge(card: Item, description: String?, criteria: Map<String, String>): AbroadJobs.Judged {
            val body = description.orEmpty()
            val text = "${card.title}\n${card.location.orEmpty()}\n$body"
            val folded = Filters.fold(text)
            val type = criteria["employment type"]

            val item = card.copy(
                market = LANE,
                seniority = criteria["seniority level"],
                employmentType = type,
                workplace = "Remote",
                remote = true,
                excerpt = listOf(LANE, body).filter { it.isNotEmpty() }.joinToString("\n").take(4000),
            )

            val rejected = when {
                description.isNullOrBlank() -> "no-description"
                type?.contains("intern", ignoreCase = true) == true -> "internship"
                type?.contains("part", ignoreCase = true) == true -> "part-time"
                // Check one: who may apply.
                AbroadFit.blocked(text) != null -> AbroadFit.blocked(text)
                Filters.containsAny(folded, Jobright.US_ONLY) -> "us-only"
                Filters.cannotApply(folded) -> "ineligible"
                // Check two: says it is open.
                !Filters.containsAny(Filters.fold(body), Jobright.WORLDWIDE) -> "not-open-worldwide"
                else -> null
            }
            return AbroadJobs.Judged(item, rejected)
        }
    }
}
