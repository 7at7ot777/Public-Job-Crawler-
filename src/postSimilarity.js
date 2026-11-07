/*
 * Is this hiring post one we have already seen under another link?
 *
 * A post's id is its activity id, and a repost, a reshare or a second
 * recruiter pasting the same ad each get a new one. The store's
 * company + title fingerprint does not help either: for a post the "company"
 * is the author, and a different author — or the same one spelled from the
 * URL slug instead of the page — is a different key. What stays the same is
 * the ad itself, so that is what is compared.
 */

/** Below this many words there is too little text to call two posts the same. */
export const MIN_TOKENS = 12;

export const DEFAULT_THRESHOLD = 0.8;

/*
 * Words every hiring post carries. Left in, they make any two ads look alike;
 * the comparison has to rest on what is specific to one.
 */
const STOP_WORDS = new Set([
  // English
  'the', 'and', 'or', 'of', 'to', 'in', 'on', 'at', 'for', 'with', 'a', 'an',
  'is', 'are', 'be', 'we', 'our', 'you', 'your', 'us', 'this', 'that', 'will',
  'as', 'by', 'from', 'if', 'it', 'its', 'please', 'looking', 'hiring',
  'join', 'team', 'send', 'cv', 'apply', 'job', 'role', 'position',
  // Arabic
  'في', 'من', 'على', 'الى', 'إلى', 'عن', 'مع', 'او', 'أو', 'و', 'ان', 'أن',
  'هذا', 'هذه', 'التي', 'الذي', 'لدى', 'كل',
]);

/**
 * The words of a post that say what it is about, as a set.
 *
 * Normalized the way the store normalizes company and title — lowercase,
 * NFKD, letters and digits of any script — after links, hashtags and
 * mentions are dropped, since a repost changes exactly those.
 */
export function postTokens(text) {
  if (!text) return new Set();

  const cleaned = String(text)
    .toLowerCase()
    .normalize('NFKD')
    .replace(/https?:\/\/\S+|www\.\S+/g, ' ')
    .replace(/[#@][\p{L}\p{N}_]+/gu, ' ')
    // NFKD splits accented letters; drop the combining marks it leaves.
    .replace(/\p{M}+/gu, '')
    .replace(/[^\p{L}\p{N}]+/gu, ' ');

  const tokens = new Set();

  for (const word of cleaned.split(' ')) {
    if (word.length < 2 || STOP_WORDS.has(word)) continue;
    tokens.add(word);
  }

  return tokens;
}

/**
 * Share of the smaller set found in the larger one.
 *
 * Containment rather than Jaccard, because the two sides are often unequal
 * by nature: a post behind the auth wall is known only by its search snippet,
 * the same post fetched later by its whole body. Jaccard scores that pair low;
 * containment sees the snippet sitting inside the body.
 */
export function similarity(a, b) {
  const [small, large] = a.size <= b.size ? [a, b] : [b, a];
  if (small.size === 0) return 0;

  let shared = 0;
  for (const token of small) if (large.has(token)) shared += 1;

  return shared / small.size;
}

function emailsOf(value) {
  const list = Array.isArray(value) ? value : String(value ?? '').split(',');
  return new Set(list.map((email) => email.trim().toLowerCase()).filter(Boolean));
}

/**
 * How closely two posts match, 0..1, or 0 when there is too little to judge.
 *
 * Each side is `{ excerpt, title, contactEmails }`; `contactEmails` may be the
 * array an item carries or the comma-joined string a row stores.
 *
 * Errs towards "different": a false match silently swallows a real vacancy,
 * which is worse than sending one twice.
 */
export function matchScore(a, b, threshold = DEFAULT_THRESHOLD) {
  const bodyA = postTokens(a.excerpt ?? a.title);
  const bodyB = postTokens(b.excerpt ?? b.title);

  if (Math.min(bodyA.size, bodyB.size) >= MIN_TOKENS) {
    const score = similarity(bodyA, bodyB);
    if (score >= threshold) return score;
  }

  /*
   * A reworded ad still names the same inbox. The same address alone is not
   * enough — one HR mailbox advertises every opening a company has — so the
   * title has to agree as well.
   */
  const emailsA = emailsOf(a.contactEmails);
  const sharesEmail = [...emailsOf(b.contactEmails)].some((email) => emailsA.has(email));

  if (sharesEmail) {
    const titleA = postTokens(a.title);
    const titleB = postTokens(b.title);

    if (Math.min(titleA.size, titleB.size) >= 2) {
      const score = similarity(titleA, titleB);
      if (score >= 0.6) return score;
    }
  }

  return 0;
}

export const isNearDuplicate = (a, b, threshold = DEFAULT_THRESHOLD) =>
  matchScore(a, b, threshold) > 0;
