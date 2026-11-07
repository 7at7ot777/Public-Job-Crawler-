package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.Filters
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Limits
import com.jobcrawler.app.Store

/**
 * AI data work: labelling, annotation, transcription, and rating or
 * evaluating model output, on audio, images, video and text, Arabic
 * especially. Ported from src/sources/aiData.js.
 *
 * jobs.workable.com searched with this source's own labelling keywords, not
 * the stack keywords from Settings, in Egypt and with no location at all
 * ("Anywhere"), where most of this work is listed. A borderless result is
 * kept only when it is remote, in Egypt, or names an Egyptian place.
 *
 * Every three hours: freelance projects stay open for weeks, and sixteen
 * searches every run would eat the other sources' time.
 */
class AiData(
    override val client: GuestClient = GuestClient("AI data", headers = mapOf("Accept" to "application/json"), delayMs = 1_000),
) : Source {
    override val key = KEY

    private val cadence = Cadence(KEY, Limits.SLOW_SOURCE_EVERY_MS)

    override fun isDue(store: Store) = cadence.isDue(store)

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        cadence.started(store)
        // Keyed by company + title, not by Workable id: see collect().
        val seen = LinkedHashMap<String, Item>()

        try {
            for (location in LOCATIONS) for (keyword in KEYWORDS) {
                val body = client.get(
                    SEARCH_URL,
                    mapOf(
                        "query" to keyword,
                        // Null is left out of the query, which makes the search borderless.
                        "location" to location.takeUnless { it == ANYWHERE },
                        "day_range" to MAX_AGE_DAYS,
                    ),
                )
                val (items, _) = Workable.parse(body, keyword, location)
                for (job in items) {
                    if (Dates.isOlderThan(job.postedAt, MAX_AGE_DAYS)) continue
                    keep(job)?.let { collect(seen, it) }
                }
            }
        } catch (error: BlockedException) {
            error.partialItems = seen.values.toList()
            throw error
        }

        return seen.values.toList()
    }

    companion object {
        const val KEY = "ai_data"
        private const val SEARCH_URL = "https://jobs.workable.com/api/v1/jobs"
        private const val MAX_AGE_DAYS = 7
        private const val ANYWHERE = "Anywhere"

        val LOCATIONS = listOf("Egypt", ANYWHERE)

        val KEYWORDS = listOf(
            "Data Annotation", "Arabic Annotator", "Audio Annotation", "Image Annotation",
            "Image Labeling", "Transcription", "AI Trainer", "Arabic AI Trainer", "Arabic Linguist",
            "AI Rater",
        )

        /*
         * The title decides for this source, in place of the stack titles:
         * a search for "Transcription" also returns medical scribes.
         */
        val TITLE_TERMS = listOf(
            "annotation", "annotator", "annotators", "data labeling", "data labelling", "data labeler",
            "data labeller", "labeling specialist", "labelling specialist", "image labeling",
            "image labelling", "image labeler", "image labeller", "image tagging", "image tagger",
            "photo tagging", "video labeling", "video labelling", "video tagging", "data tagging", "tagger",
            "transcription", "transcriptionist", "transcriber", "ai trainer", "ai tutor", "data trainer",
            "ai rater", "rater", "search quality", "ai evaluator", "evaluator", "ai data", "speech data",
            "audio data", "image data", "data collection", "rlhf", "prompt writer", "linguist",
            "language expert", "language specialist",
            "وسم البيانات", "وسم الصور", "تصنيف الصور", "تصنيف البيانات", "تعليق توضيحي", "تفريغ", "تقييم البيانات", "مدرب ذكاء اصطناعي",
        )

        /*
         * Generalist work only: a title naming a field that needs a degree is
         * dropped. Engineering by discipline, not the word itself: "AI Trainer -
         * Software Engineering" and "Image Annotation Engineer" are wanted.
         * Title only, since a body's "no medical background needed" says
         * nothing about the role. "Medical Image Annotator" goes, "Image
         * Labeler" stays. Arabic terms match inside longer words, so they are
         * ones that cannot collide with an everyday word.
         */
        val TITLE_EXCLUDE = listOf(
            "civil engineering", "civil engineer", "mechanical engineering", "mechanical engineer",
            // Bare: "Mechanical CAD&Simulation Engineer" names no "mechanical engineer".
            "mechanical", "cad",
            "electrical engineering", "electrical engineer", "chemical engineering", "chemical engineer",
            "biomedical", "math", "maths", "mathematics", "physics", "chemistry", "biology", "stem", "phd",
            "medical", "medicine", "doctor", "physician", "nurse", "nursing", "pharmacy", "pharmacist",
            "pharmacology", "pharmaceutical", "healthcare", "clinical", "radiology", "dental", "dentist",
            "legal", "law", "lawyer", "attorney", "finance", "financial", "accounting", "accountant", "economics",
            "هندسة مدنية", "هندسة ميكانيكية", "هندسة كهربائية", "مهندس مدني", "مهندس ميكانيكا", "مهندس كهرباء",
            "طبيب", "طبية", "صيدل", "تمريض", "قانون", "محاسب",
        )

        /* How a posting says the work is in Arabic: the language rule and the "Arabic" label. */
        val ARABIC = listOf("arabic", "msa", "egyptian dialect", "gulf dialect", "levantine", "عربي", "العربية", "اللغة العربية")

        /*
         * Arabic, or Arabic with English: an ad naming one of these is dropped
         * unless it names Arabic too. One naming no language is a general ad and
         * is kept. English is in neither list on purpose; "polish" is left out
         * as far more often a verb.
         */
        val OTHER_LANGUAGES = listOf(
            "french", "german", "spanish", "italian", "portuguese", "dutch", "russian", "turkish", "persian",
            "farsi", "hebrew", "hindi", "urdu", "bengali", "punjabi", "tamil", "telugu", "japanese", "korean",
            "chinese", "mandarin", "cantonese", "vietnamese", "thai", "indonesian", "malay", "tagalog", "filipino",
            "swahili", "greek", "swedish", "norwegian", "danish", "finnish", "czech", "romanian", "hungarian",
            "ukrainian", "الفرنسية", "الألمانية", "الالمانية", "الإسبانية", "الاسبانية",
        )

        /** The rejection reason for a specialist or another-language ad, or null when it is wanted. */
        fun scopeReason(role: String, text: String): String? = when {
            Filters.containsAny(role, TITLE_EXCLUDE) -> "title-excluded"
            Filters.containsAny(text, OTHER_LANGUAGES) && !Filters.containsAny(text, ARABIC) -> "language"
            else -> null
        }

        /* What the work is done on, shown on the message's "Matched" line: "Arabic · Audio". */
        val MODALITIES = listOf(
            "Arabic" to ARABIC,
            "Audio" to listOf(
                "audio", "speech", "voice", "asr", "tts", "utterance", "utterances", "transcription",
                "transcriptionist", "transcriber", "صوت", "صوتي", "صوتية", "تفريغ",
            ),
            "Image" to listOf(
                "image", "images", "photo", "photos", "video", "videos", "bounding box", "bounding boxes",
                "segmentation", "computer vision", "lidar", "صور", "صورة", "فيديو",
            ),
            "Text" to listOf(
                "text", "nlp", "llm", "llms", "prompt", "prompts", "rlhf", "chatbot", "search quality",
                "content evaluation", "نص", "نصوص",
            ),
        )

        /** The modalities a posting names, or null for a general "AI Trainer" ad. */
        fun modalitiesOf(item: Item): String? {
            val text = Filters.fold(listOfNotNull(item.title, item.excerpt).joinToString(" \n "))
            return MODALITIES.filter { (_, terms) -> Filters.containsAny(text, terms) }
                .joinToString(" · ") { it.first }
                .ifEmpty { null }
        }

        /** Whether a candidate in Egypt can take it: placed there, remote, or naming a place there. */
        fun placeWanted(item: Item): Boolean {
            if (item.market != null || item.remote) return true
            val text = Filters.fold(listOfNotNull(item.title, item.location, item.workplace).joinToString(" \n "))
            if (Filters.containsAny(text, listOf("remote", "work from home", "anywhere"))) return true
            return Filters.containsAny(text, Filters.egyptPlaces)
        }

        /*
         * One vendor role posted once per country: Mindrift and CloudFactory
         * open a separate Workable requisition, with its own id and url, for
         * every country they hire in, so an "AI Trainer" ad answers a dozen
         * keyword x location searches as eighteen different jobs. The store's
         * fingerprint folds them into one row, but only after each has been
         * filtered and looked up; here they fold before that, so a run's
         * parsed and matched counts are of roles, not requisitions.
         *
         * The key is the store's own fingerprint (normalized company + title),
         * so this collapses exactly what the store would. No company falls
         * back to the id: titles alone collide.
         */
        fun dedupKey(item: Item): String = Store.fingerprintOf(item.company, item.title) ?: "id:${item.externalId}"

        /**
         * Adds [item] to [seen], or, when the same role is already there,
         * keeps whichever variant the filter is likelier to want (see [rank]),
         * in the first variant's place so the order stays first-seen. The
         * locations are not merged: the filter rejects a location naming any
         * blocked country, and a role open in India and Egypt is still wanted
         * in Egypt.
         */
        fun collect(seen: MutableMap<String, Item>, item: Item) {
            val key = dedupKey(item)
            val held = seen[key]
            if (held == null || rank(item) < rank(held)) seen[key] = item
        }

        /** Lower is better: placed in Egypt, then remote, then anywhere not blocked, then blocked. */
        fun rank(item: Item): Int {
            val placed = Filters.fold(listOfNotNull(item.title, item.location).joinToString(" \n "))
            return when {
                Filters.containsAny(placed, Filters.blockedLocations) -> 3
                item.market != null -> 0
                item.remote -> 1
                else -> 2
            }
        }

        /** A Workable result as this source's item, or null when it is somewhere not wanted. */
        fun keep(job: Item): Item? {
            if (!placeWanted(job)) return null
            return job.copy(source = KEY, keyword = modalitiesOf(job) ?: "AI data")
        }
    }
}
