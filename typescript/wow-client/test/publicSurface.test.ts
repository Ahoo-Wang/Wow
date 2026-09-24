/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * The public surface, name by name.
 *
 * Every folder index re-exports its files with `export *`, so any `export` in
 * any file becomes a promise to every application the moment it is written.
 * Once the package is published each such name is a compatibility burden
 * until the next major release.
 *
 * So each entry's exports are kept as a list under `test/surface/`, one name
 * a line with whether it is a type or a value, and this suite compares the
 * source entries with it. A change to the list is a change to the public
 * surface: it shows up in review as one, and it is made on purpose with
 * `pnpm exec vitest run test/publicSurface.test.ts -u`.
 * `scripts/verify-package.mjs`, which the build runs, holds each built
 * JavaScript entry to the same list's values.
 */

import { describe, expect, it } from 'vitest';
import {
  ENTRIES,
  entryExports,
  type Entry,
  type ExportKind,
} from './fixtures/exports.js';

/** Where each entry's list lives, beside this suite. */
const LISTS: Record<Entry, string> = {
  '@ahoo-wang/wow-client': 'surface/root.txt',
  '@ahoo-wang/wow-client/dsl': 'surface/dsl.txt',
  '@ahoo-wang/wow-client/legacy': 'surface/legacy.txt',
};

/** One entry's list as the file holds it: a heading, then `kind name`. */
function list(entry: Entry, names: ReadonlyMap<string, ExportKind>): string {
  const lines = [...names]
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([name, kind]) => `${kind.padEnd(5)} ${name}`);
  return [
    `# ${entry} — ${names.size} names, from ${ENTRIES[entry]}.`,
    '# Written by test/publicSurface.test.ts; a change here is a change to',
    '# the public surface.',
    ...lines,
    '',
  ].join('\n');
}

describe('the public surface of each entry', () => {
  for (const entry of Object.keys(ENTRIES) as Entry[])
    it(`${entry} exports exactly its list`, async () => {
      await expect(list(entry, entryExports(entry))).toMatchFileSnapshot(
        LISTS[entry],
      );
    });

  it('keeps the Condition API off the root entry', () => {
    const root = entryExports('@ahoo-wang/wow-client');
    const legacy = entryExports('@ahoo-wang/wow-client/legacy');
    expect([...legacy.keys()].filter(name => root.has(name)).sort()).toEqual([
      // The legacy factories build Condition queries under the root names.
      'listQuery',
      'pagedQuery',
      'singleQuery',
    ]);
  });

  it('keeps the DSL entry a part of the root entry', () => {
    const root = entryExports('@ahoo-wang/wow-client');
    const dsl = entryExports('@ahoo-wang/wow-client/dsl');
    expect([...dsl.keys()].filter(name => !root.has(name))).toEqual([]);
    for (const client of ['SnapshotQueryClient', 'CommandClient', 'WowError'])
      expect(dsl.has(client)).toBe(false);
  });
});
