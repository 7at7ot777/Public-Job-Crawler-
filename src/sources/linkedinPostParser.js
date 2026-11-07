import * as cheerio from 'cheerio';

/**
 * Pulls author, body and timestamp off a public LinkedIn post permalink.
 *
 * Logged-out post pages come in two shapes: the full render, and a stub behind
 * the auth wall. Both still carry Open Graph tags, so the og: values are the
 * fallback chain rather than the last resort — they are often all we get, and
 * they hold enough text for the filters to judge the post.
 */
export function parsePost(html) {
  const empty = { author: null, text: null, postedAt: null, walled: false };

  const trimmed = (html ?? '').trim();
  if (trimmed === '') return empty;

  const $ = cheerio.load(trimmed);

  const ogTitle = meta($, 'og:title');
  const ogDescription = meta($, 'og:description');

  const body =
    text($, '.attributed-text-segment-list__content') ??
    text($, '.feed-shared-update-v2__description') ??
    text($, '.share-update-card__update-text') ??
    ogDescription;

  const author =
    text($, '.base-main-feed-card__entity-lockup .update-components-actor__title') ??
    text($, '.feed-shared-actor__name') ??
    text($, '.share-update-card__actor-text') ??
    authorFromOgTitle(ogTitle);

  return {
    author,
    text: body,
    postedAt: extractPostedAt($),
    // No body and no og: description means the page rendered as a login prompt.
    walled: body === null,
  };
}

/**
 * LinkedIn titles a post page "<Author> on LinkedIn: <first line of the post>",
 * which is the only author attribution the walled render keeps.
 */
export function authorFromOgTitle(title) {
  if (!title) return null;

  const match = title.match(/^(.*?)\s+on\s+LinkedIn\b/i);
  const author = match ? match[1].trim() : null;

  return author === '' ? null : author;
}

/** The post text without the "<Author> on LinkedIn:" prefix LinkedIn adds. */
export function stripAuthorPrefix(title) {
  if (!title) return null;

  const match = title.match(/^.*?\s+on\s+LinkedIn:\s*(.+)$/is);
  const body = (match ? match[1] : title).trim();

  return body === '' ? null : body;
}

function extractPostedAt($) {
  const published =
    meta($, 'article:published_time') ??
    $('time[datetime]').first().attr('datetime') ??
    null;

  if (!published) return null;

  const parsed = new Date(published);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

function meta($, property) {
  const node = $(`meta[property="${property}"], meta[name="${property}"]`).first();
  if (node.length === 0) return null;

  const value = (node.attr('content') ?? '').replace(/\s+/g, ' ').trim();
  return value === '' ? null : value;
}

function text($, selector) {
  const node = $(selector).first();
  if (node.length === 0) return null;

  const value = node.text().replace(/\s+/g, ' ').trim();
  return value === '' ? null : value;
}

export default parsePost;
