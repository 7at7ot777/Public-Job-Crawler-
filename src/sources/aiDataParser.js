import { arabicTerms } from '../config.js';
import { containsAny, isRemote } from '../filters.js';
import { fingerprintOf } from '../store.js';

/**
 * The pure half of the ai_data source: what kind of data a labelling or
 * assessment job works on, and whether the posting is somewhere wanted.
 *
 * Kept apart from the crawl so the rules can be tested against plain objects,
 * the way every other parser in this folder is.
 */

/*
 * What the work is done on, read from the title and the description. A job
 * may name several ("Arabic speech transcription" is Arabic and Audio). The
 * Arabic terms are there because Gulf and Egyptian vendors advertise in
 * Arabic as often as in English.
 */
export const MODALITIES = [
  {
    label: 'Arabic',
    terms: arabicTerms,
  },
  {
    label: 'Audio',
    terms: [
      'audio',
      'speech',
      'voice',
      'asr',
      'tts',
      'utterance',
      'utterances',
      'transcription',
      'transcriptionist',
      'transcriber',
      'صوت',
      'صوتي',
      'صوتية',
      'تفريغ',
    ],
  },
  {
    label: 'Image',
    terms: [
      'image',
      'images',
      'photo',
      'photos',
      'video',
      'videos',
      'bounding box',
      'bounding boxes',
      'segmentation',
      'computer vision',
      'lidar',
      'صور',
      'صورة',
      'فيديو',
    ],
  },
  {
    label: 'Text',
    terms: [
      'text',
      'nlp',
      'llm',
      'llms',
      'prompt',
      'prompts',
      'rlhf',
      'chatbot',
      'search quality',
      'content evaluation',
      'نص',
      'نصوص',
    ],
  },
];

/**
 * The modalities a posting names, in MODALITIES order, as one label for the
 * message's "Matched" line: "Arabic · Audio". Null when it names none, which
 * is common for a general "AI Trainer" ad and not a reason to drop it.
 */
export function modalitiesOf(item) {
  const text = [item.title, item.excerpt].filter(Boolean).join(' \n ');
  const found = MODALITIES.filter(({ terms }) => containsAny(text, terms)).map(
    ({ label }) => label,
  );

  return found.length > 0 ? found.join(' · ') : null;
}

/**
 * Whether a posting is one a candidate in the wanted places can take.
 *
 * Labelling work is done from anywhere, so a borderless search or a vendor's
 * own board returns jobs in every country. Kept are the ones placed in a
 * searched market (the source sets `market` when the job really is there),
 * the remote ones, and the ones whose title or location names a wanted place.
 * An on-site job in Manila is not.
 */
export function placeWanted(item, places) {
  if (item.market) return true;
  if (isRemote(item)) return true;

  const placed = [item.title, item.location].filter(Boolean).join(' \n ');
  return places.length > 0 && containsAny(placed, places);
}

/*
 * One vendor role posted once per country: Mindrift and CloudFactory open a
 * separate Workable requisition, with its own id and url, for every country
 * they hire in, so one "AI Trainer" ad answers the keyword x location searches
 * as a dozen or more different jobs. The store's fingerprint folds them into
 * one row, but only after each has been filtered and looked up; the source
 * folds them first, on the same key, so a run counts roles, not requisitions.
 * No company falls back to the url: titles alone collide.
 */
export function dedupKey(item) {
  const fingerprint = fingerprintOf(item);
  return fingerprint ? `fp:${fingerprint}` : `id:${item.url ?? item.externalId}`;
}

/**
 * Which of two variants of one role to keep; lower is better. Placed in a
 * searched market, then remote, then anywhere not blocked, then blocked. The
 * locations are not merged: the filter drops a location naming any blocked
 * country, and a role open in India and Egypt is still wanted in Egypt.
 */
export function variantRank(item, blockedLocations = []) {
  const placed = [item.title, item.location].filter(Boolean).join(' \n ');
  if (blockedLocations.length > 0 && containsAny(placed, blockedLocations)) return 3;
  if (item.market) return 0;
  if (isRemote(item)) return 1;
  return 2;
}

export default modalitiesOf;
