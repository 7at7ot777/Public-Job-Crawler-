package com.jobcrawler.app

/**
 * Saudi Arabia and the UAE, the two markets outside Egypt the app searches.
 * Their jobs reach the boards and company ATS sources only when
 * [Settings.boardsGulf] is on; LinkedIn's own Saudi/UAE lanes stay apart.
 */
object Gulf {
    const val SAUDI = "Saudi Arabia"
    const val UAE = "United Arab Emirates"

    val COUNTRIES = listOf(SAUDI, UAE)

    private val emiratesRe = Regex(
        """\b(uae|u\.a\.e|emirates|dubai|abu dhabi|sharjah|ajman|fujairah|ras al khaimah|umm al quwain|al ain)\b|الإمارات|الامارات|دبي""",
        RegexOption.IGNORE_CASE,
    )

    // Workday and Oracle boards often name the city alone: "Yanbu", "Al-Khobar".
    private val saudiRe = Regex(
        """\b(saudi|ksa|riyadh|jeddah|dammam|khobar|jubail|yanbu|dhahran|makkah|mecca|madinah|medina|tabuk|abha|qassim|buraydah|hofuf|ahsa|jazan|jizan|neom|taif)\b""" +
            """|السعودية|الرياض|جدة|الخبر|الدمام|مكة|المدينة المنورة""",
        RegexOption.IGNORE_CASE,
    )

    /** The Gulf country a place names, or null for anywhere else. */
    fun countryOf(place: String?): String? = when {
        place.isNullOrBlank() -> null
        emiratesRe.containsMatchIn(place) -> UAE
        saudiRe.containsMatchIn(place) -> SAUDI
        else -> null
    }

    fun isGulf(market: String?) = market != null && market in COUNTRIES

    /** As a Telegram line shows it. */
    fun flagged(market: String) = when (market) {
        SAUDI -> "🇸🇦 $SAUDI"
        UAE -> "🇦🇪 $UAE"
        else -> market
    }
}
