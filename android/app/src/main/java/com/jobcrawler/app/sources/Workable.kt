package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.Defaults
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Gulf
import com.jobcrawler.app.Item
import com.jobcrawler.app.Limits
import com.jobcrawler.app.Store
import org.json.JSONObject

/**
 * jobs.workable.com, every Workable customer's openings in one search, read
 * through the JSON endpoint its own search page calls. One response carries
 * employer, place, date and description, so nothing is opened afterwards.
 */
class Workable(
    // A JSON API that has never throttled us: 1 s apart, so a run's slice fits every keyword (backoff still applies).
    override val client: GuestClient = GuestClient("Workable", headers = mapOf("Accept" to "application/json"), delayMs = 1_000),
    /** Search Saudi Arabia and the UAE after Egypt. */
    private val gulf: Boolean = false,
) : Source {
    override val key = KEY
    override val regions get() = com.jobcrawler.app.Region.reach(KEY, gulf)

    /** Saudi Arabia and the UAE every three hours, not every run: they doubled the requests and ran Workable out of time. */
    private val gulfCadence = Cadence("${KEY}_gulf", Limits.SLOW_SOURCE_EVERY_MS)

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        val seen = LinkedHashMap<String, Item>()
        val gulfDue = gulf && gulfCadence.isDue(store)
        var gulfStarted = false

        try {
            for (location in locations(gulf, gulfDue)) for (keyword in keywords) {
                // Counted once the Gulf search really starts: an Egypt pass cut short leaves it due.
                if (location != LOCATION && !gulfStarted) {
                    gulfCadence.started(store)
                    gulfStarted = true
                }
                var pageToken: String? = null
                // Egypt is the main market: two pages there, one in each Gulf country.
                for (page in 0 until (if (location == LOCATION) MAX_PAGES else 1)) {
                    val body = client.get(
                        SEARCH_URL,
                        mapOf(
                            "query" to keyword,
                            "location" to location,
                            "day_range" to MAX_AGE_DAYS,
                            "pageToken" to pageToken,
                        ),
                    )
                    val (items, next) = parse(body, keyword, location)
                    for (item in items) {
                        if (!Dates.isOlderThan(item.postedAt, MAX_AGE_DAYS)) seen.putIfAbsent(item.externalId, item)
                    }
                    if (items.isEmpty() || next == null) break
                    pageToken = next
                }
            }
        } catch (error: BlockedException) {
            error.partialItems = seen.values.toList()
            throw error
        }

        return seen.values.toList()
    }

    companion object {
        const val KEY = "workable"
        private const val SEARCH_URL = "https://jobs.workable.com/api/v1/jobs"
        private const val MAX_PAGES = 2
        private const val MAX_AGE_DAYS = 7
        private val LOCATION = Defaults.locations.first()

        /** Egypt first, so a short run spends itself there. */
        fun locations(gulf: Boolean, gulfDue: Boolean = true) =
            if (gulf && gulfDue) listOf(LOCATION) + Gulf.COUNTRIES else listOf(LOCATION)

        private val WORKPLACES = mapOf("remote" to "Remote", "hybrid" to "Hybrid", "on_site" to "On-site")

        /** @return the jobs and the next page token. A non-JSON body (a challenge page) is no jobs. */
        fun parse(body: String, keyword: String?, searched: String): Pair<List<Item>, String?> {
            val text = body.trim()
            if (!text.startsWith("{")) return emptyList<Item>() to null
            val payload = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList<Item>() to null

            val jobs = payload.optJSONArray("jobs")
            val items = mutableListOf<Item>()

            for (index in 0 until (jobs?.length() ?: 0)) {
                val job = jobs!!.optJSONObject(index) ?: continue
                if (job.optString("state") != "published") continue

                val externalId = Html.clean(job.optString("id")) ?: continue
                val title = Html.clean(job.optString("title")) ?: continue
                val url = Html.clean(job.optString("url")) ?: continue

                val places = buildList {
                    val list = job.optJSONArray("locations")
                    for (i in 0 until (list?.length() ?: 0)) {
                        Html.clean(list!!.optString(i))?.takeIf { it != "TELECOMMUTE" }?.let(::add)
                    }
                }
                val country = Html.clean(job.optJSONObject("location")?.optString("countryName"))

                // The searched country only when the job is really there; a
                // job merely accepting it is left to the location filter.
                val placed = country.equals(searched, ignoreCase = true) ||
                    places.any { it.substringAfterLast(',').trim().equals(searched, ignoreCase = true) }

                val workplace = job.optString("workplace")
                items += Item(
                    source = KEY,
                    externalId = externalId,
                    title = title,
                    url = url,
                    company = Html.clean(job.optJSONObject("company")?.optString("title")),
                    location = if (places.isNotEmpty()) places.joinToString(" / ") else country,
                    market = if (placed) searched else null,
                    remote = workplace == "remote",
                    workplace = WORKPLACES[workplace],
                    employmentType = Html.clean(job.optString("employmentType")),
                    postedAt = Dates.absoluteToIso(job.optString("created")),
                    excerpt = listOf(job.optString("requirementsSection"), job.optString("description"))
                        .map(Html::strip).filter { it.isNotEmpty() }.joinToString("\n").take(4000).ifEmpty { null },
                    keyword = keyword,
                )
            }

            return items to Html.clean(payload.optString("nextPageToken"))
        }
    }
}
