/*
 * What a posting asks for and offers, read from its own words: years of
 * experience ("3-5 years of experience", "خبرة 3 سنوات") and a salary
 * ("15,000 - 20,000 EGP per month"). Ported from the Android app's Extract.kt.
 *
 * Both are cautious: a number only counts next to the word that makes it one
 * (experience, a currency), so "founded 10 years ago" or a phone number reads
 * as nothing rather than as a wrong answer.
 */

const RANGE = '(?:(?:-|–|to)\\s*(\\d{1,2})\\s*)?';

const ENGLISH_RES = [
  // "3-5 years of experience", "3+ yrs backend experience"
  new RegExp(
    `(\\d{1,2})\\s*\\+?\\s*${RANGE}\\+?\\s*(?:years?|yrs?)['’]?\\s+(?:of\\s+)?(?:[a-z/+#.-]+\\s+){0,3}?(?:experience|exp\\b)`,
    'gi',
  ),
  // "Minimum 4 years", "at least 2-3 years"
  new RegExp(
    `(?:minimum(?:\\s+of)?|at\\s+least|min\\.?)\\s*(\\d{1,2})\\s*\\+?\\s*${RANGE}(?:years?|yrs?)\\b`,
    'gi',
  ),
  // "Experience: 3 years"
  new RegExp(`experience\\s*[:：-]\\s*(\\d{1,2})\\s*\\+?\\s*${RANGE}(?:years?|yrs?)\\b`, 'gi'),
];

const AR_YEARS = '(?:سنوات|سنة|سنين|عام|أعوام|اعوام)';
const AR_EXPERIENCE = '(?:ال)?خبر[ةه]';

const ARABIC_RES = [
  // "خبرة 3 سنوات", "خبرة لا تقل عن 2 سنة", "خبرة من 2 إلى 4 سنوات"
  new RegExp(
    `${AR_EXPERIENCE}\\s*(?:لا\\s*تقل\\s*عن|من|لمدة|مدة)?\\s*(\\d{1,2})\\s*(?:(?:-|–|إلى|الى)\\s*(\\d{1,2})\\s*)?${AR_YEARS}`,
    'g',
  ),
  // "3 سنوات خبرة"
  new RegExp(`(\\d{1,2})\\s*${AR_YEARS}\\s*(?:من\\s*)?${AR_EXPERIENCE}`, 'g'),
];

/** "سنتين خبرة": two years, in the dual, with no digit at all. */
const ARABIC_TWO_RE = new RegExp(
  `(?:سنتين|سنتان|عامين)\\s*(?:من\\s*)?${AR_EXPERIENCE}|${AR_EXPERIENCE}\\s*(?:سنتين|سنتان|عامين)`,
);

/** Arabic-Indic and Persian digits as ASCII, so one set of patterns reads both. */
export function digits(text) {
  let out = '';

  for (const ch of String(text)) {
    const code = ch.charCodeAt(0);
    if (code >= 0x0660 && code <= 0x0669) out += String(code - 0x0660);
    else if (code >= 0x06f0 && code <= 0x06f9) out += String(code - 0x06f0);
    else if (ch === '٬') out += ',';
    else out += ch;
  }

  return out;
}

/** "3–5 yrs", "3+ yrs", "3 yrs". */
export function formatYears({ min, max }) {
  if (max != null && max !== min) return `${min}–${max} yrs`;
  if (max == null) return `${min}+ yrs`;
  return `${min} yrs`;
}

/** @returns {{min: number, max: number|null}|null} the lowest experience asked for anywhere in text. */
export function years(text) {
  if (!text || String(text).trim() === '') return null;

  const ascii = digits(text);
  const found = [];

  for (const re of [...ENGLISH_RES, ...ARABIC_RES]) {
    for (const match of ascii.matchAll(re)) {
      const min = Number.parseInt(match[1], 10);
      if (!Number.isFinite(min) || min < 0 || min > 30) continue;

      const max = match[2] == null ? null : Number.parseInt(match[2], 10);
      found.push({ min, max: Number.isFinite(max) && max >= min && max <= 30 ? max : null });
    }
  }

  if (ARABIC_TWO_RE.test(ascii)) found.push({ min: 2, max: 2 });
  if (found.length === 0) return null;

  return found.reduce((best, entry) => (entry.min < best.min ? entry : best));
}

// ---- Salary ----

const CUR = '(EGP|L\\.?E\\.?|SAR|SR|AED|USD|US\\$|\\$|جنيه|ج\\.م|ريال|درهم)';
const AMT = '(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)\\s*([kK])?';
const TO = '\\s*(?:-|–|to|إلى|الى)\\s*';
const PERIOD =
  '(?:\\s*(?:/|per\\s+|a\\s+)?\\s*(month|mo|year|yr|annum|hour|hr|شهري[اًا]?|سنوي[اًا]?))?';

// A Latin currency must stand on its own: "LE" inside "SALE" is no currency.
const EDGE_L = '(?<![A-Za-z])';
const EDGE_R = '(?![A-Za-z])';

/** "EGP 15,000 - 20,000" and "$90k–110k". */
const LEADING_RE = new RegExp(`${EDGE_L}${CUR}${EDGE_R}\\s*${AMT}(?:${TO}(?:${CUR}\\s*)?${AMT})?${PERIOD}`, 'i');

/** "15,000 - 20,000 EGP" and "8000 درهم". */
const TRAILING_RE = new RegExp(`${AMT}(?:${TO}${AMT})?\\s*${CUR}${EDGE_R}${PERIOD}`, 'i');

/** The salary text names, compacted ("EGP 15k–20k/mo"), or null. "Competitive" is null. */
export function salary(text) {
  if (!text || String(text).trim() === '') return null;

  const ascii = digits(text);
  const g = (match) => match.map((value) => value ?? '');

  const leading = ascii.match(LEADING_RE);
  if (leading) {
    // 1 currency, 2-3 amount, 4 second currency, 5-6 second amount, 7 period
    const m = g(leading);
    const out = render(m[1], m[2], m[3], m[5], m[6], m[7]);
    if (out) return out;
  }

  const trailing = ascii.match(TRAILING_RE);
  if (trailing) {
    // 1-2 amount, 3-4 second amount, 5 currency, 6 period
    const m = g(trailing);
    const out = render(m[5], m[1], m[2], m[3], m[4], m[6]);
    if (out) return out;
  }

  return null;
}

function render(currency, amount, k, amount2, k2, period) {
  const code = currencyCode(currency);
  if (!code) return null;

  const low = value(amount, k);
  if (low == null) return null;

  const high = amount2 ? value(amount2, k2 || k) : null;

  // A bare "$5" or "SR 30" is a price or a code, not a salary.
  if (low < 100) return null;

  const range = high != null && high > low ? `${compact(low)}–${compact(high)}` : compact(low);
  return `${code} ${range}${periodSuffix(period)}`;
}

function value(amount, k) {
  const number = Number.parseFloat(String(amount).replace(/,/g, ''));
  if (!Number.isFinite(number)) return null;
  return k ? number * 1000 : number;
}

function compact(number) {
  if (number < 1000) return String(Math.trunc(number));

  const thousands = number / 1000;
  if (thousands % 1 === 0) return `${thousands}k`;

  return `${thousands.toFixed(1)}k`.replace('.0k', 'k');
}

function currencyCode(raw) {
  switch (String(raw).toLowerCase().replace(/\./g, '')) {
    case 'egp':
    case 'le':
    case 'جنيه':
    case 'جم':
      return 'EGP';
    case 'sar':
    case 'sr':
    case 'ريال':
      return 'SAR';
    case 'aed':
    case 'درهم':
      return 'AED';
    case 'usd':
    case 'us$':
    case '$':
      return 'USD';
    default:
      return null;
  }
}

function periodSuffix(period) {
  const text = String(period).toLowerCase();
  if (text === '') return '';
  if (text.startsWith('mo') || text.startsWith('شهري')) return '/mo';
  if (text.startsWith('y') || text === 'annum' || text.startsWith('سنوي')) return '/yr';
  if (text.startsWith('h')) return '/hr';
  return '';
}

/** Fills item.yearsMin, item.yearsMax and item.salary from the title and text. */
export function applyExtract(item) {
  const text = [item.title, item.excerpt].filter(Boolean).join('\n');
  const asked = years(text);

  if (asked) {
    item.yearsMin = asked.min;
    item.yearsMax = asked.max;
  }

  if (item.salary == null) item.salary = salary(text);

  return item;
}
