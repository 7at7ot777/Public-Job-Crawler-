import config from './config.js';
import { formatYears } from './extract.js';
import { flagged, isGulf } from './gulf.js';
import { isAbroad, regionOf } from './region.js';
import { abroadRegionOf, scoreItem as abroadScore } from './sources/abroadFit.js';

const TITLE_MAX = 200;
const FIELD_MAX = 100;
const EXCERPT_MAX = 600;

/**
 * Telegram's HTML parse mode understands a small tag set and requires '&', '<'
 * and '>' to be escaped in text. Quotes are left alone on purpose: they need no
 * escaping in a text node, and escaping them mangles apostrophes in company
 * names. Attribute values get the stricter escape in link().
 */
function escapeText(value, max = null) {
  let text = value == null ? '' : String(value);

  if (max !== null && text.length > max) {
    text = `${text.slice(0, max - 1)}…`;
  }

  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function escapeAttribute(value) {
  return escapeText(value).replace(/"/g, '&quot;');
}

/** Only https links become anchors; anything else is dropped, not injected. */
function link(url, label) {
  if (!url || !String(url).startsWith('https://')) return '';
  return `<a href="${escapeAttribute(url)}">${escapeText(label)}</a>`;
}

/** Items come either from a source (camelCase) or from SQLite (snake_case). */
function normalize(item) {
  return {
    id: item.id ?? null,
    itemType: item.itemType ?? item.item_type ?? 'job',
    title: item.title,
    company: item.company,
    location: item.location,
    url: item.url,
    keyword: item.keyword,
    postedAt: item.postedAt ?? item.posted_at ?? null,
    excerpt: item.excerpt,
    seniority: item.seniority,
    employmentType: item.employmentType ?? item.employment_type ?? null,
    // Which board an aggregated posting really lives on: where you apply.
    board: item.board,
    workplace: item.workplace,
    source: item.source,
    market: item.market ?? null,
    score: item.score ?? null,
    yearsMin: item.yearsMin ?? item.years_min ?? null,
    yearsMax: item.yearsMax ?? item.years_max ?? null,
    salary: item.salary ?? null,
    emails: toList(item.contactEmails ?? item.contact_emails),
    phones: toList(item.contactPhones ?? item.contact_phones),
  };
}

/** Contacts arrive as an array from a source, or a joined string from SQLite. */
function toList(value) {
  if (!value) return [];
  if (Array.isArray(value)) return value;
  return String(value)
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean);
}

function formatPostedAt(value) {
  if (!value) return null;

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);

  const pad = (n) => String(n).padStart(2, '0');

  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

export function formatMessage(rawItem) {
  const item = normalize(rawItem);
  return item.itemType === 'post' ? formatPost(item) : formatJob(item);
}

function formatJob(item) {
  const lines = [`🧑‍💻 <b>${escapeText(item.title, TITLE_MAX)}</b>`, ''];

  if (item.company) lines.push(`🏢 <b>${escapeText(item.company, FIELD_MAX)}</b>`);
  if (item.location) lines.push(`📍 ${escapeText(item.location, FIELD_MAX)}`);

  const postedAt = formatPostedAt(item.postedAt);
  if (postedAt) lines.push(`🕒 Posted ${postedAt}`);

  if (item.seniority) lines.push(`📊 ${escapeText(item.seniority, FIELD_MAX)}`);
  lines.push(...factLines(item));
  if (item.workplace) lines.push(`🏠 ${escapeText(item.workplace, FIELD_MAX)}`);
  // Only aggregators set this, and then it is the board to apply on.
  if (item.board) lines.push(`🗂 via ${escapeText(item.board, FIELD_MAX)}`);

  // The lane of a job abroad: why it is here at all.
  if (item.market && (item.market.startsWith('🌍') || item.market.startsWith('✈️'))) {
    lines.push(escapeText(item.market, FIELD_MAX));
  }

  // How well a job abroad fits the CV, and where it is.
  if (isAbroad(item)) {
    const region = abroadRegionOf(item.location);
    lines.push(`🎯 CV fit ${abroadScore(item)}/100${region ? ` · ${region}` : ''}`);
  }

  // A Saudi/UAE job in Egypt's channel: the country up front, whatever the place line says.
  if (isGulf(item.market)) lines.push(escapeText(flagged(item.market), FIELD_MAX));

  if (item.keyword) lines.push(`🔎 Matched: <i>${escapeText(item.keyword, FIELD_MAX)}</i>`);

  lines.push(...contactLines(item));
  lines.push('', link(item.url, openLabel(item)));

  return join(lines);
}

/**
 * Where the link goes, named honestly.
 *
 * The crawler reads four sites now, and one of them is an aggregator whose
 * links lead to a page about a job on a fifth — so the label is derived
 * rather than assumed.
 */
function openLabel(item) {
  const url = String(item.url ?? '');

  if (url.includes('linkedin.com')) return 'Open on LinkedIn ↗';
  if (url.includes('wuzzuf.net')) return 'Open on Wuzzuf ↗';
  if (url.startsWith('https://x.com/')) return 'Open on X ↗';
  if (url.includes('recruitera.ai')) return 'Open on Recruitera ↗';
  if (url.includes('workable.com')) return 'Open on Workable ↗';
  if (url.includes('jobright.ai')) return 'Open on Jobright ↗';
  if (url.includes('relocate.me')) return 'Open on Relocate.me ↗';
  if (url.includes('glassdoor.')) return 'Open on Glassdoor ↗';
  if (url.includes('mostaql.com')) return 'Open on Mostaql ↗';
  if (url.includes('khamsat.com')) return 'Open on Khamsat ↗';
  if (url.includes('lever.co')) return 'Open on Lever ↗';
  if (url.includes('greenhouse.io')) return 'Open on Greenhouse ↗';
  if (url.includes('ashbyhq.com')) return 'Open on Ashby ↗';
  if (url.includes('bamboohr.com')) return 'Open on BambooHR ↗';
  if (url.includes('tanqeeb.com')) {
    return item.board ? `Open on Tanqeeb (from ${item.board}) ↗` : 'Open on Tanqeeb ↗';
  }

  return 'Open the posting ↗';
}

/** Fit, experience and pay, when known: what decides whether to open it. */
function factLines(item) {
  const lines = [];
  if (item.score != null) lines.push(`⭐ ${item.score}/100`);
  if (item.yearsMin != null) lines.push(`🧭 ${formatYears({ min: item.yearsMin, max: item.yearsMax })}`);
  if (item.salary) lines.push(`💰 ${escapeText(item.salary, FIELD_MAX)}`);
  return lines;
}

/** Contacts are the point of the whole filter, so they get their own block. */
function contactLines(item) {
  const lines = [];

  if (item.emails.length > 0) {
    lines.push('', `📧 <code>${escapeText(item.emails.join(', '), FIELD_MAX * 2)}</code>`);
  }

  if (item.phones.length > 0) {
    if (item.emails.length === 0) lines.push('');
    lines.push(`📱 <code>${escapeText(item.phones.join(', '), FIELD_MAX * 2)}</code>`);
  }

  // One channel for every region: the tag is what a search in Telegram keys on.
  if (item.source) lines.push('', regionOf(item).tag);

  // The row id, for /up, /down when the bot takes commands.
  if (item.id != null && config.telegram.commandsEnabled) lines.push(`🆔 ${item.id}`);

  return lines;
}

function formatPost(item) {
  const lines = [`📣 <b>${escapeText(item.title, TITLE_MAX)}</b>`, ''];

  if (item.company) lines.push(`👤 ${escapeText(item.company, FIELD_MAX)}`);
  lines.push(...factLines(item));

  // A post's heading is its own opening line, so a short post would otherwise
  // be printed twice — once bold, once as the body.
  if (item.excerpt && !startsWithTitle(item.excerpt, item.title)) {
    lines.push('', escapeText(item.excerpt, EXCERPT_MAX));
  }

  const postedAt = formatPostedAt(item.postedAt);
  if (postedAt) lines.push('', `🕒 Posted ${postedAt}`);

  if (item.keyword) lines.push(`🔎 Matched: <i>${escapeText(item.keyword, FIELD_MAX)}</i>`);

  lines.push(...contactLines(item));
  lines.push('', link(item.url, openLabel(item)));

  return join(lines);
}

/**
 * Whether the body opens with the text already used as the heading, allowing
 * for the ellipsis a truncated heading ends with.
 */
function startsWithTitle(excerpt, title) {
  const heading = (title ?? '').replace(/…$/, '').trim().toLowerCase();
  if (heading === '') return false;

  return excerpt.trim().toLowerCase().startsWith(heading);
}

/** Drop trailing blanks so a missing link never leaves dangling newlines. */
function join(lines) {
  const trimmed = [...lines];

  while (trimmed.length > 0 && trimmed.at(-1) === '') {
    trimmed.pop();
  }

  return trimmed.join('\n');
}

export default formatMessage;
