package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Limits
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store

/**
 * The job boards of companies in Europe and the UK that are known to
 * sponsor work visas or relocate engineers — on the UK's licensed-sponsor
 * register, the Netherlands' recognised-sponsor list, or hiring
 * internationally on record. Their Greenhouse, Lever and Ashby boards are
 * public and need no key; the same readers as the company boards ([Ats]).
 *
 * A posting from one of them often does not repeat "visa sponsorship", so it
 * is marked as a known sponsor, and [AbroadFit] still requires the job to be
 * in Europe, the UK, Canada or Australia (or remote and open to Egypt), in
 * English, at the CV's stack and level, and ranks it by fit.
 */
class SponsorBoards(
    override val client: GuestClient = GuestClient(
        "Sponsor boards",
        timeoutMs = 30_000,
        headers = mapOf("Accept" to "application/json"),
        delayMs = 1_000,
    ),
    private val cadence: Cadence = Cadence(KEY, Limits.SLOW_SOURCE_EVERY_MS),
    /** platform:slug[:Company] entries to walk; the tests hand in their own. */
    private val boards: List<String> = BOARDS,
    /** One board's feed by its address; [GuestClient.get] unless a test stands in for the network. */
    private val read: (String) -> String = { url -> client.get(url) },
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
        val count = RemoteFeeds.Companion.Counts()

        try {
            Rotation(store, "$KEY.rotation").walk(boards.mapNotNull { Ats.parseBoard(it) }) { board ->
                val body = try {
                    read(Ats.urlOf(board))
                } catch (error: BlockedException) {
                    if (!error.transport) throw error
                    Log.warn("Sponsor boards: could not read ${board.platform}:${board.slug}: ${error.message}")
                    return@walk
                }
                val jobs = Ats.parseBoardResponse(body, board) ?: run {
                    Log.warn(noFeed(board, body, client.lastResponse))
                    return@walk
                }
                count.listed += jobs.size
                for (item in jobs) {
                    if (item.externalId in checked) { count.seen += 1; continue }
                    if (Dates.isOlderThan(item.postedAt, MAX_AGE_DAYS)) { count.old += 1; continue }
                    store.checked.mark(KEY, item.externalId)
                    count.judged += 1
                    RemoteFeeds.tally(RemoteFeeds.judge(asJob(item, board.name), KEY), kept, rejected)
                }
            }
        } catch (error: BlockedException) {
            error.partialItems = kept.toList()
            throw error
        } finally {
            listed.add(count.listed)
            Log.info(RemoteFeeds.summary("Sponsor boards", count, kept.size, rejected))
        }
        return kept
    }

    companion object {
        const val KEY = "sponsor_boards"
        private const val MAX_AGE_DAYS = 30

        /** What the cover note and the card say about a company that sponsors without the posting saying so. */
        const val KNOWN_SPONSOR = "Known visa sponsor: this company sponsors work visas or relocates engineers; the posting may not say so."

        /*
         * Checked on 2026-11-02: each board answered with open roles. Remote-first
         * ones (GitLab, Elastic, Supabase, Linear) are here for their remote roles
         * open to EMEA; AbroadFit decides each job.
         *
         * Re-checked on 2026-11-05: Doctolib left Greenhouse (its board API
         * answers 404) for Ashby, where careers.doctolib.com links every job
         * (jobs.ashbyhq.com/doctolib, ~150 listed).
         */
        val BOARDS = listOf(
            "greenhouse:monzo:Monzo",
            "greenhouse:deliveroo:Deliveroo",
            "greenhouse:tide:Tide",
            "greenhouse:gocardless:GoCardless",
            "greenhouse:wise:Wise",
            "greenhouse:intercom:Intercom",
            "greenhouse:adyen:Adyen",
            "greenhouse:catawiki:Catawiki",
            "greenhouse:celonis:Celonis",
            "greenhouse:n26:N26",
            "greenhouse:hellofresh:HelloFresh",
            "greenhouse:getyourguide:GetYourGuide",
            "greenhouse:contentful:Contentful",
            "greenhouse:sumup:SumUp",
            "greenhouse:trivago:trivago",
            "greenhouse:datadog:Datadog",
            "greenhouse:cloudflare:Cloudflare",
            "greenhouse:gitlab:GitLab",
            "greenhouse:elastic:Elastic",
            "lever:spotify:Spotify",
            "lever:swile:Swile",
            "lever:veepee:Veepee",
            "ashby:paddle:Paddle",
            "ashby:pennylane:Pennylane",
            "ashby:synthesia:Synthesia",
            "ashby:supabase:Supabase",
            "ashby:linear:Linear",
            "ashby:doctolib:Doctolib",
        )

        /**
         * The warning for a board that gave no feed. A 4xx arrives as "", and
         * [GuestClient.lastResponse] is only set by a 2xx, so on a 404 it still
         * describes the board before: "doctolib returned no feed (HTTP 200,
         * 265613 chars)" was trivago's answer. An empty body is said as such.
         */
        fun noFeed(board: Ats.Board, body: String, lastResponse: String?): String {
            val name = "${board.platform}:${board.slug}"
            return if (body.isBlank()) {
                "Sponsor boards: $name answered nothing (HTTP error above; board renamed, moved to another ATS or closed)"
            } else {
                "Sponsor boards: $name returned no feed (${lastResponse ?: "${body.length} chars"})"
            }
        }

        /** A board's job as the feeds' shape, marked as from a known sponsor. */
        fun asJob(item: Item, company: String?): RemoteFeeds.Job {
            val remote = item.remote || item.workplace.equals("Remote", ignoreCase = true) ||
                item.location?.contains("remote", ignoreCase = true) == true
            return RemoteFeeds.Job(
                feed = "sponsor",
                id = item.externalId,
                title = item.title,
                company = item.company ?: company,
                url = item.url,
                location = item.location,
                restriction = null,
                description = listOfNotNull(item.excerpt, KNOWN_SPONSOR).joinToString("\n"),
                postedAt = item.postedAt,
                remote = remote,
            )
        }
    }
}
