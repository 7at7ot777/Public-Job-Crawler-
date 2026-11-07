// Shared parser fixtures (fixtures/<parser>/<case>.*): the same markup and
// the same expected fields, checked here and by the Android app's
// FixturesTest, so the two ports cannot drift apart unnoticed.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { parseJobCards } from '../src/sources/jobCardParser.js';
import { parseSearchResponse } from '../src/sources/workableParser.js';
import { parseBoard, parseBoardResponse } from '../src/sources/atsParser.js';
import { parseSearchResults } from '../src/sources/tanqeebParser.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'fixtures');

const parsers = {
  linkedin_cards: (input, args) => parseJobCards(input, args.keyword, args.market),
  workable: (input, args) => parseSearchResponse(input, args.keyword).items,
  ats_lever: (input, args) => parseBoardResponse(input, parseBoard(args.board)),
  ats_greenhouse: (input, args) => parseBoardResponse(input, parseBoard(args.board)),
  tanqeeb_search: (input, args) => parseSearchResults(input, args.keyword),
};

// Dates compare as instants: "…00Z" and "…00.000Z" are the same moment.
const same = (field, actual, expected) =>
  field === 'postedAt' && actual != null && expected != null
    ? new Date(actual).getTime() === new Date(expected).getTime()
    : actual === expected;

for (const parser of readdirSync(root).sort()) {
  const dir = join(root, parser);
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.expected.json')).sort()) {
    const name = file.replace(/\.expected\.json$/, '');
    test(`fixture ${parser}/${name}`, () => {
      const parse = parsers[parser];
      assert.ok(parse, `no Node parser registered for ${parser}`);
      const input = readFileSync(join(dir, readdirSync(dir).find((f) => f.startsWith(`${name}.`) && f !== file)), 'utf8');
      const expected = JSON.parse(readFileSync(join(dir, file), 'utf8'));
      const skipped = expected.skip?.node ?? {};
      const items = parse(input, expected.args ?? {});
      assert.equal(items.length, expected.items.length, 'item count');
      expected.items.forEach((want, i) => {
        for (const [field, value] of Object.entries(want)) {
          if (field in skipped) continue;
          assert.ok(same(field, items[i][field], value), `${parser}/${name} item ${i} ${field}: got ${items[i][field]}, want ${value}`);
        }
      });
    });
  }
}
