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

/** Read once: three suites below walk the same text. */
const sources = documents.map(document => ({
  at: relative(ROOT, document),
  text: readFileSync(document, 'utf8'),
}));

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
  const citations = sources.flatMap(({ at: where, text }) =>
    text.split('\n').flatMap((line, index) =>
      [
        ...line.matchAll(
          /(test\/[A-Za-z0-9_.\-/]*\.test\.tsx?)((?:\s*「[^」]*」)*)/g,
        ),
      ].map(([, suite, quoted]) => ({
        at: `${where}:${index + 1}`,
        suite,
        titles: [...quoted.matchAll(/「([^」]*)」/g)].map(([, title]) => title),
      })),
    ),
  );

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
  const page = pageAt('docs/design/extension.md');

  it('names every member of the closed union', () => {
    const missing = EDITOR_INPUTS.filter(input => !page.includes(`'${input}'`));
    expect(missing).toEqual([]);
  });
});

/** One page's text, off the single read every suite here shares. */
function pageAt(at: string): string {
  const page = sources.find(source => source.at === at);
  if (!page) throw new Error(`no such design page: ${at}`);
  return page.text;
}

/**
 * The pages that say what the code is today. `decisions.md` and `todo.md` are
 * left out on purpose: a decision entry names the shape it declined or
 * deleted, and a todo names work not written yet, so both properly speak of
 * names that are not in the tree.
 */
const descriptions = sources.filter(
  ({ at }) =>
    at.startsWith('docs/design/') &&
    at !== 'docs/design/decisions.md' &&
    at !== 'docs/design/todo.md',
);

/** Where a name may be answered: this package, its stories, and Wow's own. */
const CODE_TREES = [
  join(ROOT, 'src'),
  join(ROOT, 'test'),
  join(ROOT, '../../stories/view-engine'),
  join(ROOT, '../wow/src'),
];

/**
 * This file is not part of the corpus it reads: it spells every exempted
 * name below, and a rule that answers itself proves nothing.
 */
const SELF = join(ROOT, 'test/docsReferences.test.ts');

/** Every word the trees above spell, as one set — one read of each file. */
function spelled(): Set<string> {
  function walk(dir: string): string[] {
    if (!existsSync(dir)) return [];
    return readdirSync(dir).flatMap(entry => {
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) return walk(path);
      return /\.tsx?$/.test(entry) && path !== SELF ? [path] : [];
    });
  }
  const words = new Set<string>();
  for (const file of CODE_TREES.flatMap(walk))
    for (const [word] of readFileSync(file, 'utf8').matchAll(
      /[A-Za-z_$][\w$]*/g,
    ))
      words.add(word);
  return words;
}

/**
 * The names these pages carry that are nobody's identifier here, each with
 * why. Keep it short: an entry is a name this repository will never spell,
 * not a place to park drift.
 */
const NOT_OURS: Record<string, string> = {
  // A shadcn component this package looked at and declined (ui/README.md).
  Breadcrumb: 'shadcn 注册表里的组件，本包明说不用它',
  // The old package's host component, on README.md's 「不搬迁清单」.
  StatefulViewHost: '重建前那个包的导出，搬迁清单上写着不搬',
  // A Kotlin rule in the Wow server, named so a reader of model.md can go
  // and find the other half of the guard.
  requireScalarMetricFilterFields: 'Wow 服务端（Kotlin）的规则，不在本仓库里',
};

/**
 * A page names code, and the name has to be one the code answers to.
 * `AnalysisController` outlived its rename and `usePinnedEdges` outlived its
 * deletion, both for months, each sending the next reader after something
 * that is not there — the same failure as a citation that does not resolve
 * (C6), one identifier down. So every backticked name shaped like an
 * identifier has to be spelled somewhere under `src/`, `test/`,
 * `stories/view-engine/` or the wow sources the kernels compile against.
 *
 * Shaped like an identifier is the whole of the rule: camelCase or
 * PascalCase, nothing but letters and digits inside the ticks. A name
 * carrying a dot, a slash, a bracket, a call's parentheses or a generic's
 * `<` is a path, a member or a signature and reads differently; a word with
 * no case turn (`pinned`, `AND`) is English or a stored literal. What
 * survives that and is still legitimately not ours goes in `NOT_OURS` with
 * its reason — never a loosened pattern.
 */
describe('the identifiers the design pages name', () => {
  const PATTERNS = [
    /`([a-z][A-Za-z0-9]*[A-Z][A-Za-z0-9]*)`/g,
    /`([A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*)`/g,
  ];
  const named = descriptions.flatMap(({ at, text }) =>
    text.split('\n').flatMap((line, index) =>
      PATTERNS.flatMap(pattern =>
        [...line.matchAll(pattern)].map(([, name]) => ({
          at: `${at}:${index + 1}`,
          name,
        })),
      ),
    ),
  );
  const words = spelled();

  it('names identifiers at all', () => {
    expect(new Set(named.map(({ name }) => name)).size).toBeGreaterThan(200);
  });

  it('names only identifiers the code spells', () => {
    const unknown = [
      ...new Set(
        named
          .filter(({ name }) => !words.has(name) && !(name in NOT_OURS))
          .map(({ at, name }) => `${at} → ${name}`),
      ),
    ];
    expect(unknown).toEqual([]);
  });

  it('keeps the exemption list to names still asked for', () => {
    const stale = Object.keys(NOT_OURS).filter(
      name => words.has(name) || !named.some(entry => entry.name === name),
    );
    expect(stale).toEqual([]);
  });
});
