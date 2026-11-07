/**
 * "The last N hours", translated into each source's own vocabulary.
 *
 * There is no shared spelling to translate into: LinkedIn's job search takes
 * f_TPR seconds ("r86400"), while a search engine takes a coarse bucket
 * ("d", "w", "m"). So the mapping is per source and lives here, named by
 * source key, rather than being invented at each call site.
 */

const HOUR = 3600;

/** @returns {Record<string, string>} keyed by source, for `timeWindows`. */
export function windowsForHours(hours) {
  const clamped = Math.max(1, Math.round(Number(hours) || 0));

  return {
    linkedin_jobs: `r${clamped * HOUR}`,
    linkedin_posts: postsBucket(clamped),
    x_posts: postsBucket(clamped),
    // X search takes a since: date, so whole days, rounded up.
    x_session: Math.ceil(clamped / 24),
  };
}

/**
 * Search engines only offer day/week/month, so a request is rounded up to the
 * first bucket that covers it: asking for 48 hours and being handed a day
 * would quietly drop everything older than yesterday.
 */
function postsBucket(hours) {
  if (hours <= 24) return 'd';
  if (hours <= 24 * 7) return 'w';
  if (hours <= 24 * 31) return 'm';

  // Older than a month: no bucket covers it, so let the engine decide.
  return '';
}

export default windowsForHours;
