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
 * The public surface, name by name (A-16, D29).
 *
 * Every layer index re-exports its files, and the root entry re-exports the
 * layers, so a helper written for `/react` or `/ui` became a promise to every
 * host the moment its file had an `export` — the runtime's scheduler, its
 * store and its listener sets among them. Once the package is published each
 * such name is a compatibility burden, and nothing said which ones were meant.
 *
 * So each entry's exports are kept as a list under `test/surface/`, one name
 * a line with whether it is a type or a value, and this suite compares the
 * source entries with it. A change to the list is a change to the public
 * surface: it shows up in review as one, and it is made on purpose with
 * `pnpm exec vitest run test/publicSurface.test.ts -u`. `scripts/verify-package.mjs`
 * holds each built JavaScript entry to the same list's values.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { COMMANDS } from '../theme-check/cli';
import {
  ENTRIES,
  entryExports,
  type Entry,
  type ExportKind,
} from './fixtures/exports.js';

/** Where each entry's list lives, beside this suite. */
const LISTS: Record<Entry, string> = {
  '@ahoo-wang/wow-view-engine': 'surface/root.txt',
  '@ahoo-wang/wow-view-engine/react': 'surface/react.txt',
  '@ahoo-wang/wow-view-engine/ui': 'surface/ui.txt',
};

/** One entry's list as the file holds it: a heading, then `kind name`. */
function list(entry: Entry, names: ReadonlyMap<string, ExportKind>): string {
  const lines = [...names]
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([name, kind]) => `${kind.padEnd(5)} ${name}`);
  return [
    `# ${entry} — ${names.size} names, from ${ENTRIES[entry]}.`,
    '# Written by test/publicSurface.test.ts; a change here is a change to',
    '# the public surface (README「Entries」, docs/design/decisions.md D29).',
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

  /**
   * The runtime's parts stay behind the entry: what a host holds is the
   * engine and the contracts, and these are what the engine is built from.
   * Named here as well as in the list, so that putting one back is a
   * decision this suite asks for twice.
   */
  it('keeps the runtime’s parts off the root entry', () => {
    const root = entryExports('@ahoo-wang/wow-view-engine');
    const parts = [
      'RuntimeStore',
      'RequestRunner',
      'listenerSet',
      'RefreshTimer',
      'DataViewRuntime',
      'RecordDataViewRuntime',
      'DashboardViewRuntime',
      'dataViewRuntime',
      'ViewRuntimeOptions',
      'ManagedViewRuntime',
      'DashboardRuntimeOptions',
      'WriteLedger',
      'ViewChanges',
      'ValueCandidateSources',
    ].filter(name => root.has(name));
    expect(parts).toEqual([]);
  });
});

/**
 * The package's command is surface too (theme-architecture.md 5.2, S7): the
 * `bin` a host's CI calls, and each subcommand it answers. Kept as a list
 * beside the entries', made on purpose with `-u`.
 */
describe('the public surface of the command', () => {
  it('names exactly its list', async () => {
    const manifest = JSON.parse(
      readFileSync(join(import.meta.dirname, '../package.json'), 'utf8'),
    ) as { bin?: Record<string, string> };
    const lines = Object.entries(manifest.bin ?? {}).flatMap(
      ([command, file]) => [
        `bin   ${command} → ${file}`,
        ...COMMANDS.map(subcommand => `cmd   ${command} ${subcommand}`),
      ],
    );
    await expect(
      [
        "# The package's command — its bin and subcommands.",
        '# Written by test/publicSurface.test.ts; a change here is a change to',
        '# the public surface (README「Checking a theme」, theme-architecture.md 5.2).',
        ...lines,
        '',
      ].join('\n'),
    ).toMatchFileSnapshot('surface/bin.txt');
  });
});
