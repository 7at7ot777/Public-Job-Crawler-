import * as cheerio from 'cheerio';

/**
 * Pulls the description text and the "Seniority level / Employment type / Job
 * function" criteria off a guest job-detail page.
 *
 * Needed because a search card carries only a title — not enough to tell a
 * junior role from a senior one, and never enough to find a contact email.
 */
export function parseJobDetail(html) {
  const trimmed = (html ?? '').trim();
  if (trimmed === '') return { description: null, criteria: {} };

  const $ = cheerio.load(trimmed);

  const description = $('.description__text, .show-more-less-html__markup')
    .first()
    .text()
    .replace(/\s+/g, ' ')
    .trim();

  const criteria = {};

  $('.description__job-criteria-item').each((_, element) => {
    const label = $(element)
      .find('.description__job-criteria-subheader')
      .text()
      .replace(/\s+/g, ' ')
      .trim()
      .toLowerCase();

    const value = $(element)
      .find('.description__job-criteria-text')
      .text()
      .replace(/\s+/g, ' ')
      .trim();

    if (label && value) criteria[label] = value;
  });

  return {
    description: description === '' ? null : description,
    criteria,
  };
}

export default parseJobDetail;
