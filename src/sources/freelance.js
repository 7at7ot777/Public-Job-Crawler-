import { containsAny, fold } from '../text.js';
import { stackOf } from './cvStack.js';

/*
 * Arabic freelance boards (Mostaql, Khamsat): gigs, not jobs, filed under
 * their own region (FREELANCE) and posted with #Freelance.
 *
 * The job filters' title and stack lists are English job titles, so an Arabic
 * project ("بناء متجر إلكتروني") would fail them all. A freelance item is
 * judged here instead: it has to name the CV's stack, in English or Arabic.
 */
export const FREELANCE_KEYS = new Set(['mostaql', 'khamsat']);

/** A non-broad market, so the location rule lets it through; never 🌍/✈️, so it is not Worldwide. */
export const freelanceMarket = (board) => `💼 Freelance · ${board}`;

/*
 * The CV's stack in Arabic. Arabic letters are outside the word boundaries,
 * so these match inside longer words too: never a bare "نود" ("نود أن" is
 * "we would like") or "جو" (inside جودة, جوال, ...).
 */
const ARABIC = [
  ['Laravel', ['لارافيل', 'لارفيل', 'لارافل']],
  ['PHP', ['بي اتش بي', 'بي إتش بي', 'بى اتش بى']],
  ['Node.js', ['نود جي اس', 'نود جى اس', 'نود جي إس', 'نودجي اس', 'نود.جي اس']],
  ['NestJS', ['نست جي اس', 'نيست جي اس']],
  ['Go', ['جولانج', 'جو لانج']],
  ['TypeScript', ['تايب سكريبت', 'تايبسكريبت']],
  ['Backend', ['باك اند', 'باك إند', 'باك-اند', 'الباك اند', 'الباك إند', 'الواجهة الخلفية', 'واجهة برمجة', 'واجهات برمجة']],
  ['Full stack', ['فول ستاك', 'فل ستاك', 'فول-ستاك']],
];

const BACKEND = ['backend', 'back-end', 'back end', 'api', 'apis', 'rest api', 'restful', 'express', 'express.js', 'expressjs'];
const FULL_STACK = ['full stack', 'full-stack', 'fullstack'];

/** The stack text asks for, English names first then Arabic hits; empty when none. */
export function freelanceStack(text) {
  const folded = fold(text);
  const found = new Set(stackOf(text));

  for (const [name, terms] of ARABIC) if (containsAny(folded, terms)) found.add(name);
  if (containsAny(folded, BACKEND)) found.add('Backend');
  if (containsAny(folded, FULL_STACK)) found.add('Full stack');

  return [...found];
}

/** Whether a gig is work the CV can take. */
export const fitsFreelance = (text) => freelanceStack(text).length > 0;

/** "$50.00 - $100.00" as "$50–100"; a single amount as "$250"; null when there is none. */
export function budget(value) {
  if (value == null) return null;

  const amounts = (String(value).match(/\d[\d,]*(?:\.\d+)?/g) ?? []).map((amount) =>
    amount.replace(/,/g, '').replace(/\.00$/, '').replace(/\.0$/, ''),
  );

  if (amounts.length === 0) return null;
  if (amounts.length === 1) return `$${amounts[0]}`;
  return `$${amounts[0]}–${amounts[1]}`;
}
