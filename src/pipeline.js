import { randomUUID } from 'node:crypto';
import config from './config.js';
import logger from './logger.js';
import { BlockedError, TelegramPermanentError, TelegramRateLimitError } from './errors.js';
import { buildSources } from './sources/index.js';
import { evaluate } from './filters.js';
import { formatMessage } from './formatter.js';
import TelegramClient from './telegram.js';
import Store, { STATUS, toItem } from './store.js';
import { dayOf, SerperBudget } from './sources/serperBudget.js';
import { applyExtract } from './extract.js';
import { scoreOf, reloadLearned } from './score.js';
import { isAbroad, parseRegion, regionOf } from './region.js';
import { loadMutes } from './mutes.js';
import { destinationFor } from './topics.js';
import { best as bestAbroadFits, scoreItem as abroadScore } from './sources/abroadFit.js';
import { digestDue, MAX_WAIT_MS, renderDigest, splitForDigest, STATE as DIGEST_STATE, TOP as DIGEST_TOP } from './digest.js';
import { LOOKBACK_DAYS, nextStreak, quietMessage, silent, yieldsHistorically } from './breakage.js';
import { addDays, instantOf, renderWeekly, slot, STATE as WEEKLY_STATE, weeklyDue, weeklyMissed } from './weekly.js';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Runs the enabled sources, stores what they find, and delivers new items.
 *
 * Crawling is sequential and paced on purpose: it must not be retried wholesale
 * or we hammer a rate-limited endpoint. Delivery is per item, so one throttled
 * message never blocks or duplicates the rest.
 */
export class Pipeline {
  constructor({ store, telegram, sources } = {}) {
    this.store = store ?? new Store();
    this.telegram = telegram ?? new TelegramClient();
    this.sources = sources ?? buildSources();

    /*
     * A source that lists everything on every run — Wuzzuf's sitemap carries
     * all 5,000-odd live jobs each time — needs to know what has already been
     * crawled, or it re-fetches the same pages hourly for the store to
     * discard. Offered, not required: a source that does not want it says so
     * by not implementing useStore.
     */
    for (const source of this.sources) {
      source.useStore?.(this.store);
    }
  }

  /**
   * @param {{sources?: string[], keywords?: string[], locations?: string[],
   *          pages?: number, limit?: number, dryRun?: boolean,
   *          noSend?: boolean}} options
   */
  async run(options = {}) {
    const runId = options.runId ?? randomUUID();
    const results = [];
    const startedAt = Date.now();

    if (!options.dryRun && config.log.decisions) {
      try {
        const pruned = this.store.pruneDecisions(config.log.decisionsRetentionDays);
        if (pruned > 0) logger.debug('Pruned old decisions', { runId, pruned });
      } catch (error) {
        logger.warn('Could not prune decisions', { runId, error: error.message });
      }
    }

    /*
     * A host without a persistent disk hands back an empty database after
     * every restart, and every match in the window is then new again — the
     * channel gets the same jobs it already had, which is how a restart loop
     * turns into a flood.
     *
     * So the pass that finds an empty database seeds it instead of sending:
     * rows are stored and marked skipped, and the next pass delivers normally
     * because dedup now has something to work with. The cost is the first
     * window's genuinely new items, which is one pass; the alternative is the
     * whole window re-sent on every boot.
     *
     * Asked once, before any source stores anything: source two would
     * otherwise see the database source one just filled.
     */
    const wantSeeding = config.limits.seedFreshDatabase && !options.dryRun && !options.noSend;

    // What the person muted and what their 👍/👎 taught the score, as of now.
    loadMutes(this.store);
    reloadLearned(this.store);

    for (const source of this.sources) {
      if (options.sources?.length && !options.sources.includes(source.key)) continue;
      if (!source.isEnabled()) continue;

      /*
       * Per source, as the Android app does it: a source that has stored
       * nothing and never finished a clean run seeds instead of sending, so
       * switching a new source on does not flood the channel and the rest
       * do not go quiet while it seeds. An empty database is every source's
       * first run at once.
       */
      const seeding = wantSeeding && this.#shouldSeed(source.key);

      if (seeding) {
        logger.warn('Source has stored nothing yet; seeding without sending', {
          runId,
          source: source.key,
          reason: 'first run of this source, or a host that does not keep the database',
          hint: 'Set SEED_FRESH_DATABASE=false to send on an empty database instead.',
        });
      }

      results.push(await this.#runSource(runId, source, options, seeding));
    }

    if (results.length > 0) logSummary(runId, results, Date.now() - startedAt);

    // What used to need a resident process: the digest bundle and Friday's summary.
    if (!options.dryRun && !options.noSend) await this.housekeeping();

    return results;
  }

  #shouldSeed(key) {
    try {
      return this.store.getState(`${key}.seeded`) == null && this.store.isEmpty(key);
    } catch {
      return false;
    }
  }

  async #runSource(runId, source, options, seeding = false) {
    const startedAt = Date.now();

    const stat = {
      source: source.key,
      parsed: 0,
      matched: 0,
      rejected: {},
      stored: 0,
      duplicates: 0,
      delivered: 0,
      seeded: 0,
      digested: 0,
      listed: null,
      blocked: false,
      // Cut short by the network: run it again soon rather than at its next slot.
      retryable: false,
      error: null,
      items: [],
    };

    let items = [];
    // Things a person has to act on — out of credits, a cap reached — as opposed to a bad pass.
    const alerts = [];

    // A count from an earlier run of a kept instance must not make this one look healthy.
    if ('listed' in source) source.listed = null;
    if ('dryRun' in source) source.dryRun = Boolean(options.dryRun);

    try {
      items = await source.fetch(this.#sourceOptions(source, options));
    } catch (error) {
      if (error instanceof BlockedError) {
        // Keep whatever was parsed before the block.
        items = error.partialItems ?? [];
        stat.blocked = true;
        stat.retryable = Boolean(error.retryable);
        stat.error = error.message;

        if (error.alert) alerts.push({ kind: error.alert, message: error.message });

        logger.warn('Source blocked', {
          runId,
          source: source.key,
          recovered: items.length,
          error: error.message,
        });
      } else {
        // One broken source must not abort the whole run.
        stat.error = error.message;
        logger.error('Source failed', { runId, source: source.key, error: error.message });
      }
    }

    // A clean pass that parsed nothing is how a changed page layout shows up.
    if (items.length === 0 && !stat.error) {
      logger.warn('Source returned no items', {
        runId,
        source: source.key,
        hint: 'Normal for a quiet window; if it repeats, the site markup may have changed.',
      });
    }

    alerts.push(...(source.takeAlerts?.() ?? []));

    if (alerts.length > 0 && !options.dryRun && !options.noSend) {
      await this.#alert(source.key, alerts);
    }

    stat.parsed = items.length;
    stat.listed = Number.isFinite(source.listed) ? source.listed : null;

    if (!options.dryRun) {
      const quiet = this.#checkSilence(source.key, stat);
      if (quiet && !seeding && !options.noSend) await this.#alert(source.key, [quiet]);
    }

    // One filter for every source: jobs today, a posts provider tomorrow.
    const { kept, rejected, rejections } = this.#filter(items, runId);
    stat.matched = kept.length;
    stat.rejected = rejected;
    stat.items = kept;

    items = kept;

    if (!options.dryRun) {
      const { created, decisions } = this.#store(items, runId);
      stat.stored = created.length;
      stat.duplicates = decisions.filter((row) => row.decision === 'duplicate').length;

      this.#recordDecisions(runId, source.key, [...rejections, ...decisions]);

      if (seeding) {
        stat.seeded = this.#seed(created);
        if (!stat.error) this.store.setState(`${source.key}.seeded`, '1');
      } else if (!options.noSend) {
        // Jobs abroad: only the best fits go out; the rest stay stored with their score. Then best first.
        const outgoing = this.#bestAbroad(created).sort((a, b) => (b.score ?? 0) - (a.score ?? 0));

        // Digest mode: the weaker matches wait for the next bundle.
        const { digest, digestThreshold } = config.ranking;
        const [now, later] = splitForDigest(outgoing, digest, digestThreshold);
        for (const row of later) this.store.markDigest(row.id);
        stat.digested = later.length;

        stat.delivered = await this.#deliver(this.#withRetryable(now));
        if (!stat.error && !stat.blocked) this.store.setState(`${source.key}.seeded`, '1');
      }

      try {
        this.store.recordRun(stat);
      } catch (error) {
        logger.warn('Could not record the run', { runId, source: source.key, error: error.message });
      }
    }

    const memory = process.memoryUsage();

    logger.info('Run finished', {
      runId,
      source: source.key,
      parsed: stat.parsed,
      matched: stat.matched,
      rejected: stat.rejected,
      stored: stat.stored,
      duplicates: stat.duplicates,
      delivered: stat.delivered,
      seeded: stat.seeded,
      digested: stat.digested,
      blocked: stat.blocked,
      durationMs: Date.now() - startedAt,
      rssMb: toMb(memory.rss),
      heapUsedMb: toMb(memory.heapUsed),
    });

    return stat;
  }

  /**
   * A time window is source-specific: LinkedIn's f_TPR ("r3600") and a search
   * engine's date filter ("w") share no vocabulary. Callers that need to widen
   * every source at once pass `timeWindows` keyed by source, and each source
   * sees only its own value as `timeWindow`.
   */
  #sourceOptions(source, options) {
    const timeWindow = options.timeWindows?.[source.key] ?? options.timeWindow;
    return timeWindow === undefined ? options : { ...options, timeWindow };
  }

  /**
   * Apply the configured filters, attaching any contacts found so the message
   * can show them.
   *
   * @returns {{kept: object[], rejected: Record<string, number>, rejections: object[]}}
   */
  #filter(items, runId = null) {
    const kept = [];
    const rejected = {};
    const rejections = [];

    for (const item of items) {
      // What it asks for and offers, before the filters: the experience gate reads the years.
      applyExtract(item);

      const { pass, reason, detail, contacts } = evaluate(item);

      if (!pass) {
        rejected[reason] = (rejected[reason] ?? 0) + 1;
        rejections.push({ decision: 'rejected', reason, detail, item });

        logger.debug('Rejected', {
          runId,
          source: item.source,
          reason,
          detail,
          title: item.title,
          company: item.company,
          url: item.url,
        });
        continue;
      }

      item.contactEmails = contacts.emails;
      item.contactPhones = contacts.phones;
      item.region = regionOf(item);
      item.score = scoreOf(item);
      kept.push(item);
    }

    return { kept, rejected, rejections };
  }

  /**
   * @returns {{created: object[], decisions: object[]}} rows created by this
   *   run only, and an accepted/duplicate decision for every item tried.
   */
  #store(items, runId = null) {
    const created = [];
    const decisions = [];

    for (const item of items) {
      try {
        const { row, created: isNew, duplicate } = this.store.insertIfNew(item);
        const contacts = [...(item.contactEmails ?? []), ...(item.contactPhones ?? [])];

        if (isNew) {
          created.push(row);
          decisions.push({
            decision: 'accepted',
            reason: null,
            detail: contacts.length ? `contacts: ${contacts.join(', ')}` : null,
            item,
          });
          logger.debug('Accepted', {
            runId,
            source: item.source,
            itemId: row?.id,
            title: item.title,
            company: item.company,
            contacts: contacts.length ? contacts : undefined,
          });
        } else {
          decisions.push({
            decision: 'duplicate',
            reason: duplicate?.kind ?? 'same-id',
            duplicateOf: duplicate?.duplicateOf ?? null,
            item,
          });
          logger.debug('Duplicate', {
            runId,
            source: item.source,
            kind: duplicate?.kind ?? 'same-id',
            title: item.title,
            duplicateOf: duplicate?.duplicateOf,
          });
        }
      } catch (error) {
        logger.error('Failed to store item', {
          source: item.source,
          externalId: item.externalId,
          error: error.message,
        });
      }
    }

    return { created, decisions };
  }

  /** Never throws: the decision log is a diagnostic, not part of delivery. */
  #recordDecisions(runId, sourceKey, rows) {
    if (!config.log.decisions) return;

    const wanted = config.log.decisionsAccepted
      ? rows
      : rows.filter((row) => row.decision !== 'accepted');

    try {
      this.store.recordDecisions(wanted.map((row) => ({ ...row, runId, source: sourceKey })));
    } catch (error) {
      logger.warn('Could not record decisions', { runId, source: sourceKey, error: error.message });
    }
  }

  /**
   * Record rows without sending them, so the next pass has a dedup baseline.
   *
   * Marked skipped rather than sent: nothing was delivered, and `sent` is
   * reserved for a confirmed ok:true from Telegram. The reason is stored on
   * the row, so it is clear later why a window arrived silently.
   *
   * @returns {number} how many were seeded.
   */
  #seed(rows) {
    for (const row of rows) {
      this.store.markSkipped(row.id, 'seeded: database was empty, not sent');
    }

    return rows.length;
  }

  /**
   * Tell a person, on Telegram, about something only they can fix.
   *
   * Once a day per kind: a source stopped by its cap stays stopped all day,
   * and every pass repeating that would bury the jobs it is there to deliver.
   * The day is recorded only once the message is through, so a failed send
   * is tried again on the next pass. Never throws — an alert must not cost
   * the run its deliveries.
   */
  async #alert(sourceKey, alerts) {
    if (!config.alerts.enabled || !this.telegram.isEnabled()) return;

    const day = dayOf();

    for (const { kind, message } of alerts) {
      const key = `alert.${kind}.day`;
      if (this.store.getState(key) === day) continue;

      try {
        await this.telegram.sendMessage(
          `⚠️ <b>Crawler alert</b> (${escapeHtml(sourceKey)})\n${escapeHtml(message)}`,
          config.alerts.chatId,
        );
        this.store.setState(key, day);
        logger.warn('Alert sent', { source: sourceKey, kind });
      } catch (error) {
        logger.error('Alert could not be sent', { source: sourceKey, kind, error: error.message });
      }
    }
  }

  /** New rows plus anything whose delivery previously stalled. */
  #withRetryable(newRows) {
    const cap = config.limits.maxItemsPerRun;
    const rows = [...newRows];
    const remaining = cap - rows.length;

    if (remaining > 0) {
      const known = new Set(rows.map((row) => row.id));

      for (const row of this.store.getRetryable(
        config.limits.retryAfterMinutes,
        config.limits.maxSendAttempts,
        remaining,
      )) {
        if (!known.has(row.id)) rows.push(row);
      }
    }

    return rows.slice(0, cap);
  }

  /** @returns {Promise<number>} how many were confirmed delivered. */
  async #deliver(rows) {
    let delivered = 0;

    for (const [index, row] of rows.entries()) {
      // Stagger to stay under Telegram's per-channel rate limit.
      if (index > 0 && config.telegram.sendDelayMs > 0) {
        await sleep(config.telegram.sendDelayMs);
      }

      if (await this.deliverOne(row.id)) delivered += 1;
    }

    return delivered;
  }

  /**
   * Deliver a single stored row. This is the only place that may mark a row
   * sent, so a Telegram outage delays delivery but never swallows a posting.
   */
  async deliverOne(id) {
    const row = this.store.findById(id);
    if (!row) return false;

    // Idempotency: never send the same row twice.
    if (row.status === STATUS.SENT) return false;

    if (!this.telegram.isEnabled()) {
      this.store.markSkipped(row.id, 'Telegram delivery is disabled or not configured.');
      logger.info('Skipped delivery: Telegram disabled or not configured', { itemId: row.id });
      return false;
    }

    try {
      // Each region to its own forum topic when .env names one (src/topics.js).
      const region = parseRegion(row.region) ?? regionOf(row);
      await this.telegram.sendMessage(formatMessage(row), destinationFor(region));
      this.store.markSent(row.id);
      return true;
    } catch (error) {
      if (error instanceof TelegramRateLimitError) {
        // Stay pending; the next run picks it up.
        this.store.recordAttempt(row.id, error.message);
        logger.warn('Telegram rate limited', {
          itemId: row.id,
          retryAfter: error.retryAfterSeconds,
        });

        await sleep(error.retryAfterSeconds * 1000);
        return false;
      }

      if (error instanceof TelegramPermanentError) {
        this.store.markFailed(row.id, error.message, true);
        logger.error('Telegram rejected the message permanently', {
          itemId: row.id,
          error: error.message,
        });
        return false;
      }

      this.store.markFailed(row.id, error.message);
      logger.error('Telegram delivery failed', { itemId: row.id, error: error.message });
      return false;
    }
  }

  /**
   * Egypt's jobs as they are; of the jobs abroad, only the best fits (CV fit
   * of at least ABROAD_MIN_SCORE, the best ABROAD_MAX_PER_RUN), best first.
   * The others are kept, marked skipped with their score.
   */
  #bestAbroad(rows) {
    const abroad = rows.filter((row) => isAbroad(row));
    if (abroad.length === 0) return rows;

    const { abroadMinScore, abroadMaxPerRun } = config.ranking;
    const items = abroad.map((row) => Object.assign(toItem(row), { id: row.id }));
    const chosenIds = new Set(
      bestAbroadFits(items, { minScore: abroadMinScore, maxPerRun: abroadMaxPerRun }).map((item) => item.id),
    );

    for (const item of items) {
      if (chosenIds.has(item.id)) continue;
      const fit = abroadScore(item);
      this.store.markSkipped(
        item.id,
        `lower fit: ${fit}/100, below ${abroadMinScore} or past the best ${abroadMaxPerRun}`,
      );
      logger.info('Lower CV fit, not sent', { source: item.source, title: item.title, fit });
    }

    return rows.filter((row) => !isAbroad(row) || chosenIds.has(row.id));
  }

  /**
   * A source that answers but parses nothing, run after run, while it used to
   * find jobs: the site has likely changed. Returns an alert once the streak
   * reaches QUIET_SOURCE_STREAK; the alert itself goes out once a day.
   */
  #checkSilence(key, stat) {
    try {
      const since = new Date(Date.now() - LOOKBACK_DAYS * 86_400_000);
      const history = this.store.runsSince(since, key);
      const stateKey = `${key}.zeroStreak`;
      const previous = Number.parseInt(this.store.getState(stateKey) ?? '0', 10) || 0;
      const next = nextStreak(previous, stat.parsed, stat.blocked, stat.error, yieldsHistorically(history), stat.listed);

      if (next == null) return null;
      this.store.setState(stateKey, next);

      if (silent(stat.parsed, stat.listed, stat.blocked, stat.error) && next > 0) {
        logger.warn('Parsed nothing from a good answer', { source: key, streak: next });
      }

      if (next >= config.ranking.quietStreak) {
        const lastYield = [...history].reverse().find((run) => run.parsed > 0)?.ranAt ?? null;
        return { kind: `zero-${key}`, message: quietMessage(key, next, lastYield) };
      }
    } catch (error) {
      logger.warn('Silence check failed', { source: key, error: error.message });
    }

    return null;
  }

  /** The digest bundle and the weekly summary; never throws. */
  async housekeeping(now = new Date()) {
    if (!this.telegram.isEnabled()) return;

    try {
      await this.#digest(now);
    } catch (error) {
      logger.warn('Digest failed', { error: error.message });
    }

    try {
      if (config.ranking.weekly) await this.#weekly(now);
    } catch (error) {
      logger.warn('Weekly summary failed', { error: error.message });
    }
  }

  /** The bundled weaker matches, every DIGEST_EVERY_HOURS while digest mode is on, and the leftovers once it is off. */
  async #digest(now) {
    const rows = this.store.digestRows();
    if (rows.length === 0) return;

    const at = now.getTime();
    const fresh = [];

    for (const row of rows) {
      // Too long in the queue without making the top: let go, so it never arrives a day late.
      if (at - Date.parse(row.created_at) > MAX_WAIT_MS) {
        this.store.markSkipped(row.id, `digest: not in the top ${DIGEST_TOP} within a day`);
      } else {
        fresh.push(row);
      }
    }

    const last = Number.parseInt(this.store.getState(DIGEST_STATE) ?? '', 10);
    const everyMs = config.ranking.digestEveryHours * 3_600_000;
    if (!digestDue(at, Number.isFinite(last) ? last : null, fresh.length, config.ranking.digest, everyMs)) return;

    const { text, ids } = renderDigest(fresh);
    if (ids.length === 0) return;

    try {
      await this.telegram.sendMessage(text);
    } catch (error) {
      if (error instanceof TelegramPermanentError) {
        for (const id of ids) this.store.markFailed(id, error.message, true);
      }
      // Rate limits and outages: the rows stay in the digest for the next run.
      throw error;
    }

    for (const id of ids) this.store.markSent(id);
    this.store.setState(DIGEST_STATE, at);
    logger.info('Digest sent', { jobs: ids.length });
  }

  /** Friday's summary of the week, once, from the run history and the stored jobs. */
  async #weekly(now) {
    const zone = config.schedule.timezone;
    const lastSent = this.store.getState(WEEKLY_STATE);

    /*
     * Never sent from this database: start counting from here instead of
     * summarising a week it did not see. The hosted database is wiped on
     * every restart, and a restart loop would otherwise repeat the summary.
     */
    if (lastSent == null) {
      this.store.setState(WEEKLY_STATE, slot(now, zone));
      return;
    }

    if (!weeklyDue(now, lastSent, zone)) {
      // Missed by days: skip that week quietly rather than send it late.
      if (weeklyMissed(now, lastSent, zone)) this.store.setState(WEEKLY_STATE, slot(now, zone));
      return;
    }

    const friday = slot(now, zone);
    const since = instantOf(addDays(friday, -7), 10, zone);
    const budget = new SerperBudget(this.store);
    const quiet = Object.fromEntries(
      this.store
        .statesWithPrefix('')
        .filter(({ key }) => key.endsWith('.zeroStreak'))
        .map(({ key, value }) => [key.slice(0, -'.zeroStreak'.length), Number.parseInt(value, 10)])
        .filter(([, streak]) => streak >= config.ranking.quietStreak),
    );

    const text = renderWeekly({
      from: addDays(friday, -7),
      to: addDays(friday, -1),
      facts: this.store.itemFacts(since),
      top: this.store.topScored(since, 5),
      runs: this.store.runsSince(since),
      serperWeek: Array.from({ length: 7 }, (_, i) => budget.used(addDays(friday, -i))).reduce((a, b) => a + b, 0),
      quiet,
    });

    await this.telegram.sendMessage(text);
    this.store.setState(WEEKLY_STATE, friday);
    logger.info('Weekly summary sent');
  }

  close() {
    this.store.close();
  }
}

const toMb = (bytes) => Math.round((bytes / 1_048_576) * 10) / 10;

/** One line for the whole crawl: what came in, what went out, what was thrown away most. */
function logSummary(runId, results, durationMs) {
  const sum = (key) => results.reduce((total, stat) => total + (stat[key] ?? 0), 0);
  const rejected = {};

  for (const stat of results) {
    for (const [reason, count] of Object.entries(stat.rejected ?? {})) {
      rejected[reason] = (rejected[reason] ?? 0) + count;
    }
  }

  const topRejections = Object.fromEntries(
    Object.entries(rejected)
      .sort(([, a], [, b]) => b - a)
      .slice(0, 5),
  );

  logger.info('Crawl summary', {
    runId,
    sources: results.map((stat) => stat.source),
    parsed: sum('parsed'),
    matched: sum('matched'),
    rejected: sum('parsed') - sum('matched'),
    topRejections,
    stored: sum('stored'),
    duplicates: sum('duplicates'),
    delivered: sum('delivered'),
    failedSources: results.filter((stat) => stat.error).map((stat) => stat.source),
    durationMs,
    rssMb: toMb(process.memoryUsage().rss),
  });
}

function escapeHtml(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

export default Pipeline;
