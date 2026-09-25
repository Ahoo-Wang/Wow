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
 * "The contract" is the theme's registry (`src/ui/theme/tokens.ts`,
 * theme-architecture.md 5): each token a `--fve-<token>` and, where it has
 * a dark half, a `--fve-dark-<token>`. This holds the registry to what the
 * package really reads — every `--fve-*` the stylesheet or the UI's code
 * names is registered, and every token the registry puts in the stylesheet's
 * blocks is declared there as it says — so the contract is complete as well
 * as kept; holds each preset to it; and holds both READMEs to their
 * rendering of it. It also holds the list of names a host can build a
 * picker from, `BUILT_IN_PRESETS`, to the files.
 */

import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import postcss from 'postcss';
import { describe, expect, it } from 'vitest';
import { BUILT_IN_PRESETS } from '../src/ui/presets';
import { hostVariables, type TokenEntry, TOKENS } from '../src/ui/theme/tokens';
import { presetSources } from '../scripts/themes.mjs';
import { hostReads, READMES, renderReadme, ROOT } from './fixtures/themeDocs';

const read = (file: string) => readFileSync(join(ROOT, file), 'utf8');

const ENTRIES: readonly TokenEntry[] = TOKENS;

const REGISTERED = new Set(ENTRIES.flatMap(hostVariables));

/**
 * The variables the engine writes for itself under the public prefix — the
 * expanded view's box, a pinned column's offset, the chart's tap hint. They
 * are not the contract and leave the prefix in S2 (theme-architecture.md
 * 3.2); until then they are named here, one by one, so a new one is not.
 */
const PRIVATE = /^--fve-(expanded-[xywh]|pin-left-|tap-hint)$/;

/** The token blocks of `styles.css`, as each declares its tokens. */
function tokenBlocks(): Record<'light' | 'dark', Map<string, string>> {
  const blocks = { light: new Map<string, string>(), dark: new Map() };
  postcss.parse(read('src/styles.css')).walkRules(rule => {
    if (rule.parent?.type === 'atrule' && rule.parent.params === 'print')
      return;
    const selector = rule.selector.replace(/\s+/g, ' ');
    const mode =
      selector === '.fve-root, .fve-tokens'
        ? 'light'
        : selector.startsWith(".dark .fve-root:not([data-theme='light'])")
          ? 'dark'
          : undefined;
    if (!mode) return;
    rule.walkDecls(/^--/, decl => {
      blocks[mode].set(
        decl.prop,
        decl.value
          .replace(/\s+/g, ' ')
          .replace(/\(\s+/g, '(')
          .replace(/\s+\)/g, ')')
          .trim(),
      );
    });
  });
  return blocks;
}

describe('the registry is the contract', () => {
  it('names every host variable the package reads', () => {
    const unregistered = [
      ...new Set(
        hostReads()
          .map(({ variable }) => variable)
          .filter(
            variable => !REGISTERED.has(variable) && !PRIVATE.test(variable),
          ),
      ),
    ];
    expect(unregistered).toEqual([]);
  });

  it('is read: every variable of it is read somewhere, bar what only presets and charts read', () => {
    // `brand` is read by the `brand` preset and `chart-patterns` by a
    // chart off its computed style (`readChartTheme`); every other one is
    // read by the stylesheet or the UI's code.
    const read = new Set(hostReads().map(({ variable }) => variable));
    const unread = [...REGISTERED].filter(
      variable =>
        !read.has(variable) &&
        !/^--fve-(dark-)?brand$|^--fve-chart-patterns$/.test(variable),
    );
    expect(unread).toEqual([]);
  });

  it('declares a block token in the blocks, as the registry says', () => {
    const blocks = tokenBlocks();
    for (const entry of ENTRIES) {
      const [light, dark] = hostVariables(entry);
      const lightValue = blocks.light.get(`--${entry.name}`);
      const darkValue = blocks.dark.get(`--${entry.name}`);
      if (!entry.block) {
        expect(lightValue, entry.name).toBeUndefined();
        continue;
      }
      expect(lightValue, entry.name).toMatch(
        new RegExp(`^var\\(${light}(,|\\)$)`),
      );
      if (dark)
        expect(darkValue, entry.name).toMatch(
          new RegExp(`^var\\(${dark}(,|\\)$)`),
        );
      else expect(darkValue, entry.name).toBeUndefined();
      // A built-in value that is another token is the registry's fallback.
      const other = /^var\(--fve-[\w-]+, var\(--([\w-]+)\)\)$/.exec(
        lightValue!,
      )?.[1];
      const registered = ENTRIES.some(({ name }) => name === other);
      expect(entry.fallback, entry.name).toBe(registered ? other : undefined);
    }
  });

  it('declares nothing in the blocks it does not register', () => {
    const blocks = tokenBlocks();
    const tokens = new Set(
      ENTRIES.filter(entry => entry.block).map(({ name }) => `--${name}`),
    );
    const unregistered = [...blocks.light.keys(), ...blocks.dark.keys()].filter(
      token =>
        !tokens.has(token) &&
        /^var\(--fve-/.test(
          blocks.light.get(token) ?? blocks.dark.get(token) ?? '',
        ),
    );
    expect(unregistered).toEqual([]);
  });

  it('gives each layout variable one default wherever it is read', () => {
    for (const entry of ENTRIES.filter(({ tier }) => tier === 'layout'))
      expect(
        hostReads().filter(
          ({ variable, fallback }) =>
            variable === `--fve-${entry.name}` && fallback === undefined,
        ),
        entry.name,
      ).toEqual([]);
  });
});

describe.each(READMES)('%s', (file, language) => {
  // Regenerate with `pnpm --filter @ahoo-wang/wow-view-engine theme:docs`.
  it(`is the registry's rendering, in ${language}`, async () => {
    await expect(await renderReadme(file, language)).toMatchFileSnapshot(
      join(ROOT, file),
    );
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

  it('sets only variables the registry gives a preset', () => {
    const presetOwned = new Set(
      ENTRIES.filter(entry => entry.preset).flatMap(hostVariables),
    );
    const outside: string[] = [];
    sheet.walkDecls(decl => {
      if (!presetOwned.has(decl.prop)) outside.push(decl.prop);
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
    const files = readdirSync(join(ROOT, 'src', 'themes'))
      .filter(file => file.endsWith('.css'))
      .map(file => file.slice(0, -'.css'.length))
      .sort();
    expect(files).toEqual([...BUILT_IN_PRESETS].sort());
  });
});
