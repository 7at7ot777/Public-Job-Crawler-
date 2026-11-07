/**
 * Reading the decision log — the `decisions` table the pipeline writes for
 * every rejected, duplicate and accepted item — in the three places it is
 * asked for: the CLI (--rejected / --decisions), GET /decisions, and the
 * bot's /rejected.
 *
 * The bot runs in the small resident process, which holds no store of its
 * own; `withStore` opens the database for one read and closes it again.
 */
import Store from './store.js';

export const DECISIONS = ['rejected', 'duplicate', 'accepted'];

/** Run `read` against a short-lived store, so a resident process holds no handle. */
export function withStore(read, open = () => new Store()) {
  const store = open();

  try {
    return read(store);
  } finally {
    store.close();
  }
}

/**
 * Query parameters (HTTP, CLI) into the store's filter shape. Unknown keys are
 * ignored; bad numbers fall back to defaults rather than failing.
 */
export function decisionFilters({ decision, reason, source, since, limit, runId } = {}) {
  const sinceHours = Number.parseFloat(since ?? '');
  const max = Number.parseInt(limit ?? '', 10);

  return {
    // null is "every decision"; undefined leaves the caller's default (rejected).
    decision: decision === 'all' ? null : DECISIONS.includes(decision) ? decision : undefined,
    reason: reason || undefined,
    source: source || undefined,
    runId: runId || undefined,
    sinceHours: Number.isFinite(sinceHours) && sinceHours > 0 ? sinceHours : undefined,
    limit: Number.isFinite(max) && max > 0 ? max : undefined,
  };
}

const clip = (value, max) => {
  const text = String(value ?? '');
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
};

/**
 * A plain-text report: counts by reason, the titles rejected most often per
 * reason, then the latest rows. Short enough for one Telegram message.
 */
export function formatReport(store, filters = {}, { rows = 10 } = {}) {
  const defined = Object.fromEntries(
    Object.entries(filters).filter(([, value]) => value !== undefined),
  );
  const scoped = { decision: 'rejected', sinceHours: 24, ...defined };
  const { byReason, topRejectedTitles } = store.decisionSummary({ ...scoped, topTitles: 3 });
  const latest = store.queryDecisions({ ...scoped, limit: rows });

  const window = scoped.sinceHours ? `last ${scoped.sinceHours}h` : 'all time';
  const heading = [scoped.decision ?? 'all decisions', scoped.reason, scoped.source, window]
    .filter(Boolean)
    .join(' · ');

  if (byReason.length === 0) return `Decisions (${heading}): nothing recorded.`;

  const lines = [`Decisions (${heading})`, ''];

  for (const { decision, reason, count } of byReason) {
    lines.push(`${count}× ${decision}${reason ? `: ${reason}` : ''}`);

    for (const { title, count: times } of topRejectedTitles[reason] ?? []) {
      if (decision === 'rejected') lines.push(`   – ${clip(title, 60)} (${times})`);
    }
  }

  lines.push('', `Latest ${latest.length}:`);

  for (const row of latest) {
    const why = [row.reason, row.detail ?? row.duplicate_of].filter(Boolean).join(' — ');
    const company = row.company ? ` @ ${clip(row.company, 30)}` : '';
    lines.push(`• [${row.source}] ${clip(row.title, 60)}${company}`);
    if (why) lines.push(`   ${clip(why, 120)}`);
  }

  return lines.join('\n');
}
