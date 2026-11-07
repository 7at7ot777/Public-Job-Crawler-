package com.jobcrawler.app

import android.provider.Settings as AndroidSettings
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.jobcrawler.app.sources.LinkedInJobs

/* Telegram, Gmail, the battery and updates: whether each is ready, and a way to check it. */
internal fun SettingsActivity.showConnections() {
    val settings = Settings.load(this)
    val apply = ApplySettings.load(this)
    val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    val updates = Updater.status(this)

    connections.removeAllViews()
    val rows = listOf(
        SettingsActivity.Connection(
            "Telegram channel",
            if (settings.telegramReady) "Connected · every match is posted" + Topics.summary().let { if (it.isEmpty()) "" else " · $it" } else "Not set up: .env has no bot token or chat id",
            settings.telegramReady, "Test",
        ) { testTelegram(Settings.load(this)) },
        SettingsActivity.Connection(
            "Gmail for CV emails",
            if (apply.mailReady) "Sends from ${apply.gmail}" else "Not set up: enter a Gmail address and app password under Applying",
            apply.mailReady, "Test",
        ) { testEmail(saveApply()) },
        SettingsActivity.Connection(
            "Gmail for Saudi & UAE",
            when {
                !apply.gulfAutoEmail && !apply.abroadAutoEmail -> "Off · those jobs wait on the review screen"
                apply.gulfSender.mailReady -> "Emails on its own from ${apply.gulfSender.gmail}"
                else -> "Not set up: enter a Gmail address and app password under Applying"
            },
            (!apply.gulfAutoEmail && !apply.abroadAutoEmail) || apply.gulfSender.mailReady, "Test",
        ) { saveApply().let { if (it.gulfGmail.isNotBlank()) testGulfEmail(it) else testEmail(it) } },
        SettingsActivity.Connection(
            "Battery",
            if (exempt) "Unrestricted · runs stay on time" else "Optimised · runs may be late",
            exempt, if (exempt) "Open" else "Allow",
        ) { askBatteryExemption() },
        SettingsActivity.Connection(
            "App updates",
            when {
                updates.updateReady -> "${updates.latest!!.versionName} is on Drive · this is ${BuildConfig.VERSION_NAME}"
                updates.checkedAt != null -> "Up to date · checked ${updates.checkedAt}"
                else -> "Checked after runs, from the Drive folder"
            },
            !updates.updateReady, if (updates.updateReady) "Update" else "Check",
        ) { if (Updater.status(this).updateReady) update() else checkForUpdate() },
    )
    for ((index, row) in rows.withIndex()) {
        if (index > 0) {
            connections.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        connections.addView(connectionRow(row))
    }
}

internal fun SettingsActivity.connectionRow(row: SettingsActivity.Connection) = Ui.row(this).apply {
    minimumHeight = dp(64)
    val badge = Ui.icon(this@connectionRow, if (row.ok) R.drawable.ic_check else R.drawable.ic_warning,
        if (row.ok) R.color.good else R.color.warn, 24).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(getColor(if (row.ok) R.color.goodSoft else R.color.warnSoft))
        }
        setPadding(dp(5), dp(5), dp(5), dp(5))
    }
    addView(badge)
    addView(Ui.column(this@connectionRow).apply {
        addView(Ui.body(this@connectionRow, row.name, 16f))
        addView(Ui.muted(this@connectionRow, row.note))
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    addView(Ui.filterChip(this@connectionRow, row.action) { row.run() }.apply {
        background = null
        setTextColor(getColor(R.color.softText))
    })
}

/** The real email, sent to your own address, so you see what employers will. */
internal fun SettingsActivity.testEmail(apply: ApplySettings) {
    if (!apply.mailReady) return toast("Enter your Gmail address and app password under Applying first")
    val cv = Cv.read(this) ?: return toast("Choose your CV first")
    val sample = Item(
        source = "wuzzuf", externalId = "sample", title = "Backend Developer",
        url = "https://example.com", company = "Sample Company",
    )
    toast("Sending a test email to ${apply.gmail}…")
    background({
        val message = Mime.build(
            apply.gmail, apply.name, apply.gmail,
            CoverNote.subject(sample), CoverNote.text(sample, apply, withCv = true), cv,
        )
        Smtp(apply.gmail, apply.appPassword).send(apply.gmail, message)
    }) { toast("Sent to ${apply.gmail}: check the inbox") }
}

/**
 * The Saudi/UAE account on its own: signs in with the Gmail and app
 * password entered for it and sends that account the email a Saudi job
 * would get, CV attached. Never falls back to the main account — that
 * would pass while the account being tested is wrong.
 */
internal fun SettingsActivity.testGulfEmail(apply: ApplySettings) {
    if (apply.gulfGmail.isBlank() || apply.gulfAppPassword.isBlank()) {
        return toast("Enter the Saudi & UAE Gmail and its app password first. Blank, those emails go from your main Gmail")
    }
    val sender = apply.gulfSender
    val cv = Cv.read(this) ?: return toast("Choose your CV first")
    val sample = Item(
        source = LinkedInJobs.GULF_KEY, externalId = "sample", title = "Backend Developer",
        url = "https://example.com", company = "Sample Company", location = "Riyadh, Saudi Arabia",
    )
    toast("Sending a test from ${sender.gmail}…")
    background({
        val message = Mime.build(
            sender.gmail, sender.name, sender.gmail,
            CoverNote.subject(sample), CoverNote.text(sample, sender, withCv = true), cv,
        )
        Smtp(sender.gmail, sender.appPassword).send(sender.gmail, message)
    }) { toast("Sent from ${sender.gmail} to itself: check that inbox") }
}

internal fun SettingsActivity.testTelegram(settings: Settings) {
    if (!settings.telegramReady) return toast("This build has no Telegram bot: .env had no token or chat id")
    background({ Telegram(settings.botToken, settings.chatId).send("✅ Job Crawler is connected.") }) {
        toast("Sent: check the channel")
    }
}

/**
 * Doze and vendor battery savers delay or skip background work. Being
 * exempt is what keeps a 30-minute schedule close to 30 minutes.
 */
@SuppressLint("BatteryLife")

internal fun SettingsActivity.askBatteryExemption() {
    val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    runCatching { startActivity(intent) }
        .onFailure { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

internal fun SettingsActivity.checkForUpdate() {
    toast("Checking Drive…")
    background({ Updater.check(this) }) { status ->
        showConnections()
        if (status.updateReady) update() else toast("Up to date: ${BuildConfig.VERSION_NAME} is the newest")
    }
}

/**
 * Downloads the newest build and opens the system's install screen. The
 * first time, Android wants this app allowed to install apps; that
 * screen opens instead, and Update is tapped again after.
 */
internal fun SettingsActivity.update() {
    if (!Updater.canInstall(this)) {
        toast("Allow Job Crawler to install apps, then come back and tap Update")
        startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        return
    }
    Updater.cancelNotification(this)
    toast("Downloading the update…")
    background({
        val status = Updater.status(this).takeIf { it.updateReady } ?: Updater.check(this)
        val release = status.latest?.takeIf { status.updateReady }
        if (release != null) Updater.install(this, release)
        release
    }) { release ->
        toast(if (release == null) "Up to date: ${BuildConfig.VERSION_NAME} is the newest" else "Installing ${release.versionName}…")
    }
}
