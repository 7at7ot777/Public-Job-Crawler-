package com.jobcrawler.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jobcrawler.app.sources.AbroadJobs
import com.jobcrawler.app.sources.HnHiring
import com.jobcrawler.app.sources.JobApis
import com.jobcrawler.app.sources.Jobgether
import com.jobcrawler.app.sources.RemoteFeeds
import com.jobcrawler.app.sources.SponsorBoards
import com.jobcrawler.app.sources.Glassdoor
import com.jobcrawler.app.sources.Jobright
import com.jobcrawler.app.sources.LinkedInWorldwide
import com.jobcrawler.app.sources.RelocateMe
import java.io.File
import java.time.Instant

/**
 * What happens to a new job besides the channel message: a notification
 * with an Apply button, and — for jobs that give an address — a nudge to the
 * review screen, where each CV email is sent or ignored by hand.
 *
 * Applying on LinkedIn itself stays a tap by the person: LinkedIn bans
 * automated activity, and the account is worth more than the seconds saved.
 */
class Applier(
    private val context: Context,
    private val store: Store,
    private val profile: ApplySettings = ApplySettings.load(context),
) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    /** A source's new jobs: an Apply notification each. */
    fun handle(rows: List<Row>) {
        if (rows.isNotEmpty() && profile.notify) notify(rows)
    }

    /**
     * An auto-email source's new jobs (Saudi/UAE, abroad): the CV emailed to each that gives an
     * address, then one notification saying where it went.
     */
    fun autoEmail(rows: List<Row>, isStopped: () -> Boolean = { false }, run: RunContext = RunContext()) {
        val sent = Mailer.autoSend(context, store, rows, isStopped, run)
        if (sent.isEmpty() || !profile.notify || !notifications.areNotificationsEnabled()) return
        createChannels()

        val open = PendingIntent.getActivity(
            context,
            AUTO_ID,
            Intent(context, JobsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val lines = sent.map { (row, address) -> "${row.item.title}${row.item.company?.let { " @ $it" } ?: ""} → $address" }
        val notification = Notification.Builder(context, CHANNEL_AUTO)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(Mailer.autoLabel(sent.first().first.item.source).let { kind ->
                if (sent.size == 1) "📨 CV sent to 1 $kind job" else "📨 CV sent to ${sent.size} $kind jobs"
            })
            .setContentText(lines.first())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One per batch, so an earlier run's summary is not overwritten.
        notifications.notify(AUTO_ID - sent.first().first.id.toInt(), notification)
    }

    /** Once a run, after every source: one notification for the jobs waiting on a decision. */
    fun notifyReview() {
        if (!profile.notify || !notifications.areNotificationsEnabled()) return
        val waiting = store.reviewCount(Mailer.queueSince())
        if (waiting == 0) {
            notifications.cancel(REVIEW_ID)
            return
        }
        createChannels()

        val open = PendingIntent.getActivity(
            context,
            REVIEW_ID,
            Intent(context, ReviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_REVIEW)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(if (waiting == 1) "1 job waiting for your CV" else "$waiting jobs waiting for your CV")
            .setContentText("Tap to send or ignore each one")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        notifications.notify(REVIEW_ID, notification)
    }

    private fun notify(rows: List<Row>) {
        if (!notifications.areNotificationsEnabled()) {
            Log.warn("Apply: notifications are off for this app; no Apply buttons")
            return
        }
        createChannels()

        for (row in rows.take(MAX_NOTIFICATIONS)) {
            val item = row.item
            val apply = PendingIntent.getActivity(
                context,
                row.id.toInt(),
                ApplyActivity.intent(context, row.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(
                context,
                -row.id.toInt(),
                Intent(Intent.ACTION_VIEW, Uri.parse(item.url)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            // An address in the description: say so, and open the job's page with Send CV on it.
            val address = Mailer.addressFor(item, profile)
            if (address != null) {
                val review = PendingIntent.getActivity(
                    context,
                    row.id.toInt(),
                    JobActivity.intent(context, row.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val notification = Notification.Builder(context, CHANNEL_EMAIL)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle("📧 Email found: ${item.title}")
                    .setContentText(listOfNotNull(address, item.company).joinToString(" · "))
                    .setStyle(Notification.BigTextStyle().bigText(
                        listOfNotNull(address, item.company, item.location, item.excerpt?.take(300)).joinToString("\n"),
                    ))
                    .setContentIntent(review)
                    .addAction(Notification.Action.Builder(null, "Email CV", review).build())
                    .addAction(Notification.Action.Builder(null, "Apply (copies note)", apply).build())
                    .setAutoCancel(true)
                    .build()

                Log.info("📧 Email found: ${item.title} → $address")
                notifications.notify(row.id.toInt(), notification)
                continue
            }

            val notification = Notification.Builder(context, CHANNEL_JOBS)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle(item.title)
                .setContentText(listOfNotNull(item.company, item.location).joinToString(" · "))
                .setStyle(Notification.BigTextStyle().bigText(
                    listOfNotNull(item.company, item.location, item.excerpt?.take(300)).joinToString("\n"),
                ))
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Apply (copies note)", apply).build())
                .setAutoCancel(true)
                .setGroup(GROUP)
                .build()

            notifications.notify(row.id.toInt(), notification)
        }
    }

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_JOBS, "New jobs", NotificationManager.IMPORTANCE_DEFAULT),
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_REVIEW, "Jobs to email", NotificationManager.IMPORTANCE_DEFAULT),
        )
        // Its own channel, so it can ring louder than a plain new job from the phone's settings.
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_EMAIL, "Email found in a job", NotificationManager.IMPORTANCE_HIGH),
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_AUTO, "CVs sent on their own", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        private const val CHANNEL_JOBS = "jobs"
        private const val CHANNEL_REVIEW = "review"
        private const val CHANNEL_EMAIL = "email-found"
        private const val CHANNEL_AUTO = "auto-email"
        private const val GROUP = "jobs"

        // Row ids are positive and the open-link intents negative ids of them;
        // this one can never clash with a job's notification.
        private const val REVIEW_ID = Int.MIN_VALUE + 1

        // Below every open-link id (-row id), and counted down from there per batch.
        private const val AUTO_ID = Int.MIN_VALUE / 2

        // A run that finds a burst of jobs should not bury the phone.
        private const val MAX_NOTIFICATIONS = 10
    }
}

/**
 * One CV email, sent because the person tapped Send. No hours, no caps: the
 * decision is theirs. Addresses nobody reads, blocked ones and the sender's
 * own are still never written to.
 */
object Mailer {
    sealed class Result {
        data class Sent(val address: String) : Result()
        object NoAddress : Result()
        object NoCv : Result()
        object NoAccount : Result()
        data class Failed(val message: String, val permanent: Boolean, val auth: Boolean = false) : Result()
    }

    // Older than this, a job with an address is likely filled; it leaves the review list.
    const val QUEUE_DAYS = 7L

    fun queueSince(now: Instant = Instant.now()): Instant = now.minusSeconds(QUEUE_DAYS * 86_400)

    /** The address a job would be emailed at: the first a person reads. */
    fun addressFor(item: Item, profile: ApplySettings): String? =
        Recipients.eligible(item, profile) { false }.firstOrNull()

    /** An email as the person edited it before sending: recipient, subject and body. */
    data class Draft(val to: String, val subject: String, val body: String)

    /** The email Send would write, for the person to read and change first. */
    fun draft(item: Item, profile: ApplySettings): Draft? =
        addressFor(item, profile)?.let { Draft(it, CoverNote.subject(item), CoverNote.text(item, profile, withCv = true)) }

    /**
     * Blocking: call off the main thread.
     *
     * @param draft what the person edited; a blank subject or body falls back to the drafted one.
     */
    fun send(context: Context, store: Store, row: Row, draft: Draft? = null): Result {
        // A Saudi/UAE or abroad job sent by hand still goes out from the second account.
        val profile = senderFor(row.item, ApplySettings.load(context))
        if (!profile.mailReady) return Result.NoAccount
        val cv = Cv.read(context) ?: return Result.NoCv
        val item = row.item
        val address = if (draft != null) {
            // Typed by the person: only its shape is checked, not the no-reply rules.
            Recipients.normalise(draft.to) ?: return Result.Failed("\"${draft.to.trim()}\" is not an email address", permanent = false)
        } else {
            addressFor(item, profile) ?: run {
                store.applications.record(item, "email-skipped")
                return Result.NoAddress
            }
        }

        return deliver(
            store, item, profile, address,
            subject = draft?.subject?.trim()?.ifEmpty { null } ?: CoverNote.subject(item),
            body = draft?.body?.trimEnd()?.ifEmpty { null } ?: CoverNote.text(item, profile, withCv = true),
            cv = cv,
        )
    }

    /** One message, from [profile]'s account, and its outcome recorded. */
    private fun deliver(
        store: Store,
        item: Item,
        profile: ApplySettings,
        address: String,
        subject: String,
        body: String,
        cv: Mime.Attachment,
    ): Result {
        val message = Mime.build(
            from = profile.gmail,
            fromName = profile.name,
            to = address,
            subject = subject,
            body = body,
            attachment = cv,
        )

        return try {
            Smtp(profile.gmail, profile.appPassword).send(address, message)
            store.applications.record(item, "email", address)
            Log.info("📨 emailed CV to $address for ${item.title}${item.company?.let { " @ $it" } ?: ""}")
            Result.Sent(address)
        } catch (error: MailException) {
            // Remembered, so the same bad address does not stay in the list.
            if (error.permanent) store.applications.record(item, "email-failed", address)
            Log.warn("Apply: could not email $address: ${error.message}")
            Result.Failed(error.message ?: "refused", error.permanent, error.auth)
        } catch (error: Exception) {
            Log.warn("Apply: could not email $address: ${error.javaClass.simpleName}: ${error.message}")
            Result.Failed("${error.javaClass.simpleName}: ${error.message}", permanent = false)
        }
    }

    /*
     * Saudi and UAE jobs are emailed without asking, and without limits:
     * every new job that gives an address gets the CV, however many a run or
     * a day, and a recruiter who posts five roles hears about each of them.
     * Only the pace is kept — Gmail treats a burst from one account as spam.
     */
    val AUTO_SOURCES: Set<String> by lazy { Pipeline.keysIn(Region.GULF) }

    /* Jobs outside Egypt: every source whose home is worldwide (jobright's US listings, Relocate.me's Europe and Japan ones…). */
    val ABROAD_SOURCES: Set<String> by lazy { Pipeline.keysIn(Region.WORLDWIDE) }

    /**
     * A job outside Egypt and the Gulf: every jobright and Relocate.me job, and
     * a Glassdoor one in a lane abroad — not a Saudi/UAE Glassdoor listing,
     * which is posted like an Egypt one.
     */
    fun isAbroad(item: Item) = item.source in ABROAD_SOURCES ||
        (item.source == Glassdoor.KEY && item.market != null && !Gulf.isGulf(item.market))

    /**
     * The account a job's CV goes out from, sent on its own or by hand:
     * every job outside Egypt (Saudi/UAE and abroad) from the second account
     * when one is set, Egypt's from the main one.
     */
    fun senderFor(item: Item, profile: ApplySettings): ApplySettings =
        if (item.source in AUTO_SOURCES || isAbroad(item)) profile.gulfSender else profile

    /** The account a job's automatic email goes out from, or null when it is not emailed on its own. */
    fun autoSender(item: Item, profile: ApplySettings): ApplySettings? = when {
        item.source in AUTO_SOURCES -> senderFor(item, profile).takeIf { profile.gulfAutoEmail }
        isAbroad(item) -> senderFor(item, profile).takeIf { profile.abroadAutoEmail }
        else -> null
    }

    /** How a notification or log line names a source's jobs. */
    fun autoLabel(source: String) = when (source) {
        Jobright.KEY -> "jobright"
        RelocateMe.KEY -> "Relocate.me"
        AbroadJobs.KEY -> "Europe, Canada & Australia"
        RemoteFeeds.KEY -> "remote job feeds"
        LinkedInWorldwide.KEY -> "LinkedIn worldwide"
        HnHiring.KEY -> "Hacker News"
        JobApis.KEY -> "job search APIs"
        SponsorBoards.KEY -> "visa-sponsor companies"
        Jobgether.KEY -> "Jobgether"
        Glassdoor.KEY -> "Glassdoor"
        else -> "Saudi/UAE"
    }

    private const val AUTO_GAP_MS = 8_000L

    /**
     * Blocking. Emails the CV to each of [rows] that gives an address a person
     * reads; the rest stay on the review screen, as does whatever a stopped
     * run leaves over. Stops at a refused login: every later send would be
     * refused too.
     *
     * @return the rows sent, each with the address it went to.
     */
    fun autoSend(
        context: Context,
        store: Store,
        rows: List<Row>,
        isStopped: () -> Boolean = { false },
        run: RunContext = RunContext(),
    ): List<Pair<Row, String>> {
        if (rows.none { it.item.contactEmails.isNotEmpty() }) return emptyList()
        val profile = ApplySettings.load(context)
        // Only the jobs emailed on their own: a Glassdoor job in Egypt waits on the review screen.
        val withAddress = rows.filter { it.item.contactEmails.isNotEmpty() && autoSender(it.item, profile) != null }
        if (withAddress.isEmpty()) return emptyList()

        val kind = autoLabel(withAddress.first().item.source)
        val sender = autoSender(withAddress.first().item, profile) ?: return emptyList()
        if (!sender.mailReady) {
            Log.warn("Auto-email: no Gmail set up; ${withAddress.size} $kind job(s) wait on the review screen")
            return emptyList()
        }
        val cv = Cv.read(context) ?: run {
            Log.warn("Auto-email: no CV chosen; ${withAddress.size} $kind job(s) wait on the review screen")
            return emptyList()
        }

        val sent = mutableListOf<Pair<Row, String>>()

        for (row in withAddress) {
            if (isStopped() || run.lost) break
            val item = row.item
            // The main account too: neither of your own inboxes is a recruiter's.
            val address = Recipients.eligible(item, sender) { it == profile.gmail.lowercase() }.firstOrNull()
            if (address == null) {
                store.applications.record(item, "email-skipped")
                Log.info("Auto-email: skipped ${item.title}: no address a person reads")
                continue
            }

            if (sent.isNotEmpty()) Thread.sleep(AUTO_GAP_MS)
            val result = deliver(store, item, sender, address, CoverNote.subject(item), CoverNote.text(item, sender, withCv = true), cv)
            when {
                result is Result.Sent -> sent += row to address
                result is Result.Failed && result.auth -> break
            }
        }
        return sent
    }

    /** Out of the list for good. No address is recorded, so none counts as written to. */
    fun ignore(store: Store, row: Row) {
        store.applications.record(row.item, "email-ignored")
    }

    fun describe(result: Result): String = when (result) {
        is Result.Sent -> "CV sent to ${result.address}"
        Result.NoAddress -> "No address on this job that a person reads; removed"
        Result.NoCv -> "Choose your CV first, on the main screen"
        Result.NoAccount -> "No Gmail set up: enter the address and app password in Settings"
        is Result.Failed -> when {
            result.auth -> "Gmail refused the login: ${result.message}. Check the app password in Settings"
            result.permanent -> "Refused for good: ${result.message}; removed"
            else -> "Not sent: ${result.message}. Try again"
        }
    }

    /** Whether the job is settled and leaves the list. */
    fun settles(result: Result) = when (result) {
        is Result.Sent, Result.NoAddress -> true
        is Result.Failed -> result.permanent && !result.auth
        else -> false
    }
}

/** The CV, copied into the app's own storage when chosen, so the original can move. */
object Cv {
    private const val MAX_BYTES = 8 * 1024 * 1024

    private fun file(context: Context) = File(context.filesDir, "cv.pdf")

    fun name(context: Context): String? =
        context.getSharedPreferences("apply", Context.MODE_PRIVATE).getString("cvName", null)
            ?.takeIf { file(context).exists() }

    /** @return an error to show, or null when saved. */
    fun save(context: Context, uri: Uri, displayName: String?): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "Could not read the file"
        if (bytes.size > MAX_BYTES) return "The CV is over 8 MB; Gmail and most inboxes refuse that"
        if (!(bytes.size > 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "%PDF")) return "That file is not a PDF"

        file(context).writeBytes(bytes)
        val name = displayName?.takeIf { it.endsWith(".pdf", ignoreCase = true) } ?: "CV.pdf"
        context.getSharedPreferences("apply", Context.MODE_PRIVATE).edit().putString("cvName", name).apply()
        return null
    }

    fun read(context: Context): Mime.Attachment? {
        val name = name(context) ?: return null
        return Mime.Attachment(name, file(context).readBytes())
    }
}
