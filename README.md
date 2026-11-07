# LinkedIn jobs → Telegram

Crawls LinkedIn's **public guest job search** for a configured list of keywords and
locations, stores each posting once, and pushes new ones to a Telegram channel.

A second, optional source finds **hiring posts** — the "we're looking for a
Laravel dev, DM me" kind that never becomes a job listing. See
[Hiring posts](#hiring-posts).

Standalone Node.js project — no docker, no database server, no web framework.

---

## How it works

```
 cron tick
    │
    ▼
 LinkedInJobsSource ── for each keyword × location × page
    │                    └─ LinkedInGuestClient  (paced, backs off on 429/403/999)
    │                         └─ jobCardParser   (cheerio → item objects)
    │                    └─ jobDetailParser      (description + seniority level)
    ▼
 filters.js      ── remote ∧ tech ∧ location ∧ ¬senior ∧ (contact, optional)
    │
    ▼
 Store (SQLite)  ── UNIQUE(source, external_id)  ← the dedup guarantee
    │
    ▼
 TelegramClient  ── one message per new item, staggered
    │
    ▼
 markSent()      ← only on a confirmed ok:true
```

The crawl is sequential and paced on purpose: it must not be retried wholesale,
or it hammers a rate-limited endpoint. Delivery is per item, so one throttled
message never blocks or duplicates the rest.

**Only the row-delivery step may mark an item `sent`.** A Telegram outage
therefore delays delivery but never silently swallows a posting — stalled rows
are picked up again on a later run until `MAX_SEND_ATTEMPTS`.

---

## Requirements

- **Node.js 22.5+** (uses the built-in `node:sqlite` and global `fetch`; verified on 22.23 and 24.15)
- No database server, no Python, no docker

---

## Setup

```bash
cd D:\backend\linkedin-jobs-telegram
npm install
copy .env.example .env      # PowerShell/cmd;  cp on bash
```

Then edit `.env`:

1. **Create a bot** — message [@BotFather](https://t.me/BotFather) on Telegram,
   send `/newbot`, copy the token into `TELEGRAM_BOT_TOKEN`.
2. **Create a channel or group**, add the bot as an **administrator**
   (a bot cannot post to a channel it does not administer).
3. **Get the chat id** — for a public channel use `@yourchannelname`. For a
   private one, post any message in it and open:
   `https://api.telegram.org/bot<TOKEN>/getUpdates`
   then copy `result[].channel_post.chat.id` (it looks like `-1001234567890`)
   into `TELEGRAM_CHAT_ID`.
4. Set your keywords and markets. `JOB_LOCATIONS` is searched as-is —
   on-site, hybrid and remote alike — and `JOB_REMOTE_LOCATIONS` for
   **remote work only**, which suits a borderless search:
   ```
   JOB_KEYWORDS=Laravel Developer,Node.js Developer,Backend Developer,PHP Developer,Full Stack Developer,Application Support
   JOB_LOCATIONS=Egypt,Saudi Arabia,United Arab Emirates,Qatar,Kuwait,Bahrain,Oman
   JOB_REMOTE_LOCATIONS=Worldwide,EMEA
   ```
5. Flip the switches when you are ready:
   ```
   CRAWLER_ENABLED=true
   TELEGRAM_ENABLED=true
   ```

> Requests per run = keywords × locations × pages. Four keywords × three
> locations × four pages is 48 requests an hour. Keep the lists short.

---

## Filtering

LinkedIn's search is fuzzy — asking for "Backend Developer" returns plenty of
things that are not backend developer roles. `src/filters.js` runs after the
crawl, in the pipeline, so every source is filtered by the same rules.

An item must satisfy **all** of:

| Rule | Env key | Default |
|---|---|---|
| Is remote, if asked | `FILTER_REMOTE_ONLY` | `false` — on-site and hybrid are kept; `true` keeps remote only, and a hybrid posting counts as on-site |
| Contains a wanted technology | `FILTER_TECH` | laravel, node, nodejs, node.js, backend, back-end, back end, support |
| Contains a wanted location | `FILTER_LOCATIONS` | Egypt and the Gulf, plus borderless terms: worldwide, anywhere, emea, mena, middle east, gcc, egypt, cairo, saudi arabia, riyadh, uae, dubai, qatar, kuwait, bahrain, oman, … |
| Title names the stack or a role it fits | `FILTER_TITLE` | laravel, php, symfony, backend, node, nestjs, express, full stack, fullstack, mern, software engineer, software developer, web developer, api developer, application support, … |
| Is not in a blocked country | `FILTER_BLOCKED_LOCATIONS` | India, Pakistan, Nigeria and their main cities — title and card location only |
| Says nothing that rules the candidate out | `FILTER_INELIGIBLE` | authorized to work in, must be based in, us citizen, security clearance, no visa sponsorship, saudi national, co-founder, … |
| Is not a senior-level role | `FILTER_SENIORITY_EXCLUDE` | senior, sr, lead, principal, staff, staff engineer, founding, head of, director, manager, architect, vp, chief, expert |
| States a wanted level (or none at all) | `FILTER_SENIORITY_INCLUDE`, `FILTER_SENIORITY_STRICT` | junior, jr, mid, mid-level, … / strict = false |
| Carries an email or phone | `FILTER_REQUIRE_CONTACT` | `none` — set to `all` to keep only postings with a contact |

Matching is word-boundary aware, so `node` does not match `nodemon`, and the
text searched is title + company + location + description + seniority level.

### Remote only, if you want it

Off by default: on-site and hybrid jobs in Egypt and the Gulf are kept. Set
`FILTER_REMOTE_ONLY=true` and every posting has to be remote. LinkedIn settles
it for the markets in `JOB_REMOTE_LOCATIONS` — those searches carry `f_WT=2`,
so the item arrives flagged and is trusted outright. Anything else has to say
so in its own words ("remote", "work from home", "home-based", …), and a
posting that says **hybrid** is rejected: hybrid is on-site work with fewer
days, and its ad uses the word "remote" as freely as a real remote one does.

Two levels of seniority handling:

- **LinkedIn's own "Seniority level" field is authoritative.** `Mid-Senior
  level`, `Director` and `Executive` are rejected outright. This matters —
  "Mid-Senior level" contains the word "mid", so a naive include-check lets it
  straight through.
- The free-text title and description are then checked against the include and
  exclude word lists.

`LINKEDIN_EXPERIENCE_LEVELS=2,3` also narrows **server-side** (`f_E`: entry +
associate), so fewer unwanted results are fetched at all.

`LINKEDIN_FETCH_DETAILS=true` fetches each posting's description page. It costs
one extra request per posting, and without it the filters see only a job title —
not enough to judge seniority or to find a contact address.

### Contact extraction

Emails and phone numbers are pulled from the text and shown in the message:

- Emails, including obfuscated forms: `jobs (at) startup (dot) io`
- Egyptian mobiles: `01012345678`, `0100 123 4567`, `+201112223344`, `010-1234-5678`
- Saudi mobiles: `0551234567`, `+966 55 123 4567`
- Generic `+<country> …` numbers

Salaries, years and reference numbers are not mistaken for phone numbers.

### Where it looks

The target is **work in Egypt and the Gulf** — Egypt, Saudi Arabia, UAE,
Qatar, Kuwait, Bahrain and Oman — on-site, hybrid or remote, plus remote work
open across borders. US, UK and EU postings are left out on purpose — even a
"remote" one is remote *inside* that country, and its knockout questions
reject anyone without the right to work there.

`JOB_LOCATIONS` holds the seven countries and is searched as-is, with no
work-type filter. `JOB_REMOTE_LOCATIONS` holds only the borderless searches,
Worldwide and EMEA, asked for remote work only by passing LinkedIn's
`f_WT=2`; what they return is placed by `FILTER_LOCATIONS`, read out of the
posting.

A search that named a market settles where its results are, so
`FILTER_LOCATIONS` is not consulted for them. It has to work this way: a US
card reads "Chicago, IL" and never names the country, so a text rule rejects
nearly everything a US or UK search returns — and a rejected card is never
enriched, so it then fails the tech rule too, for want of the description that
would have passed it. Only a borderless search, such as
`Worldwide` or the literal location `Remote`, falls back to reading `FILTER_LOCATIONS` out of the posting.

The two lists are interleaved, so a run that stops early — at `--limit`, or at
a block — has still reached both.

### What "seniority" reads

Seniority is judged on the **role name** — the job title, plus LinkedIn's own
"Seniority level" when the posting states one — and never on the body. A junior
ad talks about senior people all the time ("you will report to the Engineering
Manager", "our architect will mentor you"), and matching `FILTER_SENIORITY_EXCLUDE`
against the description threw away most genuine junior roles.

One body pattern is still rejected, as `seniority-excluded-body`: a level
attached to a role, such as "senior developer" or "lead engineer", for the ad
whose title is plain but whose text says what it is really after.

`FILTER_SENIORITY_INCLUDE` still reads the whole description, because
"we are looking for a junior" in a body is exactly the signal it wants.

### Tuning

```bash
# See the rejection breakdown without sending anything
node src/index.js --once --dry-run --pages 1 --keyword "Laravel Developer"
```

The summary prints `rejected` as `reason:count`, e.g.
`seniority-level:4 tech:2` — if matches are too few, that tells you which rule
to loosen. Set `FILTER_ENABLED=false` to bypass all of it.

---

## HTTP API

`--watch` (with `PORT` set) and `--serve` both expose a small API. `--serve`
is the API on its own: no schedule, nothing crawls until you ask.

```bash
node src/index.js --serve                 # port 3000, or $PORT
curl -X POST "http://localhost:3000/run?hours=24"
```

A request does not crawl; it **queues a job**. A crawl takes minutes and two
cannot overlap — they share the pacing budget of one rate-limited endpoint — so
a second caller is queued rather than refused, and gets an id to ask about it.
The scheduler's ticks go through the same queue, so `/jobs` shows every crawl
whoever asked for it.

| Route | Purpose |
|---|---|
| `POST /run` | Queue a crawl of the last `hours` hours (24 by default). Answers `202` with a job id |
| `GET /jobs/<id>` | One job: `queued`, `running`, `done` or `failed`, plus its per-source results |
| `GET /jobs` | Recent jobs, newest first (`?limit=`) |
| `GET /` | Status: queue, last run, stored totals |
| `GET /healthz` | Liveness probe |

`POST /run` parameters, all optional:

| Param | Default | Meaning |
|---|---|---|
| `hours` | `24` | How far back to look. Each source is handed its own spelling: `f_TPR=r86400` for jobs, a `d`/`w`/`m` bucket for posts |
| `wait` | `false` | Hold the connection until the job finishes and answer with the job itself |
| `sources` | all enabled | Comma-separated: `linkedin_jobs,linkedin_posts` |
| `keywords`, `locations` | `.env` | Comma-separated overrides for this run |
| `limit`, `pages` | `.env` | Caps for this run |
| `dryRun`, `noSend` | `false` | Same meaning as the CLI flags |

```bash
# Queue it, get an id back immediately
curl -X POST "http://localhost:3000/run?hours=24"
# {"accepted":true,"jobId":"8f92…","status":"running","queuePosition":0,
#  "hours":24,"statusUrl":"/jobs/8f92…"}

# Ask how it went
curl "http://localhost:3000/jobs/8f92…"
# {"status":"done","durationMs":6171,
#  "results":[{"source":"linkedin_jobs","parsed":2,"matched":1,"stored":1,"delivered":1,…}]}

# Or wait for it instead of polling
curl -X POST "http://localhost:3000/run?hours=24&wait=true"

# Rehearse: crawl, filter, report — store nothing, send nothing
curl -X POST "http://localhost:3000/run?hours=48&wait=true&dryRun=true&limit=5"

# Remote, with a token
curl -X POST "https://your-host/run?hours=24" -H "Authorization: Bearer $API_TOKEN"
```

**Answers you should expect:** `202` when the job is queued, `200` (or `500` for
a failed job) when `wait=true`, `429` when more than ten jobs are already
waiting, `401` when the token is wrong, `405` on `GET /run`, `404` for an
unknown job id — including one from before a restart, since job history is in
memory while delivered items are in SQLite.

A crawl posts to your Telegram channel, so the endpoint is not open: set
`API_TOKEN` and it is required, leave it unset and only localhost may queue a
job. `GET /run` is refused on purpose — a link, a preload or a crawler must not
be able to start one.

The same window override exists on the CLI:

```bash
node src/index.js --once --hours 24
```

---

## Usage

```bash
# See what would be found, send and store nothing
node src/index.js --once --dry-run --limit 5 --pages 1 --keyword "Backend Developer"

# Store new items but do not post to Telegram
node src/index.js --once --no-send

# Normal single run
node src/index.js --once
npm run once

# Stay running, crawl on CRON_SCHEDULE (default: hourly)
node src/index.js --watch
npm start

# Inspect state
node src/index.js --stats
node src/index.js --resend 42      # re-send one stored item
node src/index.js --help
```

### Flags

| Flag | Meaning |
|---|---|
| `--once` | One crawl, then exit (the default) |
| `--watch` | Run now, then keep running on `CRON_SCHEDULE` |
| `--dry-run` | Crawl and print only — nothing is written or sent |
| `--no-send` | Store new items, skip Telegram |
| `--limit <n>` | Stop after n items |
| `--pages <n>` | Result pages per keyword/location |
| `--keyword <term>` | Override keyword list (repeatable) |
| `--location <name>` | Override location list (repeatable) |
| `--source <key>` | `linkedin_jobs` (repeatable) |
| `--resend <id>` | Re-send one stored item, then exit |
| `--stats` | Item counts by status |

Three independent ways to avoid posting to a live channel: `--dry-run`,
`--no-send`, and `TELEGRAM_ENABLED=false`.

---

## Running it unattended on Windows

`--watch` keeps the process alive and does its own scheduling, so it only needs
something to start it. Either:

**Task Scheduler, hourly, no resident process** — action
`C:\Program Files\nodejs\node.exe`, arguments `src\index.js --once`, "Start in"
`D:\backend\linkedin-jobs-telegram`.

**Task Scheduler, at logon, resident** — same but arguments `src\index.js --watch`,
trigger "At log on", and tick "Run whether user is logged on or not".

---

## Docker

The image is self-contained: it carries its own configuration and needs no
arguments, no shell access and no setup on the host.

```bash
docker compose up -d          # or: docker run -d --name crawler <image>
```

It crawls the fast lane on start, then every 30 minutes; the slower lanes keep
their own clocks.

### What it does on its own

Each source runs as its **own short-lived process**, one after another, in the
order given by `CRAWL_SOURCES`:

```
linkedin_jobs  →  [periodic sources, if due: workable hourly, …]  →  sleep
```

There are **three lanes**. `CRAWL_SOURCES` runs every pass. `CRAWL_DAILY_SOURCES`
is checked every pass and runs once a calendar day, at the wall-clock time in
`CRAWL_DAILY_AT` (07:00) — `linkedin_posts` is there because it is the one
source billed per query, and it reads a search index that updates daily.
Crawling it hourly would pay 24 times over for the same posts.
`CRAWL_PERIODIC_SOURCES` runs every `CRAWL_PERIODIC_HOURS`, or every N hours
when written `source:N`.

A **time of day, not an interval**: "every 1440 minutes" drifts by however long
each pass took and slides through the clock. A pass that misses the target
minute entirely — one starting at 06:55 and running twenty minutes — still
crawls, at the first pass after the time, a little late rather than not at all.

The day it last ran is recorded as a file under `DATA_DIR`, beside the
database, so a redeploy does not crawl it again.

Where `DATA_DIR` is **read-only** — a host with no persistent disk, which is
Render's free plan — that file cannot be written, and the loop falls back to
`/tmp`. Losing it on restart costs one extra query per deploy. Losing it
*entirely* would cost far more: the gate is "after the time and not yet run
 today", so a state file that never appears makes the source due again on every
pass — 22 paid queries a day rather than one. The loop therefore also keeps
the answer in memory for the life of the process, which no filesystem can
refuse.

The directory is probed by writing to it, not by asking: `[ -w ]` reports a
read-only mount as writable, and the failure only surfaces at the first write.

A source fetches, filters, stores and sends its own jobs before the next one
starts, and its process exits before that next one begins — so the host only
ever holds one source's memory, not the sum of all of them. That is what keeps
a run inside roughly 80-90 MB rather than 130+.

One source failing does not stop the others: the loop logs the exit code and
carries on to the next. A pass that overruns its interval starts the next pass
immediately instead of stacking crawls against a rate-limited site.

### Knobs

Set these as container environment variables; every one has a working default.

| Variable | Default | What it changes |
|---|---|---|
| `CRAWL_SOURCES` | `linkedin_jobs` | Which sites, in which order, every pass (every 30 minutes). Remove one to stop crawling it; set it empty to run nothing in this lane. `wuzzuf`, `tanqeeb` and `naukrigulf` block datacenter IPs, so they are left out of the hosted default; add them back on a residential connection |
| `CRAWL_INTERVAL_MINUTES` | `30` | Minutes between passes, counted from the pass's start; one that overruns starts the next immediately. 30 because a LinkedIn pass runs ~20 minutes |
| `CRAWL_DAILY_SOURCES` | `linkedin_posts` | Sources that run once a calendar day, checked each pass and run when due |
| `CRAWL_DAILY_AT` | `07:00` | The time of day they run, `HH:MM`, read in the container `TZ` |
| `CRAWL_PERIODIC_SOURCES` | `workable:1 vertex:1 recruitera:6 ats:12 ai_data:3` | Sources that run every `CRAWL_PERIODIC_HOURS`, or every N hours written `source:N` — `x_session` belongs here too |
| `CRAWL_PERIODIC_HOURS` | `6` | Hours between their runs, for entries without their own `:N` |
| `CRAWL_NOTIFY_AFTER_FAILURES` | `3` | Failed runs in a row of a periodic source (a crash, or exit 75 when the network cut it short) before a Telegram alert to `TELEGRAM_ALERT_CHAT_ID`; a recovery alert follows. `0` turns it off |
| `CRAWL_RUN_AT_START` | `recruitera` | Periodic sources that also run on the first pass after every container start, then keep their interval. Never put `x_session` here — or anything billed per query, such as `linkedin_posts` (33 Serper queries a run) |
| `NODE_HEAP_MB` | `128` | V8 heap cap per crawl process, sized for the 256 MB host |
| `CRAWL_MODE` | `sequential` | `watch` restores the single resident process with the HTTP API — needs about twice the memory |
| `WUZZUF_MAX_DETAILS` | `10` | Wuzzuf pages opened per pass. Lower it if memory is tight |
| `TANQEEB_MAX_DETAILS` | `10` | Tanqeeb postings opened per pass |
| `NAUKRIGULF_MAX_PAGES` | `1` | Naukrigulf pages per keyword. One request each, nothing opened per posting |
| `CRAWL_STARTUP_DELAY` | `0` | Seconds to wait before the first pass |

### On a 256 MB host

Measured peak RSS per crawl process: Wuzzuf ~79 MB, Tanqeeb ~87 MB, every
source in one process ~90 MB. The 128 MB heap cap leaves the host room for the
heartbeat process, SQLite and the kernel; if it is still tight, in order of
effect:

```bash
NODE_HEAP_MB=96
WUZZUF_MAX_DETAILS=5
TANQEEB_MAX_DETAILS=5
CRAWL_SOURCES="wuzzuf tanqeeb"     # drop the LinkedIn crawl, the longest pass
```

Lowering `*_MAX_DETAILS` costs time, not coverage: a backlog is worked through
across passes, and the ledger means no page is ever opened twice.

`CRAWL_MODE=watch` fits this host, since one process's high-water mark is now
affordable — but it holds every source's for as long as the container lives, so
it wants the host raised again if it grows.

### Health

`docker ps` reports health without anything listening on a port: the crawl
loop touches a heartbeat file around every source, and the healthcheck fails
the container if it goes stale for more than two intervals. In
`CRAWL_MODE=watch` the same check falls back to the app's `/healthz`.

### Telegram commands

Message the bot privately to run a source now instead of waiting for its
schedule:

| Command | Runs |
|---|---|
| `/posts` | `linkedin_posts` |
| `/jobs` | `linkedin_jobs` |
| `/run <source> [source…]` | any source key, e.g. `/run ats workable` |
| `/sources` | lists the keys |
| `/status` | what is running and queued |

The bot replies when the run finishes; new items go to the channel as usual.

Only user ids in `TELEGRAM_COMMAND_USER_IDS` (comma-separated) may use them.
Anyone else, you included before it is set, is told their id, so message the
bot once, copy the id into the variable, and redeploy.
`TELEGRAM_COMMANDS_ENABLED=false` turns the bot off.

The bot never crawls. It long-polls Telegram from the heartbeat server, which
runs anyway (about 0.3 MB more), and queues the request. The loop runs it after
the current source, or within `CRAWL_REQUEST_POLL` seconds (default 15) while
sleeping. Requested crawls therefore never overlap a scheduled one. A requested
run counts as its lane's run, so `/posts` at noon pushes the next scheduled
`linkedin_posts` crawl a day on rather than paying for it twice.
Sequential mode only; `CRAWL_MODE=watch` has no commands.

Don't poll the same bot token from two places: Telegram allows one
`getUpdates` client, and the other backs off with `409`.

### The volume matters

`/app/data` holds the SQLite database: what has been delivered, what has been
crawled, and the fingerprints that stop the same job arriving from two sites.
Without a persistent volume behind it, every restart re-sends everything it
still considers recent. The compose file mounts it; a plain `docker run`
should too:

```bash
docker run -d -v crawler-data:/app/data --name crawler <image>
```

### Logs

```bash
docker logs -f crawler
```

Each pass prints which source is running and a table of what it parsed,
matched, stored and delivered. The `rejected` column names the rule that
dropped everything else, which is the fastest way to tell "nothing was posted"
from "my filters are too tight".

#### Decision log: every rejected, duplicate and accepted item

Each item the crawler sees gets one row in the database's `decisions` table:
the decision, the rule (`reason`), and what triggered it (`detail`, e.g.
`matched: wordpress` or `none of: laravel, php, …`). Duplicates say what they
duplicate: `same-id`, `fingerprint` (the same job from another board, with that
board's URL) or `repost`. Rows older than `DECISIONS_RETENTION_DAYS` (14) are
pruned. `LOG_DECISIONS=false` turns it off, and `LOG_DECISIONS_ACCEPTED=false` keeps
only the dropped items.

```bash
node src/index.js --rejected                      # last 24h: counts, top titles, latest rows
node src/index.js --rejected title --since 72     # one reason
node src/index.js --decisions duplicate --source wuzzuf
node src/index.js --decisions all --since 1 --limit 50
```

The Telegram bot answers `/rejected [reason|source] [hours]` (also `/rejected
duplicate`, `/rejected all`). With `--watch` / `--serve`, `GET /decisions` and
`GET /decisions/summary` return JSON (`?decision=&reason=&source=&since=&limit=`,
behind `API_TOKEN` like `POST /run`).

With `LOG_LEVEL=debug` the log also prints a line for every rejected, duplicate
and accepted item and every HTTP request (status, duration). At `info`, each
run logs a `Config snapshot` (secrets redacted), `Crawl requested` (who started
it: `cli`, `schedule`, `telegram`, `http`, `startup`), a `Run finished` per source
(including `duplicates`, `rssMb`, `heapUsedMb`), a `Search budget` line for the
search-engine sources, and a `Crawl summary` with the top rejection reasons.
`Source returned no items` is a warning: when it repeats, the site's markup has
probably changed.

## Deploying to a PaaS (Render, Railway, Deplexo, Fly…)

This is a **background job, not a web app**.

Running the **container** needs no start command: its default is the crawl
loop, which runs each source in its own process and sleeps between passes.
That is the option to prefer — see [Docker](#docker).

If the platform runs the repo rather than the image, and expects a process
that stays up and answers a port, use watch mode:

```
start command:  npm start        # = node src/index.js --watch
```

`--once` is wrong on a PaaS — nothing would re-invoke it. Watch mode holds
every source's peak memory for the life of the process, so on a small
instance prefer the container's sequential mode.

**Health check.** Most platforms kill a process that binds no port, and their
router answers `502` until something listens. If `PORT` is set, `--watch` starts
a small HTTP server:

| Path | Response |
|---|---|
| `/healthz`, `/health` | `ok`, 200 — point the platform's health check here |
| `/` | JSON status: uptime, schedule, last run stats, item totals |

Locally `PORT` is unset and no server starts, so nothing changes.

**Persistent storage is required.** `data/crawler.sqlite` is how the crawler
remembers what it already sent. On most platforms the filesystem is wiped on
every deploy and restart — without a disk, **every restart re-sends matching
jobs**. Mount a volume at `./data` (or point `DATABASE_PATH` at one).

The status page reports `databaseLooksFresh: true` when the process has been up
for more than five minutes with an empty database — that means the volume is
missing or not mounted where the crawler is looking.

**Don't run two copies against one chat.** A PaaS deployment and a local
scheduled task keep separate databases, so they will not dedup against each
other and you will get every job twice.

---

## Sources

| Source | Reads | Notes |
|---|---|---|
| `linkedin_jobs` | Public guest job search | `JOB_LOCATIONS` (Egypt + the Gulf) as-is — on-site, hybrid and remote; `JOB_REMOTE_LOCATIONS` (Worldwide, EMEA) remote-only (`f_WT=2`) |
| `linkedin_posts` | Hiring posts, via a search index (Serper) | **On**: Egypt + Greater Cairo, Backend / Full Stack / Software Engineer, once a day (`/posts` for sooner). Aimed elsewhere than the job sources; see [Hiring posts](#hiring-posts) |
| `wuzzuf` | wuzzuf.net **sitemap** + job pages | **On, but not in the hosted schedule** (job pages answer 403 to datacenter IPs): Egypt and Saudi listings. Search pages are behind Cloudflare and are never requested |
| `tanqeeb` | Tanqeeb search, one site per country | **On, but not in the hosted schedule** (serves pages with no jobs to datacenter IPs): Egypt + the Gulf (`TANQEEB_SITES`). Aggregator — carries **Bayt**, LinkedIn, Wuzzuf and Naukri Gulf listings |
| `naukrigulf` | naukrigulf.com **JSON endpoint** | **On, but not in the hosted schedule** (never answers datacenter IPs): Gulf markets. One request per keyword, nothing opened per posting |
| `x_posts` | Hiring posts on X, via a search index (Serper) | **Off**: same target as `linkedin_posts`, daily. Snippet only; see [Hiring posts on X](#hiring-posts-on-x) |
| `x_session` | X's own search, signed in by cookie | **Off**: needs a throwaway account's cookies. Every 6 hours; see [Hiring posts on X](#hiring-posts-on-x) |
| `recruitera` | app.recruitera.ai **public JSON API** | **On**: tech jobs located in Egypt or the Gulf, at every employer on Recruitera. On start, then every 24 hours; see [Recruitera](#recruitera) |
| `workable` | jobs.workable.com **JSON search** | **On**: every Workable customer's jobs in Egypt and the Gulf, one request per keyword x country. Hourly (periodic lane, `workable:1`); `WORKABLE_*` |
| `vertex` | vertextech-eg.com **sitemap** + vacancy pages | **On**: VerteX Technologies' (Smart Village) vacancies. Undated, so the first run records what is open without sending and later runs send only vacancies not listed before. Hourly (periodic lane, `vertex:1`); `VERTEX_*` |
| `ats` | Lever / Greenhouse / Ashby / BambooHR / Recruitee **JSON feeds**, Breezy **JSON**, Teamtailor **RSS**, SuccessFactors **XML**, Zoho Recruit **careers page** | **On**: the career boards named in `ATS_BOARDS` (Klivvr, Thndr, Tamara, Instabug, Bosta, elmenus, Axis, Property Finder, Supersub, Convertedin, Dubizzle, Capgemini Egypt, Yassir, Agoda, almentor, MigrationIT, Fixed Solutions, QuTip, CoorB, CME, Centro CDX, Dot IT, Seena Pay, plus the work-from-anywhere boards Canonical, Constructor, Automattic and Xapo Bank, and more). Twice a day (periodic lane, `ats:12`); jobs older than `ATS_MAX_AGE_DAYS` (14) are dropped, or a board's own cutoff in `ATS_BOARD_MAX_AGE_DAYS` (MigrationIT: 3 days); `ATS_*` |
| `ai_data` | jobs.workable.com **JSON search** + optional vendor boards (`AI_DATA_BOARDS`, same adapters as `ats`) | **On**: AI data labelling and assessment — annotation, transcription, AI trainer / rater / evaluator, linguist roles — on audio, images, video and text, Arabic especially. Searched in Egypt and borderless (`Anywhere`, remote only); the title decides (`AI_DATA_FILTER_TITLE`), not `FILTER_TECH`. Generalist roles only — a title naming a field that needs a degree (medical, pharmacy, law, finance, the sciences, civil / mechanical / electrical engineering, …) is dropped, while software / coding gigs and plain image labelling are kept (`AI_DATA_FILTER_TITLE_EXCLUDE`) — and Arabic or Arabic + English: an ad for another language's speakers is dropped unless it names Arabic too (`AI_DATA_LANGUAGES` / `AI_DATA_OTHER_LANGUAGES`). Each message's *Matched* line names the data: `Arabic · Audio`. Every 3 hours (periodic lane, `ai_data:3`); `AI_DATA_*` |


**Ported from the Android app** (`android/`), each its own short process in
the container's periodic lane at the app's rhythm:

| Source | Reads | Notes |
|---|---|---|
| `drjob` | drjobpro.com / drjobs.ae keyword pages + JobPosting JSON-LD | **On**, hourly: Egypt, plus Saudi Arabia and the UAE with `BOARDS_GULF`. A page that answers HTTP 5xx is rested for days instead of stopping the run; `DRJOB_*` |
| `remote_feeds` | Himalayas, Jobicy, Remotive, We Work Remotely, RemoteOK, Working Nomads, Arbeitnow, Landing.jobs, Remote Rocketship | **On**, every 3 h, free: a remote job is kept only when the feed's own "who may apply" takes someone in Egypt, then judged abroad (below). `REMOTE_FEEDS_OFF` switches feeds off |
| `jobgether` | Jobgether's public search API + JobPosting page | **On**, every 3 h: remote jobs open to Egypt; at most 15 pages opened a run |
| `sponsor_boards` | Greenhouse / Lever / Ashby boards of 28 European companies known to sponsor visas | **On**, every 3 h: each job is marked as from a known sponsor, then judged abroad; `SPONSOR_BOARDS` |
| `relocateme` | relocate.me list + JobPosting JSON-LD | **On**, every 3 h: Europe/Japan jobs with the move and the visa paid, at the CV's stack and level |
| `jobright` | jobright.ai category pages (`__NEXT_DATA__`) | **On**, hourly: US jobs kept only when remote open outside the US or H1B-sponsored |
| `hn_hiring` | Hacker News "Who is hiring?" through Algolia | **On**, daily: "VISA" in an ad's header counts as sponsorship |
| `job_apis` | Adzuna, Jooble, Careerjet | **On**, twice a day, but each only once its free key is in `.env` (`ADZUNA_APP_ID`+`ADZUNA_APP_KEY`, `JOOBLE_API_KEY`, `CAREERJET_API_KEY`) |
| `mostaql`, `khamsat` | The Arabic freelance boards' listings | **On, but not in the hosted schedule** (they answer a datacenter IP with a 202 bot challenge): gigs naming the CV's stack in English or Arabic, posted under `#Freelance` |
| `career_sites` | Any company's careers sitemap + job pages | **On once `CAREER_SITES` names a site** (`id\|Company\|sitemap URL\|job URL regex[\|Location[\|Market]]`): JSON-LD or the page's heading and main text; a site's first run records what is open and sends none of it |
| `linkedin_worldwide` | LinkedIn guest search, Worldwide + remote | **Off**, as in the app (it spends the Egypt search's LinkedIn patience): jobs open to anyone or to EMEA/Africa/Egypt |
| `glassdoor` | Glassdoor listings found on Google (Serper) | **Off**, as in the app: 8 paid searches a run (10 with `BOARDS_GULF`) under `SERPER_DAILY_CAP` |

**Jobs abroad** (every worldwide source above) are kept only when remote and
open to Egypt, or visa-sponsored; in English (no "German C1 required"); not
only for residents there; at the CV's stack (PHP/Laravel, Node.js/NestJS,
Go, TypeScript) and at most 4 years asked (`src/sources/abroadFit.js`). Each
gets a **CV fit score**; only those scoring `ABROAD_MIN_SCORE` (55) or more go
out, the best `ABROAD_MAX_PER_RUN` (10) a run, with a `🎯 CV fit` line. The
rest are stored, marked skipped with their score.

**Saudi & UAE on the boards** (`BOARDS_GULF`, on by default, the app's
switch): Workable also searches Saudi Arabia and the UAE (one page each),
Tanqeeb reads its `saudi.` and `uae.` sites, Wuzzuf its Saudi sitemap,
Recruitera keeps SA/AE jobs, Dr.Job its Saudi and UAE pages, and the company
boards (with the app's SmartRecruiters, Workday and Oracle HCM readers) give a
Riyadh/Dubai job its country. Such a job is posted like Egypt's, flagged
🇸🇦/🇦🇪, tagged `#Gulf`, and dropped as `gulf-local-only` unless it says it
takes someone relocating (relocation, a sponsored visa or iqama, all
nationalities).

### Ranking, regions and muting

Ported from the Android app:

- **Every job has a region** — Egypt, Saudi & UAE, Worldwide or Freelance —
  and every message ends with `#Egypt`, `#Gulf`, `#Worldwide` or
  `#Freelance`. In a group with Topics, `TELEGRAM_TOPIC_EGYPT`,
  `TELEGRAM_TOPIC_GULF`, `TELEGRAM_TOPIC_WORLDWIDE` and
  `TELEGRAM_TOPIC_FREELANCE` (a topic link, `chatId:topicId`, or a bare topic
  id) post each region to its own topic (`src/topics.js`).
- **A fit score**, 0–100 (`src/score.js`): the title's and the text's stack,
  years in reach, freshness, remote, an address to write to, the region; a
  run sends its jobs best first and messages show `⭐ 82/100`.
- **Years of experience and salary** are read from the posting
  (`src/extract.js`: "3-5 years of experience", "خبرة 3 سنوات",
  "15,000 - 20,000 EGP per month") and shown as 🧭 and 💰 lines.
  `FILTER_MAX_YEARS` drops jobs asking for more (0, off, by default).
- **Can't apply**: the ineligible list now has the app's Gulf residency,
  iqama and nationality phrases in English and Arabic; a residency phrase
  followed by an Egyptian place ("must be based in Cairo") does not count.
  A level range in a title ("Mid/Senior") is open to its lower level.
- **Muting and verdicts** from Telegram (with `TELEGRAM_COMMANDS_ENABLED`):
  `/mute_company`, `/mute_word`, `/unmute`, `/mutes`; `/up <id>` and
  `/down <id>` (the 🆔 on each message) — two 👎 on a company or a title word
  lower its later scores.
- **Digest** (`DIGEST_ENABLED`, off): jobs scoring `DIGEST_THRESHOLD` (70) or
  more go out on their own; the rest arrive as one message every
  `DIGEST_EVERY_HOURS` (3), best first, grouped by region.
- **A source gone quiet**: one that used to find jobs and parses nothing for
  `QUIET_SOURCE_STREAK` (3) clean runs in a row sends one alert a day.
- **Weekly summary** (`WEEKLY_SUMMARY_ENABLED`, on): Friday after 10:00 Cairo,
  jobs found and sent by region and source, the five best matches, Serper
  searches, the top reasons jobs were dropped and any source gone quiet.
  Every source run is recorded in the `runs` table for it.
- **Seeding is per source**: a source that has stored nothing seeds its first
  run instead of sending it, so switching one on does not flood the channel.

**Bayt is not crawled directly.** Every path on bayt.com — including
`sitemap.xml` and `robots.txt`-permitted pages — answers 403 to anything that
is not a browser, and its `robots.txt` disallows the job listing paths. Its
postings arrive through Tanqeeb instead, labelled with the board they came
from.

### Wuzzuf

Discovery is the sitemap, never the search page:

```
 sitemap-job-1.xml + sitemap-saudi-job-1.xml   (5,000+ live jobs)
    │  slug filter          WUZZUF_SLUG_TERMS — the URL names the role
    │  already-seen filter  stored items + the checked_urls ledger
    │  cap                  WUZZUF_MAX_DETAILS (25)
    ▼
 job pages → title, company, location, "3 days ago" → an absolute date
    │  age filter           WUZZUF_MAX_AGE_DAYS (7)
    ▼
 the same filters, store and delivery as every other source
```

Two things make that work. The sitemap's `<lastmod>` is the time the file was
generated — identical on all 5,306 URLs — so it says nothing about which jobs
are new, and age has to be read off each page. And a page that turns out to be
too old is not stored, so without the `checked_urls` ledger the crawler would
re-open the same stale pages every hour and never reach the fresh ones behind
them.

Wuzzuf renders with emotion, so its class names are hashes that change on
every rebuild. The parser reads `h1`, the `<title>`/`og:` tags and text
anchored to fixed headings — nothing hashed.

### Naukrigulf

The only source here that reads JSON instead of markup, because there is no
markup to read: a naukrigulf.com page is a 43 KB script shell that fetches the
site's own search endpoint and draws the results client-side. The crawler calls
the same endpoint:

```
 GET /spapi/jobapi/search?Keywords=…&Limit=50&Offset=0&SortBy=1
    │  headers               appid + systemid, the ids the site's front-end sends
    │  age filter            NAUKRIGULF_MAX_AGE_DAYS (7)
    ▼
 title, employer, location, description, posted date — all in one response
    │
    ▼
 the same filters, store and delivery as every other source
```

That one response carrying the employer **and** the description is what makes
this the cheapest source in the project: four keywords cost four requests, and
no posting is ever opened. Tanqeeb needs a request per card to recover the same
employer name; Wuzzuf needs a half-megabyte page per job.

Searches name no country. The endpoint accepts a `Location`, but running every
keyword against every Gulf market multiplies requests to find what one
unfiltered, newest-first page already contains — so the crawler asks for
everything and lets `FILTER_LOCATIONS` decide. For the same reason the item's
`market` is left unset: a market tells the location filter that the search
already settled where the job is, which is true of a per-country site like
Tanqeeb and false here.

### Recruitera

Recruitera is the ATS behind about eighty employers' career pages — Paymob,
Fawry, Banque Misr, ElSewedy, Hassan Allam, Seoudi, Wuzzuf itself. Their pages
are drawn from a public JSON API, and the crawler calls the same one:

```
 sitemap.xml                               every company career site (~80)
    │  extra sites           RECRUITERA_EXTRA_SITES, for any the sitemap misses
    ▼
 /{site}/opportunities/filters             which categories it has open
    │  category filter       RECRUITERA_CATEGORIES — most sites stop here
    ▼
 /{site}/opportunities?category=…&cursor=… the tech jobs, 12 a page
    │  already-seen filter   stored items + the checked_urls ledger
    │  age filter            days_since_posting > RECRUITERA_MAX_AGE_DAYS (7)
    │  cap                   RECRUITERA_MAX_DETAILS (60)
    ▼
 /{site}/jobs/{slug}                       country, city, date, full text
    │  country filter        RECRUITERA_COUNTRIES (EG) — the job's, not the company's
    ▼
 the same filters, store and delivery as every other source
```

**Option B, by design:** companies are not listed anywhere. A new employer on
Recruitera is crawled the day it appears in the sitemap, a foreign company's
Cairo role is kept, and an Egyptian company's Dubai role is dropped.

**Tech** is Recruitera's own category: `IT/Software Development` and
`Engineering - Telecom/Technology`. `FILTER_TECH` and `FILTER_TITLE` are not
applied — they would narrow it to the Laravel/Node stack — but
`RECRUITERA_FILTER_TITLE` does the same for this source if wanted. The
seniority, ineligible and blocked-location rules still apply as everywhere.

**Local, so not remote-only.** Most of these jobs are hybrid or on-site in
Cairo, which is the point; `RECRUITERA_REMOTE_ONLY=true` reverses that.

**Never the same job twice.** A job's Recruitera id is its key in the store, so
a job delivered once is never queued again; the company + title fingerprint
catches the same role reposted under a new id, or already sent from LinkedIn or
Wuzzuf. Jobs the filters reject go in the `checked_urls` ledger and are never
opened again. All of it lives in the SQLite file on the data volume — without a
volume it is lost on redeploy, and the current jobs are sent again.

**Schedule.** In the container it is in the periodic lane as `recruitera:6`
and in `CRAWL_RUN_AT_START`: it runs as soon as the container starts, then
every 6 hours. If the network drops mid-run (five unanswered requests in a
row, or an unreachable sitemap) it exits 75 and the next pass retries it, every
`CRAWL_INTERVAL_MINUTES`; after `CRAWL_NOTIFY_AFTER_FAILURES` failures in a row
(default 3) you get a Telegram alert, and another once it recovers. A redeploy runs it again straight away, which costs requests
but sends nothing twice. A run is about 100–150 requests, paced 2.5 s apart —
five or six minutes, well under the API's limit of 60 a minute.

### Memory

Measured peak RSS of a real run, on the 256 MB host this is tuned for
(Windows, `--max-old-space-size=48` at the time; a Linux container runs lower,
and the cap itself is `NODE_HEAP_MB=128` now):

| Run | Peak RSS |
|---|---|
| `wuzzuf` alone, 10 pages | **79 MB** |
| `tanqeeb` alone, 1 page/keyword, 10 details | **87 MB** |
| both in one process | **109 MB** |

Neither parser builds a DOM of a whole page, which is what makes those numbers
possible. A Wuzzuf job page is 500-700 KB and a Tanqeeb search page ~450 KB;
loading them into cheerio cost 10-15 MB of RSS each and pushed a run to 137 MB.
Both now cut what they need out of the markup as strings, and Tanqeeb parses
each ~1 KB card on its own. The saving is roughly 5x per page.

Run the sources as **separate one-shot processes** rather than one `--watch`
process holding every source's high-water mark:

```bash
NODE_OPTIONS=--max-old-space-size=128 node src/index.js --once --source wuzzuf
NODE_OPTIONS=--max-old-space-size=128 node src/index.js --once --source tanqeeb
```

Each exits and returns its memory, so the host never holds more than one
source's peak. `MAX_DETAILS` and `MAX_PAGES` are the levers if it is still
tight.

### Cross-source dedup

Tanqeeb re-lists what LinkedIn and Wuzzuf already carry, and a company that
reposts gets a fresh id from the board each time. `UNIQUE (source,
external_id)` cannot see either case, so the store also holds a **fingerprint**
— a hash of normalized company + title — and treats a known fingerprint as
already delivered.

A posting with no company yields no fingerprint: titles alone collide
constantly ("Backend Developer"), and silently swallowing a real job is worse
than sending one twice.

---

## Hiring posts

The jobs source reads LinkedIn's guest job-search endpoint. Posts have no such
endpoint: `/search/results/content/` is behind the auth wall, and every free
way past it needs an account cookie — which breaks LinkedIn's User Agreement
and risks the account it belongs to.

What is reachable is the index. LinkedIn lets search engines crawl public posts,
so the `linkedin_posts` source searches an engine for post permalinks, then
reads each permalink from LinkedIn's own public page:

```
 search engine ── site:linkedin.com/posts "{keyword}" "{location}" hiring
      │
      ▼
 post permalinks ── activity id is the dedup key
      │
      ▼
 LinkedInGuestClient ── one paced request per post (capped by MAX_DETAILS)
      │                 auth-walled page? keep the search snippet
      ▼
 hiringIntent() ── employer advertising, or person job-hunting?
      │
      ▼
 the same filters, store and Telegram delivery as the jobs source
```

**This source aims somewhere else than the rest of the crawler.** The job
sources hunt remote work in the UK, the US and the EU; this one hunts local
hiring posts in Egypt. So it carries its own keywords, locations, schedule and
filters rather than inheriting `JOB_KEYWORDS` and the remote markets:

| | job sources | `linkedin_posts` |
|---|---|---|
| keywords | `JOB_KEYWORDS` (6) | `LINKEDIN_POSTS_KEYWORDS` (Laravel, Full Stack, Backend, Node.js, Software Engineer) |
| markets | Egypt + the Gulf, Worldwide/EMEA remote | `LINKEDIN_POSTS_LOCATIONS` (Egypt + 9 Greater Cairo areas) |
| remote | not required | not required — the market is local |
| schedule | `CRON_SCHEDULE`, hourly | `LINKEDIN_POSTS_CRON`, daily |
| cost | free | one billed query per keyword × location × page |

A source that sets its own `cron` is scheduled separately in `--watch`, and
everything without one keeps sharing `CRON_SCHEDULE`.

### Choosing an engine

| Engine | Setup | Reality |
|---|---|---|
| `serper` | A key from [serper.dev](https://serper.dev) | Google's index as a JSON API. ~$1 per 1,000 queries, 2,500 free to start. **The only one that answers a datacentre address**, which is why it is the default |
| `searxng` | The container in `docker-compose.yml` | Free, and yours — but it has to run beside the crawler, and it still scrapes upstream engines that block cloud addresses |
| `duckduckgo` | None | Serves an "anomaly" page instead of results to most non-browser addresses. Try it, but expect `blocked: yes` |

Bing is deliberately not an option: scraped without a session it answers HTTP
200 with results for an unrelated query, which is worse than a block because it
looks like a successful empty crawl.

### What one query a day costs

One keyword × one location × one page × one run a day is about **30 Serper
credits a month** — the free 2,500 last years. Every extra keyword, location or
page multiplies that, so widening the search is the thing to think twice about.

The defaults are 3 keywords × 11 places = **33 queries a run**, about 1,000 a
month. Four controls keep that in check:

| Setting | Default | What it does |
|---|---|---|
| `SERPER_DAILY_CAP` | `100` | Billed queries a day, across `linkedin_posts` and `x_posts`, counted in `TZ`. A source that reaches it stops, delivers what it found, and sends one alert. `0` = no cap |
| `SERPER_DELAY_MS` | `1000` | Serper's own pace. It used to inherit `REQUEST_DELAY_MS` (15 s, tuned for LinkedIn), which made a run wait ~8 minutes for nothing |
| `LINKEDIN_POSTS_QUERY_MODE` | `pairs` | `grouped` puts up to `LINKEDIN_POSTS_GROUP_SIZE` (6) places in one query as an OR group — `"Backend" ("Egypt" OR "Cairo" OR …)` — so 33 queries become 6 |
| `LINKEDIN_POSTS_COLD_AFTER` / `_COLD_EVERY_DAYS` | `5` / `3` | A query that found no fresh post on 5 runs in a row is asked every 3 days instead of daily, until it finds one. `0` turns it off |

The `X_POSTS_` versions of the last two tune `x_posts` the same way.

**Measuring grouped against pairs.** Grouped is not the default yet: an OR
group of *phrases* returned nothing (below), and an OR group of *places* has
not been measured. Every query's yield is recorded, so the comparison is
`node src/index.js --stats` — Serper usage, then each query's `hits` (fresh
posts found) and `misses` (empty runs in a row) — read after a week of each
mode. Switch when grouped finds about as many posts for a sixth of the cost.

**Alerts.** Running out of credits, a rejected key, the daily cap, and a
balance under `SERPER_LOW_BALANCE` (default `300`, read from Serper's free
account endpoint once a run) each send one Telegram message a day — to the
channel, or to `TELEGRAM_ALERT_CHAT_ID` if set. `ALERTS_ENABLED=false` turns
them off. Before this, running out of credits looked like a quiet day: Serper
answers HTTP 400 with the reason, and the crawler read that as "no results".
`/healthz` shows `serper: {today, month, dailyCap}`.

### Hiring posts vs. job-seeking posts

A search for `Laravel Egypt hiring` returns both, and in a market where far
more people are looking for work than advertising it, the seekers are the
majority of what comes back. The word "hiring" does not separate them — a
seeker writes "any hiring managers, please DM me" using exactly the vocabulary
of the employer they are addressing.

**This cannot be done in the query.** Google's operators are available through
Serper, and measurement showed both of the obvious tricks are worse than
useless here:

- an OR group of employer phrases (`"we are hiring" OR "we're hiring" OR …`)
  returned **zero** results
- a single negative term, `-"#OpenToWork"`, took a query that answered with
  ten results down to **zero**

Both are exact-phrase matches against pages Google indexed by its own rules; on
a corpus this narrow they match nothing and eliminate everything. So the query
stays plain and `hiringIntent()` in `filters.js` decides afterwards, reading
the post body that enrichment fetched rather than whatever phrasing Google
happened to index.

It reads **grammatical voice — who wants whom**, in three stages:

1. **Disqualifiers.** `#OpenToWork`, `#OpenForWork`, and first-person-singular
   statements about wanting work — "I am looking for", "بدور على", "خبرتي".
   These reject outright rather than scoring, because a seeker's post is
   *addressed to* employers and is therefore full of employer vocabulary.
2. **Scoring.** Employer phrasings ("we are hiring", "send your CV",
   "مطلوب", "نبحث عن") against weaker seeker ones ("fresh graduate",
   "any leads", "خريج"). More seeker than employer means seeking.
3. **Ties go to the employer**, and anything that said neither way is
   `unclear`.

`LINKEDIN_POSTS_HIRING_STRICT` is **on**, which drops `unclear` too: a
`site:linkedin.com/posts laravel` search returns as much Laravel *content* —
release notes, war stories, package announcements — as Laravel hiring, and all
of it passes the tech filter.

Two things that look like signals and are not:

- **`#Hiring` is worthless.** Seekers tag it to reach recruiters as often as
  employers tag it to be found.
- **"فرصة عمل" ("job opportunity") is worthless.** It reads like an
  advertisement and mostly is not — "بدور على فرصة عمل جديدة" is a seeker.
  A term both sides use scores for the wrong one half the time.

### Where the post is

Every other source knows the market it searched and says so through `market`,
which settles the question. A search engine only *biases* towards a market: a
search naming Egypt returns "Senior Laravel Developer. Location: Jordan" in
first place. So this source sets no `market`, and its location filter works the
other way round:

- the post names an Egyptian place → keep
- it names somewhere in `LINKEDIN_POSTS_EXCLUDE_LOCATIONS` → drop
- it names nowhere → **keep**, on the strength of the search

That last rule is not laziness. Most Egyptian hiring posts never write the word
"Egypt" — a Cairo company advertising to Cairo writes "Location: Remote" and
leaves the rest to context. Demanding the word threw away the real vacancies
and kept nothing.

The queried location is still stored for the message, but the filter ignores
it: it would otherwise be confirming that a search for Egypt was a search for
Egypt.

### Freshness

`LINKEDIN_POSTS_TIME_WINDOW` is `w`, not `d`, and not by preference —
**`qdr:d` returns zero results for this search.** A niche this narrow is not
indexed daily. The run still happens daily; dedup is what makes the week-wide
overlap free, so each run delivers only what was indexed since the last one.

`LINKEDIN_POSTS_MAX_AGE_DAYS` (default **3**) is the real freshness cut, and an
exact one. An engine dates a result by when it indexed the page, so its week-wide
window still returns posts from last December — but the **activity id in every
permalink encodes when the post was written** (the top bits are the timestamp,
the same trick Twitter uses), so age is read from the post itself, not the
engine. The week-wide engine window stays for recall; this drops the stale
majority it returns.

Three days, not one, because this is a search-index route: **Google indexes a
post two to three days after it is written**, so a one-day cut here is empty
almost always. For genuinely same-day posts, use the cookie route
([Hiring posts on X](#hiring-posts-on-x)), which reads X's live search and
defaults to a one-day cut.

The cut also bounds one failure mode: cross-run dedup lives in the SQLite store,
and on a host with no persistent disk (see [The volume matters](#the-volume-matters))
that store is wiped on restart, so old links would re-send. The cut caps how far
back a reset can reach — three days here, one on the cookie route.

### Setup

```bash
LINKEDIN_POSTS_ENABLED=true
LINKEDIN_POSTS_ENGINE=serper
SERPER_API_KEY=<from serper.dev>
LINKEDIN_POSTS_KEYWORDS=Backend,Full Stack,Software Engineer
LINKEDIN_POSTS_LOCATIONS=Egypt,Cairo,New Cairo,Giza,Maadi,Dokki,Nasr City,Heliopolis,Sheikh Zayed,6th of October,Fifth Settlement
LINKEDIN_POSTS_COUNTRY=eg
LINKEDIN_POSTS_CRON=0 7 * * *       # 07:00, in TZ (Africa/Cairo)
```

`SERPER_API_KEY` is a secret with no per-key spend cap beyond the credit pack,
and this repo commits `.env` on purpose — so treat the clone as key-bearing,
and rotate the key if it goes anywhere it should not.

Check it end to end without sending anything, for the price of one credit:

```bash
node src/index.js --once --dry-run --source linkedin_posts --pages 1
```

To see everything the query returned before the filters judge it, run the same
command with `FILTER_ENABLED=false`. The run summary's `rejected` column breaks
down by reason — `seeking-work`, `intent-unclear`, `location`, `tech` — which
is the feedback signal for tuning the phrase lists.

### Running it separately

This source is billed per query and wants a daily rhythm, so it is separable
from the rest of the crawl. Three ways, depending on what is running it:

```bash
npm run posts            # one crawl, then exit — for an external scheduler
npm run posts:watch      # stays up, crawls on LINKEDIN_POSTS_CRON (daily)
npm run posts:dry-run    # one crawl, store nothing, send nothing
```

And the other half, for when the posts crawl is its own process:

```bash
npm run jobs             # everything except linkedin_posts, hourly
```

`--source` and `--exclude` are what those scripts use, and both apply to the
scheduler as well as to a one-shot run — which is what makes
`--watch --source linkedin_posts` a standalone daily crawler rather than the
whole crawl with a filter on the end. Naming a source that does not exist
fails immediately rather than reporting a clean, empty crawl.

**Pick one.** `npm start` already schedules the posts source daily inside the
main process, so running `npm run posts:watch` beside it crawls twice a day
rather than once. Use `npm run jobs` in place of `npm start` when the posts
crawl has its own process. Dedup means the worst case is a wasted query, never
a duplicate message.

In Docker the split is not these commands but the lanes in
`docker-crawl-loop.sh` — `CRAWL_SOURCES` every pass, `CRAWL_DAILY_SOURCES` daily.
See [Running in Docker](#knobs). To run *only* the posts crawl in a container:

```bash
CRAWL_SOURCES=""                      # empty means nothing hourly
CRAWL_DAILY_SOURCES="linkedin_posts"
CRAWL_DAILY_AT="07:00"
```

On a platform with its own scheduler — a Render cron job, a Kubernetes
CronJob, a host crontab — skip both and run the one-shot command on a daily
trigger:

```bash
30 3 * * *  cd /app && node src/index.js --once --source linkedin_posts
```

### SearXNG setup

Only needed for `LINKEDIN_POSTS_ENGINE=searxng`.
`docker compose up -d searxng` writes a `settings.yml` into the
`searxng-config` volume on first boot. Two edits are needed, then restart it:

```yaml
search:
  formats: [html, json]   # the crawler reads JSON; HTML only is the default

server:
  limiter: false          # the bot limiter otherwise blocks your own crawler
```

```bash
LINKEDIN_POSTS_ENGINE=searxng
SEARXNG_URL=http://searxng:8080     # http://localhost:8080 when not in compose
SEARXNG_SECRET=<any long random string>
```

---

## Hiring posts on X

The same hunt as [Hiring posts](#hiring-posts) — Laravel hiring in Egypt, same
keywords, locations and filters unless given their own — on X, by two routes.
Both are off by default.

| | `x_posts` | `x_session` |
|---|---|---|
| How | Serper searches `site:x.com` | X's own `SearchTimeline` API, signed in by cookie |
| Cost | One Serper credit per query | Free |
| Recall | Low: Google indexes a small share of X | Everything X search finds |
| Text | The search snippet (X shows logged-out clients nothing) | The full post |
| Risk | None | **Against X's terms.** The account can be locked |
| Schedule | Daily: `X_POSTS_CRON` / `CRAWL_DAILY_SOURCES` | Every 6 h: `X_SESSION_CRON` / `CRAWL_PERIODIC_SOURCES` |

Both report a post under its status id, and the store's repost check compares
posts across sources, so a post found by both is sent once.

### `x_posts`

```bash
X_POSTS_ENABLED=true        # uses SERPER_API_KEY from the LinkedIn posts setup
```

Expect it to be quiet. Google indexes few X posts, and one that names both the
stack and the country is rarer still: a month-wide `site:x.com laravel hiring`
returned ten results when this was built, the same search with `"Egypt"`
none. Widen `X_POSTS_QUERY` or `X_POSTS_LOCATIONS` for more; the location
filter still reads each post.

### `x_session`

**Use a throwaway X account, never your own.** X locks accounts it thinks are
automated, and the `auth_token` cookie is full control of the account —
posting, DMs, settings, past 2FA. Keep it in the host's secret settings or your
shell, never in a committed file (`.env` is tracked in this repository).

1. Log in to the throwaway account at x.com.
2. DevTools → Application → Cookies → `https://x.com`: copy `auth_token` and `ct0`.
3. Set `X_SESSION_ENABLED=true`, `X_AUTH_TOKEN` and `X_CT0`.
4. `node src/index.js --once --dry-run --source x_session`

It is built to break loudly, since every failure here would otherwise look like
a search that found nothing:

| The run says | Fix |
|---|---|
| `X rejected the session` | Cookie expired or account locked: copy fresh cookies |
| `X answered 404 for SearchTimeline query id` | X rotated it: copy the id from the `SearchTimeline` request in DevTools → Network on x.com/search into `X_SEARCH_QUERY_ID` |
| `features cannot be null` | Set `X_SESSION_FEATURES` to a JSON object including the flags X lists |
| `Could not build x-client-transaction-id` | X changed its page: `npm update x-client-transaction-id` |
| `X rate-limited the account` | Lower `X_SESSION_MAX_REQUESTS` (default 10 per run) |

Requests are paced 8 s apart (`X_SESSION_DELAY_MS`) and capped per run, far
under X's ~50 searches per 15 minutes. `since:` is added to the query from
`X_SESSION_TIME_WINDOW_DAYS` (1; 2 at startup), and `X_SESSION_MAX_AGE_DAYS`
(1) is the exact cut on top of it — read from each post's `created_at` or its
status id — so only the last day's posts are delivered.

This cookie route defaults to a **one-day** cut because it reads X's live search
and returns minutes-old posts. The Serper routes (`x_posts`, `linkedin_posts`)
default to **three days**, since Google indexes a post 2-3 days late and a
tighter cut there is empty almost always. Both bound how far back the dedup
store — which resets on a host with no persistent disk — can re-send.

---

## Layout

```
src/
  index.js              CLI + scheduler
  config.js             .env → typed config
  logger.js             console + JSON lines to logs/crawler.log
  decisions.js          reads the decision log for --rejected, /decisions, /rejected
  health.js             status page + job endpoints
  jobs.js               one-at-a-time crawl queue, shared with the scheduler
  windows.js            "last N hours" → each source's own time window
  store.js              SQLite: dedup, delivery status, retries
  pipeline.js           orchestration: fetch → store → deliver
  formatter.js          item → Telegram HTML
  telegram.js           Bot API client
  errors.js             BlockedError, Telegram rate-limit / permanent errors
  filters.js            tech / location / seniority / hiring-intent / contact rules
  dates.js              "3 days ago" -> an absolute date
  text.js               term matching shared by filters, score and regions
  region.js, gulf.js    Egypt / Saudi & UAE / Worldwide / Freelance per job
  extract.js            years of experience and salary from the posting
  score.js              the 0-100 fit score
  mutes.js              muted companies and title words
  topics.js             a forum topic per region
  digest.js             the digest bundle
  breakage.js           a source gone quiet
  weekly.js             Friday's summary
  sources/
    index.js            source registry
    linkedinClient.js   paced HTTP with backoff
    jobCardParser.js    HTML → items (the part LinkedIn can break)
    jobDetailParser.js  description + seniority level
    linkedinJobs.js     keyword × location × page crawl
    searchEngines.js    Serper / SearXNG / DuckDuckGo adapters
    postSearchParser.js DuckDuckGo HTML → results
    linkedinPostParser.js post permalink → author, body, date
    linkedinPosts.js    hiring posts, found via a search index
    wuzzuf.js           Wuzzuf, crawled through its sitemap
    wuzzufParser.js     job page -> item (no hashed selectors)
    tanqeeb.js          aggregator search, carries Bayt listings
    tanqeebParser.js    search card -> item
    naukrigulf.js       Gulf jobs, through the site's own JSON endpoint
    naukrigulfParser.js search response -> item
    abroadFit.js        jobs abroad: lanes, language, region locks, CV fit score
    abroad.js           the shared judge for every abroad source
    baseSource.js       own pace, checked ledger, rotation for the ported sources
    cvStack.js          the CV's stack and level
    freelance.js        the freelance matcher (English + Arabic stack)
    remoteFeeds.js, jobgether.js, sponsorBoards.js, hnHiring.js, jobApis.js,
    jobright.js, relocateMe.js, drjob.js, freelanceBoards.js, careerSites.js,
    linkedinWorldwide.js, glassdoor.js   the sources ported from the Android app
data/crawler.sqlite     created on first run
logs/crawler.log        JSON lines
```

---

## Legal note

LinkedIn's User Agreement prohibits automated scraping. This project uses only
the public, unauthenticated guest endpoint that serves logged-out visitors — no
login, no session cookie, no account that can be banned — at a low hourly rate
with pacing and exponential backoff.

The posts source stays on the same footing: it reads a search engine's index
and the same public post pages a logged-out visitor sees. No cookie is sent, so
there is no account to restrict — which is exactly why it does not use the
Voyager API or a `li_at` cookie, the two common shortcuts to real post search.

That is the lowest-risk approach that still works, but it is not risk-free: the
endpoint can start returning 429 or 999 at any time. The crawler fails soft — it
logs, keeps whatever it already parsed, and tries again on the next run. If you
need a supported guarantee, use a paid provider or LinkedIn's official partner
APIs.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Crawler is disabled` | `CRAWLER_ENABLED=true` in `.env` |
| `POST /run` answers `401` | `API_TOKEN` is set and the `Authorization: Bearer` header did not match — or it is unset and the call is not from localhost |
| `POST /run` answers `429` | More than ten jobs are already waiting. Something is calling far faster than a crawl can run |
| `GET /jobs/<id>` answers `404` | Unknown id, or the process restarted — job history is in memory |
| Job `status: "failed"`, `Crawler is disabled` | `CRAWLER_ENABLED=true` in `.env`; the switch applies to queued jobs too |
| Runs fine, nothing in Telegram | `TELEGRAM_ENABLED`, token, and chat id; bot must be a channel **admin**. Check `--stats` for `skipped` rows |
| `parsed: 0` every run | `LINKEDIN_TIME_WINDOW=r3600` only matches the last hour — try `r86400`, or broaden keywords |
| `parsed` high but `matched: 0` | Filters are too tight. Read the `rejected` column to see which rule fired, then loosen that env list |
| Lots of `blocked: yes` | Raise `REQUEST_DELAY_MS`, lower `LINKEDIN_MAX_PAGES`, or cut the keyword list |
| `Telegram rejected the message permanently` | Usually a bad chat id or the bot was removed from the channel. Row is parked at the attempt ceiling; fix config, then `--resend <id>` |
| Posts: `DuckDuckGo served an anomaly page` | That address looks automated to DuckDuckGo. Switch `LINKEDIN_POSTS_ENGINE` to `searxng` |
| Posts: `SearXNG did not return JSON` | Set `search.formats: [html, json]` in its `settings.yml` and restart the container |
| Posts: `blocked` with HTTP 429 from SearXNG | `server.limiter: false` in its `settings.yml` — the limiter is blocking your own crawler |
| Posts: `parsed: 0` | Index lag. Widen `LINKEDIN_POSTS_TIME_WINDOW` to `m` or empty, and check the query in `LINKEDIN_POSTS_QUERY` returns anything in a browser |
| Wuzzuf `parsed: 0`, log says "older than the age limit" | Working as configured — nothing in the backlog is recent. Raise `WUZZUF_MAX_AGE_DAYS`, or wait for the ledger to work through the old entries at `WUZZUF_MAX_DETAILS` a run |
| Wuzzuf `blocked: yes` with no HTTP status | A fetch timeout, not a throttle — its pages are 500-670 KB. Raise `WUZZUF_TIMEOUT_MS`, or lower `WUZZUF_MAX_DETAILS` |
| Wuzzuf re-opens the same pages every run | The `checked_urls` table is not persisting — check `DATABASE_PATH` is on a mounted volume |
| A job arrives twice from two sources | Its posting has no company on one of them, so no fingerprint could be built. Expected, and rare |
| Suddenly `parsed: 0` after months | LinkedIn changed its markup — `src/sources/jobCardParser.js` is the only file to touch |
