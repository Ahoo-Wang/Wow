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
 * The README's theme contract, rendered from the registry
 * (`src/ui/theme/tokens.ts` and its words in `tokenDocs.ts`), and the host
 * variables the package's source reads, found where they are read.
 *
 * Three regions of each README are generated, each between a pair of
 * `<!-- name:begin -->` / `<!-- name:end -->` markers: the token table, the
 * table of the host's layout variables, and the list of attributes that
 * make a chart read its theme again. `test/themeFiles.test.ts` holds each
 * README to its rendering (`pnpm --filter @ahoo-wang/wow-view-engine
 * theme:docs` writes them). The defaults are read, not written: a token's
 * off the stylesheet's own blocks, a layout variable's off the `var()` that
 * reads it — the registry only has words where the default is no single
 * value or token (a mix, a stack, "unset").
 */

import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import postcss from 'postcss';
import { format, resolveConfig } from 'prettier';
import ts from 'typescript';
import { TOKEN_DOCS, type Words } from '../../src/ui/theme/tokenDocs';
import {
  THEME_ATTRIBUTES,
  type TokenEntry,
  TOKENS,
} from '../../src/ui/theme/tokens';
import { declared, type Mode } from './themeTokens';

export const ROOT = join(import.meta.dirname, '..', '..');

export type Language = keyof Words;

/** A README and the language its tables are written in. */
export const READMES: readonly [file: string, language: Language][] = [
  ['README.md', 'en'],
  ['README.zh-CN.md', 'zh'],
];

const ENTRIES: readonly TokenEntry[] = TOKENS;

const code = (text: string) => `\`${text}\``;

const SAME: Words = { en: 'the same', zh: '同左' };

/** One `--fve-*` read in the source, and the fallback it is read with. */
export interface HostRead {
  variable: string;
  fallback?: string;
  file: string;
}

const READ = /--fve-[\w-]+/g;
const READ_WITH_FALLBACK = /var\((--fve-[\w-]+)\s*(?:,\s*([^()]*))?\)/g;

function readsIn(text: string, file: string): HostRead[] {
  const reads: HostRead[] = [];
  const found = new Set<number>();
  for (const match of text.matchAll(READ_WITH_FALLBACK)) {
    reads.push({
      variable: match[1],
      fallback: match[2]?.trim() || undefined,
      file,
    });
    found.add(match.index + match[0].indexOf(match[1]));
  }
  for (const match of text.matchAll(READ))
    if (!found.has(match.index)) reads.push({ variable: match[0], file });
  return reads;
}

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return /\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

let sourceReads: HostRead[] | undefined;

/**
 * Every `--fve-*` the package's code names: in a declaration of
 * `styles.css` (a comment is not a read), and in a string of `src/ui` —
 * a class a component writes (`max-w-[var(--fve-record-text-max-w,24rem)]`),
 * a style it sets, a name it reads off the computed style — found on the
 * TypeScript AST, so a comment there is not one either. The registry in
 * `src/ui/theme/` is left out: it spells the variables, it reads none.
 */
export function hostReads(): readonly HostRead[] {
  if (sourceReads) return sourceReads;
  const reads: HostRead[] = [];
  postcss
    .parse(readFileSync(join(ROOT, 'src', 'styles.css'), 'utf8'))
    .walkDecls(decl => {
      reads.push(...readsIn(decl.value, 'src/styles.css'));
    });
  // The registry spells the variables rather than reading them.
  const registry = join(ROOT, 'src', 'ui', 'theme');
  for (const path of sourceFiles(join(ROOT, 'src', 'ui'))) {
    if (path.startsWith(registry)) continue;
    const file = relative(ROOT, path);
    const source = ts.createSourceFile(
      path,
      readFileSync(path, 'utf8'),
      ts.ScriptTarget.Latest,
      true,
    );
    const visit = (node: ts.Node) => {
      if (
        ts.isStringLiteralLike(node) ||
        ts.isTemplateHead(node) ||
        ts.isTemplateMiddle(node) ||
        ts.isTemplateTail(node)
      )
        reads.push(...readsIn(node.text, file));
      ts.forEachChild(node, visit);
    };
    visit(source);
  }
  sourceReads = reads;
  return reads;
}

/** A token's built-in value in one mode, as `styles.css` declares it. */
const builtIn = (mode: Mode) =>
  declared('neutral', mode, 'semantic', {}) as ReadonlyMap<string, string>;

const LIGHT = builtIn('light');
const DARK = builtIn('dark');

/**
 * What the table says a token is when nothing sets it, in one mode: its
 * words where the registry has them, or else what the stylesheet declares —
 * a literal, or the other token it is. Anything else has to be put in words.
 */
function defaultCell(
  entry: TokenEntry,
  mode: Mode,
  language: Language,
): string {
  const doc = TOKEN_DOCS[entry.name as keyof typeof TOKEN_DOCS];
  if (mode === 'dark') {
    if (entry.modes === 1) return '—';
    if (doc.dark) return doc.dark[language];
    if (doc.light) return SAME[language];
  } else if (doc.light) return doc.light[language];
  const value = (mode === 'light' ? LIGHT : DARK).get(`--${entry.name}`);
  if (value === undefined)
    throw new Error(`--fve-${entry.name} needs words for its ${mode} default`);
  const token = /^var\(--([\w-]+)\)$/.exec(value)?.[1];
  if (token) return code(token);
  if (value.includes('var('))
    throw new Error(`--fve-${entry.name} needs words for ${value}`);
  return code(value);
}

const HEADERS: Record<Language, { tokens: string[]; layout: string[] }> = {
  en: {
    tokens: ['Token', 'Role', 'Light default', 'Dark default'],
    layout: ['Variable', 'Role', 'Default'],
  },
  zh: {
    tokens: ['Token', '用途', '亮色默认值', '暗色默认值'],
    layout: ['变量', '用途', '默认值'],
  },
};

const table = (header: string[], rows: string[][]) =>
  [header, header.map(() => '-'), ...rows]
    .map(
      cells =>
        `| ${cells.map(cell => cell.replace(/\|/g, '\\|')).join(' | ')} |`,
    )
    .join('\n');

/** The token table: every theme token of the registry, in its order. */
export function tokenTable(language: Language): string {
  return table(
    HEADERS[language].tokens,
    ENTRIES.filter(entry => entry.tier !== 'layout').map(entry => [
      code(entry.name),
      TOKEN_DOCS[entry.name as keyof typeof TOKEN_DOCS].role[language],
      defaultCell(entry, 'light', language),
      defaultCell(entry, 'dark', language),
    ]),
  );
}

/**
 * The layout variables' default: the fallback every `var()` that reads one
 * gives it, which the source keeps the same wherever it is read.
 */
export function layoutDefault(variable: string): string {
  const fallbacks = new Set(
    hostReads()
      .filter(read => read.variable === variable)
      .map(read => read.fallback),
  );
  const [only, ...more] = fallbacks;
  if (only === undefined || more.length > 0)
    throw new Error(
      `${variable} is read with ${[...fallbacks].join(', ')}; it needs one default`,
    );
  return only;
}

/** The host's layout variables: lengths and a level, not the theme. */
export function layoutTable(language: Language): string {
  return table(
    HEADERS[language].layout,
    ENTRIES.filter(entry => entry.tier === 'layout').map(entry => [
      code(`--fve-${entry.name}`),
      TOKEN_DOCS[entry.name as keyof typeof TOKEN_DOCS].role[language],
      code(layoutDefault(`--fve-${entry.name}`)),
    ]),
  );
}

/** The attributes that make a chart read its theme again, as a list. */
export function attributeList(language: Language): string {
  const names = THEME_ATTRIBUTES.map(code);
  const last = names.pop();
  return language === 'en'
    ? `${names.join(', ')} or ${last}`
    : `${names.join('、')} 或 ${last}`;
}

const RENDER: Record<string, (language: Language) => string> = {
  'theme-tokens': language => `\n${tokenTable(language)}\n`,
  'layout-variables': language => `\n${layoutTable(language)}\n`,
  'chart-attributes': attributeList,
};

/**
 * One README with its generated regions written from the registry, and
 * formatted as the repository formats Markdown — what the file should be.
 */
export async function renderReadme(
  file: string,
  language: Language,
): Promise<string> {
  const path = join(ROOT, file);
  let text = readFileSync(path, 'utf8');
  for (const [region, render] of Object.entries(RENDER)) {
    const begin = `<!-- ${region}:begin -->`;
    const end = `<!-- ${region}:end -->`;
    const from = text.indexOf(begin);
    const to = text.indexOf(end);
    if (from < 0 || to < from)
      throw new Error(`${file} has no ${begin} … ${end} region`);
    text = `${text.slice(0, from + begin.length)}${render(language)}${text.slice(to)}`;
  }
  const options = await resolveConfig(path);
  return format(text, { ...options, filepath: path });
}
