import { containsAny, containsTerm, fold } from '../text.js';

/*
 * What the CV offers, for sources that list jobs from abroad (jobright,
 * Relocate.me, the remote feeds): those boards are not searched by keyword, so
 * a job has to be checked against the CV's stack and level here instead.
 */

/** The person has 1-3 years: a job asking for more than this is out of reach. */
export const MAX_YEARS = 4;

/* The CV's stack, as shown on the matched line. At least one has to be there. */
const STACK = [
  ['PHP', ['php']],
  ['Laravel', ['laravel']],
  ['NestJS', ['nestjs', 'nest.js']],
  ['Node.js', ['node.js', 'nodejs', 'node']],
  ['Go', ['golang', 'go lang']],
  ['TypeScript', ['typescript']],
];

// "Go" as a language, never "go live" or "go-to-market": case-sensitive and in a list.
const GO_RE = /(?:\b(?:in|with|using|and|or|of)\s+|[,(/]\s*)Go\b(?![-\s]+(?:to|live|beyond)\b)/;

/* What else on the CV a posting may ask for; more of these ranks it higher. */
const BOOST = [
  'mysql', 'sql server', 'mongodb', 'redis', 'rest', 'rest api', 'restful', 'queue', 'queues', 'webhooks',
  'multi-tenant', 'multi-tenancy', 'docker', 'aws', 'jwt', 'oauth', 'payments', 'payment', 'fintech',
  'pos', 'erp', 'filament', 'livewire', 'microservices', 'ai-assisted', 'ai tools', 'claude', 'llm',
  'spec-driven', 'api design', 'postgresql', 'sequelize', 'typeorm', 'phpunit',
];

// "3+ years of experience", "2-4 years of professional backend experience", "5 years' experience".
const YEARS_RE =
  /(\d{1,2})\s*\+?\s*(?:(?:-|–|to)\s*\d{1,2}\s*)?\+?\s*years?['’]?\s+(?:of\s+)?(?:[a-z/+#.-]+\s+){0,3}?(?:experience|exp\b)/gi;

/** The CV's languages and frameworks text names, in the CV's order. */
export function stackOf(text) {
  const folded = fold(text);
  return STACK.filter(
    ([name, terms]) => containsAny(folded, terms) || (name === 'Go' && GO_RE.test(String(text ?? ''))),
  ).map(([name]) => name);
}

/** How much of the rest of the CV text asks for. */
export function boostScore(text) {
  const folded = fold(text);
  return BOOST.filter((term) => containsTerm(folded, term)).length;
}

/** The lowest experience a posting names, or null when it names none. */
export function minYears(text) {
  const found = [...String(text ?? '').matchAll(YEARS_RE)]
    .map((match) => Number.parseInt(match[1], 10))
    .filter((n) => n >= 1 && n <= 20);
  return found.length ? Math.min(...found) : null;
}
