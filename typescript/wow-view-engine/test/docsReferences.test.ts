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

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';
import { EDITOR_INPUTS } from '@/filter/index.js';

const ROOT = join(import.meta.dirname, '..');

/** Every design page, plus the file an agent reads before them. */
function pages(dir: string): string[] {
  return readdirSync(dir).flatMap(entry => {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) return pages(path);
    return entry.endsWith('.md') ? [path] : [];
  });
}

const documents = [
  ...pages(join(ROOT, 'docs/design')),
  join(ROOT, 'AGENTS.md'),
];

/**
 * A rule worth having is a test, and a doc that points at the test is how the
 * next reader finds it. That only works while the pointer resolves: `docs/`
 * pointed at `test/ui.test.tsx` for a while, a suite that never existed, and
 * a reader who goes looking learns nothing except not to trust the next
 * citation either (C6). So every `test/<file>.test.ts(x)` a page names must
 * be a file, and every 「title」 quoted after one must be a title that file
 * holds — the citation survives a suite being split or a test renamed.
 */
describe('the test suites the design pages cite', () => {
  /** A file reference, with the titles quoted straight after it. */
  const citations = documents.flatMap(document => {
    const text = readFileSync(document, 'utf8');
    const where = relative(ROOT, document);
    return text.split('\n').flatMap((line, index) =>
      [
        ...line.matchAll(
          /(test\/[A-Za-z0-9_.\-/]*\.test\.tsx?)((?:\s*「[^」]*」)*)/g,
        ),
      ].map(([, suite, quoted]) => ({
        at: `${where}:${index + 1}`,
        suite,
        titles: [...quoted.matchAll(/「([^」]*)」/g)].map(([, title]) => title),
      })),
    );
  });

  it('cites suites at all', () => {
    expect(citations.length).toBeGreaterThan(50);
  });

  it('names only suites that exist', () => {
    const missing = citations
      .filter(({ suite }) => !existsSync(join(ROOT, suite)))
      .map(({ at, suite }) => `${at} → ${suite}`);
    expect(missing).toEqual([]);
  });

  it('quotes only titles those suites hold', () => {
    const missing = citations.flatMap(({ at, suite, titles }) => {
      const path = join(ROOT, suite);
      if (!existsSync(path)) return [];
      const source = readFileSync(path, 'utf8');
      return titles
        .filter(title => !source.includes(title))
        .map(title => `${at} → ${suite}「${title}」`);
    });
    expect(missing).toEqual([]);
  });
});

/**
 * `extension.md` is where an application author reads what a custom
 * `FieldKind` may ask for, and `EditorDescriptor.input` is the closed union
 * it has to choose from. The page once listed seven members, two of them
 * misspelled, of a union that has eleven (A-07, C4) — an author who trusts
 * that list writes a kind admission refuses.
 */
describe('the editor inputs extension.md offers', () => {
  const page = readFileSync(join(ROOT, 'docs/design/extension.md'), 'utf8');

  it('names every member of the closed union', () => {
    const missing = EDITOR_INPUTS.filter(input => !page.includes(`'${input}'`));
    expect(missing).toEqual([]);
  });
});
