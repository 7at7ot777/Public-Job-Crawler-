package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Gulf
import com.jobcrawler.app.Item
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Career boards of named companies — Klivvr, Thndr, Tamara, Instabug and so
 * on — on Lever, Greenhouse, Ashby, BambooHR, Recruitee, Teamtailor, Breezy
 * and Zoho Recruit, each read through the public feed its own careers page is
 * built from. Ported from src/sources/ats.js and atsParser.js. SmartRecruiters,
 * Workday and Oracle HCM are Android-only, for the Saudi/UAE employers.
 *
 * One request per board, the whole board in one response. A board is
 * everything the company has open, sales included; the shared filters narrow
 * it like any other source. The boards are walked in [Rotation], so a run cut
 * short carries on from there next time. Twice a day ([EVERY_MS]): the boards
 * change slowly, and a Zoho careers page is ~2 MB of the phone's data.
 *
 * SuccessFactors (Capgemini) is left to the server: its listing is 15 MB.
 */
class Ats(
    override val client: GuestClient = GuestClient(
        "ATS",
        timeoutMs = 30_000,
        headers = mapOf("Accept" to "application/json, application/rss+xml, text/html;q=0.9"),
        // Every board is another host; the pace only keeps the phone polite.
        delayMs = 1_000,
    ),
    /** Keep a board's Saudi and UAE jobs too, not only Egypt's. */
    private val gulf: Boolean = false,
) : Source {
    override val key = KEY
    override val regions get() = com.jobcrawler.app.Region.reach(KEY, gulf)

    private val cadence = Cadence(KEY, EVERY_MS)

    override fun isDue(store: Store) = cadence.isDue(store)

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        cadence.started(store)
        val items = mutableListOf<Item>()

        try {
            Rotation(store, "ats.rotation").walk(BOARDS.mapNotNull { parseBoard(it) }) { board ->
                val body = try {
                    request(board)
                } catch (error: BlockedException) {
                    // One company's board being down is not a reason to skip the rest.
                    if (!error.transport) throw error
                    Log.warn("ATS: could not read ${board.platform}:${board.slug}: ${error.message}")
                    return@walk
                }

                val jobs = parseBoardResponse(body, board)
                if (jobs == null) {
                    // A renamed or closed board answers 404, which arrives as nothing.
                    Log.warn("ATS board returned no feed: ${board.platform}:${board.slug} (${client.lastResponse})")
                    return@walk
                }

                val maxAgeDays = BOARD_MAX_AGE_DAYS["${board.platform}:${board.slug}".lowercase()] ?: MAX_AGE_DAYS
                items += jobs.filter { !isOlderThan(it.postedAt, maxAgeDays) }.map { if (gulf) placeInGulf(it) else it }
            }
        } catch (error: BlockedException) {
            error.partialItems = items
            throw error
        }

        return items
    }

    private fun request(board: Board): String {
        if (board.platform != "workday") return client.get(urlOf(board))
        /*
         * Workday answers 20 at a time, by relevance rather than date, so a
         * new job can sit on any page: read up to WORKDAY_PAGES of them and
         * hand the parser one list.
         */
        val all = JSONArray()
        var offset = 0
        repeat(WORKDAY_PAGES) {
            val page = client.postJson(urlOf(board), requestBody(board, offset)!!)
            val json = runCatching { JSONObject(page.trim()) }.getOrNull() ?: return if (offset == 0) page else all.wrap()
            val jobs = json.optJSONArray("jobPostings") ?: JSONArray()
            for (i in 0 until jobs.length()) all.put(jobs.get(i))
            offset += WORKDAY_PAGE_SIZE
            if (jobs.length() < WORKDAY_PAGE_SIZE || offset >= json.optInt("total", 0)) return all.wrap()
        }
        return all.wrap()
    }

    private fun JSONArray.wrap() = JSONObject().put("jobPostings", this).toString()

    data class Board(val platform: String, val slug: String, val name: String?)

    /** One job as a feed describes it, before the board's own name and ids are applied. */
    private data class Raw(
        val externalId: String?,
        val title: String?,
        val url: String?,
        val company: String? = null,
        val location: String? = null,
        val workplace: String? = null,
        val employmentType: String? = null,
        val postedAt: String? = null,
        val excerpt: String? = null,
    )

    companion object {
        const val KEY = "ats"
        private const val MAX_AGE_DAYS = 14

        /** Twice a day, as the server's periodic lane (ats:12). */
        const val EVERY_MS = 12 * 3_600_000L

        /**
         * Boards with a cutoff of their own, by platform:slug, as
         * ATS_BOARD_MAX_AGE_DAYS on the server. MigrationIT keeps filled and
         * months-old openings on its page.
         */
        val BOARD_MAX_AGE_DAYS = mapOf("zoho:careers.migrationit.com" to 3)

        /**
         * [Dates.isOlderThan], except that a date with no time (Zoho's
         * Date_Opened arrives as UTC midnight) is aged by calendar day: with 3
         * days a job opened on the 31st is kept all through the 3rd.
         */
        fun isOlderThan(postedAt: String?, maxAgeDays: Int, now: Long = System.currentTimeMillis()): Boolean {
            if (postedAt == null || maxAgeDays <= 0) return false
            val posted = runCatching { Instant.parse(postedAt).toEpochMilli() }.getOrNull() ?: return false
            if (posted % 86_400_000L != 0L) return Dates.isOlderThan(postedAt, maxAgeDays, now)
            return now - now % 86_400_000L - posted > maxAgeDays * 86_400_000L
        }

        /*
         * platform:slug[:Company], from the board's own address:
         * jobs.lever.co/klivvr is lever:klivvr. As src/config.js, less
         * SuccessFactors.
         */
        val BOARDS = listOf(
            "lever:klivvr:Klivvr",
            "ashby:thndr:Thndr",
            "greenhouse:tamara",
            "bamboohr:instabug:Instabug",
            "bamboohr:bosta:Bosta",
            "recruitee:elmenus",
            "teamtailor:careers.axisapp.com:Axis",
            "teamtailor:propertyfinder:Property Finder",
            "teamtailor:supersub:Supersub",
            "teamtailor:convertedin:Convertedin",
            "teamtailor:dubizzle:Dubizzle",
            "lever:Yassir:Yassir",
            "lever:aleph:Aleph",
            "ashby:hirehangar:Hire Hangar",
            "ashby:talentsafari:Talent Safari",
            "greenhouse:agoda",
            "greenhouse:decimainternational",
            "greenhouse:wppmedia",
            "teamtailor:issinternational:ISS International",
            "breezy:almentor",
            "breezy:digisay",
            "breezy:manara",
            "breezy:takwene",
            "breezy:ontrac-solutions-llc",
            "breezy:plat4mation",
            "zoho:careers.migrationit.com:MigrationIT",
            "zoho:fixedmea:Fixed Solutions",
            "zoho:fawrymicrofinance:FawryMSME Finance",
            "zoho:leadintop:LeadInTop",
            "zoho:qutip:QuTip",
            "zoho:snap-14:SNAP14 Production",
            "zoho:zohomea.zohorecruit.in:Zoho MEA",
            "zoho:coorb:CoorB",
            "zoho:gotocme:CME",
            "zoho:centrocdx:Centro CDX",
            "zoho:dotit:Dot IT",
            "zoho:sajoom:Impact Sustainability",
            "zoho:seenapay:Seena Pay",
            "zoho:vxlabs.zohorecruit.eu:VxLabs",
            "zoho:garageilla:Garage ILLA",
            "zoho:nacita:Nacita",
            "zoho:proven-sa:Proven Arabia",
            "zoho:luckyegypt:Lucky",
            // Remote from anywhere / EMEA, Egypt included.
            "greenhouse:canonical:Canonical",
            "ashby:constructor:Constructor",
            "greenhouse:automatticcareers:Automattic",
            "greenhouse:xapo61:Xapo Bank",
            /*
             * Saudi / UAE employers: their jobs are kept with boardsGulf on,
             * and dropped by the location rule otherwise. Each had openings
             * posted within the month when it was added (November 2026).
             * A Workday slug is host/tenant/site[?search]; an Oracle HCM one
             * is host/siteNumber.
             */
            "smartrecruiters:ArchirodonGroup:Archirodon",
            "workday:wd3.myworkdaysite.com/yokogawa/yokogawa-career-site?Saudi:Yokogawa",
            "oracle:eghj.fa.em2.oraclecloud.com/CX_1:NHC",
        )

        private val PLATFORMS = setOf(
            "lever", "greenhouse", "ashby", "bamboohr", "recruitee", "teamtailor", "breezy", "zoho",
            "smartrecruiters", "workday", "oracle",
        )

        private val WORKPLACES = mapOf("remote" to "Remote", "hybrid" to "Hybrid", "onsite" to "On-site", "on_site" to "On-site")

        /**
         * A job whose place is in Saudi Arabia or the UAE, given that country
         * as its market so the Egypt location rule lets it through; any other
         * job is returned as it was.
         */
        fun placeInGulf(item: Item): Item =
            Gulf.countryOf(item.location)?.let { item.copy(market = it) } ?: item

        fun parseBoard(entry: String): Board? {
            val parts = entry.split(':').map { it.trim() }
            val platform = parts.getOrNull(0)?.lowercase() ?: return null
            val slug = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
            if (platform !in PLATFORMS) return null
            return Board(platform, slug, parts.drop(2).joinToString(":").ifEmpty { null })
        }

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        private fun teamtailorHost(slug: String) = if ('.' in slug) slug else "$slug.teamtailor.com"
        private fun zohoHost(slug: String) = if ('.' in slug) slug else "$slug.zohorecruit.com"

        /** wd3.myworkdaysite.com/yokogawa/yokogawa-career-site?Saudi -> host, tenant, site, search. */
        private data class WorkdaySlug(val host: String, val tenant: String, val site: String, val search: String)

        private fun workdaySlug(slug: String): WorkdaySlug {
            val path = slug.substringBefore('?')
            val parts = path.split('/').filter { it.isNotEmpty() }
            require(parts.size == 3) { "workday slug is host/tenant/site: $slug" }
            return WorkdaySlug(parts[0], parts[1], parts[2], if ('?' in slug) slug.substringAfter('?') else "")
        }

        /** eghj.fa.em2.oraclecloud.com/CX_1 -> host, site number. */
        private fun oracleSlug(slug: String): Pair<String, String> {
            val host = slug.substringBefore('/')
            val site = slug.substringAfter('/', "")
            require(host.isNotEmpty() && site.isNotEmpty()) { "oracle slug is host/siteNumber: $slug" }
            return host to site
        }

        /** Workday's page size (its maximum) and how many pages one board may take. */
        private const val WORKDAY_PAGE_SIZE = 20
        private const val WORKDAY_PAGES = 3

        /** Workday's search is a POST; every other feed is a GET. */
        fun requestBody(board: Board, offset: Int = 0): String? = when (board.platform) {
            "workday" -> JSONObject()
                .put("limit", WORKDAY_PAGE_SIZE)
                .put("offset", offset)
                .put("searchText", workdaySlug(board.slug).search)
                .put("appliedFacets", JSONObject())
                .toString()
            else -> null
        }

        fun urlOf(board: Board): String = when (board.platform) {
            "lever" -> "https://api.lever.co/v0/postings/${enc(board.slug)}?mode=json"
            "greenhouse" -> "https://boards-api.greenhouse.io/v1/boards/${enc(board.slug)}/jobs?content=true"
            "ashby" -> "https://api.ashbyhq.com/posting-api/job-board/${enc(board.slug)}"
            "bamboohr" -> "https://${enc(board.slug)}.bamboohr.com/careers/list"
            "recruitee" -> "https://${enc(board.slug)}.recruitee.com/api/offers/"
            "teamtailor" -> "https://${teamtailorHost(board.slug)}/jobs.rss"
            "breezy" -> "https://${enc(board.slug)}.breezy.hr/json"
            "zoho" -> "https://${zohoHost(board.slug)}/jobs/Careers"
            "smartrecruiters" -> "https://api.smartrecruiters.com/v1/companies/${enc(board.slug)}/postings?limit=100"
            "workday" -> workdaySlug(board.slug).let { "https://${it.host}/wday/cxs/${it.tenant}/${it.site}/jobs" }
            "oracle" -> oracleSlug(board.slug).let { (host, site) ->
                "https://$host/hcmRestApi/resources/latest/recruitingCEJobRequisitions?onlyData=true" +
                    "&expand=requisitionList&finder=findReqs;siteNumber=$site,limit=50,sortBy=POSTING_DATES_DESC"
            }
            else -> error("unknown platform ${board.platform}")
        }

        /** @return the board's jobs, or null when the body was not a feed at all. */
        fun parseBoardResponse(body: String, board: Board): List<Item>? {
            val raws = when (board.platform) {
                "lever" -> (decode(body) as? JSONArray)?.objects()?.map { lever(it) }
                "greenhouse" -> (decode(body) as? JSONObject)?.optJSONArray("jobs")?.objects()?.map { greenhouse(it) }
                "ashby" -> (decode(body) as? JSONObject)?.optJSONArray("jobs")?.objects()
                    ?.filter { !(it.has("isListed") && !it.optBoolean("isListed", true)) }?.map { ashby(it) }
                "bamboohr" -> (decode(body) as? JSONObject)?.optJSONArray("result")?.objects()?.map { bamboohr(it, board.slug) }
                "recruitee" -> (decode(body) as? JSONObject)?.optJSONArray("offers")?.objects()
                    ?.filter { it.str("status").let { s -> s == null || s == "published" } }?.map { recruitee(it) }
                "teamtailor" -> if (Regex("<rss[\\s>]").containsMatchIn(body)) xmlBlocks(body, "item").map { teamtailor(it) } else null
                "breezy" -> (decode(body) as? JSONArray)?.objects()?.map { breezy(it) }
                "zoho" -> zohoJobs(body)?.objects()?.filter { !(it.has("Publish") && !it.optBoolean("Publish", true)) }
                    ?.map { zoho(it, board.slug) }
                "smartrecruiters" -> (decode(body) as? JSONObject)?.optJSONArray("content")?.objects()?.map { smartrecruiters(it, board.slug) }
                "workday" -> (decode(body) as? JSONObject)?.optJSONArray("jobPostings")?.objects()?.map { workday(it, board.slug) }
                "oracle" -> (decode(body) as? JSONObject)?.optJSONArray("items")?.optJSONObject(0)
                    ?.optJSONArray("requisitionList")?.objects()?.map { oracle(it, board.slug) }
                else -> null
            } ?: return null

            return raws.mapNotNull { raw ->
                val externalId = clean(raw.externalId) ?: return@mapNotNull null
                val title = clean(raw.title) ?: return@mapNotNull null
                val url = clean(raw.url) ?: return@mapNotNull null
                val workplace = clean(raw.workplace)

                Item(
                    source = KEY,
                    // Ids are only unique inside one board.
                    externalId = "${board.platform}:${board.slug}:$externalId",
                    title = title,
                    url = url,
                    company = clean(raw.company) ?: board.name ?: titleCase(board.slug),
                    location = clean(raw.location),
                    // A board belongs to a company, not a market: the location filter reads each job.
                    market = null,
                    remote = workplace == "Remote",
                    workplace = workplace,
                    employmentType = clean(raw.employmentType),
                    postedAt = parseDate(raw.postedAt),
                    excerpt = clean(raw.excerpt)?.take(4000),
                )
            }
        }

        private fun lever(job: JSONObject): Raw {
            val categories = job.optJSONObject("categories")
            return Raw(
                externalId = job.str("id"),
                title = job.str("text"),
                url = job.str("hostedUrl"),
                location = categories?.str("location"),
                workplace = workplaceOf(job.str("workplaceType")),
                employmentType = categories?.str("commitment"),
                postedAt = job.optLong("createdAt", 0).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                excerpt = listOfNotNull(job.str("descriptionPlain"), job.str("additionalPlain")).joinToString("\n"),
            )
        }

        // Greenhouse sends the HTML entity-escaped: unescaped once before the tags go.
        private fun greenhouse(job: JSONObject) = Raw(
            externalId = job.str("id"),
            title = job.str("title"),
            url = job.str("absolute_url"),
            company = job.str("company_name"),
            location = job.optJSONObject("location")?.str("name"),
            postedAt = job.str("first_published") ?: job.str("updated_at"),
            excerpt = Html.strip(Html.strip(job.str("content"))),
        )

        private fun ashby(job: JSONObject): Raw {
            val address = job.optJSONObject("address")?.optJSONObject("postalAddress")
            // "Cairo Office" names a building; the address names the country.
            val place = listOfNotNull(address?.str("addressLocality"), address?.str("addressCountry")).joinToString(", ")
            return Raw(
                externalId = job.str("id"),
                title = job.str("title"),
                url = job.str("jobUrl"),
                location = place.ifEmpty { job.str("location") },
                workplace = workplaceOf(job.str("workplaceType")) ?: if (job.optBoolean("isRemote")) "Remote" else null,
                employmentType = job.str("employmentType"),
                postedAt = job.str("publishedAt"),
                excerpt = job.str("descriptionPlain"),
            )
        }

        // The list has no date and no description; undated is kept, and dedup sends each job once.
        private fun bamboohr(job: JSONObject, slug: String): Raw {
            val id = job.str("id")
            return Raw(
                externalId = id,
                title = job.str("jobOpeningName"),
                url = "https://$slug.bamboohr.com/careers/${enc(id ?: "")}",
                location = listOfNotNull(
                    job.optJSONObject("location")?.str("city"),
                    job.optJSONObject("location")?.str("state"),
                    job.optJSONObject("atsLocation")?.str("country"),
                ).joinToString(", "),
                // 0 on-site, 1 remote, 2 hybrid.
                workplace = mapOf("0" to "On-site", "1" to "Remote", "2" to "Hybrid")[job.str("locationType")],
                employmentType = job.str("employmentStatusLabel"),
            )
        }

        private fun recruitee(job: JSONObject) = Raw(
            externalId = job.str("id"),
            title = job.str("title"),
            url = job.str("careers_url"),
            company = job.str("company_name"),
            location = job.str("location") ?: listOfNotNull(job.str("city"), job.str("country")).joinToString(", "),
            workplace = when {
                job.optBoolean("remote") -> "Remote"
                job.optBoolean("hybrid") -> "Hybrid"
                job.optBoolean("on_site") -> "On-site"
                else -> null
            },
            employmentType = job.str("employment_type_code"),
            postedAt = job.str("published_at") ?: job.str("created_at"),
            excerpt = listOf(job.str("requirements"), job.str("description")).map(Html::strip).filter { it.isNotEmpty() }.joinToString("\n"),
        )

        private fun teamtailor(item: String): Raw {
            val place = listOfNotNull(xmlText(item, "tt:city"), xmlText(item, "tt:country")).joinToString(", ")
            return Raw(
                externalId = xmlText(item, "guid") ?: xmlText(item, "link"),
                title = xmlText(item, "title"),
                url = xmlText(item, "link"),
                location = place.ifEmpty { xmlText(item, "tt:name") },
                workplace = workplaceOf(xmlText(item, "remoteStatus")),
                postedAt = fromRfc822(xmlText(item, "pubDate")),
                excerpt = Html.strip(xmlText(item, "description")),
            )
        }

        private fun breezy(job: JSONObject): Raw {
            val locations = job.optJSONArray("locations")?.objects().orEmpty()
            val places = locations.ifEmpty { listOfNotNull(job.optJSONObject("location")) }
            val place = places.mapNotNull { at ->
                listOfNotNull(at.str("city"), at.optJSONObject("country")?.str("name")).joinToString(", ")
                    .ifEmpty { at.str("name") }
            }.joinToString(" / ")
            val location = job.optJSONObject("location")
            return Raw(
                externalId = job.str("id"),
                title = job.str("name"),
                url = job.str("url"),
                company = job.optJSONObject("company")?.str("name"),
                location = place,
                workplace = workplaceOf(location?.optJSONObject("remote_details")?.str("value"))
                    ?: if (location?.optBoolean("is_remote") == true) "Remote" else null,
                employmentType = job.optJSONObject("type")?.str("name"),
                postedAt = job.str("published_date"),
                excerpt = Html.strip(job.str("description")),
            )
        }

        // Date_Opened is a field most companies hide; undated is kept.
        private fun zoho(job: JSONObject, slug: String): Raw {
            val id = job.str("id")
            return Raw(
                externalId = id,
                title = job.str("Posting_Title") ?: job.str("Job_Opening_Name"),
                url = "https://${zohoHost(slug)}/jobs/Careers/${enc(id ?: "")}",
                location = listOfNotNull(job.str("City"), job.str("State"), job.str("Country")).joinToString(", "),
                workplace = if (job.optBoolean("Remote_Job")) "Remote" else null,
                employmentType = job.str("Job_Type"),
                postedAt = job.str("Date_Opened"),
                excerpt = Html.strip(job.str("Job_Description")),
            )
        }

        private fun smartrecruiters(job: JSONObject, slug: String): Raw {
            val location = job.optJSONObject("location")
            val id = job.str("id")
            return Raw(
                externalId = id,
                title = job.str("name"),
                url = "https://jobs.smartrecruiters.com/${enc(slug)}/${enc(id ?: "")}",
                company = job.optJSONObject("company")?.str("name"),
                // "Ruwais, , United Arab Emirates": a blank region left in.
                location = location?.str("fullLocation")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(", ")
                    ?: listOfNotNull(location?.str("city"), location?.str("country")?.uppercase()).joinToString(", "),
                workplace = when {
                    location?.optBoolean("remote") == true -> "Remote"
                    location?.optBoolean("hybrid") == true -> "Hybrid"
                    else -> null
                },
                employmentType = job.optJSONObject("typeOfEmployment")?.str("label"),
                postedAt = job.str("releasedDate"),
            )
        }

        // The list carries no description, and its date only as "Posted 6 Days Ago".
        private fun workday(job: JSONObject, slug: String): Raw {
            val at = workdaySlug(slug)
            val path = job.str("externalPath")
            val base = if (at.host.endsWith("myworkdaysite.com")) "https://${at.host}/recruiting/${at.tenant}/${at.site}"
            else "https://${at.host}/${at.site}"
            return Raw(
                externalId = job.optJSONArray("bulletFields")?.optString(0)?.trim()?.ifEmpty { null } ?: path,
                title = job.str("title"),
                url = path?.let { base + it },
                location = job.str("locationsText"),
                postedAt = workdayPosted(job.str("postedOn")),
            )
        }

        /**
         * "Posted Today", "Posted Yesterday", "Posted 6 Days Ago" as ISO;
         * "Posted 30+ Days Ago" as 31 days, so the age cut drops it rather
         * than keeping it as undated.
         */
        fun workdayPosted(text: String?, now: Long = System.currentTimeMillis()): String? {
            val value = text?.lowercase() ?: return null
            val days = when {
                "today" in value -> 0
                "yesterday" in value -> 1
                else -> Regex("(\\d+)\\+?\\s+days?").find(value)?.let { m ->
                    m.groupValues[1].toInt() + if ('+' in m.value) 1 else 0
                } ?: return null
            }
            return Instant.ofEpochMilli(now - days * 86_400_000L).toString()
        }

        private fun oracle(job: JSONObject, slug: String): Raw {
            val (host, site) = oracleSlug(slug)
            val id = job.str("Id")
            return Raw(
                externalId = id,
                title = job.str("Title"),
                url = "https://$host/hcmUI/CandidateExperience/en/sites/$site/job/${enc(id ?: "")}",
                location = job.str("PrimaryLocation"),
                workplace = workplaceOf(job.str("WorkplaceTypeCode")?.removePrefix("ORA_")),
                postedAt = job.str("PostedDate"),
                excerpt = Html.strip(job.str("ShortDescriptionStr")),
            )
        }

        /**
         * A Zoho Recruit careers page has no feed, but carries every published
         * job as entity-escaped JSON in a hidden <input id="jobs">. Quote-aware:
         * the value may hold a raw ">" from a description.
         */
        private fun zohoJobs(body: String): JSONArray? {
            val input = Regex("<input\\b(?:[^>\"]|\"[^\"]*\")*\\bid=\"jobs\"(?:[^>\"]|\"[^\"]*\")*>").find(body)?.value ?: return null
            val value = Regex("\\bvalue=\"([^\"]*)\"").find(input)?.groupValues?.get(1) ?: return null
            return decode(unescapeEntities(value)) as? JSONArray
        }

        private fun unescapeEntities(value: String): String = value
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: it.value }
            .replace(Regex("&#x([0-9a-f]+);", RegexOption.IGNORE_CASE)) {
                it.groupValues[1].toIntOrNull(16)?.let { c -> String(Character.toChars(c)) } ?: it.value
            }
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

        /** A 4xx arrives as "" and an error page as HTML: no feed. */
        private fun decode(body: String): Any? {
            val text = body.trim()
            return when {
                text.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull()
                text.startsWith("[") -> runCatching { JSONArray(text) }.getOrNull()
                else -> null
            }
        }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

        /** A field as text, null when missing, JSON null or blank. */
        private fun JSONObject.str(name: String): String? =
            if (!has(name) || isNull(name)) null else optString(name).trim().ifEmpty { null }

        private fun xmlBlocks(body: String, tag: String): List<String> =
            Regex("<$tag>([\\s\\S]*?)</$tag>").findAll(body).map { it.groupValues[1] }.toList()

        /** The first <tag>'s text, CDATA unwrapped or entities decoded once. */
        private fun xmlText(xml: String, tag: String): String? {
            val inner = Regex("<$tag>([\\s\\S]*?)</$tag>").find(xml)?.groupValues?.get(1) ?: return null
            Regex("^\\s*<!\\[CDATA\\[([\\s\\S]*?)]]>\\s*$").find(inner)?.let { return it.groupValues[1] }
            return inner
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace(Regex("&#0?39;|&apos;"), "'")
                .replace("&amp;", "&")
        }

        private fun workplaceOf(value: String?) = WORKPLACES[value?.lowercase()]

        private fun fromRfc822(value: String?): String? =
            value?.let { runCatching { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString() }.getOrNull() }

        /** ISO instants, "2026-10-01 08:00:00 UTC", and plain dates. */
        private fun parseDate(value: String?): String? {
            val text = value?.trim()?.ifEmpty { null } ?: return null
            return Dates.absoluteToIso(text.replace(" UTC", "Z").replaceFirst(' ', 'T'))
                ?: Dates.absoluteToIso(text)
        }

        /** "mnt-halan" -> "Mnt Halan": a fallback name, better than none. */
        private fun titleCase(slug: String) =
            slug.split('-', '_').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

        private fun clean(value: String?): String? = value?.replace(Regex("[ \\t]+"), " ")?.trim()?.ifEmpty { null }
    }
}
