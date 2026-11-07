package com.jobcrawler.app.sources

import com.jobcrawler.app.Filters

/**
 * Arabic freelance boards (Mostaql, Khamsat): gigs, not jobs, filed under
 * their own region (Region.FREELANCE) and posted with #Freelance.
 *
 * The job filters' title and stack lists are English job titles, so an
 * Arabic project ("بناء متجر إلكتروني") would fail them all. A freelance
 * item is judged here instead: it has to name the CV's stack, in English
 * or in Arabic. Pure, so it is tested without a phone.
 */
object Freelance {
    val KEYS get() = setOf(Mostaql.KEY, Khamsat.KEY)

    /** A non-broad market, so Filters' location rule lets it through; never 🌍/✈️, so it is not Worldwide. */
    fun market(board: String) = "💼 Freelance · $board"

    /*
     * The CV's stack in Arabic. Arabic letters are outside Filters' word
     * boundaries, so these match inside longer words too: never a bare
     * "نود" ("نود أن" is "we would like") or "جو" (inside جودة, جوال, ...).
     */
    private val ARABIC = linkedMapOf(
        "Laravel" to listOf("لارافيل", "لارفيل", "لارافل"),
        "PHP" to listOf("بي اتش بي", "بي إتش بي", "بى اتش بى"),
        "Node.js" to listOf("نود جي اس", "نود جى اس", "نود جي إس", "نودجي اس", "نود.جي اس"),
        "NestJS" to listOf("نست جي اس", "نيست جي اس"),
        "Go" to listOf("جولانج", "جو لانج"),
        "TypeScript" to listOf("تايب سكريبت", "تايبسكريبت"),
        "Backend" to listOf(
            "باك اند", "باك إند", "باك-اند", "الباك اند", "الباك إند",
            "الواجهة الخلفية", "واجهة برمجة", "واجهات برمجة",
        ),
        "Full stack" to listOf("فول ستاك", "فل ستاك", "فول-ستاك"),
    )

    /* Backend work in English that is not one of the CV's named languages. */
    private val BACKEND = listOf(
        "backend", "back-end", "back end", "api", "apis", "rest api", "restful", "express", "express.js", "expressjs",
    )

    /* Full-stack work, which the CV also covers. */
    private val FULL_STACK = listOf("full stack", "full-stack", "fullstack")

    /** The stack [text] asks for, English names first then Arabic hits; empty when none. */
    fun stack(text: String): List<String> {
        val folded = Filters.fold(text)
        val found = LinkedHashSet(CvStack.of(text))
        for ((name, terms) in ARABIC) if (Filters.containsAny(folded, terms)) found += name
        if (Filters.containsAny(folded, BACKEND)) found += "Backend"
        if (Filters.containsAny(folded, FULL_STACK)) found += "Full stack"
        return found.toList()
    }

    /** Whether a gig is work the CV can take. */
    fun fits(text: String) = stack(text).isNotEmpty()

    /** "$50.00 - $100.00" as "$50–100"; a single amount as "$250"; null when there is none. */
    fun budget(value: String?): String? {
        val amounts = Regex("""\d[\d,]*(?:\.\d+)?""").findAll(value ?: return null)
            .map { it.value.replace(",", "").removeSuffix(".00").removeSuffix(".0") }
            .toList()
        return when (amounts.size) {
            0 -> null
            1 -> "$${amounts[0]}"
            else -> "$${amounts[0]}–${amounts[1]}"
        }
    }
}
