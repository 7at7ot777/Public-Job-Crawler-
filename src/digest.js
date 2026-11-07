import { escapeHtml, htmlLink } from './text.js';
import { parseRegion, regionOf, REGION } from './region.js';

/*
 * Digest mode, ported from the Android app's Digest.kt: the best matches go
 * out at once, the rest wait and arrive as one message every few hours, so the
 * channel is not a wall of near-misses. Off by default (DIGEST_ENABLED).
 */

export const TOP = 20;
export const STATE = 'digest.lastSent';
/** Under Telegram's 4096, with room for the header. */
export const BUDGET = 3900;
/** A row waiting longer than this without making the top TOP is let go. */
export const MAX_WAIT_MS = 24 * 3_600_000;

/** [send now, keep for the digest]. Off, everything goes now. A row with no score waits. */
export function splitForDigest(rows, on, threshold) {
  if (!on) return [rows, []];
  const now = [];
  const later = [];
  for (const row of rows) ((row.score ?? 0) >= threshold ? now : later).push(row);
  return [now, later];
}

/** Due every everyMs while on; once turned off, the leftovers still go out on the next run. */
export function digestDue(now, lastSent, pending, on, everyMs) {
  return pending > 0 && (!on || lastSent == null || now - lastSent >= everyMs || now < lastSent);
}

const regionOfRow = (row) => parseRegion(row.region) ?? regionOf(row);

/**
 * The message, Egypt then the Gulf then worldwide, best first in each, and
 * the ids it actually holds: only those are marked sent.
 *
 * @returns {{text: string, ids: number[]}}
 */
export function renderDigest(rows) {
  // The best TOP by score, whatever their region; then shown region by region.
  const ordered = [...rows]
    .sort((a, b) => (b.score ?? 0) - (a.score ?? 0))
    .slice(0, TOP)
    .sort((a, b) => regionOfRow(a).order - regionOfRow(b).order || (b.score ?? 0) - (a.score ?? 0));

  const ids = [];
  let text = `🗞 <b>Digest</b> · ${rows.length} more ${rows.length === 1 ? 'match' : 'matches'}\n`;
  let region = null;

  for (const row of ordered) {
    const rowRegion = regionOfRow(row) ?? REGION.EGYPT;
    const head = rowRegion !== region ? `\n${rowRegion.flag} <b>${escapeHtml(rowRegion.label)}</b>\n` : '';
    const line =
      `${row.score != null ? `⭐ ${row.score} ` : '• '}${htmlLink(row.url, String(row.title).slice(0, 90))}` +
      `${row.company ? ` · ${escapeHtml(String(row.company).slice(0, 50))}` : ''}\n`;

    if (text.length + head.length + line.length > BUDGET) break;

    text += head + line;
    region = rowRegion;
    ids.push(row.id);
  }

  const left = rows.length - ids.length;
  if (left > 0) text += `\n…and ${left} more not shown`;

  return { text: text.trimEnd(), ids };
}
