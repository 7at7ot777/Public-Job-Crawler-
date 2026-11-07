/**
 * Ages written for people ("3 days ago"), turned into something absolute.
 *
 * Three sources need this and none of them agree on where it appears: Wuzzuf
 * prints an age instead of a date on the job page, Serper returns one beside
 * a search result, and Tanqeeb prints a real date and needs none of it. It
 * lives here rather than in one parser so the second caller does not copy it.
 */

/** "3 days ago", "an hour ago", "1 month ago". */
const AGE_RE = /(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago/i;

const AGE_MS = {
  second: 1_000,
  minute: 60_000,
  hour: 3_600_000,
  day: 86_400_000,
  week: 604_800_000,
  month: 2_592_000_000,
  year: 31_536_000_000,
};

/**
 * @param {string|null} text
 * @param {number} now  injectable so a test does not depend on the clock
 * @returns {string|null} ISO 8601, or null when there is no age to read
 */
export function relativeToIso(text, now = Date.now()) {
  const match = (text ?? '').match(AGE_RE);
  if (!match) return null;

  const amount = Number.parseInt(match[1], 10);
  const unit = AGE_MS[match[2].toLowerCase()];

  if (!Number.isFinite(amount) || !unit) return null;

  return new Date(now - amount * unit).toISOString();
}

/** A date the remote already wrote absolutely ("Mar 5, 2026", an ISO string). */
export function absoluteToIso(value) {
  if (!value) return null;

  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

/**
 * Whichever of the two the value turns out to be.
 *
 * Relative is tried first: `new Date('3 days ago')` is Invalid Date, but the
 * reverse order would still be wrong for a value like "1 week ago" that some
 * runtimes parse into nonsense rather than rejecting.
 */
export function toIso(value, now = Date.now()) {
  return relativeToIso(value, now) ?? absoluteToIso(value);
}

export default toIso;
