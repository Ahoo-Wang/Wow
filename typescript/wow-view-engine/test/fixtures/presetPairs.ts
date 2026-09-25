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
 * The pairs the surface paints — ink on ground, edge on ground — and the
 * line each owes, measured on one preset in one mode and change convention
 * (`test/presetContrast.test.ts`, which holds every preset to them).
 *
 * Every pair is written as tokens and the opacity the call site gives them:
 * the selected row is `bg-muted`, a toned badge writes its token on a 10%
 * wash of it (`ToneBadge`), the dark theme gives a control a `bg-input/30`
 * fill.
 */

import {
  at,
  contrast,
  type Convention,
  type Mode,
  over,
  resolveTokens,
  type Rgba,
} from './themeTokens';

/** What a pair is, and so which line it owes. */
export type PairKind = 'text' | 'edge' | 'mark';

/**
 * The lines a preset holds, by kind: text at 4.5:1 (WCAG 1.4.3); the edge
 * of a control and the focus indicator (1.4.11), and a mark or a fill that
 * carries state, at 3:1. A preset may promise more (themes.md 5.1); the
 * numbers live here, never in its CSS.
 */
export const LINES: Record<PairKind, number> = { text: 4.5, edge: 3, mark: 3 };

/** A preset whose promise is higher than the package's, kind by kind. */
export const PRESET_LINES: Record<
  string,
  Partial<Record<PairKind, number>>
> = {};

/** The lines one preset owes. */
export function linesOf(preset: string): Record<PairKind, number> {
  return { ...LINES, ...PRESET_LINES[preset] };
}

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
 * the navigation column (a view in the list takes the focus there), plus — in the dark, where outline controls carry
 * `bg-input/30` — that wash over the card, the page and a popup.
 */
const CONTROL_GROUNDS = (mode: Mode): Record<string, Ground> => {
  const grounds: Record<string, Ground> = {
    ...PAGE,
    sidebar: ground('sidebar'),
  };
  return mode === 'light'
    ? grounds
    : {
        ...grounds,
        'input wash on card': token =>
          over(at(token('--input'), 0.3), token('--card')),
        'input wash on page': token =>
          over(at(token('--input'), 0.3), token('--background')),
        'input wash on popover': token =>
          over(at(token('--input'), 0.3), token('--popover')),
      };
};

interface Pair {
  name: string;
  ink: (token: (name: string) => Rgba) => Rgba;
  on: Ground;
  kind: PairKind;
}

const text = (ink: string, on: Record<string, Ground>): Pair[] =>
  Object.entries(on).map(([where, ground]) => ({
    name: `${ink} text on ${where}`,
    ink: token => token(`--${ink}`),
    on: ground,
    kind: 'text',
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
  // A control's own text — a select's value, a placeholder — on the
  // `input/30` wash the dark theme gives it, over each ground a control
  // sits on (axe in the gallery caught it at 3.48:1, T2).
  ...(mode === 'dark'
    ? ['foreground', 'muted-foreground'].flatMap(ink =>
        text(ink, {
          'input wash on card': token =>
            over(at(token('--input'), 0.3), token('--card')),
          'input wash on page': token =>
            over(at(token('--input'), 0.3), token('--background')),
          'input wash on popover': token =>
            over(at(token('--input'), 0.3), token('--popover')),
        }),
      )
    : []),
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
    Object.entries(PAGE).map(([where, under]): Pair => ({
      name: `${status} badge on ${where}`,
      ink: token => token(`--${status}`),
      on: token => over(at(token(`--${status}`), 0.1), under(token)),
      kind: 'text',
    })),
  ),
  // A change by its direction (themes.md 2.6): a rise and a fall as the
  // words of a metric card's badge, on a 10% wash of themselves over each
  // ground a card or a row can be, and as a waterfall's bars — marks — on
  // the page and the card.
  ...['rise', 'fall'].flatMap(change => [
    ...Object.entries(PAGE).map(([where, under]): Pair => ({
      name: `${change} badge on ${where}`,
      ink: token => token(`--${change}`),
      on: token => over(at(token(`--${change}`), 0.1), under(token)),
      kind: 'text',
    })),
    ...Object.entries({
      page: ground('background'),
      card: ground('card'),
    }).map(([where, under]): Pair => ({
      name: `${change} mark on ${where}`,
      ink: token => token(`--${change}`),
      on: under,
      kind: 'mark',
    })),
  ]),
  // A control's edge (an unticked checkbox is only this), and the focus
  // indicator (the 1px `border-ring`; the halo is emphasis).
  ...['input', 'ring'].flatMap(edge =>
    Object.entries(CONTROL_GROUNDS(mode)).map(([where, under]): Pair => ({
      name: `${edge} edge on ${where}`,
      ink: token => token(`--${edge}`),
      on: under,
      kind: 'edge',
    })),
  ),
  // A checked box, the open view's bar and a drop target are `primary`
  // fills: shapes that carry state, so a mark's line on what is around them.
  ...Object.entries({
    page: ground('background'),
    card: ground('card'),
    sidebar: ground('sidebar'),
  }).map(([where, under]): Pair => ({
    name: `primary fill on ${where}`,
    ink: token => token('--primary'),
    on: under,
    kind: 'mark',
  })),
];

/** One measured pair: what it is, the line it owes and what it reads. */
export interface Measured {
  name: string;
  kind: PairKind;
  line: number;
  ratio: number;
}

/** One preset in one mode, every pair measured against that preset's lines. */
export function measure(
  preset: string,
  mode: Mode,
  convention: Convention = 'semantic',
): Measured[] {
  const tokens = resolveTokens(preset, mode, convention);
  const token = (name: string) => {
    const color = tokens.get(name);
    if (!color) throw new Error(`${name} did not resolve`);
    return color;
  };
  const lines = linesOf(preset);
  return pairs(mode).map(({ name, ink, on, kind }) => ({
    name,
    kind,
    line: lines[kind],
    ratio: contrast(ink(token), on(token)),
  }));
}
