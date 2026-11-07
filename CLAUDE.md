# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Read `AGENTS.md` too: it holds the operational detail that is easy to get wrong
(env-value semantics per layer, deployment shape, throttling budgets, memory limits,
which sources are live). This file is the architecture tour; it does not repeat that.

## Commands

No build, lint, bundler or typecheck step exists. `npm test` is the only gate.

- `npm install` — dependencies (Node >= 22.5; ESM, `node:sqlite`, global `fetch`).
- `npm test` — all tests (`node --test`, ~2 s, fully offline: parsers run against inline
  markup, the store against a temp DB).
- `node --test test/filters.test.js` — one test file.
- `npm run once` / `npm run dry-run` — one crawl; dry-run stores nothing and sends nothing.
- `node src/index.js --once --no-send` — store, but don't post to Telegram.
- `node src/index.js --once --source wuzzuf` — one source (`--exclude <key>` for the inverse).
- `npm start` — `--watch`: resident, crawls on `CRON_SCHEDULE`. `npm run jobs` / `npm run posts`
  split the crawl into the free lane and the paid one; `npm run x` runs the X sources.
- `node src/index.js --stats` — stored counts by status. `--resend <id>` re-sends one row.
- `node src/index.js --help` — full flag list and the HTTP API.
- Tests import `src/config.js`, which loads `.env` through dotenv, so re-run `npm test`
  after editing `.env`.

## Architecture

### The flow

`src/index.js` parses flags and picks a mode (`--once` / `--watch` / `--serve`), then hands
work to `JobQueue` (`src/jobs.js`), which runs one crawl at a time — the scheduler, the
Telegram bot and `POST /run` all enqueue into the same queue, because the host is sized for
a single crawl's peak.

`Pipeline.run()` (`src/pipeline.js`) is the whole crawl: for each enabled source,
fetch → `evaluate()` (`src/filters.js`) → `Store.insert()` → format → send. It is sequential
and paced deliberately; delivery is per item so one throttled message never blocks or
duplicates the rest. A `BlockedError` (`src/errors.js`) carries whatever was already parsed,
so a block delivers partial results instead of discarding the pass.

### Source contract

The pipeline never names a concrete source. A source is any object with `key`,
`isEnabled()` and `fetch(opts)`, optionally `useStore(store)` (for sources like Wuzzuf that
list everything every run and need a high-water mark). Adding one means writing that class
and appending it to `buildSources()` in `src/sources/index.js` — filtering, storage, dedup,
retry and formatting then apply unchanged.

Each source is split in two: the crawl/pagination logic (`wuzzuf.js`) and a pure HTML/JSON
parser (`wuzzufParser.js`). The parsers are the part a site can break, and they are what the
tests cover. `linkedinClient.js` is the shared paced HTTP client with backoff;
`searchEngines.js` adapts Serper / SearXNG / DuckDuckGo; `xShared.js` / `xSessionClient.js`
back the two X sources.

### Dedup

`src/store.js` is SQLite with two layers: `UNIQUE (source, external_id)` is the hard
guarantee that the same posting from the same board is one row, and a `fingerprint`
(normalized company + title) catches the same job arriving from a second source. Hiring
posts can't use either — the author is the "company" and a repost gets a new activity id —
so `src/postSimilarity.js` compares the ad text instead. Full rows are stored, not just
fingerprints, so a retry after a Telegram outage can still send an item whose time window
has rolled past; `status` + `send_attempts` drive that.

### Cross-cutting modules

- `src/config.js` — the single `.env` boundary, ~1100 lines of typed defaults. Empty env
  value means "use the code default", not "empty" (see `AGENTS.md`).
- `src/filters.js` — `evaluate()` runs a fixed gate order and returns a rejection reason.
  Keywords only widen the *search*; filters decide delivery.
- `src/windows.js` — "last N hours" into each source's own vocabulary (LinkedIn's `f_TPR`
  seconds vs. a search engine's coarse `d`/`w`/`m` bucket).
- `src/dates.js` — "3 days ago" into an absolute date, for the three sources that need it.
- `src/text.js` — `fold` / `containsTerm` / HTML helpers, shared so ranking modules need
  not import the filter gates.
- `src/region.js`, `src/gulf.js` — each job's region (Egypt / Gulf / Worldwide /
  Freelance); its `#tag` ends every message and `src/topics.js` maps it to a forum topic.
- `src/extract.js`, `src/score.js` — years asked and salary; the 0–100 fit score.
  `src/sources/abroadFit.js` judges and scores jobs abroad; `src/sources/abroad.js` is
  the shared judge the worldwide sources call.
- `src/digest.js`, `src/weekly.js`, `src/breakage.js` — run by `Pipeline.housekeeping()`
  at the end of every non-dry run (no resident process needed). `src/mutes.js` +
  the bot's `/mute_*`, `/up`, `/down` feed the filters and the score.
- `src/health.js` — status page, `/healthz`, `/jobs`, `POST /run`, for `--watch` / `--serve`.
- `src/heartbeat-server.js` — the minimal `$PORT` listener for Docker's sequential mode,
  where nothing is resident; it reads the loop's heartbeat file and owns no crawler state.
- `src/bot.js` — Telegram commands. The bot never crawls: it drops a request file that
  `docker-crawl-loop.sh` picks up between sources.

### Deployment shape

Docker runs `docker-crawl-loop.sh`: one short-lived `node --once --source X` process per
source, plus the heartbeat server. This is for memory, not tidiness — a single all-sources
process OOMs on a 256 MB host. Don't collapse it into one process, and don't run `--watch`
on a small instance.
