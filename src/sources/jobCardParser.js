import * as cheerio from 'cheerio';
import logger from '../logger.js';

export const SOURCE_KEY = 'linkedin_jobs';

/**
 * Turns the HTML fragment returned by the guest job-search endpoint into item
 * objects.
 *
 * This is the fragile part of the project: LinkedIn changes its markup without
 * notice. Every card is parsed defensively — a card that no longer matches is
 * logged and skipped, never fatal to the page or the run.
 */
export function parseJobCards(html, keyword = null, market = null) {
  const trimmed = (html ?? '').trim();
  if (trimmed === '') return [];

  const $ = cheerio.load(`<ul>${trimmed}</ul>`);
  const items = [];

  $('li').each((_, element) => {
    try {
      const item = parseCard($, $(element), keyword, market);
      if (item) items.push(item);
    } catch (error) {
      logger.warn('Failed to parse a LinkedIn job card', {
        error: error.message,
        snippet: $.html(element).slice(0, 300),
      });
    }
  });

  return items;
}

function parseCard($, card, keyword, market) {
  const externalId = extractExternalId(card);
  const url = attr(card, 'a.base-card__full-link', 'href') ?? attr(card, 'a[href*="/jobs/view/"]', 'href');
  const title = text(card, '.base-search-card__title') ?? text(card, '.sr-only');

  // LinkedIn pads the list with promo and empty <li>s.
  if (!externalId || !url || !title) return null;

  return {
    source: SOURCE_KEY,
    externalId,
    itemType: 'job',
    title,
    url: stripQuery(url),
    company: text(card, '.base-search-card__subtitle a') ?? text(card, '.base-search-card__subtitle'),
    location: text(card, '.job-search-card__location'),
    // The market this search asked LinkedIn for. A US card reads "Chicago, IL"
    // and never names the country, so the search is the only reliable record
    // of where the posting is.
    market,
    postedAt: extractPostedAt(card),
    keyword,
    excerpt: null,
  };
}

/** The job id lives in data-entity-urn="urn:li:jobPosting:4012345678". */
function extractExternalId(card) {
  const urn =
    attr(card, 'div.base-card', 'data-entity-urn') ?? attr(card, '[data-entity-urn]', 'data-entity-urn');

  if (urn) {
    const match = urn.match(/(\d+)\s*$/);
    if (match) return match[1];
  }

  // Fall back to the id embedded in the job URL.
  const href = attr(card, 'a[href*="/jobs/view/"]', 'href');
  if (href) {
    const match = href.match(/\/jobs\/view\/(?:[^/?]*-)?(\d+)/);
    if (match) return match[1];
  }

  return null;
}

function extractPostedAt(card) {
  const datetime = attr(card, 'time[datetime]', 'datetime') ?? attr(card, 'time', 'datetime');
  if (!datetime) return null;

  const parsed = new Date(datetime);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

/**
 * Strip LinkedIn's tracking query string; it makes identical jobs look
 * different and bloats the stored URL.
 */
function stripQuery(url) {
  const index = url.indexOf('?');
  return (index === -1 ? url : url.slice(0, index)).trim();
}

function text(card, selector) {
  const node = card.find(selector).first();
  if (node.length === 0) return null;

  const value = node.text().replace(/\s+/g, ' ').trim();
  return value === '' ? null : value;
}

function attr(card, selector, attribute) {
  const node = card.find(selector).first();
  if (node.length === 0) return null;

  const value = (node.attr(attribute) ?? '').replace(/\s+/g, ' ').trim();
  return value === '' ? null : value;
}

export default parseJobCards;
