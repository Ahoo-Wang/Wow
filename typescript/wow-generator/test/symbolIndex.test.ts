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
 * The generator's symbol index, the one package page no build writes, held to
 * its public surface (`test/surface/root.txt`) in both languages, as
 * `test:api` holds the other packages' pages to their builds.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { PACKAGE_ROOT } from './support/generation';

/** The names `test/surface/root.txt` lists, types and values alike, in its order. */
function surface(): string[] {
  return readFileSync(join(PACKAGE_ROOT, 'test', 'surface', 'root.txt'), 'utf8')
    .split('\n')
    .map(line => /^(?:type|value)\s+(\S+)$/.exec(line.trim())?.[1])
    .filter((name): name is string => name !== undefined);
}

/** The names the table under `heading` of the `language` symbol page lists, each in backticks. */
function indexed(language: string, heading: RegExp): string[] {
  const text = readFileSync(
    join(
      PACKAGE_ROOT,
      '..',
      '..',
      'documentation',
      'docs',
      language,
      'reference',
      'typescript',
      'wow-generator',
      'symbols.md',
    ),
    'utf8',
  );
  const start = text.search(heading);
  expect(start).toBeGreaterThan(-1);
  // The table is the run of `|` lines that follows the heading.
  const lines = text.slice(start).split('\n').slice(1);
  const first = lines.findIndex(line => line.startsWith('|'));
  const end = lines.findIndex(
    (line, at) => at > first && !line.startsWith('|'),
  );
  const table = lines.slice(first, end < 0 ? undefined : end);
  return table.flatMap(line =>
    [...line.split('|')[1].matchAll(/`([^`]+)`/g)].map(match => match[1]),
  );
}

describe('the symbol index of wow-generator', () => {
  it.each([
    ['en', /^## Public symbol index$/m],
    ['zh', /^## 公开符号索引$/m],
  ])(
    'lists in %s every name the package root exports, and no other',
    (language, heading) => {
      expect(indexed(language, heading).sort()).toEqual(surface().sort());
    },
  );
});
