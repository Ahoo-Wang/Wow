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
 * The API reference's generated parts on the documentation site
 * (`documentation/docs/{en,zh}/reference/typescript/wow-view-engine/`), held
 * to what they are generated from, without a build:
 *
 * - the issue-code tables of the two `issues.md` pages, rendered from
 *   `test/surface/issues.txt` and each language's catalogue between their
 *   `issue-codes:begin` / `issue-codes:end` comments — regenerate with
 *   `pnpm --filter @ahoo-wang/wow-view-engine reference:docs`;
 * - the English symbol index, one page per entry, which
 *   `scripts/api-report.mjs` writes from the built declarations (`test:api
 *   -u`): here its names are held to the entry's surface list and its
 *   "Covered in" column to the curated pages, so an index that lags either
 *   fails before anything is built. Its kinds and summaries are the
 *   report's to check, after a build.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { en } from '../src/ui/messages/en.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import {
  INDEX_PAGES,
  REFERENCE,
  coverageCell,
  curatedCoverage,
  parseIndex,
} from '../scripts/symbol-index.mjs';

type Language = 'en' | 'zh';

const ROOT = join(import.meta.dirname, '..');
const DOCS = join(ROOT, '../../documentation/docs');

const ISSUE_PAGES: readonly [file: string, language: Language][] = [
  [join(DOCS, 'en/reference/typescript/wow-view-engine/issues.md'), 'en'],
  [join(DOCS, 'zh/reference/typescript/wow-view-engine/issues.md'), 'zh'],
];

const CATALOGUE: Record<Language, Readonly<Record<string, string>>> = {
  en,
  zh: zhCN,
};

const HEADER: Record<Language, string> = {
  en: '| Code | Default wording |',
  zh: '| 代码 | 默认措辞 |',
};

/** Every line of a surface list that is not its heading. */
function listed(file: string): string[] {
  return readFileSync(join(ROOT, 'test/surface', file), 'utf8')
    .split('\n')
    .filter(line => line !== '' && !line.startsWith('#'));
}

/** A sentence as a table cell: its pipes escaped, its tags as text. */
const cell = (text: string) =>
  text
    .replace(/\s+/g, ' ')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\|/g, '\\|');

/**
 * The issue codes, a table per namespace (the code's first segment), each
 * with the wording the language's catalogue gives it.
 */
function issueTables(language: Language): string {
  const byNamespace = new Map<string, string[]>();
  for (const code of listed('issues.txt')) {
    const namespace = code.split('.')[0];
    byNamespace.set(namespace, [...(byNamespace.get(namespace) ?? []), code]);
  }
  const words = CATALOGUE[language];
  return [...byNamespace]
    .map(([namespace, codes]) =>
      [
        `### \`${namespace}.*\` {#issues-${namespace}}`,
        '',
        HEADER[language],
        '|---|---|',
        ...codes.map(code => `| \`${code}\` | ${cell(words[code] ?? '')} |`),
      ].join('\n'),
    )
    .join('\n\n');
}

/** A page with its generated region rendered again: what it should be. */
function renderIssuePage(file: string, language: Language): string {
  const text = readFileSync(file, 'utf8');
  const begin = '<!-- issue-codes:begin -->';
  const end = '<!-- issue-codes:end -->';
  const from = text.indexOf(begin);
  const to = text.indexOf(end);
  if (from < 0 || to < from)
    throw new Error(`${file} has no ${begin} … ${end} region`);
  return `${text.slice(0, from + begin.length)}\n\n::: v-pre\n\n${issueTables(language)}\n\n:::\n\n${text.slice(to)}`;
}

describe.each(ISSUE_PAGES)('%s', (file, language) => {
  // Regenerate with `pnpm --filter @ahoo-wang/wow-view-engine reference:docs`.
  it(`lists every issue code with its ${language} wording`, async () => {
    await expect(renderIssuePage(file, language)).toMatchFileSnapshot(file);
  });
});

describe('the symbol index', () => {
  const coverage = curatedCoverage();

  describe.each(Object.entries(INDEX_PAGES))('%s', (report, { page }) => {
    const rows = parseIndex(readFileSync(join(REFERENCE, page), 'utf8'));
    const surface = listed(`${report}.txt`).map(line => line.split(/\s+/)[1]);

    // After a change to the surface: a build, then `test:api -u`.
    it('lists exactly the names the entry exports', () => {
      expect([...rows.keys()].sort()).toEqual([...surface].sort());
    });

    it('links each name to the curated page that covers it', () => {
      const stale = [...rows]
        .filter(([name, cell]) => cell !== coverageCell(name, coverage))
        .map(([name]) => name);
      expect(stale).toEqual([]);
    });
  });

  it('links only names some entry exports', () => {
    const exported = new Set(
      Object.keys(INDEX_PAGES).flatMap(report =>
        listed(`${report}.txt`).map(line => line.split(/\s+/)[1]),
      ),
    );
    expect([...coverage.keys()].filter(name => !exported.has(name))).toEqual(
      [],
    );
  });
});
