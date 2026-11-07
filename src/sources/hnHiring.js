import logger from '../logger.js';
import { containsAny, decodeEntities, detag, fold, stripHtml } from '../text.js';
import BaseSource from './baseSource.js';
import { arr, judgeFeedJob, newCounts, parseJson, str, summarize, tally } from './abroad.js';

export const KEY = 'hn_hiring';

const STORIES_URL = 'https://hn.algolia.com/api/v1/search_by_date';

/**
 * Hacker News' monthly "Ask HN: Who is hiring?", through its free search API
 * (Algolia), ported from the Android app's HnHiring.kt. Each top-level comment
 * is one company: "Acme | Backend Engineer | Berlin | REMOTE | VISA", then the
 * ad. Newest comments first; each judged once like a remote feed's job.
 */
export class HnHiringSource extends BaseSource {
  constructor(client = null) {
    super(KEY, { label: 'Hacker News', headers: { Accept: 'application/json' }, client });
  }

  async fetch() {
    const counts = newCounts();

    try {
      const stories = await this.get(STORIES_URL, { tags: 'story,author_whoishiring', hitsPerPage: 5 });
      const story = stories == null ? null : latestThread(stories);

      if (!story) {
        logger.warn('Hacker News: no Who is hiring thread found');
        return [];
      }

      const comments = await this.get(STORIES_URL, { tags: `comment,story_${story}`, hitsPerPage: 300 });
      if (comments == null) return [];

      const checked = this.checked();
      const list = ads(comments, story);
      counts.listed = list.length;

      for (const ad of list) {
        const id = `hn:${ad.id}`;
        if (checked.has(id)) {
          counts.seen += 1;
          continue;
        }
        this.markChecked(id);
        counts.judged += 1;
        tally(judgeFeedJob(ad, KEY), counts);
      }
    } finally {
      this.addListed(counts.listed);
      logger.info('Hacker News', summarize(counts));
    }

    return counts.kept;
  }
}

/** The newest "Who is hiring?" story's id; the same account also posts "Who wants to be hired?". */
export function latestThread(body) {
  const hit = arr(parseJson(body)?.hits).find((story) => /who is hiring/i.test(story?.title ?? ''));
  return str(hit?.objectID);
}

const ROLE = /\b(engineer|developer|programmer|backend|back-end|full[- ]?stack|swe)\b/i;
const VISA = /\bvisa\b/i;
const NO_VISA = /\bno\s+visa\b|\bvisa:\s*no\b/i;

/** Each company's ad in the thread: top-level comments only, replies are not ads. */
export function ads(body, story) {
  return arr(parseJson(body)?.hits)
    .map((hit) => {
      if (String(hit?.parent_id) !== String(story)) return null;
      const id = str(hit.objectID);
      if (!id) return null;

      const html = String(hit.comment_text ?? '');
      const first = detag(decodeEntities(html.split('<p>')[0]));
      if (!first) return null;

      const parts = first.split('|').map((part) => part.trim()).filter(Boolean);
      if (parts.length < 2) return null;

      const title = parts.slice(1).find((part) => ROLE.test(part)) ?? parts[1];
      const place = parts.slice(1).filter((part) => part !== title).join(' · ') || null;
      const remote = containsAny(fold(first), ['remote']);

      let text = stripHtml(decodeEntities(html));
      // "VISA" in the header is the thread's way of saying the company sponsors.
      if (VISA.test(first) && !NO_VISA.test(first)) text += '\nVisa sponsorship available.';

      return {
        feed: 'hn',
        id,
        title: title.slice(0, 120),
        company: parts[0].slice(0, 80),
        url: `https://news.ycombinator.com/item?id=${id}`,
        location: place,
        restriction: null,
        description: text,
        postedAt: str(hit.created_at),
        remote,
      };
    })
    .filter(Boolean);
}

export default HnHiringSource;
