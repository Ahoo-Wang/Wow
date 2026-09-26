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
 * The contrast this package promises holds in every built-in preset, in both
 * modes (phase 5, 5C; D30 Q42, Q44): text at 4.5:1 (WCAG 1.4.3), the edge of
 * a control and the focus indicator at 3:1 (1.4.11).
 *
 * Every pair is a pair the surface actually paints
 * (`fixtures/presetPairs.ts`), measured on the values the shipped
 * stylesheets resolve to, so a preset tuned later is held to the same
 * lines. The browser stories (`ToneBadgeInk*`, `ControlBorders*`,
 * `FocusIndicators*`, `FocusMarks*`) measure the cascaded neutral colours
 * on the real screen; this is the same arithmetic over every preset.
 */

import { converter, parse } from 'culori';
import { describe, expect, it } from 'vitest';
import { measure } from './fixtures/presetPairs';
import { contrastPairs, isPending, PENDING } from '../src/ui/theme/pairs';
import {
  presetVariables,
  TOKEN_GROUPS,
  type TokenEntry,
  TOKENS,
} from '../src/ui/theme/tokens';
import {
  CONVENTIONS,
  declared,
  PRESET_NAMES,
  presets,
  resolveTokens,
} from './fixtures/themeTokens';

const toOklch = converter('oklch');

const ENTRIES: readonly TokenEntry[] = TOKENS;

/**
 * The arithmetic here and the browser's contrast matrix in Storybook both
 * expand the registry's one list (`src/ui/theme/pairs.ts`), so they measure
 * the same pairs: before, each kept its own, and they had parted.
 */
describe('the pairs measured', () => {
  it.each(['light', 'dark'] as const)(
    'are the registry’s, each once, in %s',
    mode => {
      const names = contrastPairs(mode).map(({ name }) => name);
      expect(new Set(names).size).toBe(names.length);
      // `porcelain` fills its controls, so every pair a filled control
      // paints applies to it — all but the hover fill it leaves unset.
      expect(measure('porcelain', mode).map(({ name }) => name)).toEqual(
        contrastPairs(mode)
          .filter(({ requires }) => requires !== 'control-hover')
          .map(({ name }) => name),
      );
      // `azure` gives the hover fill, so what is painted on it is measured.
      expect(measure('azure', mode).map(({ name }) => name)).toContain(
        'outline-hover-foreground text on hovered outline button on its hover fill',
      );
      // Where a theme leaves the control fill unset, those pairs are not
      // painted, and the arithmetic has nothing to measure.
      expect(measure('neutral', mode).map(({ name }) => name)).toEqual(
        contrastPairs(mode)
          .filter(({ requires }) => !requires)
          .map(({ name }) => name),
      );
    },
  );

  it('name only registered tokens', () => {
    const registered = new Set(ENTRIES.map(({ name }) => name));
    const layers = (['light', 'dark'] as const).flatMap(mode =>
      contrastPairs(mode).flatMap(pair => [pair.ink, ...pair.ground]),
    );
    expect(
      [...new Set(layers.map(({ token }) => token))].filter(
        token => !registered.has(token),
      ),
    ).toEqual([]);
  });
});

describe('the built-in presets', () => {
  it('are the catalogue of D35, in its order', () => {
    expect(PRESET_NAMES).toEqual(['neutral', 'azure', 'porcelain', 'contrast']);
  });

  it('leave neutral to the stylesheet: it writes nothing', () => {
    // The reset empties the preset layer where a preset is named, so neutral
    // has nothing to put back (theme-architecture.md 3, S2).
    expect(presets().get('neutral')!.size).toBe(0);
  });

  it('give the chart colours and the shadows whole or not at all (D35 Q62)', () => {
    // The groups are the registry's; only a group that is one design has to
    // be given whole.
    for (const [group, { whole: required }] of Object.entries(TOKEN_GROUPS)) {
      if (!required) continue;
      const whole = ENTRIES.filter(entry => entry.group === group).flatMap(
        presetVariables,
      );
      const members = new Set(whole);
      expect(whole.length, group).toBeGreaterThan(0);
      for (const [name, assigned] of presets()) {
        const given = [...assigned.keys()]
          .filter(variable => members.has(variable))
          .sort();
        expect([[], [...whole].sort()], `${name} ${group}`).toContainEqual(
          given,
        );
      }
    }
  });

  it("never set a rise or a fall: the convention is the host's", () => {
    for (const assigned of presets().values())
      for (const variable of assigned.keys())
        expect(variable).not.toMatch(/^--fv[ep]-(dark-)?(rise|fall)$/);
  });
});

describe.each(PRESET_NAMES)('preset %s', preset => {
  it.each(
    (['light', 'dark'] as const).flatMap(mode =>
      CONVENTIONS.map(convention => [mode, convention] as const),
    ),
  )('clears every line in %s, %s', (mode, convention) => {
    const failing = measure(preset, mode, convention).filter(
      ({ name, ratio, line }) => ratio < line && !isPending(preset, mode, name),
    );
    expect(failing).toEqual([]);
  });
});

/**
 * A pending pair is excused only while it is short: once the batch that
 * owes it lands, the entry has to go (`PENDING` in the registry).
 */
describe('the pending pairs', () => {
  // One test over the list rather than one per entry: the list is empty
  // whenever no batch owes a pair, and an empty table is no test at all.
  it('are each still short', () => {
    for (const { preset, mode, pair } of PENDING) {
      const found = measure(preset, mode).find(({ name }) => name === pair);
      expect(found, pair).toBeDefined();
      expect(found!.ratio, `${preset} ${mode} ${pair}`).toBeLessThan(
        found!.line,
      );
    }
  });
});

/**
 * `red-up` crosses the pair and nothing else: a rise is the destructive
 * colour and a fall the success one, and the default and `green-up` keep
 * them the other way round (themes.md 2.6).
 */
describe('the change convention decides a rise and a fall', () => {
  it.each(
    PRESET_NAMES.flatMap(preset =>
      (['light', 'dark'] as const).map(mode => [preset, mode] as const),
    ),
  )('%s, %s', (preset, mode) => {
    for (const convention of CONVENTIONS) {
      const tokens = resolveTokens(preset, mode, convention);
      const [up, down] =
        convention === 'red-up'
          ? ['--destructive', '--success']
          : ['--success', '--destructive'];
      expect(tokens.get('--_fve-rise'), convention).toEqual(tokens.get(up));
      expect(tokens.get('--_fve-fall'), convention).toEqual(tokens.get(down));
    }
  });
});

/**
 * Q44: the dark status colours are quiet. The Tailwind 400 steps they were
 * carried a chroma of 0.19–0.21, which on a dark ground made a soft badge
 * glare; they sit in 0.14–0.16 now, still clearing 4.5:1 above.
 */
describe('the dark status colours are desaturated (Q44)', () => {
  it.each(PRESET_NAMES)('%s', preset => {
    const tokens = declared(preset, 'dark');
    for (const status of ['--destructive', '--success', '--warning']) {
      const { c } = toOklch(parse(tokens.get(status)!)!);
      expect(c, status).toBeGreaterThanOrEqual(0.14);
      expect(c, status).toBeLessThanOrEqual(0.16);
    }
  });
});
