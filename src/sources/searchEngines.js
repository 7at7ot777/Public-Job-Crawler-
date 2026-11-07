import { BlockedError } from '../errors.js';
import { parseSearchResults, isChallengePage } from './postSearchParser.js';
import { toIso } from '../dates.js';

/*
 * Search engines, as the posts source sees them: build a query, turn a response
 * body into `{url, title, snippet}` records.
 *
 * An engine is `{url, parse}` plus one of two ways to say what to ask for:
 * `params` for a query string (a search page), or `body` for a JSON POST (a
 * search API), with `headers` for whatever credential it needs. The source
 * picks the shape off whichever the engine defines.
 *
 * There are three because free search is unreliable in different ways, and the
 * reliable one is not free. Serper is a paid Google API — sub-cent per query,
 * JSON in and out, and it answers from a datacentre address, which is the one
 * thing neither free option does. A SearXNG instance you run yourself is free
 * and dependable, but it has to run somewhere beside the crawler and it still
 * scrapes upstream engines that rate-limit cloud addresses. DuckDuckGo's HTML
 * endpoint needs nothing installed and mostly answers a suspected bot with an
 * "anomaly" page instead of results.
 *
 * Bing is deliberately absent: scraped without a session it returns HTTP 200
 * with results for an entirely unrelated query, which is worse than a block
 * because it looks like a successful empty crawl.
 */

/** Shared vocabulary (d/w/m) mapped to whatever the engine calls it. */
const WINDOWS = {
  searxng: { d: 'day', w: 'week', m: 'month' },
  duckduckgo: { d: 'd', w: 'w', m: 'm' },
  // Google's own spelling, which Serper passes straight through as `tbs`.
  serper: { d: 'qdr:d', w: 'qdr:w', m: 'qdr:m' },
};

const window = (engine, value) => WINDOWS[engine][(value ?? '').trim().toLowerCase()] ?? '';

export const ENGINES = {
  /**
   * Serper.dev — Google's index behind a JSON API, billed per query.
   *
   * The only option here that works from a datacentre address, which is what
   * makes it the default: the free engines are blocked by the upstreams they
   * scrape long before they are blocked by anything the crawler does. It also
   * passes Google's operators through untouched, so the OR/negative terms in
   * the query template do real filtering before a credit is spent.
   */
  serper: {
    key: 'serper',
    label: 'Serper',
    pageSize: 10,
    // Every request costs a credit: counted and capped by SerperBudget, and
    // paced by config.serper rather than the crawl-wide delay.
    billed: true,

    url: (settings) => settings.serperUrl,

    headers: (settings) => ({ 'X-API-KEY': settings.serperApiKey }),

    body: (settings, { query, page, timeWindow }) => ({
      q: query,
      // Country and interface language. `gl` is what actually scopes results
      // to a market; the location term in the query only biases them.
      gl: settings.serperCountry,
      hl: settings.serperLanguage,
      num: 10,
      // Serper pages are 1-based.
      page: page + 1,
      tbs: window('serper', timeWindow),
    }),

    parse(body) {
      let payload;

      try {
        payload = JSON.parse(body);
      } catch {
        // Anything that is not JSON here is an error page or a proxy in the
        // way. Named rather than parsed as zero results.
        throw new BlockedError(
          `Serper did not return JSON: ${body.slice(0, 200).replace(/\s+/g, ' ').trim()}`,
        );
      }

      if (payload.message && !Array.isArray(payload.organic)) {
        // Serper states its own refusals — out of credits, bad key — in this
        // field, with an HTTP status the client has already let through.
        const error = new BlockedError(`Serper refused the request: ${payload.message}`);
        error.alert = 'serper-refused';
        throw error;
      }

      if (!Array.isArray(payload.organic)) return [];

      return payload.organic.map((result) => ({
        url: result.link ?? null,
        title: clean(result.title),
        snippet: clean(result.snippet),
        // Google dates a result either way — "3 days ago" or "5 Mar 2026" —
        // depending on how old it is.
        postedAt: toIso(result.date),
      }));
    },
  },

  /**
   * A SearXNG instance — ideally your own; docker-compose.yml has one. It
   * federates the engines it is configured with and returns JSON, so results
   * survive a markup change at the upstream engine.
   */
  searxng: {
    key: 'searxng',
    label: 'SearXNG',
    pageSize: 10,

    url: (settings) => `${settings.searxngUrl.replace(/\/+$/, '')}/search`,

    params: (settings, { query, page, timeWindow }) => ({
      q: query,
      format: 'json',
      // SearXNG pages are 1-based.
      pageno: page + 1,
      time_range: window('searxng', timeWindow),
      safesearch: 0,
      language: settings.language,
    }),

    parse(body) {
      let payload;

      try {
        payload = JSON.parse(body);
      } catch {
        /*
         * JSON is not on by default. A stock instance answers this request with
         * its HTML page, which parses as nothing at all — so say what to change
         * rather than reporting an empty crawl.
         */
        throw new BlockedError(
          'SearXNG did not return JSON. Add `formats: [html, json]` under `search:` ' +
            'in its settings.yml and restart it.',
        );
      }

      if (!Array.isArray(payload.results)) return [];

      return payload.results.map((result) => ({
        url: result.url ?? null,
        title: clean(result.title),
        snippet: clean(result.content),
        postedAt: toIso(result.publishedDate),
      }));
    },
  },

  /**
   * DuckDuckGo's HTML endpoint: nothing to install, but it decides on its own
   * whether you look like a browser. Worth trying before standing up SearXNG;
   * not worth relying on.
   */
  duckduckgo: {
    key: 'duckduckgo',
    label: 'DuckDuckGo',
    pageSize: 30,

    url: () => 'https://html.duckduckgo.com/html/',

    params: (settings, { query, page, timeWindow }) => ({
      q: query,
      df: window('duckduckgo', timeWindow),
      kl: settings.region,
      s: page * 30,
    }),

    parse(body) {
      // The challenge page is served with HTTP 200, so the client cannot catch
      // it: unrecognised as a block, it reads as "no results".
      if (isChallengePage(body)) {
        throw new BlockedError(
          'DuckDuckGo served an anomaly page instead of results. It has decided this ' +
            'address looks automated; use the searxng engine instead.',
        );
      }

      return parseSearchResults(body).map((result) => ({ ...result, postedAt: null }));
    },
  },
};

/** @throws {Error} naming the valid engines, since a typo here finds nothing. */
export function resolveEngine(key) {
  const engine = ENGINES[(key ?? '').trim().toLowerCase()];

  if (!engine) {
    throw new Error(
      `Unknown search engine "${key}". Valid values: ${Object.keys(ENGINES).join(', ')}.`,
    );
  }

  return engine;
}

function clean(value) {
  const text = (value ?? '').replace(/\s+/g, ' ').trim();
  return text === '' ? null : text;
}

export default ENGINES;
