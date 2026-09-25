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
 * theme-architecture.md 5): each token a host's `--fve-<token>` and a
 * preset's `--fvp-<token>` and, where it has a dark half, the `-dark-` pair
 * of those. This holds the registry to what the package really reads —
 * every `--fve-*` the stylesheet or the UI's code names is registered, and
 * every token the registry puts in the stylesheet's blocks is declared there
 * as it says, reading the host's layer before the preset's — so the contract
 * is complete as well as kept; holds each preset to it; holds the reset rule
 * to the registry's preset layer; and holds both READMEs to their rendering
 * of it. It also holds the list of names a host can build a picker from,
 * `BUILT_IN_PRESETS`, to the files.
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import postcss from 'postcss';
import { compile } from 'tailwindcss';
import { describe, expect, it } from 'vitest';
import { BUILT_IN_PRESETS } from '../src/ui/presets';
import {
  declaredVariable,
  hostVariables,
  presetVariables,
  type TokenEntry,
  TOKENS,
} from '../src/ui/theme/tokens';
import { presetSources } from '../scripts/themes.mjs';
import {
  hostReads,
  READMES,
  renderReadme,
  renderStyles,
  ROOT,
} from './fixtures/themeDocs';

const read = (file: string) => readFileSync(join(ROOT, file), 'utf8');

const ENTRIES: readonly TokenEntry[] = TOKENS;

const REGISTERED = new Set(ENTRIES.flatMap(hostVariables));

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
          .filter(variable => !REGISTERED.has(variable)),
      ),
    ];
    expect(unregistered).toEqual([]);
  });

  it('is read: every variable of it is read somewhere', () => {
    // By the stylesheet or the UI's code — the brand colour and its bounds
    // by the derivation in `styles.css` (theme-architecture.md 2).
    const read = new Set(hostReads().map(({ variable }) => variable));
    const unread = [...REGISTERED].filter(variable => !read.has(variable));
    expect(unread).toEqual([]);
  });

  it('writes no variable of the public prefix for itself', () => {
    // What the engine derives or measures is `--_fve-*` (theme-architecture.md
    // 3.2): a `--fve-*` a component sets would be a host variable the host
    // cannot own. The one the stylesheet sets is on paper, where the chart
    // patterns are pinned on over any host's pin.
    const written: string[] = [];
    postcss.parse(read('src/styles.css')).walkDecls(/^--fve-/, decl => {
      const media = decl.parent?.parent;
      if (!(media?.type === 'atrule' && media.params === 'print'))
        written.push(decl.prop);
    });
    expect(written).toEqual([]);
  });

  it('declares a block token in the blocks, reading the host first, as the registry says', () => {
    const blocks = tokenBlocks();
    for (const entry of ENTRIES) {
      const declared = declaredVariable(entry);
      if (!declared) {
        expect(blocks.light.get(`--${entry.name}`), entry.name).toBeUndefined();
        expect(
          blocks.light.get(`--_fve-${entry.name}`),
          entry.name,
        ).toBeUndefined();
        continue;
      }
      const presets = presetVariables(entry);
      hostVariables(entry).forEach((host, half) => {
        const value = blocks[half === 0 ? 'light' : 'dark'].get(declared);
        // `var(--fve-x, var(--fvp-x, <built-in>))`, or with no built-in
        // value; a token no preset owns reads the host alone. A token the
        // brand colour derives reads the derivation between the two,
        // `var(--_fve-brand-[dark-]x, …)` (theme-architecture.md 2).
        const preset = presets[half];
        const brand = `--_fve-brand-${half ? 'dark-' : ''}${entry.name}`;
        const shape = entry.brand
          ? `^var\\(${host}, var\\(${brand}, var\\(${preset}(, .+)?\\)\\)\\)$`
          : preset
            ? `^var\\(${host}, var\\(${preset}(, .+)?\\)\\)$`
            : `^var\\(${host}(, .+)?\\)$`;
        expect(value, `${entry.name} (${half ? 'dark' : 'light'})`).toMatch(
          new RegExp(shape),
        );
      });
      if (entry.modes === 1)
        expect(blocks.dark.get(declared), entry.name).toBeUndefined();
      // A built-in value that is another token is the registry's fallback.
      const other =
        /^var\(--fve-[\w-]+, (?:var\(--_fve-brand-[\w-]+, )?(?:var\(--fvp-[\w-]+, )?var\(--(?:_fve-)?([\w-]+)\)\)+$/.exec(
          blocks.light.get(declared)!,
        )?.[1];
      const registered = ENTRIES.some(({ name }) => name === other);
      expect(entry.fallback, entry.name).toBe(registered ? other : undefined);
    }
  });

  it('declares nothing in the blocks it does not register', () => {
    const blocks = tokenBlocks();
    const tokens = new Set(ENTRIES.map(declaredVariable));
    const unregistered = [...blocks.light.keys(), ...blocks.dark.keys()].filter(
      token =>
        !tokens.has(token) &&
        /^var\(--fve-/.test(
          blocks.light.get(token) ?? blocks.dark.get(token) ?? '',
        ),
    );
    expect(unregistered).toEqual([]);
  });

  it('reads the host before the preset wherever a preset variable is read', () => {
    // Outside the blocks too: the surface's type, the density a preset
    // recommends. A preset variable read on its own would let a preset beat
    // the host.
    const alone: string[] = [];
    postcss.parse(read('src/styles.css')).walkDecls(decl => {
      const value = decl.value
        .replace(/\s+/g, ' ')
        .replace(/\(\s+/g, '(')
        .replace(/\s+\)/g, ')');
      for (const match of value.matchAll(/--fvp-[\w-]+/g)) {
        const host = match[0].replace('--fvp-', '--fve-');
        // The brand's derivation may sit between the two layers.
        const brand = match[0].replace('--fvp-', '--_fve-brand-');
        if (
          !value.includes(`var(${host}, var(${match[0]}`) &&
          !value.includes(`var(${host}, var(${brand}, var(${match[0]}`)
        )
          alone.push(`${decl.prop}: ${value}`);
      }
    });
    expect(alone).toEqual([]);
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

  // The brand's relative colours are the stylesheet's, in its one feature
  // query (theme-architecture.md 2.6): a preset only gives numbers.
  it('holds no at-rule', () => {
    const atRules: string[] = [];
    sheet.walkAtRules(rule => {
      atRules.push(`@${rule.name} ${rule.params}`);
    });
    expect(atRules).toEqual([]);
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
    const presetOwned = new Set(ENTRIES.flatMap(presetVariables));
    const outside: string[] = [];
    sheet.walkDecls(decl => {
      if (!presetOwned.has(decl.prop)) outside.push(decl.prop);
    });
    expect(outside).toEqual([]);
  });

  // The reset rule empties the preset layer before the block applies, so
  // `initial` would say nothing: a preset writes only what it changes.
  it('writes only what it changes, never initial', () => {
    sheet.walkDecls(decl => {
      expect(decl.value, decl.prop).not.toBe('initial');
    });
  });
});

describe("the engine's own variables", () => {
  // Tailwind turns an underscore in an arbitrary value into a space unless it
  // sits in a `var()` name it can see — after an operator in a `calc()` it
  // cannot — so `calc(100%+2*var(--_fve-x))` compiled to `var(-- fve-x)`,
  // and the minifier dropped the rule without a word (a table header lost
  // its room, 2026-09-25). Every class of `src/ui` that names one is
  // compiled here and must keep the name whole.
  it('keep their names whole in every class that reads one', async () => {
    const files: string[] = [];
    const walk = (directory: string) => {
      for (const name of readdirSync(directory)) {
        const path = join(directory, name);
        if (statSync(path).isDirectory()) walk(path);
        else if (/\.tsx?$/.test(name)) files.push(path);
      }
    };
    walk(join(ROOT, 'src', 'ui'));
    const classes = new Set<string>();
    for (const file of files)
      for (const [, text] of readFileSync(file, 'utf8').matchAll(
        /['"`]([^'"`]*_fve-[^'"`]*)['"`]/g,
      ))
        for (const token of text.split(/\s+/))
          if (/[[(].*_fve-/.test(token)) classes.add(token);
    expect(classes.size).toBeGreaterThan(0);
    const broken: string[] = [];
    for (const name of classes) {
      const compiler = await compile('@tailwind utilities;', {
        base: ROOT,
        loadStylesheet: async () => ({ path: '', content: '', base: ROOT }),
      });
      if (/--\s+fve-/.test(compiler.build([name]))) broken.push(name);
    }
    expect(broken).toEqual([]);
  });
});

describe('the reset rule', () => {
  // Regenerate with `pnpm --filter @ahoo-wang/wow-view-engine theme:docs`.
  it("empties the registry's preset layer, as the stylesheet writes it", async () => {
    await expect(await renderStyles()).toMatchFileSnapshot(
      join(ROOT, 'src', 'styles.css'),
    );
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
