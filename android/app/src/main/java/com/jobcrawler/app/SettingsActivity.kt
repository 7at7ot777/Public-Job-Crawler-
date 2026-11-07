package com.jobcrawler.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.jobcrawler.app.sources.HnHiring
import com.jobcrawler.app.sources.JobApis
import com.jobcrawler.app.sources.Khamsat
import com.jobcrawler.app.sources.Mostaql
import com.jobcrawler.app.sources.RemoteFeeds
import com.jobcrawler.app.sources.SponsorBoards
import com.jobcrawler.app.sources.AiData
import com.jobcrawler.app.sources.Ats
import com.jobcrawler.app.sources.CareerSites
import com.jobcrawler.app.sources.DrJob
import com.jobcrawler.app.sources.Glassdoor
import com.jobcrawler.app.sources.Jobgether
import com.jobcrawler.app.sources.Jobright
import com.jobcrawler.app.sources.LinkedInJobs
import com.jobcrawler.app.sources.LinkedInPosts
import com.jobcrawler.app.sources.LinkedInWorldwide
import com.jobcrawler.app.sources.Recruitera
import com.jobcrawler.app.sources.RelocateMe
import com.jobcrawler.app.sources.Tanqeeb
import com.jobcrawler.app.sources.Workable
import com.jobcrawler.app.sources.Wuzzuf

/**
 * Everything that is set once and rarely changed: the schedule, what to
 * search, the applying details, the backup and the connections. Saved as it
 * changes and again on leaving, so there is no Save button to forget.
 */
class SettingsActivity : Screen() {

    internal lateinit var scheduleOn: Switch
    internal lateinit var interval: Spinner
    internal lateinit var keywordChips: FlowLayout
    internal lateinit var sourceSwitches: Map<String, Switch>
    internal lateinit var applyNotify: Switch
    internal lateinit var applyName: EditText
    internal lateinit var applyPhone: EditText
    internal lateinit var applyPitch: EditText
    internal lateinit var applyNever: EditText
    internal lateinit var applyGmail: EditText
    internal lateinit var applyAppPassword: EditText
    internal lateinit var applyGulfAuto: Switch
    internal lateinit var boardsGulf: Switch
    internal lateinit var applyAbroadAuto: Switch
    internal lateinit var applyGulfGmail: EditText
    internal lateinit var applyGulfAppPassword: EditText
    internal lateinit var applyCv: TextView
    internal lateinit var chooseCv: Button
    internal lateinit var backupDot: View
    internal lateinit var backupStatus: TextView
    internal lateinit var connections: LinearLayout
    internal lateinit var careerSiteList: LinearLayout
    internal lateinit var tuning: LinearLayout

    internal var keywords: List<String> = emptyList()
    // Set while the screen fills its own controls, so their listeners stay quiet.
    internal var loading = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Log.init(this)
        title = "Settings"
        showBack()

        scheduleOn = findViewById(R.id.scheduleOn)
        interval = findViewById(R.id.interval)
        keywordChips = findViewById(R.id.keywordChips)
        applyNotify = findViewById(R.id.applyNotify)
        applyName = findViewById(R.id.applyName)
        applyPhone = findViewById(R.id.applyPhone)
        applyPitch = findViewById(R.id.applyPitch)
        applyNever = findViewById(R.id.applyNever)
        applyGmail = findViewById(R.id.applyGmail)
        applyAppPassword = findViewById(R.id.applyAppPassword)
        applyGulfAuto = findViewById(R.id.applyGulfAuto)
        boardsGulf = findViewById(R.id.boardsGulf)
        applyAbroadAuto = findViewById(R.id.applyAbroadAuto)
        applyGulfGmail = findViewById(R.id.applyGulfGmail)
        applyGulfAppPassword = findViewById(R.id.applyGulfAppPassword)
        applyCv = findViewById(R.id.applyCv)
        chooseCv = findViewById(R.id.chooseCv)
        backupDot = findViewById(R.id.backupDot)
        backupStatus = findViewById(R.id.backupStatus)
        connections = findViewById(R.id.connections)
        sourceSwitches = mapOf(
            LinkedInJobs.KEY to findViewById(R.id.srcLinkedin),
            Wuzzuf.KEY to findViewById(R.id.srcWuzzuf),
            Tanqeeb.KEY to findViewById(R.id.srcTanqeeb),
            DrJob.KEY to findViewById(R.id.srcDrJob),
            Workable.KEY to findViewById(R.id.srcWorkable),
            CareerSites.KEY to findViewById(R.id.srcCareers),
            Recruitera.KEY to findViewById(R.id.srcRecruitera),
            Ats.KEY to findViewById(R.id.srcAts),
            AiData.KEY to findViewById(R.id.srcAiData),
            // Worldwide remote sources (back on).
            Jobright.KEY to findViewById(R.id.srcJobright),
            RelocateMe.KEY to findViewById(R.id.srcRelocate),
            RemoteFeeds.KEY to findViewById(R.id.srcRemoteFeeds),
            Jobgether.KEY to findViewById(R.id.srcJobgether),
            HnHiring.KEY to findViewById(R.id.srcHn),
            SponsorBoards.KEY to findViewById(R.id.srcSponsors),
            JobApis.KEY to findViewById(R.id.srcJobApis),
            // Arabic freelance gigs, filed under 💼 Freelance.
            Mostaql.KEY to findViewById(R.id.srcMostaql),
            Khamsat.KEY to findViewById(R.id.srcKhamsat),
            // LinkedIn worldwide and LinkedIn Saudi & UAE: back on.
            LinkedInWorldwide.KEY to findViewById(R.id.srcLinkedinWorldwide),
            LinkedInJobs.GULF_KEY to findViewById(R.id.srcGulfJobs),
            // LinkedIn posts and the Serper sources (paid, under the Serper daily cap).
            LinkedInPosts.KEY to findViewById(R.id.srcPosts),
            Glassdoor.KEY to findViewById(R.id.srcGlassdoor),
            LinkedInPosts.GULF_KEY to findViewById(R.id.srcGulfPosts),
        )
        groupSourcesByRegion()

        val settings = Settings.load(this)
        val apply = ApplySettings.load(this)

        scheduleOn.text = label("Look for jobs", "Pauses when offline, picks up once back online")
        applyNotify.text = label("Notify me of each new job", "With an Apply button that copies the cover note")
        applyGulfAuto.text = label(
            "Email my CV to Saudi & UAE jobs on my behalf",
            "No review and no limit: every new Saudi/UAE job that gives an address",
        )
        // Worldwide sources are back on, but the abroad CV auto-email stays OFF and hidden: those jobs are
        // posted to Telegram, and auto-emailing the CV abroad is a separate opt-in not requested here.
        applyAbroadAuto.visibility = View.GONE
        // applyAbroadAuto.text = label(
        //     "Email my CV to jobs abroad on my behalf",
        //     "From the Saudi & UAE Gmail: every new jobright, Relocate.me or other worldwide job that gives an address",
        // )
        for ((key, switch) in sourceSwitches) {
            val (name, note) = SOURCE_LABELS.getValue(key)
            switch.text = label(name, scheduleNote(key, note, settings))
            switch.isChecked = key in settings.sources
            // Each source has its own schedule: switching one on or off starts or cancels it.
            switch.setOnCheckedChangeListener { _, _ -> if (!loading) reschedule(save()) }
            switch.setOnLongClickListener {
                pickSourceInterval(key, name)
                true
            }
        }
        // After the loop: it takes over the Remote job feeds switch's listener.
        buildFeedSwitches()
        boardsGulf.text = label(
            "Saudi & UAE on job boards and ATS",
            "Workable, company boards, Recruitera, Wuzzuf, Tanqeeb, Dr.Job and Glassdoor (2 more paid searches) also bring Saudi/UAE jobs, posted like Egypt's",
        )
        boardsGulf.isChecked = settings.boardsGulf
        boardsGulf.setOnCheckedChangeListener { _, _ -> if (!loading) save() }

        interval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, INTERVALS.map { it.second })
        interval.setSelection(INTERVALS.indexOfFirst { it.first == settings.intervalMinutes }.coerceAtLeast(0))
        interval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (loading) return
                val saved = save()
                // A new interval takes effect at once while the schedule is on.
                if (scheduleOn.isChecked) Scheduler.start(this@SettingsActivity, saved.intervalMinutes)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        scheduleOn.setOnCheckedChangeListener { _, on ->
            if (loading) return@setOnCheckedChangeListener
            val saved = save()
            if (on) {
                Scheduler.start(this, saved.intervalMinutes)
                toast("On: every ${INTERVALS.first { it.first == saved.intervalMinutes }.second}")
            } else {
                Scheduler.stop(this)
                toast("Off: no runs until it is switched back on")
            }
        }
        findViewById<Button>(R.id.runNow).setOnClickListener {
            save()
            Scheduler.runNow(this)
            toast("Run queued")
        }

        keywords = settings.keywords
        showKeywords()
        findViewById<Button>(R.id.editKeywords).setOnClickListener { editKeywords() }
        Criteria.load(this)
        showLists()

        tuning = findViewById(R.id.tuning)
        showTuning()

        careerSiteList = findViewById(R.id.careerSiteList)
        showCareerSites()
        findViewById<Button>(R.id.addCareerSite).setOnClickListener { addCareerSite() }

        applyNotify.isChecked = apply.notify
        applyNotify.setOnCheckedChangeListener { _, _ -> if (!loading) saveApply() }
        applyName.setText(apply.name)
        applyPhone.setText(apply.phone)
        applyPitch.setText(apply.pitch)
        applyNever.setText(apply.neverEmail.joinToString(", "))
        applyGmail.setText(apply.gmail)
        applyAppPassword.setText(apply.appPassword)
        applyGulfAuto.isChecked = apply.gulfAutoEmail
        applyGulfAuto.setOnCheckedChangeListener { _, _ -> if (!loading) saveApply() }
        // OFF (2026-11-05): hidden, always off.
        applyAbroadAuto.isChecked = false
        // applyAbroadAuto.isChecked = apply.abroadAutoEmail
        // applyAbroadAuto.setOnCheckedChangeListener { _, _ -> if (!loading) saveApply() }
        applyGulfGmail.setText(apply.gulfGmail)
        applyGulfAppPassword.setText(apply.gulfAppPassword)
        applyName.hint = "Your name"
        applyPhone.hint = "Your phone"
        showCv()
        chooseCv.setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
            @Suppress("DEPRECATION")
            startActivityForResult(pick, PICK_CV)
        }
        findViewById<Button>(R.id.testEmail).setOnClickListener { testEmail(saveApply()) }
        findViewById<Button>(R.id.testGulfEmail).setOnClickListener { testGulfEmail(saveApply()) }

        findViewById<Button>(R.id.chooseBackup).setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_BACKUP_FOLDER)
        }
        findViewById<Button>(R.id.backupNow).setOnClickListener {
            if (Backup.folder(this) == null) return@setOnClickListener toast("Choose a backup folder first")
            background({ Store.shared(this).let { Backup.save(this, it) } }) {
                toast(it ?: "Backed up")
                showBackup()
            }
        }
        findViewById<Button>(R.id.restoreBackup).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            @Suppress("DEPRECATION")
            startActivityForResult(pick, PICK_BACKUP_FILE)
        }

        findViewById<TextView>(R.id.version).text = "Job Crawler ${BuildConfig.VERSION_NAME}"

        // The "Update ready" notification opens here to install it.
        if (intent.getBooleanExtra(EXTRA_UPDATE, false)) update()

        // The schedule's state comes from WorkManager, which blocks: read it off the main thread.
        background({ Scheduler.isScheduled(this) }) { scheduled ->
            scheduleOn.isChecked = scheduled
            loading = false
        }
    }

    override fun onResume() {
        super.onResume()
        showBackup()
        showConnections()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    /** A switch's text: the title, and under it a smaller muted caption. */
    internal fun label(title: String, caption: String): CharSequence = SpannableStringBuilder(title).apply {
        append("\n")
        val start = length
        append(caption)
        setSpan(AbsoluteSizeSpan(13, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(getColor(R.color.textMuted)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    internal fun save(): Settings {
        val settings = Settings(
            keywords = keywords.ifEmpty { Defaults.keywords },
            sources = sourceSwitches.filterValues { it.isChecked }.keys,
            intervalMinutes = INTERVALS.getOrNull(interval.selectedItemPosition)?.first ?: Defaults.INTERVAL_MINUTES,
            boardsGulf = boardsGulf.isChecked,
        )
        Settings.save(this, settings)
        saveApply()
        return settings
    }

    internal fun saveApply(): ApplySettings {
        val apply = ApplySettings(
            notify = applyNotify.isChecked,
            name = applyName.text.toString().trim(),
            phone = applyPhone.text.toString().trim(),
            pitch = applyPitch.text.toString().trim().ifEmpty { ApplySettings.DEFAULT_PITCH },
            neverEmail = Settings.splitList(applyNever.text.toString()) ?: emptyList(),
            gmail = applyGmail.text.toString().trim(),
            // Google shows an app password in groups of four; the spaces are not part of it.
            appPassword = applyAppPassword.text.toString().replace(" ", ""),
            gulfAutoEmail = applyGulfAuto.isChecked,
            // OFF (2026-11-05): no jobs abroad, so never emailed on its own.
            abroadAutoEmail = false,
            gulfGmail = applyGulfGmail.text.toString().trim(),
            gulfAppPassword = applyGulfAppPassword.text.toString().replace(" ", ""),
        )
        ApplySettings.save(this, apply)

        if (apply.notify && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        return apply
    }

    internal fun showCv() {
        val name = Cv.name(this)
        applyCv.text = name ?: "No CV chosen yet"
        chooseCv.text = if (name == null) "Choose" else "Replace"
    }

    internal fun showBackup() {
        val on = Backup.folder(this) != null
        val last = Backup.lastSaved(this)
        backupDot.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(if (on) R.color.good else R.color.warn))
        backupStatus.text = if (on) {
            "On. The database is copied to your folder after every run" + (last?.let { "; last copy $it." } ?: ".")
        } else {
            "Off. Choose a folder once, and the app copies its database there after every run. " +
                "After a reinstall, choosing the same folder brings everything back."
        }
    }

    internal class Connection(val name: String, val note: String, val ok: Boolean, val action: String, val run: () -> Unit)

    @Deprecated("Activity result API needs AndroidX; this app has none")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            PICK_CV -> saveCv(uri)
            PICK_BACKUP_FOLDER -> useBackupFolder(uri)
            PICK_BACKUP_FILE -> background({ Backup.restore(this, uri) }) {
                toast(it ?: "Restored: already-sent jobs will not be sent again")
                showBackup()
            }
        }
    }

    /*
     * The folder is the reinstall path too. An install that has never saved a
     * backup — a fresh one, since an uninstall wipes that record — restores the
     * backup it finds there instead of overwriting it, even if a run already
     * seeded a few rows.
     */
    internal fun useBackupFolder(tree: Uri) {
        background({
            val fresh = Backup.lastSaved(this) == null
            Backup.setFolder(this, tree)
            val previous = if (fresh) Backup.existing(this, tree) else null
            if (previous != null) {
                Backup.restore(this, previous) ?: "Restored the backup found in that folder"
            } else {
                Store.shared(this).let { Backup.save(this, it) } ?: "Backup folder set; backed up now and after every run"
            }
        }) {
            toast(it)
            showBackup()
        }
    }

    /** Runs [work] off the main thread and hands its result to [done] on it. */
    internal fun <T> background(work: () -> T, done: (T) -> Unit) {
        io {
            val result = runCatching(work)
            onUi {
                result.fold(done) { toast("Failed: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
    }

    internal fun saveCv(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val error = runCatching { Cv.save(this, uri, name) }.getOrElse { it.message ?: "Could not save the CV" }
        toast(error ?: "CV saved")
        showCv()
    }

    internal fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        internal const val PICK_CV = 1
        internal const val PICK_BACKUP_FOLDER = 2
        internal const val PICK_BACKUP_FILE = 3

        /** Set by the "Update ready" notification: start the update on opening. */
        const val EXTRA_UPDATE = "update"
        // Entries shown per list before "+N more"; the dialog shows them all.
        internal const val SHOWN = 10

        /** WorkManager will not run periodic work more often than every 15 minutes. */
        val INTERVALS = listOf(
            15L to "15 minutes",
            30L to "30 minutes",
            60L to "1 hour",
            120L to "2 hours",
            180L to "3 hours",
            360L to "6 hours",
            720L to "12 hours",
        )

        /** Each source's name and how often it runs, as the switches and the home screen show them. */
        val SOURCE_LABELS = mapOf(
            LinkedInJobs.KEY to ("LinkedIn jobs" to "Every 15 min · Egypt only · rests after a LinkedIn 429"),
            Wuzzuf.KEY to ("Wuzzuf" to "Every run"),
            Tanqeeb.KEY to ("Tanqeeb" to "Every run · includes Bayt · Saudi & UAE optional"),
            DrJob.KEY to ("Dr.Job" to "Every run · drjobpro.com · Saudi & UAE optional"),
            Workable.KEY to ("Workable" to "Every run · Saudi & UAE every 3 h"),
            CareerSites.KEY to ("Career sites" to "Every run · new jobs only · VerteX and more"),
            Recruitera.KEY to ("Recruitera" to "Every 6 h · Paymob, Fawry and more"),
            Ats.KEY to ("Company boards" to "Twice a day · Lever, Greenhouse, Zoho and more"),
            AiData.KEY to ("AI data labelling" to "Every 3 h · annotation, transcription, AI rater · audio, images, Arabic text · Egypt or remote"),
            Jobright.KEY to ("Jobright (US listings)" to "Hourly · all US locations · remote-from-anywhere or visa jobs that fit your CV"),
            RelocateMe.KEY to ("Relocate.me" to "Every 3 h · Europe and Japan · relocation with visa paid, fits your CV"),
            RemoteFeeds.KEY to ("Remote job feeds" to "Every 3 h · Himalayas, Jobicy, Remotive, We Work Remotely, RemoteOK, Working Nomads, Arbeitnow, Remote Rocketship · open to Egypt"),
            Jobgether.KEY to ("Jobgether" to "Every 3 h · remote jobs open to Egypt, EMEA or anywhere · public API"),
            LinkedInWorldwide.KEY to ("LinkedIn worldwide" to "Hourly · remote jobs open to anyone, or to EMEA/Egypt · its own LinkedIn budget"),
            HnHiring.KEY to ("Hacker News: Who is hiring" to "Daily · the monthly thread · remote or VISA ads"),
            SponsorBoards.KEY to ("Visa-sponsor companies" to "Every 3 h · Monzo, Adyen, Spotify, GitLab and 24 more · Europe and UK"),
            JobApis.KEY to ("Job search APIs" to "Twice a day · Adzuna, Jooble, Careerjet · UK, EU, Australia, Canada · needs free keys in .env"),
            Mostaql.KEY to ("مستقل · Mostaql" to "Every 30 min · programming projects that name your stack · budget shown"),
            Khamsat.KEY to ("خمسات · Khamsat requests" to "Every 30 min · requests whose title names your stack"),
            LinkedInPosts.KEY to ("LinkedIn hiring posts" to "Daily after 07:00 · paid search, capped"),
            Glassdoor.KEY to ("Glassdoor (through Google)" to "Daily after 07:00 · 8 paid searches (10 with Saudi & UAE) · Egypt, visa and remote abroad, Canada, Australia"),
            LinkedInJobs.GULF_KEY to ("LinkedIn Saudi & UAE" to "Every 3 h · Saudi Arabia and the UAE over the last day · posted to Telegram (#Gulf)"),
            LinkedInPosts.GULF_KEY to ("Saudi & UAE hiring posts" to "Daily after 07:00 · paid search · emailed, not posted"),
        )

        /** The caption under each remote feed's switch. */
        val FEED_NOTES = mapOf(
            "himalayas" to "Remote jobs with country and time-zone limits",
            "jobicy" to "Remote developer jobs · small board, less competition",
            "remotive" to "Remote software jobs, many open worldwide",
            "wwr" to "Remote back-end programming jobs",
            "remoteok" to "Large remote board",
            "rocketship" to "Remote jobs that list Egypt or worldwide · links to the employer's own page",
        )

                fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
