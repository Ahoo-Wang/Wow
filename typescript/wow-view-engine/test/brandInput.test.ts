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
 * The brand colour is an input beside any preset (theme-architecture.md 2,
 * S4): a host gives one `--fve-brand`, and `styles.css` derives the primary,
 * three tints and — where the preset bounds it — the focus ring, held to the
 * bounds each preset gives on its own grounds. What that promises cannot be
 * measured value by value: it is measured colour by colour, across the
 * whole sRGB range a brand book can name — every hue, from grey to the
 * gamut's edge, from near black to near white — and every pair the surface
 * paints has to clear the line it owes in that preset (`contrast`'s are 7 /
 * 4.5), for all of them, on every preset, in both modes.
 *
 * The derived colours are `oklch()` with a clamped lightness and the
 * brand's chroma, which can fall outside sRGB. How a browser brings one
 * back differs — clipping each channel, or carrying chroma in at the same
 * lightness — so the sweep holds both.
 */

import { clampChroma, converter, formatHex, parse } from 'culori';
import { describe, expect, it } from 'vitest';
import { measure } from './fixtures/presetPairs';
import { isPending } from '../src/ui/theme/pairs';
import { type TokenEntry, TOKENS } from '../src/ui/theme/tokens';
import {
  CONVENTIONS,
  declared,
  type HostVariables,
  type Mode,
  type Placement,
  PRESET_NAMES,
  resolveTokens,
  tokenVariable,
} from './fixtures/themeTokens';

const toOklch = converter('oklch');

/** Brand colours across sRGB, each written as a host would: in hex. */
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

/** The colour the examples below derive from. */
const VIOLET = '#7c3aed';

const MODES = ['light', 'dark'] as const;

const brandOf = (brand: string, more: HostVariables = {}): HostVariables => ({
  '--fve-brand': brand,
  ...more,
});

/** The tokens the registry says a brand colour derives. */
const DERIVED = (TOKENS as readonly TokenEntry[])
  .filter(entry => entry.brand)
  .map(entry => tokenVariable(entry.name));

/**
 * The tokens a host's variables change the value of, as the cascade hands
 * it on — not the ones that only read a changed one (`highlight` is
 * `var(--accent)` either way).
 */
function moved(
  preset: string,
  mode: Mode,
  host: HostVariables,
  placement: Placement = {},
): string[] {
  const before = declared(preset, mode, 'semantic', {});
  const after = declared(preset, mode, 'semantic', host, placement);
  return [...after.keys()]
    .filter(variable => before.get(variable) !== after.get(variable))
    .sort();
}

/** A preset that bounds its focus ring, so the ring follows the brand. */
const RING = new Set(['porcelain', 'contrast']);

/** `data-fve-brand-chart` on the surface or an ancestor. */
const CHARTED: Placement = { brandChart: true };

/** The tokens a brand colour derives on one preset, the chart's slot off. */
const derivedOn = (preset: string) =>
  DERIVED.filter(
    variable =>
      variable !== '--chart-1' && (variable !== '--ring' || RING.has(preset)),
  );

describe('any brand colour holds every line of the preset it is worn on', () => {
  it('sweeps a wide range of colours', () => {
    expect(BRANDS.length).toBeGreaterThan(1000);
  });

  it.each(
    PRESET_NAMES.flatMap(preset =>
      MODES.flatMap(mode =>
        (['clip', 'chroma'] as const).map(
          gamut => [preset, mode, gamut] as const,
        ),
      ),
    ),
  )('%s, %s, gamut mapped by %s', (preset, mode, gamut) => {
    const failing = BRANDS.flatMap(brand =>
      measure(preset, mode, 'semantic', brandOf(brand), gamut)
        .filter(
          ({ name, ratio, line }) =>
            ratio < line && !isPending(preset, mode, name),
        )
        .map(({ name, ratio }) => `${brand} ${name} ${ratio.toFixed(2)}`),
    );
    expect(failing.slice(0, 20)).toEqual([]);
  });
});

describe('no brand colour', () => {
  // Everything else a host may give the brand — a bound, the chart's
  // attribute — derives nothing without the colour: each preset is as it
  // ships (`test/themeSnapshot.test.ts` holds that to the values saved
  // before S4).
  it.each(
    PRESET_NAMES.flatMap(preset =>
      MODES.flatMap(mode => CONVENTIONS.map(c => [preset, mode, c] as const)),
    ),
  )('is the preset, %s, %s, %s', (preset, mode, convention) => {
    expect(
      resolveTokens(
        preset,
        mode,
        convention,
        {
          '--fve-brand-l-min': '0.2',
          '--fve-brand-ring-l-min': '0.3',
          '--fve-brand-ring-l-max': '0.5',
        },
        'clip',
        CHARTED,
      ),
    ).toEqual(resolveTokens(preset, mode, convention, {}));
  });
});

describe('what a brand colour derives', () => {
  // A preset that bounds its focus ring lets it follow the brand; one whose
  // ring is a grey tuned to 3:1 on every ground does not.
  it.each(PRESET_NAMES.flatMap(preset => MODES.map(m => [preset, m] as const)))(
    'is what the registry says, on %s, %s',
    (preset, mode) => {
      const expected = derivedOn(preset);
      expect(moved(preset, mode, brandOf(VIOLET))).toEqual(
        [...expected].sort(),
      );
      // The chart's first slot only under `data-fve-brand-chart`: absent is
      // off, present is on — no value of a variable can switch it on by
      // mistake.
      expect(
        moved(preset, mode, brandOf(VIOLET, { '--fve-brand-chart': '0' })),
      ).toEqual([...expected].sort());
      expect(moved(preset, mode, brandOf(VIOLET), CHARTED)).toEqual(
        [...expected, '--chart-1'].sort(),
      );
    },
  );

  it('carries the brand’s hue into every derived colour', () => {
    const hue = toOklch(parse(VIOLET)!).h!;
    for (const preset of PRESET_NAMES)
      for (const mode of MODES) {
        const tokens = resolveTokens(
          preset,
          mode,
          'semantic',
          brandOf(VIOLET),
          'chroma',
          CHARTED,
        );
        for (const variable of [...derivedOn(preset), '--chart-1']) {
          const color = tokens.get(variable)!;
          expect(
            toOklch({ mode: 'rgb', ...color }).h,
            `${preset} ${mode} ${variable}`,
          ).toBeCloseTo(hue, 0);
        }
      }
  });
});

describe('the derived primary', () => {
  // Lightness and hue, read back after the colour is brought into sRGB
  // along chroma — which keeps both.
  it.each([
    // In the band: the brand as it is.
    ['neutral', 'light', '#6d28d9', 0.4907, 292.6],
    // Too light for a white ink: taken down to the band's top.
    ['neutral', 'light', '#facc15', 0.5, 91.9],
    ['porcelain', 'light', '#facc15', 0.48, 91.9],
    ['contrast', 'light', '#6d28d9', 0.36, 292.6],
    // Too dark for the dark theme: lifted to the band's floor.
    ['neutral', 'dark', '#1e1b4b', 0.68, 281.3],
    ['porcelain', 'dark', '#1e1b4b', 0.77, 281.3],
    ['contrast', 'dark', '#1e1b4b', 0.8, 281.3],
  ] as const)('%s, %s, from %s', (preset, mode, brand, lightness, hue) => {
    const primary = resolveTokens(
      preset,
      mode,
      'semantic',
      brandOf(brand),
      'chroma',
    ).get('--primary')!;
    const { l, h } = toOklch({ mode: 'rgb', ...primary });
    expect(l).toBeCloseTo(lightness, 2);
    expect(h).toBeCloseTo(hue, 0);
  });

  it('takes a bound the host gives over the preset’s', () => {
    const primary = resolveTokens(
      'neutral',
      'light',
      'semantic',
      brandOf('#facc15', { '--fve-brand-l-max': '0.45' }),
      'chroma',
    ).get('--primary')!;
    expect(toOklch({ mode: 'rgb', ...primary }).l).toBeCloseTo(0.45, 2);
  });

  it('gives way to a primary the host writes itself', () => {
    for (const preset of PRESET_NAMES) {
      const own = resolveTokens(preset, 'light', 'semantic', {
        '--fve-primary': 'rgb(1, 2, 3)',
      }).get('--primary');
      expect(
        resolveTokens(
          preset,
          'light',
          'semantic',
          brandOf(VIOLET, { '--fve-primary': 'rgb(1, 2, 3)' }),
        ).get('--primary'),
        preset,
      ).toEqual(own);
    }
  });
});

describe('the dark half', () => {
  it('takes its own colour from --fve-dark-brand when there is one', () => {
    for (const preset of PRESET_NAMES) {
      const own = resolveTokens(preset, 'dark', 'semantic', {
        '--fve-brand': '#0a7d2c',
        '--fve-dark-brand': VIOLET,
      });
      const shared = resolveTokens(preset, 'dark', 'semantic', brandOf(VIOLET));
      for (const variable of DERIVED)
        expect(own.get(variable), `${preset} ${variable}`).toEqual(
          shared.get(variable),
        );
    }
  });
});

describe('the first chart slot under the brand', () => {
  // The attribute keeps the lightness and chroma the preset tuned its first
  // slot to, so the numbers a preset gives for it (the stylesheet's, for
  // `neutral`) are that slot's own: a palette retuned without them would
  // move the ink inside a bar.
  it.each(PRESET_NAMES.flatMap(preset => MODES.map(m => [preset, m] as const)))(
    'keeps %s’s own first slot’s lightness and chroma, %s',
    (preset, mode) => {
      const text = declared(preset, mode, 'semantic', {});
      const own = toOklch(parse(text.get('--chart-1')!)!);
      const derived = declared(
        preset,
        mode,
        'semantic',
        brandOf(VIOLET),
        CHARTED,
      ).get('--chart-1')!;
      const [, l, c] = /^oklch\(from \S+ ([\d.]+) ([\d.]+) /.exec(derived)!;
      expect([Number(l), Number(c)]).toEqual([own.l, own.c]);
    },
  );
});
