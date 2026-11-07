package com.jobcrawler.app

import android.content.Context
import com.jobcrawler.app.sources.CareerSites
import org.json.JSONArray
import org.json.JSONObject

/**
 * Careers sites added on the phone, from the settings screen, kept beside the
 * built-in [CareerSites.SITES]. Stored as the pattern's text, not a Regex, in
 * a preferences file of their own so a settings save never touches them.
 */
data class SavedSite(
    val id: String,
    val company: String,
    val sitemap: String,
    val pattern: String,
    val location: String = "Cairo, Egypt",
    val sitemapFilter: String? = null,
) {
    fun toSite() = CareerSites.Site(
        id = id,
        company = company,
        sitemap = sitemap,
        jobUrl = Regex(pattern, RegexOption.IGNORE_CASE),
        location = location,
        sitemapFilter = sitemapFilter?.let { Regex(it, RegexOption.IGNORE_CASE) },
    )
}

object UserSites {
    private const val PREFS = "career_sites"
    private const val KEY = "sites"

    fun load(context: Context): List<SavedSite> =
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, sites: List<SavedSite>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(sites)).apply()
    }

    fun add(context: Context, site: SavedSite) = save(context, load(context).filterNot { it.id == site.id } + site)

    fun remove(context: Context, id: String) = save(context, load(context).filterNot { it.id == id })

    /** An id no built-in or saved site has: "acme", then "acme-2". */
    fun freeId(base: String, taken: Collection<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n += 1
        return "$base-$n"
    }

    fun encode(sites: List<SavedSite>): String = JSONArray().apply {
        for (site in sites) {
            put(JSONObject().apply {
                put("id", site.id)
                put("company", site.company)
                put("sitemap", site.sitemap)
                put("pattern", site.pattern)
                put("location", site.location)
                site.sitemapFilter?.let { put("sitemapFilter", it) }
            })
        }
    }.toString()

    /** A damaged entry is dropped, not a reason to lose the rest. */
    fun decode(text: String?): List<SavedSite> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()

        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val site = SavedSite(
                id = entry.optString("id").ifEmpty { return@mapNotNull null },
                company = entry.optString("company").ifEmpty { return@mapNotNull null },
                sitemap = entry.optString("sitemap").ifEmpty { return@mapNotNull null },
                pattern = entry.optString("pattern").ifEmpty { return@mapNotNull null },
                location = entry.optString("location").ifEmpty { "Cairo, Egypt" },
                sitemapFilter = entry.optString("sitemapFilter").ifEmpty { null },
            )
            // A pattern that no longer compiles would fail every run.
            site.takeIf { runCatching { it.toSite() }.isSuccess }
        }
    }
}
