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
 * Every pair is a pair the surface actually paints, written as tokens and the
 * opacity the call site gives them — the selected row is `bg-muted`, a toned
 * badge writes its token on a 10% wash of it (`ToneBadge`), the dark theme
 * gives a control a `bg-input/30` fill — and measured on the values the two
 * shipped stylesheets resolve to, so a preset tuned later is held to the
 * same lines. The browser stories (`ToneBadgeInk*`, `ControlBorders*`,
 * `FocusIndicators*`, `FocusMarks*`) measure the cascaded neutral colours
 * on the real screen; this is the same arithmetic over every preset.
 */

import { converter, parse } from 'culori';
import { describe, expect, it } from 'vitest';
import {
  at,
  type Convention,
  CONVENTIONS,
  declared,
  contrast,
  type Mode,
  over,
  PRESET_NAMES,
  presets,
  resolveTokens,
  type Rgba,
} from './fixtures/themeTokens';

const toOklch = converter('oklch');

const TEXT = 4.5;
const NON_TEXT = 3;

/** A ground the surface paints, from the resolved tokens. */
type Ground = (tokens: (name: string) => Rgba) => Rgba;

const ground =
  (name: string): Ground =>
  token =>
    token(`--${name}`);

/** The grounds text and controls sit on, by what the surface calls them. */
const PAGE: Record<string, Ground> = {
  page: ground('background'),
  card: ground('card'),
  popover: ground('popover'),
  // The header and summary bands, a selected row, a pressed group.
  band: ground('muted'),
  // A hovered row: the muted step mixed halfway into the page.
  'hovered row': token => over(token('--row-hover'), token('--background')),
};

/**
 * Where a control's edge and the focus indicator land: every ground above,
 * plus — in the dark, where outline controls carry `bg-input/30` — that wash
 * over the card and over the page.
 */
const CONTROL_GROUNDS = (mode: Mode): Record<string, Ground> =>
  mode === 'light'
    ? PAGE
    : {
        ...PAGE,
        'input wash on card': token =>
          over(at(token('--input'), 0.3), token('--card')),
        'input wash on page': token =>
          over(at(token('--input'), 0.3), token('--background')),
      };

interface Pair {
  name: string;
  ink: (token: (name: string) => Rgba) => Rgba;
  on: Ground;
  line: number;
}

const text = (ink: string, on: Record<string, Ground>): Pair[] =>
  Object.entries(on).map(([where, ground]) => ({
    name: `${ink} text on ${where}`,
    ink: token => token(`--${ink}`),
    on: ground,
    line: TEXT,
  }));

const pairs = (mode: Mode): Pair[] => [
  // Body text, everywhere it is written.
  ...text('foreground', PAGE),
  ...text('card-foreground', { card: ground('card') }),
  ...text('popover-foreground', { popover: ground('popover') }),
  // The quiet grey is toned against the page (on the band it is 4.34:1 in
  // neutral, which is why the band writes in `quiet-foreground`).
  ...text('muted-foreground', {
    page: ground('background'),
    card: ground('card'),
    popover: ground('popover'),
  }),
  ...text('quiet-foreground', { band: ground('muted') }),
  // Filled things and their own ink.
  ...text('primary-foreground', { primary: ground('primary') }),
  ...text('secondary-foreground', { secondary: ground('secondary') }),
  ...text('accent-foreground', { accent: ground('accent') }),
  ...text('destructive-foreground', { destructive: ground('destructive') }),
  // A link in a cell and the default-view star are written in `primary`.
  ...text('primary', PAGE),
  // The navigation column.
  ...text('sidebar-foreground', { sidebar: ground('sidebar') }),
  ...text('sidebar-accent-foreground', {
    'sidebar accent': ground('sidebar-accent'),
  }),
  // A status as the words of a callout, on the surfaces it is drawn on.
  ...['destructive', 'success', 'warning'].flatMap(status =>
    text(status, {
      page: ground('background'),
      card: ground('card'),
      popover: ground('popover'),
    }),
  ),
  // A toned badge: the status written on a 10% wash of itself, over each
  // ground a row can be — the band (a selected row) is the tightest.
  ...['destructive', 'success', 'warning'].flatMap(status =>
    Object.entries(PAGE).map(([where, under]) => ({
      name: `${status} badge on ${where}`,
      ink: (token: (name: string) => Rgba) => token(`--${status}`),
      on: (token: (name: string) => Rgba) =>
        over(at(token(`--${status}`), 0.1), under(token)),
      line: TEXT,
    })),
  ),
  // A change by its direction (themes.md 2.6): a rise and a fall as the
  // words of a metric card's badge, on a 10% wash of themselves over each
  // ground a card or a row can be, and as a waterfall's bars — marks, so
  // 3:1 — on the page and the card.
  ...['rise', 'fall'].flatMap(change => [
    ...Object.entries(PAGE).map(([where, under]) => ({
      name: `${change} badge on ${where}`,
      ink: (token: (name: string) => Rgba) => token(`--${change}`),
      on: (token: (name: string) => Rgba) =>
        over(at(token(`--${change}`), 0.1), under(token)),
      line: TEXT,
    })),
    ...Object.entries({
      page: ground('background'),
      card: ground('card'),
    }).map(([where, under]) => ({
      name: `${change} mark on ${where}`,
      ink: (token: (name: string) => Rgba) => token(`--${change}`),
      on: under,
      line: NON_TEXT,
    })),
  ]),
  // A control's edge (an unticked checkbox is only this), and the focus
  // indicator (the 1px `border-ring`; the halo is emphasis).
  ...['input', 'ring'].flatMap(edge =>
    Object.entries(CONTROL_GROUNDS(mode)).map(([where, under]) => ({
      name: `${edge} edge on ${where}`,
      ink: (token: (name: string) => Rgba) => token(`--${edge}`),
      on: under,
      line: NON_TEXT,
    })),
  ),
  // A checked box, the open view's bar and a drop target are `primary`
  // fills: shapes that carry state, so 3:1 on what is around them.
  ...Object.entries({
    page: ground('background'),
    card: ground('card'),
    sidebar: ground('sidebar'),
  }).map(([where, under]) => ({
    name: `primary fill on ${where}`,
    ink: (token: (name: string) => Rgba) => token('--primary'),
    on: under,
    line: NON_TEXT,
  })),
];

/** One preset in one mode, every pair measured. */
function measure(
  preset: string,
  mode: Mode,
  convention: Convention = 'semantic',
) {
  const tokens = resolveTokens(preset, mode, convention);
  const token = (name: string) => {
    const color = tokens.get(name);
    if (!color) throw new Error(`${name} did not resolve`);
    return color;
  };
  return pairs(mode).map(({ name, ink, on, line }) => ({
    name,
    line,
    ratio: contrast(ink(token), on(token)),
  }));
}

describe('the built-in presets', () => {
  it('are neutral, blue and slate, in that order', () => {
    expect(PRESET_NAMES).toEqual(['neutral', 'blue', 'slate']);
  });

  it('leave neutral to the stylesheet: every variable initial', () => {
    const neutral = presets().get('neutral')!;
    expect(new Set(neutral.values())).toEqual(new Set(['initial']));
  });

  it('give each optional group whole or not at all (D35 Q62)', () => {
    const groups = [
      /^--fve-(dark-)?chart-\d+$/,
      /^--fve-(dark-)?shadow-(sm|md|lg)$/,
      /^--fve-font-sans$/,
    ];
    const neutral = [...presets().get('neutral')!.keys()];
    for (const [name, assigned] of presets())
      for (const group of groups) {
        const whole = neutral.filter(variable => group.test(variable));
        const given = [...assigned.keys()].filter(variable =>
          group.test(variable),
        );
        expect(whole.length, `${group}`).toBeGreaterThan(0);
        expect([[], whole], `${name} ${group}`).toContainEqual(given);
      }
  });

  it("never set a rise or a fall: the convention is the host's", () => {
    for (const assigned of presets().values())
      for (const variable of assigned.keys())
        expect(variable).not.toMatch(/^--fve-(dark-)?(rise|fall)$/);
  });
});

describe.each(PRESET_NAMES)('preset %s', preset => {
  it.each(
    (['light', 'dark'] as const).flatMap(mode =>
      CONVENTIONS.map(convention => [mode, convention] as const),
    ),
  )('clears every line in %s, %s', (mode, convention) => {
    const failing = measure(preset, mode, convention).filter(
      ({ ratio, line }) => ratio < line,
    );
    expect(failing).toEqual([]);
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
      expect(tokens.get('--rise'), convention).toEqual(tokens.get(up));
      expect(tokens.get('--fall'), convention).toEqual(tokens.get(down));
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
