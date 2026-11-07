import * as cheerio from 'cheerio';
import logger from '../logger.js';

/**
 * Turns a DuckDuckGo HTML result page into `{url, title, snippet}` records.
 *
 * DuckDuckGo is the discovery half of the posts source: LinkedIn has no public
 * post search for logged-out clients, but it does let search engines index
 * public posts, so the engine's index is the only free way in.
 *
 * Parsed defensively for the same reason as the job cards: the markup is not a
 * contract, and one unreadable result must never cost the page.
 */
export function parseSearchResults(html) {
  const trimmed = (html ?? '').trim();
  if (trimmed === '') return [];

  const $ = cheerio.load(trimmed);
  const results = [];

  $('.result, .web-result').each((_, element) => {
    try {
      const node = $(element);

      // Sponsored rows carry the same result markup but never a LinkedIn post.
      if (node.hasClass('result--ad') || node.find('.badge--ad').length > 0) return;

      const href = node.find('a.result__a').first().attr('href');
      const url = resolveUrl(href);
      if (!url) return;

      results.push({
        url,
        title: clean(node.find('a.result__a').first().text()),
        snippet: clean(node.find('.result__snippet').first().text()),
      });
    } catch (error) {
      logger.warn('Failed to parse a search result', { error: error.message });
    }
  });

  return results;
}

/**
 * The HTML endpoint usually hands back its own redirector
 * (`//duckduckgo.com/l/?uddg=<encoded target>`), so the real URL has to be
 * unwrapped before it can be recognised as a LinkedIn post.
 */
export function resolveUrl(href) {
  if (!href) return null;

  const raw = href.startsWith('//') ? `https:${href}` : href;

  let parsed;
  try {
    parsed = new URL(raw, 'https://duckduckgo.com');
  } catch {
    return null;
  }

  const target = parsed.searchParams.get('uddg');
  if (target) {
    try {
      return new URL(target).toString();
    } catch {
      return null;
    }
  }

  return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.toString() : null;
}

/**
 * DuckDuckGo answers a suspected bot with a 200 and an "anomaly" interstitial
 * instead of an error status, which would otherwise read as "no results" and
 * silently stop the crawl finding anything.
 */
export function isChallengePage(html) {
  const text = (html ?? '').toLowerCase();
  if (text === '') return false;

  return (
    text.includes('anomaly.js') ||
    text.includes('unusual traffic') ||
    text.includes('if this error persists, please let us know')
  );
}

function clean(value) {
  const text = (value ?? '').replace(/\s+/g, ' ').trim();
  return text === '' ? null : text;
}

export default parseSearchResults;
