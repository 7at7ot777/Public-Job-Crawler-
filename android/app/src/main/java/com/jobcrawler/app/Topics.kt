package com.jobcrawler.app

/** A chat, and the forum topic inside it; a null [threadId] posts to the chat itself. */
data class Destination(val chatId: String, val threadId: Long?)

/**
 * Which forum topic each region's jobs are posted to.
 *
 * A group with Topics turned on is one chat with numbered threads, so a job
 * goes to TELEGRAM_CHAT_ID unless `.env` names a topic for its [Region]:
 * TELEGRAM_TOPIC_EGYPT, TELEGRAM_TOPIC_GULF, TELEGRAM_TOPIC_WORLDWIDE,
 * TELEGRAM_TOPIC_FREELANCE.
 * The region is the one [Region.of] already gives every job, so a Saudi job
 * on an Egypt board still lands in the Gulf topic.
 */
object Topics {
    /** Thread 1 is a forum's General topic, which Telegram wants addressed with no thread id. */
    private const val GENERAL = 1L

    private val linkRe = Regex("""^(?:https?://)?(?:t\.me|telegram\.me)/(c/)?([^/?#\s]+)(?:/(\d+))?(?:/\d+)?/?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val pairRe = Regex("""^(-?\d+|@\w+)(?::(\d+))?$""")

    /**
     * A destination as written in `.env`:
     *   https://t.me/c/1234567890/3     private group topic (Copy Link on the topic)
     *   https://t.me/c/1234567890/3/42  a message inside topic 3
     *   https://t.me/mygroup/3          public group, by username
     *   -1001234567890:3                chat id and topic
     *   3                               topic 3 of [defaultChat]
     *   -1001234567890                  a whole chat, no topic
     * Null when it reads as none of those.
     */
    fun parse(value: String?, defaultChat: String): Destination? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null

        linkRe.matchEntire(text)?.let { match ->
            val (isPrivate, id, topic) = match.destructured
            if (isPrivate.isNotEmpty() && !id.all(Char::isDigit)) return null
            // A private link drops the "-100" every supergroup id starts with.
            val chat = if (isPrivate.isNotEmpty()) "-100$id" else "@$id"
            return Destination(chat, topicOf(topic))
        }

        pairRe.matchEntire(text)?.let { match ->
            val (chat, topic) = match.destructured
            // A short bare number is a topic of the default chat: real chat ids are long.
            if (topic.isEmpty() && chat.length <= 6 && chat.all(Char::isDigit)) {
                return if (defaultChat.isBlank()) null else Destination(defaultChat, topicOf(chat))
            }
            return Destination(chat, topicOf(topic))
        }

        return null
    }

    /** [raw] if it reads as a destination, else the whole [defaultChat]. */
    fun resolve(raw: String?, defaultChat: String): Destination =
        parse(raw, defaultChat) ?: Destination(defaultChat, null)

    /** The `.env` value for [region], as built into the APK. */
    fun raw(region: Region): String = when (region) {
        Region.EGYPT -> BuildConfig.TELEGRAM_TOPIC_EGYPT
        Region.GULF -> BuildConfig.TELEGRAM_TOPIC_GULF
        Region.WORLDWIDE -> BuildConfig.TELEGRAM_TOPIC_WORLDWIDE
        Region.FREELANCE -> BuildConfig.TELEGRAM_TOPIC_FREELANCE
    }

    /** Where a job of [region] is posted. An unreadable value is logged and falls back to the channel. */
    fun forRegion(region: Region, defaultChat: String = BuildConfig.TELEGRAM_CHAT_ID): Destination {
        val raw = raw(region)
        val parsed = parse(raw, defaultChat)
        if (parsed == null && raw.isNotBlank()) Log.warn("Unreadable TELEGRAM_TOPIC_${region.name}; posting to the channel")
        return parsed ?: Destination(defaultChat, null)
    }

    /** For Settings: "Egypt → topic 3", one per region that has a topic. */
    fun summary(defaultChat: String = BuildConfig.TELEGRAM_CHAT_ID): String =
        Region.entries.mapNotNull { region ->
            parse(raw(region), defaultChat)?.threadId?.let { "${region.label} → topic $it" }
        }.joinToString(" · ")

    private fun topicOf(value: String): Long? = value.toLongOrNull()?.takeIf { it > GENERAL }
}
