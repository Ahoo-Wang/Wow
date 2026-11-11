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
 * The API reference's hand-written signatures held to the surface: every
 * declaration in a signature block of a curated page
 * (`documentation/docs/{en,zh}/reference/typescript/wow-view-engine/*.md`, the
 * `symbols*` index pages aside) is the declaration of that name in the API
 * reports (`test/api/*.api.md`), so a signature changed in `src` and accepted
 * with `test:api -u` fails here, with the page, the line and the name, until
 * the reference says it too. No build: the reports are committed.
 *
 * - **A signature block** is a `ts` / `tsx` / `typescript` fence with no
 *   `import` line, the same rule the symbol index's coverage reads; a block
 *   that imports is an example, which `documentation/test/typescript-samples`
 *   compiles instead.
 * - **A declaration** in it is an `interface`, `class`, `function`, `type`,
 *   `enum`, or a `const` with no initializer, exported or `declare`d. A
 *   function with a body, or a `const` with a value, is an example and is
 *   not read.
 * - **Matched by name and kind** to a report's declaration of that name, in
 *   any entry (a type two entries re-export is the same declaration in both),
 *   both normalized: comments, `export` / `declare`, whitespace, the kind of
 *   quote around a string literal, a separator
 *   before a closing bracket and a leading `|` dropped; a qualification by
 *   module (`import('react').`, API Extractor's `React_2.`) and its `_2`
 *   renames dropped too. A function or a type matches one of the report's
 *   declarations of its name whole (an overload each). An interface or a
 *   class matches its heading (type parameters, `extends`, `implements`)
 *   whole, and each member the page shows matches one of the report's
 *   members of that name.
 * - **Left out on purpose:** the page may show only some of a declaration's
 *   members and some of a function's overloads, in its own order: a page
 *   shows what a host writes, and the report holds the rest. TSDoc and line
 *   breaks are the page's own.
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

type Language = 'en' | 'zh';

const ROOT = join(import.meta.dirname, '..');
const DOCS = join(ROOT, '../../documentation/docs');
const REPORTS = join(ROOT, 'test/api');
/** Fewer declarations read than this from a language's pages is the reader gone wrong. */
const READ_AT_LEAST = 90;

/** One declaration: its name, its kind, where it is, and its normalized parts. */
interface Declaration {
  readonly name: string;
  readonly kind: string;
  readonly line: number;
  /** The whole declaration, normalized; for an interface or a class, its heading. */
  readonly head: string;
  /** An interface's or a class's members, normalized, keyed by name. */
  readonly members: ReadonlyMap<string, readonly string[]>;
}

const KINDS =
  /^(?:export\s+)?(?:declare\s+)?(?:abstract\s+)?(interface|class|function|type|enum|const)\s+([A-Za-z_$][\w$]*)/;

/** Text with its comments blanked out, line breaks kept, strings untouched. */
function stripComments(text: string): string {
  let out = '';
  let quote: string | null = null;
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (quote) {
      out += c;
      if (c === '\\') out += text[++i] ?? '';
      else if (c === quote) quote = null;
    } else if (c === "'" || c === '"' || c === '`') {
      quote = c;
      out += c;
    } else if (c === '/' && text[i + 1] === '/') {
      while (i < text.length && text[i] !== '\n') i++;
      out += '\n';
    } else if (c === '/' && text[i + 1] === '*') {
      const end = text.indexOf('*/', i + 2);
      const stop = end < 0 ? text.length : end + 2;
      out += text.slice(i, stop).replace(/[^\n]/g, '');
      i = stop - 1;
    } else out += c;
  }
  return out;
}

/**
 * Splits `text` where `at` is reached outside brackets and strings: the
 * pieces with the line each starts on (1-based, from `firstLine`).
 */
function split(
  text: string,
  ends: (c: string, depth: number, piece: string) => boolean,
  firstLine = 1,
): { text: string; line: number }[] {
  const pieces: { text: string; line: number }[] = [];
  let depth = 0;
  let quote: string | null = null;
  let piece = '';
  let line = firstLine;
  let start = firstLine;
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (c === '\n') line++;
    if (!piece.trim()) start = line;
    piece += c;
    if (quote) {
      if (c === '\\') piece += text[++i] ?? '';
      else if (c === quote) quote = null;
      continue;
    }
    if (c === "'" || c === '"' || c === '`') quote = c;
    else if ('({['.includes(c)) depth++;
    else if (')}]'.includes(c)) depth--;
    if (ends(c, depth, piece)) {
      if (piece.trim()) pieces.push({ text: piece.trim(), line: start });
      piece = '';
    }
  }
  if (piece.trim()) pieces.push({ text: piece.trim(), line: start });
  return pieces;
}

/** A top-level statement ends at a `;`, or at the `}` closing a body that is no type's value. */
function statements(code: string, firstLine: number) {
  return split(
    code,
    (c, depth, piece) =>
      depth === 0 &&
      (c === ';' || (c === '}' && !/^[^{]*=/.test(piece.trim()))),
    firstLine,
  );
}

function normalize(text: string): string {
  return text
    .replace(/\bimport\(\s*(['"])[^'"]*\1\s*\)\./g, '')
    .replace(/\bReact(?:_\d+)?\./g, '')
    .replace(/\b([A-Za-z$][\w$]*?)_\d+\b/g, '$1')
    .replace(/\b(?:export|declare)\s+/g, '')
    .replace(/"/g, "'")
    .replace(/\s+/g, '')
    .replace(/[;,]+([}\])>])/g, '$1')
    .replace(/([=:(<,|&])\|/g, '$1')
    .replace(/;$/, '');
}

/** A member's name: an identifier, a quoted key, an index or a call signature. */
function memberKey(member: string): string {
  const bare = member.replace(
    /^(?:(?:readonly|static|protected|private|public|abstract|get|set|accessor)\s+)+(?=[\w$'"[(<])/,
    '',
  );
  if (/^new\s*[(<]/.test(bare)) return 'new()';
  if (/^[(<]/.test(bare)) return '()';
  if (bare.startsWith('[')) return '[]';
  return /^(['"]?)([\w$]+)\1/.exec(bare)?.[2] ?? bare;
}

function declarations(code: string, firstLine = 1): Declaration[] {
  const found: Declaration[] = [];
  for (const statement of statements(stripComments(code), firstLine)) {
    const match = KINDS.exec(statement.text);
    if (!match) continue;
    const [, kind, name] = match as unknown as [string, string, string];
    const text = statement.text;
    if (kind === 'interface' || kind === 'class') {
      const open = text.indexOf('{');
      const body = text.slice(open + 1, text.lastIndexOf('}'));
      const members = new Map<string, string[]>();
      for (const member of split(
        body,
        (c, depth) => depth === 0 && c === ';',
      )) {
        const key = memberKey(member.text);
        members.set(key, [...(members.get(key) ?? []), normalize(member.text)]);
      }
      found.push({
        name,
        kind,
        line: statement.line,
        head: normalize(text.slice(0, open)),
        members,
      });
      continue;
    }
    // An example, not a signature: a function with a body, a constant with a value.
    if (kind === 'function' && text.endsWith('}')) continue;
    if (kind === 'const' && /^[^=]*=(?!>)/.test(text.replace(/=>/g, '')))
      continue;
    found.push({
      name,
      kind,
      line: statement.line,
      head: normalize(text),
      members: new Map(),
    });
  }
  return found;
}

/** Every report's declarations by name (API Extractor's `_2` renames under the exported name). */
function reportDeclarations(): Map<string, Declaration[]> {
  const byName = new Map<string, Declaration[]>();
  for (const file of readdirSync(REPORTS)
    .filter(f => f.endsWith('.api.md'))
    .sort()) {
    const text = readFileSync(join(REPORTS, file), 'utf8');
    const code = /```ts\n([\s\S]*?)\n```/.exec(text)?.[1] ?? '';
    for (const declaration of declarations(code)) {
      const name = declaration.name.replace(/_\d+$/, '');
      byName.set(name, [...(byName.get(name) ?? []), declaration]);
    }
  }
  return byName;
}

interface Block {
  readonly page: string;
  readonly code: string;
  /** The block's first code line, 1-based. */
  readonly line: number;
}

function signatureBlocks(language: Language): Block[] {
  const dir = join(DOCS, language, 'reference/typescript/wow-view-engine');
  const blocks: Block[] = [];
  for (const page of readdirSync(dir)
    .filter(f => f.endsWith('.md') && !f.startsWith('symbols'))
    .sort()) {
    const lines = readFileSync(join(dir, page), 'utf8').split('\n');
    let fence: {
      marker: string;
      language: string;
      line: number;
      code: string[];
    } | null = null;
    lines.forEach((line, index) => {
      if (fence) {
        if (line.trimStart().startsWith(fence.marker)) {
          if (
            /^(ts|tsx|typescript)$/.test(fence.language) &&
            !fence.code.some(code => /^\s*import\s/.test(code))
          )
            blocks.push({
              page: `${language}/${page}`,
              code: fence.code.join('\n'),
              line: fence.line,
            });
          fence = null;
        } else fence.code.push(line);
        return;
      }
      const open = /^\s*(`{3,}|~{3,})\s*([\w-]*)/.exec(line);
      if (open)
        fence = {
          marker: open[1]!,
          language: open[2]!,
          line: index + 2,
          code: [],
        };
    });
  }
  return blocks;
}

/** What is wrong with the page's declaration against the report's, or nothing. */
function mismatches(
  shown: Declaration,
  reported: readonly Declaration[],
): string[] {
  const sameKind = reported.filter(
    declaration => declaration.kind === shown.kind,
  );
  if (sameKind.length === 0)
    return [
      `is a ${reported.map(d => d.kind).join(' / ')} in the report, not a ${shown.kind}`,
    ];
  if (shown.kind !== 'interface' && shown.kind !== 'class')
    return sameKind.some(declaration => declaration.head === shown.head)
      ? []
      : [
          `page: ${shown.head}\n  report: ${sameKind.map(d => d.head).join('\n  report: ')}`,
        ];
  // The heading and every shown member against the one declaration that best agrees.
  const problems = sameKind.map(declaration => {
    const wrong: string[] = [];
    if (declaration.head !== shown.head)
      wrong.push(`heading — page: ${shown.head}  report: ${declaration.head}`);
    for (const [key, members] of shown.members) {
      const theirs = declaration.members.get(key);
      if (!theirs) wrong.push(`member ${key} — not in the report`);
      else
        for (const member of members)
          if (!theirs.includes(member))
            wrong.push(
              `member ${key} — page: ${member}  report: ${theirs.join(' | ')}`,
            );
    }
    return wrong;
  });
  return problems.reduce((best, next) =>
    next.length < best.length ? next : best,
  );
}

describe('the API reference signatures', () => {
  const reports = reportDeclarations();

  it('reads the reports', () => {
    expect(reports.get('ViewHostProps')?.[0]?.members.has('router')).toBe(true);
    expect(reports.has('Text')).toBe(true);
  });

  for (const language of ['en', 'zh'] as const) {
    const blocks = signatureBlocks(language);

    it(`finds the ${language} pages' signature blocks`, () => {
      expect(blocks.length).toBeGreaterThan(20);
    });

    it(`says in ${language} what the API reports say`, () => {
      const stale: string[] = [];
      let read = 0;
      for (const block of blocks)
        for (const shown of declarations(block.code, block.line)) {
          read++;
          const where = `${block.page}:${shown.line} ${shown.name}`;
          const reported = reports.get(shown.name);
          if (!reported) {
            stale.push(`${where} — in no API report`);
            continue;
          }
          for (const problem of mismatches(shown, reported))
            stale.push(`${where} ${problem}`);
        }
      expect(stale).toEqual([]);
      // Every page's declarations were read, not a block's first alone.
      expect(read).toBeGreaterThan(READ_AT_LEAST);
    });
  }
});

describe('the signature reader', () => {
  it('normalizes what a page writes as the report writes it', () => {
    const [page] = declarations(
      "export interface P extends A<B> {\n  children: import('react').ReactNode;\n  go(path: string, options?: { state?: unknown; replace?: boolean }): void;\n}",
    );
    const [report] = declarations(
      '// @public\nexport interface P extends A<B> {\n    // (undocumented)\n    children: React_2.ReactNode;\n    go(path: string, options?: {\n        state?: unknown;\n        replace?: boolean;\n    }): void;\n    extra?: string;\n}',
    );
    expect(mismatches(page!, [report!])).toEqual([]);
  });

  it('names a member whose type changed', () => {
    const [page] = declarations('export interface P {\n  a?: string;\n}');
    const [report] = declarations('export interface P {\n    a: string;\n}');
    expect(mismatches(page!, [report!])).toEqual([
      'member a — page: a?:string  report: a:string',
    ]);
  });

  it('matches a function to one of its overloads, and skips an example', () => {
    const report = declarations(
      'export function f(a: string): void;\nexport function f(a: number): void;',
    );
    const [page] = declarations('export declare function f(a: number): void;');
    expect(mismatches(page!, report)).toEqual([]);
    expect(
      declarations(
        'export function g() {\n  return 1;\n}\nexport const x = 1;',
      ),
    ).toEqual([]);
  });

  it('reads a type alias whole, a leading bar aside', () => {
    const [page] = declarations("export type T =\n  | 'a'\n  | 'b';");
    const [report] = declarations("export type T = 'a' | 'b';");
    expect(mismatches(page!, [report!])).toEqual([]);
  });
});
