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
 * Deep review r1 of the TypeScript packages: the generator's symbol index,
 * the one package page no build writes, against the public surface.
 */

import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (path: string) =>
  readFileSync(new URL(path, import.meta.url), 'utf8');

/** The names `test/surface/root.txt` lists, types and values alike. */
function surface(): string[] {
  return read('./surface/root.txt')
    .split('\n')
    .map(line => /^(?:type|value)\s+(\S+)$/.exec(line.trim())?.[1])
    .filter((name): name is string => name !== undefined);
}

/** The names a page's symbol index table lists, each in backticks. */
function indexed(page: string, heading: RegExp): Set<string> {
  const text = read(page);
  const start = text.search(heading);
  expect(start).toBeGreaterThan(-1);
  const table = text
    .slice(start)
    .split('\n')
    .slice(1)
    .filter(line => line.startsWith('|'));
  return new Set(
    table.flatMap(line => [...line.matchAll(/`([^`]+)`/g)].map(m => m[1])),
  );
}

describe('r1: the symbol index of wow-generator', () => {
  it.each([
    ['en', /^## Public symbol index$/m],
    ['zh', /^## 公开符号索引$/m],
  ])(
    'r1 the %s symbol index lists every name the package root exports',
    (language, heading) => {
      const names = indexed(
        `../../../documentation/docs/${language}/reference/typescript/wow-generator/symbols.md`,
        heading,
      );
      expect(surface().filter(name => !names.has(name))).toEqual([]);
    },
  );
});
