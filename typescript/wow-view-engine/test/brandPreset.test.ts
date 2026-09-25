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
 * `brand` derives a theme from one colour the host gives (themes.md 2.3,
 * 2.7), so what it promises cannot be measured value by value: it is
 * measured colour by colour, across the whole sRGB range a brand book can
 * name — every hue, from grey to the gamut's edge, from near black to near
 * white — and every pair the surface paints has to clear its line for all
 * of them, in both modes.
 *
 * The derived primary is an `oklch()` with its lightness clamped and its
 * chroma the brand's, which can fall outside sRGB. How a browser brings it
 * back differs — clipping each channel, or carrying chroma in at the same
 * lightness — so the sweep holds both.
 */

import { clampChroma, converter, formatHex } from 'culori';
import { describe, expect, it } from 'vitest';
import { measure } from './fixtures/presetPairs';
import { type Gamut, type Mode, resolveTokens } from './fixtures/themeTokens';

/** Brand colours across sRGB, each written as a host would: in hex. */
const toOklch = converter('oklch');

const BRANDS = [
  ...new Set(
    Array.from({ length: 24 }, (_, step) => step * 15).flatMap(h =>
      [
        0.08, 0.15, 0.25, 0.35, 0.42, 0.48, 0.52, 0.58, 0.65, 0.72, 0.78, 0.85,
        0.92, 0.97,
      ].flatMap(l =>
        [0, 0.03, 0.06, 0.1, 0.14, 0.18, 0.22, 0.27, 0.32, 0.37].map(c =>
          formatHex(clampChroma({ mode: 'oklch', l, c, h }, 'oklch')),
        ),
      ),
    ),
  ),
];

const CASES = (['light', 'dark'] as const).flatMap(mode =>
  (['clip', 'chroma'] as const).map(gamut => [mode, gamut] as const),
);

describe('any brand colour holds every line', () => {
  it('sweeps a wide range of colours', () => {
    expect(BRANDS.length).toBeGreaterThan(1000);
  });

  it.each(CASES)('%s, gamut mapped by %s', (mode: Mode, gamut: Gamut) => {
    const failing = BRANDS.flatMap(brand =>
      measure('brand', mode, 'semantic', { '--fve-brand': brand }, gamut)
        .filter(({ ratio, line }) => ratio < line)
        .map(({ name, ratio }) => `${brand} ${name} ${ratio.toFixed(2)}`),
    );
    expect(failing.slice(0, 20)).toEqual([]);
  });
});

describe('the derived primary', () => {
  // Lightness and hue, read back after the colour is brought into sRGB
  // along chroma — which keeps both.
  it.each([
    // In the clamp: the brand as it is.
    ['light', '#6d28d9', 0.4907, 292.6],
    // Too light for a white ink: taken down to 0.50.
    ['light', '#facc15', 0.5, 91.9],
    // Too dark for the dark theme: lifted to 0.68.
    ['dark', '#1e1b4b', 0.68, 281.3],
  ] as const)('%s, from %s', (mode, brand, lightness, hue) => {
    const primary = resolveTokens(
      'brand',
      mode,
      'semantic',
      { '--fve-brand': brand },
      'chroma',
    ).get('--primary')!;
    const { l, h } = toOklch({ mode: 'rgb', ...primary });
    expect(l).toBeCloseTo(lightness, 2);
    expect(h).toBeCloseTo(hue, 0);
  });
});

describe('the dark half', () => {
  it('takes its own colour from --fve-dark-brand when there is one', () => {
    const own = resolveTokens('brand', 'dark', 'semantic', {
      '--fve-brand': '#0a7d2c',
      '--fve-dark-brand': '#7c3aed',
    }).get('--primary');
    const shared = resolveTokens('brand', 'dark', 'semantic', {
      '--fve-brand': '#7c3aed',
    }).get('--primary');
    expect(own).toEqual(shared);
  });
});

describe('no brand colour', () => {
  it.each(['light', 'dark'] as const)('is neutral, %s', mode => {
    expect(resolveTokens('brand', mode, 'semantic', {})).toEqual(
      resolveTokens('neutral', mode, 'semantic', {}),
    );
  });
});
