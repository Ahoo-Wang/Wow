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
 * The theme a chart is drawn in, as concrete colours.
 *
 * The stylesheet stays the one source of truth (`--chart-1..8`,
 * `--foreground`, `--muted-foreground`, `--border`, and whatever a host
 * sets through `--fve-*`); this is that cascade read back off the chart's
 * own element. The option handed to the library never holds a `var()`:
 * the library derives a hovered mark's colour and a label's contrast by
 * parsing the colour it was given, and a custom property parses as nothing
 * (echarts#16044, #19743). Nor an `oklch()`, which the theme is written in
 * and the library's parser does not read — so every colour is converted to
 * `rgb()` here.
 */
export interface ChartTheme {
  /** The eight slots, in the order the stylesheet validated them in. */
  palette: readonly string[];
  /** Text written over the plot: a value label, a total. */
  foreground: string;
  /** Ticks, axis titles and the grey of a pie's "Other". */
  muted: string;
  /** Gridlines and axis rules. */
  border: string;
  /** What the chart stands on, drawn around a value label as its halo. */
  ground: string;
  fontFamily: string;
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

/**
 * The host's pin on patterns over a chart's colours: `on`, `off`, or unset
 * to follow the reader's system (`patternsPinned`). Read off the chart's
 * element like every colour, so a host sets it on any ancestor.
 */
export const PATTERNS_TOKEN = '--fve-chart-patterns';

/**
 * Every token a chart reads, the slots first. `ViewSurface` watches the same
 * list to tell a chart that the theme under it moved (`useSurfaceTokens`),
 * so what is read and what is watched cannot part.
 */
export const CHART_TOKENS: readonly string[] = [
  ...Array.from(
    { length: CHART_COLOR_SLOTS },
    (_, index) => `--chart-${index + 1}`,
  ),
  '--foreground',
  '--muted-foreground',
  '--border',
  // A waterfall's rise and fall, read through `resolve`: a direction, not
  // a verdict, so the change convention decides them (themes.md 2.6).
  '--rise',
  '--fall',
  PATTERNS_TOKEN,
];

/**
 * The attributes, on the surface or an ancestor, that can move what those
 * tokens resolve to: `class` (`.dark`, and any class a host themes by),
 * `data-theme` (a pinned mode), `data-fve-preset` (a preset),
 * `data-fve-change-colors` (the change convention, `--rise` / `--fall`) and
 * `style`, where a host may set `--fve-*` inline. `ViewSurface` observes
 * these and nothing else, so a chart is told of every change to what it
 * reads; kept beside `CHART_TOKENS` so the two lists cannot part.
 */
export const THEME_ATTRIBUTES: readonly string[] = [
  'class',
  'data-theme',
  'data-fve-preset',
  'data-fve-change-colors',
  // Colours do not move with it, but a chart's cell does; the resize
  // observer answers that, and the tokens are read again for free.
  'data-fve-density',
  'style',
];

/**
 * The light theme's values, for where nothing can be read: jsdom resolves no
 * custom property, and a chart outside any stylesheet still has to draw in
 * something. They are the stylesheet's light tokens, converted — a second
 * spelling of `styles.css`, so `test/chartTheme.test.tsx` reads the light
 * token block and holds every value here to it. Before that test the first
 * slot had drifted: it was still the step the palette was tuned away from.
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
  border: 'rgb(229, 229, 229)',
  ground: 'rgb(255, 255, 255)',
  fontFamily: 'sans-serif',
} as const;

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

/**
 * A token's colour as the browser computes it.
 *
 * `getPropertyValue('--x')` hands back an unregistered custom property as
 * text, its `var()`s substituted but nothing else worked out — so a token a
 * preset derives (`color-mix()`, `oklch(from var(--fve-brand) …)`) comes back
 * as an expression the colour parser cannot read, and the chart would fall
 * back to the built-in colours without a word (themes.md 2.5). A hidden
 * probe under the chart's element takes the token as a real colour
 * property, and the browser resolves it. The property is `background-color`
 * because it does not inherit: a token that is no colour at all leaves the
 * probe transparent rather than handing it the parent's. No `@property`
 * registration instead: `--primary` and its kind are also names in a host's
 * own shadcn theme, and registering them would change the host's.
 */
function probed(element: Element, name: string): string | undefined {
  const probe = element.ownerDocument.createElement('span');
  probe.hidden = true;
  probe.style.setProperty('background-color', `var(${name})`);
  element.append(probe);
  try {
    const color = concreteColor(getComputedStyle(probe).backgroundColor);
    return color && !TRANSPARENT.test(color) ? color : undefined;
  } finally {
    probe.remove();
  }
}

export function readChartTheme(element: Element): ChartTheme {
  const style = getComputedStyle(element);
  const token = (name: string) => {
    const text = style.getPropertyValue(name).trim();
    if (!text) return undefined;
    return concreteColor(text) ?? probed(element, name);
  };
  const palette = CHART_TOKENS.slice(0, CHART_COLOR_SLOTS).map(
    (name, index) => token(name) ?? FALLBACK.palette[index],
  );
  const read = {
    palette,
    foreground: token('--foreground') ?? FALLBACK.foreground,
    muted: token('--muted-foreground') ?? FALLBACK.muted,
    border: token('--border') ?? FALLBACK.border,
    ground: groundOf(element) ?? FALLBACK.ground,
    fontFamily: style.fontFamily || FALLBACK.fontFamily,
    patterns: patternsPinned(style.getPropertyValue(PATTERNS_TOKEN)),
  };
  const resolved = new Map<string, string>();
  return {
    ...read,
    // A rise and a fall are read through `resolve` (a waterfall's steps),
    // so a host restyling only them — or naming another convention — is a
    // new theme too.
    key: JSON.stringify([read, token('--rise'), token('--fall')]),
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
