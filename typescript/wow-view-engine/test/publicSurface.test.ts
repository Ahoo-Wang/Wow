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
 * Every layer index re-exported its files, and the root entry re-exported the
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
import { en } from '../src/ui/messages/en.js';
import {
  ENTRIES,
  entryExports,
  type Entry,
  type ExportKind,
} from './fixtures/exports.js';
import { raisedCodes } from './fixtures/issueCodes.js';

/** Where each entry's list lives, beside this suite. */
const LISTS: Record<Entry, string> = {
  '@ahoo-wang/wow-view-engine': 'surface/root.txt',
  '@ahoo-wang/wow-view-engine/react': 'surface/react.txt',
  '@ahoo-wang/wow-view-engine/ui': 'surface/ui.txt',
  '@ahoo-wang/wow-view-engine/testing': 'surface/testing.txt',
  '@ahoo-wang/wow-view-engine/react-router': 'surface/react-router.txt',
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
        '# the public surface (the theming guide「Checking a theme」, docs/design/ui/theme.md).',
        ...lines,
        '',
      ].join('\n'),
    ).toMatchFileSnapshot('surface/bin.txt');
  });
});

/**
 * The words are surface too (ARCH-2, API-10, D29): a host rewords the
 * engine by message key (`MessageOverrides`) and reads a finding by its
 * issue code (`Issue.code`), so a key or a code renamed in a patch would
 * leave a host's override dead, or its branch on a code never taken, with
 * nothing failing. Each is kept as a list beside the entries', one key or
 * code a line, made on purpose with `-u`: a line removed or renamed is a
 * Breaking line in the release notes, like a removed export.
 */
describe('the public surface of the words', () => {
  const heading = (what: string, count: number) => [
    `# ${what} — ${count}.`,
    '# Written by test/publicSurface.test.ts; a change here is a change to',
    '# the public surface (README「Wording and language」, docs/design/decisions.md D29).',
  ];

  it('names every message key the English catalogue ships', async () => {
    const keys = Object.keys(en).sort();
    await expect(
      [...heading('Message keys', keys.length), ...keys, ''].join('\n'),
    ).toMatchFileSnapshot('surface/messages.txt');
  });

  it('names every issue code the package can raise', async () => {
    const { codes } = raisedCodes();
    await expect(
      [...heading('Issue codes', codes.length), ...codes, ''].join('\n'),
    ).toMatchFileSnapshot('surface/issues.txt');
  });
});
