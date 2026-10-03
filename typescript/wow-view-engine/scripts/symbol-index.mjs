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

import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The API reference's generated half: one English page per entry on the
 * documentation site, every name the entry exports with its kind, the first
 * sentence of its TSDoc and the hand-written page that covers it, if one
 * does (the curated half, `reference/typescript/wow-view-engine/*.md`).
 *
 * `scripts/api-report.mjs` reads the entries' doc model (API Extractor's
 * `.api.json`, as plain JSON) and renders the pages through `renderIndex`;
 * it fails when a committed page differs and writes them with `-u`.
 * `test/referenceDocs.test.ts` holds the names and the coverage column
 * without a build, so a page that lags the surface or the curated pages
 * fails the unit tests too.
 */

/**
 * The English reference folder on the documentation site, where the index
 * pages are written and the curated pages read. `import.meta.dirname`
 * rather than a `file:` URL, which a jsdom test does not have.
 */
export const REFERENCE = join(
  import.meta.dirname,
  '../../../documentation/docs/en/reference/typescript/wow-view-engine',
);

/** Each entry's report name → its specifier and its index page. */
export const INDEX_PAGES = {
  root: { entry: '@ahoo-wang/wow-view-engine', page: 'symbols.md' },
  react: {
    entry: '@ahoo-wang/wow-view-engine/react',
    page: 'symbols-react.md',
  },
  ui: { entry: '@ahoo-wang/wow-view-engine/ui', page: 'symbols-ui.md' },
  testing: {
    entry: '@ahoo-wang/wow-view-engine/testing',
    page: 'symbols-testing.md',
  },
  'react-router': {
    entry: '@ahoo-wang/wow-view-engine/react-router',
    page: 'symbols-react-router.md',
  },
};

const KINDS = {
  Class: 'class',
  Enum: 'enum',
  Function: 'function',
  Interface: 'interface',
  Namespace: 'namespace',
  TypeAlias: 'type',
  Variable: 'const',
};

/**
 * The first sentence of a doc comment, on one line: the text before the
 * first block tag, up to the first full stop outside backticks that ends a
 * sentence. `{@link X}` reads as `X`.
 */
export function firstSentence(docComment) {
  const body = (docComment ?? '')
    .replace(/^\s*\/\*\*/, '')
    .replace(/\*\/\s*$/, '')
    .split('\n')
    .map(line => line.replace(/^\s*\* ?/, ''))
    .join('\n');
  const paragraph = body
    .split(/\n\s*\n|\n\s*@/)[0]
    .replace(
      /\{@link\s+([^}|\s]+)(?:\s*\|\s*([^}]+))?\}/g,
      (_, target, label) => (label ? label.trim() : `\`${target}\``),
    )
    .replace(/\s+/g, ' ')
    .trim();
  if (paragraph.startsWith('@')) return '';
  let inCode = false;
  for (let index = 0; index < paragraph.length; index++) {
    const char = paragraph[index];
    if (char === '`') inCode = !inCode;
    if (inCode) continue;
    if (
      (char === '.' &&
        (index === paragraph.length - 1 || paragraph[index + 1] === ' ')) ||
      char === '。'
    )
      return paragraph.slice(0, index + 1);
  }
  return paragraph;
}

/** The items of one entry's doc model: name, kind and summary, by name. */
export function itemsOf(apiJson) {
  const [entryPoint] = apiJson.members;
  const items = new Map();
  for (const member of entryPoint.members) {
    // API Extractor suffixes a name two declarations share (`Text_2`).
    const name = member.name.replace(/_\d+$/, '');
    const kind = KINDS[member.kind] ?? member.kind;
    const found = items.get(name);
    if (found) {
      if (!found.kind.split(', ').includes(kind)) found.kind += `, ${kind}`;
      if (!found.summary) found.summary = firstSentence(member.docComment);
      continue;
    }
    items.set(name, { name, kind, summary: firstSentence(member.docComment) });
  }
  return [...items.values()].sort((a, b) =>
    a.name < b.name ? -1 : a.name > b.name ? 1 : 0,
  );
}

const ANCHORED = /^#{2,4}\s+(.*?)\s*\{#(api-[\w$]+)\}\s*$/;
const DECLARED =
  /^export\s+(?:declare\s+)?(?:abstract\s+)?(?:interface|type|class|function|const|enum)\s+([\w$]+)/gm;

/**
 * Which curated page covers each name: a heading anchored `{#api-Name}`, and
 * every name the signature blocks under such a heading declare (a block
 * that imports is an example, whose names are the reader's own). The pages
 * are the English ones; the Chinese ones carry the same anchors.
 */
export function curatedCoverage(dir = REFERENCE) {
  const coverage = new Map();
  const pages = readdirSync(dir)
    .filter(file => file.endsWith('.md') && !file.startsWith('symbols'))
    .sort();
  for (const page of pages) {
    const text = readFileSync(join(dir, page), 'utf8');
    const title = /^#\s+(.+)$/m.exec(text)?.[1].trim() ?? page;
    const link = page === 'index.md' ? './' : `./${page.replace(/\.md$/, '')}`;
    let anchor = null;
    let fence = null;
    for (const line of text.split('\n')) {
      if (fence) {
        if (line.trimStart().startsWith(fence.marker)) {
          // A signature block, not an example: an example imports.
          if (
            anchor &&
            /^(ts|tsx|typescript)$/.test(fence.language) &&
            !fence.code.some(code => /^\s*import\s/.test(code))
          )
            for (const [, name] of fence.code.join('\n').matchAll(DECLARED))
              if (!coverage.has(name))
                coverage.set(name, { title, href: `${link}#${anchor}` });
          fence = null;
        } else fence.code.push(line);
        continue;
      }
      const open = /^\s*(`{3,}|~{3,})\s*([\w-]*)/.exec(line);
      if (open) {
        fence = { marker: open[1], language: open[2], code: [] };
        continue;
      }
      const heading = /^#{1,6}\s/.test(line) ? ANCHORED.exec(line) : null;
      if (heading) {
        anchor = heading[2];
        const name = anchor.slice('api-'.length);
        if (!coverage.has(name))
          coverage.set(name, { title, href: `${link}#${anchor}` });
      } else if (/^#{1,6}\s/.test(line)) anchor = null;
    }
  }
  return coverage;
}

/**
 * A summary as a table cell: a pipe escaped, and outside a code span an
 * angle bracket too, so `<main>` in prose is text rather than a tag the
 * site's Vue compiler reads.
 */
const cell = text =>
  text
    .split('`')
    .map((part, index) =>
      index % 2 === 0 ? part.replace(/</g, '&lt;').replace(/>/g, '&gt;') : part,
    )
    .join('`')
    .replace(/\|/g, '\\|');

/** The coverage column of one name. */
export function coverageCell(name, coverage) {
  const covered = coverage.get(name);
  return covered ? `[${covered.title}](${covered.href})` : '—';
}

/** One entry's index page. */
export function renderIndex(report, items, coverage) {
  const { entry } = INDEX_PAGES[report];
  const covered = items.filter(({ name }) => coverage.has(name)).length;
  return [
    '---',
    `title: '${entry} symbol index'`,
    `description: 'Every name ${entry} exports, generated from its TSDoc.'`,
    '---',
    '',
    `# ${entry} symbol index`,
    '',
    '<!-- Generated by typescript/wow-view-engine/scripts/api-report.mjs from the',
    '     built declarations; regenerate with',
    '     `pnpm --filter @ahoo-wang/wow-view-engine test:api -u` after a build. -->',
    '',
    `Every name \`${entry}\` exports — ${items.length} — with its kind and the first sentence of its TSDoc, generated from the built declarations. ${covered} of them are described by hand in the curated pages, linked in the last column; the rest are read from their source through the signature in the [API report](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/test/api/${report}.api.md). This index is English only.`,
    '',
    // A summary may hold a URL template's `{{name}}`, which the site would
    // read as a Vue interpolation even in a code span.
    '::: v-pre',
    '',
    '| Symbol | Kind | Summary | Covered in |',
    '|---|---|---|---|',
    ...items.map(
      ({ name, kind, summary }) =>
        `| \`${name}\` | ${kind} | ${cell(summary) || '—'} | ${coverageCell(name, coverage)} |`,
    ),
    '',
    ':::',
    '',
  ].join('\n');
}

/** The rows of a committed index page: name → coverage cell. */
export function parseIndex(markdown) {
  const rows = new Map();
  for (const line of markdown.split('\n')) {
    const match = /^\| `([^`]+)` \| .* \| ([^|]+) \|$/.exec(line);
    if (match) rows.set(match[1], match[2].trim());
  }
  return rows;
}
