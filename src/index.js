#!/usr/bin/env node
import { parseArgs } from 'node:util';
import cron from 'node-cron';
import config from './config.js';
import logger from './logger.js';
import Pipeline from './pipeline.js';
import { formatMessage } from './formatter.js';
import { startHealthServer } from './health.js';
import { windowsForHours } from './windows.js';
import { JobQueue } from './jobs.js';
import SerperBudget from './sources/serperBudget.js';
import { YieldLedger } from './sources/searchPlan.js';
import { decisionFilters, formatReport } from './decisions.js';

const USAGE = `
LinkedIn jobs -> Telegram

Usage: node src/index.js [options]

  --once                Run one crawl and exit (default)
  --watch               Stay running and crawl on the cron schedule
  --serve               Stay running for POST /run only, with no schedule
  --hours <n>           Crawl the last n hours instead of the configured window
  --dry-run             Crawl and print only; store nothing, send nothing
  --no-send             Store new items but do not send them to Telegram
  --limit <n>           Stop after n items
  --pages <n>           Override the number of result pages per keyword/location
  --keyword <term>      Override the keyword list (repeatable)
  --location <name>     Override the location list (repeatable)
  --source <key>        Only run this source (repeatable): linkedin_jobs,
                        linkedin_posts, wuzzuf, tanqeeb, naukrigulf,
                        recruitera, workable, vertex, ats, ai_data,
                        x_posts, x_session
  --exclude <key>       Run everything except this source (repeatable)
  --resend <id>         Re-send one stored item by id, then exit
  --stats               Print stored item counts by status, Serper usage and
                        each posts query's yield, then exit
  --rejected [reason]   Print why items were rejected (counts, top titles,
                        latest rows), then exit. Combine with --since/--source
  --decisions <kind>    Same for duplicate | accepted | rejected | all
  --since <hours>       Window for --rejected / --decisions (default 24)
  --help                Show this message

HTTP API (--watch or --serve, needs PORT; --serve defaults to 3000):
  POST /run             Queue a crawl of the last 24 hours; answers a job id
                        ?hours=48   how far back (default 24)
                        ?wait=true  hold the connection until the job finishes
                        ?sources=linkedin_posts  &keywords= &locations=
                        ?limit= &pages= &dryRun=true &noSend=true
                        Auth: Bearer API_TOKEN, or localhost when unset
  GET  /jobs/<id>       One job: queued / running / done / failed, and results
  GET  /jobs            Recent jobs, newest first
  GET  /decisions       Decision log rows: ?decision=rejected|duplicate|
                        accepted|all &reason= &source= &since=<h> &limit=
  GET  /decisions/summary  Counts by reason/source, top rejected titles
  GET  /                Status: queue, last run, stored totals
  GET  /healthz         Liveness probe

Examples:
  node src/index.js --once --dry-run --limit 5 --pages 1 --keyword "Backend Developer"
  node src/index.js --once --hours 24
  node src/index.js --once --no-send
  node src/index.js --watch
  node src/index.js --serve

Splitting the crawl across processes (npm run jobs / npm run posts):
  node src/index.js --watch --exclude linkedin_posts    hourly, free sources
  node src/index.js --watch --source linkedin_posts     daily, the paid one
  node src/index.js --once  --source linkedin_posts     one posts crawl, then exit
  curl -X POST "http://localhost:3000/run?hours=24"
  curl "http://localhost:3000/jobs/<id>"
`;

const { values, positionals } = parseArgs({
  options: {
    once: { type: 'boolean', default: false },
    watch: { type: 'boolean', default: false },
    serve: { type: 'boolean', default: false },
    hours: { type: 'string' },
    'dry-run': { type: 'boolean', default: false },
    'no-send': { type: 'boolean', default: false },
    limit: { type: 'string' },
    pages: { type: 'string' },
    keyword: { type: 'string', multiple: true },
    location: { type: 'string', multiple: true },
    source: { type: 'string', multiple: true },
    exclude: { type: 'string', multiple: true },
    resend: { type: 'string' },
    stats: { type: 'boolean', default: false },
    rejected: { type: 'boolean', default: false },
    decisions: { type: 'string' },
    since: { type: 'string' },
    help: { type: 'boolean', default: false },
  },
  // Only for `--rejected <reason>`.
  allowPositionals: true,
});

if (values.help) {
  console.log(USAGE.trim());
  process.exit(0);
}

if (positionals.length > 1 || (positionals.length === 1 && !values.rejected)) {
  console.error(`Unexpected argument: ${positionals.join(' ')}. See --help.`);
  process.exit(1);
}

/**
 * The sources this invocation may run: `--source` narrows to a list,
 * `--exclude` removes from it, and neither means all of them.
 *
 * The two exist so the crawl can be split across processes. Most sources are
 * free to call and want the hourly schedule; the posts source is billed per
 * query and wants a daily one, and running it as its own command — its own
 * container, its own platform cron job — is the way to give it a lifecycle of
 * its own. `--exclude linkedin_posts` is then what keeps the main process from
 * crawling it a second time.
 *
 * @returns {string[]|undefined} undefined when nothing was narrowed, so the
 *   pipeline keeps its own notion of "every enabled source".
 */
function selectedSources() {
  if (!values.source?.length && !values.exclude?.length) return undefined;

  const known = Object.keys(config.sources);
  const named = [...(values.source ?? []), ...(values.exclude ?? [])];
  const unknown = named.filter((key) => !known.includes(key));

  // A typo here would otherwise be silent: the run crawls nothing at all and
  // reports it as a clean, empty crawl.
  if (unknown.length > 0) {
    console.error(`Unknown source: ${unknown.join(', ')}. Known: ${known.join(', ')}.`);
    process.exit(1);
  }

  const excluded = new Set(values.exclude ?? []);
  const chosen = (values.source?.length ? values.source : known).filter(
    (key) => !excluded.has(key),
  );

  if (chosen.length === 0) {
    console.error('Every source was excluded, so there is nothing to run.');
    process.exit(1);
  }

  return chosen;
}

const runOptions = {
  sources: selectedSources(),
  // --hours is the same override the HTTP endpoint applies, in the same
  // per-source spelling.
  timeWindows: values.hours ? windowsForHours(values.hours) : undefined,
  keywords: values.keyword,
  locations: values.location,
  pages: values.pages ? Number.parseInt(values.pages, 10) : undefined,
  limit: values.limit ? Number.parseInt(values.limit, 10) : undefined,
  dryRun: values['dry-run'],
  noSend: values['no-send'],
};

const pipeline = new Pipeline();

/**
 * Every crawl that is not a one-shot CLI run goes through this queue — the
 * scheduler's ticks included. Crawls share the pacing budget of one
 * rate-limited endpoint, so one queue is what keeps them from overlapping,
 * and it gives the schedule and the API the same job history.
 */
const jobs = new JobQueue((options) => runRequested(options));

/**
 * --serve is asked for explicitly, so it defaults to a port rather than
 * declining to start the way the PaaS health server does when PORT is unset.
 */
const servePort = () => Number.parseInt(process.env.PORT ?? '', 10) || 3000;

/** Each source's startup-only window, keyed by source. */
const startupTimeWindows = () =>
  Object.fromEntries(
    Object.entries(config.sources)
      .filter(([, settings]) => settings.startupTimeWindow !== undefined)
      .map(([key, settings]) => [key, settings.startupTimeWindow]),
  );

/**
 * The crawl's schedules, as [cron expression, source keys] pairs.
 *
 * Most sources share CRON_SCHEDULE, because they are free to call and want
 * the freshest listing. A source that sets its own `cron` does not: the posts
 * source is billed per query and reads an index that updates daily, so asking
 * hourly would buy nothing but charges. Sources on the same expression are
 * grouped into one tick, so they keep sharing a pacing budget.
 *
 * @returns {Array<[string, string[]]>}
 */
function schedules() {
  const groups = new Map();
  const selected = runOptions.sources;

  for (const [key, settings] of Object.entries(config.sources)) {
    if (!settings.enabled) continue;
    // --source / --exclude apply to the scheduler too, which is what makes
    // `--watch --source linkedin_posts` a standalone daily crawler.
    if (selected && !selected.includes(key)) continue;

    const expression = settings.cron || config.schedule.cron;
    groups.set(expression, [...(groups.get(expression) ?? []), key]);
  }

  return [...groups.entries()];
}

/** Live state, surfaced by the health endpoint in --watch mode. */
const state = {
  startedAt: new Date().toISOString(),
  // Every expression in play, not just the crawl-wide one: a source with its
  // own cron is otherwise invisible here, and "why has it not run" is exactly
  // what this endpoint gets asked.
  schedule: config.schedule.cron,
  schedules: () => Object.fromEntries(schedules()),
  timezone: config.schedule.timezone,
  databasePath: config.databasePath,
  running: false,
  lastRunAt: null,
  lastRunError: null,
  lastRun: null,
  totals: () => {
    try {
      return Object.fromEntries(pipeline.store.stats().map((row) => [row.status, row.count]));
    } catch (error) {
      return { error: error.message };
    }
  },
  /*
   * A host without a persistent volume wipes data/ on every deploy, which makes
   * the crawler re-send jobs it already delivered. An empty table on a process
   * that has been up for a while is the symptom.
   */
  // Billed queries today and this month, against the daily cap.
  serper: () => {
    try {
      const budget = new SerperBudget(pipeline.store);
      const { today, month, dailyCap } = budget.usage();
      return { today, month, dailyCap, balance: budget.lastBalance() };
    } catch (error) {
      return { error: error.message };
    }
  },
  databaseLooksFresh: () => {
    const totals = state.totals();
    const stored = Object.values(totals).reduce(
      (sum, value) => (typeof value === 'number' ? sum + value : sum),
      0,
    );

    return stored === 0 && process.uptime() > 300;
  },
};

/**
 * Perform one queued crawl — the API's jobs, the scheduler's ticks, and the
 * crawl that runs at startup all arrive here.
 *
 * `scheduled` and `startup` only label the job in its history; they are pulled
 * out so they never reach the pipeline as run options.
 *
 * @param {{hours?: number}} options  plus the usual run options
 * @returns {Promise<object[]>} per-source stats
 */
async function runRequested({ hours, scheduled, startup, ...options } = {}) {
  // The same master switch the CLI honours. A queued job must not be the one
  // way to crawl with CRAWLER_ENABLED=false.
  if (!config.enabled) {
    logger.info('Run skipped: crawler disabled');
    throw new Error('Crawler is disabled. Set CRAWLER_ENABLED=true to run it.');
  }

  state.running = true;

  try {
    const results = await pipeline.run({
      ...runOptions,
      ...clean(options),
      ...(hours ? { timeWindows: windowsForHours(hours) } : {}),
    });

    state.lastRunAt = new Date().toISOString();
    state.lastRun = results.map(({ items, ...rest }) => rest);
    state.lastRunError = null;

    return results;
  } catch (error) {
    state.lastRunError = error.message;
    throw error;
  } finally {
    state.running = false;
  }
}

/** Drop keys the caller left unset, so they do not shadow the CLI defaults. */
function clean(options) {
  return Object.fromEntries(Object.entries(options).filter(([, value]) => value !== undefined));
}

async function runOnce(overrides = {}) {
  if (!config.enabled) {
    console.log('Crawler is disabled. Set CRAWLER_ENABLED=true in .env to run it.');
    logger.info('Run skipped: crawler disabled');
    return;
  }

  logger.info('Crawl requested', {
    trigger: (process.env.CRAWL_TRIGGER || 'cli').trim(),
    sources: runOptions.sources ?? 'all enabled',
    dryRun: runOptions.dryRun,
    noSend: runOptions.noSend,
    hours: values.hours,
  });

  const results = await pipeline.run({ ...runOptions, ...overrides });

  state.lastRunAt = new Date().toISOString();
  state.lastRun = results.map(({ items, ...rest }) => rest);

  if (results.length === 0) {
    console.log('No enabled sources matched.');
    return;
  }

  printSummary(results);

  if (runOptions.dryRun) printDryRun(results);

  // A source the network cut short exits EX_TEMPFAIL, so docker-crawl-loop.sh
  // runs it again on the next pass instead of waiting out its interval. Only
  // a source that opts in by marking its error retryable: a paid source that
  // was blocked must still wait, or every pass would bill it again.
  const cutShort = results.filter((stat) => stat.retryable).map((stat) => stat.source);

  if (cutShort.length > 0 && !runOptions.dryRun) {
    console.error(`Cut short by the network, to be retried: ${cutShort.join(', ')}`);
    process.exitCode = EXIT_RETRY;
  }
}

/** sysexits.h EX_TEMPFAIL: "try again later". docker-crawl-loop.sh reads it. */
const EXIT_RETRY = 75;

function printSummary(results) {
  console.log('');
  console.table(
    results.map((stat) => ({
      source: stat.source,
      parsed: stat.parsed,
      matched: stat.matched,
      rejected: Object.entries(stat.rejected ?? {})
        .map(([reason, count]) => `${reason}:${count}`)
        .join(' ') || '-',
      stored: stat.stored,
      delivered: stat.delivered,
      blocked: stat.blocked ? 'yes' : 'no',
      error: stat.error ? stat.error.slice(0, 60) : '',
    })),
  );
}

/**
 * Show what would have been stored, plus one rendered message, so layout and
 * escaping can be checked without a real channel.
 */
function printDryRun(results) {
  let preview = null;

  for (const stat of results) {
    if (stat.items.length === 0) continue;

    console.log(`\nItems from ${stat.source} (dry run — nothing was written):`);
    console.table(
      stat.items.map((item) => ({
        externalId: item.externalId,
        title: (item.title ?? '').slice(0, 50),
        company: (item.company ?? '').slice(0, 30),
        location: (item.location ?? '').slice(0, 30),
        postedAt: item.postedAt ?? '',
      })),
    );

    preview ??= stat.items[0];
  }

  if (preview) {
    console.log('\nTelegram message preview:\n');
    console.log(formatMessage(preview));
  }
}

function printStats() {
  const rows = pipeline.store.stats();

  if (rows.length === 0) {
    console.log('No items stored yet.');
  } else {
    console.table(rows);
  }

  const budget = new SerperBudget(pipeline.store);
  const usage = budget.usage();
  const cap = usage.dailyCap > 0 ? ` of ${usage.dailyCap}` : ' (no cap)';
  const balance = budget.lastBalance();
  console.log(
    `\nSerper queries: ${usage.today}${cap} today, ${usage.month} this month.` +
      (balance === null ? '' : ` Last balance read: ${balance} credits.`),
  );

  /*
   * Which queries earn their cost: hits are fresh posts found over all runs,
   * misses the empty runs in a row. Comparing the two query modes is reading
   * this table before and after switching.
   */
  for (const key of ['linkedin_posts', 'x_posts']) {
    const report = new YieldLedger(pipeline.store, key, config.sources[key]).report();
    if (report.length === 0) continue;

    console.log(`\n${key} query yield:`);
    console.table(report.sort((a, b) => b.hits - a.hits));
  }
}

/** --rejected [reason] / --decisions <kind>: the decision log, read from the database. */
function printDecisions() {
  const filters = decisionFilters({
    decision: values.decisions ?? 'rejected',
    reason: positionals[0],
    source: values.source?.[0],
    since: values.since ?? '24',
    limit: values.limit ?? '25',
  });

  console.log(formatReport(pipeline.store, filters, { rows: filters.limit ?? 25 }));
}

/*
 * What this process is about to crawl with, in one line: the settings that
 * decide what is found and what is dropped. Long lists are counted, not
 * printed (the full values are logged at debug), and secrets never appear.
 */
const SECRET_KEY = /token|secret|password|cookie|bearer|apikey|api_key|^ct0$/i;

function redact(value, key = '', compact = false) {
  if (SECRET_KEY.test(key) && value) return '[redacted]';
  if (Array.isArray(value)) {
    return compact && value.length > 6
      ? `[${value.length} items: ${value.slice(0, 3).join(', ')}, …]`
      : value.map((entry) => redact(entry, '', compact));
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([name, entry]) => [name, redact(entry, name, compact)]),
    );
  }
  return value;
}

function logConfigSnapshot() {
  const selected = runOptions.sources;
  const sources = Object.fromEntries(
    Object.entries(config.sources).filter(
      ([key, settings]) => settings.enabled && (!selected || selected.includes(key)),
    ),
  );

  const snapshot = (compact) =>
    redact(
      {
        mode: values.watch ? 'watch' : values.serve ? 'serve' : 'once',
        enabled: config.enabled,
        sources,
        keywords: config.keywords,
        locations: config.locations,
        filters: config.filters,
        limits: config.limits,
        schedule: config.schedule,
        log: config.log,
      },
      '',
      compact,
    );

  logger.info('Config snapshot', {
    mode: values.watch ? 'watch' : values.serve ? 'serve' : 'once',
    crawlerEnabled: config.enabled,
    sources: Object.keys(sources),
    keywords: redact(config.keywords, '', true),
    locations: redact(config.locations, '', true),
    filters: redact(config.filters, '', true),
    limits: config.limits,
    schedule: config.schedule,
    logLevel: config.log.level,
    decisionLog: config.log.decisions,
  });
  logger.debug('Config snapshot (full)', snapshot(false));
}

async function main() {
  if (values.stats) {
    printStats();
    return;
  }

  if (values.rejected || values.decisions) {
    printDecisions();
    return;
  }

  if (values.resend === undefined) logConfigSnapshot();

  if (values.resend) {
    const id = Number.parseInt(values.resend, 10);
    pipeline.store.resetForResend(id);

    const sent = await pipeline.deliverOne(id);
    console.log(sent ? `Re-sent item ${id}.` : `Item ${id} was not sent — see the log.`);
    return;
  }

  if (values.serve) {
    // API only: no cron, no crawl at startup. For the case where something
    // else decides when to crawl — a cron job elsewhere, a button, a webhook.
    const server = startHealthServer(state, { queue: jobs, port: servePort() });

    if (!server) {
      console.error('Could not start the server.');
      process.exitCode = 1;
      return;
    }

    console.log(`Serving on port ${servePort()}. POST /run?hours=24 to crawl. Ctrl+C to stop.`);

    if ((process.env.API_TOKEN ?? '').trim() === '') {
      console.log('API_TOKEN is not set: only localhost may POST /run.');
    }

    const stop = () => {
      console.log('\nStopping…');
      server.close();
      pipeline.close();
      process.exit(0);
    };

    process.on('SIGINT', stop);
    process.on('SIGTERM', stop);
    return;
  }

  if (values.watch) {
    const groups = schedules();

    if (groups.length === 0) {
      console.error('No sources are enabled, so there is nothing to schedule.');
      process.exitCode = 1;
      return;
    }

    for (const [expression] of groups) {
      if (!cron.validate(expression)) {
        console.error(`Invalid cron expression: ${expression}`);
        process.exitCode = 1;
        return;
      }
    }

    for (const [expression, sources] of groups) {
      console.log(`Watching "${expression}" (${config.schedule.timezone}): ${sources.join(', ')}`);
    }
    console.log('Ctrl+C to stop.');

    logger.info('Scheduler started', {
      schedules: Object.fromEntries(groups),
      timezone: config.schedule.timezone,
    });

    // Satisfies PaaS health checks, which otherwise kill a process that binds
    // no port. No-op locally, where PORT is unset.
    const healthServer = startHealthServer(state, { queue: jobs });

    const tasks = groups.map(([expression, sources]) =>
      cron.schedule(
        expression,
        () => {
          // A tick queues a job like any API call does. If the previous crawl
          // is still running this one waits its turn rather than overlapping
          // it — but a tick is worth skipping entirely once anything is
          // already waiting, since the next tick covers the same window
          // anyway.
          if (jobs.waiting.length > 0) {
            logger.warn('A crawl is already queued; skipping this tick', { sources });
            return;
          }

          jobs.submit({ sources, scheduled: true }, { trigger: 'schedule' });
        },
        { timezone: config.schedule.timezone },
      ),
    );

    const shutdown = () => {
      console.log('\nStopping…');
      for (const task of tasks) task.stop();
      healthServer?.close();
      pipeline.close();
      process.exit(0);
    };

    process.on('SIGINT', shutdown);
    process.on('SIGTERM', shutdown);

    // Run immediately so --watch does not sit idle until the next tick, and
    // look further back than an hourly tick does: a restart loses the dedup
    // database on hosts without a volume, and downtime means missed hours.
    // Dedup still guarantees nothing is delivered twice.
    try {
      const { done } = jobs.submit(
        { timeWindows: startupTimeWindows(), startup: true },
        { trigger: 'startup' },
      );
      const job = await done;

      if (job.results) printSummary(job.results);
      if (job.error) console.error(`Startup run failed: ${job.error}`);
    } catch (error) {
      state.lastRunError = error.message;
      logger.error('Startup run failed', { error: error.message });
    }

    return;
  }

  await runOnce();
}

try {
  await main();
} catch (error) {
  logger.error('Fatal error', { error: error.message, stack: error.stack });
  console.error(error);
  process.exitCode = 1;
} finally {
  if (!values.watch) pipeline.close();
}
