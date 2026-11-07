package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Limits
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Free job feeds, no key or account: Himalayas, Jobicy, Remotive, We Work
 * Remotely, RemoteOK, Working Nomads and Remote Rocketship (remote jobs, each
 * saying which countries may apply), Arbeitnow (mostly on-site in Germany)
 * and Landing.jobs. A job is
 * judged once: open to Egypt by the feed's own restriction, then by
 * [AbroadJobs.judge] — remote or visa, English, the CV's stack and level.
 * One feed failing never stops the others.
 */
class RemoteFeeds(
    override val client: GuestClient = GuestClient("Remote feeds", headers = mapOf("Accept" to "application/json, application/rss+xml")),
    private val cadence: Cadence = Cadence(KEY, Limits.SLOW_SOURCE_EVERY_MS),
    /** Feed ids switched off in Settings → Remote job feeds; see [TOGGLEABLE]. */
    private val off: Set<String> = emptySet(),
) : Source {
    override val key = KEY

    override val autoEmail: Boolean = true

    override fun isDue(store: Store) = cadence.isDue(store)

    /** What the remote listed this run; see [Source.listed]. */
    override val listed = ListedCount()

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        cadence.started(store)
        val checked = store.checked.ids(KEY)
        val kept = mutableListOf<Item>()
        val rejected = sortedMapOf<String, Int>()
        val count = Counts()
        val perFeed = mutableListOf<String>()

        try {
            for (feed in FEEDS) if (feed.id != null && feed.id in off) perFeed += "${feed.name} off"
            for ((name, url, parse) in active(off)) {
                if (client.isStopped()) break
                val jobs = try {
                    parse(client.get(url))
                } catch (error: BlockedException) {
                    if (!error.transport) throw error
                    Log.warn("Remote feeds: $name unreachable: ${error.message}")
                    perFeed += "$name ✗"
                    continue
                } catch (error: Exception) {
                    Log.warn("Remote feeds: $name did not parse: ${error.javaClass.simpleName}: ${error.message}")
                    perFeed += "$name ✗"
                    continue
                }
                perFeed += "$name ${jobs.size}"
                count.listed += jobs.size
                for (job in jobs) {
                    val id = "${job.feed}:${job.id}"
                    if (id in checked) { count.seen += 1; continue }
                    if (Dates.isOlderThan(job.postedAt, MAX_AGE_DAYS)) { count.old += 1; continue }
                    store.checked.mark(KEY, id)
                    count.judged += 1
                    tally(judge(job, KEY), kept, rejected)
                }
            }
        } catch (error: BlockedException) {
            error.partialItems = kept.toList()
            throw error
        } finally {
            listed.add(count.listed)
            Log.info("Remote feeds: ${perFeed.joinToString(" · ")}")
            Log.info(summary("Remote feeds", count, kept.size, rejected))
        }
        return kept
    }

    /** One job as a feed lists it. [restriction] is who may apply, in the feed's words; null when it says nothing. */
    data class Job(
        val feed: String,
        val id: String,
        val title: String,
        val company: String?,
        val url: String,
        val location: String?,
        val restriction: String?,
        val description: String,
        val postedAt: String?,
        val remote: Boolean = true,
    )

    /** [id] is set on the feeds that have their own switch in Settings; it equals the [Job.feed] they emit. */
    data class Feed(val name: String, val url: String, val parse: (String) -> List<Job>, val id: String? = null)

    companion object {
        const val KEY = "remote_feeds"
        private const val MAX_AGE_DAYS = 14

        /* The trailing slash matters: without it the site answers a redirect. */
        private const val ROCKETSHIP = "https://www.remoterocketship.com/country/egypt/jobs/"

        val FEEDS = listOf(
            Feed("Himalayas", "https://himalayas.app/jobs/api?limit=100", ::himalayas, id = "himalayas"),
            Feed("Jobicy", "https://jobicy.com/api/v2/remote-jobs?count=50&industry=dev", ::jobicy, id = "jobicy"),
            Feed("Remotive", "https://remotive.com/api/remote-jobs?category=software-dev&limit=100", ::remotive, id = "remotive"),
            Feed("We Work Remotely", "https://weworkremotely.com/categories/remote-back-end-programming-jobs.rss", ::weWorkRemotely, id = "wwr"),
            Feed("RemoteOK", "https://remoteok.com/api", ::remoteOk, id = "remoteok"),
            Feed("Working Nomads", "https://www.workingnomads.com/api/exposed_jobs/", ::workingNomads),
            Feed("Arbeitnow", "https://www.arbeitnow.com/api/job-board-api", ::arbeitnow),
            Feed("Landing.jobs", "https://landing.jobs/api/v1/jobs?limit=50", ::landingJobs),
            Feed("Landing.jobs", "https://landing.jobs/api/v1/jobs?limit=50&offset=50", ::landingJobs),
            // Remote jobs open to Egypt, one title per page; only a page's first 20 are public, so three titles.
            Feed("Remote Rocketship", ROCKETSHIP + "software-engineer/", ::remoteRocketship, id = "rocketship"),
            Feed("Remote Rocketship", ROCKETSHIP + "backend-engineer/", ::remoteRocketship, id = "rocketship"),
            Feed("Remote Rocketship", ROCKETSHIP + "fullstack-engineer/", ::remoteRocketship, id = "rocketship"),
        )

        /** The feeds with their own switch, id to name, in run order. The rest run whenever the source is on. */
        val TOGGLEABLE: List<Pair<String, String>> = FEEDS.mapNotNull { feed -> feed.id?.let { it to feed.name } }.distinct()

        /** The feeds a run reads: every feed but the switched-off ones. */
        fun active(off: Set<String>): List<Feed> = FEEDS.filter { it.id == null || it.id !in off }

        private val COUNTRY_CODES = mapOf(
            "PT" to "Portugal", "ES" to "Spain", "DE" to "Germany", "NL" to "Netherlands", "GB" to "United Kingdom",
            "UK" to "United Kingdom", "IE" to "Ireland", "FR" to "France", "BE" to "Belgium", "IT" to "Italy",
            "PL" to "Poland", "SE" to "Sweden", "DK" to "Denmark", "AT" to "Austria", "CH" to "Switzerland",
            "LU" to "Luxembourg", "CZ" to "Czech Republic", "RO" to "Romania", "GR" to "Greece", "CA" to "Canada", "AU" to "Australia",
        )

        /**
         * Landing.jobs, a European tech board: each job says whether the
         * relocation is paid and whether it is remote. A paid relocation is
         * written into the text, so the visa lane sees it.
         */
        fun landingJobs(body: String): List<Job> = objects(JSONArray(body)).mapNotNull { job ->
            val url = clean(job.optString("url")) ?: return@mapNotNull null
            val places = objects(job.optJSONArray("locations")).mapNotNull { place ->
                val country = clean(place.optString("country_code"))?.uppercase()?.let { COUNTRY_CODES[it] ?: it }
                listOfNotNull(clean(place.optString("city")), country).joinToString(", ").ifEmpty { null }
            }
            val text = listOf("role_description", "main_requirements", "nice_to_have", "perks")
                .map { Html.strip(job.optString(it)) }.filter { it.isNotEmpty() }.toMutableList()
            strings(job.optJSONArray("tags")).takeIf { it.isNotEmpty() }?.let { text += "Stack: " + it.joinToString(", ") }
            if (job.optBoolean("relocation_paid", false)) text += "Relocation package provided: the relocation is paid."
            Job(
                feed = "landingjobs",
                id = clean(job.optString("id")) ?: return@mapNotNull null,
                title = clean(job.optString("title")) ?: return@mapNotNull null,
                // "https://landing.jobs/at/inscale/senior-java-…": the company is the path's second part.
                company = Regex("""/at/([^/]+)/""").find(url)?.groupValues?.get(1)?.replace('-', ' ')?.replaceFirstChar(Char::uppercaseChar),
                url = url,
                location = places.joinToString(" / ").ifEmpty { null },
                restriction = null,
                description = text.joinToString("\n"),
                postedAt = Dates.absoluteToIso(job.optString("published_at")),
                remote = job.optBoolean("remote", false),
            )
        }

        /**
         * The job as an item, judged. Remote jobs have to be open to Egypt by
         * the feed's own word first; what the feed says goes into the place,
         * so the lane check reads "Worldwide" or "EMEA" too.
         */
        fun judge(job: Job, source: String): AbroadJobs.Judged {
            val place = listOfNotNull(job.location, job.restriction?.let { "Open to: $it" }).joinToString(" · ").ifEmpty { null }
                ?: if (job.remote) "Remote, worldwide" else null
            val card = Item(
                source = source,
                externalId = "${job.feed}:${job.id}",
                title = job.title,
                url = job.url,
                company = job.company,
                location = place,
                postedAt = job.postedAt,
                board = boardName(job.feed),
            )
            if (!AbroadJobs.titleFits(card)) return AbroadJobs.Judged(card, "title")
            if (job.remote && !AbroadFit.openToEgypt(job.restriction)) return AbroadJobs.Judged(card, "region-locked")
            return AbroadJobs.judge(card, job.description, emptyMap(), job.remote)
        }

        /** What a run did with the jobs it listed: why a job was not judged is as telling as why it was dropped. */
        class Counts(var listed: Int = 0, var seen: Int = 0, var old: Int = 0, var judged: Int = 0)

        /** "Remote feeds: 527 listed, 507 seen before, 0 too old, 20 judged, 0 kept | dropped {title=20}". */
        fun summary(name: String, count: Counts, kept: Int, rejected: Map<String, Int>): String =
            "$name: ${count.listed} listed, ${count.seen} seen before, ${count.old} too old, ${count.judged} judged, $kept kept" +
                if (rejected.isEmpty()) "" else " | dropped $rejected"

        /** A judged job into the kept list, or its reason into the tally. */
        fun tally(judged: AbroadJobs.Judged, kept: MutableList<Item>, rejected: MutableMap<String, Int>) {
            val reason: String? = judged.rejected
            if (reason == null) {
                kept.add(judged.item)
            } else {
                rejected[reason] = (rejected[reason] ?: 0) + 1
            }
        }

        private fun boardName(feed: String) = when (feed) {
            "himalayas" -> "Himalayas"
            "jobicy" -> "Jobicy"
            "remotive" -> "Remotive"
            "wwr" -> "We Work Remotely"
            "remoteok" -> "RemoteOK"
            "workingnomads" -> "Working Nomads"
            "arbeitnow" -> "Arbeitnow"
            "landingjobs" -> "Landing.jobs"
            "rocketship" -> "Remote Rocketship"
            "jobgether" -> "Jobgether"
            "sponsor" -> "Company board, known visa sponsor"
            "hn" -> "Hacker News, Who is hiring"
            else -> feed
        }

        fun himalayas(body: String): List<Job> = objects(JSONObject(body).optJSONArray("jobs")).mapNotNull { job ->
            val places = strings(job.optJSONArray("locationRestrictions"))
            val zones = (0 until (job.optJSONArray("timezoneRestrictions")?.length() ?: 0)).map { job.getJSONArray("timezoneRestrictions").optDouble(it) }
            // Countries, else time zones: a list without UTC+1 to +3 does not take Cairo.
            val restriction = when {
                places.isNotEmpty() -> places.joinToString(", ")
                zones.isNotEmpty() && zones.none { it in 1.0..3.0 } -> "time zones ${zones.joinToString(", ") { it.toInt().toString() }} only"
                zones.isNotEmpty() -> "UTC+2"
                else -> null
            }
            Job(
                feed = "himalayas",
                id = clean(job.optString("guid")) ?: return@mapNotNull null,
                title = clean(job.optString("title")) ?: return@mapNotNull null,
                company = clean(job.optString("companyName")),
                url = clean(job.optString("guid")) ?: return@mapNotNull null,
                location = null,
                restriction = restriction,
                description = Html.strip(job.optString("description")),
                postedAt = job.optLong("pubDate").takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() },
            )
        }

        fun jobicy(body: String): List<Job> = objects(JSONObject(body).optJSONArray("jobs")).mapNotNull { job ->
            Job(
                feed = "jobicy",
                id = clean(job.optString("id")) ?: return@mapNotNull null,
                title = clean(Html.decode(job.optString("jobTitle"))) ?: return@mapNotNull null,
                company = clean(Html.decode(job.optString("companyName"))),
                url = clean(job.optString("url")) ?: return@mapNotNull null,
                location = null,
                restriction = clean(job.optString("jobGeo")),
                description = Html.strip(job.optString("jobDescription")),
                postedAt = Dates.absoluteToIso(job.optString("pubDate")),
            )
        }

        fun remotive(body: String): List<Job> = objects(JSONObject(body).optJSONArray("jobs")).mapNotNull { job ->
            Job(
                feed = "remotive",
                id = clean(job.optString("id")) ?: return@mapNotNull null,
                title = clean(job.optString("title")) ?: return@mapNotNull null,
                company = clean(job.optString("company_name")),
                url = clean(job.optString("url")) ?: return@mapNotNull null,
                location = null,
                restriction = clean(job.optString("candidate_required_location")),
                description = Html.strip(job.optString("description")),
                // "2026-10-29T18:22:41": UTC without the zone.
                postedAt = clean(job.optString("publication_date"))?.let { Dates.absoluteToIso(if (it.length == 19) "${it}Z" else it) },
            )
        }

        private val ITEM = Regex("""<item>([\s\S]*?)</item>""")

        private fun tag(xml: String, name: String): String? =
            Regex("""<$name>(?:<!\[CDATA\[)?([\s\S]*?)(?:]]>)?</$name>""").find(xml)?.groupValues?.get(1)

        fun weWorkRemotely(body: String): List<Job> = ITEM.findAll(body).mapNotNull { match ->
            val item = match.groupValues[1]
            val heading = clean(Html.decode(tag(item, "title"))) ?: return@mapNotNull null
            val link = clean(tag(item, "link")) ?: return@mapNotNull null
            // "Dropbox: Backend Product Software Engineer".
            val company = heading.substringBefore(':', "").trim().ifEmpty { null }
            Job(
                feed = "wwr",
                id = link.substringAfterLast('/'),
                title = if (company != null) heading.substringAfter(':').trim() else heading,
                company = company,
                url = link,
                location = null,
                restriction = clean(tag(item, "region")),
                description = Html.strip(Html.decode(tag(item, "description"))),
                postedAt = rfc1123(tag(item, "pubDate")),
            )
        }.toList()

        fun remoteOk(body: String): List<Job> = objects(JSONArray(body)).mapNotNull { job ->
            // The first entry is the terms of use, not a job.
            if (!job.has("position")) return@mapNotNull null
            Job(
                feed = "remoteok",
                id = clean(job.optString("id")) ?: return@mapNotNull null,
                title = clean(Html.decode(job.optString("position"))) ?: return@mapNotNull null,
                company = clean(job.optString("company")),
                url = clean(job.optString("url")) ?: return@mapNotNull null,
                location = null,
                restriction = clean(job.optString("location")),
                description = Html.strip(job.optString("description")),
                postedAt = Dates.absoluteToIso(job.optString("date")),
            )
        }

        fun workingNomads(body: String): List<Job> = objects(JSONArray(body)).mapNotNull { job ->
            val url = clean(job.optString("url")) ?: return@mapNotNull null
            Job(
                feed = "workingnomads",
                id = Regex("""/(\d+)/?$""").find(url)?.groupValues?.get(1) ?: url,
                title = clean(job.optString("title")) ?: return@mapNotNull null,
                company = clean(job.optString("company_name")),
                url = url,
                location = null,
                restriction = clean(job.optString("location")),
                description = Html.strip(job.optString("description")),
                postedAt = Dates.absoluteToIso(job.optString("pub_date")),
            )
        }

        fun arbeitnow(body: String): List<Job> = objects(JSONObject(body).optJSONArray("data")).mapNotNull { job ->
            Job(
                feed = "arbeitnow",
                id = clean(job.optString("slug")) ?: return@mapNotNull null,
                title = clean(job.optString("title")) ?: return@mapNotNull null,
                company = clean(job.optString("company_name")),
                url = clean(job.optString("url")) ?: return@mapNotNull null,
                location = clean(job.optString("location"))?.let { "$it, Germany" },
                restriction = null,
                description = Html.strip(job.optString("description")),
                postedAt = job.optLong("created_at").takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() },
                remote = job.optBoolean("remote", false),
            )
        }

        private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>""")

        /**
         * Remote Rocketship's Egypt pages: remote jobs it says Egypt may take,
         * read from the page's own Next.js data. Each job links to the
         * employer's ATS and carries the countries it is open to, a two-line
         * summary and the stack, which is what the judge reads. A page also
         * shows remote jobs for nearby countries (the UAE); their country list
         * leaves Egypt out, so they fail the region check.
         */
        fun remoteRocketship(body: String): List<Job> {
            val data = NEXT_DATA.find(body)?.groupValues?.get(1) ?: return emptyList()
            val page = JSONObject(data).optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()
            return objects(page.optJSONArray("initialJobOpenings")).mapNotNull { job ->
                val countries = strings(job.optJSONArray("locationCountries"))
                val stack = strings(job.optJSONArray("techStack"))
                val summary = clean(job.optString("twoLineJobDescriptionSummary")) ?: clean(job.optString("jobDescriptionSummary"))
                val type = clean(job.optString("employmentType"))
                val text = listOfNotNull(
                    summary,
                    stack.takeIf { it.isNotEmpty() }?.let { "Stack: " + it.joinToString(", ") },
                    type?.let { "Employment: $it" },
                )
                Job(
                    feed = "rocketship",
                    id = clean(job.optString("id")) ?: return@mapNotNull null,
                    title = clean(job.optString("roleTitle")) ?: return@mapNotNull null,
                    company = clean(job.optJSONObject("company")?.optString("name")),
                    url = clean(job.optString("url")) ?: return@mapNotNull null,
                    location = null,
                    restriction = countries.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: clean(job.optString("location")),
                    description = text.joinToString("\n"),
                    postedAt = Dates.absoluteToIso(job.optString("created_at")),
                    remote = job.optString("locationType").equals("remote", ignoreCase = true),
                )
            }
        }

        fun rfc1123(value: String?): String? = value?.trim()?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString() }.getOrNull()
        }

        fun objects(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).mapNotNull { array!!.optJSONObject(it) }

        fun strings(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0)).mapNotNull { clean(array!!.optString(it)) }

        private fun clean(value: String?): String? = Html.clean(value)?.takeIf { it != "null" }
    }
}
