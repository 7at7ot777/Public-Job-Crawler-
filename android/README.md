# Job Crawler for Android

The crawler, running on a phone. A phone's home or mobile IP is not blocked
the way the server's datacenter IP is, so Wuzzuf and Tanqeeb work again.

A Kotlin port of the Node app's job sources (LinkedIn guest jobs, Wuzzuf,
Tanqeeb, Workable, Recruitera, the company ATS boards, and LinkedIn hiring
posts through Serper), filters, dedup and Telegram delivery. Every source
has its own WorkManager schedule; Android wakes it, it crawls that one
source, sends, and exits. Nothing stays in memory between runs.

Companies' own careers sites (VerteX Technologies so far) are the **Career
sites** source. Add one on the phone: Settings → Career sites → **Add a
careers site**, paste the company's careers link, and the app finds the
sitemap and the pattern its job pages share, shows a few, and saves it.
A site that needs its own page reader is a `Site(...)` line in `SITES` in
`sources/CareerSites.kt` instead.
Pages are read through their JobPosting JSON-LD, or their heading and main
text; a site's first run records what is open and sends none of it, and
after that only jobs it has not listed before are sent.

The free remote job boards are the **Remote job feeds** source. Settings →
**Remote job feeds** has a switch for each of Himalayas, Jobicy, Remotive,
We Work Remotely, RemoteOK and Remote Rocketship, so one can be turned off without losing the
rest; a switched-off feed shows as "off" in the run log. Working Nomads,
Arbeitnow and Landing.jobs have no switch and run whenever the source is on.

## Get the APK

Every push replaces the **android-latest** release, whose file is the bare
APK, no zip. Open this on the phone and install what it downloads:

https://github.com/7at7ot777/JobCrawlerBot/releases/download/android-latest/JobCrawler.apk

Android asks once to allow installing from that app (Chrome, Files). The
same APK is also under Actions → the run → **Artifacts**, but GitHub always
zips those.

Each push also puts `JobCrawler-1.0.<run>.apk` in the shared Google Drive
folder and moves older ones to Drive's trash, once the `RCLONE_CONF` secret
is set. One-time setup, on a computer signed in to that Google account:

```sh
rclone config            # n → name: gdrive → storage: drive → scope: 1 (full) → defaults → browser login
gh secret set RCLONE_CONF -R 7at7ot777/JobCrawlerBot < "$(rclone config file | tail -1)"
```

The app updates itself from that folder (`Updater.kt`): after a run it looks
for a newer `JobCrawler-1.0.<run>.apk` (at most every 30 minutes) and posts an
"Update ready" notification; tapping it, or **Settings → App updates →
Update**, downloads the APK and opens Android's install screen. The first
time, Android asks to allow Job Crawler to install apps. For this to work the
folder must be shared as **Anyone with the link · Viewer** — the app reads it
without a Google sign-in. Anyone holding the link can then download the APK,
which carries the bot token and Gmail app password, so keep the link private.

To build one without pushing code: Actions → Android APK → **Run workflow**.
Pushing a tag such as `android-v1.0` publishes a release that stays.

## First run on the phone

Nothing to type: the bot token and channel come from the repo's `.env`
(`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`) and are built into the APK.
Change them there and push; the next APK uses the new values.
To post into a group with Topics, set `TELEGRAM_TOPIC_EGYPT`,
`TELEGRAM_TOPIC_GULF` and `TELEGRAM_TOPIC_WORLDWIDE` to a topic link
(`https://t.me/c/1234567890/3`), `chatId:topicId`, or a topic id of
`TELEGRAM_CHAT_ID`: each job goes to its region's topic, and a blank one
posts to `TELEGRAM_CHAT_ID`. The bot has to be a member of that group allowed
to post in topics, or Telegram rejects the message and the job is marked failed. Since the APK
carries the token, keep it as private as the repo.

1. **Send a test message** to check the bot reaches the channel.
2. Pick the sources and how often to fetch, then **Save and start**.
3. **Allow running in background**, and say yes. Without it Android delays
   or skips runs. On Xiaomi, Oppo, Huawei and Samsung also allow
   "Autostart" / set battery to "Unrestricted" in the app's settings.

The first run on an empty database records what is already out there and
sends nothing, the same as the server does, so the channel is not flooded.
Each source on Home has a **▶** button that runs just that source once, now,
whether or not it is due — for a daily or three-hourly source whose turn
passed while the phone was offline. Offline, it waits and runs as soon as
the phone is back online; while another run is going, it runs after it. The
paid hiring-post search asks first, and stays within the daily cap. Pause
stops the schedule, not a run you asked for.

LinkedIn Jobs' **▶** asks how far back to look first: tap **1h**, **6h**,
**10h**, **24h**, **48h** or **1 week**, or type any whole number of hours up
to 168 and tap **Run**. **Auto** keeps the usual window, the time since its
last clean run. The choice is for that one run; scheduled runs are
unchanged. Each search still reads one page, the newest 25 jobs for each
keyword and place, so a long window brings the most recent of them, not
every job in it.

**The screens.** A bar at the bottom switches between **Home**, **Jobs**,
**Emails** and **Log**; Settings is the icon at the top right of Home, and
**Add a post** is Home's floating button. Home opens on a dark status
console: whether it runs, the last run, and three counts that open their
screen (new jobs, emails to review, CVs the Saudi & UAE sources sent today).
Under it the sources are in two lanes: **Egypt**, posted to Telegram, and
**Saudi & UAE**, emailed and never posted, shown in indigo wherever it
appears. A Serper card shows today's paid searches against the daily cap.
**Emails** has two tabs: **To review** (Send or Ignore, as below) and **Sent
on their own** (what the Saudi & UAE sources emailed, or why they did not).
**What's new**, from Home's footer or the update banner, starts with the
update: Update now when a newer build is on Drive, Check again otherwise.
The look is the design canvas's: Manrope and IBM Plex Mono (both SIL Open
Font License, in `font-licenses/`), bundled in `res/font`.

**Open scraping log** shows each run on its own screen: every job found
(`+ new`, `· seen`), each one sent (`→ sent`), and each source's totals.
It follows a run live, and has Refresh, Copy (the whole log to the
clipboard), **Send** (the log as shown — all, or problems only — to Telegram
as a .txt file, to `TELEGRAM_LOG_CHAT_ID` when set in `.env`, else the jobs
chat) and Clear. The abroad sources log how many jobs they had already seen,
how many were too old, how many were judged, and every job left out for a
lower CV fit (`· lower fit 48/100`).

**Force stop.** Settings > Force stop — or a phone maker's cleaner, or on
some phones swiping the app away — cancels every run, and Android lets
nothing of the app run again until it is opened by hand. No app can get
around that. What the app does: each run works in the foreground under a
quiet "Searching <source>…" notice (one per running source, grouped), which cleaners mostly leave alone; and
the next time the app is opened (Android 11 and later) it reads back why its
process ended, logs the force stop, starts the missed run at once, and shows
a banner for a day with a button to the app's settings (Battery:
Unrestricted, and Autostart where the phone has it). A run cut short by the
process being killed is logged with the reason the next time the app starts.

When the schedule goes quiet — no run for three intervals, an hour at least —
the next run's first line says what Android was doing to the app: battery
optimised or not, battery saver, Doze, and the app's standby bucket ("rare"
or "restricted" means Android itself is holding the runs back). A run the
system stops says the same. A run started while a stopped one is still
winding down (as after an update) waits for it instead of being dropped.

**Wuzzuf** is read through its sitemaps, found in `wuzzuf.net/sitemap.xml`
each run: per market (Egypt, then Saudi Arabia) the file changed most
recently. Each file read logs its size, URL count, `lastmod`, how many URLs
name a wanted role and how many of those were already opened, so "0 unseen
pages" can be told apart from a sitemap that stopped changing or a page that
was not a sitemap at all. Without a readable index it reads
`sitemap-job-1.xml` and `sitemap-saudi-job-1.xml`.

**What to search and what reaches you** are editable in Settings, each list
with **Edit** (one entry per line) and **Reset** (back to the app's default):
keywords, places to search, the hiring-post keywords and places, and the
filters — the stack a job must mention, the role its title must name, the
titles too senior, the places that count as Egypt and the places never wanted,
and **Can't apply (residency, nationality, visa)**: phrases that drop a job
the person cannot take from Cairo — "must be based in", "currently in KSA",
"transferable iqama", "Saudis only", "GCC nationals", "للسعوديين فقط", work
authorisation, no sponsorship. A residency phrase followed by an Egyptian
place ("must be based in Cairo") does not count.
A list never edited follows the app's default, so updates still improve it.
Adding post keywords or places adds paid Serper searches (one per pair, daily).

**What the hiring-posts search costs** is under the post lists, in **Serper
searches**: searches today against the daily cap, this month's total, and the
credits left (read once a day from Serper's free account endpoint). The
defaults are 10 keywords × 3 places = 30 searches a day for Egypt, plus
10 × 4 = 40 for Saudi & UAE.

- **Daily cap** (default 100), for Egypt's searches. Past it the search stops,
  sends what it found, and posts one alert to the channel. 0 means no limit.
  The Saudi & UAE searches are counted in the day's total but never capped.
- **Group the post places**: one Egypt search names up to six places as an OR
  group, so 30 searches become 10. Off by default until it has been seen to
  find as many posts. Saudi & UAE are always one search per place, each in
  its own market.
- An Egypt search that found no fresh post five days running is asked every
  three days until it finds one again. Saudi & UAE searches are asked daily.

**Saudi & UAE jobs are emailed, not posted.** The Saudi & UAE jobs and hiring
posts sources pass the same filters as Egypt's, never reach the channel, and
every new one that gives an address a person reads gets your CV on its own:
no review, and no limit a run, a day or per address. Only the pace is kept,
eight seconds between emails, because Gmail treats a burst as spam.

They go from the optional second Gmail under Settings → Applying, or from
the main one when it is blank. **Send a test from the Saudi & UAE Gmail**,
under its fields, signs in with exactly that account and sends it the email
a Saudi job would get, CV attached; it never falls back to the main account,
so it fails if the account being tested is wrong.

**Saudi & UAE on job boards and ATS** (Settings → sources, on by default) is
the other way round: with it on, Workable also searches Saudi Arabia and the
UAE (one page each), Tanqeeb also reads its `saudi.` and `uae.` sites,
Dr.Job also reads its Saudi pages and drjobs.ae,
Wuzzuf reads its Saudi sitemaps, Recruitera keeps SA/AE jobs, the company
boards keep jobs placed in Riyadh, Dubai and the like, and Glassdoor adds two
paid searches (`gl=sa`, `gl=ae`). Those jobs are **posted to the channel like
Egypt's**, marked 🇸🇦 / 🇦🇪, and wait on the review screen; nothing is
emailed on its own. Off, every one of these sources is Egypt only.

**Europe, Canada & Australia** (a source switch, every 3 hours) searches
LinkedIn in the places under *What to search* (European Union, United
Kingdom, Canada, Australia) for each CV keyword twice: remote jobs, and the
keyword with "visa sponsorship". A job is kept only when it is **remote and
open to Egypt** (EMEA, Europe, UTC+2, worldwide…) or **visa-sponsored**
(visa sponsorship, Blue Card, Skilled Worker, LMIA, 482…), **in English**
(no "German C1 required", no description in another language), not only for
residents or citizens there, and at the CV's stack and level (PHP/Laravel,
Node.js/NestJS, Go; at most 4 years asked). Glassdoor also searches Canada
and Australia. Every job abroad — this source, jobright, Relocate.me,
Glassdoor abroad — gets a **CV fit score** out of 100; only those scoring
55 or more go out, **the best 10 a run**, posted with "🎯 CV fit" and the
region, and emailed from the second Gmail when they give an address. The rest
stay on the Jobs screen, marked with their score. The LinkedIn part asks only
"<keyword> visa sponsorship" (4 keywords × the places) and shares LinkedIn's
rest and hourly cap with the Egypt search.

**Remote job feeds** (every 3 hours, free, no key): Himalayas, Jobicy,
Remotive, We Work Remotely, RemoteOK, Working Nomads, Arbeitnow and
Landing.jobs (European tech jobs; a paid relocation counts as the visa lane),
and Remote Rocketship (its Egypt pages for software, backend and full-stack
engineers; each job links to the employer's own ATS page).
A remote job is kept only when the feed's own "who may apply" field takes
someone in Egypt (worldwide, EMEA, Africa, UTC+2…), then by the same rules and
fit score.

**Jobgether** (every 3 hours, free, no key) asks Jobgether's public search API
(made for crawlers; robots.txt allows it) for each keyword's newest jobs open
to Egypt. A job whose title fits has its page opened once, for the ad and the
countries that may apply; one that leaves Egypt out is dropped, the rest are
judged like a remote feed's. At most 15 pages a run.

**Dr.Job** (every run, Egypt; Saudi & UAE with the switch above) reads
drjobpro.com's keyword pages (`/egypt/php-developer-jobs`, newest first), and
opens the page of a card that could pass the filters for the whole ad, at most
10 a run. Part of it is re-listed from other boards; dedup absorbs that.

**Visa-sponsor companies** (every 3 hours, free, no key) reads the public
Greenhouse, Lever and Ashby boards of 28 companies in Europe and the UK known
to sponsor visas or relocate engineers (Monzo, Deliveroo, Wise, Adyen, N26,
HelloFresh, GetYourGuide, Spotify, GitLab…; list in `SponsorBoards.BOARDS`).
Their postings rarely repeat "visa sponsorship", so each is marked as from a
known sponsor; it still has to be in Europe, the UK, Canada or Australia (or
remote and open to Egypt), in English, at the CV's level, and score high
enough.
**Hacker News: Who is hiring** (daily) reads the monthly thread; "VISA" in an
ad's header counts as sponsorship. **Job search APIs** (twice a day) query
Adzuna, Jooble and Careerjet for the UK, the EU, Australia and Canada, each
only once its free key is in `.env` (`ADZUNA_APP_ID` + `ADZUNA_APP_KEY`,
`JOOBLE_API_KEY`, `CAREERJET_API_KEY`); Home says when a key is missing. Careerjet's v4 API only answers IP addresses allowed in its publisher account; a refusal is logged once and skipped for the run.
- Out of credits, a rejected key, the cap reached, or fewer than 300 credits
  left each send one Telegram alert a day. Running out of credits used to look
  like a quiet day: Serper answers HTTP 400, which the app read as "no results".

Stop the server crawler once the phone is running. Both post to the same
channel but keep separate databases, so running both sends duplicates.

## Regions, ranking and muting

Every source and every job is **Egypt**, **Saudi & UAE** or **Worldwide**.
Settings lists the sources under those three headings; the Jobs screen has a
region filter; every Telegram message ends with `#Egypt`, `#Gulf` or
`#Worldwide`, so the one channel can be searched by region.

Each job gets a **fit score**, 0–100: its title and the stack it asks for
against the CV, years of experience in reach, how fresh it is, remote, an
address to write to, and the region (abroad, `AbroadFit`'s score). A run
sends its jobs best first; messages and the Jobs list show `⭐ 82`, and
**Best first** sorts the list by it.

The app also reads **years of experience** (`3-5 years of experience`,
`خبرة 3 سنوات`) and **salary** (`15,000 - 20,000 EGP per month`, `SAR 12k`,
`8000 درهم`) from the posting: they show as 🧭 and 💰 lines. **Settings →
Ranking and muting → Most years of experience** drops jobs asking for more
(off by default).

**Is this a fit?** On a job's screen, or by long-pressing it in the list:
👍 / 👎, or mute its company or words in its title. Two 👎 on a company or a
title word lower its later scores; a mute drops matching jobs from the next
run on (and hides them from the list at once). Muted entries are listed in
Settings with Remove and Undo.

**Digest** (off by default): jobs scoring at least the threshold (70) go out
on their own; the rest wait and arrive as one message every 3 hours, best
first, grouped by region.

## Keeping an eye on it

- **Why jobs were dropped.** Home shows the week's top three reasons; *See
  why* opens each source's last run and seven days, by reason (title not on
  your list, senior title, not in your places, muted…), filtered by region.
- **A source gone quiet.** A source that used to find jobs and now parses
  nothing from good answers three runs in a row gets one alert a day, in
  Telegram and as a notification: the site has likely changed. The page it got
  is kept (the last 3 per source, under **Log → ⋮ → Snapshots**), and one tap
  sends it to the log chat to become a parser test.
- **Weekly summary**, Friday after 10:00 Cairo: jobs found and sent by region
  and source, the five best matches, applications, Serper searches, the top
  reasons jobs were dropped, and any source gone quiet.

## Applying

**Apply button.** Every new job also arrives as a phone notification. Its
**Apply** button copies a cover note to the clipboard and opens the job —
LinkedIn links open in the LinkedIn app — so applying is paste and send.
Tapping the notification itself only opens the job. Applying on LinkedIn is
deliberately left to you: LinkedIn bans automated activity.

**Emailed CVs, one tap each.** When a posting gives a hiring address ("send
your CV to hr@…"), the job goes on the **Review jobs to email my CV to**
screen, and a notification says how many are waiting. The job's own
notification says so too: **📧 Email found**, with the address and an
**Email CV** button that opens the job with **Send CV** on it (its own
"Email found in a job" channel, so it can ring louder). Nothing is emailed on
its own. Each job there has **Send** (your CV and the cover note go out at
once, from your Gmail) and **Ignore** (gone for good); tap the job to read
its description and open the posting. Jobs stay listed for 7 days. No-reply,
support and sales inboxes and anything on the "Never email" list are never
used. Each email's wording varies between a few versions. The subject is
the job's title ("Senior Backend Engineer application"); for a hiring post,
whose first line is rarely a title, the role is read from the post's text
("We're looking for a Laravel developer" → "Laravel Developer application").
When the posting says what the subject must be ("Subject: PHP Dev - Cairo",
"put Golang in the subject"), that comes first. Each one is in the log (`📨`) and in
Gmail's Sent folder.

**A post you found yourself.** On a LinkedIn post, tap **Send** or **Share**,
then **Job Crawler** — or open **Add a post** on the home screen and paste its
link or text. The app opens the link logged out, as the crawler does, finds
the hiring address and drafts the email. Its **To**, **Subject** and
**Message** are fields you can change before sending (**Reset the note**
brings the draft back); **Send CV** emails it as it stands, **Save only**
leaves it on the Emails screen. LinkedIn often answers a logged-out visit
with its sign-in page instead of the post: the app then asks for the text (on
the post, **⋯ → Copy text**, then **Paste**). Posts added this way skip the
filters and are kept off Telegram.

It needs, in **Settings → Applying** on the phone: your name, phone, the
Gmail address that sends CVs and its app password. No new APK is needed to
change them; a build whose `.env` has these uses them only for a field left
empty:

```
APPLY_NAME=Your Name
APPLY_PHONE=010...
APPLY_GMAIL=you@gmail.com
APPLY_GMAIL_APP_PASSWORD=abcdefghijklmnop
```

The password is a Google **app password** (Google Account → Security →
2-Step Verification → App passwords), not the account's own: it can only send
mail, and revoking it stops the app without touching anything else. Then, on
the phone, **Choose CV (PDF)**, check the note's wording, and **Email a sample
application to myself** before sending the first one.

## Updates install over the old app

Android installs an update only when it carries the same key as the app
already installed. The signing key is not in this public repository: create
your own once (`keytool -genkeypair -keystore android/app/signing.keystore
-alias jobcrawler -keyalg RSA -validity 10000`, passwords as in
`app/build.gradle.kts`) and keep using the same one, because a new key means
uninstalling first, which wipes the dedup database. Without it the build signs
with Android's debug key.

The bot token, chat id, Serper key and Gmail settings are blank here: fill in
your own in `.env` before building. An APK built with them carries them, so
keep such an APK (and the Drive folder `DRIVE_FOLDER` / `Updater.FOLDER_ID`
points at) private.

## Backup: surviving an uninstall

Installing an update over the app keeps everything. Uninstalling deletes the
app's database: which jobs were sent, which addresses were emailed. To keep
it, tap **Choose backup folder** once (Documents is fine): the app copies its
database there as `JobCrawler-backup.sqlite` after every run.

After a reinstall, tap **Choose backup folder** and pick the same folder,
ideally before the first run. A fresh install that finds a backup there
restores it rather than overwriting it, so jobs already sent are not sent
again. **Restore from a backup file** does the same from any copy of that
file. Settings (keywords, pitch, CV) are not in the backup.

## Build locally

JDK 17 and the Android SDK (platform 35):

```sh
cd android
./gradlew testDebugUnitTest assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Limits

- Every source has its own worker and schedule (`crawl-src-<key>`): LinkedIn
  jobs every 5 minutes, one place of *Places to search* a run (all keywords),
  so every place comes round once per cycle — 6 places, 30 minutes — and each
  place's search looks back to its own last clean one. WorkManager's periodic
  work cannot go under 15 minutes, so the runs in between are one-time work
  each run queues for the next (`crawl-next-linkedin_jobs-a`/`-b`); the
  15-minute schedule stays, to restart that chain if Android drops it. Giving
  LinkedIn jobs an interval of its own (long-press) turns the chain off. The
  boards on the Settings interval, Jobright hourly,
  Recruitera every 6 hours, AI data labelling, remote feeds and sponsor boards
  every 3 hours, ATS boards twice a day, the
  job APIs twice a day, Hacker News daily, and the paid searches hourly with
  their once-a-day guard. Long-press a source in Settings to give it its own
  interval. WorkManager never runs periodic work more often than every 15
  minutes, and gives each worker at most 10 minutes; nine are the source's.
- **LinkedIn worldwide** (`linkedin_worldwide`, hourly): the Egypt search's
  keywords in "Worldwide" with LinkedIn's remote filter. A job is kept only if
  nothing limits who may apply (US only, EU residents, right to work, another
  language) and its description says it is open — anywhere, or EMEA, the
  Middle East, Africa, Egypt, UTC+2/+3. It has its own 17-request hourly
  budget (`LinkedInRest.WORLDWIDE_HOURLY_CAP`), so it never uses the Egypt
  search's share, but a 429 from either rests both: LinkedIn sees one phone.
- Up to 3 sources run at once (2 on a phone WorkManager gives fewer threads).
  Sources that share a remote take turns: LinkedIn jobs and both posts
  searches (LinkedIn), and the posts searches and Glassdoor (Serper). LinkedIn's requests are capped at
  150 an hour across its sources (`LinkedInRest.HOURLY_CAP`). A worker
  that finds its slot or lane taken retries a minute later. One pace covers
  every Telegram send, whichever worker sends it.
- **HousekeepingWorker** (every 30 minutes, and 2 minutes after new rows):
  delivery retries, the review notice, the digest and the weekly summary;
  hourly, pruning, the backup and the update check.
- A source's first run records what is out there without sending it. Each
  source seeds on its own, so switching one on later does not flood the
  channel or mute the rest.
- Recruitera (~80 career sites) and the company boards do not fit one run,
  so each pass carries on from the site or board where the last one stopped.
- A Recruitera run the network cuts short does not count as its 6-hourly
  turn. Phone offline: it resumes once the phone is back online. Online but
  five requests in a row unanswered (a router without internet, DNS down): it
  stops and retries in 10 minutes. Either way it starts again from the first
  site that went unanswered. After 3 such runs in a row the phone shows a
  "recruitera keeps failing" notification, and another once it gets through.
- LinkedIn hiring posts cost a Serper query per keyword x place (50), so they
  run once a day, on the first run after 07:00 Cairo, and only when `.env`
  has `SERPER_API_KEY`. Turn them off on the server if the phone runs them.
- Doze can push a run back by 10–20 minutes while the phone sits idle.
- Not ported: X posts (account cookies), Naukrigulf (Gulf jobs), and the
  Capgemini SuccessFactors board (a 15 MB listing). They stay on the server.
