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

import {
  formatRgb,
  interpolate,
  modeHsl,
  modeLab,
  modeLch,
  modeLrgb,
  modeOklab,
  modeOklch,
  modeP3,
  modeRgb,
  parse,
  wcagContrast,
  // Registers a colour space with the parser; not a React hook.
  useMode as registerMode,
} from 'culori/fn';
import { CHART_COLOR_SLOTS } from '../../model/index.js';
import { CHART_TOKENS } from '../theme/tokens.js';
import { patternsPinned } from './patterns.js';

registerMode(modeRgb);
registerMode(modeHsl);
registerMode(modeLab);
registerMode(modeLch);
registerMode(modeLrgb);
registerMode(modeOklab);
registerMode(modeOklch);
registerMode(modeP3);

/**
 * The theme a chart is drawn in: its whole look, as concrete values.
 *
 * The stylesheet stays the one source of truth (`--chart-1..8`,
 * `--foreground`, the chart's roles `--_fve-chart-*`, and whatever a host
 * or a preset sets through `--fve-*` / `--fvp-*`); this is that cascade read
 * back off the chart's own element (theme-architecture.md 6). The option
 * handed to the library never holds a `var()`: the library derives a
 * hovered mark's colour and a label's contrast by parsing the colour it was
 * given, and a custom property parses as nothing (echarts#16044, #19743).
 * Nor an `oklch()`, which the theme is written in and the library's parser
 * does not read — so every colour is converted to `rgb()` here — nor a
 * `calc()`: every length is the browser's pixels, every number a number.
 * An option builder reads its look from here and writes no size, width or
 * corner of its own (`test/chartTheme.test.tsx` walks their source).
 */
export interface ChartTheme {
  /** The eight slots, in the order the stylesheet validated them in. */
  palette: readonly string[];
  /** Text written over the plot: a value label, a total. */
  foreground: string;
  /**
   * The quiet grey of what is no series: a pie's "Other", a reference
   * line, a target band, a gauge's track, the zoom's shade.
   */
  muted: string;
  /** What the chart stands on, drawn around a value label as its halo. */
  ground: string;
  /** Gridlines and axis rules: their colour and width (`chart-grid`). */
  grid: { color: string; width: number };
  /**
   * The quiet text: ticks, axis titles, a scale's ends, the names written
   * beside the marks (`chart-axis`).
   */
  axis: { color: string };
  /**
   * The type: the surface's family, the chart's text size and a value
   * label's, a step under it (`chart-text-size`, `chart-label-size`).
   */
  text: { family: string; size: number; labelSize: number };
  /** A line's width, and how opaque an area under it is filled. */
  line: { width: number; areaOpacity: number };
  /**
   * A bar's end corner and its width's bounds; no `minWidth` is no lower
   * bound (`chart-bar-*`).
   */
  bar: { radius: number; minWidth?: number; maxWidth: number };
  /** The seam of the ground between two slices (`chart-slice-border`). */
  slice: { border: number };
  /**
   * Patterns over the series' colours, as the host pinned them with
   * `--fve-chart-patterns: on | off`; `undefined` follows the reader's
   * system (`usePatterns`, D33 Q57).
   */
  patterns?: boolean;
  /** A colour the spec pinned — a `var(--slot)` or any CSS colour — made concrete. */
  resolve(color: string): string;
  /** Equal for two readings of the same theme. */
  key: string;
}

/** The chart's text at its size, the option's `textStyle`. */
export function chartText(theme: ChartTheme) {
  return { fontFamily: theme.text.family, fontSize: theme.text.size };
}

/**
 * The pin on patterns over a chart's colours: `on`, `off`, or unset to
 * follow the reader's system (`patternsPinned`), read in the stylesheet's
 * order — the host's `--fve-chart-patterns` first, then a preset's
 * `--fvp-chart-patterns` (theme-architecture.md 3, S2). Read off the chart's
 * element like every colour, so either is set on any ancestor.
 */
export const PATTERNS_TOKENS = [
  '--fve-chart-patterns',
  '--fvp-chart-patterns',
] as const;

/** The first layer that says anything, as `var()` falls back. */
function patternsPin(style: CSSStyleDeclaration): string {
  for (const name of PATTERNS_TOKENS) {
    const value = style.getPropertyValue(name).trim();
    if (value) return value;
  }
  return '';
}

/**
 * Every token a chart reads, the slots first, and the attributes that can
 * move what they resolve to — both the theme registry's
 * (`../theme/tokens.ts`), which the README's list of what redraws a chart
 * is written from too. `ViewSurface` watches the same tokens to tell a
 * chart that the theme under it moved (`useSurfaceTokens`), and observes
 * those attributes and nothing else, so what is read and what is watched
 * cannot part.
 */
export { CHART_TOKENS, THEME_ATTRIBUTES } from '../theme/tokens.js';

/**
 * The light theme's look, for where nothing can be read: jsdom resolves no
 * custom property, and a chart outside any stylesheet still has to draw in
 * something. They are the stylesheet's light tokens and the chart roles'
 * built-in values, made concrete — a second spelling of `styles.css`, so
 * `test/chartTheme.test.tsx` reads the light token block and holds every
 * value here to it. Before that test the first slot had drifted: it was
 * still the step the palette was tuned away from.
 */
export const CHART_FALLBACK = {
  palette: [
    'rgb(38, 117, 211)',
    'rgb(235, 104, 52)',
    'rgb(27, 175, 122)',
    'rgb(237, 161, 0)',
    'rgb(232, 123, 164)',
    'rgb(0, 131, 0)',
    'rgb(74, 58, 167)',
    'rgb(227, 73, 72)',
  ],
  foreground: 'rgb(10, 10, 10)',
  muted: 'rgb(115, 115, 115)',
  ground: 'rgb(255, 255, 255)',
  grid: { color: 'rgb(229, 229, 229)', width: 1 },
  axis: { color: 'rgb(115, 115, 115)' },
  text: { family: 'sans-serif', size: 12, labelSize: 11 },
  line: { width: 2, areaOpacity: 0.2 },
  bar: { radius: 2, maxWidth: 80 },
  slice: { border: 1 },
} as const satisfies Omit<ChartTheme, 'resolve' | 'key'>;

const FALLBACK = CHART_FALLBACK;

/** A theme slot as the palette names it. */
const VARIABLE = /^var\((--[\w-]+)\)$/;

/** Any CSS colour as `rgb()`/`rgba()`; `undefined` when it is none. */
export function concreteColor(text: string | undefined): string | undefined {
  const value = text?.trim();
  if (!value) return undefined;
  const color = parse(value);
  return color && formatRgb(color);
}

/**
 * `color` at `strength` over `ground`, as the one opaque colour the eye gets
 * there: what painting it at that opacity on that ground shows, worked out
 * before the library sees it. A scale drawn apart from the marks — a
 * heatmap's colour bar — then reads the same colours the marks show, on
 * whatever the ground is; handed the opacity instead, the library drew the
 * bar's pale end against a ground of its own, bright on a dark page where
 * the cells were near black (audit P1-9). Mixed in `rgb`, as the opacity
 * composites. A colour that does not parse is given back as it came.
 */
export function mixColor(
  ground: string,
  color: string,
  strength: number,
): string {
  const under = parse(ground);
  const over = parse(color);
  if (!under || !over) return color;
  return formatRgb(interpolate([under, over], 'rgb')(strength));
}

/**
 * How far a mark under the pointer moves toward the ink. The library's own
 * hover lifts a colour toward white, which on a light page makes the one
 * bar being read the palest thing on the plot — it looked disabled
 * (2026-09-23 audit). Toward the ink is darker on a light page and lighter
 * on a dark one: the mark gains contrast against its ground either way.
 */
const EMPHASIS = 0.2;

/**
 * A mark's colour while the pointer is on it: `fill` a step toward the
 * foreground (`EMPHASIS`), so emphasis reads as more, never as less.
 */
export function emphasized(theme: ChartTheme, fill: string): string {
  return mixColor(fill, theme.foreground, EMPHASIS);
}

/**
 * The ink a label written on `fill` wears: the foreground or the ground,
 * whichever stands further from it (WCAG contrast). A number inside a
 * stacked bar's segment or on a heatmap's cell sits on the mark rather
 * than on the page, and one colour for all of them wrote dark digits on
 * the deep end of the scale, with a halo that only blurred them (2026-09-23
 * audit). The mark decides, so a label is legible on every shade in either
 * mode, and needs no halo.
 */
export function inkOn(theme: ChartTheme, fill: string): string {
  const under = parse(fill);
  if (!under) return theme.foreground;
  const dark = parse(theme.foreground);
  const light = parse(theme.ground);
  if (!dark || !light) return theme.foreground;
  return wcagContrast(under, dark) >= wcagContrast(under, light)
    ? theme.foreground
    : theme.ground;
}

/** The colour of nothing, as `concreteColor` writes it. */
const TRANSPARENT = /^rgba\(.*,\s*0\)$/;

/** What a token is, and so which property a probe computes it through. */
type Measured = 'color' | 'length' | 'number';

/**
 * A token as the browser computes it.
 *
 * `getPropertyValue('--x')` hands back an unregistered custom property as
 * text, its `var()`s substituted but nothing else worked out — so a token a
 * preset derives (`color-mix()`, `oklch(from var(--fve-brand) …)`, a
 * `calc()` of `--radius`) comes back as an expression we would have to
 * parse, and the chart would fall back to the built-in look without a word
 * (themes.md 2.5). A hidden probe under the chart's element takes the token
 * as a real property, and the browser resolves it: a colour as
 * `background-color`, a length as `width`, a number as a width of that many
 * pixels (`calc(n * 1px)`) — each a property that does not inherit, so a
 * token that is no such value at all leaves the probe at its initial value
 * (transparent, `auto`) rather than handing it the parent's, and reads as
 * nothing. Not `opacity` for a number, which the doc first named: it clamps
 * to 0–1 and its initial 1 cannot be told from a token that computed to 1.
 * No `@property` registration instead: `--primary` and its kind are also
 * names in a host's own shadcn theme, and registering them would change the
 * host's.
 */
function probed(
  element: Element,
  name: string,
  kind: Measured,
): string | undefined {
  const probe = element.ownerDocument.createElement('span');
  probe.hidden = true;
  if (kind === 'color')
    probe.style.setProperty('background-color', `var(${name})`);
  else
    probe.style.setProperty(
      'width',
      kind === 'length' ? `var(${name})` : `calc(var(${name}) * 1px)`,
    );
  element.append(probe);
  try {
    const computed = getComputedStyle(probe);
    if (kind === 'color') {
      const color = concreteColor(computed.backgroundColor);
      return color && !TRANSPARENT.test(color) ? color : undefined;
    }
    return pixels(computed.width) === undefined ? undefined : computed.width;
  } finally {
    probe.remove();
  }
}

/** A length in pixels — `12px`, `0.2px`, `0` — or `undefined`. */
function pixels(text: string | undefined): number | undefined {
  const match = /^(-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?)(px)?$/i.exec(
    text?.trim() ?? '',
  );
  if (!match || (!match[2] && Number(match[1]) !== 0)) return undefined;
  const value = Number(match[1]);
  return Number.isFinite(value) ? value : undefined;
}

/** A plain number — `0.2`, `3` — or `undefined`. */
function plainNumber(text: string | undefined): number | undefined {
  const value = text?.trim();
  if (!value || !/^-?(?:\d+\.?\d*|\.\d+)$/.test(value)) return undefined;
  return Number(value);
}

/**
 * The chart's roles (theme-architecture.md 6): the variable each is read
 * through — the one the stylesheet declares, `--_fve-<role>` — and how.
 */
const ROLE = {
  grid: '--_fve-chart-grid',
  gridWidth: '--_fve-chart-grid-width',
  axis: '--_fve-chart-axis',
  textSize: '--_fve-chart-text-size',
  labelSize: '--_fve-chart-label-size',
  lineWidth: '--_fve-chart-line-width',
  areaOpacity: '--_fve-chart-area-opacity',
  barRadius: '--_fve-chart-bar-radius',
  barMinWidth: '--_fve-chart-bar-min-width',
  barMaxWidth: '--_fve-chart-bar-max-width',
  sliceBorder: '--_fve-chart-slice-border',
} as const;

export function readChartTheme(element: Element): ChartTheme {
  const style = getComputedStyle(element);
  const text = (name: string) => style.getPropertyValue(name).trim();
  const token = (name: string) => {
    const value = text(name);
    if (!value) return undefined;
    return concreteColor(value) ?? probed(element, name, 'color');
  };
  // A length or a number written plainly is read as written; anything the
  // browser has to work out — a `calc()`, a `min()`, `rem` — is probed.
  const length = (name: string) => {
    const value = text(name);
    if (!value) return undefined;
    return pixels(value) ?? pixels(probed(element, name, 'length'));
  };
  const number = (name: string) => {
    const value = text(name);
    if (!value) return undefined;
    return plainNumber(value) ?? pixels(probed(element, name, 'number'));
  };
  const palette = CHART_TOKENS.slice(0, CHART_COLOR_SLOTS).map(
    (name, index) => token(name) ?? FALLBACK.palette[index],
  );
  /** A length no less than `floor`, else the built-in one. */
  const least = (name: string, fallback: number, floor = 0) => {
    const value = length(name);
    return value !== undefined && value >= floor ? value : fallback;
  };
  const minWidth = length(ROLE.barMinWidth);
  const opacity = number(ROLE.areaOpacity);
  const read = {
    palette,
    foreground: token('--foreground') ?? FALLBACK.foreground,
    muted: token('--muted-foreground') ?? FALLBACK.muted,
    ground: groundOf(element) ?? FALLBACK.ground,
    grid: {
      color: token(ROLE.grid) ?? FALLBACK.grid.color,
      width: least(ROLE.gridWidth, FALLBACK.grid.width),
    },
    axis: { color: token(ROLE.axis) ?? FALLBACK.axis.color },
    text: {
      family: style.fontFamily || FALLBACK.text.family,
      // A size of nothing draws no text at all: a size is a pixel at least.
      size: least(ROLE.textSize, FALLBACK.text.size, 1),
      labelSize: least(ROLE.labelSize, FALLBACK.text.labelSize, 1),
    },
    line: {
      width: least(ROLE.lineWidth, FALLBACK.line.width),
      areaOpacity:
        opacity !== undefined && opacity >= 0 && opacity <= 1
          ? opacity
          : FALLBACK.line.areaOpacity,
    },
    bar: {
      radius: least(ROLE.barRadius, FALLBACK.bar.radius),
      ...(minWidth !== undefined && minWidth > 0 ? { minWidth } : {}),
      maxWidth: least(ROLE.barMaxWidth, FALLBACK.bar.maxWidth, 1),
    },
    slice: { border: least(ROLE.sliceBorder, FALLBACK.slice.border) },
    patterns: patternsPinned(patternsPin(style)),
  };
  const resolved = new Map<string, string>();
  return {
    ...read,
    // A rise and a fall are read through `resolve` (a waterfall's steps),
    // and so are the tones' colours (a toned category's slice), so a host
    // restyling only them — or naming another convention — is a new theme
    // too.
    key: JSON.stringify([
      read,
      ...[
        '--_fve-rise',
        '--_fve-fall',
        '--success',
        '--warning',
        '--destructive',
      ].map(name => token(name)),
    ]),
    resolve(color) {
      let found = resolved.get(color);
      if (found === undefined) {
        const variable = VARIABLE.exec(color.trim())?.[1];
        const slot = /^--chart-(\d+)$/.exec(variable ?? '')?.[1];
        found =
          (slot && palette[(Number(slot) - 1) % palette.length]) ||
          (variable ? token(variable) : concreteColor(color)) ||
          palette[0];
        resolved.set(color, found);
      }
      return found;
    },
  };
}

/**
 * The paint under the chart: the first ancestor that has any. A value
 * label is drawn with a halo of it, so a label crossing a gridline or the
 * top of a neighbouring bar stays legible — the halo has to be the ground
 * the label stands on, which is the card in a panel and the page in a
 * workbench.
 */
function groundOf(element: Element): string | undefined {
  for (let node: Element | null = element; node; node = node.parentElement) {
    const color = concreteColor(getComputedStyle(node).backgroundColor);
    if (color && !TRANSPARENT.test(color)) return color;
  }
  return undefined;
}
