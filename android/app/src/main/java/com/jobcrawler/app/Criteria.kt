package com.jobcrawler.app

import android.content.Context
import com.jobcrawler.app.sources.LinkedInPosts

/**
 * The search and filter lists the person can change from Settings. Each one
 * starts as the list src/config.js has; a list never edited keeps following
 * the app's default, so an update that improves a default still reaches it.
 *
 * Stored one entry per line, not comma separated: "Cairo, Egypt" is one place.
 */
object Criteria {
    /** One editable list: what it is called on the screen and what it does. */
    enum class Kind(val title: String, val caption: String, val defaults: List<String>) {
        // "Egypt" alone: it covers Cairo and Giza, and each place is one more request per keyword to LinkedIn.
        SEARCH_PLACES("Places to search", "LinkedIn jobs searches one of these a run, every 3 minutes, in turn: each one makes the cycle 3 minutes longer", Defaults.locations),
        POST_KEYWORDS("Post keywords", "Hiring posts, once a day. Each keyword × place is one paid Serper search, or × group of places when grouped", LinkedInPosts.KEYWORDS),
        POST_PLACES("Post places", "Each post keyword is searched with each of these", LinkedInPosts.LOCATIONS),
        TECH("Stack", "A job has to mention one of these", Filters.DEFAULT_TECH),
        TITLES("Role in the title", "A job's title has to contain one of these", Filters.DEFAULT_TITLE_TERMS),
        TOO_SENIOR("Too senior", "A title with one of these is dropped", Filters.DEFAULT_SENIORITY_EXCLUDE),
        EGYPT_PLACES("Counts as Egypt", "A posting naming one of these is in Egypt", Filters.DEFAULT_EGYPT_PLACES),
        BLOCKED_PLACES("Never these places", "A job placed in one of these is dropped", Filters.DEFAULT_BLOCKED_LOCATIONS),
        GULF_SEARCH_PLACES("Saudi & UAE places to search", "Posted to Telegram under #Gulf. LinkedIn jobs is searched in each of these every 3 h", Defaults.gulfLocations),
        GULF_POST_PLACES("Saudi & UAE post places", "Hiring posts, once a day. Each post keyword × place is one paid Serper search", LinkedInPosts.GULF_LOCATIONS),
        ABROAD_PLACES(
            "Europe, Canada & Australia places",
            "LinkedIn, every 3 h: remote jobs open to Egypt, and visa-sponsored ones, for the CV's stack",
            com.jobcrawler.app.sources.AbroadJobs.DEFAULT_PLACES,
        ),
        GULF_PLACES("Counts as Saudi or UAE", "A Saudi/UAE hiring post has to name one of these", Filters.DEFAULT_GULF_PLACES),
        INELIGIBLE(
            "Can't apply (residency, nationality, visa)",
            "A job saying one of these is dropped. \"Must be based in\" and the like are ignored when Egypt follows",
            Filters.DEFAULT_INELIGIBLE,
        ),
    }

    /* The search lists, shown under "What to search"; the rest decide what reaches you. */
    // Every search list's source is on again; only ABROAD_PLACES (the retired abroad_jobs search) stays hidden.
    val search = listOf(Kind.SEARCH_PLACES, Kind.GULF_SEARCH_PLACES, Kind.POST_KEYWORDS, Kind.POST_PLACES, Kind.GULF_POST_PLACES)
    /** Kept (saved values, defaults), shown nowhere while their sources are off. */
    val hidden = listOf(Kind.ABROAD_PLACES)
    val filters = listOf(Kind.TECH, Kind.TITLES, Kind.TOO_SENIOR, Kind.EGYPT_PLACES, Kind.GULF_PLACES, Kind.BLOCKED_PLACES, Kind.INELIGIBLE)

    /*
     * What the crawler reads. Set from the saved lists when a run starts and
     * whenever Settings saves; the defaults until then, which is what tests see.
     * Swapped whole, never changed in place, so workers reading it in
     * parallel with a save see the old lists or the new ones, never a mix.
     */
    @Volatile
    private var current: Map<Kind, List<String>> = emptyMap()

    operator fun get(kind: Kind): List<String> = current[kind] ?: kind.defaults

    fun isEdited(kind: Kind): Boolean = kind in current

    /** Reads every saved list into what the crawler uses. */
    fun load(context: Context) {
        val prefs = prefs(context)
        current = Kind.entries.mapNotNull { kind -> parse(prefs.getString(kind.name, null))?.let { kind to it } }.toMap()
    }

    /** Saves one list; an empty one, or one equal to the default, goes back to following the default. */
    fun save(context: Context, kind: Kind, entries: List<String>) {
        val clean = parse(entries.joinToString("\n")) ?: emptyList()
        prefs(context).edit().apply {
            if (clean.isEmpty() || clean == kind.defaults) remove(kind.name) else putString(kind.name, clean.joinToString("\n"))
        }.commit()
        load(context)
    }

    fun reset(context: Context, kind: Kind) = save(context, kind, emptyList())

    /** One entry per line; blank lines and repeats, in any case, dropped. */
    fun parse(text: String?): List<String>? =
        text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinctBy { it.lowercase() }?.ifEmpty { null }

    private fun prefs(context: Context) = context.getSharedPreferences("criteria", Context.MODE_PRIVATE)
}
