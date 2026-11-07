/*
 * The queries a posts source asks a search engine for, and which of them it
 * may skip this run.
 *
 * Shared by linkedin_posts and x_posts, which differ in what they do with a
 * result but not in how they spend queries. Every query here is a billed
 * request on Serper, so this is where the cost of a run is decided.
 */

const DAY_MS = 86_400_000;

/**
 * @typedef {object} PlannedQuery
 * @property {string}   key      stable identity, for the yield record
 * @property {string}   keyword
 * @property {string[]} places   the places the query names
 * @property {string}   query    the text sent to the engine
 */

/**
 * Keywords x places as queries.
 *
 * 'pairs' asks one query per keyword per place. 'grouped' names up to
 * groupSize places in one query as an OR group, which is the same search at a
 * fraction of the queries — provided the engine honours OR, which Google does.
 * The template's `"{location}"` (quotes included) becomes the group, so one
 * template serves both modes.
 *
 * @returns {PlannedQuery[]}
 */
export function planQueries({ template, keywords, locations, queryMode = 'pairs', groupSize = 6 }) {
  const groups =
    queryMode === 'grouped' ? chunk(locations, Math.max(1, groupSize)) : locations.map((place) => [place]);

  const queries = [];

  for (const keyword of keywords) {
    for (const places of groups) {
      queries.push({
        key: `${keyword}|${places.join('+')}`,
        keyword,
        places,
        query: fillTemplate(template, keyword, places),
      });
    }
  }

  return queries;
}

export function fillTemplate(template, keyword, places) {
  if (places.length === 1) {
    return template.replaceAll('{keyword}', keyword).replaceAll('{location}', places[0]);
  }

  const group = `(${places.map((place) => `"${place}"`).join(' OR ')})`;

  return template
    .replaceAll('{keyword}', keyword)
    .replaceAll('"{location}"', group)
    .replaceAll('{location}', group);
}

/**
 * Which place a result is about, for the message: the query's own place when
 * it named one, otherwise the first of its places the result's text mentions.
 * Null when it mentions none — the filters read the post's words for location
 * anyway, so a guess here would only mislabel the message.
 */
export function placeFor(planned, ...texts) {
  if (planned.places.length === 1) return planned.places[0];

  const haystack = texts.filter(Boolean).join(' ').toLowerCase();

  // Longest first: "New Cairo" before "Cairo".
  const byLength = [...planned.places].sort((a, b) => b.length - a.length);
  return byLength.find((place) => haystack.includes(place.toLowerCase())) ?? null;
}

/**
 * How each query has been doing, kept in the store's state table so a cold
 * query can be asked less often. Without a store nothing is skipped.
 */
export class YieldLedger {
  /**
   * @param {object|null} store         anything with getState / setState
   * @param {string}      source        the source key, to namespace the record
   * @param {{coldAfter: number, coldEveryDays: number}} settings
   */
  constructor(store, source, settings, now = () => Date.now()) {
    this.store = store;
    this.source = source;
    this.coldAfter = settings.coldAfter ?? 0;
    this.coldEveryDays = settings.coldEveryDays ?? 1;
    this.now = now;
  }

  #stateKey(planned) {
    return `${this.source}.yield.${planned.key}`;
  }

  /** @returns {{runs: number, misses: number, hits: number, lastAsked: number|null}} */
  read(planned) {
    const empty = { runs: 0, misses: 0, hits: 0, lastAsked: null };
    if (!this.store) return empty;

    try {
      return { ...empty, ...JSON.parse(this.store.getState(this.#stateKey(planned)) ?? '{}') };
    } catch {
      return empty;
    }
  }

  /**
   * Due unless it is cold — coldAfter empty runs in a row — and was asked
   * less than coldEveryDays ago.
   */
  isDue(planned) {
    if (!this.store || this.coldAfter <= 0) return true;

    const record = this.read(planned);
    if (record.misses < this.coldAfter || record.lastAsked === null) return true;

    const since = this.now() - record.lastAsked;
    // A clock set back must not stall the query for good.
    return since < 0 || since >= this.coldEveryDays * DAY_MS - 3_600_000;
  }

  /** @param {number} fresh  results the query found that were within the age cut */
  record(planned, fresh) {
    if (!this.store) return;

    const record = this.read(planned);

    this.store.setState(
      this.#stateKey(planned),
      JSON.stringify({
        runs: record.runs + 1,
        misses: fresh > 0 ? 0 : record.misses + 1,
        hits: record.hits + fresh,
        lastAsked: this.now(),
      }),
    );
  }

  /**
   * Every query's record, for `--stats`: which ones earn their cost.
   *
   * @returns {{query: string, runs: number, hits: number, misses: number}[]}
   */
  report() {
    if (!this.store?.statesWithPrefix) return [];

    const prefix = `${this.source}.yield.`;

    return this.store.statesWithPrefix(prefix).flatMap(({ key, value }) => {
      try {
        const { runs = 0, hits = 0, misses = 0 } = JSON.parse(value);
        return [{ query: key.slice(prefix.length), runs, hits, misses }];
      } catch {
        return [];
      }
    });
  }
}

function chunk(values, size) {
  const chunks = [];
  for (let i = 0; i < values.length; i += size) chunks.push(values.slice(i, i + size));
  return chunks;
}
