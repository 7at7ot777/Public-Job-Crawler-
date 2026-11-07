/*
 * Saudi Arabia and the UAE, the two markets outside Egypt searched. Their
 * jobs reach the boards and company ATS sources only while config.boardsGulf
 * is on (the Android app's "Saudi & UAE on job boards and ATS" switch).
 */
export const SAUDI = 'Saudi Arabia';
export const UAE = 'United Arab Emirates';
export const COUNTRIES = [SAUDI, UAE];

const EMIRATES_RE =
  /\b(uae|u\.a\.e|emirates|dubai|abu dhabi|sharjah|ajman|fujairah|ras al khaimah|umm al quwain|al ain)\b|الإمارات|الامارات|دبي/i;

// Workday and Oracle boards often name the city alone: "Yanbu", "Al-Khobar".
const SAUDI_RE = new RegExp(
  '\\b(saudi|ksa|riyadh|jeddah|dammam|khobar|jubail|yanbu|dhahran|makkah|mecca|madinah|medina|tabuk|abha|qassim|buraydah|hofuf|ahsa|jazan|jizan|neom|taif)\\b' +
    '|السعودية|الرياض|جدة|الخبر|الدمام|مكة|المدينة المنورة',
  'i',
);

/** The Gulf country a place names, or null for anywhere else. */
export function countryOf(place) {
  if (!place || String(place).trim() === '') return null;
  if (EMIRATES_RE.test(place)) return UAE;
  if (SAUDI_RE.test(place)) return SAUDI;
  return null;
}

export const isGulf = (market) => market != null && COUNTRIES.includes(market);

/** As a Telegram line shows it. */
export function flagged(market) {
  if (market === SAUDI) return `🇸🇦 ${SAUDI}`;
  if (market === UAE) return `🇦🇪 ${UAE}`;
  return market;
}

/** A job whose location names a Gulf place is placed there (company boards set no market). */
export function placeInGulf(item) {
  const country = countryOf(item.location);
  return country ? { ...item, market: country } : item;
}
