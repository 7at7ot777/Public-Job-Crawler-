/*
 * A source that answers fine but parses nothing, run after run, has usually
 * been broken by a change on the site, and says nothing about it. Ported from
 * the Android app's Breakage.kt: a source that used to find jobs, now silent
 * STREAK clean runs in a row, sends one alert a day. A block or an error is
 * not silence; those are reported already.
 *
 * Nor is a run that listed jobs and dropped every one itself (seen before,
 * too old) before handing any back: only a source that reports `listed`
 * (source.listed after fetch) can be told apart.
 */

export const STREAK = 3;
export const LOOKBACK_DAYS = 14;
export const MIN_YIELDING = 3;

/** Whether history has enough runs that found something to make silence suspicious. */
export const yieldsHistorically = (history) =>
  history.filter((run) => !run.blocked && !run.error && run.parsed > 0).length >= MIN_YIELDING;

/** A run that ended clean, handed back nothing, and listed nothing either. */
export const silent = (parsed, listed, blocked, error) =>
  !blocked && !error && parsed === 0 && (listed ?? 0) === 0;

/** The next streak of silent runs; null leaves it as it was (a block or an error says nothing either way). */
export function nextStreak(previous, parsed, blocked, error, yields, listed = null) {
  if (blocked || error) return null;
  if (!silent(parsed, listed, blocked, error)) return 0;
  if (!yields) return 0;
  return previous + 1;
}

export function quietMessage(key, streak, lastYield) {
  const when = lastYield
    ? `, though it found jobs until ${new Date(lastYield).toISOString().slice(0, 16).replace('T', ' ')} UTC`
    : '';
  return `${key} has parsed nothing for ${streak} runs in a row${when}. The site may have changed.`;
}
