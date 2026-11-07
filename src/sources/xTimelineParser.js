/*
 * X's SearchTimeline response, reduced to posts and the next-page cursor.
 *
 * The response is a list of timeline instructions, each carrying entries; a
 * post is an entry whose id starts `tweet-`, and the page's continuation is
 * the entry whose id starts `cursor-bottom-`. The first page delivers the
 * cursor inside TimelineAddEntries, later pages as a TimelineReplaceEntry —
 * both are read.
 */

/**
 * @returns {{tweets: Array<{id: string, text: string, createdAt: string|null,
 *            handle: string|null, name: string|null}>, cursor: string|null}}
 */
export function parseSearchTimeline(payload) {
  const instructions =
    payload?.data?.search_by_raw_query?.search_timeline?.timeline?.instructions ?? [];

  const tweets = [];
  let cursor = null;

  for (const instruction of instructions) {
    const entries = instruction.entries ?? (instruction.entry ? [instruction.entry] : []);

    for (const entry of entries) {
      const entryId = String(entry.entryId ?? '');

      if (entryId.startsWith('cursor-bottom-')) {
        cursor = entry.content?.value ?? entry.content?.itemContent?.value ?? cursor;
        continue;
      }

      if (!entryId.startsWith('tweet-')) continue;

      const tweet = toTweet(entry.content?.itemContent?.tweet_results?.result);
      if (tweet) tweets.push(tweet);
    }
  }

  return { tweets, cursor };
}

function toTweet(result) {
  // Posts from accounts with reduced visibility arrive wrapped one level down.
  const tweet = result?.__typename === 'TweetWithVisibilityResults' ? result.tweet : result;
  if (!tweet?.rest_id || !tweet.legacy) return null;

  // Long posts carry the full text in note_tweet; full_text is cut at 280.
  const text =
    tweet.note_tweet?.note_tweet_results?.result?.text ?? tweet.legacy.full_text ?? '';

  const user = tweet.core?.user_results?.result;
  // X moved the handle and name out of `legacy` into `core` in 2025; read both.
  const handle = user?.core?.screen_name ?? user?.legacy?.screen_name ?? null;
  const name = user?.core?.name ?? user?.legacy?.name ?? null;

  const created = tweet.legacy.created_at ? new Date(tweet.legacy.created_at) : null;

  return {
    id: String(tweet.rest_id),
    text: decodeEntities(text),
    createdAt: created && !Number.isNaN(created.getTime()) ? created.toISOString() : null,
    handle,
    name,
  };
}

/** full_text arrives HTML-escaped: `&amp;`, `&lt;`, `&gt;`. */
function decodeEntities(text) {
  return text.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
}

export default parseSearchTimeline;
