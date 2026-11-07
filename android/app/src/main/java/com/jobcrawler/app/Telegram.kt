package com.jobcrawler.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TelegramRateLimit(message: String, val retryAfterSeconds: Long) : Exception(message)
class TelegramPermanent(message: String) : Exception(message)

/** Minimal Bot API client: one chat, or a forum topic inside any chat ([Destination]). */
class Telegram(private val botToken: String, private val chatId: String) {
    private val http = GuestClient.shared.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    /**
     * Returns on a confirmed ok:true.
     * @throws TelegramRateLimit retry after the given seconds
     * @throws TelegramPermanent bad markup, unknown chat, bot removed
     * @throws Exception anything transient; safe to retry later
     */
    fun send(html: String, to: Destination? = null) {
        val payload = JSONObject()
            .put("chat_id", to?.chatId?.ifBlank { null } ?: chatId)
            .put("text", truncate(html))
            .put("parse_mode", "HTML")
            .put("disable_web_page_preview", true)
        to?.threadId?.let { payload.put("message_thread_id", it) }

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/sendMessage")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val body = runCatching { JSONObject(response.body?.string() ?: "{}") }.getOrDefault(JSONObject())
            val description = body.optString("description", response.message)

            when {
                response.code == 429 -> throw TelegramRateLimit(
                    description,
                    body.optJSONObject("parameters")?.optLong("retry_after", 30) ?: 30,
                )
                response.code == 400 || response.code == 401 || response.code == 404 ->
                    throw TelegramPermanent("HTTP ${response.code}: $description")
                !response.isSuccessful || !body.optBoolean("ok") ->
                    throw Exception("Telegram sendMessage failed (HTTP ${response.code}): $description")
            }
        }
    }

    /**
     * A text file to the chat: the log is longer than a message's 4096
     * characters, and a file keeps it whole and searchable.
     */
    fun sendDocument(fileName: String, bytes: ByteArray, caption: String) {
        http.newCall(documentRequest(botToken, chatId, fileName, bytes, caption)).execute().use { response ->
            val body = runCatching { JSONObject(response.body?.string() ?: "{}") }.getOrDefault(JSONObject())
            val description = body.optString("description", response.message)
            when {
                response.code == 429 -> throw TelegramRateLimit(
                    description,
                    body.optJSONObject("parameters")?.optLong("retry_after", 30) ?: 30,
                )
                response.code == 400 || response.code == 401 || response.code == 404 ->
                    throw TelegramPermanent("HTTP ${response.code}: $description")
                !response.isSuccessful || !body.optBoolean("ok") ->
                    throw Exception("Telegram sendDocument failed (HTTP ${response.code}): $description")
            }
        }
    }

    companion object {
        /** The sendDocument call as a request, separate from sending it so a test can read it. */
        fun documentRequest(botToken: String, chatId: String, fileName: String, bytes: ByteArray, caption: String): Request {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption.take(1024))
                .addFormDataPart("document", fileName, bytes.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                .build()
            return Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendDocument")
                .post(body)
                .build()
        }

        /** The chat logs go to: TELEGRAM_LOG_CHAT_ID when set, else the jobs chat. */
        fun logChat(): String = BuildConfig.TELEGRAM_LOG_CHAT_ID.ifBlank { BuildConfig.TELEGRAM_CHAT_ID }
    }

    /** Cut before any trailing partial tag or entity, so the HTML stays valid. */
    private fun truncate(html: String): String {
        if (html.length <= 4096) return html
        var cut = html.take(4095)
        if (cut.lastIndexOf('<') > cut.lastIndexOf('>')) cut = cut.take(cut.lastIndexOf('<'))
        if (cut.lastIndexOf('&') > cut.lastIndexOf(';')) cut = cut.take(cut.lastIndexOf('&'))
        return "$cut…"
    }
}
