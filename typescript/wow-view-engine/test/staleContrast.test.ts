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
 * A result whose rows answer an older question is kept and faded (`STALE`),
 * and its rows are still read while the answer is on its way: body text
 * faded that much holds 4.5:1 (WCAG 1.4.3) on every ground a result stands
 * on, in every built-in preset and both modes.
 *
 * At 60% it did not: 4.23:1 on `muted` in neutral's light mode. The moving
 * line under the rows says a fetch is running (D94), so the fade says only
 * "these are the old rows" and is as light as that takes. Secondary text
 * (`muted-foreground`) faded is under the line at any strength that still
 * reads as a fade, for the seconds an answer takes; it is not held here.
 */

import { describe, expect, it } from 'vitest';
import { STALE } from '../src/ui/kit/stale';
import { PRESET_NAMES, resolveTokens } from './fixtures/themeTokens';

interface Rgb {
  r: number;
  g: number;
  b: number;
}

const linear = (channel: number) =>
  channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
const luminance = ({ r, g, b }: Rgb) =>
  0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
const contrast = (a: Rgb, b: Rgb) => {
  const [high, low] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (high + 0.05) / (low + 0.05);
};
/** What `opacity` paints: the ink mixed into its ground, in sRGB. */
const faded = (ink: Rgb, ground: Rgb, strength: number): Rgb => ({
  r: ink.r * strength + ground.r * (1 - strength),
  g: ink.g * strength + ground.g * (1 - strength),
  b: ink.b * strength + ground.b * (1 - strength),
});

const strength = Number(/opacity-(\d+)/.exec(STALE)?.[1]) / 100;

describe('a faded result', () => {
  it('is faded, and not away', () => {
    expect(strength).toBeGreaterThan(0);
    expect(strength).toBeLessThan(1);
  });

  it.each(
    PRESET_NAMES.flatMap(preset =>
      (['light', 'dark'] as const).map(mode => [preset, mode] as const),
    ),
  )('%s, %s: body text still holds 4.5:1', (preset, mode) => {
    const tokens = resolveTokens(preset, mode, 'semantic');
    for (const [ink, ground] of [
      ['foreground', 'background'],
      ['foreground', 'muted'],
      ['card-foreground', 'card'],
      ['card-foreground', 'muted'],
    ]) {
      const on = tokens.get(`--${ground}`) as Rgb;
      const text = faded(tokens.get(`--${ink}`) as Rgb, on, strength);
      expect(contrast(text, on), `${ink} on ${ground}`).toBeGreaterThanOrEqual(
        4.5,
      );
    }
  });
});
