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
 * line each owes: the half of the theme's registry that says what a preset
 * is measured on (theme-architecture.md 5.2).
 *
 * Two suites measure them, each in its own way: the jsdom one by arithmetic
 * over the values the shipped stylesheets resolve to
 * (`test/fixtures/presetPairs.ts`, every preset, mode and change
 * convention), and the browser one by drawing a probe for each and reading
 * what the cascade came to (Storybook's contrast matrix,
 * `stories/view-engine/themeContrast.tsx`). Both expand this list and
 * nothing else, so the two measure the same pairs by construction: before,
 * each wrote its own, and a pair one of them left out — a preset's
 * view-list headings at 4.44:1 — was caught by the other alone.
 *
 * Every pair is written as tokens and the opacity the call site gives them:
 * the selected row is `bg-muted`, a toned badge writes its token on a 10%
 * wash of it (`ToneBadge`), the dark theme gives a control a `bg-input/30`
 * fill.
 */

/** What a pair is, and so which line it owes. */
export type PairKind = 'text' | 'edge' | 'mark';

/**
 * The lines a preset holds, by kind: text at 4.5:1 (WCAG 1.4.3); the edge
 * of a control and the focus indicator (1.4.11), and a mark or a fill that
 * carries state, at 3:1. A preset may promise more (themes.md 5.1); the
 * numbers live here, never in its CSS.
 */
export const LINES: Readonly<Record<PairKind, number>> = {
  text: 4.5,
  edge: 3,
  mark: 3,
};

/** A preset whose promise is higher than the package's, kind by kind. */
export const PRESET_LINES: Readonly<
  Record<string, Partial<Record<PairKind, number>>>
> = {
  // AAA text (1.4.6), and an edge, a focus mark or a filled state that
  // stands off its ground as far as AA text does (themes.md 3.4.5).
  contrast: { text: 7, edge: 4.5, mark: 4.5 },
};

/** The lines one preset owes. */
export function linesOf(preset: string): Record<PairKind, number> {
  return { ...LINES, ...PRESET_LINES[preset] };
}

/** One layer of paint: a token, at a Tailwind opacity modifier. */
export interface Layer {
  readonly token: string;
  readonly alpha?: number;
}

/** What is measured on a ground. */
export interface InkSpec {
  readonly ink: string;
  readonly kind: PairKind;
  /** The ink at an opacity: the view list's headings at 70%. */
  readonly alpha?: number;
  /**
   * The ink laid on a wash of itself at this opacity first: a toned badge,
   * its token on 10% of it.
   */
  readonly wash?: number;
  /**
   * Painted in this mode alone: a call site that writes one ink in light
   * and another under `dark:` (a tab's words).
   */
  readonly mode?: 'light' | 'dark';
}

/**
 * A ground the surface paints, by what the surface calls it: its layers,
 * bottom first, and what is measured on it.
 */
export interface Ground {
  readonly name: string;
  readonly layers: readonly Layer[];
  readonly pairs: readonly InkSpec[];
  /** Painted in dark alone: the `bg-input/30` a control wears there. */
  readonly dark?: true;
  /**
   * Painted only where a theme sets this token: a filled control. The
   * arithmetic measures it then; the browser draws it always, and where the
   * token is unset the fill is nothing and what it measures is the ground
   * under it, which another pair measures already.
   */
  readonly requires?: string;
}

const texts = (...inks: string[]): InkSpec[] =>
  inks.map(ink => ({ ink, kind: 'text' }));

const marks = (...inks: string[]): InkSpec[] =>
  inks.map(ink => ({ ink, kind: 'mark' }));

/** The focusable things' edges: an unticked checkbox, the focus mark. */
const EDGES: InkSpec[] = [
  { ink: 'input', kind: 'edge' },
  { ink: 'ring', kind: 'edge' },
];

/**
 * A status and a change as the words of a badge: each on a 10% wash of
 * itself over the ground a row, a card or a callout can be.
 */
const BADGES: InkSpec[] = [
  'destructive',
  'success',
  'warning',
  'rise',
  'fall',
].map(ink => ({ ink, kind: 'text', wash: 0.1 }) as const);

/**
 * The words of a tab not chosen: on the strip of a tab list, and straight
 * on the ground where the list is a row of underlined tabs (a board's).
 * The registry's trigger wrote `foreground/60`, an opacity no preset was
 * measured at (4.32:1 on a preset's strip); it writes `quiet-foreground`
 * now, and `muted-foreground` under `dark:` as the registry does.
 */
const TAB_WORDS: InkSpec[] = [
  { ink: 'quiet-foreground', kind: 'text', mode: 'light' },
  { ink: 'muted-foreground', kind: 'text', mode: 'dark' },
];

/**
 * The quiet line of a panel: a hint under a control, a chart tile's reason,
 * a group's heading in the chart picker (`quiet-foreground`, which is the
 * foreground at 70% unless a preset says otherwise).
 */
const QUIET = texts('quiet-foreground');

/** A status as the words of a callout. */
const STATUS = texts('destructive', 'success', 'warning');

const one = (token: string): Layer[] => [{ token }];

const washed = (under: string, over: string, alpha: number): Layer[] => [
  { token: under },
  { token: over, alpha },
];

/** Every ground the surface paints and what it paints on each. */
export const GROUNDS: readonly Ground[] = [
  {
    name: 'page',
    layers: one('background'),
    pairs: [
      // A board's tabs are a row of underlined words straight on the
      // ground (`TAB_WORDS`): quiet in light, muted in dark, both here.
      ...texts('foreground', 'muted-foreground'),
      ...QUIET,
      // A link in a cell and the default-view star are `primary`.
      ...texts('primary'),
      ...STATUS,
      ...BADGES,
      // A waterfall's steps, and a checked box or a drop target.
      ...marks('rise', 'fall', 'primary'),
      ...EDGES,
    ],
  },
  {
    // The grouped ground a board and its filter bar stand on.
    name: 'canvas',
    layers: one('canvas'),
    pairs: [
      ...texts('foreground', 'muted-foreground', 'primary'),
      ...QUIET,
      ...STATUS,
      ...BADGES,
      ...EDGES,
    ],
  },
  {
    name: 'card',
    layers: one('card'),
    pairs: [
      ...texts('foreground', 'card-foreground', 'muted-foreground', 'primary'),
      ...QUIET,
      ...STATUS,
      // A failed part's callout: its description in `destructive/90`.
      { ink: 'destructive', kind: 'text', alpha: 0.9 },
      ...BADGES,
      ...marks('rise', 'fall', 'primary'),
      ...EDGES,
    ],
  },
  {
    name: 'popover',
    layers: one('popover'),
    pairs: [
      ...texts(
        'foreground',
        'popover-foreground',
        'muted-foreground',
        'primary',
      ),
      ...QUIET,
      ...STATUS,
      ...BADGES,
      ...EDGES,
    ],
  },
  {
    // The header and summary bands, a selected row, a pressed group. The
    // quiet grey is toned against the page (4.34:1 here in neutral), which
    // is why the band writes in `quiet-foreground`.
    name: 'band',
    layers: one('muted'),
    pairs: [
      ...texts('foreground', 'quiet-foreground', 'primary'),
      ...BADGES,
      ...EDGES,
    ],
  },
  {
    // A hovered row: the muted step mixed halfway into the page.
    name: 'hovered row',
    layers: [{ token: 'background' }, { token: 'row-hover' }],
    pairs: [...texts('foreground', 'primary'), ...BADGES, ...EDGES],
  },
  {
    // The navigation column, and its group headings: the column's ink at
    // 70% (the browser matrix once caught a preset's at 4.44:1, T3).
    name: 'sidebar',
    layers: one('sidebar'),
    pairs: [
      ...texts('sidebar-foreground'),
      { ink: 'sidebar-foreground', kind: 'text', alpha: 0.7 },
      // The chart's options panel stands on it: its hints.
      ...QUIET,
      ...marks('primary'),
      ...EDGES,
    ],
  },
  {
    // The strip of a tab list (`TabsList`'s `bg-muted`): the words of a
    // tab not chosen.
    name: 'tab strip',
    layers: one('muted'),
    pairs: TAB_WORDS,
  },
  {
    name: 'sidebar accent',
    layers: one('sidebar-accent'),
    pairs: texts('sidebar-accent-foreground'),
  },
  // Filled things and their own ink.
  ...['primary', 'secondary', 'accent', 'destructive'].map(fill => ({
    name: fill,
    layers: one(fill),
    pairs: texts(`${fill}-foreground`),
  })),
  // In the dark an outline control wears `bg-input/30`: its own words and
  // its edge land on that wash over each ground a control sits on (axe in
  // the gallery caught a select's value at 3.48:1, T2).
  ...(
    [
      ['card', 'card'],
      ['page', 'background'],
      ['popover', 'popover'],
      ['sidebar', 'sidebar'],
    ] as const
  ).map(([where, token]): Ground => ({
    name: `input wash on ${where}`,
    layers: washed(token, 'input', 0.3),
    // The chart's options name a stage by a placeholder in quiet ink.
    pairs: [...texts('foreground', 'muted-foreground'), ...QUIET, ...EDGES],
    dark: true,
  })),
  // The same wash on the band: a header's select-all box and a selected
  // row's box stand on `muted` (a preset's at 2.91:1 there).
  {
    name: 'input wash on band',
    layers: washed('muted', 'input', 0.3),
    pairs: EDGES,
    dark: true,
  },
  // A theme that draws its controls filled (`control`, `control-thumb`):
  // the words a filter chip and a segmented control write on that fill,
  // and the focus mark that lands on it, over each ground a control stands
  // on. The pressed thumb against its track is a state, not held to 1.4.11
  // (the registry's own pressed `muted` is 1.1:1), so only its words are.
  ...(
    [
      ['page', 'background'],
      ['canvas', 'canvas'],
      ['card', 'card'],
      ['popover', 'popover'],
    ] as const
  ).flatMap(([where, token]): Ground[] => [
    {
      name: `control over ${where}`,
      layers: [{ token }, { token: 'control' }],
      pairs: [
        ...texts('foreground', 'muted-foreground'),
        { ink: 'ring', kind: 'edge' },
      ],
      requires: 'control',
    },
    {
      name: `control thumb over ${where}`,
      layers: [{ token }, { token: 'control-thumb' }],
      pairs: texts('foreground'),
      requires: 'control-thumb',
    },
    // A select on a filter's chip wears its own `bg-input/30` in the dark,
    // on the chip's fill: its value or its placeholder, and its edge.
    {
      name: `input wash on control over ${where}`,
      layers: [{ token }, { token: 'control' }, { token: 'input', alpha: 0.3 }],
      pairs: [...texts('foreground', 'muted-foreground'), ...EDGES],
      dark: true,
      requires: 'control',
    },
  ]),
];

/**
 * A pair a preset is known to fall short on, owed by the batch that retunes
 * it (theme-architecture.md 9). Both suites hold such a pair to still being
 * short, so the entry goes the moment the batch lands; nothing else is
 * excused, and a new shortfall is a failure.
 */
export interface PendingPair {
  readonly preset: string;
  readonly mode: 'light' | 'dark';
  /** The pair's name, as `contrastPairs` files it. */
  readonly pair: string;
  /** The retuning batch that owes it. */
  readonly batch: string;
}

/**
 * The shortfalls found when the band's and the sidebar's dark control wash
 * joined the registry (2026-09-25, the porcelain default): the select-all
 * box on a header band, and a placeholder in the chart's options.
 */
export const PENDING: readonly PendingPair[] = [
  {
    preset: 'azure',
    mode: 'dark',
    pair: 'input edge on input wash on band',
    batch: 'S8',
  },
  {
    preset: 'contrast',
    mode: 'dark',
    pair: 'input edge on input wash on band',
    batch: 'S11',
  },
  {
    preset: 'contrast',
    mode: 'dark',
    pair: 'ring edge on input wash on band',
    batch: 'S11',
  },
  {
    preset: 'contrast',
    mode: 'dark',
    pair: 'muted-foreground text on input wash on sidebar',
    batch: 'S11',
  },
  {
    preset: 'contrast',
    mode: 'dark',
    pair: 'quiet-foreground text on input wash on sidebar',
    batch: 'S11',
  },
];

/** Whether one preset's pair in one mode is a known, owed shortfall. */
export function isPending(
  preset: string,
  mode: 'light' | 'dark',
  pair: string,
): boolean {
  return PENDING.some(
    entry =>
      entry.preset === preset && entry.mode === mode && entry.pair === pair,
  );
}

/** One pair, expanded: the ink and its ground as layers of tokens. */
export interface ContrastPair {
  /** How the pair reads, and the key its measurement is filed under. */
  readonly name: string;
  readonly kind: PairKind;
  readonly ink: Layer;
  /** Bottom first; a badge's wash is the last. */
  readonly ground: readonly Layer[];
  readonly requires?: string;
}

/** Every pair a preset is measured on in one mode, in `GROUNDS`' order. */
export function contrastPairs(mode: 'light' | 'dark'): ContrastPair[] {
  return GROUNDS.filter(ground => mode === 'dark' || !ground.dark).flatMap(
    ({ name: where, layers, pairs, requires }) =>
      pairs
        .filter(spec => spec.mode === undefined || spec.mode === mode)
        .map(({ ink, kind, alpha, wash }): ContrastPair => {
          const what =
            wash !== undefined
              ? 'badge'
              : `${alpha === undefined ? '' : `${Math.round(alpha * 100)}% `}${kind}`;
          return {
            name: `${ink} ${what} on ${where}`,
            kind,
            ink: alpha === undefined ? { token: ink } : { token: ink, alpha },
            ground:
              wash === undefined
                ? layers
                : [...layers, { token: ink, alpha: wash }],
            ...(requires ? { requires } : {}),
          };
        }),
  );
}
