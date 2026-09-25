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
 * The role tier (theme-architecture.md 4, S3): one surface of the engine's
 * own per role, which a host writes as `--fve-<role>`, a preset as
 * `--fvp-<role>`, and the engine reads as `--_fve-<role>`.
 *
 * Each role, one by one: unset, it is the token it falls back to — so a theme
 * that sets no role paints what it painted before, and one that moves the
 * semantic token moves the role with it — or the value the surface was
 * drawn with before the role existed, or nothing at all where the controls
 * never shared one; a preset that sets it is read; the host beats the
 * preset. And each is put to work: read outside the token blocks, by a rule
 * of `styles.css` or a recipe of `src/ui`, so no role is a name nothing
 * paints with. The pixels are the browser's to measure
 * (`stories/view-engine/ThemeRoles.test.stories.tsx`) and the screenshot
 * baselines', which a role left unset keeps byte for byte.
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import postcss from 'postcss';
import { describe, expect, it } from 'vitest';
import {
  declared,
  type HostVariables,
  type Mode,
  PRESET_NAMES,
  presets,
  resolveTokens,
  tokenVariable,
} from './fixtures/themeTokens';
import { linesOf } from '../src/ui/theme/pairs';
import { CHART_TOKENS } from '../src/ui/charts/theme';
import {
  hostVariables,
  presetVariables,
  TOKEN_GROUPS,
  type TokenEntry,
  TOKENS,
} from '../src/ui/theme/tokens';

const ROOT = join(import.meta.dirname, '..');

const ROLES = (TOKENS as readonly TokenEntry[]).filter(
  entry => entry.tier === 'role',
);

const MODES: readonly Mode[] = ['light', 'dark'];

/**
 * The roles with no built-in value: unset, the rule that applies each
 * carries what every control was drawn with, because they were never one
 * value (a fill, an edge, a hover), or — the outline's width — the
 * registry's `outline-none`.
 */
const UNSET = new Set([
  'control',
  'control-edge',
  'control-thumb',
  'control-hover',
  'control-pressed',
  'focus-width',
  // No lower bound on a bar's width: as narrow as the plot makes it.
  'chart-bar-min-width',
]);

/**
 * What the rest are unset, where that is not another token (`fallback`):
 * the value each surface was drawn with before its role existed.
 */
const BUILT_IN: Readonly<Record<string, string>> = {
  'card-edge': 'color-mix(in oklab, var(--foreground) 10%, transparent)',
  'card-shadow': '0 0 #0000',
  scrim: 'oklch(0 0 0deg / 10%)',
  'table-header-divider': 'transparent',
  'row-hover': 'color-mix(in oklab, var(--muted) 50%, var(--background))',
  'focus-offset': '0px',
  'focus-style': 'solid',
  'focus-halo': 'color-mix(in oklab, var(--ring) 50%, transparent)',
  'control-thumb-shadow': '0 0 #0000',
  'control-height': '2rem',
  'control-height-sm': '1.75rem',
  'edge-width': '1px',
  'badge-edge': '30%',
  'badge-fill': '10%',
  'radius-card': 'calc(var(--radius) * 1.4)',
  'radius-badge': 'calc(var(--radius) * 2.6)',
  'radius-checkbox': '4px',
  'title-weight': '500',
  'strong-weight': '500',
  'chart-grid-width': '1px',
  'chart-text-size': 'calc(var(--_fve-text-ui) - 1px)',
  'chart-label-size': 'calc(var(--_fve-text-ui) - 2px)',
  'chart-line-width': '2px',
  'chart-area-opacity': '0.2',
  'chart-bar-radius': 'min(2px, calc(var(--radius) * 0.6))',
  'chart-bar-max-width': '80px',
  'chart-slice-border': '1px',
};

/** A value for each kind of role no built-in value is. */
const SAMPLE: Readonly<Record<TokenEntry['kind'], string>> = {
  color: 'oklch(0.5 0.1 123deg)',
  length: '3px',
  number: '650',
  shadow: '0 1px 2px oklch(0 0 0deg / 20%)',
  font: 'serif',
  keyword: 'dashed',
};

/** A host's own preset, `sample`, setting one role in both modes. */
function sampled(
  entry: TokenEntry,
  value: string,
): ReadonlyMap<string, ReadonlyMap<string, string>> {
  return new Map([
    [
      'sample',
      new Map(presetVariables(entry).map(variable => [variable, value])),
    ],
  ]);
}

const NO_HOST: HostVariables = {};

describe('the roles', () => {
  it('are one layer, with the surface groups folded into it', () => {
    // `canvas`, `card`, `controls` and `title` were optional groups (D43)
    // and are roles; what is left a group is a preset's parameter set.
    expect(Object.keys(TOKEN_GROUPS).sort()).toEqual(
      ['brand', 'chart', 'density', 'font', 'patterns', 'shadow'].sort(),
    );
    for (const entry of ROLES) {
      expect(entry.group, entry.name).toBeUndefined();
      expect(entry.area, entry.name).toBeDefined();
      expect(entry.own && entry.block && entry.preset, entry.name).toBe(true);
    }
  });

  it('each has an unset value this test knows', () => {
    const known = ROLES.filter(
      ({ name, fallback }) =>
        fallback !== undefined || UNSET.has(name) || name in BUILT_IN,
    );
    expect(known.map(({ name }) => name)).toEqual(
      ROLES.map(({ name }) => name),
    );
  });
});

describe.each(ROLES.map(entry => [entry.name, entry] as const))(
  'role %s',
  (name, entry) => {
    const variable = tokenVariable(name);
    const halves = MODES.slice(0, entry.modes);

    it.each(halves)('falls back when nothing sets it (%s)', mode => {
      const text = declared('neutral', mode, 'semantic', NO_HOST);
      if (UNSET.has(name)) {
        expect(text.has(variable)).toBe(false);
        return;
      }
      if (entry.fallback === undefined) {
        expect(text.get(variable)).toBe(BUILT_IN[name]);
        return;
      }
      const other = tokenVariable(entry.fallback);
      expect(text.get(variable)).toBe(`var(${other})`);
      // And a colour resolves to what its fallback resolves to, in every
      // preset that leaves the role alone: a preset that moves `muted`
      // moves the bands with it.
      if (entry.kind === 'color')
        for (const preset of PRESET_NAMES) {
          const own = presetVariables(entry);
          if (own.some(variable => presets().get(preset)?.has(variable)))
            continue;
          const tokens = resolveTokens(preset, mode, 'semantic', NO_HOST);
          expect(tokens.get(variable), preset).toEqual(tokens.get(other));
        }
    });

    it.each(halves)('takes a preset that sets it (%s)', mode => {
      const value = SAMPLE[entry.kind];
      const text = declared('sample', mode, 'semantic', NO_HOST, {
        extra: sampled(entry, value),
      });
      expect(text.get(variable)).toBe(value);
    });

    it.each(halves)('lets the host beat that preset (%s)', mode => {
      const host = { [hostVariables(entry)[mode === 'light' ? 0 : 1]]: 'X' };
      // A dark half the host leaves unset reads the preset's dark value.
      const text = declared('sample', mode, 'semantic', host, {
        extra: sampled(entry, SAMPLE[entry.kind]),
      });
      expect(text.get(variable)).toBe('X');
    });
  },
);

/** Every file of `src/ui`, as text, the registry's own left out. */
function uiSources(): string {
  const texts: string[] = [];
  const walk = (directory: string) => {
    for (const name of readdirSync(directory)) {
      const path = join(directory, name);
      if (path.includes(join('ui', 'theme'))) continue;
      if (statSync(path).isDirectory()) walk(path);
      else if (/\.tsx?$/.test(name)) texts.push(readFileSync(path, 'utf8'));
    }
  };
  walk(join(ROOT, 'src', 'ui'));
  return texts.join('\n');
}

describe('each role is painted with', () => {
  // Outside the token blocks: a rule of the stylesheet, a utility the
  // `@theme` block registers for our recipes, a recipe of `src/ui`, or —
  // a chart's — read back off the chart's element (`CHART_TOKENS`, which
  // `test/chartTheme.test.tsx` holds to what `readChartTheme` reads).
  const reads = new Set<string>();
  postcss
    .parse(readFileSync(join(ROOT, 'src', 'styles.css'), 'utf8'))
    .walkDecls(decl => {
      if (decl.prop.startsWith('--_fve-')) {
        const rule = decl.parent;
        const selector =
          rule?.type === 'rule' ? rule.selector.replace(/\s+/g, ' ') : '';
        // A token block declares the roles; it does not paint with them.
        if (/^\.fve-root, \.fve-tokens$|^\.dark \.fve-root/.test(selector))
          return;
      }
      for (const match of decl.value.matchAll(/--_fve-[\w-]+/g))
        reads.add(match[0]);
    });
  const recipes = uiSources();

  it.each(ROLES.map(({ name }) => name))('%s', name => {
    const variable = tokenVariable(name);
    const read =
      reads.has(variable) ||
      recipes.includes(variable) ||
      CHART_TOKENS.includes(variable);
    expect(read, `${variable} is declared and never read`).toBe(true);
  });
});

describe('the lines a role owes', () => {
  /** A length as pixels, at the root's 16px. */
  const pixels = (value: string) => {
    const match = /^([\d.]+)(px|rem)$/.exec(value);
    if (!match) throw new Error(`not a plain length: ${value}`);
    return Number(match[1]) * (match[2] === 'rem' ? 16 : 1);
  };

  // A target stays 24px or more (WCAG 2.5.8) at every step, whoever sets it.
  it.each(PRESET_NAMES)('keeps every control 24px or taller in %s', preset => {
    const text = declared(preset, 'light', 'semantic', NO_HOST);
    for (const name of ['control-height', 'control-height-sm'])
      expect(
        pixels(text.get(tokenVariable(name))!),
        name,
      ).toBeGreaterThanOrEqual(24);
  });

  // A preset that promises AAA text promises the 2px focus indicator of
  // WCAG 2.4.13 too, once it draws its own outline.
  it.each(PRESET_NAMES)('draws an AAA focus 2px wide or more in %s', preset => {
    const width = declared(preset, 'light', 'semantic', NO_HOST).get(
      tokenVariable('focus-width'),
    );
    if (width === undefined || linesOf(preset).text < 7) return;
    expect(pixels(width)).toBeGreaterThanOrEqual(2);
  });
});
