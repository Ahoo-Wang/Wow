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
 * The chart palette's gates (themes.md 5.2, D35 Q62), for the default eight
 * and every palette a preset brings, in both modes.
 *
 * Series are told apart by their colours first, so every two slots that can
 * sit side by side — neighbours, and the eighth beside the first, as a pie's
 * slices close the circle — have to stay apart in OKLab (×100) for a full
 * colour eye and under simulated colour-vision deficiency (culori's filters
 * at full strength): 15 to a full-colour eye, 8 under protanopia and
 * deuteranopia, 6 under tritanopia. The tritan line is 6 rather than 8
 * because the default dark palette, shipped and clearing the other two,
 * measures 6.1 there (themes.md 5.2).
 *
 * A mark also has to stand off the card it is drawn on, at 3:1 (WCAG
 * 1.4.11). The dark slots all do; a light palette may name exceptions — the
 * default one's aqua, yellow and magenta — because a chart that cannot be
 * told apart by colour still has its reading table and its patterns (Q57).
 * The exceptions are listed here slot by slot, so a new one is a change made
 * on purpose. A number written inside a mark is `paletteInk.test.ts`'s.
 *
 * A preset that cannot pass these does not bring a palette of its own: it
 * leaves the chart group out and draws with the default eight.
 */

import {
  type Color,
  differenceEuclidean,
  filterDeficiencyDeuter,
  filterDeficiencyProt,
  filterDeficiencyTrit,
} from 'culori';
import { describe, expect, it } from 'vitest';
import {
  contrast,
  type Mode,
  PRESET_NAMES,
  resolveTokens,
  type Rgba,
} from './fixtures/themeTokens';

const SLOTS = 8;

const distance = differenceEuclidean('oklab');

/** A full-colour eye, and the three deficiencies at full strength. */
const VISION = {
  normal: { see: (color: Color) => color, line: 15 },
  protanopia: { see: filterDeficiencyProt(1), line: 8 },
  deuteranopia: { see: filterDeficiencyDeuter(1), line: 8 },
  tritanopia: { see: filterDeficiencyTrit(1), line: 6 },
} as const;

/**
 * The light slots that sit under 3:1 on the card, by preset: in the default
 * eight, aqua (3), yellow (4) and magenta (5).
 */
const LIGHT_EXCEPTIONS: Record<string, readonly number[]> = {
  neutral: [3, 4, 5],
  slate: [3, 4, 5],
  // Yellow, in each palette of its own that keeps one.
  azure: [4],
  porcelain: [5],
  graphite: [3, 4, 6],
};

const toColor = (rgba: Rgba): Color => ({ mode: 'rgb', ...rgba });

function palette(preset: string, mode: Mode): Rgba[] {
  const tokens = resolveTokens(preset, mode);
  return Array.from({ length: SLOTS }, (_, index) => {
    const color = tokens.get(`--chart-${index + 1}`);
    if (!color) throw new Error(`--chart-${index + 1} did not resolve`);
    return color;
  });
}

/** The closest two slots that can sit side by side, the ring closed. */
function closestNeighbours(colors: readonly Rgba[], see: (c: Color) => Color) {
  let worst = { apart: Infinity, slots: [0, 0] };
  colors.forEach((color, index) => {
    const next = (index + 1) % colors.length;
    const apart =
      distance(see(toColor(color)), see(toColor(colors[next]))) * 100;
    if (apart < worst.apart) worst = { apart, slots: [index + 1, next + 1] };
  });
  return worst;
}

const CASES = PRESET_NAMES.flatMap(preset =>
  (['light', 'dark'] as const).map(mode => [preset, mode] as const),
);

describe('neighbouring slots stay apart, to every eye', () => {
  it.each(CASES)('%s, %s', (preset, mode) => {
    const colors = palette(preset, mode);
    for (const [vision, { see, line }] of Object.entries(VISION)) {
      const { apart, slots } = closestNeighbours(colors, see);
      expect(apart, `${vision}: slots ${slots.join(' and ')}`).toBeGreaterThan(
        line,
      );
    }
  });
});

describe('every slot stands off the card, bar the listed light ones', () => {
  it.each(CASES)('%s, %s', (preset, mode) => {
    const card = resolveTokens(preset, mode).get('--card')!;
    const under = palette(preset, mode)
      .map((color, index) => ({
        slot: index + 1,
        ratio: contrast(color, card),
      }))
      .filter(({ ratio }) => ratio < 3)
      .map(({ slot }) => slot);
    expect(under).toEqual(
      mode === 'light' ? (LIGHT_EXCEPTIONS[preset] ?? []) : [],
    );
  });

  it('lists exceptions only for presets there are', () => {
    expect(Object.keys(LIGHT_EXCEPTIONS)).toEqual(PRESET_NAMES);
  });
});
