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
 * The links (ui/theme.md「链接：角色跟着面上解析出的 token」): a role drawn in another token of
 * the surface — the menu's highlight in the primary, the view on screen in
 * a selected row's tint — which a preset cannot write as `var(--primary)`,
 * because its block is worked out above the surface. It gives a share
 * instead, `--fvp-<role>-link`, and the surface resolves the link, so the
 * role follows what the surface resolved: a brand colour, a host's own
 * primary, the mode.
 *
 * The brand sweep (`test/brandInput.test.ts`) holds every pair a linked role
 * paints to its line across sRGB; this holds what a link means.
 */

import { converter, parse } from 'culori';
import { describe, expect, it } from 'vitest';
import {
  type HostVariables,
  type Mode,
  resolveTokens,
  type Rgba,
  tokenVariable,
} from './fixtures/themeTokens';

const toOklch = converter('oklch');

const VIOLET = '#7c3aed';
const VIOLET_HUE = toOklch(parse(VIOLET)!).h!;

const MODES: readonly Mode[] = ['light', 'dark'];

/** One token of a preset in one mode, as the surface resolves it. */
function token(
  preset: string,
  mode: Mode,
  name: string,
  host: HostVariables = {},
): Rgba {
  const color = resolveTokens(preset, mode, 'semantic', host).get(
    tokenVariable(name),
  );
  if (!color) throw new Error(`${name} did not resolve in ${preset}`);
  return color;
}

const hueOf = (color: Rgba) => toOklch({ mode: 'rgb', ...color }).h!;

/**
 * A hue within a few degrees of another: clipping a colour into sRGB turns
 * it a little (the brand's own derivation is held to the same, 2.7).
 */
function expectHue(color: Rgba, hue: number) {
  expect(Math.abs(hueOf(color) - hue)).toBeLessThan(6);
}

/** The same colour, to the float the mix through OKLab rounds it to. */
function expectSame(actual: Rgba, expected: Rgba) {
  for (const channel of ['r', 'g', 'b', 'alpha'] as const)
    expect(actual[channel], channel).toBeCloseTo(expected[channel], 9);
}

describe.each(MODES)('a link, in %s', mode => {
  it('draws porcelain’s highlight in the brand’s primary fill and its ink', () => {
    const host = { '--fve-brand': VIOLET };
    const fill = token('porcelain', mode, 'primary-fill', host);
    expectSame(token('porcelain', mode, 'highlight', host), fill);
    expectHue(fill, VIOLET_HUE);
    expectSame(
      token('porcelain', mode, 'highlight-foreground', host),
      token('porcelain', mode, 'primary-fill-foreground', host),
    );
    // The fill is the primary in light; in dark a step deeper than it,
    // held to porcelain's band, so white words read on it (D76).
    if (mode === 'light')
      expectSame(fill, token('porcelain', mode, 'primary', host));
    else {
      const { l } = toOklch({ mode: 'rgb', ...fill });
      expect(l).toBeGreaterThanOrEqual(0.455);
      expect(l).toBeLessThanOrEqual(0.525);
      expect(
        toOklch({ mode: 'rgb', ...token('porcelain', mode, 'primary', host) })
          .l,
      ).toBeGreaterThan(0.7);
    }
  });

  it('draws contrast’s highlight and selected row’s bar in the brand’s primary', () => {
    // contrast's fill is its primary in both modes (D76).
    const host = { '--fve-brand': VIOLET };
    const primary = token('contrast', mode, 'primary', host);
    expectHue(primary, VIOLET_HUE);
    expectSame(token('contrast', mode, 'highlight', host), primary);
    expectSame(
      token('contrast', mode, 'highlight-foreground', host),
      token('contrast', mode, 'primary-foreground', host),
    );
    expectSame(token('contrast', mode, 'row-selected-mark', host), primary);
  });

  it('draws azure’s open view in the brand’s tint and primary', () => {
    const host = { '--fve-brand': VIOLET };
    const tint = token('azure', mode, 'row-selected', host);
    expectSame(token('azure', mode, 'nav-current', host), tint);
    expectSame(token('azure', mode, 'item-selected', host), tint);
    expectHue(tint, VIOLET_HUE);
    const primary = token('azure', mode, 'primary', host);
    expectSame(token('azure', mode, 'nav-current-foreground', host), primary);
    expectSame(token('azure', mode, 'outline-hover-edge', host), primary);
  });

  it('is the preset’s own colour with no brand', () => {
    // No brand colour: the link is the preset's own primary and tint — the
    // literal each preset wrote before it linked them.
    expectSame(
      token('porcelain', mode, 'highlight'),
      token('porcelain', mode, mode === 'light' ? 'primary' : 'primary-fill'),
    );
    expectSame(
      token('contrast', mode, 'highlight'),
      token('contrast', mode, 'primary'),
    );
    expectSame(
      token('azure', mode, 'nav-current'),
      token('azure', mode, 'row-selected'),
    );
  });

  it('follows a primary the host writes itself', () => {
    // Where the preset gives the fill a deeper step of its own (porcelain's
    // dark), a host that writes its own primary writes that step too.
    const host = {
      '--fve-primary': 'oklch(0.45 0.2 145deg)',
      '--fve-dark-primary': 'oklch(0.8 0.15 145deg)',
      '--fve-dark-primary-fill': 'oklch(0.48 0.15 145deg)',
    };
    expectSame(
      token('porcelain', mode, 'highlight', host),
      token('porcelain', mode, 'primary-fill', host),
    );
    expectHue(token('porcelain', mode, 'highlight', host), 145);
    expectSame(
      token('contrast', mode, 'highlight', host),
      token('contrast', mode, 'primary-fill', host),
    );
    expectHue(token('contrast', mode, 'highlight', host), 145);
  });

  it('gives way to the host’s own colour for the role', () => {
    const host = {
      '--fve-highlight': 'oklch(0.5 0.1 30deg)',
      '--fve-dark-highlight': 'oklch(0.5 0.1 30deg)',
    };
    expectHue(token('porcelain', mode, 'highlight', host), 30);
  });

  it('is the host’s to set on any preset, at any share', () => {
    // neutral links nothing; a host links its highlight to the primary at
    // half: the primary laid at 50% over what is under it.
    const host = { '--fve-highlight-link': '50%' };
    const primary = token('neutral', mode, 'primary', host);
    const highlight = token('neutral', mode, 'highlight', host);
    expect(highlight.alpha).toBeCloseTo(primary.alpha * 0.5, 6);
    expect(highlight.r).toBeCloseTo(primary.r, 6);
  });

  it('leaves a role no link names as the preset wrote it', () => {
    // azure highlights in grey and marks what is chosen in blue: its
    // highlight is not linked, so a brand leaves it grey.
    const host = { '--fve-brand': VIOLET };
    expectSame(
      token('azure', mode, 'highlight', host),
      token('azure', mode, 'highlight'),
    );
  });
});
