# AGENTS.md

Crawls job boards and hiring posts, filters them, dedups in SQLite, sends new ones to a
Telegram channel. Node ≥22.5 ESM (`node:sqlite`, global `fetch`) — no build, lint,
typecheck or bundler step exists; `npm test` is the only gate. `CLAUDE.md` has the
architecture tour; this file is the operational detail that is easy to get wrong.

## Commands

- All tests: `npm test` (= `node --test`, ~78 tests, ~2 s, offline — parsers are tested
  against inline markup, the store against a temp DB).
- One test: `node --test test/filters.test.js`.
- Parser fixtures: `fixtures/<parser>/<case>.*` plus `<case>.expected.json` are checked by
  both ports (`test/fixtures.test.js` and the Android app's `FixturesTest`). A parser fix
  adds or updates a fixture there, so the Node and Kotlin parsers cannot drift apart; a
  known disagreement goes under `"skip": {"node"|"kotlin": {field: reason}}`, never silently.
- One source, no Telegram: `node src/index.js --once --no-send --source wuzzuf`
  (add `--dry-run` to store nothing either).
- `node src/index.js --help` is the flag list: `--limit --pages --keyword --location
  --hours --exclude --stats --resend`, plus the HTTP API of `--watch` / `--serve`.
- Run `npm test` after editing `.env`: tests import `src/config.js`, which loads `.env`
  through dotenv.

## Empty env values mean different things per layer

1. In `src/config.js` empty means "use the code default" — `list('')`, `bool('')` and
   `int('')` all return their fallback. `JOB_LOCATIONS=` in `.env` therefore means the
   default seven countries, **not** no markets. An empty list can only be written as a
   value that trims away (a single space).
2. `docker-crawl-loop.sh` is the opposite: lane vars use `${VAR-default}`, so
   set-but-empty is "no sources in this lane" (deliberate, see its header comment).
3. The Dockerfile and `docker-compose.yml` set six keys to `""` — `FILTER_REMOTE_ONLY`,
   `JOB_LOCATIONS`, `JOB_REMOTE_LOCATIONS`, `FILTER_LOCATIONS`,
   `LINKEDIN_POSTS_KEYWORDS`, `LINKEDIN_POSTS_LOCATIONS` — to mask stale values from an
   older `.env` baked into an image. dotenv never overwrites an existing variable, so
   **inside a container those `.env` values are ignored and the code defaults win.** A
   setting that "has no effect" is usually masked this way: check the Dockerfile `ENV`
   block, then compose `environment:`, then the platform's own env panel.
4. The masking bites in practice: the file currently sets `FILTER_REMOTE_ONLY=true`,
   eight `JOB_REMOTE_LOCATIONS` and a custom `FILTER_LOCATIONS`, all three masked in the
   container (remote-only off, Worldwide/EMEA, the default list). A local run therefore
   searches something different from the hosted service — check which layer wins before
   trusting a local repro of a hosted problem.

## Deployment

- The hosted service runs the image: `CMD docker-crawl-loop.sh` (one short-lived
  `node --max-old-space-size=$NODE_HEAP_MB src/index.js --once --source X` per source)
  plus `heartbeat-server.js` (bot poller + health port). Pushing `main` is the deploy;
  `.env` is tracked and part of the deployed config.
- `.env` holds live credentials (bot token, Serper key) and is deliberately *not*
  dockerignored, because platforms that generate their own image read only `COPY . .`.
  Never echo its contents into responses or logs.
- Dedup DB path is `DATABASE_PATH` — deployed as `/tmp/crawler.sqlite`, wiped on
  restart, so every restart re-sends matches; the status page flags the condition as
  `databaseLooksFresh`. On Windows `/tmp/...` lands on `<current drive>:\tmp\...`.
- One `getUpdates` poller per bot token: the heartbeat server is one, so a second
  deployment or a local copy produces `409 Conflict … terminated by other getUpdates
  request`. `TELEGRAM_COMMANDS_ENABLED=false` (current setting) stops this service's
  poller; sending is unaffected.
- `/jobs` asks how far back to crawl (buttons 1h…1 week, a typed number, or `/jobs 10`;
  whole hours 1–168, LinkedIn Jobs only). The bot writes `hours/linkedin_jobs` beside the
  queue entry (two waiting requests keep the larger value), and `docker-crawl-loop.sh`
  passes it on as `--hours`, i.e. `f_TPR=r<hours×3600>` for that one run. "Default window"
  and scheduled runs keep `LINKEDIN_TIME_WINDOW`.
- `linkedin_posts` is on — the only source billed per query (Serper, 33 queries a run =
  3 keywords × 11 locations in the default `pairs` mode, 6 in `grouped`; `.env`'s lists
  are empty on purpose, so the code defaults apply). Container: the daily lane at 07:00
  (`CRAWL_DAILY_SOURCES`/`CRAWL_DAILY_AT`), deliberately *not* in `CRAWL_RUN_AT_START`
  so a restart loop doesn't bill per boot — the loop script's own defaults now agree;
  local `--watch`: `LINKEDIN_POSTS_CRON=0 7 * * *`. `x_posts` and `x_session` are off by
  default.
- Serper spend is capped in code (`SERPER_DAILY_CAP`, 100/day across both posts sources,
  counted in the store's `state` table) and paced by `SERPER_DELAY_MS` (1 s), not
  `REQUEST_DELAY_MS`. On the hosted `/tmp` database the count resets with the database,
  so the cap bounds a day, not a restart loop across wipes. A JSON-API 4xx (Serper's
  "Not enough credits" is a 400) is a `BlockedError` with `alert` set, not an empty
  answer; the pipeline turns `alert` errors and `source.takeAlerts()` into one Telegram
  message a day per kind. `--stats` prints usage and each query's yield
  (`src/sources/searchPlan.js`), which is how `grouped` is measured against `pairs`.

## Ported from the Android app

- The Android app (`android/`) and Node now share the ranking and delivery logic:
  regions, fit score, extract, CV fit for jobs abroad, digest, weekly summary, quiet-source
  alerts, mutes. The sources it had and Node lacked are in `src/sources/` on
  `BaseSource` (own `delayMs`, not `REQUEST_DELAY_MS`). The app's per-source WorkManager
  cadence is *not* ported: the rhythm comes from `CRAWL_PERIODIC_SOURCES` (container) or
  each source's `cron` (`--watch`), so a manual `--source X` / bot `/run` always runs.
- Seeding is per source: `<key>.seeded` in `state`, set after a clean run; a source with no
  rows and no flag seeds. On the hosted `/tmp` database every source seeds once per wipe.
- `BOARDS_GULF` (default on) widens Workable, Tanqeeb, Wuzzuf, Recruitera, Dr.Job and the
  ATS boards to Saudi Arabia and the UAE — more requests per run on those sources, and Gulf
  jobs must pass `gulf-local-only`. Set `BOARDS_GULF=false` to go back to Egypt only.
- Worldwide sources deliver only the best CV fits (`ABROAD_MIN_SCORE`,
  `ABROAD_MAX_PER_RUN`); the rest are stored `skipped` with `lower fit: N/100`.
- The weekly summary never sends from a database that has not recorded a week before
  (it starts counting instead), so the `/tmp` restart loop does not repeat it.
- `mostaql` / `khamsat` get a 202 bot challenge from datacenter IPs; they are not in the
  hosted lane. Don't try to get around the challenge.

## Memory

- Sequential mode exists for memory, not tidiness: one process holding every source's
  high-water mark is what kills a small host. An all-sources single process (`--once`
  with no `--source`) died of a V8 heap OOM at a 48 MB cap, peak RSS ~130 MB. Current:
  `NODE_HEAP_MB=128` on a 256 MB host. Per-source processes are the supported shape —
  don't "simplify" it into one process, and don't run `--watch` on a small instance.
- Items matched during a run are retained in memory (`stat.items = kept`,
  `src/pipeline.js`), so peak memory scales with matches.
- The bounded-retention work (`ITEM_MEMORY_BUDGET_MB`) was rolled back on purpose and
  lives on `backup/memory-retention` and the `worktree-declarative-sauteeing-floyd`
  branch. Do not restore it unprompted.

## Filters decide delivery, not keywords

`src/filters.js` `evaluate()` gate order: LinkedIn's own seniority field (`director` /
`executive` rejected; `mid-senior` is handed to the title rules) → blocked location →
muted company / title word (`muted-company`, `muted-title`) → title (`FILTER_TITLE`; a
source with `filters.matcher: 'freelance'` — mostaql, khamsat — uses the English+Arabic
stack matcher instead of title and tech) → ineligible (a residency phrase followed by an
Egyptian place does not count) → `FILTER_REMOTE_ONLY` → tech (`FILTER_TECH`) → location
→ `gulf-local-only` (market Saudi Arabia / UAE without relocation, sponsorship or
all-nationalities wording) → (posts only: hiring intent) → seniority terms (a
"Mid/Senior" range is removed first) → `experience` (`FILTER_MAX_YEARS`, off) → contact.
Before `evaluate()` the pipeline fills `yearsMin`/`salary` (`src/extract.js`); after a
pass it sets `region` and `score`. Adding a
`JOB_KEYWORDS` term only adds the search — every card is still dropped with
`reason: 'title'` unless some `FILTER_TITLE` term matches its title, and then by the tech
gate. Verified example: "Application Support Engineer" needed both `application support`
in `FILTER_TITLE` and `support` in `FILTER_TECH`.

- Every rejection returns `detail` (what fired) next to `reason`. Add a new gate with
  `return reject('<reason>', '<detail>')` so it shows up in the `decisions` table; read
  that table (`--rejected`, bot `/rejected`) before changing a filter.
- `item.remote` is set only for LinkedIn's remote searches (`f_WT=2`). With
  `FILTER_REMOTE_ONLY=true`, results from the local market searches survive only if their
  text says remote.

## Throttling

- LinkedIn 429/403/999 are retried, so `Throttled, backing off … attempt:1` lines are
  recoveries, not failures — and each one now slows the whole client, not just the retry:
  `linkedinClient.js` doubles the pace (`maxDelayMs` cap, 60 s), turns the remote's
  `Retry-After` into a cool-down every later request waits out, and gives both back after
  20 clean answers (`recoverySuccesses`). Five throttles in a run (`throttleBudget`)
  throw `BlockedError`: partial results are delivered and the next pass retries (a
  block during detail fetches stops the enrichment instead of discarding the pass —
  the un-enriched tail is retried next pass, whose window still covers it). The
  request *rate* is the trigger: 12/min was throttled continuously, so the pass is one
  page per keyword×market at `REQUEST_DELAY_MS=15000` (4/min; `LINKEDIN_MAX_PAGES=1`,
  `LINKEDIN_REMOTE_MAX_PAGES=1`). The search is Egypt only — 7 keywords × Egypt, Cairo,
  Giza, Maadi, Heliopolis, Obour, no remote-only or Worldwide searches — so 42 searches
  without rotation (7 per run with it, under 2 min before
  detail fetches; 21 searches took ~6 min). Knobs: `REQUEST_DELAY_MS`, `CRAWL_INTERVAL_MINUTES` (10 in the
  container; it was 30 when a pass ran ~20 minutes, because a pass that fills its
  interval never pauses and that drew 429s — raise it with the markets), `LINKEDIN_MAX_PAGES`
  (local markets), `LINKEDIN_REMOTE_MAX_PAGES` (remote markets).
- `LINKEDIN_ROTATE_LOCATIONS=true` (on in `.env`) searches one location per run, all
  keywords, so a run is 7 searches whatever `JOB_LOCATIONS` holds and a cycle is one run
  per location. The position lives in the store's `state` table
  (`linkedin_jobs:rotation_next`), because each container run is a fresh process; a
  blocked run does not advance it. `rotationWindow()` widens `f_TPR` to
  (locations + 1) × `LINKEDIN_ROTATE_INTERVAL_MINUTES` (default `CRAWL_INTERVAL_MINUTES`)
  when that beats `LINKEDIN_TIME_WINDOW`, so more than 5 locations at 10 min need no manual
  change. Runs with their own window or `--keyword`/`--location` search everything.
  Separately, postings already in the store are dropped before their detail page is
  fetched, which used to be repeated on every run the posting stayed in the window.
- `wuzzuf`, `tanqeeb` and `naukrigulf` block datacentre IPs (403 / pages with no jobs),
  which is why they are absent from the hosted schedule; they work from a residential IP.

## This workspace (Windows / PowerShell)

- `git push` reports exit 1 even when it succeeds (stderr banner) — confirm with
  `git ls-remote origin <branch>` instead of trusting the exit code.
