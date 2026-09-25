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
 * The built-in presets are written in the host's contract and nothing else
 * (themes.md 1.1): whatever a built-in preset does, a host's own preset can
 * do with the same words, and a preset that needed more would be a gap in
 * the mechanism, fixed there — never a private selector, a component's
 * internals or a code path for one preset.
 *
 * "The contract" is what the README documents: the token table under
 * 「Customising the theme」, each token a `--fve-<token>` and, where it has a
 * dark default, a `--fve-dark-<token>`. So this reads the README rather than
 * the stylesheet, and holds both ends to it — every variable a preset sets
 * is documented, and every host variable the stylesheet reads is too, so the
 * contract is complete as well as kept. It also holds the list of names a
 * host can build a picker from, `BUILT_IN_PRESETS`, to the files.
 */

import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import postcss from 'postcss';
import { describe, expect, it } from 'vitest';
import { BUILT_IN_PRESETS } from '../src/ui/presets';
import { presetSources } from '../scripts/themes.mjs';

const root = join(import.meta.dirname, '..');
const read = (file: string) => readFileSync(join(root, file), 'utf8');

/**
 * The host variables one README's token table documents. A row's first cell
 * names the token (`shadow-sm`, `-md`, `-lg` spells three); a dark default
 * of `—` means the token has no dark half.
 */
function documented(readme: string): Set<string> {
  const lines = readme.split('\n');
  const header = lines.findIndex(line => /^\| Token\s/.test(line));
  expect(header, 'the README has a token table').toBeGreaterThan(-1);
  const end = lines.findIndex(
    (line, at) => at > header && !line.startsWith('|'),
  );
  const table = lines
    .slice(header + 2, end)
    .map(line => line.split('|').map(cell => cell.trim()));
  const variables = new Set<string>();
  for (const [, first, , , dark] of table) {
    const names = [...first.matchAll(/`([^`]+)`/g)].map(([, name]) => name);
    const stem = names[0].replace(/-[a-z]+$/, '');
    for (const name of names) {
      const token = name.startsWith('-') ? `${stem}${name}` : name;
      variables.add(`--fve-${token}`);
      if (dark !== '—') variables.add(`--fve-dark-${token}`);
    }
  }
  return variables;
}

const README = documented(read('README.md'));
const README_ZH = documented(read('README.zh-CN.md'));

/**
 * Every `--fve-*` the theme of `styles.css` reads: in its tokens, and in the
 * type of the surface. The layout's own host variables — the popups'
 * stacking level, a record table's cap, the expanded view's box — sit in
 * other properties and are documented where they are used.
 */
function readByTheStylesheet(): Set<string> {
  const variables = new Set<string>();
  postcss.parse(read('src/styles.css')).walkDecls(decl => {
    if (!decl.prop.startsWith('--') && decl.prop !== 'font-family') return;
    for (const [variable] of decl.value.matchAll(/--fve-[\w-]+/g))
      variables.add(variable);
  });
  return variables;
}

describe('the token table is the contract', () => {
  it('is the same in both READMEs', () => {
    expect([...README_ZH].sort()).toEqual([...README].sort());
  });

  it('documents every host variable the stylesheet reads', () => {
    const undocumented = [...readByTheStylesheet()].filter(
      variable => !README.has(variable),
    );
    expect(undocumented).toEqual([]);
  });
});

describe.each(presetSources())('preset $name', ({ name, text }) => {
  const sheet = postcss.parse(text);

  it('is one :where([data-fve-preset]) block named as its file', () => {
    const blocks: string[] = [];
    sheet.walkRules(rule => {
      blocks.push(rule.selector);
    });
    expect(blocks).toEqual([`:where([data-fve-preset='${name}'])`]);
  });

  // `brand`'s relative colours need a browser that reads them; without one
  // the block is not there at all and the page is `neutral` (themes.md 2.7).
  it('holds no at-rule, bar brand’s one feature query', () => {
    const atRules: string[] = [];
    sheet.walkAtRules(rule => {
      atRules.push(`@${rule.name} ${rule.params}`);
    });
    expect(atRules).toEqual(
      name === 'brand' ? ['@supports (color: oklch(from red l c h))'] : [],
    );
  });

  // A utility composes its shadow into one `box-shadow` list with its rings,
  // and `none` in that list voids the declaration — a popup's hairline ring
  // with it (measured in the browser, T2). No shadow is a transparent one.
  it('takes a shadow away with a transparent one, never none', () => {
    sheet.walkDecls(/shadow/, decl => {
      expect(decl.value, decl.prop).not.toMatch(/\bnone\b/);
    });
  });

  it('sets only variables the README documents', () => {
    const outside: string[] = [];
    sheet.walkDecls(decl => {
      if (!README.has(decl.prop)) outside.push(decl.prop);
    });
    expect(outside).toEqual([]);
  });
});

describe('the presets a host can name', () => {
  it('are the files the index imports, in its order', () => {
    expect([...BUILT_IN_PRESETS]).toEqual(
      presetSources().map(({ name }) => name),
    );
  });

  it('leave no preset file out of the index', () => {
    const files = readdirSync(join(root, 'src', 'themes'))
      .filter(file => file.endsWith('.css'))
      .map(file => file.slice(0, -'.css'.length))
      .sort();
    expect(files).toEqual([...BUILT_IN_PRESETS].sort());
  });
});
