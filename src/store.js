import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import config from './config.js';
import logger from './logger.js';
import { matchScore } from './postSimilarity.js';
import { regionOf, parseRegion, REGION } from './region.js';

/**
 * Find somewhere the database can actually live.
 *
 * Hosts vary in what is writable: a generated container image may never create
 * the data directory, and the app directory itself is often read-only. Dying at
 * import time is the worst outcome — it happens before the health server binds,
 * so the platform only reports "not responding on port" and the real error is
 * buried in the logs.
 *
 * Falls back rather than crashing, and says loudly when it does: a fallback
 * outside a mounted volume means dedup is lost on restart, which shows up as
 * already-delivered jobs being sent again.
 */
function resolveWritablePath(preferred) {
  const candidates = [
    preferred,
    path.join(process.cwd(), 'data', 'crawler.sqlite'),
    path.join(os.tmpdir(), 'linkedin-jobs-telegram', 'crawler.sqlite'),
  ];

  const failures = [];

  for (const candidate of candidates) {
    const directory = path.dirname(candidate);

    try {
      fs.mkdirSync(directory, { recursive: true });

      /*
       * Probed by writing, not by asking: a read-only mount still reports
       * writable permission bits, so accessSync said yes and the first real
       * write then failed. docker-crawl-loop.sh learned this the same way.
       */
      const probe = path.join(directory, `.write-probe-${process.pid}`);

      fs.writeFileSync(probe, '');
      fs.unlinkSync(probe);

      if (candidate !== preferred) {
        logger.warn('Database directory not usable; falling back', {
          preferred,
          using: candidate,
          reason: failures[0]?.message,
          warning: 'Dedup state will not survive a restart unless this path is a mounted volume.',
        });
      }

      return candidate;
    } catch (error) {
      failures.push(error);
    }
  }

  throw new Error(
    `No writable database location. Tried: ${candidates.join(', ')}. ` +
      `Last error: ${failures.at(-1)?.message}. ` +
      'Set DATABASE_PATH to a writable directory, or mount a volume there.',
  );
}

/**
 * A job's identity across boards: its company and title, stripped of what
 * differs between listings of the same role.
 *
 * Returns null rather than a weak key when there is no company to anchor it.
 * Titles alone collide constantly — every board carries several unrelated
 * "Backend Developer" postings — and a false match silently swallows a job,
 * which is worse than sending one twice.
 */
export function fingerprintOf(item) {
  const company = normalize(item.company);
  const title = normalize(item.title);

  if (!company || !title) return null;

  return createHash('sha1').update(`${company}::${title}`).digest('hex');
}

/** Lowercased, punctuation dropped, whitespace collapsed. */
function normalize(value) {
  if (!value) return null;

  const text = String(value)
    .toLowerCase()
    .normalize('NFKD')
    // Keep letters and digits in any script: company names here are as often
    // Arabic as Latin.
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim()
    .replace(/\s+/g, ' ');

  return text === '' ? null : text;
}

export const STATUS = {
  PENDING: 'pending', // crawled, not yet delivered
  SENT: 'sent', // confirmed ok:true from Telegram
  FAILED: 'failed', // delivery failed, retryable
  SKIPPED: 'skipped', // Telegram disabled or not configured
  DIGEST: 'digest', // waiting for the next digest message (src/digest.js)
};

/* Rows that are finds, not a first run's backlog: what the summaries count. */
const FOUND = "(last_error IS NULL OR last_error NOT LIKE 'seeded%')";

/**
 * SQLite-backed dedup + delivery state.
 *
 * Full rows are stored rather than fingerprints: a retry after a Telegram
 * outage must be able to re-render the message without re-crawling a time
 * window that has already rolled past, and `status` + `send_attempts` on the
 * same row is what makes "delivered exactly once" enforceable.
 */
export class Store {
  constructor(databasePath = config.databasePath) {
    this.databasePath = resolveWritablePath(databasePath);

    this.db = new DatabaseSync(this.databasePath);
    this.db.exec('PRAGMA journal_mode = WAL');
    this.db.exec('PRAGMA busy_timeout = 5000');
    this.#migrate();
  }

  #migrate() {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS items (
        id             INTEGER PRIMARY KEY AUTOINCREMENT,
        source         TEXT    NOT NULL,
        external_id    TEXT    NOT NULL,
        item_type      TEXT    NOT NULL DEFAULT 'job',
        title          TEXT    NOT NULL,
        company        TEXT,
        location       TEXT,
        url            TEXT    NOT NULL,
        keyword        TEXT,
        posted_at      TEXT,
        excerpt        TEXT,
        status         TEXT    NOT NULL DEFAULT 'pending',
        send_attempts  INTEGER NOT NULL DEFAULT 0,
        last_error     TEXT,
        sent_at        TEXT,
        created_at     TEXT    NOT NULL,
        updated_at     TEXT    NOT NULL,
        UNIQUE (source, external_id)
      );
    `);

    this.db.exec('CREATE INDEX IF NOT EXISTS idx_items_status ON items (status, source)');
    this.db.exec('CREATE INDEX IF NOT EXISTS idx_items_created ON items (created_at)');

    // Additive columns for databases created before contact extraction existed.
    // SQLite has no ADD COLUMN IF NOT EXISTS, so check the table info first.
    const existing = new Set(
      this.db.prepare('PRAGMA table_info(items)').all().map((column) => column.name),
    );

    for (const [name, definition] of [
      ['contact_emails', 'TEXT'],
      ['contact_phones', 'TEXT'],
      ['seniority', 'TEXT'],
      ['employment_type', 'TEXT'],
      ['fingerprint', 'TEXT'],
      ['board', 'TEXT'],
      ['market', 'TEXT'],
      // Ported from the Android app: what a posting asks for and how well it fits.
      ['years_min', 'INTEGER'],
      ['years_max', 'INTEGER'],
      ['salary', 'TEXT'],
      ['score', 'INTEGER'],
      ['region', 'TEXT'],
      ['remote', 'INTEGER'],
      ['workplace', 'TEXT'],
      // The person's verdict, 'up' or 'down' (bot /up, /down): what the score learns from.
      ['feedback', 'TEXT'],
    ]) {
      if (!existing.has(name)) {
        this.db.exec(`ALTER TABLE items ADD COLUMN ${name} ${definition}`);
      }
    }

    this.db.exec('CREATE INDEX IF NOT EXISTS idx_items_fingerprint ON items (fingerprint)');

    /*
     * Pages a sitemap-driven source has already opened and decided about.
     *
     * Needed because a sitemap lists every live job on every run while saying
     * nothing about age: a posting rejected for being three months old is not
     * stored, so without this ledger it would be fetched again every hour,
     * and the per-run cap would never advance past the oldest entries to
     * reach the new ones.
     */
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS checked_urls (
        source      TEXT NOT NULL,
        external_id TEXT NOT NULL,
        checked_at  TEXT NOT NULL,
        PRIMARY KEY (source, external_id)
      );
    `);

    /*
     * Small values carried from one run to the next: the Serper query count
     * for a day, how a search has been yielding, the day an alert was last
     * sent. The Android app keeps the same table under the same name.
     */
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS state (
        key   TEXT PRIMARY KEY,
        value TEXT NOT NULL
      );
    `);

    /*
     * What the pipeline decided about every item it saw — rejected (and why),
     * duplicate (of what), accepted — so the filters can be tuned from data
     * instead of guesses. A log, not state: pruned by age, never read by the
     * crawl itself.
     */
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS decisions (
        id           INTEGER PRIMARY KEY AUTOINCREMENT,
        run_id       TEXT,
        source       TEXT NOT NULL,
        decision     TEXT NOT NULL,
        reason       TEXT,
        detail       TEXT,
        item_type    TEXT,
        title        TEXT,
        company      TEXT,
        location     TEXT,
        url          TEXT,
        external_id  TEXT,
        duplicate_of TEXT,
        created_at   TEXT NOT NULL
      );
    `);

    this.db.exec(
      'CREATE INDEX IF NOT EXISTS idx_decisions_lookup ON decisions (decision, reason, created_at)',
    );
    this.db.exec('CREATE INDEX IF NOT EXISTS idx_decisions_created ON decisions (created_at)');

    /*
     * One row per source run: what a source parsed, kept, sent and why it
     * dropped the rest. Read by the breakage check (a source gone quiet) and
     * the weekly summary. The Android app keeps the same table.
     */
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS runs (
        id            INTEGER PRIMARY KEY AUTOINCREMENT,
        source        TEXT NOT NULL,
        ran_at        TEXT NOT NULL,
        parsed        INTEGER NOT NULL DEFAULT 0,
        listed        INTEGER,
        matched       INTEGER NOT NULL DEFAULT 0,
        stored        INTEGER NOT NULL DEFAULT 0,
        sent          INTEGER NOT NULL DEFAULT 0,
        seeded        INTEGER NOT NULL DEFAULT 0,
        rejected_json TEXT,
        blocked       INTEGER NOT NULL DEFAULT 0,
        error         TEXT
      );
    `);
    this.db.exec('CREATE INDEX IF NOT EXISTS idx_runs_source_time ON runs (source, ran_at)');

    // Companies and title words never wanted again (bot /mute_company, /mute_word).
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS mutes (
        kind       TEXT NOT NULL,
        value      TEXT NOT NULL,
        label      TEXT NOT NULL,
        created_at TEXT NOT NULL,
        PRIMARY KEY (kind, value)
      );
    `);
  }

  // ---- Run history ----

  /** @param {{source: string, parsed: number, listed?: number|null, matched: number, stored: number,
   *          delivered: number, seeded: number, rejected: object, blocked: boolean, error: string|null}} stat */
  recordRun(stat, at = new Date()) {
    this.db
      .prepare(
        `INSERT INTO runs (source, ran_at, parsed, listed, matched, stored, sent, seeded, rejected_json, blocked, error)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        stat.source,
        at.toISOString(),
        stat.parsed ?? 0,
        stat.listed ?? null,
        stat.matched ?? 0,
        stat.stored ?? 0,
        stat.delivered ?? 0,
        stat.seeded ?? 0,
        JSON.stringify(stat.rejected ?? {}),
        stat.blocked ? 1 : 0,
        stat.error ?? null,
      );
  }

  /** Runs since a time, oldest first; one source's when given. */
  runsSince(since, source = null) {
    const iso = since instanceof Date ? since.toISOString() : String(since);
    const rows = source
      ? this.db.prepare('SELECT * FROM runs WHERE source = ? AND ran_at >= ? ORDER BY ran_at').all(source, iso)
      : this.db.prepare('SELECT * FROM runs WHERE ran_at >= ? ORDER BY ran_at').all(iso);

    return rows.map((row) => ({
      source: row.source,
      ranAt: row.ran_at,
      parsed: row.parsed,
      listed: row.listed,
      matched: row.matched,
      stored: row.stored,
      sent: row.sent,
      seeded: row.seeded,
      rejected: parseJsonObject(row.rejected_json),
      blocked: Boolean(row.blocked),
      error: row.error,
    }));
  }

  /** @returns {number} runs deleted */
  pruneRuns(days = 60) {
    const cutoff = new Date(Date.now() - days * 86_400_000).toISOString();
    return Number(this.db.prepare('DELETE FROM runs WHERE ran_at < ?').run(cutoff).changes);
  }

  // ---- Mutes and feedback ----

  /** @returns {{kind: string, value: string, label: string, createdAt: string}[]} */
  mutes() {
    return this.db
      .prepare('SELECT kind, value, label, created_at FROM mutes ORDER BY created_at')
      .all()
      .map((row) => ({ kind: row.kind, value: row.value, label: row.label, createdAt: row.created_at }));
  }

  addMute(kind, value, label = value) {
    this.db
      .prepare('INSERT OR REPLACE INTO mutes (kind, value, label, created_at) VALUES (?, ?, ?, ?)')
      .run(kind, value, label, new Date().toISOString());
  }

  /** @returns {boolean} whether there was one */
  removeMute(kind, value) {
    return Number(this.db.prepare('DELETE FROM mutes WHERE kind = ? AND value = ?').run(kind, value).changes) > 0;
  }

  /** The person's verdict on a job ('up', 'down'), or null to clear it. */
  setFeedback(id, value) {
    return Number(this.db.prepare('UPDATE items SET feedback = ? WHERE id = ?').run(value ?? null, id).changes) > 0;
  }

  /** Jobs the person marked with value, newest first: what the score learns from. */
  withFeedback(value, limit = 500) {
    return this.db
      .prepare('SELECT * FROM items WHERE feedback = ? ORDER BY created_at DESC LIMIT ?')
      .all(value, limit)
      .map(toItem);
  }

  // ---- Digest and summaries ----

  /** Kept for the next digest message instead of being sent on its own. */
  markDigest(id) {
    this.db
      .prepare('UPDATE items SET status = ?, updated_at = ? WHERE id = ?')
      .run(STATUS.DIGEST, new Date().toISOString(), id);
  }

  /** What waits for the digest, best first. */
  digestRows(limit = 200) {
    return this.db
      .prepare('SELECT * FROM items WHERE status = ? ORDER BY COALESCE(score, 0) DESC, created_at DESC LIMIT ?')
      .all(STATUS.DIGEST, limit);
  }

  /** Per stored job since a time: enough to count by region, source and status. */
  itemFacts(since) {
    const iso = since instanceof Date ? since.toISOString() : String(since);
    return this.db
      .prepare(`SELECT source, region, status, score FROM items WHERE ${FOUND} AND created_at >= ?`)
      .all(iso)
      .map((row) => ({
        source: row.source,
        region: parseRegion(row.region) ?? REGION.EGYPT,
        status: row.status,
        score: row.score,
      }));
  }

  /** The best-scored jobs stored since a time. */
  topScored(since, limit = 5) {
    const iso = since instanceof Date ? since.toISOString() : String(since);
    return this.db
      .prepare(
        `SELECT * FROM items WHERE ${FOUND} AND created_at >= ? AND score IS NOT NULL
          ORDER BY score DESC, created_at DESC LIMIT ?`,
      )
      .all(iso, limit);
  }

  /**
   * @param {Array<{runId?: string, source: string, decision: string, reason?: string,
   *   detail?: string, item?: object, duplicateOf?: string}>} rows
   * @returns {number} rows written
   */
  recordDecisions(rows) {
    if (!rows.length) return 0;

    const now = new Date().toISOString();
    const insert = this.db.prepare(
      `INSERT INTO decisions
         (run_id, source, decision, reason, detail, item_type, title, company,
          location, url, external_id, duplicate_of, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    );

    this.db.exec('BEGIN');

    try {
      for (const row of rows) {
        const item = row.item ?? {};

        insert.run(
          row.runId ?? null,
          row.source ?? item.source ?? 'unknown',
          row.decision,
          row.reason ?? null,
          row.detail ?? null,
          item.itemType ?? null,
          item.title ?? null,
          item.company ?? null,
          item.location ?? null,
          item.url ?? null,
          item.externalId == null ? null : String(item.externalId),
          row.duplicateOf ?? null,
          now,
        );
      }

      this.db.exec('COMMIT');
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }

    return rows.length;
  }

  /** @returns {number} rows deleted */
  pruneDecisions(days) {
    if (!(days > 0)) return 0;

    const cutoff = new Date(Date.now() - days * 86_400_000).toISOString();

    const { changes } = this.db.prepare('DELETE FROM decisions WHERE created_at < ?').run(cutoff);

    return Number(changes);
  }

  #decisionWhere({ decision, reason, source, sinceHours, runId } = {}) {
    const clauses = [];
    const params = [];

    for (const [column, value] of [
      ['decision', decision],
      ['reason', reason],
      ['source', source],
      ['run_id', runId],
    ]) {
      if (!value) continue;
      clauses.push(`${column} = ?`);
      params.push(value);
    }

    if (sinceHours > 0) {
      clauses.push('created_at >= ?');
      params.push(new Date(Date.now() - sinceHours * 3_600_000).toISOString());
    }

    return { where: clauses.length ? `WHERE ${clauses.join(' AND ')}` : '', params };
  }

  /** Newest first. */
  queryDecisions({ limit = 50, ...filters } = {}) {
    const { where, params } = this.#decisionWhere(filters);

    return this.db
      .prepare(`SELECT * FROM decisions ${where} ORDER BY id DESC LIMIT ?`)
      .all(...params, Math.max(1, Math.min(Number(limit) || 50, 1000)));
  }

  /**
   * Counts by decision/reason/source, plus the titles rejected most often for
   * each reason — the rows that say which rule to loosen.
   */
  decisionSummary({ topTitles = 5, ...filters } = {}) {
    const { where, params } = this.#decisionWhere(filters);

    const byReason = this.db
      .prepare(
        `SELECT decision, reason, COUNT(*) AS count FROM decisions ${where}
          GROUP BY decision, reason ORDER BY count DESC`,
      )
      .all(...params);

    const bySource = this.db
      .prepare(
        `SELECT source, decision, COUNT(*) AS count FROM decisions ${where}
          GROUP BY source, decision ORDER BY source, decision`,
      )
      .all(...params);

    const rejectedWhere = `${where ? `${where} AND` : 'WHERE'} decision = 'rejected'`;

    const titles = this.db
      .prepare(
        `SELECT reason, title, COUNT(*) AS count FROM decisions ${rejectedWhere}
          GROUP BY reason, title ORDER BY reason, count DESC`,
      )
      .all(...params);

    const topRejectedTitles = {};

    for (const row of titles) {
      const list = (topRejectedTitles[row.reason] ??= []);
      if (list.length < topTitles) list.push({ title: row.title, count: row.count });
    }

    return { byReason, bySource, topRejectedTitles };
  }

  /** @returns {string|null} */
  getState(key) {
    return this.db.prepare('SELECT value FROM state WHERE key = ?').get(key)?.value ?? null;
  }

  setState(key, value) {
    this.db
      .prepare('INSERT OR REPLACE INTO state (key, value) VALUES (?, ?)')
      .run(key, String(value));
  }

  /** @returns {{key: string, value: string}[]} every entry whose key starts with prefix. */
  statesWithPrefix(prefix) {
    return this.db
      .prepare("SELECT key, value FROM state WHERE substr(key, 1, length(?)) = ? ORDER BY key")
      .all(prefix, prefix);
  }

  /** Remember that this page was opened, whatever was decided about it. */
  markChecked(source, externalId) {
    this.db
      .prepare(
        `INSERT INTO checked_urls (source, external_id, checked_at)
         VALUES (?, ?, ?)
         ON CONFLICT (source, external_id) DO UPDATE SET checked_at = excluded.checked_at`,
      )
      .run(source, externalId, new Date().toISOString());
  }

  /** @returns {Set<string>} every external id this source has already opened. */
  checkedIds(source) {
    const rows = this.db
      .prepare('SELECT external_id FROM checked_urls WHERE source = ?')
      .all(source);

    return new Set(rows.map((row) => row.external_id));
  }

  /**
   * Insert the item unless it has been seen before.
   *
   * Two tests, because one job can arrive twice in two different shapes. The
   * UNIQUE (source, external_id) constraint is the guarantee — the same
   * posting from the same board is the same row, always. The fingerprint
   * catches what that cannot: a job listed on LinkedIn and again on Tanqeeb
   * carries a different id on each, and a company that reposts monthly gets a
   * new id from the board itself.
   *
   * @returns {{row: object, created: boolean,
   *            duplicate?: {kind: 'same-id'|'fingerprint'|'repost', duplicateOf: string|null}}}
   */
  insertIfNew(item) {
    const now = new Date().toISOString();
    const fingerprint = fingerprintOf(item);

    const duplicate = fingerprint ? this.findByFingerprint(fingerprint) : null;

    if (duplicate) {
      // Already delivered under another id. Reported as not created, so it is
      // never queued for sending a second time.
      const sameRow =
        duplicate.source === item.source && duplicate.external_id === String(item.externalId);

      return {
        row: duplicate,
        created: false,
        duplicate: {
          kind: sameRow ? 'same-id' : 'fingerprint',
          duplicateOf: sameRow ? duplicate.url : `${duplicate.source}: ${duplicate.url}`,
        },
      };
    }

    if (item.itemType === 'post') {
      const repost = this.findSimilarPost(item);

      if (repost) {
        logger.info('Post is a repost of an earlier one', {
          url: item.url,
          duplicateOf: repost.row.url,
          score: Number(repost.score.toFixed(2)),
        });

        return {
          row: repost.row,
          created: false,
          duplicate: {
            kind: 'repost',
            duplicateOf: `${repost.row.url} (score ${repost.score.toFixed(2)})`,
          },
        };
      }
    }

    const result = this.db
      .prepare(
        `INSERT OR IGNORE INTO items
           (source, external_id, item_type, title, company, location, url,
            keyword, posted_at, excerpt, contact_emails, contact_phones,
            seniority, employment_type, fingerprint, board, market,
            years_min, years_max, salary, score, region, remote, workplace,
            status, send_attempts, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)`,
      )
      .run(
        item.source,
        item.externalId,
        item.itemType,
        item.title,
        item.company ?? null,
        item.location ?? null,
        item.url,
        item.keyword ?? null,
        item.postedAt ?? null,
        item.excerpt ?? null,
        item.contactEmails?.length ? item.contactEmails.join(', ') : null,
        item.contactPhones?.length ? item.contactPhones.join(', ') : null,
        item.seniority ?? null,
        item.employmentType ?? null,
        fingerprint,
        item.board ?? null,
        item.market ?? null,
        item.yearsMin ?? null,
        item.yearsMax ?? null,
        item.salary ?? null,
        item.score ?? null,
        (item.region ?? regionOf(item)).name,
        item.remote ? 1 : 0,
        item.workplace ?? null,
        STATUS.PENDING,
        now,
        now,
      );

    const row = this.findByExternalId(item.source, item.externalId);
    const created = result.changes > 0;

    return created
      ? { row, created }
      : { row, created, duplicate: { kind: 'same-id', duplicateOf: row?.url ?? null } };
  }

  findById(id) {
    return this.db.prepare('SELECT * FROM items WHERE id = ?').get(id) ?? null;
  }

  /**
   * An earlier post carrying the same ad under a different link.
   *
   * Every status counts, not only sent: a copy still pending or skipped is the
   * one that will be (or would have been) delivered, and a second copy of it
   * adds nothing. Post volume is a handful a day, so the window is scanned in
   * memory rather than indexed.
   *
   * @returns {{row: object, score: number} | null}
   */
  findSimilarPost(item, settings = config.sources.linkedin_posts?.dedup ?? {}) {
    const windowDays = settings.windowDays ?? 30;
    if (windowDays <= 0) return null;

    const since = new Date(Date.now() - windowDays * 86_400_000).toISOString();
    const candidate = {
      excerpt: item.excerpt,
      title: item.title,
      contactEmails: item.contactEmails,
    };

    const rows = this.db
      .prepare(
        `SELECT * FROM items
          WHERE item_type = 'post' AND created_at >= ?
            AND NOT (source = ? AND external_id = ?)
          ORDER BY created_at ASC`,
      )
      .all(since, item.source, item.externalId);

    for (const row of rows) {
      const score = matchScore(
        candidate,
        { excerpt: row.excerpt, title: row.title, contactEmails: row.contact_emails },
        settings.threshold,
      );

      if (score > 0) return { row, score };
    }

    return null;
  }

  /** Any source's row for the same job, however it was identified there. */
  findByFingerprint(fingerprint) {
    return (
      this.db.prepare('SELECT * FROM items WHERE fingerprint = ?').get(fingerprint) ?? null
    );
  }

  findByExternalId(source, externalId) {
    return (
      this.db
        .prepare('SELECT * FROM items WHERE source = ? AND external_id = ?')
        .get(source, externalId) ?? null
    );
  }

  /**
   * Rows whose delivery never completed and that are due for another attempt.
   */
  getRetryable(retryAfterMinutes, maxAttempts, limit) {
    const cutoff = new Date(Date.now() - retryAfterMinutes * 60_000).toISOString();

    return this.db
      .prepare(
        `SELECT * FROM items
          WHERE status IN (?, ?)
            AND send_attempts < ?
            AND updated_at < ?
          ORDER BY COALESCE(posted_at, created_at) ASC
          LIMIT ?`,
      )
      .all(STATUS.PENDING, STATUS.FAILED, maxAttempts, cutoff, limit);
  }

  markSent(id) {
    const now = new Date().toISOString();

    this.db
      .prepare(
        `UPDATE items
            SET status = ?, sent_at = ?, send_attempts = send_attempts + 1,
                last_error = NULL, updated_at = ?
          WHERE id = ?`,
      )
      .run(STATUS.SENT, now, now, id);
  }

  markFailed(id, error, permanent = false) {
    const now = new Date().toISOString();

    if (permanent) {
      // Park at the attempt ceiling so it is never picked up again.
      this.db
        .prepare(
          `UPDATE items SET status = ?, send_attempts = ?, last_error = ?, updated_at = ? WHERE id = ?`,
        )
        .run(STATUS.FAILED, config.limits.maxSendAttempts, String(error).slice(0, 2000), now, id);

      return;
    }

    this.db
      .prepare(
        `UPDATE items
            SET status = ?, send_attempts = send_attempts + 1, last_error = ?, updated_at = ?
          WHERE id = ?`,
      )
      .run(STATUS.FAILED, String(error).slice(0, 2000), now, id);
  }

  markSkipped(id, reason) {
    const now = new Date().toISOString();

    this.db
      .prepare('UPDATE items SET status = ?, last_error = ?, updated_at = ? WHERE id = ?')
      .run(STATUS.SKIPPED, String(reason).slice(0, 2000), now, id);
  }

  /**
   * Count an attempt without changing status, so a throttled row stays pending
   * and is picked up again later.
   */
  recordAttempt(id, note) {
    const now = new Date().toISOString();

    this.db
      .prepare(
        'UPDATE items SET send_attempts = send_attempts + 1, last_error = ?, updated_at = ? WHERE id = ?',
      )
      .run(String(note).slice(0, 2000), now, id);
  }

  resetForResend(id) {
    const now = new Date().toISOString();

    this.db
      .prepare('UPDATE items SET status = ?, send_attempts = 0, updated_at = ? WHERE id = ?')
      .run(STATUS.PENDING, now, id);
  }

  stats() {
    return this.db
      .prepare('SELECT status, COUNT(*) AS count FROM items GROUP BY status')
      .all();
  }

  /**
   * No rows at all: either a first run, or a host that wiped the database.
   *
   * The two are indistinguishable from here, and the pipeline treats them the
   * same — see its seeding path. Asked before the run stores anything, because
   * the first insert is what stops it being true.
   */
  isEmpty(source = null) {
    if (source) {
      return this.db.prepare('SELECT 1 FROM items WHERE source = ? LIMIT 1').get(source) === undefined;
    }
    return this.db.prepare('SELECT 1 FROM items LIMIT 1').get() === undefined;
  }

  close() {
    this.db.close();
  }
}

/** A stored row back to the item shape the score and filters read. */
export function toItem(row) {
  return {
    source: row.source,
    externalId: row.external_id,
    itemType: row.item_type,
    title: row.title,
    company: row.company,
    location: row.location,
    url: row.url,
    keyword: row.keyword,
    postedAt: row.posted_at,
    excerpt: row.excerpt,
    market: row.market,
    board: row.board,
    seniority: row.seniority,
    workplace: row.workplace,
    remote: Boolean(row.remote),
    yearsMin: row.years_min,
    yearsMax: row.years_max,
    salary: row.salary,
    score: row.score,
  };
}

function parseJsonObject(json) {
  if (!json) return {};
  try {
    const value = JSON.parse(json);
    return value && typeof value === 'object' ? value : {};
  } catch {
    return {};
  }
}

export default Store;
