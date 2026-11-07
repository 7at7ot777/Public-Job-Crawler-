package com.jobcrawler.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * [permanent] means retrying cannot help — a rejected recipient. A refused
 * login is [auth]: nothing else can be sent until the password is fixed.
 */
class MailException(message: String, val permanent: Boolean = false, val auth: Boolean = false) : Exception(message)

/** One message, as MIME text: a plain-text body and an optional PDF. */
object Mime {
    class Attachment(val name: String, val bytes: ByteArray, val type: String = "application/pdf")

    fun build(
        from: String,
        fromName: String,
        to: String,
        subject: String,
        body: String,
        attachment: Attachment?,
        boundary: String = "=_" + UUID.randomUUID().toString().replace("-", ""),
        date: ZonedDateTime = ZonedDateTime.now(),
    ): String {
        val lines = mutableListOf(
            "From: ${if (fromName.isBlank()) "" else "${word(fromName)} "}<$from>",
            "To: <$to>",
            "Subject: ${word(subject)}",
            "Date: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(date)}",
            "Message-ID: <${UUID.randomUUID()}@${from.substringAfter('@')}>",
            "MIME-Version: 1.0",
        )

        val text = listOf(
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: base64",
            "",
            base64(body.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray()),
        )

        if (attachment == null) {
            lines += text
        } else {
            lines += "Content-Type: multipart/mixed; boundary=\"$boundary\""
            lines += ""
            lines += "--$boundary"
            lines += text
            lines += "--$boundary"
            lines += "Content-Type: ${attachment.type}; name=\"${attachment.name}\""
            lines += "Content-Disposition: attachment; filename=\"${attachment.name}\""
            lines += "Content-Transfer-Encoding: base64"
            lines += ""
            lines += base64(attachment.bytes)
            lines += "--$boundary--"
        }

        return lines.joinToString("\r\n") + "\r\n"
    }

    /** A header value as an RFC 2047 word when it is not plain ASCII. */
    fun word(value: String): String =
        if (value.all { it.code in 32..126 }) value
        else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.toByteArray()) + "?="

    private fun base64(bytes: ByteArray) = Base64.getMimeEncoder().encodeToString(bytes)

    /** SMTP ends a message at a line holding one dot, so a body line starting with one gets another. */
    fun dotStuff(message: String): String =
        message.split("\r\n").joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
}

/**
 * Just enough SMTP to send through Gmail: implicit TLS on 465, AUTH PLAIN
 * with an app password, one recipient per message.
 */
class Smtp(
    private val user: String,
    private val password: String,
    private val host: String = "smtp.gmail.com",
    private val port: Int = 465,
    private val timeoutMs: Int = 30_000,
) {
    fun send(to: String, message: String) {
        val socket = SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket
        socket.use {
            it.soTimeout = timeoutMs
            it.startHandshake()
            // A raw TLS socket checks the chain but not the name on it.
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, it.session)) {
                throw MailException("$host presented a certificate for another host")
            }

            val reader = BufferedReader(InputStreamReader(it.inputStream, Charsets.US_ASCII))
            val out = it.outputStream

            expect(reader, 220)
            command(out, reader, "EHLO jobcrawler.local", 250)

            val credentials = Base64.getEncoder().encodeToString("\u0000$user\u0000$password".toByteArray())
            val (authCode, authText) = exchange(out, reader, "AUTH PLAIN $credentials")
            if (authCode != 235) throw MailException("Gmail refused the login ($authCode $authText)", auth = true)

            command(out, reader, "MAIL FROM:<$user>", 250)
            val (rcptCode, rcptText) = exchange(out, reader, "RCPT TO:<$to>")
            if (rcptCode !in 250..251) throw MailException("Gmail refused $to ($rcptCode $rcptText)", permanent = rcptCode in 500..599)
            command(out, reader, "DATA", 354)

            out.write((Mime.dotStuff(message) + "\r\n.\r\n").toByteArray(Charsets.US_ASCII))
            out.flush()
            expect(reader, 250)

            runCatching { exchange(out, reader, "QUIT") }
        }
    }

    private fun command(out: OutputStream, reader: BufferedReader, line: String, code: Int) {
        val (got, text) = exchange(out, reader, line)
        if (got != code) throw MailException("SMTP ${line.substringBefore(' ')} answered $got $text", permanent = got in 500..599)
    }

    private fun exchange(out: OutputStream, reader: BufferedReader, line: String): Pair<Int, String> {
        out.write("$line\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        return reply(reader)
    }

    private fun expect(reader: BufferedReader, code: Int) {
        val (got, text) = reply(reader)
        if (got != code) throw MailException("SMTP expected $code, got $got $text")
    }

    /** A reply, which may run over several "250-" lines before the last "250 ". */
    private fun reply(reader: BufferedReader): Pair<Int, String> {
        while (true) {
            val line = reader.readLine() ?: throw MailException("SMTP connection closed")
            if (line.length < 4 || line[3] != '-') return (line.take(3).toIntOrNull() ?: 0) to line.drop(4)
        }
    }
}
