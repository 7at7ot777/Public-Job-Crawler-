package com.jobcrawler.app

import java.util.Locale

/**
 * What a posting asks for and offers, read from its own words: years of
 * experience ("3-5 years of experience", "خبرة 3 سنوات") and a salary
 * ("15,000 - 20,000 EGP per month").
 *
 * Both are cautious: a number only counts next to the word that makes it
 * one (experience, a currency), so "founded 10 years ago" or a phone number
 * reads as nothing rather than as a wrong answer.
 */
object Extract {
    /** [max] is null for an open-ended "3+". */
    data class Years(val min: Int, val max: Int?) {
        override fun toString() = if (max != null && max != min) "$min–$max yrs" else if (max == null) "$min+ yrs" else "$min yrs"

        companion object {
            /** What an item already carries, or null when it asked for nothing. */
            fun of(item: Item): Years? = item.yearsMin?.let { Years(it, item.yearsMax) }
        }
    }

    private const val RANGE = """(?:(?:-|–|to)\s*(\d{1,2})\s*)?"""

    private val englishRes = listOf(
        // "3-5 years of experience", "3+ yrs backend experience"
        Regex(
            """(\d{1,2})\s*\+?\s*$RANGE\+?\s*(?:years?|yrs?)['’]?\s+(?:of\s+)?(?:[a-z/+#.-]+\s+){0,3}?(?:experience|exp\b)""",
            RegexOption.IGNORE_CASE,
        ),
        // "Minimum 4 years", "at least 2-3 years"
        Regex("""(?:minimum(?:\s+of)?|at\s+least|min\.?)\s*(\d{1,2})\s*\+?\s*$RANGE(?:years?|yrs?)\b""", RegexOption.IGNORE_CASE),
        // "Experience: 3 years"
        Regex("""experience\s*[:：-]\s*(\d{1,2})\s*\+?\s*$RANGE(?:years?|yrs?)\b""", RegexOption.IGNORE_CASE),
    )

    private const val AR_YEARS = "(?:سنوات|سنة|سنين|عام|أعوام|اعوام)"
    private const val AR_EXPERIENCE = "(?:ال)?خبر[ةه]"

    private val arabicRes = listOf(
        // "خبرة 3 سنوات", "خبرة لا تقل عن 2 سنة", "خبرة من 2 إلى 4 سنوات"
        Regex("""$AR_EXPERIENCE\s*(?:لا\s*تقل\s*عن|من|لمدة|مدة)?\s*(\d{1,2})\s*(?:(?:-|–|إلى|الى)\s*(\d{1,2})\s*)?$AR_YEARS"""),
        // "3 سنوات خبرة"
        Regex("""(\d{1,2})\s*$AR_YEARS\s*(?:من\s*)?$AR_EXPERIENCE"""),
    )

    /** "سنتين خبرة": two years, in the dual, with no digit at all. */
    private val arabicTwoRe = Regex("""(?:سنتين|سنتان|عامين)\s*(?:من\s*)?$AR_EXPERIENCE|$AR_EXPERIENCE\s*(?:سنتين|سنتان|عامين)""")

    /** The lowest experience asked for anywhere in [text], or null when none is named. */
    fun years(text: String?): Years? {
        if (text.isNullOrBlank()) return null
        val ascii = digits(text)
        val found = mutableListOf<Years>()
        for (re in englishRes + arabicRes) {
            for (match in re.findAll(ascii)) {
                val min = match.groupValues[1].toIntOrNull() ?: continue
                val max = match.groupValues.getOrNull(2)?.toIntOrNull()
                if (min !in 0..30) continue
                found += Years(min, max?.takeIf { it in min..30 })
            }
        }
        if (arabicTwoRe.containsMatchIn(ascii)) found += Years(2, 2)
        return found.minByOrNull { it.min }
    }

    /** Arabic-Indic and Persian digits as ASCII, so one set of patterns reads both. */
    internal fun digits(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            out.append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    '٬' -> ','
                    else -> ch
                },
            )
        }
        return out.toString()
    }

    // ---- Salary ----

    private const val CUR = """(EGP|L\.?E\.?|SAR|SR|AED|USD|US\$|\$|جنيه|ج\.م|ريال|درهم)"""
    private const val AMT = """(\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?)\s*([kK])?"""
    private const val TO = """\s*(?:-|–|to|إلى|الى)\s*"""
    private const val PERIOD = """(?:\s*(?:/|per\s+|a\s+)?\s*(month|mo|year|yr|annum|hour|hr|شهري[اًا]?|سنوي[اًا]?))?"""

    // A Latin currency must stand on its own: "LE" inside "SALE" is no currency.
    private const val EDGE_L = """(?<![A-Za-z])"""
    private const val EDGE_R = """(?![A-Za-z])"""

    /** "EGP 15,000 - 20,000" and "$90k–110k". */
    private val leadingRe = Regex("""$EDGE_L$CUR$EDGE_R\s*$AMT(?:$TO(?:$CUR\s*)?$AMT)?$PERIOD""", RegexOption.IGNORE_CASE)

    /** "15,000 - 20,000 EGP" and "8000 درهم". */
    private val trailingRe = Regex("""$AMT(?:$TO$AMT)?\s*$CUR$EDGE_R$PERIOD""", RegexOption.IGNORE_CASE)

    /** The salary [text] names, compacted ("EGP 15k–20k/mo"), or null. "Competitive" is null. */
    fun salary(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val ascii = digits(text)
        leadingRe.find(ascii)?.let { m ->
            val g = m.groupValues
            // 1 currency, 2-3 amount, 4 second currency, 5-6 second amount, 7 period
            render(g[1], g[2], g[3], g[5], g[6], g[7])?.let { return it }
        }
        trailingRe.find(ascii)?.let { m ->
            val g = m.groupValues
            // 1-2 amount, 3-4 second amount, 5 currency, 6 period
            render(g[5], g[1], g[2], g[3], g[4], g[6])?.let { return it }
        }
        return null
    }

    private fun render(currency: String, amount: String, k: String, amount2: String, k2: String, period: String): String? {
        val code = currencyCode(currency) ?: return null
        val low = value(amount, k) ?: return null
        val high = if (amount2.isNotEmpty()) value(amount2, k2.ifEmpty { k }) else null
        // A bare "$5" or "SR 30" is a price or a code, not a salary.
        if (low < 100) return null
        val range = if (high != null && high > low) "${compact(low)}–${compact(high)}" else compact(low)
        return "$code $range${periodSuffix(period)}"
    }

    private fun value(amount: String, k: String): Double? {
        val number = amount.replace(",", "").toDoubleOrNull() ?: return null
        return if (k.isNotEmpty()) number * 1000 else number
    }

    private fun compact(value: Double): String {
        if (value < 1000) return value.toLong().toString()
        val thousands = value / 1000
        return if (thousands % 1.0 == 0.0) "${thousands.toLong()}k"
        else String.format(Locale.ROOT, "%.1fk", thousands).replace(".0k", "k")
    }

    private fun currencyCode(raw: String): String? {
        val value = raw.lowercase(Locale.ROOT).replace(".", "")
        return when (value) {
            "egp", "le", "جنيه", "جم" -> "EGP"
            "sar", "sr", "ريال" -> "SAR"
            "aed", "درهم" -> "AED"
            "usd", "us$", "$" -> "USD"
            else -> null
        }
    }

    private fun periodSuffix(period: String): String {
        val value = period.lowercase(Locale.ROOT)
        return when {
            value.isEmpty() -> ""
            value.startsWith("mo") || value.startsWith("شهري") -> "/mo"
            value.startsWith("y") || value == "annum" || value.startsWith("سنوي") -> "/yr"
            value.startsWith("h") -> "/hr"
            else -> ""
        }
    }

    /** Fills [Item.yearsMin], [Item.yearsMax] and [Item.salary] from the title and text. */
    fun apply(item: Item) {
        val text = listOfNotNull(item.title, item.excerpt).joinToString("\n")
        years(text)?.let {
            item.yearsMin = it.min
            item.yearsMax = it.max
        }
        if (item.salary == null) item.salary = salary(text)
    }
}
