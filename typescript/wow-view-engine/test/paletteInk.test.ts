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
 * A number written inside a bar, a stacked segment or a heatmap cell takes
 * whichever of the page's two inks stands off the colour more (`inkOn`). So
 * every slot of the palette, in both themes, must have one ink that reads at
 * 4.5:1 — the first blue had neither (4.48 and 4.41) until it was taken a
 * step darker (the user's call, 2026-09-23). This reads the shipped values
 * out of `styles.css`, so a slot tuned later is held to the same line.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const css = readFileSync(
  join(import.meta.dirname, '..', 'src', 'styles.css'),
  'utf8',
);

type Oklch = [number, number, number];

/** The slots and the two inks of one theme, by the variable they fall back to. */
function theme(prefix: '--fve-' | '--fve-dark-') {
  const read = (name: string): Oklch => {
    const escaped = `${prefix}${name}`.replace(/-/g, '\\-');
    const match = new RegExp(
      `var\\(${escaped}, oklch\\(([\\d.]+) ([\\d.]+) ([\\d.]+)deg\\)\\)`,
    ).exec(css);
    if (!match) throw new Error(`no ${prefix}${name} in styles.css`);
    return [Number(match[1]), Number(match[2]), Number(match[3])];
  };
  return {
    slots: Array.from({ length: 8 }, (_, at) => read(`chart-${at + 1}`)),
    inks: [read('foreground'), read('background')],
  };
}

/** OKLCH to linear sRGB, clipped to the gamut. */
function linear([l, c, h]: Oklch): [number, number, number] {
  const hue = (h * Math.PI) / 180;
  const a = c * Math.cos(hue);
  const b = c * Math.sin(hue);
  const l3 = (l + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m3 = (l - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s3 = (l - 0.0894841775 * a - 1.291485548 * b) ** 3;
  const clip = (value: number) => Math.min(1, Math.max(0, value));
  return [
    clip(4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3),
    clip(-1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3),
    clip(-0.0041960863 * l3 - 0.7034186147 * m3 + 1.707614701 * s3),
  ];
}

function contrast(one: Oklch, other: Oklch): number {
  const luminance = (colour: Oklch) => {
    const [r, g, b] = linear(colour);
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const [hi, lo] = [luminance(one), luminance(other)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe('every palette slot has an ink that reads on it', () => {
  it.each([
    ['light', '--fve-'],
    ['dark', '--fve-dark-'],
  ] as const)('%s', (_, prefix) => {
    const { slots, inks } = theme(prefix);
    const best = slots.map(slot =>
      Math.max(...inks.map(ink => contrast(slot, ink))),
    );
    for (const ratio of best) expect(ratio).toBeGreaterThanOrEqual(4.5);
  });
});
